package com.example.security

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.example.domain.model.EmergencyContact
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class PairingBundle(
    val peerId: String,
    val alias: String,
    val x25519PublicKeyHex: String,
    val ed25519PublicKeyHex: String,
    val sharedSecretHex: String = ""
) {
    fun toContact(nowMillis: Long = System.currentTimeMillis()): EmergencyContact = EmergencyContact(
        peerId = peerId,
        alias = alias.ifBlank { "Node-${peerId.take(4)}" },
        x25519PublicKeyHex = x25519PublicKeyHex,
        ed25519PublicKeyHex = ed25519PublicKeyHex,
        sharedSecretHex = sharedSecretHex,
        addedAtMillis = nowMillis
    )
}

/**
 * Encodes & decodes trusted contact pairing QR URIs ("rescuemesh://pair?v=2&...")
 * as well as compact pasteable pairing keys ("RMPAIR:...") and renders QR bitmaps via ZXing.
 */
object QrPairingCodec {

    fun encodePairingUri(
        peerId: String,
        alias: String,
        x25519PublicBytes: ByteArray,
        ed25519PublicBytes: ByteArray
    ): String {
        val xHex = RescueCrypto.bytesToHex(x25519PublicBytes)
        val eHex = RescueCrypto.bytesToHex(ed25519PublicBytes)
        return Uri.Builder()
            .scheme("rescuemesh")
            .authority("pair")
            .appendQueryParameter("v", "2")
            .appendQueryParameter("id", peerId.uppercase())
            .appendQueryParameter("n", alias.trim().ifEmpty { "RescueNode" })
            .appendQueryParameter("x", xHex)
            .appendQueryParameter("e", eHex)
            .build()
            .toString()
    }

    fun encodeCompactManualKey(
        peerId: String,
        alias: String,
        x25519PublicBytes: ByteArray,
        ed25519PublicBytes: ByteArray
    ): String {
        val uri = encodePairingUri(peerId, alias, x25519PublicBytes, ed25519PublicBytes)
        val b64 = Base64.encodeToString(uri.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return "RMKEY2:$b64"
    }

    fun decodePairingInput(rawInput: String): PairingBundle? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val normalized = if (trimmed.startsWith("RMKEY2:", ignoreCase = true)) {
            val b64 = trimmed.substringAfter(":").trim()
            runCatching {
                String(Base64.decode(b64, Base64.NO_WRAP or Base64.URL_SAFE), StandardCharsets.UTF_8)
            }.getOrNull() ?: return null
        } else {
            trimmed
        }

        if (normalized.startsWith("rescuemesh://pair", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            val peerId = uri.getQueryParameter("id")?.trim()?.uppercase().orEmpty()
            val alias = uri.getQueryParameter("n")?.trim().orEmpty().ifBlank { "Node-${peerId.take(4)}" }
            val xHex = uri.getQueryParameter("x")?.trim()?.uppercase().orEmpty()
            val eHex = uri.getQueryParameter("e")?.trim()?.uppercase().orEmpty()
            val kHex = uri.getQueryParameter("k")?.trim()?.uppercase().orEmpty()
            if (peerId.length < 8 || !isValidKeyHex(xHex)) return null
            return PairingBundle(
                peerId = peerId,
                alias = alias,
                x25519PublicKeyHex = xHex,
                ed25519PublicKeyHex = eHex,
                sharedSecretHex = kHex
            )
        }

        // Also accept simple pipe-delimited manual string: PEERID|ALIAS|X25519HEX|ED25519HEX
        val parts = normalized.split("|")
        if (parts.size >= 3) {
            val peerId = parts[0].trim().uppercase()
            val alias = parts[1].trim().ifBlank { "Node-${peerId.take(4)}" }
            val xHex = parts[2].trim().uppercase()
            val eHex = parts.getOrNull(3)?.trim()?.uppercase().orEmpty()
            if (peerId.length >= 8 && isValidKeyHex(xHex)) {
                return PairingBundle(
                    peerId = peerId,
                    alias = alias,
                    x25519PublicKeyHex = xHex,
                    ed25519PublicKeyHex = eHex
                )
            }
        }
        return null
    }

    private fun isValidKeyHex(hex: String): Boolean {
        val bytes = RescueCrypto.hexToBytes(hex)
        return bytes.size == 32
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeQrFromYuvPlane(
        yBytes: ByteArray,
        width: Int,
        height: Int
    ): String? {
        return try {
            val source = PlanarYUVLuminanceSource(
                yBytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(bitmap).text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compresses a captured JPEG/photo bitmap so it fits comfortably over multi-hop BLE mesh
     * at lowest Priority-7 (<= 480px max edge, <= ~14 KB).
     */
    fun compressBitmapForMesh(bitmap: Bitmap, maxDimension: Int = 480, maxBytes: Int = 14_000): ByteArray {
        val ratio = minOf(
            maxDimension.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f),
            maxDimension.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f),
            1.0f
        )
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = if (targetW != bitmap.width || targetH != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        var quality = 72
        var result: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            result = out.toByteArray()
            quality -= 12
        } while (result.size > maxBytes && quality >= 20)
        return result
    }
}
