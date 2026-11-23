package com.example.network

import com.example.domain.model.Acknowledgement
import com.example.domain.model.AudioClipMetadata
import com.example.domain.model.AudioTransferStatus
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.DiagnosticLogEntry
import com.example.domain.model.EmergencyContext
import com.example.domain.model.EmergencyPacket
import com.example.domain.model.EmergencyReply
import com.example.domain.model.EmergencySession
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.example.domain.model.NoiseHandshakeState
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.domain.model.SessionStatus
import com.example.protocol.PacketSerializer
import com.example.security.IdentityManager
import com.example.security.RescueCrypto
import com.example.storage.RescueMeshRepository
import com.example.storage.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class SosWirePayload(
    val creatorName: String,
    val context: EmergencyContext,
    val location: LocationSnapshot?,
    val senderX25519Hex: String = ""
)

@Serializable
data class ReplyWirePayload(
    val replyId: String,
    val senderName: String,
    val message: String
)

@Serializable
data class AckWirePayload(
    val ackedPacketId: String,
    val isFinalDelivery: Boolean,
    val fromPeerId: String,
    val observedHopCount: Int
)

@Serializable
data class AudioMetaWirePayload(
    val clipId: String,
    val codec: String,
    val durationMillis: Long,
    val totalFragments: Int,
    val sha256Hex: String
)

@Serializable
data class AudioCompleteWirePayload(
    val clipId: String,
    val totalFragments: Int,
    val sha256Hex: String
)

@Serializable
data class SessionAcceptWirePayload(
    val responderPeerId: String,
    val responderName: String,
    val responderX25519Hex: String = "",
    val responderLocation: LocationSnapshot? = null
)

@Serializable
data class ImageMetaWirePayload(
    val imageId: String,
    val senderName: String,
    val totalFragments: Int,
    val sha256Hex: String
)

@Serializable
data class ImageCompleteWirePayload(
    val imageId: String,
    val totalFragments: Int,
    val sha256Hex: String
)

private val meshJson = Json { ignoreUnknownKeys = true }

/**
 * Validates raw incoming frames before admitting any packet into RescueMesh.
 */
class PacketReceiver(
    private val duplicateDetection: DuplicateDetectionManager,
    private val identityManager: IdentityManager
) {
    sealed class IntakeResult {
        data class Accepted(val packet: EmergencyPacket) : IntakeResult()
        data class Dropped(val reason: String) : IntakeResult()
    }

    suspend fun acceptRawFrame(rawBytes: ByteArray, nowMillis: Long = System.currentTimeMillis()): IntakeResult {
        val validation = PacketSerializer.validateAndDeserialize(rawBytes, nowMillis)
        if (validation is PacketSerializer.ValidationResult.Invalid) {
            return IntakeResult.Dropped(validation.reason)
        }
        val packet = (validation as PacketSerializer.ValidationResult.Valid).packet

        // Ignore our own packets looping back through relays
        if (packet.senderId.equals(identityManager.localPeerId, ignoreCase = true)) {
            return IntakeResult.Dropped("Own packet echo ignored")
        }

        if (duplicateDetection.isDuplicateOrMarkSeen(packet)) {
            return IntakeResult.Dropped("Duplicate packet suppressed: ${packet.packetId}")
        }

        // Enforce replay sequence protection per sender for control/SOS packets
        if (packet.type == PacketType.SOS || packet.type == PacketType.SESSION_RESOLVED) {
            if (!identityManager.checkAndRecordReplayWindow(packet.senderId, packet.sequenceNumber, packet.createdAtMillis)) {
                return IntakeResult.Dropped("Replay window rejected sequence ${packet.sequenceNumber}")
            }
        }

        return IntakeResult.Accepted(packet)
    }
}

/**
 * Handles packet routing:
 * - Local delivery + ACK_DELIVERED emission when intended for us or broadcast
 * - Relay persistence + ACK_STORED emission when acting as an intermediate relay
 * - Never marks a message delivered when only ACK_STORED is received
 */
class MessageRouter(
    private val repository: RescueMeshRepository,
    private val storeForwardQueue: StoreForwardQueue,
    private val identityManager: IdentityManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val onSessionTerminated: (suspend (String, SessionStatus) -> Unit)? = null,
    private val onIncomingAlert: suspend (EmergencySession) -> Unit
) {
    suspend fun routeIncomingPacket(packet: EmergencyPacket) {
        val localId = identityManager.localPeerId
        val settings = preferencesRepository.currentSettings()
        val isBroadcast = packet.recipientId == MeshConstants.BROADCAST_RECIPIENT_ID
        val isPairedEnvelope = packet.recipientId == MeshConstants.PAIRED_MULTI_RECIPIENT_ID
        val isAddressedToUs = packet.recipientId.equals(localId, ignoreCase = true) ||
            (packet.recipientId.length >= 8 && localId.startsWith(packet.recipientId, ignoreCase = true))
        val existingSession = repository.getSession(packet.sessionId)
        val isForLocalSession = existingSession != null && packet.type != PacketType.SOS

        // Update peer registry with sender's verified Ed25519 identity
        val existingPeer = repository.getPeer(packet.senderId)
        val attemptDecryptForUs = packet.isEncryptedPayload && (isAddressedToUs || isPairedEnvelope || isForLocalSession)
        val clearPayload: ByteArray? = if (packet.isEncryptedPayload) {
            if (attemptDecryptForUs) {
                identityManager.openAnyEncryptedPayload(packet.payload, packet.sessionId.toByteArray())
            } else {
                null // Intermediate relay node cannot decrypt recipient-targeted payload
            }
        } else {
            packet.payload
        }

        val canDeliverLocally = (clearPayload != null) && when (packet.type) {
            PacketType.SOS -> isBroadcast || isAddressedToUs || isPairedEnvelope
            PacketType.SESSION_RESOLVED -> true
            else -> isAddressedToUs || isForLocalSession
        }

        repository.upsertPeer(
            Peer(
                peerId = packet.senderId,
                displayName = existingPeer?.displayName ?: "Node-${packet.senderId.take(4)}",
                bluetoothAddress = existingPeer?.bluetoothAddress,
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(packet.senderPublicKeyEd25519),
                x25519PublicKeyHex = existingPeer?.x25519PublicKeyHex ?: "",
                connectionState = existingPeer?.connectionState ?: PeerConnectionState.CONNECTED,
                noiseState = if (packet.isEncryptedPayload && clearPayload != null) NoiseHandshakeState.ESTABLISHED
                else existingPeer?.noiseState ?: NoiseHandshakeState.NONE,
                rssi = existingPeer?.rssi ?: -68,
                isRelayEnabled = true,
                lastSeenAtMillis = System.currentTimeMillis()
            )
        )

        if (canDeliverLocally) {
            deliverLocally(packet, clearPayload, isAddressedToUs || isPairedEnvelope || isBroadcast || isForLocalSession)
        }

        // Blind Store-Carry-Forward relay when message is not solely addressed to us or uses paired multi-target forwarding
        val shouldRelay = settings.relayParticipationEnabled &&
            !isAddressedToUs &&
            packet.canRelay()

        if (shouldRelay) {
            val nextHop = (packet.hopCount + 1).coerceAtMost(254)
            val relayedPacket = packet.copy(hopCount = nextHop)
            storeForwardQueue.enqueue(relayedPacket, isOriginLocal = false)

            // Emit ACK_STORED back toward sender for intermediate relay custody
            if (packet.type == PacketType.SOS || packet.type == PacketType.EMERGENCY_REPLY || packet.type == PacketType.SESSION_ACCEPT) {
                enqueueAckPacket(
                    targetPeerId = packet.senderId,
                    sessionId = packet.sessionId,
                    ackedPacketId = packet.packetId,
                    isFinalDelivery = false,
                    observedHopCount = nextHop,
                    maxHopCount = settings.maxHopCount
                )
            }
        }

        repository.logDiagnostic(
            DiagnosticLogEntry(
                packetId = packet.packetId,
                packetType = packet.type,
                priority = packet.priority,
                queueTimeMillis = 0L,
                attempts = packet.hopCount,
                deliveryStatus = if (canDeliverLocally) DeliveryStatus.ACK_DELIVERED else DeliveryStatus.ACK_STORED,
                detail = "Received ${packet.type.name} from ${packet.senderId} (hop ${packet.hopCount})",
                timestampMillis = System.currentTimeMillis()
            )
        )
    }

    private suspend fun deliverLocally(
        packet: EmergencyPacket,
        clearPayload: ByteArray,
        isTargetMatch: Boolean
    ) {
        val now = System.currentTimeMillis()
        when (packet.type) {
            PacketType.SOS -> {
                val sos = runCatching {
                    meshJson.decodeFromString<SosWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return

                if (sos.senderX25519Hex.isNotBlank()) {
                    repository.getPeer(packet.senderId)?.let { peer ->
                        repository.upsertPeer(
                            peer.copy(
                                displayName = sos.creatorName.ifBlank { peer.displayName },
                                x25519PublicKeyHex = sos.senderX25519Hex
                            )
                        )
                    }
                }

                val existing = repository.getSession(packet.sessionId)
                val session = EmergencySession(
                    sessionId = packet.sessionId,
                    creatorPeerId = packet.senderId,
                    creatorDisplayName = sos.creatorName,
                    recipientPeerId = packet.recipientId,
                    isOutgoing = false,
                    context = sos.context,
                    location = sos.location,
                    responderLocation = existing?.responderLocation,
                    status = existing?.status ?: SessionStatus.ACTIVE,
                    deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                    sessionAccepted = existing?.sessionAccepted ?: false,
                    hopCount = packet.hopCount,
                    relayStoredCount = existing?.relayStoredCount ?: 0,
                    deliveredCount = existing?.deliveredCount ?: 1,
                    createdAtMillis = packet.createdAtMillis,
                    updatedAtMillis = now,
                    unread = existing?.sessionAccepted != true
                )
                repository.upsertSession(session)
                repository.upsertPacket(packet, DeliveryStatus.ACK_DELIVERED)
                onIncomingAlert(session)

                // Automatic ACK_DELIVERED back to sender when decrypted by target
                if (isTargetMatch) {
                    val settings = preferencesRepository.currentSettings()
                    enqueueAckPacket(
                        targetPeerId = packet.senderId,
                        sessionId = packet.sessionId,
                        ackedPacketId = packet.packetId,
                        isFinalDelivery = true,
                        observedHopCount = packet.hopCount,
                        maxHopCount = settings.maxHopCount
                    )
                }
            }

            PacketType.SESSION_ACCEPT -> {
                val accept = runCatching {
                    meshJson.decodeFromString<SessionAcceptWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                if (accept.responderX25519Hex.isNotBlank()) {
                    repository.getPeer(packet.senderId)?.let { peer ->
                        repository.upsertPeer(
                            peer.copy(
                                displayName = accept.responderName.ifBlank { peer.displayName },
                                x25519PublicKeyHex = accept.responderX25519Hex,
                                noiseState = NoiseHandshakeState.ESTABLISHED
                            )
                        )
                    }
                }
                val existing = repository.getSession(packet.sessionId) ?: return
                val resolvedRecipient = if (
                    existing.recipientPeerId == MeshConstants.PAIRED_MULTI_RECIPIENT_ID ||
                    existing.recipientPeerId == MeshConstants.BROADCAST_RECIPIENT_ID
                ) {
                    packet.senderId
                } else {
                    existing.recipientPeerId
                }
                val updated = existing.copy(
                    recipientPeerId = resolvedRecipient,
                    responderLocation = accept.responderLocation ?: existing.responderLocation,
                    deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                    sessionAccepted = true,
                    deliveredCount = maxOf(existing.deliveredCount, 1),
                    hopCount = maxOf(existing.hopCount, packet.hopCount),
                    updatedAtMillis = now
                )
                repository.upsertSession(updated)
                // Stop spraying original SOS beacon packets once session is accepted
                storeForwardQueue.allPendingEntries()
                    .filter { it.sessionId == packet.sessionId && it.typeCode == PacketType.SOS.code }
                    .forEach { storeForwardQueue.markDelivered(it.packetId) }
                repository.recordAcknowledgement(
                    Acknowledgement(
                        ackId = "accept_${packet.sessionId}_${packet.senderId}",
                        packetId = packet.sessionId,
                        sessionId = packet.sessionId,
                        fromPeerId = packet.senderId,
                        isFinalDelivery = true,
                        hopCount = packet.hopCount,
                        timestampMillis = now
                    )
                )
            }

            PacketType.LOCATION_UPDATE -> {
                val loc = runCatching {
                    meshJson.decodeFromString<LocationSnapshot>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                val existing = repository.getSession(packet.sessionId) ?: return
                val updated = if (packet.senderId.equals(existing.creatorPeerId, ignoreCase = true)) {
                    existing.copy(location = loc, updatedAtMillis = now)
                } else {
                    existing.copy(responderLocation = loc, updatedAtMillis = now)
                }
                repository.upsertSession(updated)
            }

            PacketType.ACK_STORED, PacketType.ACK_DELIVERED -> {
                val ackWire = runCatching {
                    meshJson.decodeFromString<AckWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                if (ackWire.isFinalDelivery && ackWire.ackedPacketId.startsWith("clip:")) {
                    val clipId = ackWire.ackedPacketId.removePrefix("clip:")
                    val existingClip = repository.getAudioClip(clipId)
                    if (existingClip != null) {
                        repository.upsertAudioClip(
                            existingClip.copy(status = AudioTransferStatus.REASSEMBLED)
                        )
                        val minTs = existingClip.createdAtMillis
                        val maxTs = existingClip.createdAtMillis + existingClip.totalFragments + 2L
                        val pendingAudio = storeForwardQueue.allPendingEntries().filter {
                            it.sessionId == packet.sessionId &&
                                (it.typeCode == PacketType.AUDIO_METADATA.code ||
                                    it.typeCode == PacketType.AUDIO_FRAGMENT.code ||
                                    it.typeCode == PacketType.AUDIO_COMPLETE.code) &&
                                it.createdAtMillis in minTs..maxTs
                        }
                        pendingAudio.forEach { storeForwardQueue.markDelivered(it.packetId) }
                    }
                } else {
                    repository.recordAcknowledgement(
                        Acknowledgement(
                            ackId = "${ackWire.ackedPacketId}_${ackWire.fromPeerId}_${ackWire.isFinalDelivery}",
                            packetId = ackWire.ackedPacketId,
                            sessionId = packet.sessionId,
                            fromPeerId = ackWire.fromPeerId,
                            isFinalDelivery = ackWire.isFinalDelivery,
                            hopCount = ackWire.observedHopCount,
                            timestampMillis = packet.createdAtMillis
                        )
                    )
                    if (ackWire.isFinalDelivery) {
                        // Delivered means done: drop the origin's queued copy (and any relay
                        // copies still circulating) so replies/location updates don't linger
                        // for the full 12h TTL and re-spray on every flush.
                        storeForwardQueue.remove(ackWire.ackedPacketId)
                        val isSessionAcceptanceAck = ackWire.ackedPacketId == packet.sessionId
                        repository.getSession(packet.sessionId)?.let { s ->
                            val resolvedRecipient = if (
                                s.recipientPeerId == MeshConstants.BROADCAST_RECIPIENT_ID ||
                                s.recipientPeerId == MeshConstants.PAIRED_MULTI_RECIPIENT_ID
                            ) {
                                ackWire.fromPeerId
                            } else {
                                s.recipientPeerId
                            }
                            repository.upsertSession(
                                s.copy(
                                    sessionAccepted = if (isSessionAcceptanceAck) true else s.sessionAccepted,
                                    deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                                    recipientPeerId = resolvedRecipient,
                                    deliveredCount = maxOf(s.deliveredCount, 1),
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }

            PacketType.EMERGENCY_REPLY, PacketType.EMERGENCY_STATUS -> {
                val replyWire = runCatching {
                    meshJson.decodeFromString<ReplyWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                repository.upsertReply(
                    EmergencyReply(
                        replyId = replyWire.replyId,
                        sessionId = packet.sessionId,
                        senderPeerId = packet.senderId,
                        senderName = replyWire.senderName,
                        message = replyWire.message,
                        isIncoming = true,
                        deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                        timestampMillis = packet.createdAtMillis
                    )
                )
                repository.getSession(packet.sessionId)?.let {
                    repository.upsertSession(it.copy(updatedAtMillis = now, unread = true))
                }
                if (isTargetMatch || packet.type == PacketType.EMERGENCY_REPLY) {
                    val settings = preferencesRepository.currentSettings()
                    enqueueAckPacket(
                        targetPeerId = packet.senderId,
                        sessionId = packet.sessionId,
                        ackedPacketId = packet.packetId,
                        isFinalDelivery = true,
                        observedHopCount = packet.hopCount,
                        maxHopCount = settings.maxHopCount
                    )
                }
            }

            PacketType.SESSION_RESOLVED -> {
                // Only the session creator may terminate a session; relays and bystanders
                // must never be able to kill someone else's emergency.
                val existing = repository.getSession(packet.sessionId)
                if (existing != null && !packet.senderId.equals(existing.creatorPeerId, ignoreCase = true)) {
                    return
                }
                val statusText = String(clearPayload, StandardCharsets.UTF_8).trim().uppercase()
                val resolvedStatus = if (statusText == "CANCELLED") SessionStatus.CANCELLED else SessionStatus.RESOLVED
                repository.clearPendingRelaysForSession(packet.sessionId)
                onSessionTerminated?.invoke(packet.sessionId, resolvedStatus)
                if (existing == null) return
                repository.upsertSession(
                    existing.copy(status = resolvedStatus, updatedAtMillis = now, unread = false)
                )
            }

            PacketType.AUDIO_METADATA -> {
                val meta = runCatching {
                    meshJson.decodeFromString<AudioMetaWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                val existing = repository.getAudioClip(meta.clipId)
                val preservedStatus = if (existing?.status == AudioTransferStatus.REASSEMBLED &&
                    existing.localFileId != null
                ) {
                    AudioTransferStatus.REASSEMBLED
                } else {
                    AudioTransferStatus.TRANSFERRING
                }
                repository.upsertAudioClip(
                    AudioClipMetadata(
                        clipId = meta.clipId,
                        sessionId = packet.sessionId,
                        senderPeerId = packet.senderId,
                        codec = meta.codec,
                        durationMillis = meta.durationMillis,
                        totalFragments = meta.totalFragments,
                        receivedFragments = existing?.receivedFragments ?: 0,
                        sha256Hex = meta.sha256Hex,
                        localFileId = existing?.localFileId,
                        status = preservedStatus,
                        isIncoming = true,
                        createdAtMillis = existing?.createdAtMillis ?: packet.createdAtMillis
                    )
                )
                val reassembled = repository.tryReassembleAudioClip(meta.clipId)
                val session = repository.getSession(packet.sessionId)
                val shouldSendClipAck = isTargetMatch && (session?.isOutgoing == true || session?.sessionAccepted == true)
                if (reassembled?.status == AudioTransferStatus.REASSEMBLED && shouldSendClipAck) {
                    sendClipDeliveredAck(packet.senderId, packet.sessionId, meta.clipId, packet.hopCount)
                }
            }

            PacketType.AUDIO_FRAGMENT -> {
                val frag = decodeAudioFragment(clearPayload) ?: return
                val count = repository.storeIncomingAudioFragment(
                    clipId = frag.clipId,
                    sessionId = packet.sessionId,
                    fragmentIndex = frag.index,
                    totalFragments = frag.total,
                    data = frag.data
                )
                val existing = repository.getAudioClip(frag.clipId)
                if (existing != null) {
                    repository.upsertAudioClip(existing.copy(receivedFragments = count))
                } else {
                    repository.upsertAudioClip(
                        AudioClipMetadata(
                            clipId = frag.clipId,
                            sessionId = packet.sessionId,
                            senderPeerId = packet.senderId,
                            codec = "RMAD_8K",
                            durationMillis = 5000L,
                            totalFragments = frag.total,
                            receivedFragments = count,
                            sha256Hex = "",
                            localFileId = null,
                            status = AudioTransferStatus.TRANSFERRING,
                            isIncoming = true,
                            createdAtMillis = packet.createdAtMillis
                        )
                    )
                }
                val reassembled = repository.tryReassembleAudioClip(frag.clipId)
                val session = repository.getSession(packet.sessionId)
                val shouldSendClipAck = isTargetMatch && (session?.isOutgoing == true || session?.sessionAccepted == true)
                if (reassembled?.status == AudioTransferStatus.REASSEMBLED && shouldSendClipAck) {
                    sendClipDeliveredAck(packet.senderId, packet.sessionId, frag.clipId, packet.hopCount)
                }
            }

            PacketType.AUDIO_COMPLETE -> {
                val complete = runCatching {
                    meshJson.decodeFromString<AudioCompleteWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                val reassembled = repository.tryReassembleAudioClip(complete.clipId)
                val session = repository.getSession(packet.sessionId)
                val shouldSendClipAck = isTargetMatch && (session?.isOutgoing == true || session?.sessionAccepted == true)
                if (reassembled?.status == AudioTransferStatus.REASSEMBLED && shouldSendClipAck) {
                    sendClipDeliveredAck(packet.senderId, packet.sessionId, complete.clipId, packet.hopCount)
                } else if (reassembled != null && reassembled.status == AudioTransferStatus.MISSING_FRAGMENTS) {
                    val missing = repository.getMissingAudioFragmentIndices(complete.clipId)
                    val settings = preferencesRepository.currentSettings()
                    enqueueStatusMessage(
                        sessionId = packet.sessionId,
                        recipientId = packet.senderId,
                        text = "AUDIO_NACK:${complete.clipId}:${missing.joinToString(",")}",
                        maxHopCount = settings.maxHopCount
                    )
                }
            }

            PacketType.IMAGE_METADATA -> {
                val meta = runCatching {
                    meshJson.decodeFromString<ImageMetaWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                val existing = repository.getImageMetadata(meta.imageId)
                repository.upsertImageMetadata(
                    com.example.storage.ImageClipMetadataEntity(
                        imageId = meta.imageId,
                        sessionId = packet.sessionId,
                        senderPeerId = packet.senderId,
                        senderName = meta.senderName,
                        totalFragments = meta.totalFragments,
                        sha256Hex = meta.sha256Hex,
                        localFileId = existing?.localFileId,
                        isIncoming = true,
                        createdAtMillis = packet.createdAtMillis
                    )
                )
                val assembledFileId = repository.tryReassembleImageClip(meta.imageId)
                if (assembledFileId != null) {
                    recordIncomingImageReply(packet, meta.imageId, meta.senderName, assembledFileId, now)
                }
            }

            PacketType.IMAGE_FRAGMENT -> {
                val frag = decodeAudioFragment(clearPayload) ?: return
                repository.storeIncomingImageFragment(
                    imageId = frag.clipId,
                    sessionId = packet.sessionId,
                    fragmentIndex = frag.index,
                    totalFragments = frag.total,
                    data = frag.data
                )
                val meta = repository.getImageMetadata(frag.clipId)
                if (meta != null) {
                    val assembledFileId = repository.tryReassembleImageClip(frag.clipId)
                    if (assembledFileId != null) {
                        recordIncomingImageReply(packet, frag.clipId, meta.senderName, assembledFileId, now)
                    }
                }
            }

            PacketType.IMAGE_COMPLETE -> {
                val complete = runCatching {
                    meshJson.decodeFromString<ImageCompleteWirePayload>(String(clearPayload, StandardCharsets.UTF_8))
                }.getOrNull() ?: return
                val meta = repository.getImageMetadata(complete.imageId)
                val assembledFileId = repository.tryReassembleImageClip(complete.imageId)
                if (assembledFileId != null) {
                    recordIncomingImageReply(
                        packet = packet,
                        imageId = complete.imageId,
                        senderName = meta?.senderName ?: "Node-${packet.senderId.take(4)}",
                        fileId = assembledFileId,
                        nowMillis = now
                    )
                }
            }
        }
    }

    private suspend fun recordIncomingImageReply(
        packet: EmergencyPacket,
        imageId: String,
        senderName: String,
        fileId: String,
        nowMillis: Long
    ) {
        repository.upsertReply(
            EmergencyReply(
                replyId = "img_$imageId",
                sessionId = packet.sessionId,
                senderPeerId = packet.senderId,
                senderName = senderName,
                message = "Camera snapshot captured",
                isIncoming = true,
                deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                timestampMillis = packet.createdAtMillis,
                imageFileId = fileId
            )
        )
        repository.getSession(packet.sessionId)?.let {
            repository.upsertSession(it.copy(updatedAtMillis = nowMillis))
        }
    }

    private suspend fun sendClipDeliveredAck(
        senderPeerId: String,
        sessionId: String,
        clipId: String,
        observedHopCount: Int
    ) {
        val settings = preferencesRepository.currentSettings()
        enqueueAckPacket(
            targetPeerId = senderPeerId,
            sessionId = sessionId,
            ackedPacketId = "clip:$clipId",
            isFinalDelivery = true,
            observedHopCount = observedHopCount,
            maxHopCount = settings.maxHopCount
        )
    }

    suspend fun enqueueAckPacket(
        targetPeerId: String,
        sessionId: String,
        ackedPacketId: String,
        isFinalDelivery: Boolean,
        observedHopCount: Int,
        maxHopCount: Int
    ) {
        val type = if (isFinalDelivery) PacketType.ACK_DELIVERED else PacketType.ACK_STORED
        val payloadBytes = meshJson.encodeToString(
            AckWirePayload.serializer(),
            AckWirePayload(
                ackedPacketId = ackedPacketId,
                isFinalDelivery = isFinalDelivery,
                fromPeerId = identityManager.localPeerId,
                observedHopCount = observedHopCount
            )
        ).toByteArray(StandardCharsets.UTF_8)

        val packet = buildSignedPacket(
            sessionId = sessionId,
            recipientId = targetPeerId,
            type = type,
            payload = payloadBytes,
            maxHopCount = maxHopCount
        )
        storeForwardQueue.enqueue(packet, isOriginLocal = true)
    }

    private suspend fun enqueueStatusMessage(
        sessionId: String,
        recipientId: String,
        text: String,
        maxHopCount: Int
    ) {
        val payload = meshJson.encodeToString(
            ReplyWirePayload.serializer(),
            ReplyWirePayload(
                replyId = UUID.randomUUID().toString(),
                senderName = identityManager.localPeerId,
                message = text
            )
        ).toByteArray(StandardCharsets.UTF_8)
        val packet = buildSignedPacket(
            sessionId = sessionId,
            recipientId = recipientId,
            type = PacketType.EMERGENCY_STATUS,
            payload = payload,
            maxHopCount = maxHopCount
        )
        storeForwardQueue.enqueue(packet, isOriginLocal = true)
    }

    fun buildSignedPacket(
        sessionId: String,
        recipientId: String,
        type: PacketType,
        payload: ByteArray,
        maxHopCount: Int,
        isEncrypted: Boolean = false,
        createdAtOverrideMillis: Long? = null
    ): EmergencyPacket {
        val now = createdAtOverrideMillis ?: System.currentTimeMillis()
        val packetId = UUID.randomUUID().toString().replace("-", "").take(16)
        val seq = identityManager.nextSequenceNumber()
        val priority = PacketPriority.defaultForType(type)
        val expiresAt = now + MeshConstants.DEFAULT_PACKET_TTL_MILLIS
        val payloadHash = RescueCrypto.sha256(payload)
        val signable = PacketSerializer.buildSignableBytes(
            packetId = packetId,
            sessionId = sessionId,
            senderId = identityManager.localPeerId,
            recipientId = recipientId,
            type = type,
            priority = priority,
            sequenceNumber = seq,
            createdAtMillis = now,
            expiresAtMillis = expiresAt,
            maxHopCount = maxHopCount,
            payloadHash = payloadHash
        )
        val signature = RescueCrypto.signEd25519(identityManager.identity.ed25519Private, signable)
        return EmergencyPacket(
            packetId = packetId,
            sessionId = sessionId,
            senderId = identityManager.localPeerId,
            recipientId = recipientId,
            type = type,
            priority = priority,
            createdAtMillis = now,
            expiresAtMillis = expiresAt,
            sequenceNumber = seq,
            hopCount = 0,
            maxHopCount = maxHopCount,
            payload = payload,
            payloadHash = payloadHash,
            authTag = signature,
            senderPublicKeyEd25519 = identityManager.identity.ed25519Public,
            isEncryptedPayload = isEncrypted
        )
    }

    private data class DecodedAudioFragment(
        val clipId: String,
        val index: Int,
        val total: Int,
        val data: ByteArray
    )

    private fun decodeAudioFragment(bytes: ByteArray): DecodedAudioFragment? {
        return try {
            val buf = ByteBuffer.wrap(bytes)
            val idLen = buf.get().toInt() and 0xFF
            if (idLen == 0 || buf.remaining() < idLen + 8) return null
            val idBytes = ByteArray(idLen).also { buf.get(it) }
            val index = buf.getShort().toInt() and 0xFFFF
            val total = buf.getShort().toInt() and 0xFFFF
            if (total == 0 || index >= total) return null
            val dataLen = buf.getInt()
            if (dataLen <= 0 || dataLen > buf.remaining()) return null
            val data = ByteArray(dataLen).also { buf.get(it) }
            DecodedAudioFragment(String(idBytes, StandardCharsets.UTF_8), index, total, data)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun encodeAudioFragment(clipId: String, index: Int, total: Int, data: ByteArray): ByteArray {
            val idBytes = clipId.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(1 + idBytes.size + 2 + 2 + 4 + data.size)
            buf.put(idBytes.size.toByte())
            buf.put(idBytes)
            buf.putShort(index.toShort())
            buf.putShort(total.toShort())
            buf.putInt(data.size)
            buf.put(data)
            return buf.array()
        }
    }
}

/**
 * Coordinates BLE transport lifecycle, peer discovery, incoming packet verification,
 * and priority-ordered store-carry-forward flushing across reconnections and restarts.
 */
class MeshCoordinator(
    private val transport: MeshTransport,
    private val repository: RescueMeshRepository,
    val peerDiscoveryManager: PeerDiscoveryManager,
    val storeForwardQueue: StoreForwardQueue,
    val priorityScheduler: PriorityScheduler,
    val packetReceiver: PacketReceiver,
    val messageRouter: MessageRouter,
    private val onPeriodicLocationTick: (suspend () -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private var loopJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val transportStatus: StateFlow<TransportStatus> = transport.status
    val diagnosticMessage: StateFlow<String> = transport.diagnosticMessage

    private val _incomingAlertEvents = MutableSharedFlow<EmergencySession>(extraBufferCapacity = 16)
    val incomingAlertEvents = _incomingAlertEvents.asSharedFlow()

    fun start() {
        if (loopJob != null) {
            if (transport.status.value != TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING) {
                scope.launch { transport.startTransport() }
            }
            return
        }
        _isRunning.value = true
        loopJob = scope.launch {
            // Clear stale CONNECTED states persisted across previous app runs
            repository.resetAllPeerConnections()
            transport.startTransport()
            // Sync active SOS spray mode automatically based on local active outgoing SOS
            launch {
                repository.observeActiveOutgoingSession().collect { activeSession ->
                    (transport as? BleGattMeshTransport)?.setSosSprayActive(
                        active = activeSession != null,
                        sessionId = activeSession?.sessionId
                    )
                }
            }
            // Collect discovered/connected peers
            launch {
                transport.discoverPeers().collect { peer ->
                    peerDiscoveryManager.onPeerDiscoveredOrUpdated(peer)
                    if (peer.connectionState == PeerConnectionState.CONNECTED) {
                        storeForwardQueue.resetBackoffsNow()
                        flushPendingQueue()
                    }
                }
            }
            // Collect incoming raw BLE frames
            launch {
                transport.receiveData().collect { frame ->
                    when (val res = packetReceiver.acceptRawFrame(frame.rawBytes)) {
                        is PacketReceiver.IntakeResult.Accepted -> {
                            messageRouter.routeIncomingPacket(res.packet)
                        }
                        is PacketReceiver.IntakeResult.Dropped -> Unit
                    }
                }
            }
            // 5-second live GPS ping loop while any session is active
            launch {
                while (isActive) {
                    delay(MeshConstants.LOCATION_UPDATE_INTERVAL_MILLIS)
                    runCatching { onPeriodicLocationTick?.invoke() }
                }
            }
            // Periodic store-carry-forward flush (faster spray cadence when SOS is active)
            while (isActive) {
                flushPendingQueue()
                val hasActiveSos = repository.observeActiveOutgoingSession().first() != null
                delay(if (hasActiveSos) 2_000L else 3_500L)
            }
        }
    }

    suspend fun restartDiscovery() {
        if (loopJob == null) {
            start()
        } else {
            transport.restartDiscovery()
        }
    }

    suspend fun stop() {
        _isRunning.value = false
        loopJob?.cancel()
        loopJob = null
        transport.stopTransport()
        repository.resetAllPeerConnections()
    }

    suspend fun flushPendingQueue(): Int = flushMutex.withLock {
        val now = System.currentTimeMillis()
        val liveIds = transport.connectedPeerIds()
        val liveLinks = transport.hasLiveConnections()
        if (liveIds.isEmpty() && !liveLinks) {
            return@withLock 0
        }
        val dbPeersById = repository.observePeers().first().associateBy { it.peerId.uppercase() }
        val connectedPeers = liveIds.map { pid ->
            dbPeersById[pid.uppercase()] ?: Peer(
                peerId = pid,
                displayName = "Node-${pid.take(4)}",
                connectionState = PeerConnectionState.CONNECTED,
                lastSeenAtMillis = now
            )
        }
        priorityScheduler.dispatchNextBatch(
            connectedPeers = connectedPeers,
            sendBytes = { peerId, bytes -> transport.sendData(peerId, bytes) },
            hasLiveTransportLinks = liveLinks
        )
    }

    suspend fun notifyAlert(session: EmergencySession) {
        _incomingAlertEvents.emit(session)
    }
}
