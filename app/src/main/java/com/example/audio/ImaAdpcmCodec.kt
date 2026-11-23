package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Self-contained 4-bit IMA-ADPCM speech codec tailored for low-bandwidth BLE GATT mesh audio.
 * Compresses 8 kHz 16-bit mono PCM (16 KB/s) by 4:1 down to 4 KB/s (~20 KB raw / ~12 KB zlib per 5s clip),
 * eliminating MediaRecorder MP4/AAC header fragmentation and MediaPlayer Looper deadlocks.
 */
object ImaAdpcmCodec {

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte())
    private const val HEADER_SIZE = 16 // magic(4) + sampleRate(4) + sampleCount(4) + predictor(2) + stepIndex(2)
    const val DEFAULT_SAMPLE_RATE = 8000

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun isRmadFormat(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE &&
            bytes[0] == MAGIC[0] &&
            bytes[1] == MAGIC[1] &&
            bytes[2] == MAGIC[2] &&
            bytes[3] == MAGIC[3]

    fun encode(pcmSamples: ShortArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        if (pcmSamples.isEmpty()) return ByteArray(0)
        val nibbleByteCount = (pcmSamples.size + 1) / 2
        val out = ByteArray(HEADER_SIZE + nibbleByteCount)

        var predictor = pcmSamples[0].toInt()
        var stepIndex = 0

        val header = ByteBuffer.wrap(out, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putInt(sampleRate)
        header.putInt(pcmSamples.size)
        header.putShort(predictor.toShort())
        header.putShort(stepIndex.toShort())

        var outIdx = HEADER_SIZE
        var lowNibble = true
        var currentByte = 0

        for (i in 1 until pcmSamples.size) {
            val sample = pcmSamples[i].toInt()
            var diff = sample - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var step = STEP_TABLE[stepIndex]
            var delta = step shr 3
            if (diff >= step) {
                code = code or 4
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                code = code or 2
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                code = code or 1
                delta += step
            }

            predictor = if ((code and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.lastIndex)

            if (lowNibble) {
                currentByte = code and 0x0F
                lowNibble = false
            } else {
                currentByte = currentByte or ((code and 0x0F) shl 4)
                out[outIdx++] = currentByte.toByte()
                lowNibble = true
            }
        }
        if (!lowNibble && outIdx < out.size) {
            out[outIdx] = currentByte.toByte()
        }
        return out
    }

    data class DecodedPcm(val sampleRate: Int, val samples: ShortArray)

    fun decode(rmadBytes: ByteArray): DecodedPcm? {
        if (!isRmadFormat(rmadBytes)) return null
        return try {
            val header = ByteBuffer.wrap(rmadBytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.position(4)
            val sampleRate = header.int.coerceIn(4000, 48000)
            val sampleCount = header.int.coerceIn(0, 480_000)
            if (sampleCount == 0) return DecodedPcm(sampleRate, ShortArray(0))
            var predictor = header.short.toInt()
            var stepIndex = header.short.toInt().coerceIn(0, STEP_TABLE.lastIndex)

            val out = ShortArray(sampleCount)
            out[0] = predictor.toShort()

            var byteOffset = HEADER_SIZE
            var lowNibble = true
            var currentByte = 0

            for (i in 1 until sampleCount) {
                val code = if (lowNibble) {
                    if (byteOffset >= rmadBytes.size) break
                    currentByte = rmadBytes[byteOffset++].toInt() and 0xFF
                    lowNibble = false
                    currentByte and 0x0F
                } else {
                    lowNibble = true
                    (currentByte shr 4) and 0x0F
                }

                val step = STEP_TABLE[stepIndex]
                var delta = step shr 3
                if ((code and 4) != 0) delta += step
                if ((code and 2) != 0) delta += step shr 1
                if ((code and 1) != 0) delta += step shr 2
                predictor = if ((code and 8) != 0) predictor - delta else predictor + delta
                predictor = predictor.coerceIn(-32768, 32767)
                stepIndex = (stepIndex + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.lastIndex)
                out[i] = predictor.toShort()
            }
            DecodedPcm(sampleRate, out)
        } catch (_: Exception) {
            null
        }
    }

    fun synthesizeFallbackBeacon(durationMs: Long = 1000L, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        val count = ((sampleRate * durationMs.coerceIn(400L, 5000L)) / 1000L).toInt().coerceAtLeast(800)
        val pcm = ShortArray(count)
        for (i in 0 until count) {
            // Subtle two-tone distress chirp
            val freq = if ((i / (sampleRate / 4)) % 2 == 0) 520.0 else 660.0
            pcm[i] = (sin(2.0 * PI * freq * i / sampleRate) * 4200.0).toInt().toShort()
        }
        return encode(pcm, sampleRate)
    }
}
