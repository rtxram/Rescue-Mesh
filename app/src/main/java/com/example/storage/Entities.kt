package com.example.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_sessions")
data class EmergencySessionEntity(
    @PrimaryKey val sessionId: String,
    val creatorPeerId: String,
    val creatorDisplayName: String,
    val recipientPeerId: String,
    val isOutgoing: Boolean,
    val contextCategory: String,
    val contextSummary: String,
    val batteryPercent: Int,
    val peopleCount: Int,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationTimestampMillis: Long?,
    val locationIsFresh: Boolean,
    val locationProvider: String?,
    val responderLatitude: Double? = null,
    val responderLongitude: Double? = null,
    val responderAccuracyMeters: Float? = null,
    val responderLocationTimestampMillis: Long? = null,
    val status: String,
    val deliveryStatus: String,
    val sessionAccepted: Boolean = false,
    val hopCount: Int,
    val relayStoredCount: Int,
    val deliveredCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val unread: Boolean
)

@Entity(
    tableName = "emergency_packets",
    indices = [Index("sessionId"), Index("expiresAtMillis")]
)
data class EmergencyPacketEntity(
    @PrimaryKey val packetId: String,
    val sessionId: String,
    val senderId: String,
    val recipientId: String,
    val typeCode: Byte,
    val priorityRank: Int,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sequenceNumber: Long,
    val hopCount: Int,
    val maxHopCount: Int,
    val payload: ByteArray,
    val payloadHash: ByteArray,
    val authTag: ByteArray,
    val senderPublicKeyEd25519: ByteArray,
    val isEncryptedPayload: Boolean,
    val deliveryStatus: String
)

@Entity(
    tableName = "relay_queue",
    indices = [Index("priorityRank", "createdAtMillis"), Index("expiresAtMillis")]
)
data class RelayQueueEntity(
    @PrimaryKey val packetId: String,
    val sessionId: String,
    val recipientId: String,
    val typeCode: Byte,
    val priorityRank: Int,
    val hopCount: Int,
    val maxHopCount: Int,
    val wireBytes: ByteArray,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val enqueuedAtMillis: Long,
    val nextRetryAtMillis: Long,
    val attemptCount: Int,
    val isOriginLocal: Boolean,
    val forwardedToPeersCsv: String
)

@Entity(
    tableName = "seen_packets",
    indices = [Index("expiresAtMillis")]
)
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val senderId: String,
    val seenAtMillis: Long,
    val expiresAtMillis: Long
)

@Entity(
    tableName = "acknowledgements",
    indices = [Index("sessionId"), Index("packetId")]
)
data class AcknowledgementEntity(
    @PrimaryKey val ackId: String,
    val packetId: String,
    val sessionId: String,
    val fromPeerId: String,
    val isFinalDelivery: Boolean,
    val hopCount: Int,
    val timestampMillis: Long
)

@Entity(
    tableName = "emergency_replies",
    indices = [Index("sessionId")]
)
data class EmergencyReplyEntity(
    @PrimaryKey val replyId: String,
    val sessionId: String,
    val senderPeerId: String,
    val senderName: String,
    val message: String,
    val isIncoming: Boolean,
    val deliveryStatus: String,
    val timestampMillis: Long,
    val imageFileId: String? = null
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val bluetoothAddress: String?,
    val ed25519PublicKeyHex: String,
    val x25519PublicKeyHex: String,
    val connectionState: String,
    val noiseState: String,
    val rssi: Int,
    val isRelayEnabled: Boolean,
    val lastSeenAtMillis: Long
)

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey val peerId: String,
    val alias: String,
    val x25519PublicKeyHex: String,
    val ed25519PublicKeyHex: String = "",
    val sharedSecretHex: String = "",
    val addedAtMillis: Long
)

@Entity(
    tableName = "audio_clips",
    indices = [Index("sessionId")]
)
data class AudioClipMetadataEntity(
    @PrimaryKey val clipId: String,
    val sessionId: String,
    val senderPeerId: String,
    val codec: String,
    val durationMillis: Long,
    val totalFragments: Int,
    val receivedFragments: Int,
    val sha256Hex: String,
    val localFileId: String?,
    val status: String,
    val isIncoming: Boolean,
    val createdAtMillis: Long
)

@Entity(tableName = "audio_fragments", primaryKeys = ["clipId", "fragmentIndex"])
data class AudioFragmentEntity(
    val clipId: String,
    val sessionId: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val fragmentFileId: String,
    val receivedAtMillis: Long
)

@Entity(
    tableName = "image_clips",
    indices = [Index("sessionId")]
)
data class ImageClipMetadataEntity(
    @PrimaryKey val imageId: String,
    val sessionId: String,
    val senderPeerId: String,
    val senderName: String,
    val totalFragments: Int,
    val sha256Hex: String,
    val localFileId: String?,
    val isIncoming: Boolean,
    val createdAtMillis: Long
)

@Entity(tableName = "image_fragments", primaryKeys = ["imageId", "fragmentIndex"])
data class ImageFragmentEntity(
    val imageId: String,
    val sessionId: String,
    val fragmentIndex: Int,
    val totalFragments: Int,
    val receivedAtMillis: Long
)

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packetId: String,
    val packetTypeName: String,
    val priorityRank: Int,
    val queueTimeMillis: Long,
    val attempts: Int,
    val deliveryStatus: String,
    val detail: String,
    val timestampMillis: Long
)
