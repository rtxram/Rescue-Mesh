package com.example.storage

import android.content.Context
import com.example.security.RescueCrypto
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Persists compressed voice clips and fragment payloads outside SQLite in app-private storage,
 * referenced by persistent fileId strings in Room metadata.
 */
class AudioFileStore(context: Context) {

    private val audioDir: File = File(context.filesDir, "audio_clips").apply { mkdirs() }
    private val fragmentDir: File = File(context.filesDir, "audio_fragments").apply { mkdirs() }
    private val imageDir: File = File(context.filesDir, "mesh_images").apply { mkdirs() }
    private val imageFragmentDir: File = File(context.filesDir, "mesh_image_fragments").apply { mkdirs() }

    fun createClipFile(fileId: String): File = File(audioDir, fileId)

    fun saveClipBytes(fileId: String, bytes: ByteArray): File {
        val file = File(audioDir, fileId)
        file.writeBytes(bytes)
        return file
    }

    fun readClipBytes(fileId: String): ByteArray? {
        val file = File(audioDir, fileId)
        return if (file.exists()) file.readBytes() else null
    }

    fun getClipFile(fileId: String): File? {
        val file = File(audioDir, fileId)
        return if (file.exists()) file else null
    }

    fun saveFragment(clipId: String, fragmentIndex: Int, bytes: ByteArray): String {
        val fileId = "${clipId}_frag_$fragmentIndex.bin"
        File(fragmentDir, fileId).writeBytes(bytes)
        return fileId
    }

    fun readFragment(fileId: String): ByteArray? {
        val file = File(fragmentDir, fileId)
        return if (file.exists()) file.readBytes() else null
    }

    fun assembleClipFromFragments(
        clipId: String,
        codec: String,
        fragmentFileIds: List<String>,
        expectedSha256Hex: String
    ): String? {
        val out = ByteArrayOutputStream()
        for (fid in fragmentFileIds) {
            val part = readFragment(fid) ?: return null
            out.write(part)
        }
        val combined = out.toByteArray()
        val actualSha = RescueCrypto.bytesToHex(RescueCrypto.sha256(combined))
        if (expectedSha256Hex.isNotBlank() && !actualSha.equals(expectedSha256Hex, ignoreCase = true)) {
            return null
        }
        val ext = when {
            codec.contains("RMAD", ignoreCase = true) -> "rmad"
            codec.contains("OPUS", ignoreCase = true) -> "ogg"
            codec.contains("WAV", ignoreCase = true) -> "wav"
            else -> "m4a"
        }
        val assembledFileId = "${clipId}_reassembled.$ext"
        saveClipBytes(assembledFileId, combined)
        return assembledFileId
    }

    fun saveImageBytes(imageId: String, bytes: ByteArray): String {
        val fileId = "${imageId}.jpg"
        File(imageDir, fileId).writeBytes(bytes)
        return fileId
    }

    fun getImageFile(fileId: String): File? {
        val file = File(imageDir, fileId)
        return if (file.exists()) file else null
    }

    fun saveImageFragment(imageId: String, fragmentIndex: Int, bytes: ByteArray): String {
        val fileId = "${imageId}_imgfrag_$fragmentIndex.bin"
        File(imageFragmentDir, fileId).writeBytes(bytes)
        return fileId
    }

    fun assembleImageFromFragments(
        imageId: String,
        totalFragments: Int,
        expectedSha256Hex: String
    ): String? {
        val out = ByteArrayOutputStream()
        for (idx in 0 until totalFragments) {
            val fragFile = File(imageFragmentDir, "${imageId}_imgfrag_$idx.bin")
            if (!fragFile.exists()) return null
            out.write(fragFile.readBytes())
        }
        val combined = out.toByteArray()
        val actualSha = RescueCrypto.bytesToHex(RescueCrypto.sha256(combined))
        if (expectedSha256Hex.isNotBlank() && !actualSha.equals(expectedSha256Hex, ignoreCase = true)) {
            return null
        }
        return saveImageBytes(imageId, combined)
    }
}
