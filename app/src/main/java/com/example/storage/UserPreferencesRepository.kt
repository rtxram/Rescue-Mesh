package com.example.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.MeshConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rescuemesh_prefs")

data class UserSettings(
    val deviceAlias: String = "Responder",
    val relayParticipationEnabled: Boolean = true,
    val maxHopCount: Int = MeshConstants.UNLIMITED_HOPS,
    val shakeSosEnabled: Boolean = false,
    val volumeShortcutEnabled: Boolean = false,
    val autoDownloadAudioClips: Boolean = true,
    val autoRecordVoiceOnSos: Boolean = true,
    val autoPlaySpeakerOnSession: Boolean = true,
    val preferInternetAudioWhenOnline: Boolean = true,
    val signalingServerUrl: String = "",
    val audioClipMaxSeconds: Int = MeshConstants.VOICE_SEGMENT_SECONDS,
    val sendToAllNodes: Boolean = true
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ALIAS = stringPreferencesKey("device_alias")
        val RELAY_ENABLED = booleanPreferencesKey("relay_enabled")
        val MAX_HOP_COUNT = intPreferencesKey("max_hop_count")
        val SHAKE_SOS = booleanPreferencesKey("shake_sos_enabled")
        val VOLUME_SHORTCUT = booleanPreferencesKey("volume_shortcut_enabled")
        val AUTO_DOWNLOAD_AUDIO = booleanPreferencesKey("auto_download_audio")
        val AUTO_RECORD_VOICE_SOS = booleanPreferencesKey("auto_record_voice_sos")
        val AUTO_PLAY_SPEAKER = booleanPreferencesKey("auto_play_speaker")
        val PREFER_INTERNET_AUDIO = booleanPreferencesKey("prefer_internet_audio")
        val SIGNALING_SERVER_URL = stringPreferencesKey("signaling_server_url")
        val AUDIO_MAX_SECONDS = intPreferencesKey("audio_max_seconds")
        val SEND_TO_ALL_NODES = booleanPreferencesKey("send_to_all_nodes")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            deviceAlias = prefs[Keys.DEVICE_ALIAS] ?: "RescueNode",
            relayParticipationEnabled = prefs[Keys.RELAY_ENABLED] ?: true,
            maxHopCount = (prefs[Keys.MAX_HOP_COUNT] ?: MeshConstants.UNLIMITED_HOPS)
                .coerceIn(MeshConstants.MIN_HOP_COUNT, MeshConstants.MAX_HOP_COUNT),
            shakeSosEnabled = prefs[Keys.SHAKE_SOS] ?: false,
            volumeShortcutEnabled = prefs[Keys.VOLUME_SHORTCUT] ?: false,
            autoDownloadAudioClips = prefs[Keys.AUTO_DOWNLOAD_AUDIO] ?: true,
            autoRecordVoiceOnSos = prefs[Keys.AUTO_RECORD_VOICE_SOS] ?: true,
            autoPlaySpeakerOnSession = prefs[Keys.AUTO_PLAY_SPEAKER] ?: true,
            preferInternetAudioWhenOnline = prefs[Keys.PREFER_INTERNET_AUDIO] ?: true,
            signalingServerUrl = prefs[Keys.SIGNALING_SERVER_URL] ?: "",
            audioClipMaxSeconds = (prefs[Keys.AUDIO_MAX_SECONDS] ?: MeshConstants.VOICE_SEGMENT_SECONDS).coerceIn(3, 10),
            sendToAllNodes = prefs[Keys.SEND_TO_ALL_NODES] ?: true
        )
    }

    suspend fun currentSettings(): UserSettings = settings.first()

    suspend fun setDeviceAlias(alias: String) {
        context.dataStore.edit { it[Keys.DEVICE_ALIAS] = alias.trim().ifEmpty { "RescueNode" } }
    }

    suspend fun setRelayParticipation(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RELAY_ENABLED] = enabled }
    }

    suspend fun setMaxHopCount(hops: Int) {
        context.dataStore.edit {
            it[Keys.MAX_HOP_COUNT] = hops.coerceIn(MeshConstants.MIN_HOP_COUNT, MeshConstants.MAX_HOP_COUNT)
        }
    }

    suspend fun setShakeSosEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHAKE_SOS] = enabled }
    }

    suspend fun setVolumeShortcutEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_SHORTCUT] = enabled }
    }

    suspend fun setAutoDownloadAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_DOWNLOAD_AUDIO] = enabled }
    }

    suspend fun setAutoRecordVoiceOnSos(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RECORD_VOICE_SOS] = enabled }
    }

    suspend fun setAutoPlaySpeakerOnSession(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PLAY_SPEAKER] = enabled }
    }

    suspend fun setPreferInternetAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PREFER_INTERNET_AUDIO] = enabled }
    }

    suspend fun setSignalingServerUrl(url: String) {
        context.dataStore.edit { it[Keys.SIGNALING_SERVER_URL] = url.trim() }
    }

    suspend fun setSendToAllNodes(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SEND_TO_ALL_NODES] = enabled }
    }
}
