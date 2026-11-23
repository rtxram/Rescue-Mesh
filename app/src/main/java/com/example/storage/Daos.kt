package com.example.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencySessionDao {
    @Query("SELECT * FROM emergency_sessions ORDER BY updatedAtMillis DESC")
    fun observeAllSessions(): Flow<List<EmergencySessionEntity>>

    @Query("SELECT * FROM emergency_sessions WHERE isOutgoing = 1 AND status = 'ACTIVE' ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeActiveOutgoingSession(): Flow<EmergencySessionEntity?>

    @Query("SELECT * FROM emergency_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeSession(sessionId: String): Flow<EmergencySessionEntity?>

    @Query("SELECT * FROM emergency_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): EmergencySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: EmergencySessionEntity)

    @Query("UPDATE emergency_sessions SET unread = 0 WHERE sessionId = :sessionId")
    suspend fun markRead(sessionId: String)

    @Query("SELECT COUNT(*) FROM emergency_sessions WHERE isOutgoing = 0 AND status = 'ACTIVE'")
    fun observeIncomingActiveCount(): Flow<Int>
}

@Dao
interface EmergencyPacketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(packet: EmergencyPacketEntity)

    @Query("SELECT * FROM emergency_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getById(packetId: String): EmergencyPacketEntity?

    @Query("UPDATE emergency_packets SET deliveryStatus = :status WHERE packetId = :packetId")
    suspend fun updateDeliveryStatus(packetId: String, status: String)
}

@Dao
interface RelayQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: RelayQueueEntity)

    @Query(
        "SELECT * FROM relay_queue WHERE expiresAtMillis > :nowMillis " +
            "ORDER BY priorityRank ASC, createdAtMillis ASC"
    )
    fun observePendingQueue(nowMillis: Long = System.currentTimeMillis()): Flow<List<RelayQueueEntity>>

    @Query(
        "SELECT * FROM relay_queue WHERE expiresAtMillis > :nowMillis AND nextRetryAtMillis <= :nowMillis " +
            "ORDER BY priorityRank ASC, createdAtMillis ASC LIMIT :limit"
    )
    suspend fun getNextDueBatch(nowMillis: Long, limit: Int = 20): List<RelayQueueEntity>

    @Query("SELECT * FROM relay_queue WHERE expiresAtMillis > :nowMillis ORDER BY priorityRank ASC, createdAtMillis ASC")
    suspend fun getAllPending(nowMillis: Long): List<RelayQueueEntity>

    @Query("SELECT * FROM relay_queue WHERE packetId = :packetId LIMIT 1")
    suspend fun getEntry(packetId: String): RelayQueueEntity?

    @Query("DELETE FROM relay_queue WHERE packetId = :packetId")
    suspend fun remove(packetId: String)

    @Query("DELETE FROM relay_queue WHERE sessionId = :sessionId")
    suspend fun removeForSession(sessionId: String): Int

    @Query("DELETE FROM relay_queue WHERE expiresAtMillis <= :nowMillis")
    suspend fun purgeExpired(nowMillis: Long): Int

    @Query("SELECT COUNT(*) FROM relay_queue WHERE expiresAtMillis > :nowMillis")
    fun observePendingCount(nowMillis: Long = System.currentTimeMillis()): Flow<Int>

    @Query("SELECT COUNT(*) FROM relay_queue WHERE sessionId = :sessionId AND expiresAtMillis > :nowMillis")
    suspend fun countForSession(sessionId: String, nowMillis: Long = System.currentTimeMillis()): Int

    @Query("UPDATE relay_queue SET nextRetryAtMillis = :nowMillis")
    suspend fun resetAllRetryTimes(nowMillis: Long = System.currentTimeMillis())
}

@Dao
interface SeenPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seen: SeenPacketEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE packetId = :packetId)")
    suspend fun hasSeen(packetId: String): Boolean

    @Query("DELETE FROM seen_packets WHERE expiresAtMillis <= :nowMillis")
    suspend fun purgeExpired(nowMillis: Long)
}

@Dao
interface AcknowledgementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ack: AcknowledgementEntity)

    @Query("SELECT * FROM acknowledgements WHERE sessionId = :sessionId ORDER BY timestampMillis DESC")
    fun observeForSession(sessionId: String): Flow<List<AcknowledgementEntity>>

    @Query("SELECT COUNT(*) FROM acknowledgements WHERE sessionId = :sessionId AND isFinalDelivery = 0")
    suspend fun countStoredForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM acknowledgements WHERE sessionId = :sessionId AND isFinalDelivery = 1")
    suspend fun countDeliveredForSession(sessionId: String): Int
}

@Dao
interface EmergencyReplyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reply: EmergencyReplyEntity)

    @Query("SELECT * FROM emergency_replies WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    fun observeForSession(sessionId: String): Flow<List<EmergencyReplyEntity>>

    @Query("UPDATE emergency_replies SET deliveryStatus = :status WHERE replyId = :replyId")
    suspend fun updateDeliveryStatus(replyId: String, status: String)
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeenAtMillis DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeer(peerId: String): PeerEntity?

    @Query("UPDATE peers SET connectionState = :state WHERE peerId = :peerId")
    suspend fun updateConnectionState(peerId: String, state: String)

    @Query("UPDATE peers SET connectionState = :state")
    suspend fun resetAllConnectionStates(state: String)
}

@Dao
interface EmergencyContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: EmergencyContactEntity)

    @Query("SELECT * FROM emergency_contacts ORDER BY alias ASC")
    fun observeAll(): Flow<List<EmergencyContactEntity>>

    @Query("SELECT * FROM emergency_contacts ORDER BY addedAtMillis ASC")
    suspend fun getAllContacts(): List<EmergencyContactEntity>

    @Query("SELECT COUNT(*) FROM emergency_contacts")
    suspend fun countContacts(): Int

    @Query("SELECT * FROM emergency_contacts WHERE peerId = :peerId LIMIT 1")
    suspend fun getContact(peerId: String): EmergencyContactEntity?

    @Query("DELETE FROM emergency_contacts WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}

@Dao
interface AudioClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: AudioClipMetadataEntity)

    @Query("SELECT * FROM audio_clips WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    fun observeClipsForSession(sessionId: String): Flow<List<AudioClipMetadataEntity>>

    @Query("SELECT * FROM audio_clips WHERE clipId = :clipId LIMIT 1")
    suspend fun getClip(clipId: String): AudioClipMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFragment(fragment: AudioFragmentEntity)

    @Query("SELECT * FROM audio_fragments WHERE clipId = :clipId ORDER BY fragmentIndex ASC")
    suspend fun getFragments(clipId: String): List<AudioFragmentEntity>

    @Query("SELECT COUNT(*) FROM audio_fragments WHERE clipId = :clipId")
    suspend fun countFragments(clipId: String): Int
}

@Dao
interface ImageClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImage(meta: ImageClipMetadataEntity)

    @Query("SELECT * FROM image_clips WHERE imageId = :imageId LIMIT 1")
    suspend fun getImage(imageId: String): ImageClipMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFragment(fragment: ImageFragmentEntity)

    @Query("SELECT COUNT(*) FROM image_fragments WHERE imageId = :imageId")
    suspend fun countFragments(imageId: String): Int
}

@Dao
interface DiagnosticLogDao {
    @Insert
    suspend fun insert(entry: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMillis DESC, id DESC LIMIT 120")
    fun observeRecent(): Flow<List<DiagnosticLogEntity>>

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY timestampMillis DESC, id DESC LIMIT 200)")
    suspend fun trimOld()
}
