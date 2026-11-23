package com.example.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EmergencySessionEntity::class,
        EmergencyPacketEntity::class,
        RelayQueueEntity::class,
        SeenPacketEntity::class,
        AcknowledgementEntity::class,
        EmergencyReplyEntity::class,
        PeerEntity::class,
        EmergencyContactEntity::class,
        AudioClipMetadataEntity::class,
        AudioFragmentEntity::class,
        ImageClipMetadataEntity::class,
        ImageFragmentEntity::class,
        DiagnosticLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RescueMeshDatabase : RoomDatabase() {
    abstract fun emergencySessionDao(): EmergencySessionDao
    abstract fun emergencyPacketDao(): EmergencyPacketDao
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun acknowledgementDao(): AcknowledgementDao
    abstract fun emergencyReplyDao(): EmergencyReplyDao
    abstract fun peerDao(): PeerDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun audioClipDao(): AudioClipDao
    abstract fun imageClipDao(): ImageClipDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao

    companion object {
        @Volatile
        private var INSTANCE: RescueMeshDatabase? = null

        fun getInstance(context: Context): RescueMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RescueMeshDatabase::class.java,
                    "rescuemesh.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
