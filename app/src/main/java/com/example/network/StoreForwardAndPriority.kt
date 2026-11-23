package com.example.network

import com.example.domain.model.DeliveryStatus
import com.example.domain.model.DiagnosticLogEntry
import com.example.domain.model.EmergencyPacket
import com.example.domain.model.NoiseHandshakeState
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.protocol.PacketSerializer
import com.example.storage.RelayQueueEntity
import com.example.storage.RescueMeshRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Combines fast bounded LRU in-memory lookup with persistent Room seen_packets table
 * so duplicate suppression survives process restarts.
 */
class DuplicateDetectionManager(
    private val repository: RescueMeshRepository,
    maxInMemoryCapacity: Int = 2048
) {
    private val memoryCache: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(maxInMemoryCapacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                    size > maxInMemoryCapacity
            }
        )
    )

    suspend fun isDuplicateOrMarkSeen(packet: EmergencyPacket): Boolean {
        if (memoryCache.contains(packet.packetId)) return true
        if (repository.hasSeenPacket(packet.packetId)) {
            memoryCache.add(packet.packetId)
            return true
        }
        val inserted = repository.markPacketSeen(
            packetId = packet.packetId,
            senderId = packet.senderId,
            expiresAtMillis = packet.expiresAtMillis
        )
        memoryCache.add(packet.packetId)
        return !inserted
    }

    suspend fun purgeExpired(nowMillis: Long = System.currentTimeMillis()) {
        repository.purgeExpiredSeen(nowMillis)
    }
}

/**
 * Persistent Room-backed store-carry-forward queue.
 * Ensures queued SOS, ACK, reply, and audio packets survive disconnects and process restarts.
 */
class StoreForwardQueue(
    private val repository: RescueMeshRepository
) {
    private val mutex = Mutex()

    suspend fun enqueue(packet: EmergencyPacket, isOriginLocal: Boolean) = mutex.withLock {
        val now = System.currentTimeMillis()
        val wire = PacketSerializer.serialize(packet)
        val existing = repository.getRelayEntry(packet.packetId)
        if (existing != null) return@withLock

        repository.enqueueRelay(
            RelayQueueEntity(
                packetId = packet.packetId,
                sessionId = packet.sessionId,
                recipientId = packet.recipientId,
                typeCode = packet.type.code,
                priorityRank = packet.priority.rank,
                hopCount = packet.hopCount,
                maxHopCount = packet.maxHopCount,
                wireBytes = wire,
                createdAtMillis = packet.createdAtMillis,
                expiresAtMillis = packet.expiresAtMillis,
                enqueuedAtMillis = now,
                nextRetryAtMillis = now,
                attemptCount = 0,
                isOriginLocal = isOriginLocal,
                forwardedToPeersCsv = ""
            )
        )
        repository.logDiagnostic(
            DiagnosticLogEntry(
                packetId = packet.packetId,
                packetType = packet.type,
                priority = packet.priority,
                queueTimeMillis = 0L,
                attempts = 0,
                deliveryStatus = DeliveryStatus.QUEUED_RELAY,
                detail = if (isOriginLocal) "Enqueued local packet (hop 0/${packet.maxHopCount})"
                else "Persisted relay packet (hop ${packet.hopCount}/${packet.maxHopCount})",
                timestampMillis = now
            )
        )
    }

    suspend fun nextDueEntries(limit: Int = 16, nowMillis: Long = System.currentTimeMillis()): List<RelayQueueEntity> =
        mutex.withLock {
            repository.purgeExpiredRelays(nowMillis)
            repository.getDueRelayBatch(nowMillis, limit)
        }

    suspend fun allPendingEntries(nowMillis: Long = System.currentTimeMillis()): List<RelayQueueEntity> =
        mutex.withLock {
            repository.purgeExpiredRelays(nowMillis)
            repository.getAllPendingRelays(nowMillis)
        }

    suspend fun recordForwardAttempt(
        entry: RelayQueueEntity,
        succeeded: Boolean,
        targetPeers: List<String>,
        nowMillis: Long = System.currentTimeMillis()
    ) = mutex.withLock {
        val nextAttempts = entry.attemptCount + 1
        val isCritical = entry.priorityRank <= PacketPriority.URGENT_LOCATION.rank
        val backoffMillis = if (isCritical) {
            (2_000L * (1 shl minOf(nextAttempts, 2))).coerceAtMost(6_000L)
        } else {
            (1_500L * (1 shl minOf(nextAttempts, 3))).coerceAtMost(12_000L)
        }
        val mergedPeers = (entry.forwardedToPeersCsv.split(",") + targetPeers)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

        repository.enqueueRelay(
            entry.copy(
                attemptCount = nextAttempts,
                nextRetryAtMillis = nowMillis + if (succeeded) backoffMillis else 3_000L,
                forwardedToPeersCsv = mergedPeers
            )
        )
        val type = PacketType.fromCode(entry.typeCode) ?: PacketType.EMERGENCY_STATUS
        val priority = PacketPriority.fromRank(entry.priorityRank)
        repository.logDiagnostic(
            DiagnosticLogEntry(
                packetId = entry.packetId,
                packetType = type,
                priority = priority,
                queueTimeMillis = (nowMillis - entry.enqueuedAtMillis).coerceAtLeast(0L),
                attempts = nextAttempts,
                deliveryStatus = if (succeeded) DeliveryStatus.QUEUED_RELAY else DeliveryStatus.FAILED,
                detail = if (succeeded) "Forwarded attempt #$nextAttempts to ${targetPeers.size} peer(s)"
                else "No reachable peer; deferred for store-carry-forward retry #$nextAttempts",
                timestampMillis = nowMillis
            )
        )
    }

    suspend fun resetBackoffsNow(nowMillis: Long = System.currentTimeMillis()) = mutex.withLock {
        repository.resetAllRelayBackoffs(nowMillis)
    }

    suspend fun markDelivered(packetId: String) = mutex.withLock {
        repository.removeRelayEntry(packetId)
    }
}

/**
 * Strict Priority Scheduler ensuring Priority-1 SOS and Priority-2 Location updates always preempt
 * bulk Priority-5 audio fragments and Priority-6 ordinary status packets.
 */
class PriorityScheduler(
    private val storeForwardQueue: StoreForwardQueue
) {
    suspend fun dispatchNextBatch(
        connectedPeers: List<Peer>,
        sendBytes: suspend (targetPeerId: String?, wireBytes: ByteArray) -> Boolean,
        maxBatchSize: Int = 32,
        hasLiveTransportLinks: Boolean = false
    ): Int {
        if (connectedPeers.isEmpty() && !hasLiveTransportLinks) return 0
        val now = System.currentTimeMillis()
        val dueEntries = storeForwardQueue.nextDueEntries(limit = maxBatchSize * 3, nowMillis = now)
        if (dueEntries.isEmpty()) return 0

        val sortedAll = dueEntries.sortedWith(
            compareBy<RelayQueueEntity> { it.priorityRank }
                .thenBy { it.createdAtMillis }
        )

        // Preempt bulk P5+ packets (audio/image) to max 6 per flush cycle so P1..P4 never starve
        var bulkAdded = 0
        val sorted = buildList {
            for (entry in sortedAll) {
                if (size >= maxBatchSize) break
                if (entry.priorityRank >= PacketPriority.AUDIO_DATA.rank) {
                    if (bulkAdded >= 6) continue
                    bulkAdded++
                }
                add(entry)
            }
        }

        var dispatched = 0
        val peerIds = connectedPeers.map { it.peerId }.distinct()
        for (entry in sorted) {
            val alreadySent = entry.forwardedToPeersCsv.split(",")
                .filter { it.isNotBlank() }
                .map { it.uppercase() }
                .toSet()
            val candidatePeers = peerIds.filter { it.uppercase() !in alreadySent }
            val isControlHandshake = entry.typeCode == PacketType.SESSION_ACCEPT.code ||
                entry.typeCode == PacketType.SESSION_RESOLVED.code ||
                entry.typeCode == PacketType.ACK_DELIVERED.code

            val targets: List<String?> = when {
                peerIds.isEmpty() && hasLiveTransportLinks -> listOf(null)
                candidatePeers.isNotEmpty() -> candidatePeers
                entry.isOriginLocal || isControlHandshake -> peerIds
                else -> continue
            }

            var sentAny = false
            val sentTo = mutableListOf<String>()
            for (pid in targets) {
                if (sendBytes(pid, entry.wireBytes)) {
                    sentAny = true
                    if (pid != null) {
                        sentTo.add(pid)
                    }
                }
            }
            if (!sentAny && targets.any { it != null }) {
                sentAny = sendBytes(null, entry.wireBytes)
                if (sentAny) {
                    sentTo.addAll(targets.filterNotNull())
                }
            }
            storeForwardQueue.recordForwardAttempt(entry, sentAny, sentTo, now)
            if (sentAny) dispatched++
        }
        return dispatched
    }
}

/**
 * Tracks discovered RescueMesh peers, signal strength, connection states, and Noise trust state.
 */
class PeerDiscoveryManager(
    private val repository: RescueMeshRepository
) {
    suspend fun onPeerDiscoveredOrUpdated(peer: Peer) {
        val existing = repository.getPeer(peer.peerId)
        val merged = if (existing != null) {
            existing.copy(
                displayName = peer.displayName.ifBlank { existing.displayName },
                bluetoothAddress = peer.bluetoothAddress ?: existing.bluetoothAddress,
                ed25519PublicKeyHex = peer.ed25519PublicKeyHex.ifBlank { existing.ed25519PublicKeyHex },
                x25519PublicKeyHex = peer.x25519PublicKeyHex.ifBlank { existing.x25519PublicKeyHex },
                connectionState = peer.connectionState,
                noiseState = if (peer.noiseState != NoiseHandshakeState.NONE) peer.noiseState else existing.noiseState,
                rssi = peer.rssi,
                isRelayEnabled = peer.isRelayEnabled,
                lastSeenAtMillis = peer.lastSeenAtMillis
            )
        } else {
            peer
        }
        repository.upsertPeer(merged)
    }

    suspend fun markNoiseVerified(peerId: String) {
        val existing = repository.getPeer(peerId) ?: return
        repository.upsertPeer(
            existing.copy(
                noiseState = NoiseHandshakeState.ESTABLISHED,
                lastSeenAtMillis = System.currentTimeMillis()
            )
        )
    }
}
