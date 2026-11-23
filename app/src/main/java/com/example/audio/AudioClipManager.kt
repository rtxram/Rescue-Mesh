package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.domain.model.AudioClipMetadata
import com.example.domain.model.AudioTransferStatus
import com.example.domain.model.MeshConstants
import com.example.domain.model.PacketType
import com.example.domain.usecase.ForwardPendingMessagesUseCase
import com.example.domain.usecase.SendEmergencyPacketUseCase
import com.example.domain.usecase.sealPayloadForSessionTarget
import com.example.network.AudioCompleteWirePayload
import com.example.network.AudioMetaWirePayload
import com.example.network.MessageRouter
import com.example.network.TransportStatus
import com.example.network.WifiAwareTransportContract
import com.example.security.IdentityManager
import com.example.security.RescueCrypto
import com.example.storage.RescueMeshRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

private val audioJson = Json { ignoreUnknownKeys = true }

/**
 * Reliable continuous BLE voice engine:
 * - Captures continuous 8 kHz PCM via [AudioRecord] (no start/stop MediaRecorder dropouts)
 * - Encodes each 5-second segment into compact 4-bit IMA-ADPCM ("RMAD", ~4 KB/s + zlib compression)
 * - Fragments into 2 KB chunks (~6-10 packets per 5s clip) prioritized at P5 after SOS/ACKs
 * - Plays incoming RMAD clips deterministically via [AudioTrack] FIFO queue (with MediaPlayer legacy fallback)
 */
class AudioClipManager(
    private val context: Context,
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val sendEmergencyPacketUseCase: SendEmergencyPacketUseCase,
    private val forwardPendingMessagesUseCase: ForwardPendingMessagesUseCase? = null
) {
    companion object {
        private const val TAG = "AudioClipManager"
        const val FRAGMENT_PAYLOAD_SIZE = 2048

        fun fragmentBytes(bytes: ByteArray, chunkSize: Int): List<ByteArray> {
            if (bytes.isEmpty()) return listOf(ByteArray(0))
            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + chunkSize)
                result.add(bytes.copyOfRange(offset, end))
                offset = end
            }
            return result
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordMutex = Mutex()
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var speakerListenJob: Job? = null

    private var activeSessionId: String? = null
    private var activeRecipientId: String = MeshConstants.BROADCAST_RECIPIENT_ID
    private var recordingStartedAt: Long = 0L

    @Volatile
    private var isContinuousLoopEnabled = false
    @Volatile
    private var pendingSamples: ShortArray = ShortArray(0)
    @Volatile
    private var pendingSampleLen = 0
    private val pcmBufferLock = Any()

    private var player: MediaPlayer? = null
    private var activeAudioTrack: AudioTrack? = null
    private val playedClipIds = ConcurrentHashMap.newKeySet<String>()
    private val playbackQueue = ArrayDeque<AudioClipMetadata>()
    private var listeningSessionId: String? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isLoopStreaming = MutableStateFlow(false)
    val isLoopStreaming: StateFlow<Boolean> = _isLoopStreaming.asStateFlow()

    private val _isSpeakerListening = MutableStateFlow(false)
    val isSpeakerListening: StateFlow<Boolean> = _isSpeakerListening.asStateFlow()

    private val _recordingElapsedMillis = MutableStateFlow(0L)
    val recordingElapsedMillis: StateFlow<Long> = _recordingElapsedMillis.asStateFlow()

    private val _playingClipId = MutableStateFlow<String?>(null)
    val playingClipId: StateFlow<String?> = _playingClipId.asStateFlow()

    private val _lastAudioStatus = MutableStateFlow<String?>(null)
    val lastAudioStatus: StateFlow<String?> = _lastAudioStatus.asStateFlow()

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun clearAudioStatus() {
        _lastAudioStatus.value = null
    }

    fun updateRecipientId(sessionId: String, recipientId: String) {
        if (activeSessionId == sessionId && recipientId.isNotBlank()) {
            activeRecipientId = recipientId
        }
    }

    /**
     * Continuous 5-second BLE mesh microphone streaming loop:
     * Keeps a single AudioRecord session running seamlessly, slicing 5s ADPCM segments without gaps
     * until muted via [stopVoiceLoop].
     */
    fun startContinuousVoiceLoop(
        sessionId: String,
        recipientId: String = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
        segmentSeconds: Int = MeshConstants.VOICE_SEGMENT_SECONDS
    ): Boolean {
        if (!hasRecordPermission()) {
            _lastAudioStatus.value = "Microphone permission required for live voice stream"
            return false
        }
        isContinuousLoopEnabled = true
        _isLoopStreaming.value = true
        if (!_isRecording.value || activeSessionId != sessionId) {
            return startRecording(sessionId, recipientId, segmentSeconds)
        }
        activeRecipientId = recipientId
        return true
    }

    suspend fun stopVoiceLoop(): AudioClipMetadata? {
        isContinuousLoopEnabled = false
        _isLoopStreaming.value = false
        return if (_isRecording.value) {
            stopAndSendClip()
        } else {
            null
        }
    }

    /**
     * Enables/disables sequential FIFO auto-playback of incoming 5-second voice packets for a session.
     */
    fun setSpeakerListening(enabled: Boolean, sessionId: String) {
        if (enabled) {
            if (_isSpeakerListening.value && listeningSessionId == sessionId) return
            listeningSessionId = sessionId
            _isSpeakerListening.value = true
            speakerListenJob?.cancel()
            speakerListenJob = scope.launch {
                repository.observeAudioClipsForSession(sessionId).collect { clips ->
                    val readyIncoming = synchronized(playbackQueue) {
                        val filtered = clips.filter {
                            it.isIncoming &&
                                it.status == AudioTransferStatus.REASSEMBLED &&
                                it.localFileId != null &&
                                it.clipId !in playedClipIds &&
                                playbackQueue.none { q -> q.clipId == it.clipId }
                        }.sortedBy { it.createdAtMillis }
                        filtered.forEach { playbackQueue.addLast(it) }
                        filtered.isNotEmpty()
                    }
                    if (readyIncoming && _playingClipId.value == null) {
                        playNextFromQueue()
                    }
                }
            }
        } else {
            _isSpeakerListening.value = false
            listeningSessionId = null
            speakerListenJob?.cancel()
            speakerListenJob = null
            synchronized(playbackQueue) { playbackQueue.clear() }
            stopPlayback()
        }
    }

    private fun playNextFromQueue() {
        if (!_isSpeakerListening.value || _playingClipId.value != null) return
        val next = synchronized(playbackQueue) {
            if (playbackQueue.isEmpty()) null else playbackQueue.removeFirst()
        } ?: return
        playedClipIds.add(next.clipId)
        playClip(next, onFinished = {
            if (_isSpeakerListening.value) {
                playNextFromQueue()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        sessionId: String,
        recipientId: String = MeshConstants.BROADCAST_RECIPIENT_ID,
        maxDurationSeconds: Int = MeshConstants.VOICE_SEGMENT_SECONDS
    ): Boolean {
        if (!hasRecordPermission()) return false
        captureJob?.cancel()

        val segmentSec = maxDurationSeconds.coerceIn(2, 10)
        val sampleRate = ImaAdpcmCodec.DEFAULT_SAMPLE_RATE
        val segmentSampleTarget = sampleRate * segmentSec

        synchronized(pcmBufferLock) {
            pendingSamples = ShortArray(segmentSampleTarget)
            pendingSampleLen = 0
        }

        activeSessionId = sessionId
        activeRecipientId = recipientId
        recordingStartedAt = System.currentTimeMillis()
        _isRecording.value = true
        _recordingElapsedMillis.value = 0L
        _lastAudioStatus.value = if (isContinuousLoopEnabled) {
            "Live ${segmentSec}s ADPCM voice stream active"
        } else {
            "Recording voice clip..."
        }

        captureJob = scope.launch {
            val minBufBytes = runCatching {
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            }.getOrDefault(1600).coerceAtLeast(1600)

            var audioRecord: AudioRecord? = null
            val sources = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
            for (src in sources) {
                val candidate = runCatching {
                    AudioRecord(
                        src,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufBytes * 4
                    )
                }.getOrNull()
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    val started = runCatching {
                        candidate.startRecording()
                        candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    }.getOrDefault(false)
                    if (started) {
                        audioRecord = candidate
                        break
                    }
                }
                runCatching { candidate?.release() }
            }

            if (audioRecord == null) {
                Log.w(TAG, "AudioRecord unavailable on this hardware; running compact ADPCM beacon loop")
                while (isActive && _isRecording.value) {
                    val elapsed = System.currentTimeMillis() - recordingStartedAt
                    _recordingElapsedMillis.value = elapsed
                    if (elapsed >= segmentSec * 1000L) {
                        val beacon = ImaAdpcmCodec.synthesizeFallbackBeacon(1000L, sampleRate)
                        enqueueEncodedSegment(sessionId, activeRecipientId, beacon, elapsed)
                        if (!isContinuousLoopEnabled) {
                            _isRecording.value = false
                            break
                        }
                        recordingStartedAt = System.currentTimeMillis()
                    }
                    delay(120L)
                }
                return@launch
            }

            try {
                val readChunk = ShortArray((minBufBytes / 2).coerceIn(320, 2048))
                while (isActive && _isRecording.value) {
                    val readCount = runCatching {
                        audioRecord.read(readChunk, 0, readChunk.size)
                    }.getOrDefault(-1)

                    if (readCount > 0) {
                        var completedSegment: ShortArray? = null
                        synchronized(pcmBufferLock) {
                            var offset = 0
                            while (offset < readCount) {
                                val space = pendingSamples.size - pendingSampleLen
                                val toCopy = min(space, readCount - offset)
                                System.arraycopy(readChunk, offset, pendingSamples, pendingSampleLen, toCopy)
                                pendingSampleLen += toCopy
                                offset += toCopy
                                if (pendingSampleLen >= pendingSamples.size) {
                                    completedSegment = pendingSamples.copyOf()
                                    pendingSampleLen = 0
                                }
                            }
                        }
                        _recordingElapsedMillis.value = System.currentTimeMillis() - recordingStartedAt
                        val seg = completedSegment
                        if (seg != null) {
                            val encoded = ImaAdpcmCodec.encode(seg, sampleRate)
                            enqueueEncodedSegment(sessionId, activeRecipientId, encoded, segmentSec * 1000L)
                            if (!isContinuousLoopEnabled) {
                                _isRecording.value = false
                                break
                            }
                            recordingStartedAt = System.currentTimeMillis()
                        }
                    } else {
                        delay(25L)
                    }
                }
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
            }
        }
        return true
    }

    suspend fun stopAndSendClip(): AudioClipMetadata? = recordMutex.withLock {
        if (!_isRecording.value) return null
        _isRecording.value = false
        captureJob?.cancel()
        captureJob = null

        val sessionId = activeSessionId ?: return null
        val recipientId = activeRecipientId
        val duration = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(300L)

        val tailSamples: ShortArray = synchronized(pcmBufferLock) {
            val len = pendingSampleLen
            pendingSampleLen = 0
            if (len >= 1600) pendingSamples.copyOfRange(0, len) else ShortArray(0)
        }

        val adpcmBytes = if (tailSamples.isNotEmpty()) {
            ImaAdpcmCodec.encode(tailSamples, ImaAdpcmCodec.DEFAULT_SAMPLE_RATE)
        } else {
            return null
        }

        return enqueueEncodedSegment(sessionId, recipientId, adpcmBytes, duration)
    }

    private suspend fun enqueueEncodedSegment(
        sessionId: String,
        recipientId: String,
        bytes: ByteArray,
        durationMillis: Long
    ): AudioClipMetadata? {
        val backlog = repository.countPendingRelaysForSession(sessionId)
        if (isContinuousLoopEnabled && backlog >= 24) {
            _lastAudioStatus.value = "BLE queue busy ($backlog pkts) · waiting before next 5s clip"
            forwardPendingMessagesUseCase?.invoke()
            return null
        }
        val clipId = UUID.randomUUID().toString().replace("-", "").take(12)
        val fileId = "${clipId}_local.rmad"
        repository.audioFileStore.saveClipBytes(fileId, bytes)
        val meta = enqueueAudioBytesForMesh(
            sessionId = sessionId,
            recipientId = recipientId,
            codec = "RMAD_8K",
            durationMillis = durationMillis,
            bytes = bytes,
            localFileId = fileId
        )
        _lastAudioStatus.value = if (isContinuousLoopEnabled) {
            "Live ${MeshConstants.VOICE_SEGMENT_SECONDS}s stream · ${meta.totalFragments} BLE pkts (${bytes.size / 1024} KB)"
        } else {
            "Voice clip queued (${meta.totalFragments} BLE pkts)"
        }
        return meta
    }

    suspend fun enqueueAudioBytesForMesh(
        sessionId: String,
        recipientId: String,
        codec: String,
        durationMillis: Long,
        bytes: ByteArray,
        localFileId: String
    ): AudioClipMetadata {
        val clipId = localFileId.substringBefore("_")
        val shaHex = RescueCrypto.bytesToHex(RescueCrypto.sha256(bytes))
        val fragments = fragmentBytes(bytes, FRAGMENT_PAYLOAD_SIZE)
        val total = fragments.size
        val baseTime = System.currentTimeMillis()

        val metadata = AudioClipMetadata(
            clipId = clipId,
            sessionId = sessionId,
            senderPeerId = identityManager.localPeerId,
            codec = codec,
            durationMillis = durationMillis,
            totalFragments = total,
            receivedFragments = total,
            sha256Hex = shaHex,
            localFileId = localFileId,
            status = AudioTransferStatus.QUEUED,
            isIncoming = false,
            createdAtMillis = baseTime
        )
        repository.upsertAudioClip(metadata)

        val metaPayload = audioJson.encodeToString(
            AudioMetaWirePayload.serializer(),
            AudioMetaWirePayload(clipId, codec, durationMillis, total, shaHex)
        ).toByteArray(StandardCharsets.UTF_8)

        val (sealedMeta, encMeta) = sealPayloadForSessionTarget(
            repository = repository,
            identityManager = identityManager,
            sessionId = sessionId,
            targetPeerId = recipientId,
            plaintext = metaPayload
        )
        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = recipientId,
            type = PacketType.AUDIO_METADATA,
            payload = sealedMeta,
            isEncrypted = encMeta,
            flushImmediately = false,
            createdAtOverrideMillis = baseTime
        )

        for (idx in fragments.indices) {
            val fragBytes = fragments[idx]
            val encoded = MessageRouter.encodeAudioFragment(clipId, idx, total, fragBytes)
            val (sealedFrag, encFrag) = sealPayloadForSessionTarget(
                repository = repository,
                identityManager = identityManager,
                sessionId = sessionId,
                targetPeerId = recipientId,
                plaintext = encoded
            )
            sendEmergencyPacketUseCase(
                sessionId = sessionId,
                recipientId = recipientId,
                type = PacketType.AUDIO_FRAGMENT,
                payload = sealedFrag,
                isEncrypted = encFrag,
                flushImmediately = false,
                createdAtOverrideMillis = baseTime + idx + 1L
            )
        }

        val completePayload = audioJson.encodeToString(
            AudioCompleteWirePayload.serializer(),
            AudioCompleteWirePayload(clipId, total, shaHex)
        ).toByteArray(StandardCharsets.UTF_8)

        val (sealedComplete, encComplete) = sealPayloadForSessionTarget(
            repository = repository,
            identityManager = identityManager,
            sessionId = sessionId,
            targetPeerId = recipientId,
            plaintext = completePayload
        )
        sendEmergencyPacketUseCase(
            sessionId = sessionId,
            recipientId = recipientId,
            type = PacketType.AUDIO_COMPLETE,
            payload = sealedComplete,
            isEncrypted = encComplete,
            flushImmediately = false,
            createdAtOverrideMillis = baseTime + total + 1L
        )

        val updated = metadata.copy(status = AudioTransferStatus.TRANSFERRING)
        repository.upsertAudioClip(updated)

        forwardPendingMessagesUseCase?.invoke()
        return updated
    }

    fun playClip(clip: AudioClipMetadata, onFinished: (() -> Unit)? = null) {
        val fileId = clip.localFileId ?: run {
            onFinished?.invoke()
            return
        }
        val file = repository.audioFileStore.getClipFile(fileId) ?: run {
            onFinished?.invoke()
            return
        }
        stopPlayback()

        val raw = runCatching { file.readBytes() }.getOrNull()
        if (raw != null && ImaAdpcmCodec.isRmadFormat(raw)) {
            val decoded = ImaAdpcmCodec.decode(raw)
            if (decoded == null || decoded.samples.isEmpty()) {
                _playingClipId.value = null
                onFinished?.invoke()
                return
            }
            _playingClipId.value = clip.clipId
            playbackJob = scope.launch {
                var track: AudioTrack? = null
                var wasCancelled = false
                try {
                    val minBuf = AudioTrack.getMinBufferSize(
                        decoded.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(1600)

                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(decoded.sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    activeAudioTrack = track
                    track.play()

                    var offset = 0
                    val chunk = 1600
                    while (isActive && offset < decoded.samples.size) {
                        val len = min(chunk, decoded.samples.size - offset)
                        val written = track.write(decoded.samples, offset, len)
                        if (written <= 0) break
                        offset += written
                    }
                    // Wait slightly for tail audio buffer drain before advancing FIFO queue
                    delay(120L)
                } catch (ce: CancellationException) {
                    wasCancelled = true
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack playback failed", e)
                    _lastAudioStatus.value = "Playback error: ${e.message ?: "audio hardware"}"
                } finally {
                    runCatching { track?.stop() }
                    runCatching { track?.release() }
                    if (activeAudioTrack === track) activeAudioTrack = null
                    _playingClipId.value = null
                    if (playbackJob === coroutineContext[Job]) {
                        playbackJob = null
                    }
                    if (!wasCancelled) {
                        scope.launch { onFinished?.invoke() }
                    }
                }
            }
            return
        }

        // Legacy fallback for .m4a / .wav / .ogg clips
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _playingClipId.value = null
                    release()
                    player = null
                    onFinished?.invoke()
                }
                setOnErrorListener { failedPlayer, _, _ ->
                    _playingClipId.value = null
                    runCatching { failedPlayer.release() }
                    player = null
                    onFinished?.invoke()
                    true
                }
                prepare()
                start()
            }
            player = mp
            _playingClipId.value = clip.clipId
        } catch (e: Exception) {
            Log.e(TAG, "Legacy MediaPlayer playback failed", e)
            _lastAudioStatus.value = "Playback error: ${e.message ?: "unsupported format"}"
            _playingClipId.value = null
            onFinished?.invoke()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching { activeAudioTrack?.stop() }
        runCatching { activeAudioTrack?.release() }
        activeAudioTrack = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playingClipId.value = null
    }
}

/**
 * Wi-Fi Aware (NAN) capability check & transport adapter.
 */
class WifiAwareTransportAdapter(private val context: Context) : WifiAwareTransportContract {
    override val transportName: String = "Wi-Fi Aware (Future)"
    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    override fun isWifiAwareSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

    override suspend fun establishHighBandwidthAudioSession(peerId: String): Boolean = false
    override suspend fun startTransport() {}
    override suspend fun stopTransport() {}
    override fun discoverPeers(): Flow<com.example.domain.model.Peer> = emptyFlow()
    override suspend fun sendData(targetPeerId: String?, data: ByteArray): Boolean = false
    override fun receiveData(): Flow<com.example.network.IncomingTransportFrame> = emptyFlow()
    override fun observeConnectionStatus(): Flow<TransportStatus> = status
}
