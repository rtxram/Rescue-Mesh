package com.example.storage

import com.example.domain.model.Acknowledgement
import com.example.domain.model.AudioClipMetadata
import com.example.domain.model.AudioTransferStatus
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.DiagnosticLogEntry
import com.example.domain.model.EmergencyContact
import com.example.domain.model.EmergencyContext
import com.example.domain.model.EmergencyPacket
import com.example.domain.model.EmergencyReply
import com.example.domain.model.EmergencySession
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.NoiseHandshakeState
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RescueMeshRepository(
    private val db: RescueMeshDatabase,
    val audioFileStore: AudioFileStore
) {
    private val sessionDao = db.emergencySessionDao()
    private val packetDao = db.emergencyPacketDao()
    private val relayDao = db.relayQueueDao()
    private val seenDao = db.seenPacketDao()
    private val ackDao = db.acknowledgementDao()
    private val replyDao = db.emergencyReplyDao()
    private val peerDao = db.peerDao()
    private val contactDao = db.emergencyContactDao()
    private val audioDao = db.audioClipDao()
    private val imageDao = db.imageClipDao()
    private val logDao = db.diagnosticLogDao()

    fun observeAllSessions(): Flow<List<EmergencySession>> =
        sessionDao.observeAllSessions().map { list -> list.map { it.toDomain() } }

    fun observeActiveOutgoingSession(): Flow<EmergencySession?> =
        sessionDao.observeActiveOutgoingSession().map { it?.toDomain() }

    fun observeSession(sessionId: String): Flow<EmergencySession?> =
        sessionDao.observeSession(sessionId).map { it?.toDomain() }

    fun observeIncomingActiveCount(): Flow<Int> = sessionDao.observeIncomingActiveCount()

    suspend fun getSession(sessionId: String): EmergencySession? = sessionDao.getSession(sessionId)?.toDomain()

    suspend fun upsertSession(session: EmergencySession) = sessionDao.upsert(session.toEntity())

    suspend fun markSessionRead(sessionId: String) = sessionDao.markRead(sessionId)

    suspend fun upsertPacket(packet: EmergencyPacket, status: DeliveryStatus) {
        packetDao.upsert(packet.toEntity(status))
    }

    suspend fun updatePacketDeliveryStatus(packetId: String, status: DeliveryStatus) {
        packetDao.updateDeliveryStatus(packetId, status.name)
    }

    suspend fun enqueueRelay(entry: RelayQueueEntity) = relayDao.enqueue(entry)

    suspend fun getDueRelayBatch(nowMillis: Long, limit: Int = 20): List<RelayQueueEntity> =
        relayDao.getNextDueBatch(nowMillis, limit)

    suspend fun getAllPendingRelays(nowMillis: Long): List<RelayQueueEntity> =
        relayDao.getAllPending(nowMillis)

    suspend fun getRelayEntry(packetId: String): RelayQueueEntity? = relayDao.getEntry(packetId)

    suspend fun removeRelayEntry(packetId: String) = relayDao.remove(packetId)

    suspend fun purgeExpiredRelays(nowMillis: Long): Int = relayDao.purgeExpired(nowMillis)

    fun observePendingRelayCount(): Flow<Int> = relayDao.observePendingCount()

    suspend fun countPendingRelaysForSession(sessionId: String, nowMillis: Long = System.currentTimeMillis()): Int =
        relayDao.countForSession(sessionId, nowMillis)

    suspend fun resetAllRelayBackoffs(nowMillis: Long = System.currentTimeMillis()) =
        relayDao.resetAllRetryTimes(nowMillis)

    suspend fun markPacketSeen(packetId: String, senderId: String, expiresAtMillis: Long): Boolean {
        val rowId = seenDao.insert(
            SeenPacketEntity(
                packetId = packetId,
                senderId = senderId,
                seenAtMillis = System.currentTimeMillis(),
                expiresAtMillis = expiresAtMillis
            )
        )
        return rowId != -1L
    }

    suspend fun hasSeenPacket(packetId: String): Boolean = seenDao.hasSeen(packetId)

    suspend fun purgeExpiredSeen(nowMillis: Long) = seenDao.purgeExpired(nowMillis)

    suspend fun recordAcknowledgement(ack: Acknowledgement) {
        ackDao.upsert(
            AcknowledgementEntity(
                ackId = ack.ackId,
                packetId = ack.packetId,
                sessionId = ack.sessionId,
                fromPeerId = ack.fromPeerId,
                isFinalDelivery = ack.isFinalDelivery,
                hopCount = ack.hopCount,
                timestampMillis = ack.timestampMillis
            )
        )
        val session = sessionDao.getSession(ack.sessionId) ?: return
        val storedCount = ackDao.countStoredForSession(ack.sessionId)
        val deliveredCount = ackDao.countDeliveredForSession(ack.sessionId)
        val nextDeliveryStatus = when {
            deliveredCount > 0 -> DeliveryStatus.ACK_DELIVERED
            storedCount > 0 && session.deliveryStatus != DeliveryStatus.ACK_DELIVERED.name ->
                DeliveryStatus.ACK_STORED
            else -> runCatching { DeliveryStatus.valueOf(session.deliveryStatus) }
                .getOrDefault(DeliveryStatus.QUEUED_RELAY)
        }
        val isSessionAcceptanceAck = ack.isFinalDelivery && ack.packetId == ack.sessionId
        sessionDao.upsert(
            session.copy(
                deliveryStatus = nextDeliveryStatus.name,
                sessionAccepted = if (isSessionAcceptanceAck) true else session.sessionAccepted,
                relayStoredCount = storedCount,
                deliveredCount = deliveredCount,
                updatedAtMillis = maxOf(session.updatedAtMillis, ack.timestampMillis)
            )
        )
        if (ack.isFinalDelivery) {
            packetDao.updateDeliveryStatus(ack.packetId, DeliveryStatus.ACK_DELIVERED.name)
            relayDao.remove(ack.packetId)
        } else {
            val existingPacket = packetDao.getById(ack.packetId)
            if (existingPacket != null && existingPacket.deliveryStatus != DeliveryStatus.ACK_DELIVERED.name) {
                packetDao.updateDeliveryStatus(ack.packetId, DeliveryStatus.ACK_STORED.name)
            }
        }
    }

    fun observeAcknowledgementsForSession(sessionId: String): Flow<List<Acknowledgement>> =
        ackDao.observeForSession(sessionId).map { list ->
            list.map {
                Acknowledgement(
                    ackId = it.ackId,
                    packetId = it.packetId,
                    sessionId = it.sessionId,
                    fromPeerId = it.fromPeerId,
                    isFinalDelivery = it.isFinalDelivery,
                    hopCount = it.hopCount,
                    timestampMillis = it.timestampMillis
                )
            }
        }

    suspend fun upsertReply(reply: EmergencyReply) {
        replyDao.upsert(
            EmergencyReplyEntity(
                replyId = reply.replyId,
                sessionId = reply.sessionId,
                senderPeerId = reply.senderPeerId,
                senderName = reply.senderName,
                message = reply.message,
                isIncoming = reply.isIncoming,
                deliveryStatus = reply.deliveryStatus.name,
                timestampMillis = reply.timestampMillis,
                imageFileId = reply.imageFileId
            )
        )
    }

    fun observeRepliesForSession(sessionId: String): Flow<List<EmergencyReply>> =
        replyDao.observeForSession(sessionId).map { list ->
            list.map {
                EmergencyReply(
                    replyId = it.replyId,
                    sessionId = it.sessionId,
                    senderPeerId = it.senderPeerId,
                    senderName = it.senderName,
                    message = it.message,
                    isIncoming = it.isIncoming,
                    deliveryStatus = runCatching { DeliveryStatus.valueOf(it.deliveryStatus) }
                        .getOrDefault(DeliveryStatus.QUEUED_RELAY),
                    timestampMillis = it.timestampMillis,
                    imageFileId = it.imageFileId
                )
            }
        }

    suspend fun upsertPeer(peer: Peer) {
        peerDao.upsert(
            PeerEntity(
                peerId = peer.peerId,
                displayName = peer.displayName,
                bluetoothAddress = peer.bluetoothAddress,
                ed25519PublicKeyHex = peer.ed25519PublicKeyHex,
                x25519PublicKeyHex = peer.x25519PublicKeyHex,
                connectionState = peer.connectionState.name,
                noiseState = peer.noiseState.name,
                rssi = peer.rssi,
                isRelayEnabled = peer.isRelayEnabled,
                lastSeenAtMillis = peer.lastSeenAtMillis
            )
        )
    }

    suspend fun getPeer(peerId: String): Peer? = peerDao.getPeer(peerId)?.toDomain()

    fun observePeers(): Flow<List<Peer>> =
        peerDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun resetAllPeerConnections() {
        peerDao.resetAllConnectionStates(PeerConnectionState.DISCONNECTED.name)
    }

    suspend fun upsertContact(contact: EmergencyContact): Boolean {
        val existing = contactDao.getContact(contact.peerId)
        if (existing == null && contactDao.countContacts() >= com.example.domain.model.MeshConstants.MAX_PAIRED_CONTACTS) {
            return false
        }
        contactDao.upsert(
            EmergencyContactEntity(
                peerId = contact.peerId,
                alias = contact.alias,
                x25519PublicKeyHex = contact.x25519PublicKeyHex,
                ed25519PublicKeyHex = contact.ed25519PublicKeyHex,
                sharedSecretHex = contact.sharedSecretHex,
                addedAtMillis = contact.addedAtMillis
            )
        )
        return true
    }

    suspend fun getAllContacts(): List<EmergencyContact> =
        contactDao.getAllContacts().map {
            EmergencyContact(
                peerId = it.peerId,
                alias = it.alias,
                x25519PublicKeyHex = it.x25519PublicKeyHex,
                ed25519PublicKeyHex = it.ed25519PublicKeyHex,
                sharedSecretHex = it.sharedSecretHex,
                addedAtMillis = it.addedAtMillis
            )
        }

    suspend fun getContact(peerId: String): EmergencyContact? =
        contactDao.getContact(peerId)?.let {
            EmergencyContact(
                peerId = it.peerId,
                alias = it.alias,
                x25519PublicKeyHex = it.x25519PublicKeyHex,
                ed25519PublicKeyHex = it.ed25519PublicKeyHex,
                sharedSecretHex = it.sharedSecretHex,
                addedAtMillis = it.addedAtMillis
            )
        }

    suspend fun deleteContact(peerId: String) = contactDao.delete(peerId)

    fun observeContacts(): Flow<List<EmergencyContact>> =
        contactDao.observeAll().map { list ->
            list.map {
                EmergencyContact(
                    peerId = it.peerId,
                    alias = it.alias,
                    x25519PublicKeyHex = it.x25519PublicKeyHex,
                    ed25519PublicKeyHex = it.ed25519PublicKeyHex,
                    sharedSecretHex = it.sharedSecretHex,
                    addedAtMillis = it.addedAtMillis
                )
            }
        }

    suspend fun upsertAudioClip(clip: AudioClipMetadata) {
        audioDao.upsertClip(
            AudioClipMetadataEntity(
                clipId = clip.clipId,
                sessionId = clip.sessionId,
                senderPeerId = clip.senderPeerId,
                codec = clip.codec,
                durationMillis = clip.durationMillis,
                totalFragments = clip.totalFragments,
                receivedFragments = clip.receivedFragments,
                sha256Hex = clip.sha256Hex,
                localFileId = clip.localFileId,
                status = clip.status.name,
                isIncoming = clip.isIncoming,
                createdAtMillis = clip.createdAtMillis
            )
        )
    }

    suspend fun getAudioClip(clipId: String): AudioClipMetadata? =
        audioDao.getClip(clipId)?.toDomain()

    fun observeAudioClipsForSession(sessionId: String): Flow<List<AudioClipMetadata>> =
        audioDao.observeClipsForSession(sessionId).map { list -> list.map { it.toDomain() } }

    suspend fun storeIncomingAudioFragment(
        clipId: String,
        sessionId: String,
        fragmentIndex: Int,
        totalFragments: Int,
        data: ByteArray
    ): Int {
        val fragFileId = audioFileStore.saveFragment(clipId, fragmentIndex, data)
        audioDao.upsertFragment(
            AudioFragmentEntity(
                clipId = clipId,
                sessionId = sessionId,
                fragmentIndex = fragmentIndex,
                totalFragments = totalFragments,
                fragmentFileId = fragFileId,
                receivedAtMillis = System.currentTimeMillis()
            )
        )
        return audioDao.countFragments(clipId)
    }

    suspend fun tryReassembleAudioClip(clipId: String): AudioClipMetadata? {
        val clip = audioDao.getClip(clipId)?.toDomain() ?: return null
        val fragments = audioDao.getFragments(clipId)
        if (fragments.size < clip.totalFragments || clip.totalFragments <= 0) {
            return clip
        }
        val expectedIndices = (0 until clip.totalFragments).toSet()
        val presentIndices = fragments.map { it.fragmentIndex }.toSet()
        if (presentIndices != expectedIndices) {
            val missingClip = clip.copy(
                receivedFragments = fragments.size,
                status = AudioTransferStatus.MISSING_FRAGMENTS
            )
            upsertAudioClip(missingClip)
            return missingClip
        }
        val assembledFileId = audioFileStore.assembleClipFromFragments(
            clipId = clip.clipId,
            codec = clip.codec,
            fragmentFileIds = fragments.sortedBy { it.fragmentIndex }.map { it.fragmentFileId },
            expectedSha256Hex = clip.sha256Hex
        )
        val updated = if (assembledFileId != null) {
            clip.copy(
                receivedFragments = clip.totalFragments,
                localFileId = assembledFileId,
                status = AudioTransferStatus.REASSEMBLED
            )
        } else {
            clip.copy(
                receivedFragments = fragments.size,
                status = if (clip.sha256Hex.isBlank()) AudioTransferStatus.TRANSFERRING else AudioTransferStatus.CORRUPTED
            )
        }
        upsertAudioClip(updated)
        return updated
    }

    suspend fun clearPendingRelaysForSession(sessionId: String): Int =
        relayDao.removeForSession(sessionId)

    suspend fun getMissingAudioFragmentIndices(clipId: String): List<Int> {
        val clip = audioDao.getClip(clipId) ?: return emptyList()
        val present = audioDao.getFragments(clipId).map { it.fragmentIndex }.toSet()
        return (0 until clip.totalFragments).filter { it !in present }
    }

    suspend fun upsertImageMetadata(meta: ImageClipMetadataEntity) {
        imageDao.upsertImage(meta)
    }

    suspend fun getImageMetadata(imageId: String): ImageClipMetadataEntity? = imageDao.getImage(imageId)

    suspend fun storeIncomingImageFragment(
        imageId: String,
        sessionId: String,
        fragmentIndex: Int,
        totalFragments: Int,
        data: ByteArray
    ): Int {
        audioFileStore.saveImageFragment(imageId, fragmentIndex, data)
        imageDao.upsertFragment(
            ImageFragmentEntity(
                imageId = imageId,
                sessionId = sessionId,
                fragmentIndex = fragmentIndex,
                totalFragments = totalFragments,
                receivedAtMillis = System.currentTimeMillis()
            )
        )
        return imageDao.countFragments(imageId)
    }

    suspend fun tryReassembleImageClip(imageId: String): String? {
        val meta = imageDao.getImage(imageId) ?: return null
        if (meta.localFileId != null) return meta.localFileId
        val count = imageDao.countFragments(imageId)
        if (meta.totalFragments <= 0 || count < meta.totalFragments) return null
        val assembled = audioFileStore.assembleImageFromFragments(imageId, meta.totalFragments, meta.sha256Hex)
        if (assembled != null) {
            imageDao.upsertImage(meta.copy(localFileId = assembled))
        }
        return assembled
    }

    suspend fun logDiagnostic(entry: DiagnosticLogEntry) {
        logDao.insert(
            DiagnosticLogEntity(
                packetId = entry.packetId,
                packetTypeName = entry.packetType.name,
                priorityRank = entry.priority.rank,
                queueTimeMillis = entry.queueTimeMillis,
                attempts = entry.attempts,
                deliveryStatus = entry.deliveryStatus.name,
                detail = entry.detail,
                timestampMillis = entry.timestampMillis
            )
        )
        logDao.trimOld()
    }

    fun observeDiagnostics(): Flow<List<DiagnosticLogEntry>> =
        logDao.observeRecent().map { list ->
            list.map {
                DiagnosticLogEntry(
                    id = it.id,
                    packetId = it.packetId,
                    packetType = runCatching { PacketType.valueOf(it.packetTypeName) }
                        .getOrDefault(PacketType.EMERGENCY_STATUS),
                    priority = PacketPriority.fromRank(it.priorityRank),
                    queueTimeMillis = it.queueTimeMillis,
                    attempts = it.attempts,
                    deliveryStatus = runCatching { DeliveryStatus.valueOf(it.deliveryStatus) }
                        .getOrDefault(DeliveryStatus.QUEUED_RELAY),
                    detail = it.detail,
                    timestampMillis = it.timestampMillis
                )
            }
        }
}

private fun EmergencySessionEntity.toDomain(): EmergencySession {
    val loc = if (latitude != null && longitude != null && accuracyMeters != null && locationTimestampMillis != null) {
        LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            timestampMillis = locationTimestampMillis,
            isFresh = locationIsFresh,
            provider = locationProvider ?: "GNSS"
        )
    } else null

    val responderLoc = if (responderLatitude != null && responderLongitude != null && responderLocationTimestampMillis != null) {
        LocationSnapshot(
            latitude = responderLatitude,
            longitude = responderLongitude,
            accuracyMeters = responderAccuracyMeters ?: 25f,
            timestampMillis = responderLocationTimestampMillis,
            isFresh = true,
            provider = "MESH_GPS"
        )
    } else null

    return EmergencySession(
        sessionId = sessionId,
        creatorPeerId = creatorPeerId,
        creatorDisplayName = creatorDisplayName,
        recipientPeerId = recipientPeerId,
        isOutgoing = isOutgoing,
        context = EmergencyContext(
            category = contextCategory,
            summary = contextSummary,
            batteryPercent = batteryPercent,
            peopleCount = peopleCount
        ),
        location = loc,
        responderLocation = responderLoc,
        status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.ACTIVE),
        deliveryStatus = runCatching { DeliveryStatus.valueOf(deliveryStatus) }.getOrDefault(DeliveryStatus.CREATED),
        sessionAccepted = sessionAccepted,
        hopCount = hopCount,
        relayStoredCount = relayStoredCount,
        deliveredCount = deliveredCount,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        unread = unread
    )
}

private fun EmergencySession.toEntity(): EmergencySessionEntity = EmergencySessionEntity(
    sessionId = sessionId,
    creatorPeerId = creatorPeerId,
    creatorDisplayName = creatorDisplayName,
    recipientPeerId = recipientPeerId,
    isOutgoing = isOutgoing,
    contextCategory = context.category,
    contextSummary = context.summary,
    batteryPercent = context.batteryPercent,
    peopleCount = context.peopleCount,
    latitude = location?.latitude,
    longitude = location?.longitude,
    accuracyMeters = location?.accuracyMeters,
    locationTimestampMillis = location?.timestampMillis,
    locationIsFresh = location?.isFresh ?: false,
    locationProvider = location?.provider,
    responderLatitude = responderLocation?.latitude,
    responderLongitude = responderLocation?.longitude,
    responderAccuracyMeters = responderLocation?.accuracyMeters,
    responderLocationTimestampMillis = responderLocation?.timestampMillis,
    status = status.name,
    deliveryStatus = deliveryStatus.name,
    sessionAccepted = sessionAccepted,
    hopCount = hopCount,
    relayStoredCount = relayStoredCount,
    deliveredCount = deliveredCount,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    unread = unread
)

private fun EmergencyPacket.toEntity(status: DeliveryStatus): EmergencyPacketEntity = EmergencyPacketEntity(
    packetId = packetId,
    sessionId = sessionId,
    senderId = senderId,
    recipientId = recipientId,
    typeCode = type.code,
    priorityRank = priority.rank,
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
    sequenceNumber = sequenceNumber,
    hopCount = hopCount,
    maxHopCount = maxHopCount,
    payload = payload,
    payloadHash = payloadHash,
    authTag = authTag,
    senderPublicKeyEd25519 = senderPublicKeyEd25519,
    isEncryptedPayload = isEncryptedPayload,
    deliveryStatus = status.name
)

private fun PeerEntity.toDomain(): Peer = Peer(
    peerId = peerId,
    displayName = displayName,
    bluetoothAddress = bluetoothAddress,
    ed25519PublicKeyHex = ed25519PublicKeyHex,
    x25519PublicKeyHex = x25519PublicKeyHex,
    connectionState = runCatching { PeerConnectionState.valueOf(connectionState) }
        .getOrDefault(PeerConnectionState.DISCOVERED),
    noiseState = runCatching { NoiseHandshakeState.valueOf(noiseState) }
        .getOrDefault(NoiseHandshakeState.NONE),
    rssi = rssi,
    isRelayEnabled = isRelayEnabled,
    lastSeenAtMillis = lastSeenAtMillis
)

private fun AudioClipMetadataEntity.toDomain(): AudioClipMetadata = AudioClipMetadata(
    clipId = clipId,
    sessionId = sessionId,
    senderPeerId = senderPeerId,
    codec = codec,
    durationMillis = durationMillis,
    totalFragments = totalFragments,
    receivedFragments = receivedFragments,
    sha256Hex = sha256Hex,
    localFileId = localFileId,
    status = runCatching { AudioTransferStatus.valueOf(status) }.getOrDefault(AudioTransferStatus.QUEUED),
    isIncoming = isIncoming,
    createdAtMillis = createdAtMillis
)
