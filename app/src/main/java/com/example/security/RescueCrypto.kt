package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cryptographic primitives aligned with Bitchat Android's documented security baseline:
 * - Ed25519 for device identity & packet signatures
 * - X25519 for ECDH key agreement
 * - HKDF-SHA256 for key derivation
 * - ChaCha20-Poly1305 AEAD for Noise transport & encrypted direct emergency payloads
 */
object RescueCrypto {

    private val secureRandom = SecureRandom()

    data class IdentityKeyPair(
        val ed25519Private: ByteArray,
        val ed25519Public: ByteArray,
        val x25519Private: ByteArray,
        val x25519Public: ByteArray
    ) {
        val peerId: String by lazy { derivePeerId(ed25519Public) }
    }

    fun generateIdentityKeyPair(): IdentityKeyPair {
        val edPriv = Ed25519PrivateKeyParameters(secureRandom)
        val edPub = edPriv.generatePublicKey()
        val xPriv = X25519PrivateKeyParameters(secureRandom)
        val xPub = xPriv.generatePublicKey()
        return IdentityKeyPair(
            ed25519Private = edPriv.encoded,
            ed25519Public = edPub.encoded,
            x25519Private = xPriv.encoded,
            x25519Public = xPub.encoded
        )
    }

    fun generateEphemeralX25519(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        return priv.encoded to pub.encoded
    }

    /**
     * Derives a compact 16-hex-char (64-bit) Peer ID from SHA-256(ed25519PublicKey),
     * matching Bitchat's compact binary peer identifier approach.
     */
    fun derivePeerId(ed25519Public: ByteArray): String {
        val hash = sha256(ed25519Public)
        return hash.take(8).joinToString("") { "%02X".format(it) }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun signEd25519(privateKeyBytes: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verifyEd25519(publicKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyBytes.size != 32 || signature.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKeyBytes, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun x25519SharedSecret(privateKeyBytes: ByteArray, peerPublicKeyBytes: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKeyBytes, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKeyBytes, 0), out, 0)
        return out
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        hkdf.generateBytes(out, 0, length)
        return out
    }

    fun chaCha20Poly1305Encrypt(
        key32: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        require(key32.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce12.size == 12) { "ChaCha20-Poly1305 nonce must be 12 bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key32), 128, nonce12, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun chaCha20Poly1305Decrypt(
        key32: ByteArray,
        nonce12: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray = ByteArray(0)
    ): ByteArray? {
        if (key32.size != 32 || nonce12.size != 12 || ciphertext.size < 16) return null
        return try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key32), 128, nonce12, aad))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            cipher.doFinal(out, len)
            out
        } catch (_: Exception) {
            null
        }
    }

    fun nonceFromCounter(counter: Long): ByteArray {
        val buf = ByteBuffer.allocate(12)
        buf.putInt(0)
        buf.putLong(counter)
        return buf.array()
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return ByteArray(0)
        if (clean.any { Character.digit(it, 16) < 0 }) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * Lightweight implementation of Noise_XX_25519_ChaChaPoly_SHA256 handshake & transport cipher state
 * patterned after Bitchat Android's Noise encryption pipeline.
 */
class NoiseSession(
    val remotePeerId: String,
    private val localStaticPrivate: ByteArray,
    private val localStaticPublic: ByteArray,
    private val isInitiator: Boolean
) {
    private var chainingKey: ByteArray = RescueCrypto.sha256("Noise_XX_25519_ChaChaPoly_SHA256".toByteArray())
    private var handshakeHash: ByteArray = chainingKey.copyOf()
    private var localEphemeralPriv: ByteArray? = null
    private var localEphemeralPub: ByteArray? = null
    private var remoteEphemeralPub: ByteArray? = null
    var remoteStaticPublic: ByteArray? = null
        private set

    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null
    private var sendNonce: Long = 0L
    private var recvNonce: Long = 0L
    private var step: Int = 0
    private var lastTempKey: ByteArray? = null

    val isEstablished: Boolean
        get() = sendKey != null && recvKey != null

    private fun mixHash(data: ByteArray) {
        handshakeHash = RescueCrypto.sha256(handshakeHash + data)
    }

    private fun mixKey(dhOutput: ByteArray): ByteArray {
        val okm = RescueCrypto.hkdfSha256(dhOutput, chainingKey, "RescueMeshNoise".toByteArray(), 64)
        chainingKey = okm.copyOfRange(0, 32)
        return okm.copyOfRange(32, 64)
    }

    /**
     * Initiator Step 1 (-> e)
     */
    fun writeMessage1(): ByteArray {
        check(isInitiator && step == 0)
        val (ePriv, ePub) = RescueCrypto.generateEphemeralX25519()
        localEphemeralPriv = ePriv
        localEphemeralPub = ePub
        mixHash(ePub)
        step = 1
        return ePub
    }

    /**
     * Responder processes Step 1 and emits Step 2 (<- e, ee, s, es)
     */
    fun readMessage1AndWrite2(msg1: ByteArray): ByteArray {
        check(!isInitiator && step == 0 && msg1.size == 32)
        remoteEphemeralPub = msg1
        mixHash(msg1)

        val (ePriv, ePub) = RescueCrypto.generateEphemeralX25519()
        localEphemeralPriv = ePriv
        localEphemeralPub = ePub
        mixHash(ePub)

        // ee
        val tempKey1 = mixKey(RescueCrypto.x25519SharedSecret(ePriv, msg1))
        // encrypt static s
        val encryptedStatic = RescueCrypto.chaCha20Poly1305Encrypt(
            key32 = tempKey1,
            nonce12 = RescueCrypto.nonceFromCounter(0),
            plaintext = localStaticPublic,
            aad = handshakeHash
        )
        mixHash(encryptedStatic)

        // es (responder static, initiator ephemeral)
        val tempKey2 = mixKey(RescueCrypto.x25519SharedSecret(localStaticPrivate, msg1))
        val encryptedEmpty = RescueCrypto.chaCha20Poly1305Encrypt(
            key32 = tempKey2,
            nonce12 = RescueCrypto.nonceFromCounter(0),
            plaintext = ByteArray(0),
            aad = handshakeHash
        )
        mixHash(encryptedEmpty)
        lastTempKey = tempKey2
        step = 2
        return ePub + encryptedStatic + encryptedEmpty
    }

    /**
     * Initiator processes Step 2 and emits Step 3 (-> s, se)
     */
    fun readMessage2AndWrite3(msg2: ByteArray): ByteArray {
        check(isInitiator && step == 1 && msg2.size >= 32 + 48 + 16)
        val re = msg2.copyOfRange(0, 32)
        val encS = msg2.copyOfRange(32, 32 + 48)
        val encPayload = msg2.copyOfRange(32 + 48, msg2.size)

        remoteEphemeralPub = re
        mixHash(re)

        val ePriv = localEphemeralPriv ?: error("Missing local ephemeral")
        val tempKey1 = mixKey(RescueCrypto.x25519SharedSecret(ePriv, re))
        val rs = RescueCrypto.chaCha20Poly1305Decrypt(tempKey1, RescueCrypto.nonceFromCounter(0), encS, handshakeHash)
            ?: error("Noise msg2 static decrypt failed")
        remoteStaticPublic = rs
        mixHash(encS)

        val tempKey2 = mixKey(RescueCrypto.x25519SharedSecret(ePriv, rs))
        RescueCrypto.chaCha20Poly1305Decrypt(tempKey2, RescueCrypto.nonceFromCounter(0), encPayload, handshakeHash)
            ?: error("Noise msg2 verification failed")
        mixHash(encPayload)

        // Step 3: -> s, se
        val encryptedStatic = RescueCrypto.chaCha20Poly1305Encrypt(
            key32 = tempKey2,
            nonce12 = RescueCrypto.nonceFromCounter(1),
            plaintext = localStaticPublic,
            aad = handshakeHash
        )
        mixHash(encryptedStatic)

        val tempKey3 = mixKey(RescueCrypto.x25519SharedSecret(localStaticPrivate, re))
        val confirm = RescueCrypto.chaCha20Poly1305Encrypt(
            key32 = tempKey3,
            nonce12 = RescueCrypto.nonceFromCounter(0),
            plaintext = ByteArray(0),
            aad = handshakeHash
        )
        mixHash(confirm)

        splitTransportKeys(tempKey3)
        step = 3
        return encryptedStatic + confirm
    }

    /**
     * Responder processes Step 3 and completes session.
     */
    fun readMessage3(msg3: ByteArray) {
        check(!isInitiator && step == 2 && msg3.size >= 48 + 16)
        val tempKey2 = lastTempKey ?: error("Missing Noise tempKey2")
        val encS = msg3.copyOfRange(0, 48)
        val encConfirm = msg3.copyOfRange(48, msg3.size)

        val rs = RescueCrypto.chaCha20Poly1305Decrypt(tempKey2, RescueCrypto.nonceFromCounter(1), encS, handshakeHash)
            ?: error("Noise msg3 static decrypt failed")
        remoteStaticPublic = rs
        mixHash(encS)

        val ePriv = localEphemeralPriv ?: error("Missing local ephemeral")
        val tempKey3 = mixKey(RescueCrypto.x25519SharedSecret(ePriv, rs))
        RescueCrypto.chaCha20Poly1305Decrypt(tempKey3, RescueCrypto.nonceFromCounter(0), encConfirm, handshakeHash)
            ?: error("Noise msg3 confirm failed")
        mixHash(encConfirm)

        splitTransportKeys(tempKey3)
        step = 3
    }

    private fun splitTransportKeys(rootMix: ByteArray) {
        val split = RescueCrypto.hkdfSha256(rootMix, chainingKey, "NoiseSplit".toByteArray(), 64)
        val k1 = split.copyOfRange(0, 32)
        val k2 = split.copyOfRange(32, 64)
        if (isInitiator) {
            sendKey = k1
            recvKey = k2
        } else {
            sendKey = k2
            recvKey = k1
        }
    }

    @Synchronized
    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val key = sendKey ?: error("Noise session not established")
        val nonce = RescueCrypto.nonceFromCounter(sendNonce++)
        val ct = RescueCrypto.chaCha20Poly1305Encrypt(key, nonce, plaintext, aad)
        return nonce + ct
    }

    @Synchronized
    fun decrypt(ciphertextWithNonce: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        val key = recvKey ?: return null
        if (ciphertextWithNonce.size < 12 + 16) return null
        val nonce = ciphertextWithNonce.copyOfRange(0, 12)
        val ct = ciphertextWithNonce.copyOfRange(12, ciphertextWithNonce.size)
        recvNonce++
        return RescueCrypto.chaCha20Poly1305Decrypt(key, nonce, ct, aad)
    }
}

/**
 * Manages the local device's persistent cryptographic identity and per-peer Noise/direct encryption
 * plus replay sequence windows.
 */
class IdentityManager(context: Context) {

    private val prefs = context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE)
    private val keyAlias = "rescuemesh_master_aes_key"
    private val lastSeqBySender = ConcurrentHashMap<String, Long>()
    private val lastTimestampBySender = ConcurrentHashMap<String, Long>()
    private var seqCounter: Long = prefs.getLong("local_seq_counter", 1L)

    val identity: RescueCrypto.IdentityKeyPair by lazy { loadOrCreateIdentity() }
    val localPeerId: String get() = identity.peerId

    private fun loadOrCreateIdentity(): RescueCrypto.IdentityKeyPair {
        val edPrivB64 = prefs.getString("ed_priv_enc", null)
        val edPubB64 = prefs.getString("ed_pub", null)
        val xPrivB64 = prefs.getString("x_priv_enc", null)
        val xPubB64 = prefs.getString("x_pub", null)

        if (edPrivB64 != null && edPubB64 != null && xPrivB64 != null && xPubB64 != null) {
            val edPriv = try {
                decryptWithKeystoreOrFallback(Base64.decode(edPrivB64, Base64.NO_WRAP))
            } catch (_: Exception) {
                null
            }
            val xPriv = try {
                decryptWithKeystoreOrFallback(Base64.decode(xPrivB64, Base64.NO_WRAP))
            } catch (_: Exception) {
                null
            }
            if (edPriv != null && xPriv != null && edPriv.size == 32 && xPriv.size == 32) {
                return RescueCrypto.IdentityKeyPair(
                    ed25519Private = edPriv,
                    ed25519Public = Base64.decode(edPubB64, Base64.NO_WRAP),
                    x25519Private = xPriv,
                    x25519Public = Base64.decode(xPubB64, Base64.NO_WRAP)
                )
            }
        }

        // Legacy plaintext keys (if any) are consumed once and migrated into the
        // keystore-encrypted blob only, then erased.
        val legacyEdPriv = prefs.getString("ed_priv_hex", null)
        val legacyEdPub = prefs.getString("ed_pub_hex", null)
        val legacyXPriv = prefs.getString("x_priv_hex", null)
        val legacyXPub = prefs.getString("x_pub_hex", null)
        if (legacyEdPriv != null && legacyEdPub != null && legacyXPriv != null && legacyXPub != null) {
            val edPriv = RescueCrypto.hexToBytes(legacyEdPriv)
            val xPriv = RescueCrypto.hexToBytes(legacyXPriv)
            if (edPriv.size == 32 && xPriv.size == 32) {
                prefs.edit()
                    .putString("ed_priv_enc", Base64.encodeToString(encryptWithKeystoreOrFallback(edPriv), Base64.NO_WRAP))
                    .putString("ed_pub", legacyEdPub)
                    .putString("x_priv_enc", Base64.encodeToString(encryptWithKeystoreOrFallback(xPriv), Base64.NO_WRAP))
                    .putString("x_pub", legacyXPub)
                    .remove("ed_priv_hex")
                    .remove("ed_pub_hex")
                    .remove("x_priv_hex")
                    .remove("x_pub_hex")
                    .commit()
                return RescueCrypto.IdentityKeyPair(
                    ed25519Private = edPriv,
                    ed25519Public = RescueCrypto.hexToBytes(legacyEdPub),
                    x25519Private = xPriv,
                    x25519Public = RescueCrypto.hexToBytes(legacyXPub)
                )
            }
        }

        val generated = RescueCrypto.generateIdentityKeyPair()
        prefs.edit()
            .putString("ed_priv_enc", Base64.encodeToString(encryptWithKeystoreOrFallback(generated.ed25519Private), Base64.NO_WRAP))
            .putString("ed_pub", Base64.encodeToString(generated.ed25519Public, Base64.NO_WRAP))
            .putString("x_priv_enc", Base64.encodeToString(encryptWithKeystoreOrFallback(generated.x25519Private), Base64.NO_WRAP))
            .putString("x_pub", Base64.encodeToString(generated.x25519Public, Base64.NO_WRAP))
            .commit()
        return generated
    }

    @Synchronized
    fun nextSequenceNumber(): Long {
        val next = seqCounter++
        prefs.edit().putLong("local_seq_counter", seqCounter).commit()
        return next
    }

    /**
     * Replay protection: checks if sequenceNumber from senderId is strictly newer than previously
     * validated packets, while allowing bootstrap from unseen senders or restarted/reinstalled devices
     * whose signed packet timestamp advanced by > 30 seconds.
     */
    fun checkAndRecordReplayWindow(
        senderId: String,
        sequenceNumber: Long,
        createdAtMillis: Long = 0L
    ): Boolean {
        val prevSeq = lastSeqBySender[senderId]
        val prevTs = lastTimestampBySender[senderId] ?: 0L
        val restartedWithNewerClock = createdAtMillis > 0L && prevTs > 0L && (createdAtMillis - prevTs) > 30_000L
        if (prevSeq != null && sequenceNumber <= prevSeq && !restartedWithNewerClock) {
            return false
        }
        lastSeqBySender[senderId] = sequenceNumber
        if (createdAtMillis > prevTs) {
            lastTimestampBySender[senderId] = createdAtMillis
        }
        return true
    }

    /**
     * Stateless recipient-level E2EE box (ephemeral X25519 + HKDF-SHA256 + ChaCha20-Poly1305)
     * for offline store-carry-forward direct SOS / contact messages when no live Noise session exists yet.
     */
    fun sealForRecipient(recipientX25519Public: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val (ePriv, ePub) = RescueCrypto.generateEphemeralX25519()
        val shared = RescueCrypto.x25519SharedSecret(ePriv, recipientX25519Public)
        val key = RescueCrypto.hkdfSha256(shared, ePub + recipientX25519Public, "RescueMeshDirectBox".toByteArray(), 32)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = RescueCrypto.chaCha20Poly1305Encrypt(key, nonce, plaintext, aad)
        return ePub + nonce + ct
    }

    fun openFromSender(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 32 + 12 + 16) return null
        val ePub = sealed.copyOfRange(0, 32)
        val nonce = sealed.copyOfRange(32, 44)
        val ct = sealed.copyOfRange(44, sealed.size)
        val shared = RescueCrypto.x25519SharedSecret(identity.x25519Private, ePub)
        val key = RescueCrypto.hkdfSha256(shared, ePub + identity.x25519Public, "RescueMeshDirectBox".toByteArray(), 32)
        return RescueCrypto.chaCha20Poly1305Decrypt(key, nonce, ct, aad)
    }

    /**
     * Multi-recipient paired-contact E2EE envelope ("RME2"):
     * Encrypts plaintext once with a random 32-byte contentKey and wraps that contentKey
     * individually for each paired recipient's X25519 public key (up to 7 contacts).
     * Intermediate relay nodes verify the outer Ed25519 signature and forward blindly,
     * unable to unwrap contentKey or read the SOS/payload.
     */
    fun sealForPairedContacts(
        recipientX25519PublicKeys: List<ByteArray>,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val validKeys = recipientX25519PublicKeys.filter { it.size == 32 }.take(com.example.domain.model.MeshConstants.MAX_PAIRED_CONTACTS)
        require(validKeys.isNotEmpty()) { "At least one valid X25519 recipient public key required" }
        val rng = SecureRandom()
        val contentKey = ByteArray(32).also { rng.nextBytes(it) }
        val contentNonce = ByteArray(12).also { rng.nextBytes(it) }
        val contentCt = RescueCrypto.chaCha20Poly1305Encrypt(contentKey, contentNonce, plaintext, aad)

        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x4D, 0x45, 0x32)) // "RME2"
        out.write(validKeys.size)
        for (recipientPub in validKeys) {
            val (ePriv, ePub) = RescueCrypto.generateEphemeralX25519()
            val shared = RescueCrypto.x25519SharedSecret(ePriv, recipientPub)
            val wrapKey = RescueCrypto.hkdfSha256(shared, ePub + recipientPub, "RescueMeshEnvelopeV2".toByteArray(), 32)
            val wrapNonce = ByteArray(12).also { rng.nextBytes(it) }
            val wrappedKey = RescueCrypto.chaCha20Poly1305Encrypt(wrapKey, wrapNonce, contentKey, aad)
            out.write(ePub)       // 32 bytes
            out.write(wrapNonce)  // 12 bytes
            out.write(wrappedKey) // 48 bytes (32 + 16 tag)
        }
        out.write(contentNonce) // 12 bytes
        out.write(contentCt)
        return out.toByteArray()
    }

    fun openPairedEnvelope(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 4 + 1 + 92 + 12 + 16) return null
        if (sealed[0] != 0x52.toByte() || sealed[1] != 0x4D.toByte() ||
            sealed[2] != 0x45.toByte() || sealed[3] != 0x32.toByte()
        ) {
            return null
        }
        val count = sealed[4].toInt() and 0xFF
        if (count !in 1..com.example.domain.model.MeshConstants.MAX_PAIRED_CONTACTS) return null
        val slotSize = 32 + 12 + 48
        val headerAndSlots = 5 + count * slotSize
        if (sealed.size < headerAndSlots + 12 + 16) return null

        var contentKey: ByteArray? = null
        var offset = 5
        for (i in 0 until count) {
            val ePub = sealed.copyOfRange(offset, offset + 32)
            val wrapNonce = sealed.copyOfRange(offset + 32, offset + 44)
            val wrappedKey = sealed.copyOfRange(offset + 44, offset + slotSize)
            offset += slotSize

            val shared = RescueCrypto.x25519SharedSecret(identity.x25519Private, ePub)
            val wrapKey = RescueCrypto.hkdfSha256(
                shared,
                ePub + identity.x25519Public,
                "RescueMeshEnvelopeV2".toByteArray(),
                32
            )
            val unwrapped = RescueCrypto.chaCha20Poly1305Decrypt(wrapKey, wrapNonce, wrappedKey, aad)
            if (unwrapped != null && unwrapped.size == 32) {
                contentKey = unwrapped
                break
            }
        }
        val key = contentKey ?: return null
        val contentNonce = sealed.copyOfRange(headerAndSlots, headerAndSlots + 12)
        val contentCt = sealed.copyOfRange(headerAndSlots + 12, sealed.size)
        return RescueCrypto.chaCha20Poly1305Decrypt(key, contentNonce, contentCt, aad)
    }

    fun openAnyEncryptedPayload(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size >= 4 &&
            sealed[0] == 0x52.toByte() && sealed[1] == 0x4D.toByte() &&
            sealed[2] == 0x45.toByte() && sealed[3] == 0x32.toByte()
        ) {
            return openPairedEnvelope(sealed, aad)
        }
        return openFromSender(sealed, aad)
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(keyAlias, null) as? SecretKey) ?: run {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                kg.init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                kg.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptWithKeystoreOrFallback(plain: ByteArray): ByteArray {
        val key = getOrCreateKeystoreSecretKey() ?: return byteArrayOf(0x00) + plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val enc = cipher.doFinal(plain)
            byteArrayOf(0x01, iv.size.toByte()) + iv + enc
        } catch (_: Exception) {
            byteArrayOf(0x00) + plain
        }
    }

    private fun decryptWithKeystoreOrFallback(blob: ByteArray): ByteArray? {
        if (blob.isEmpty()) return null
        return when (blob[0]) {
            0x00.toByte() -> blob.copyOfRange(1, blob.size)
            0x01.toByte() -> {
                val key = getOrCreateKeystoreSecretKey() ?: return null
                val ivLen = blob[1].toInt() and 0xFF
                if (blob.size < 2 + ivLen + 16) return null
                val iv = blob.copyOfRange(2, 2 + ivLen)
                val enc = blob.copyOfRange(2 + ivLen, blob.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.doFinal(enc)
            }
            else -> null
        }
    }
}
