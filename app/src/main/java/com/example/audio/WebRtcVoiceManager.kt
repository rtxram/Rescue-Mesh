package com.example.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.domain.model.VoiceTransportMode
import com.example.network.InternetAudioTransportContract
import com.example.storage.UserPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

@Serializable
private data class WebRtcSignalEnvelope(
    val room: String,
    val from: String,
    val kind: String, // "join", "offer", "answer", "ice"
    val sdp: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = ""
)

/**
 * Real Internet-assisted WebRTC audio manager (`io.getstream:stream-webrtc-android`) +
 * OkHttp WebSocket signaling client. Automatically monitors Android connectivity and
 * cleanly degrades to BLE 5-second voice clips whenever offline or no signaling endpoint is configured.
 */
class WebRtcInternetAudioAdapter(
    private val context: Context,
    private val preferencesRepository: UserPreferencesRepository
) : InternetAudioTransportContract {

    companion object {
        private const val TAG = "WebRtcVoice"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _isInternetReachable = MutableStateFlow(checkCurrentConnectivity())
    override val isInternetReachable: StateFlow<Boolean> = _isInternetReachable.asStateFlow()

    private val _webrtcConnected = MutableStateFlow(false)
    val webrtcConnected: StateFlow<Boolean> = _webrtcConnected.asStateFlow()

    private var factoryInitialized = false
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var signalingSocket: WebSocket? = null
    private var activeSessionId: String? = null
    private var localPeerId: String = ""
    private var configuredIceServers: List<InternetAudioTransportContract.IceServerConfig> = emptyList()

    init {
        registerConnectivityCallback()
    }

    private fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerConnectivityCallback() {
        val cm = connectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isInternetReachable.value = checkCurrentConnectivity()
                }

                override fun onLost(network: Network) {
                    _isInternetReachable.value = checkCurrentConnectivity()
                    if (!_isInternetReachable.value) {
                        scope.launch { activeSessionId?.let { endLiveAudioCall(it) } }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _isInternetReachable.value =
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    override suspend fun initializeSignalling(
        localPeerId: String,
        iceServers: List<InternetAudioTransportContract.IceServerConfig>
    ) {
        this.localPeerId = localPeerId
        this.configuredIceServers = iceServers.ifEmpty {
            listOf(InternetAudioTransportContract.IceServerConfig(listOf("stun:stun.l.google.com:19302")))
        }
        ensureFactoryInitialized()
    }

    private fun ensureFactoryInitialized() {
        if (factoryInitialized) return
        runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factoryInitialized = true
        }.onFailure {
            Log.w(TAG, "PeerConnectionFactory init skipped/failed", it)
        }
    }

    suspend fun canUseLiveWebRtc(): Boolean {
        val settings = preferencesRepository.currentSettings()
        return _isInternetReachable.value &&
            settings.preferInternetAudioWhenOnline &&
            settings.signalingServerUrl.isNotBlank()
    }

    suspend fun resolveVoiceMode(micEnabled: Boolean): VoiceTransportMode {
        if (!micEnabled) return VoiceTransportMode.MUTED
        return if (_webrtcConnected.value && canUseLiveWebRtc()) {
            VoiceTransportMode.WEBRTC_LIVE
        } else {
            VoiceTransportMode.BLE_5S_LOOP
        }
    }

    override suspend fun startLiveAudioCall(sessionId: String, remotePeerId: String): Boolean {
        val settings = preferencesRepository.currentSettings()
        val wsUrl = settings.signalingServerUrl.trim()
        if (!_isInternetReachable.value || !settings.preferInternetAudioWhenOnline || wsUrl.isBlank()) {
            return false
        }

        ensureFactoryInitialized()
        val factory = peerConnectionFactory ?: return false
        activeSessionId = sessionId

        val iceList = configuredIceServers.ifEmpty {
            listOf(InternetAudioTransportContract.IceServerConfig(listOf("stun:stun.l.google.com:19302")))
        }.map { cfg ->
            PeerConnection.IceServer.builder(cfg.urls).apply {
                if (cfg.username != null && cfg.credential != null) {
                    setUsername(cfg.username)
                    setPassword(cfg.credential)
                }
            }.createIceServer()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceList)
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                _webrtcConnected.value =
                    newState == PeerConnection.IceConnectionState.CONNECTED ||
                    newState == PeerConnection.IceConnectionState.COMPLETED
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                sendSignal(
                    WebRtcSignalEnvelope(
                        room = sessionId,
                        from = localPeerId,
                        kind = "ice",
                        sdpMid = candidate.sdpMid ?: "",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                )
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }) ?: return false

        val audioConstraints = MediaConstraints()
        val audioSource = factory.createAudioSource(audioConstraints)
        val audioTrack = factory.createAudioTrack("RM_AUDIO_$sessionId", audioSource).apply {
            setEnabled(true)
        }
        pc.addTrack(audioTrack, listOf("RM_STREAM_$sessionId"))

        peerConnection = pc
        localAudioSource = audioSource
        localAudioTrack = audioTrack

        return connectSignalingAndOffer(wsUrl, sessionId, remotePeerId)
    }

    private suspend fun connectSignalingAndOffer(wsUrl: String, sessionId: String, remotePeerId: String): Boolean {
        val openDeferred = CompletableDeferred<Boolean>()
        return try {
            val request = Request.Builder().url(wsUrl).build()
            signalingSocket?.close(1000, "Restarting")
            signalingSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openDeferred.complete(true)
                    sendSignal(WebRtcSignalEnvelope(room = sessionId, from = localPeerId, kind = "join"))
                    if (localPeerId < remotePeerId) {
                        createAndSendOffer(sessionId)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val env = runCatching { json.decodeFromString<WebRtcSignalEnvelope>(text) }.getOrNull() ?: return
                    if (env.room != sessionId || env.from == localPeerId) return
                    when (env.kind) {
                        "join" -> createAndSendOffer(sessionId)
                        "offer" -> handleRemoteOffer(sessionId, env.sdp)
                        "answer" -> peerConnection?.setRemoteDescription(
                            NoOpSdpObserver(),
                            SessionDescription(SessionDescription.Type.ANSWER, env.sdp)
                        )
                        "ice" -> peerConnection?.addIceCandidate(
                            IceCandidate(env.sdpMid, env.sdpMLineIndex, env.candidate)
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebRTC signaling offline; using BLE 5s audio stream", t)
                    openDeferred.complete(false)
                    _webrtcConnected.value = false
                }
            })
            val opened = withTimeoutOrNull(3_500L) { openDeferred.await() } ?: false
            if (!opened) {
                endLiveAudioCall(sessionId)
            }
            opened
        } catch (e: Exception) {
            Log.w(TAG, "Failed connecting WebRTC signaling", e)
            openDeferred.complete(false)
            endLiveAudioCall(sessionId)
            false
        }
    }

    private fun createAndSendOffer(sessionId: String) {
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpObserver by NoOpSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(NoOpSdpObserver(), sdp)
                sendSignal(WebRtcSignalEnvelope(room = sessionId, from = localPeerId, kind = "offer", sdp = sdp.description))
            }
        }, MediaConstraints())
    }

    private fun handleRemoteOffer(sessionId: String, remoteSdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(NoOpSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        pc.createAnswer(object : SdpObserver by NoOpSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(NoOpSdpObserver(), sdp)
                sendSignal(WebRtcSignalEnvelope(room = sessionId, from = localPeerId, kind = "answer", sdp = sdp.description))
            }
        }, MediaConstraints())
    }

    private fun sendSignal(envelope: WebRtcSignalEnvelope) {
        val payload = json.encodeToString(WebRtcSignalEnvelope.serializer(), envelope)
        signalingSocket?.send(payload)
    }

    override suspend fun endLiveAudioCall(sessionId: String) {
        activeSessionId = null
        _webrtcConnected.value = false
        runCatching { signalingSocket?.close(1000, "Ended") }
        signalingSocket = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { localAudioSource?.dispose() }
        localAudioSource = null
        runCatching { peerConnection?.close() }
        peerConnection = null
    }

    private open class NoOpSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
