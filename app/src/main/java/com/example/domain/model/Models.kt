package com.example.domain.model

import kotlinx.serialization.Serializable

enum class PacketType(val code: Byte) {
    SOS(0x01),
    LOCATION_UPDATE(0x02),
    ACK_STORED(0x03),
    ACK_DELIVERED(0x04),
    EMERGENCY_REPLY(0x05),
    EMERGENCY_STATUS(0x06),
    AUDIO_METADATA(0x07),
    AUDIO_FRAGMENT(0x08),
    AUDIO_COMPLETE(0x09),
    SESSION_RESOLVED(0x0A),
    IMAGE_METADATA(0x0B),
    IMAGE_FRAGMENT(0x0C),
    IMAGE_COMPLETE(0x0D),
    SESSION_ACCEPT(0x0E);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}

enum class PacketPriority(val rank: Int, val label: String) {
    CRITICAL_SOS(1, "P1 · SOS"),
    URGENT_LOCATION(2, "P2 · LOCATION"),
    DELIVERY_ACK(3, "P3 · ACK"),
    EMERGENCY_REPLY(4, "P4 · REPLY"),
    AUDIO_DATA(5, "P5 · AUDIO"),
    ORDINARY_STATUS(6, "P6 · STATUS"),
    IMAGE_DATA(7, "P7 · IMAGE");

    companion object {
        fun fromRank(rank: Int): PacketPriority =
            entries.firstOrNull { it.rank == rank } ?: ORDINARY_STATUS

        fun defaultForType(type: PacketType): PacketPriority = when (type) {
            PacketType.SOS, PacketType.SESSION_ACCEPT, PacketType.SESSION_RESOLVED -> CRITICAL_SOS
            PacketType.LOCATION_UPDATE -> URGENT_LOCATION
            PacketType.ACK_DELIVERED, PacketType.ACK_STORED -> DELIVERY_ACK
            PacketType.EMERGENCY_REPLY -> EMERGENCY_REPLY
            PacketType.AUDIO_METADATA, PacketType.AUDIO_FRAGMENT, PacketType.AUDIO_COMPLETE -> AUDIO_DATA
            PacketType.EMERGENCY_STATUS -> ORDINARY_STATUS
            PacketType.IMAGE_METADATA, PacketType.IMAGE_FRAGMENT, PacketType.IMAGE_COMPLETE -> IMAGE_DATA
        }
    }
}

enum class DeliveryStatus(val label: String) {
    CREATED("Waiting for node"),
    QUEUED_RELAY("Waiting for node"),
    ACK_STORED("Sent successfully"),
    ACK_DELIVERED("Delivered to target"),
    EXPIRED("Expired"),
    FAILED("Failed")
}

enum class SessionStatus(val label: String) {
    ACTIVE("ACTIVE"),
    RESOLVED("RESOLVED"),
    CANCELLED("CANCELLED")
}

enum class PeerConnectionState(val label: String) {
    DISCOVERED("Discovered"),
    CONNECTING("Connecting"),
    CONNECTED("GATT Connected"),
    DISCONNECTED("Disconnected")
}

enum class NoiseHandshakeState(val label: String) {
    NONE("Unverified"),
    HANDSHAKING("Noise XX Handshake"),
    ESTABLISHED("E2EE Verified")
}

enum class AudioTransferStatus(val label: String) {
    RECORDING("Recording"),
    QUEUED("Queued"),
    TRANSFERRING("Transferring"),
    REASSEMBLED("Ready to Play"),
    MISSING_FRAGMENTS("Missing Fragments"),
    CORRUPTED("Integrity Failed")
}

enum class VoiceTransportMode(val label: String) {
    MUTED("Mic Muted"),
    BLE_5S_LOOP("BLE 5s Stream"),
    WEBRTC_LIVE("WebRTC Live")
}

@Serializable
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val isFresh: Boolean,
    val provider: String = "GNSS"
)

@Serializable
data class EmergencyContext(
    val category: String = "GENERAL_SOS",
    val summary: String = "Emergency SOS activated",
    val batteryPercent: Int = 100,
    val peopleCount: Int = 1
)

data class EmergencySession(
    val sessionId: String,
    val creatorPeerId: String,
    val creatorDisplayName: String,
    val recipientPeerId: String, // "BROADCAST", "PAIRED_MULTI", or specific peer fingerprint
    val isOutgoing: Boolean,
    val context: EmergencyContext,
    val location: LocationSnapshot?,
    val responderLocation: LocationSnapshot? = null,
    val status: SessionStatus,
    val deliveryStatus: DeliveryStatus,
    val sessionAccepted: Boolean = false,
    val hopCount: Int = 0,
    val relayStoredCount: Int = 0,
    val deliveredCount: Int = 0,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val unread: Boolean = false
)

data class EmergencyPacket(
    val packetId: String,
    val sessionId: String,
    val senderId: String,
    val recipientId: String,
    val type: PacketType,
    val priority: PacketPriority,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sequenceNumber: Long,
    val hopCount: Int,
    val maxHopCount: Int,
    val payload: ByteArray,
    val payloadHash: ByteArray,
    val authTag: ByteArray,
    val senderPublicKeyEd25519: ByteArray = ByteArray(0),
    val isEncryptedPayload: Boolean = false
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis > expiresAtMillis

    fun canRelay(): Boolean = maxHopCount >= MeshConstants.UNLIMITED_HOPS || hopCount < maxHopCount
}

data class Peer(
    val peerId: String,
    val displayName: String,
    val bluetoothAddress: String? = null,
    val ed25519PublicKeyHex: String = "",
    val x25519PublicKeyHex: String = "",
    val connectionState: PeerConnectionState = PeerConnectionState.DISCOVERED,
    val noiseState: NoiseHandshakeState = NoiseHandshakeState.NONE,
    val rssi: Int = -75,
    val isRelayEnabled: Boolean = true,
    val lastSeenAtMillis: Long = System.currentTimeMillis()
) {
    val signalBand: String
        get() = when {
            rssi >= -60 -> "Strong"
            rssi >= -78 -> "Moderate"
            else -> "Weak"
        }

    val signalBars: Int
        get() = when {
            rssi >= -58 -> 4
            rssi >= -70 -> 3
            rssi >= -82 -> 2
            else -> 1
        }
}

data class Acknowledgement(
    val ackId: String,
    val packetId: String,
    val sessionId: String,
    val fromPeerId: String,
    val isFinalDelivery: Boolean, // false = ACK_STORED, true = ACK_DELIVERED
    val hopCount: Int,
    val timestampMillis: Long
)

data class EmergencyReply(
    val replyId: String,
    val sessionId: String,
    val senderPeerId: String,
    val senderName: String,
    val message: String,
    val isIncoming: Boolean,
    val deliveryStatus: DeliveryStatus,
    val timestampMillis: Long,
    val imageFileId: String? = null
)

data class AudioChunk(
    val clipId: String,
    val sessionId: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val codec: String,
    val data: ByteArray,
    val clipSha256Hex: String
)

data class AudioClipMetadata(
    val clipId: String,
    val sessionId: String,
    val senderPeerId: String,
    val codec: String,
    val durationMillis: Long,
    val totalFragments: Int,
    val receivedFragments: Int,
    val sha256Hex: String,
    val localFileId: String?,
    val status: AudioTransferStatus,
    val isIncoming: Boolean,
    val createdAtMillis: Long
)

data class EmergencyContact(
    val peerId: String,
    val alias: String,
    val x25519PublicKeyHex: String,
    val ed25519PublicKeyHex: String = "",
    val sharedSecretHex: String = "",
    val addedAtMillis: Long
)

data class DiagnosticLogEntry(
    val id: Long = 0,
    val packetId: String,
    val packetType: PacketType,
    val priority: PacketPriority,
    val queueTimeMillis: Long,
    val attempts: Int,
    val deliveryStatus: DeliveryStatus,
    val detail: String,
    val timestampMillis: Long
)

object MeshConstants {
    const val BROADCAST_RECIPIENT_ID = "FFFFFFFFFFFFFFFF"
    const val PAIRED_MULTI_RECIPIENT_ID = "PAIRED_TRUSTED"
    const val UNLIMITED_HOPS = 255
    const val DEFAULT_MAX_HOP_COUNT = UNLIMITED_HOPS
    const val MIN_HOP_COUNT = 1
    const val MAX_HOP_COUNT = UNLIMITED_HOPS
    const val MAX_PAIRED_CONTACTS = 7
    const val DEFAULT_PACKET_TTL_MILLIS = 12L * 60L * 60L * 1000L // 12h TTL with deduplication
    const val FRESH_LOCATION_THRESHOLD_MILLIS = 90_000L // 90 seconds
    const val LOCATION_UPDATE_INTERVAL_MILLIS = 5_000L // 5-second live GPS ping
    const val VOICE_SEGMENT_SECONDS = 5
}
