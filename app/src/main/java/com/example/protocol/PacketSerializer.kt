package com.example.protocol

import com.example.domain.model.EmergencyPacket
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.security.RescueCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Bitchat-inspired compact binary emergency packet protocol with:
 * - Fixed compact binary header (version, flags, type, priority, hop counts, sequence, timestamps, IDs)
 * - Optional zlib compression for payloads
 * - SHA-256 payload integrity check
 * - Ed25519 sender signature verification over immutable packet fields + payloadHash
 * - MTU-aware fragmentation & reassembly for BLE GATT characteristics
 */
object PacketSerializer {

    const val PROTOCOL_VERSION: Byte = 2
    private const val FLAG_COMPRESSED: Byte = 0x01
    private const val FLAG_ENCRYPTED: Byte = 0x02

    private const val MAX_PACKET_BYTES = 64 * 1024 // 64 KB hard safety cap against malformed floods

    fun serialize(packet: EmergencyPacket): ByteArray {
        val compressed = compressIfSmaller(packet.payload)
        val useCompressed = compressed.size < packet.payload.size
        val wirePayload = if (useCompressed) compressed else packet.payload
        var flags: Byte = 0
        if (useCompressed) flags = (flags.toInt() or FLAG_COMPRESSED.toInt()).toByte()
        if (packet.isEncryptedPayload) flags = (flags.toInt() or FLAG_ENCRYPTED.toInt()).toByte()

        val packetIdBytes = packet.packetId.toByteArray(StandardCharsets.UTF_8)
        val sessionIdBytes = packet.sessionId.toByteArray(StandardCharsets.UTF_8)
        val senderIdBytes = packet.senderId.toByteArray(StandardCharsets.UTF_8)
        val recipientIdBytes = packet.recipientId.toByteArray(StandardCharsets.UTF_8)

        require(packet.payloadHash.size == 32) { "Payload hash must be 32 bytes" }
        require(packet.authTag.size == 64) { "Ed25519 signature must be 64 bytes" }
        require(packet.senderPublicKeyEd25519.size == 32) { "Sender Ed25519 public key must be 32 bytes" }

        val out = ByteArrayOutputStream()
        // Header: version(1) + flags(1) + type(1) + priority(1) + hopCount(1) + maxHopCount(1)
        out.write(PROTOCOL_VERSION.toInt())
        out.write(flags.toInt())
        out.write(packet.type.code.toInt())
        out.write(packet.priority.rank)
        out.write(packet.hopCount.coerceIn(0, 255))
        out.write(packet.maxHopCount.coerceIn(1, 255))

        val numBuf = ByteBuffer.allocate(8 + 8 + 8)
        numBuf.putLong(packet.sequenceNumber)
        numBuf.putLong(packet.createdAtMillis)
        numBuf.putLong(packet.expiresAtMillis)
        out.write(numBuf.array())

        writeLenBytes(out, packetIdBytes)
        writeLenBytes(out, sessionIdBytes)
        writeLenBytes(out, senderIdBytes)
        writeLenBytes(out, recipientIdBytes)

        out.write(packet.senderPublicKeyEd25519) // 32 bytes
        out.write(packet.payloadHash) // 32 bytes
        out.write(packet.authTag) // 64 bytes

        val payloadLenBuf = ByteBuffer.allocate(4).putInt(wirePayload.size)
        out.write(payloadLenBuf.array())
        out.write(wirePayload)

        return out.toByteArray()
    }

    /**
     * Canonical signable digest covering immutable packet attributes and payloadHash so intermediate
     * relays can increment hopCount without invalidating the sender's Ed25519 signature.
     */
    fun buildSignableBytes(
        packetId: String,
        sessionId: String,
        senderId: String,
        recipientId: String,
        type: PacketType,
        priority: PacketPriority,
        sequenceNumber: Long,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        maxHopCount: Int,
        payloadHash: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PROTOCOL_VERSION.toInt())
        out.write(type.code.toInt())
        out.write(priority.rank)
        out.write(maxHopCount.coerceIn(1, 255))
        val buf = ByteBuffer.allocate(24)
            .putLong(sequenceNumber)
            .putLong(createdAtMillis)
            .putLong(expiresAtMillis)
        out.write(buf.array())
        writeLenBytes(out, packetId.toByteArray(StandardCharsets.UTF_8))
        writeLenBytes(out, sessionId.toByteArray(StandardCharsets.UTF_8))
        writeLenBytes(out, senderId.toByteArray(StandardCharsets.UTF_8))
        writeLenBytes(out, recipientId.toByteArray(StandardCharsets.UTF_8))
        out.write(payloadHash)
        return out.toByteArray()
    }

    sealed class ValidationResult {
        data class Valid(val packet: EmergencyPacket) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun deserialize(raw: ByteArray): EmergencyPacket {
        return when (val res = validateAndDeserialize(raw)) {
            is ValidationResult.Valid -> res.packet
            is ValidationResult.Invalid -> error("Packet validation failed: ${res.reason}")
        }
    }

    fun validateAndDeserialize(raw: ByteArray, nowMillis: Long = System.currentTimeMillis()): ValidationResult {
        if (raw.size < 160 || raw.size > MAX_PACKET_BYTES) {
            return ValidationResult.Invalid("Malformed frame size: ${raw.size}")
        }
        return try {
            val buf = ByteBuffer.wrap(raw)
            val version = buf.get()
            if (version != PROTOCOL_VERSION) {
                return ValidationResult.Invalid("Unsupported protocol version: $version")
            }
            val flags = buf.get()
            val isCompressed = (flags.toInt() and FLAG_COMPRESSED.toInt()) != 0
            val isEncrypted = (flags.toInt() and FLAG_ENCRYPTED.toInt()) != 0

            val type = PacketType.fromCode(buf.get())
                ?: return ValidationResult.Invalid("Unknown packet type")
            val priorityRank = buf.get().toInt() and 0xFF
            val priority = PacketPriority.fromRank(priorityRank)
            val hopCount = buf.get().toInt() and 0xFF
            val maxHopCount = buf.get().toInt() and 0xFF
            if (maxHopCount <= 0 || (maxHopCount < 255 && hopCount > maxHopCount)) {
                return ValidationResult.Invalid("Hop budget exceeded ($hopCount/$maxHopCount)")
            }

            val seq = buf.getLong()
            val createdAt = buf.getLong()
            val expiresAt = buf.getLong()
            if (nowMillis > expiresAt) {
                return ValidationResult.Invalid("Packet expired")
            }

            val packetId = String(readLenBytes(buf, 64), StandardCharsets.UTF_8)
            val sessionId = String(readLenBytes(buf, 64), StandardCharsets.UTF_8)
            val senderId = String(readLenBytes(buf, 64), StandardCharsets.UTF_8)
            val recipientId = String(readLenBytes(buf, 64), StandardCharsets.UTF_8)

            if (packetId.isBlank() || sessionId.isBlank() || senderId.isBlank() || recipientId.isBlank()) {
                return ValidationResult.Invalid("Missing required identifiers")
            }

            val senderPub = ByteArray(32).also { buf.get(it) }
            val expectedPeerId = RescueCrypto.derivePeerId(senderPub)
            if (!expectedPeerId.equals(senderId, ignoreCase = true)) {
                return ValidationResult.Invalid("Sender ID does not match public key fingerprint")
            }

            val payloadHash = ByteArray(32).also { buf.get(it) }
            val authTag = ByteArray(64).also { buf.get(it) }

            val wireLen = buf.getInt()
            if (wireLen < 0 || wireLen > buf.remaining() || buf.remaining() != wireLen) {
                return ValidationResult.Invalid("Corrupted payload length")
            }
            val wirePayload = ByteArray(wireLen).also { buf.get(it) }
            val payload = if (isCompressed) decompress(wirePayload) else wirePayload

            val actualHash = RescueCrypto.sha256(payload)
            if (!actualHash.contentEquals(payloadHash)) {
                return ValidationResult.Invalid("Payload SHA-256 mismatch")
            }

            val signable = buildSignableBytes(
                packetId = packetId,
                sessionId = sessionId,
                senderId = senderId,
                recipientId = recipientId,
                type = type,
                priority = priority,
                sequenceNumber = seq,
                createdAtMillis = createdAt,
                expiresAtMillis = expiresAt,
                maxHopCount = maxHopCount,
                payloadHash = payloadHash
            )
            if (!RescueCrypto.verifyEd25519(senderPub, signable, authTag)) {
                return ValidationResult.Invalid("Invalid Ed25519 packet signature")
            }

            ValidationResult.Valid(
                EmergencyPacket(
                    packetId = packetId,
                    sessionId = sessionId,
                    senderId = senderId,
                    recipientId = recipientId,
                    type = type,
                    priority = priority,
                    createdAtMillis = createdAt,
                    expiresAtMillis = expiresAt,
                    sequenceNumber = seq,
                    hopCount = hopCount,
                    maxHopCount = maxHopCount,
                    payload = payload,
                    payloadHash = payloadHash,
                    authTag = authTag,
                    senderPublicKeyEd25519 = senderPub,
                    isEncryptedPayload = isEncrypted
                )
            )
        } catch (e: Exception) {
            ValidationResult.Invalid("Parse error: ${e.message ?: "malformed"}")
        }
    }

    private fun writeLenBytes(out: ByteArrayOutputStream, data: ByteArray) {
        require(data.size <= 255)
        out.write(data.size)
        out.write(data)
    }

    private fun readLenBytes(buf: ByteBuffer, maxLen: Int): ByteArray {
        val len = buf.get().toInt() and 0xFF
        require(len <= maxLen && len <= buf.remaining()) { "Invalid length prefix: $len" }
        return ByteArray(len).also { buf.get(it) }
    }

    private fun compressIfSmaller(input: ByteArray): ByteArray {
        if (input.size < 32) return input
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun decompress(input: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(input)
        val out = ByteArrayOutputStream(input.size * 2)
        val buffer = ByteArray(256)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_PACKET_BYTES) {
                inflater.end()
                error("Decompressed payload exceeds safety limit")
            }
        }
        inflater.end()
        return out.toByteArray()
    }
}

/**
 * BLE MTU fragmentation & reassembly engine mirroring Bitchat's chunked GATT characteristic writes.
 * Chunk header (8 bytes):
 *   magic (2B = 0x52, 0x4D) + frameId (4B int) + index (1B) + total (1B)
 */
object PacketFragmenter {
    private const val MAGIC_0: Byte = 0x52 // 'R'
    private const val MAGIC_1: Byte = 0x4D // 'M'
    private const val HEADER_SIZE = 8

    fun fragment(wireBytes: ByteArray, mtuPayloadBudget: Int = 180): List<ByteArray> {
        val maxData = (mtuPayloadBudget - HEADER_SIZE).coerceAtLeast(10)
        val total = ((wireBytes.size + maxData - 1) / maxData).coerceAtLeast(1)
        require(total <= 255) { "Frame exceeds 255 MTU fragments" }
        val frameId = RescueCrypto.sha256(wireBytes).let { ByteBuffer.wrap(it).getInt() }
        val result = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val start = i * maxData
            val end = minOf(wireBytes.size, start + maxData)
            val chunkLen = end - start
            val buf = ByteBuffer.allocate(HEADER_SIZE + chunkLen)
            buf.put(MAGIC_0)
            buf.put(MAGIC_1)
            buf.putInt(frameId)
            buf.put(i.toByte())
            buf.put(total.toByte())
            buf.put(wireBytes, start, chunkLen)
            result.add(buf.array())
        }
        return result
    }
}

class PacketReassembler {
    private data class PartialFrame(
        val total: Int,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val partialByKey = ConcurrentHashMap<String, PartialFrame>()

    /**
     * Accepts a raw BLE chunk from [sourcePeerKey] and returns the complete reassembled wire packet
     * once all fragments arrive, or null while awaiting remaining fragments.
     */
    fun acceptChunk(sourcePeerKey: String, chunk: ByteArray): ByteArray? {
        if (chunk.size < 8 || chunk[0] != 0x52.toByte() || chunk[1] != 0x4D.toByte()) {
            // Treat unframed raw payload as single packet if valid
            return chunk
        }
        val buf = ByteBuffer.wrap(chunk)
        buf.get(); buf.get()
        val frameId = buf.getInt()
        val index = buf.get().toInt() and 0xFF
        val total = buf.get().toInt() and 0xFF
        if (total <= 0 || index >= total) return null

        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        if (total == 1) return data

        val key = "$sourcePeerKey:$frameId"
        val partial = partialByKey.compute(key) { _, existing ->
            val target = if (existing == null || existing.total != total) PartialFrame(total) else existing
            target.chunks[index] = data
            target.copy(updatedAt = System.currentTimeMillis())
        } ?: return null

        if (partial.chunks.size == total) {
            partialByKey.remove(key)
            val out = ByteArrayOutputStream()
            for (i in 0 until total) {
                val part = partial.chunks[i] ?: return null
                out.write(part)
            }
            return out.toByteArray()
        }

        purgeStale()
        return null
    }

    private fun purgeStale() {
        val cutoff = System.currentTimeMillis() - 60_000L
        partialByKey.entries.removeIf { it.value.updatedAt < cutoff }
    }
}
