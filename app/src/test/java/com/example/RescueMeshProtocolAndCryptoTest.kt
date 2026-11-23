package com.example

import com.example.domain.model.EmergencyPacket
import com.example.domain.model.MeshConstants
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.protocol.PacketFragmenter
import com.example.protocol.PacketReassembler
import com.example.protocol.PacketSerializer
import com.example.security.NoiseSession
import com.example.security.RescueCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RescueMeshProtocolAndCryptoTest {

    @Test
    fun `binary packet serialization validates signatures, rejects tampering, and reassembles MTU fragments`() {
        val keys = RescueCrypto.generateIdentityKeyPair()
        val payload = "SOS: Trapped near ridge trail marker 14. Battery 42%.".repeat(4).toByteArray(StandardCharsets.UTF_8)
        val payloadHash = RescueCrypto.sha256(payload)
        val now = System.currentTimeMillis()
        val signable = PacketSerializer.buildSignableBytes(
            packetId = "pkt001",
            sessionId = "sess001",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 42L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = 9,
            payloadHash = payloadHash
        )
        val sig = RescueCrypto.signEd25519(keys.ed25519Private, signable)
        val packet = EmergencyPacket(
            packetId = "pkt001",
            sessionId = "sess001",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 42L,
            hopCount = 0,
            maxHopCount = 9,
            payload = payload,
            payloadHash = payloadHash,
            authTag = sig,
            senderPublicKeyEd25519 = keys.ed25519Public
        )

        val wire = PacketSerializer.serialize(packet)
        val fragments = PacketFragmenter.fragment(wire, mtuPayloadBudget = 60)
        assertTrue(fragments.size > 1)

        val reassembler = PacketReassembler()
        var reassembled: ByteArray? = null
        fragments.forEach { chunk ->
            reassembled = reassembler.acceptChunk("AA:BB:CC:DD", chunk)
        }
        assertNotNull(reassembled)

        val wireFrame = reassembled ?: error("Expected reassembled frame")
        val validResult = PacketSerializer.validateAndDeserialize(wireFrame, now + 1000L)
        assertTrue(validResult is PacketSerializer.ValidationResult.Valid)
        val decoded = (validResult as PacketSerializer.ValidationResult.Valid).packet
        assertArrayEquals(payload, decoded.payload)
        assertEquals(9, decoded.maxHopCount)

        // Tamper last byte -> must be rejected
        val tampered = wireFrame.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0xFF).toByte() }
        val invalidResult = PacketSerializer.validateAndDeserialize(tampered, now + 1000L)
        assertTrue(invalidResult is PacketSerializer.ValidationResult.Invalid)

        // Expired packet -> must be rejected
        val expiredResult = PacketSerializer.validateAndDeserialize(wireFrame, now + 120_000L)
        assertTrue(expiredResult is PacketSerializer.ValidationResult.Invalid)
    }

    @Test
    fun `Noise XX handshake establishes forward-secret ChaCha20-Poly1305 session`() {
        val alice = RescueCrypto.generateIdentityKeyPair()
        val bob = RescueCrypto.generateIdentityKeyPair()

        val aliceNoise = NoiseSession(bob.peerId, alice.x25519Private, alice.x25519Public, isInitiator = true)
        val bobNoise = NoiseSession(alice.peerId, bob.x25519Private, bob.x25519Public, isInitiator = false)

        val m1 = aliceNoise.writeMessage1()
        val m2 = bobNoise.readMessage1AndWrite2(m1)
        val m3 = aliceNoise.readMessage2AndWrite3(m2)
        bobNoise.readMessage3(m3)

        assertTrue(aliceNoise.isEstablished)
        assertTrue(bobNoise.isEstablished)

        val secretMsg = "Responder team 2 en route via north ridge".toByteArray(StandardCharsets.UTF_8)
        val encrypted = aliceNoise.encrypt(secretMsg)
        val decrypted = bobNoise.decrypt(encrypted)
        assertArrayEquals(secretMsg, decrypted)

        // Tampering ciphertext must fail AEAD authentication
        val bad = encrypted.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0x01).toByte() }
        assertNull(bobNoise.decrypt(bad))
    }
}
