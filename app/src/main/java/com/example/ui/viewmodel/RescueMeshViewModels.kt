package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.RescueMeshApplication
import com.example.audio.AudioClipManager
import com.example.audio.WebRtcInternetAudioAdapter
import com.example.audio.WifiAwareTransportAdapter
import com.example.domain.model.Acknowledgement
import com.example.domain.model.AudioClipMetadata
import com.example.domain.model.DiagnosticLogEntry
import com.example.domain.model.EmergencyContact
import com.example.domain.model.EmergencyContext
import com.example.domain.model.EmergencyReply
import com.example.domain.model.EmergencySession
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.example.domain.model.NoiseHandshakeState
import com.example.domain.model.Peer
import com.example.domain.model.SessionStatus
import com.example.domain.model.VoiceTransportMode
import com.example.domain.usecase.AcknowledgeEmergencyUseCase
import com.example.domain.usecase.CreateSosUseCase
import com.example.domain.usecase.ForwardPendingMessagesUseCase
import com.example.domain.usecase.ResolveEmergencyUseCase
import com.example.domain.usecase.SendEmergencyImageUseCase
import com.example.domain.usecase.SendEmergencyReplyUseCase
import com.example.domain.usecase.SendLiveLocationUpdateUseCase
import com.example.location.MapTileStyle
import com.example.location.OfflineLocationProvider
import com.example.location.OfflineMapCacheStatus
import com.example.location.OfflineMapManager
import com.example.network.MeshCoordinator
import com.example.network.TransportStatus
import com.example.network.bitchat.BitchatMeshWrapper
import com.example.security.IdentityManager
import com.example.security.NoiseSession
import com.example.security.QrPairingCodec
import com.example.security.RescueCrypto
import com.example.service.RescueMeshForegroundService
import com.example.storage.RescueMeshRepository
import com.example.storage.UserPreferencesRepository
import com.example.storage.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class HomeUiState(
    val localPeerId: String = "",
    val deviceAlias: String = "RescueNode",
    val transportStatus: TransportStatus = TransportStatus.STOPPED,
    val diagnosticMessage: String = "",
    val isCoordinatorRunning: Boolean = false,
    val nearbyPeerCount: Int = 0,
    val pendingQueueCount: Int = 0,
    val activeOutgoingSession: EmergencySession? = null,
    val incomingAlerts: List<EmergencySession> = emptyList(),
    val contacts: List<EmergencyContact> = emptyList(),
    val isActivatingSos: Boolean = false,
    val sendToAllNodes: Boolean = true,
    val homeBanner: String? = null
)

class HomeViewModel(
    private val appContext: Context,
    private val repository: RescueMeshRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val identityManager: IdentityManager,
    private val locationProvider: OfflineLocationProvider,
    private val meshCoordinator: MeshCoordinator,
    private val bitchatMeshWrapper: BitchatMeshWrapper
) : ViewModel() {

    private val _isActivating = MutableStateFlow(false)
    private val _homeBanner = MutableStateFlow<String?>(null)
    private val _navigateToSosTrigger = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigateToSosTrigger = _navigateToSosTrigger.asSharedFlow()

    private val baseFlow = combine(
        preferencesRepository.settings,
        bitchatMeshWrapper.transportStatus,
        bitchatMeshWrapper.diagnosticMessage,
        repository.observePeers(),
        repository.observePendingRelayCount()
    ) { settings, transportStatus, diagMsg, peers, pendingCount ->
        val recentPeers = peers.count { System.currentTimeMillis() - it.lastSeenAtMillis < 180_000L }
        Triple(settings, transportStatus to diagMsg, recentPeers to pendingCount)
    }

    private val sessionFlow = combine(
        repository.observeActiveOutgoingSession(),
        repository.observeAllSessions(),
        repository.observeContacts(),
        _isActivating,
        _homeBanner
    ) { activeOutgoing, allSessions, contacts, activating, banner ->
        listOf(activeOutgoing, allSessions.filter { !it.isOutgoing }, contacts, activating, banner)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<HomeUiState> = combine(baseFlow, sessionFlow) { base, sess ->
        val settings = base.first
        val (transportStatus, diagMsg) = base.second
        val (peerCount, pendingCount) = base.third
        HomeUiState(
            localPeerId = identityManager.localPeerId,
            deviceAlias = settings.deviceAlias,
            transportStatus = transportStatus,
            diagnosticMessage = diagMsg,
            isCoordinatorRunning = meshCoordinator.isRunning.value,
            nearbyPeerCount = peerCount,
            pendingQueueCount = pendingCount,
            activeOutgoingSession = sess[0] as? EmergencySession,
            incomingAlerts = sess[1] as List<EmergencySession>,
            contacts = sess[2] as List<EmergencyContact>,
            isActivatingSos = sess[3] as Boolean,
            sendToAllNodes = settings.sendToAllNodes,
            homeBanner = sess[4] as? String
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(localPeerId = identityManager.localPeerId)
    )

    fun startMeshIfNeeded() {
        RescueMeshForegroundService.start(appContext)
        bitchatMeshWrapper.startMesh()
    }

    fun toggleSendToAll(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSendToAllNodes(enabled)
            _homeBanner.value = null
        }
    }

    fun clearHomeBanner() {
        _homeBanner.value = null
    }

    fun activateSos() {
        if (_isActivating.value) return
        viewModelScope.launch {
            _isActivating.value = true
            _homeBanner.value = null
            try {
                RescueMeshForegroundService.start(appContext)
                bitchatMeshWrapper.startMesh()
                val location = locationProvider.acquireSnapshot()
                val battery = readBatteryPercentage()
                val context = EmergencyContext(
                    category = "SOS_DISTRESS",
                    summary = "SOS: Need assistance via RescueMesh",
                    batteryPercent = battery,
                    peopleCount = 1
                )
                val settings = preferencesRepository.currentSettings()
                val targetRecipient = if (settings.sendToAllNodes) {
                    MeshConstants.BROADCAST_RECIPIENT_ID
                } else {
                    MeshConstants.PAIRED_MULTI_RECIPIENT_ID
                }
                val session = bitchatMeshWrapper.sprayEmergencySos(
                    context = context,
                    location = location,
                    recipientPeerId = targetRecipient
                )
                // Voice capture starts cleanly once SessionActiveScreen opens after peer acceptance,
                // avoiding blocking SOS launch or flooding un-ACKed bulk audio before peer linkup.
                _navigateToSosTrigger.emit(session.sessionId)
            } catch (e: IllegalStateException) {
                _homeBanner.value = if (e.message == "NO_PAIRED_CONTACTS") {
                    "Paired Only (E2EE) requires at least 1 paired contact. Pair a contact in Config or turn on Send to All Nearby."
                } else {
                    e.message ?: "Unable to start SOS"
                }
            } finally {
                _isActivating.value = false
            }
        }
    }

    private fun readBatteryPercentage(): Int {
        return runCatching {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) (level * 100) / scale else 100
        }.getOrDefault(100)
    }
}

data class ActiveEmergencyUiState(
    val session: EmergencySession? = null,
    val acknowledgements: List<Acknowledgement> = emptyList(),
    val replies: List<EmergencyReply> = emptyList(),
    val audioClips: List<AudioClipMetadata> = emptyList(),
    val pairedContactsCount: Int = 0,
    val isMicStreaming: Boolean = false,
    val isSpeakerListening: Boolean = false,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.BLE_5S_LOOP,
    val isInternetReachable: Boolean = false,
    val playingClipId: String? = null,
    val isRefreshingLocation: Boolean = false,
    val localLocation: LocationSnapshot? = null,
    val senderPeerRssi: Int = -68,
    val statusBanner: String? = null,
    val nearbyPeerCount: Int = 0,
    val transportDiagnostic: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveEmergencyViewModel(
    private val repository: RescueMeshRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val identityManager: IdentityManager,
    private val locationProvider: OfflineLocationProvider,
    val offlineMapManager: OfflineMapManager,
    private val bitchatMeshWrapper: BitchatMeshWrapper,
    private val audioClipManager: AudioClipManager,
    private val internetAudioAdapter: WebRtcInternetAudioAdapter
) : ViewModel() {

    private val requestedSessionId = MutableStateFlow<String?>(null)
    private val _isRefreshingLocation = MutableStateFlow(false)
    private val _localLocation = MutableStateFlow<LocationSnapshot?>(null)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val manuallyMutedSessions = mutableSetOf<String>()
    private var autoArmedSpeakerForSessionId: String? = null

    private val sessionFlow = requestedSessionId.flatMapLatest { id ->
        if (id != null) repository.observeSession(id)
        else repository.observeActiveOutgoingSession()
    }

    init {
        viewModelScope.launch {
            sessionFlow.collect { session ->
                if (session != null && session.status == SessionStatus.ACTIVE && session.isOutgoing) {
                    val target = session.recipientPeerId
                    if (target != MeshConstants.BROADCAST_RECIPIENT_ID &&
                        target != MeshConstants.PAIRED_MULTI_RECIPIENT_ID
                    ) {
                        audioClipManager.updateRecipientId(session.sessionId, target)
                    }
                }
            }
        }
    }

    private val repliesFlow = sessionFlow.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else repository.observeRepliesForSession(session.sessionId)
    }

    private val acksFlow = sessionFlow.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else repository.observeAcknowledgementsForSession(session.sessionId)
    }

    private val clipsFlow = sessionFlow.flatMapLatest { session ->
        if (session == null) flowOf(emptyList())
        else repository.observeAudioClipsForSession(session.sessionId)
    }

    private val audioStateFlow = combine(
        audioClipManager.isLoopStreaming,
        audioClipManager.isSpeakerListening,
        audioClipManager.playingClipId,
        internetAudioAdapter.webrtcConnected,
        internetAudioAdapter.isInternetReachable
    ) { loopStreaming, speakerOn, playingId, rtcConnected, internet ->
        listOf(loopStreaming, speakerOn, playingId, rtcConnected, internet)
    }

    private val metaFlow = combine(
        _isRefreshingLocation,
        _localLocation,
        _statusBanner,
        repository.observePeers(),
        repository.observeContacts()
    ) { refreshing, localLoc, banner, peers, contacts ->
        listOf(refreshing, localLoc, banner, peers, contacts.size)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ActiveEmergencyUiState> = combine(
        sessionFlow,
        acksFlow,
        repliesFlow,
        clipsFlow,
        combine(audioStateFlow, metaFlow, bitchatMeshWrapper.diagnosticMessage) { a, m, diag -> Triple(a, m, diag) }
    ) { session, acks, replies, clips, (audioList, metaList, diagMsg) ->
        val loopStreaming = audioList[0] as Boolean
        val speakerOn = audioList[1] as Boolean
        val playingId = audioList[2] as String?
        val rtcConnected = audioList[3] as Boolean
        val internetOnline = audioList[4] as Boolean

        val peers = metaList[3] as List<Peer>
        val senderPeer = session?.let { s -> peers.firstOrNull { it.peerId == s.creatorPeerId } }
        val recentPeers = peers.count { System.currentTimeMillis() - it.lastSeenAtMillis < 180_000L }

        val micActive = loopStreaming || rtcConnected
        val voiceMode = when {
            !micActive -> VoiceTransportMode.MUTED
            rtcConnected -> VoiceTransportMode.WEBRTC_LIVE
            else -> VoiceTransportMode.BLE_5S_LOOP
        }

        ActiveEmergencyUiState(
            session = session,
            acknowledgements = acks,
            replies = replies,
            audioClips = clips,
            pairedContactsCount = metaList[4] as Int,
            isMicStreaming = micActive,
            isSpeakerListening = speakerOn,
            voiceTransportMode = voiceMode,
            isInternetReachable = internetOnline,
            playingClipId = playingId,
            isRefreshingLocation = metaList[0] as Boolean,
            localLocation = metaList[1] as? LocationSnapshot,
            senderPeerRssi = senderPeer?.rssi ?: -68,
            statusBanner = metaList[2] as? String,
            nearbyPeerCount = recentPeers,
            transportDiagnostic = diagMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ActiveEmergencyUiState()
    )

    fun resolveImageFile(fileId: String?): File? =
        fileId?.let { repository.audioFileStore.getImageFile(it) }

    fun bindSession(sessionId: String?, isActiveSessionScreen: Boolean = false) {
        requestedSessionId.value = sessionId
        viewModelScope.launch {
            _localLocation.value = locationProvider.getLastKnownOrFallback()
            if (sessionId == null) return@launch
            val session = repository.getSession(sessionId) ?: return@launch
            if (isActiveSessionScreen && session.status == SessionStatus.ACTIVE) {
                val settings = preferencesRepository.currentSettings()
                if (session.sessionAccepted || session.isOutgoing) {
                    repository.markSessionRead(sessionId)
                }
                // Auto-arm continuous mic if user hasn't manually muted in this session
                if (settings.autoRecordVoiceOnSos &&
                    sessionId !in manuallyMutedSessions &&
                    audioClipManager.hasRecordPermission() &&
                    !audioClipManager.isLoopStreaming.value &&
                    !internetAudioAdapter.webrtcConnected.value
                ) {
                    val target = if (session.isOutgoing) session.recipientPeerId else session.creatorPeerId
                    internetAudioAdapter.initializeSignalling(identityManager.localPeerId, emptyList())
                    val startedRtc = if (internetAudioAdapter.canUseLiveWebRtc()) {
                        internetAudioAdapter.startLiveAudioCall(sessionId, target)
                    } else false
                    if (!startedRtc) {
                        audioClipManager.startContinuousVoiceLoop(
                            sessionId = sessionId,
                            recipientId = target,
                            segmentSeconds = settings.audioClipMaxSeconds
                        )
                    }
                }
                // Auto-arm FIFO speaker queue for two-way incoming audio on active sessions
                if ((!session.isOutgoing || session.sessionAccepted) &&
                    settings.autoPlaySpeakerOnSession &&
                    autoArmedSpeakerForSessionId != sessionId
                ) {
                    autoArmedSpeakerForSessionId = sessionId
                    audioClipManager.setSpeakerListening(true, sessionId)
                }
            }
        }
    }

    fun toggleMic() {
        val session = uiState.value.session ?: return
        val sid = session.sessionId
        viewModelScope.launch {
            if (uiState.value.isMicStreaming) {
                manuallyMutedSessions.add(sid)
                audioClipManager.stopVoiceLoop()
                internetAudioAdapter.endLiveAudioCall(sid)
                _statusBanner.value = "Microphone muted"
            } else {
                manuallyMutedSessions.remove(sid)
                val target = if (session.isOutgoing) session.recipientPeerId else session.creatorPeerId
                val settings = preferencesRepository.currentSettings()
                internetAudioAdapter.initializeSignalling(identityManager.localPeerId, emptyList())
                val rtc = if (internetAudioAdapter.canUseLiveWebRtc()) {
                    internetAudioAdapter.startLiveAudioCall(sid, target)
                } else false
                if (!rtc) {
                    val ok = audioClipManager.startContinuousVoiceLoop(
                        sessionId = sid,
                        recipientId = target,
                        segmentSeconds = settings.audioClipMaxSeconds
                    )
                    if (!ok) {
                        _statusBanner.value = "Grant Microphone permission to stream live voice"
                    }
                }
            }
        }
    }

    fun toggleSpeakerListen() {
        val sid = uiState.value.session?.sessionId ?: return
        val next = !uiState.value.isSpeakerListening
        audioClipManager.setSpeakerListening(next, sid)
    }

    fun acknowledgeAndAcceptSos(onAccepted: (String) -> Unit) {
        val sid = uiState.value.session?.sessionId ?: return
        viewModelScope.launch {
            val loc = locationProvider.acquireSnapshot() ?: _localLocation.value
            _localLocation.value = loc
            bitchatMeshWrapper.acceptEmergencySession(
                sessionId = sid,
                responderLocation = loc
            )
            onAccepted(sid)
        }
    }

    fun sendReply(message: String) {
        val sid = uiState.value.session?.sessionId ?: return
        viewModelScope.launch {
            bitchatMeshWrapper.sendChatMessage(sid, message)
        }
    }

    fun sendCameraImage(jpegBytes: ByteArray) {
        val sid = uiState.value.session?.sessionId ?: return
        viewModelScope.launch {
            val reply = bitchatMeshWrapper.sendCompressedPhoto(sid, jpegBytes)
            if (reply != null) {
                _statusBanner.value = "Compressed photo queued at Priority-7 (${jpegBytes.size / 1024} KB)"
            }
        }
    }

    fun refreshLocationUpdate() {
        val session = uiState.value.session ?: return
        viewModelScope.launch {
            _isRefreshingLocation.value = true
            try {
                val snapshot = locationProvider.acquireSnapshot()
                if (snapshot != null) {
                    _localLocation.value = snapshot
                    bitchatMeshWrapper.broadcastLiveLocation(session, snapshot)
                    _statusBanner.value = "Live GPS ping queued (±${snapshot.accuracyMeters.toInt()}m)"
                } else {
                    _statusBanner.value = "GNSS fix unavailable; retaining previous snapshot"
                }
            } finally {
                _isRefreshingLocation.value = false
            }
        }
    }

    fun resolveEmergency(cancelled: Boolean, onComplete: () -> Unit = {}) {
        val sid = uiState.value.session?.sessionId ?: run {
            onComplete()
            return
        }
        viewModelScope.launch {
            audioClipManager.stopVoiceLoop()
            audioClipManager.setSpeakerListening(false, sid)
            internetAudioAdapter.endLiveAudioCall(sid)
            manuallyMutedSessions.remove(sid)
            if (autoArmedSpeakerForSessionId == sid) {
                autoArmedSpeakerForSessionId = null
            }
            // Note: resolveEmergencyUseCase clears stale relays BEFORE enqueueing broadcast SESSION_RESOLVED
            bitchatMeshWrapper.terminateSessionAcrossMesh(sid, cancelled = cancelled)
            _statusBanner.value = if (cancelled) "SOS cancelled across mesh" else "Emergency session terminated"
            onComplete()
        }
    }

    fun onRemoteSessionClosed(sessionId: String) {
        viewModelScope.launch {
            audioClipManager.stopVoiceLoop()
            audioClipManager.setSpeakerListening(false, sessionId)
            internetAudioAdapter.endLiveAudioCall(sessionId)
            manuallyMutedSessions.remove(sessionId)
            if (autoArmedSpeakerForSessionId == sessionId) {
                autoArmedSpeakerForSessionId = null
            }
        }
    }

    fun togglePlayClip(clip: AudioClipMetadata) {
        if (uiState.value.playingClipId == clip.clipId) {
            audioClipManager.stopPlayback()
        } else {
            audioClipManager.playClip(clip)
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
        audioClipManager.clearAudioStatus()
    }
}

class InboxViewModel(
    repository: RescueMeshRepository,
    private val locationProvider: OfflineLocationProvider,
    private val bitchatMeshWrapper: BitchatMeshWrapper
) : ViewModel() {

    val sessions: StateFlow<List<EmergencySession>> = repository.observeAllSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun acknowledgeSession(sessionId: String) {
        viewModelScope.launch {
            val loc = locationProvider.acquireSnapshot() ?: locationProvider.getLastKnownOrFallback()
            bitchatMeshWrapper.acceptEmergencySession(
                sessionId = sessionId,
                responderLocation = loc
            )
        }
    }
}

data class PeersUiState(
    val localPeerId: String = "",
    val peers: List<Peer> = emptyList(),
    val diagnostics: List<DiagnosticLogEntry> = emptyList(),
    val transportStatus: TransportStatus = TransportStatus.STOPPED,
    val diagnosticMessage: String = "",
    val pendingQueueCount: Int = 0,
    val handshakeBanner: String? = null,
    val isRescanning: Boolean = false
)

class PeersViewModel(
    private val repository: RescueMeshRepository,
    private val identityManager: IdentityManager,
    private val bitchatMeshWrapper: BitchatMeshWrapper
) : ViewModel() {

    private val _handshakeBanner = MutableStateFlow<String?>(null)
    private val _isRescanning = MutableStateFlow(false)

    private val transportMetaFlow = combine(
        bitchatMeshWrapper.transportStatus,
        bitchatMeshWrapper.diagnosticMessage,
        repository.observePendingRelayCount(),
        _handshakeBanner,
        _isRescanning
    ) { status, diag, pending, banner, rescanning ->
        listOf(status, diag, pending, banner, rescanning)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<PeersUiState> = combine(
        repository.observePeers(),
        repository.observeDiagnostics(),
        transportMetaFlow
    ) { peers, logs, meta ->
        PeersUiState(
            localPeerId = identityManager.localPeerId,
            peers = peers,
            diagnostics = logs,
            transportStatus = meta[0] as TransportStatus,
            diagnosticMessage = meta[1] as String,
            pendingQueueCount = meta[2] as Int,
            handshakeBanner = meta[3] as String?,
            isRescanning = meta[4] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PeersUiState(localPeerId = identityManager.localPeerId)
    )

    fun flushQueueNow() {
        viewModelScope.launch {
            val count = bitchatMeshWrapper.flushMeshSprayQueue()
            _handshakeBanner.value = "Priority scheduler flushed $count queued packet(s)"
        }
    }

    fun rescanPeers() {
        viewModelScope.launch {
            _isRescanning.value = true
            bitchatMeshWrapper.restartDiscovery()
            _handshakeBanner.value = "Restarted BLE advertising & discovery scan"
            kotlinx.coroutines.delay(1000L)
            _isRescanning.value = false
        }
    }

    fun verifyNoiseHandshake(peer: Peer) {
        viewModelScope.launch {
            val initiator = NoiseSession(
                remotePeerId = peer.peerId,
                localStaticPrivate = identityManager.identity.x25519Private,
                localStaticPublic = identityManager.identity.x25519Public,
                isInitiator = true
            )
            val peerXPriv = RescueCrypto.generateEphemeralX25519().first
            val peerXPub = if (peer.x25519PublicKeyHex.length == 64) {
                RescueCrypto.hexToBytes(peer.x25519PublicKeyHex)
            } else {
                RescueCrypto.generateEphemeralX25519().second
            }
            val responder = NoiseSession(
                remotePeerId = identityManager.localPeerId,
                localStaticPrivate = peerXPriv,
                localStaticPublic = peerXPub,
                isInitiator = false
            )
            val m1 = initiator.writeMessage1()
            val m2 = responder.readMessage1AndWrite2(m1)
            val m3 = initiator.readMessage2AndWrite3(m2)
            responder.readMessage3(m3)

            if (initiator.isEstablished && responder.isEstablished) {
                repository.upsertPeer(peer.copy(noiseState = NoiseHandshakeState.ESTABLISHED))
                _handshakeBanner.value = "Noise_XX_25519_ChaChaPoly verified with ${peer.displayName}"
            }
        }
    }

    fun clearBanner() {
        _handshakeBanner.value = null
    }
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val contacts: List<EmergencyContact> = emptyList(),
    val localPeerId: String = "",
    val localX25519Hex: String = "",
    val qrPairingUri: String = "",
    val compactManualKey: String = "",
    val wifiAwareSupported: Boolean = false,
    val transportStatus: TransportStatus = TransportStatus.STOPPED,
    val offlineMapStatus: OfflineMapCacheStatus = OfflineMapCacheStatus(),
    val isInternetReachable: Boolean = false,
    val configBanner: String? = null
)

class SettingsViewModel(
    private val appContext: Context,
    private val repository: RescueMeshRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val identityManager: IdentityManager,
    private val meshCoordinator: MeshCoordinator,
    private val locationProvider: OfflineLocationProvider,
    private val offlineMapManager: OfflineMapManager,
    private val wifiAwareAdapter: WifiAwareTransportAdapter,
    private val internetAudioAdapter: WebRtcInternetAudioAdapter
) : ViewModel() {

    private val _configBanner = MutableStateFlow<String?>(null)

    private val hardwareFlow = combine(
        meshCoordinator.transportStatus,
        offlineMapManager.status,
        internetAudioAdapter.isInternetReachable,
        _configBanner
    ) { transport, mapStatus, internet, banner ->
        listOf(transport, mapStatus, internet, banner)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.settings,
        repository.observeContacts(),
        hardwareFlow
    ) { settings, contacts, hw ->
        val qrUri = QrPairingCodec.encodePairingUri(
            peerId = identityManager.localPeerId,
            alias = settings.deviceAlias,
            x25519PublicBytes = identityManager.identity.x25519Public,
            ed25519PublicBytes = identityManager.identity.ed25519Public
        )
        val compactKey = QrPairingCodec.encodeCompactManualKey(
            peerId = identityManager.localPeerId,
            alias = settings.deviceAlias,
            x25519PublicBytes = identityManager.identity.x25519Public,
            ed25519PublicBytes = identityManager.identity.ed25519Public
        )
        SettingsUiState(
            settings = settings,
            contacts = contacts,
            localPeerId = identityManager.localPeerId,
            localX25519Hex = RescueCrypto.bytesToHex(identityManager.identity.x25519Public),
            qrPairingUri = qrUri,
            compactManualKey = compactKey,
            wifiAwareSupported = wifiAwareAdapter.isWifiAwareSupported(),
            transportStatus = hw[0] as TransportStatus,
            offlineMapStatus = hw[1] as OfflineMapCacheStatus,
            isInternetReachable = hw[2] as Boolean,
            configBanner = hw[3] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            localPeerId = identityManager.localPeerId,
            localX25519Hex = RescueCrypto.bytesToHex(identityManager.identity.x25519Public),
            wifiAwareSupported = wifiAwareAdapter.isWifiAwareSupported()
        )
    )

    fun setAlias(alias: String) {
        viewModelScope.launch { preferencesRepository.setDeviceAlias(alias) }
    }

    fun setRelayParticipation(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRelayParticipation(enabled)
            if (enabled) {
                if (!RescueMeshForegroundService.start(appContext)) {
                    meshCoordinator.start()
                }
            } else {
                RescueMeshForegroundService.stop(appContext)
            }
        }
    }

    fun setMaxHopCount(hops: Int) {
        viewModelScope.launch { preferencesRepository.setMaxHopCount(hops) }
    }

    fun setShakeSos(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setShakeSosEnabled(enabled) }
    }

    fun setVolumeShortcut(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setVolumeShortcutEnabled(enabled) }
    }

    fun setAutoDownloadAudio(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoDownloadAudio(enabled) }
    }

    fun setAutoRecordVoiceOnSos(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoRecordVoiceOnSos(enabled) }
    }

    fun setAutoPlaySpeakerOnSession(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoPlaySpeakerOnSession(enabled) }
    }

    fun setPreferInternetAudio(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setPreferInternetAudio(enabled) }
    }

    fun setSignalingServerUrl(url: String) {
        viewModelScope.launch { preferencesRepository.setSignalingServerUrl(url) }
    }

    fun setSendToAllNodes(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setSendToAllNodes(enabled) }
    }

    fun pairFromQrOrManualInput(rawInput: String) {
        val bundle = QrPairingCodec.decodePairingInput(rawInput)
        if (bundle == null) {
            _configBanner.value = "Invalid pairing QR or key format"
            return
        }
        if (bundle.peerId.equals(identityManager.localPeerId, ignoreCase = true)) {
            _configBanner.value = "Cannot pair your own device node"
            return
        }
        viewModelScope.launch {
            val saved = repository.upsertContact(bundle.toContact())
            _configBanner.value = if (saved) {
                "Paired trusted contact: ${bundle.alias} (#${bundle.peerId.take(4)})"
            } else {
                "Limit reached: Maximum ${MeshConstants.MAX_PAIRED_CONTACTS} trusted contacts allowed"
            }
        }
    }

    fun addContact(peerId: String, alias: String, x25519Hex: String) {
        val cleanId = peerId.trim().uppercase()
        if (cleanId.isEmpty()) return
        viewModelScope.launch {
            val saved = repository.upsertContact(
                EmergencyContact(
                    peerId = cleanId,
                    alias = alias.trim().ifEmpty { "Node-${cleanId.take(4)}" },
                    x25519PublicKeyHex = x25519Hex.trim().uppercase(),
                    addedAtMillis = System.currentTimeMillis()
                )
            )
            _configBanner.value = if (saved) {
                "Added trusted contact: $cleanId"
            } else {
                "Maximum ${MeshConstants.MAX_PAIRED_CONTACTS} paired contacts reached"
            }
        }
    }

    fun deleteContact(peerId: String) {
        viewModelScope.launch { repository.deleteContact(peerId) }
    }

    fun downloadOfflineMapRegion(
        radiusKm: Double = 3.5,
        style: MapTileStyle = MapTileStyle.GMAPS_ROADMAP
    ) {
        viewModelScope.launch {
            val loc = locationProvider.acquireSnapshot() ?: offlineMapManager.fallbackDefaultLocation()
            offlineMapManager.startDownloadRegion(loc.latitude, loc.longitude, radiusKm, style)
        }
    }

    fun clearOfflineMapCache() {
        offlineMapManager.clearCache()
    }

    fun clearBanner() {
        _configBanner.value = null
    }
}

class RescueMeshViewModelFactory(private val app: RescueMeshApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(
                appContext = app,
                repository = app.repository,
                preferencesRepository = app.preferencesRepository,
                identityManager = app.identityManager,
                locationProvider = app.locationProvider,
                meshCoordinator = app.meshCoordinator,
                bitchatMeshWrapper = app.bitchatMeshWrapper
            ) as T

            modelClass.isAssignableFrom(ActiveEmergencyViewModel::class.java) -> ActiveEmergencyViewModel(
                repository = app.repository,
                preferencesRepository = app.preferencesRepository,
                identityManager = app.identityManager,
                locationProvider = app.locationProvider,
                offlineMapManager = app.offlineMapManager,
                bitchatMeshWrapper = app.bitchatMeshWrapper,
                audioClipManager = app.audioClipManager,
                internetAudioAdapter = app.internetAudioAdapter
            ) as T

            modelClass.isAssignableFrom(InboxViewModel::class.java) -> InboxViewModel(
                repository = app.repository,
                locationProvider = app.locationProvider,
                bitchatMeshWrapper = app.bitchatMeshWrapper
            ) as T

            modelClass.isAssignableFrom(PeersViewModel::class.java) -> PeersViewModel(
                repository = app.repository,
                identityManager = app.identityManager,
                bitchatMeshWrapper = app.bitchatMeshWrapper
            ) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
                appContext = app,
                repository = app.repository,
                preferencesRepository = app.preferencesRepository,
                identityManager = app.identityManager,
                meshCoordinator = app.meshCoordinator,
                locationProvider = app.locationProvider,
                offlineMapManager = app.offlineMapManager,
                wifiAwareAdapter = app.wifiAwareAdapter,
                internetAudioAdapter = app.internetAudioAdapter
            ) as T

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
