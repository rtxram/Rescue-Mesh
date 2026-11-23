package com.example.domain.usecase

import com.example.domain.model.DeliveryStatus
import com.example.domain.model.EmergencyContext
import com.example.domain.model.EmergencyPacket
import com.example.domain.model.EmergencyReply
import com.example.domain.model.EmergencySession
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.example.domain.model.PacketType
import com.example.domain.model.SessionStatus
import com.example.network.AckWirePayload
import com.example.network.MeshCoordinator
import com.example.network.MessageRouter
import com.example.network.PacketReceiver
import com.example.network.ReplyWirePayload
import com.example.network.SosWirePayload
import com.example.network.StoreForwardQueue
import com.example.security.IdentityManager
import com.example.security.RescueCrypto
import com.example.storage.RescueMeshRepository
import com.example.storage.UserPreferencesRepository
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID

private val useCaseJson = Json { ignoreUnknownKeys = true }

class CreateSosUseCase(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase
) {
    suspend operator fun invoke(
        context: EmergencyContext,
        location: LocationSnapshot?,
        recipientPeerId: String = MeshConstants.PAIRED_MULTI_RECIPIENT_ID
    ): EmergencySession {
        val now = System.currentTimeMillis()
        val settings = preferencesRepository.currentSettings()
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)

        val pairedContacts = repository.getAllContacts()
        val explicitContact = if (
            recipientPeerId != MeshConstants.BROADCAST_RECIPIENT_ID &&
            recipientPeerId != MeshConstants.PAIRED_MULTI_RECIPIENT_ID
        ) {
            repository.getContact(recipientPeerId)
        } else null

        val isBroadcastRequested = settings.sendToAllNodes || recipientPeerId == MeshConstants.BROADCAST_RECIPIENT_ID
        val targetKeys: List<ByteArray>
        val effectiveRecipientId: String
        when {
            isBroadcastRequested -> {
                targetKeys = emptyList()
                effectiveRecipientId = MeshConstants.BROADCAST_RECIPIENT_ID
            }
            explicitContact != null && explicitContact.x25519PublicKeyHex.isNotBlank() -> {
                targetKeys = listOf(RescueCrypto.hexToBytes(explicitContact.x25519PublicKeyHex))
                effectiveRecipientId = explicitContact.peerId
            }
            pairedContacts.isNotEmpty() -> {
                targetKeys = pairedContacts.mapNotNull { c ->
                    RescueCrypto.hexToBytes(c.x25519PublicKeyHex).takeIf { it.size == 32 }
                }
                if (targetKeys.isEmpty()) {
                    throw IllegalStateException("NO_PAIRED_CONTACTS")
                }
                effectiveRecipientId = if (pairedContacts.size == 1) {
                    pairedContacts.first().peerId
                } else {
                    MeshConstants.PAIRED_MULTI_RECIPIENT_ID
                }
            }
            else -> {
                throw IllegalStateException("NO_PAIRED_CONTACTS")
            }
        }

        val session = EmergencySession(
            sessionId = sessionId,
            creatorPeerId = identityManager.localPeerId,
            creatorDisplayName = settings.deviceAlias,
            recipientPeerId = effectiveRecipientId,
            isOutgoing = true,
            context = context,
            location = location,
            status = SessionStatus.ACTIVE,
            deliveryStatus = DeliveryStatus.CREATED,
            sessionAccepted = false,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        repository.upsertSession(session)

        val wirePayload = useCaseJson.encodeToString(
            SosWirePayload.serializer(),
            SosWirePayload(
                creatorName = settings.deviceAlias,
                context = context,
                location = location,
                senderX25519Hex = RescueCrypto.bytesToHex(identityManager.identity.x25519Public)
            )
        ).toByteArray(StandardCharsets.UTF_8)

        val finalPayload: ByteArray
        val encrypted: Boolean
        if (targetKeys.isNotEmpty()) {
            finalPayload = identityManager.sealForPairedContacts(
                recipientX25519PublicKeys = targetKeys,
                plaintext = wirePayload,
                aad = sessionId.toByteArray()
            )
            encrypted = true
        } else {
            finalPayload = wirePayload
            encrypted = false
        }

        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = effectiveRecipientId,
            type = PacketType.SOS,
            payload = finalPayload,
            isEncrypted = encrypted
        )

        val queuedSession = session.copy(deliveryStatus = DeliveryStatus.QUEUED_RELAY)
        repository.upsertSession(queuedSession)
        return queuedSession
    }
}

class SendEmergencyPacketUseCase(
    private val repository: RescueMeshRepository,
    private val messageRouter: MessageRouter,
    private val storeForwardQueue: StoreForwardQueue,
    private val preferencesRepository: UserPreferencesRepository,
    private val coordinatorProvider: () -> MeshCoordinator
) {
    suspend operator fun invoke(
        sessionId: String,
        recipientId: String,
        type: PacketType,
        payload: ByteArray,
        isEncrypted: Boolean = false,
        flushImmediately: Boolean = true,
        createdAtOverrideMillis: Long? = null
    ): EmergencyPacket {
        val settings = preferencesRepository.currentSettings()
        val packet = messageRouter.buildSignedPacket(
            sessionId = sessionId,
            recipientId = recipientId,
            type = type,
            payload = payload,
            maxHopCount = settings.maxHopCount,
            isEncrypted = isEncrypted,
            createdAtOverrideMillis = createdAtOverrideMillis
        )
        repository.upsertPacket(packet, DeliveryStatus.QUEUED_RELAY)
        storeForwardQueue.enqueue(packet, isOriginLocal = true)
        if (flushImmediately) {
            coordinatorProvider().flushPendingQueue()
        }
        return packet
    }
}

class ReceiveEmergencyPacketUseCase(
    private val packetReceiver: PacketReceiver,
    private val messageRouter: MessageRouter
) {
    suspend operator fun invoke(rawBytes: ByteArray): PacketReceiver.IntakeResult {
        val result = packetReceiver.acceptRawFrame(rawBytes)
        if (result is PacketReceiver.IntakeResult.Accepted) {
            messageRouter.routeIncomingPacket(result.packet)
        }
        return result
    }
}

class StoreRelayPacketUseCase(
    private val storeForwardQueue: StoreForwardQueue
) {
    suspend operator fun invoke(packet: EmergencyPacket) {
        if (packet.canRelay() && !packet.isExpired()) {
            storeForwardQueue.enqueue(
                packet.copy(hopCount = (packet.hopCount + 1).coerceAtMost(254)),
                isOriginLocal = false
            )
        }
    }
}

class ForwardPendingMessagesUseCase(
    private val coordinatorProvider: () -> MeshCoordinator
) {
    suspend operator fun invoke(): Int = coordinatorProvider().flushPendingQueue()
}

class AcknowledgeEmergencyUseCase(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val messageRouter: MessageRouter,
    private val preferencesRepository: UserPreferencesRepository,
    private val coordinatorProvider: () -> MeshCoordinator
) {
    suspend operator fun invoke(
        sessionId: String,
        isFinalDelivery: Boolean = true,
        responderLocation: LocationSnapshot? = null
    ) {
        val session = repository.getSession(sessionId) ?: return
        val settings = preferencesRepository.currentSettings()
        val now = System.currentTimeMillis()

        repository.upsertSession(
            session.copy(
                sessionAccepted = isFinalDelivery || session.sessionAccepted,
                responderLocation = responderLocation ?: session.responderLocation,
                unread = false,
                updatedAtMillis = now
            )
        )

        if (isFinalDelivery) {
            val acceptWire = useCaseJson.encodeToString(
                com.example.network.SessionAcceptWirePayload.serializer(),
                com.example.network.SessionAcceptWirePayload(
                    responderPeerId = identityManager.localPeerId,
                    responderName = settings.deviceAlias,
                    responderX25519Hex = RescueCrypto.bytesToHex(identityManager.identity.x25519Public),
                    responderLocation = responderLocation
                )
            ).toByteArray(StandardCharsets.UTF_8)

            val (acceptPayload, acceptEncrypted) = if (session.recipientPeerId == MeshConstants.BROADCAST_RECIPIENT_ID) {
                acceptWire to false
            } else {
                sealPayloadForSessionTarget(
                    repository = repository,
                    identityManager = identityManager,
                    sessionId = sessionId,
                    targetPeerId = session.creatorPeerId,
                    plaintext = acceptWire
                )
            }

            val acceptPacket = messageRouter.buildSignedPacket(
                sessionId = sessionId,
                recipientId = session.creatorPeerId,
                type = PacketType.SESSION_ACCEPT,
                payload = acceptPayload,
                maxHopCount = settings.maxHopCount,
                isEncrypted = acceptEncrypted
            )
            repository.upsertPacket(acceptPacket, DeliveryStatus.QUEUED_RELAY)
            coordinatorProvider().storeForwardQueue.enqueue(acceptPacket, isOriginLocal = true)
        }

        val payload = useCaseJson.encodeToString(
            AckWirePayload.serializer(),
            AckWirePayload(
                ackedPacketId = sessionId,
                isFinalDelivery = isFinalDelivery,
                fromPeerId = identityManager.localPeerId,
                observedHopCount = session.hopCount
            )
        ).toByteArray(StandardCharsets.UTF_8)
        val packet = messageRouter.buildSignedPacket(
            sessionId = sessionId,
            recipientId = session.creatorPeerId,
            type = if (isFinalDelivery) PacketType.ACK_DELIVERED else PacketType.ACK_STORED,
            payload = payload,
            maxHopCount = settings.maxHopCount
        )
        repository.upsertPacket(packet, DeliveryStatus.QUEUED_RELAY)
        coordinatorProvider().storeForwardQueue.enqueue(packet, isOriginLocal = true)
        coordinatorProvider().flushPendingQueue()
        repository.markSessionRead(sessionId)
    }
}

class SendEmergencyReplyUseCase(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase
) {
    suspend operator fun invoke(sessionId: String, messageText: String): EmergencyReply? {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty()) return null
        val session = repository.getSession(sessionId) ?: return null
        val settings = preferencesRepository.currentSettings()
        val now = System.currentTimeMillis()
        val replyId = UUID.randomUUID().toString().replace("-", "").take(16)

        val reply = EmergencyReply(
            replyId = replyId,
            sessionId = sessionId,
            senderPeerId = identityManager.localPeerId,
            senderName = settings.deviceAlias,
            message = trimmed,
            isIncoming = false,
            deliveryStatus = DeliveryStatus.QUEUED_RELAY,
            timestampMillis = now
        )
        repository.upsertReply(reply)

        val targetPeerId = if (session.isOutgoing) session.recipientPeerId else session.creatorPeerId
        val wire = useCaseJson.encodeToString(
            ReplyWirePayload.serializer(),
            ReplyWirePayload(
                replyId = replyId,
                senderName = settings.deviceAlias,
                message = trimmed
            )
        ).toByteArray(StandardCharsets.UTF_8)

        val (finalPayload, encrypted) = sealPayloadForSessionTarget(
            repository = repository,
            identityManager = identityManager,
            sessionId = sessionId,
            targetPeerId = targetPeerId,
            plaintext = wire
        )

        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = targetPeerId,
            type = PacketType.EMERGENCY_REPLY,
            payload = finalPayload,
            isEncrypted = encrypted
        )
        return reply
    }
}

class SendEmergencyImageUseCase(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase,
    private val forwardPendingMessagesUseCase: ForwardPendingMessagesUseCase
) {
    suspend operator fun invoke(sessionId: String, jpegBytes: ByteArray): EmergencyReply? {
        if (jpegBytes.isEmpty()) return null
        val session = repository.getSession(sessionId) ?: return null
        val settings = preferencesRepository.currentSettings()
        val imageId = UUID.randomUUID().toString().replace("-", "").take(12)
        val localFileId = repository.audioFileStore.saveImageBytes(imageId, jpegBytes)
        val baseTime = System.currentTimeMillis()
        val replyId = "img_$imageId"

        val localReply = EmergencyReply(
            replyId = replyId,
            sessionId = sessionId,
            senderPeerId = identityManager.localPeerId,
            senderName = settings.deviceAlias,
            message = "Camera snapshot captured",
            isIncoming = false,
            deliveryStatus = DeliveryStatus.QUEUED_RELAY,
            timestampMillis = baseTime,
            imageFileId = localFileId
        )
        repository.upsertReply(localReply)

        val shaHex = RescueCrypto.bytesToHex(RescueCrypto.sha256(jpegBytes))
        val fragments = com.example.audio.AudioClipManager.fragmentBytes(jpegBytes, 1024)
        val total = fragments.size
        val targetPeerId = if (session.isOutgoing) session.recipientPeerId else session.creatorPeerId

        val metaWire = useCaseJson.encodeToString(
            com.example.network.ImageMetaWirePayload.serializer(),
            com.example.network.ImageMetaWirePayload(
                imageId = imageId,
                senderName = settings.deviceAlias,
                totalFragments = total,
                sha256Hex = shaHex
            )
        ).toByteArray(StandardCharsets.UTF_8)

        val (sealedMeta, encMeta) = sealPayloadForSessionTarget(
            repository, identityManager, sessionId, targetPeerId, metaWire
        )
        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = targetPeerId,
            type = PacketType.IMAGE_METADATA,
            payload = sealedMeta,
            isEncrypted = encMeta,
            flushImmediately = false,
            createdAtOverrideMillis = baseTime
        )

        for (idx in fragments.indices) {
            val encodedFrag = MessageRouter.encodeAudioFragment(imageId, idx, total, fragments[idx])
            val (sealedFrag, encFrag) = sealPayloadForSessionTarget(
                repository, identityManager, sessionId, targetPeerId, encodedFrag
            )
            sendEmergencyPacketUseCase(
                sessionId = sessionId,
                recipientId = targetPeerId,
                type = PacketType.IMAGE_FRAGMENT,
                payload = sealedFrag,
                isEncrypted = encFrag,
                flushImmediately = false,
                createdAtOverrideMillis = baseTime + idx + 1L
            )
        }

        val completeWire = useCaseJson.encodeToString(
            com.example.network.ImageCompleteWirePayload.serializer(),
            com.example.network.ImageCompleteWirePayload(imageId, total, shaHex)
        ).toByteArray(StandardCharsets.UTF_8)
        val (sealedComplete, encComplete) = sealPayloadForSessionTarget(
            repository, identityManager, sessionId, targetPeerId, completeWire
        )
        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = targetPeerId,
            type = PacketType.IMAGE_COMPLETE,
            payload = sealedComplete,
            isEncrypted = encComplete,
            flushImmediately = false,
            createdAtOverrideMillis = baseTime + total + 1L
        )

        forwardPendingMessagesUseCase()
        return localReply
    }
}

class SendLiveLocationUpdateUseCase(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase
) {
    suspend operator fun invoke(session: EmergencySession, location: LocationSnapshot) {
        val targetPeerId = if (session.isOutgoing) session.recipientPeerId else session.creatorPeerId
        val updatedSession = if (session.isOutgoing) {
            session.copy(location = location, updatedAtMillis = location.timestampMillis)
        } else {
            session.copy(responderLocation = location, updatedAtMillis = location.timestampMillis)
        }
        repository.upsertSession(updatedSession)

        val wire = useCaseJson.encodeToString(LocationSnapshot.serializer(), location)
            .toByteArray(StandardCharsets.UTF_8)
        val (finalPayload, encrypted) = sealPayloadForSessionTarget(
            repository = repository,
            identityManager = identityManager,
            sessionId = session.sessionId,
            targetPeerId = targetPeerId,
            plaintext = wire
        )
        sendEmergencyPacketUseCase(
            sessionId = session.sessionId,
            recipientId = targetPeerId,
            type = PacketType.LOCATION_UPDATE,
            payload = finalPayload,
            isEncrypted = encrypted,
            flushImmediately = false
        )
    }
}

internal suspend fun sealPayloadForSessionTarget(
    repository: RescueMeshRepository,
    identityManager: IdentityManager,
    sessionId: String,
    targetPeerId: String,
    plaintext: ByteArray
): Pair<ByteArray, Boolean> {
    if (targetPeerId == MeshConstants.BROADCAST_RECIPIENT_ID) {
        return plaintext to false
    }
    if (targetPeerId == MeshConstants.PAIRED_MULTI_RECIPIENT_ID) {
        val keys = repository.getAllContacts().mapNotNull { c ->
            RescueCrypto.hexToBytes(c.x25519PublicKeyHex).takeIf { it.size == 32 }
        }
        if (keys.isNotEmpty()) {
            return identityManager.sealForPairedContacts(keys, plaintext, sessionId.toByteArray()) to true
        }
        return plaintext to false
    }
    val contact = repository.getContact(targetPeerId)
    val peer = repository.getPeer(targetPeerId)
    val recipientX25519Hex = contact?.x25519PublicKeyHex?.ifBlank { null }
        ?: peer?.x25519PublicKeyHex?.ifBlank { null }
    return if (recipientX25519Hex != null) {
        identityManager.sealForRecipient(
            RescueCrypto.hexToBytes(recipientX25519Hex),
            plaintext,
            sessionId.toByteArray()
        ) to true
    } else {
        plaintext to false
    }
}

class ResolveEmergencyUseCase(
    private val repository: RescueMeshRepository,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase
) {
    suspend operator fun invoke(sessionId: String, cancelled: Boolean = false) {
        val session = repository.getSession(sessionId) ?: return
        val now = System.currentTimeMillis()
        val updated = session.copy(
            status = if (cancelled) SessionStatus.CANCELLED else SessionStatus.RESOLVED,
            updatedAtMillis = now,
            unread = false
        )
        repository.upsertSession(updated)
        // Purge stale SOS/audio/reply relays first, then spray broadcast Priority-1 SESSION_RESOLVED
        repository.clearPendingRelaysForSession(sessionId)
        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SESSION_RESOLVED,
            payload = (if (cancelled) "CANCELLED" else "RESOLVED").toByteArray(StandardCharsets.UTF_8),
            isEncrypted = false,
            flushImmediately = true
        )
    }
}
