package com.example.network

import com.example.domain.model.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransportStatus {
    STOPPED,
    STARTING,
    ACTIVE_ADVERTISING_AND_SCANNING,
    BLUETOOTH_DISABLED,
    LOCATION_DISABLED,
    PERMISSION_MISSING,
    HARDWARE_UNAVAILABLE
}

data class IncomingTransportFrame(
    val sourceAddress: String,
    val rawBytes: ByteArray,
    val rssi: Int? = null
)

/**
 * Transport abstraction separating RescueMesh's mesh protocol & store-carry-forward routing
 * from concrete radios (BLE GATT first, Wi-Fi Aware & WebRTC internet audio later).
 */
interface MeshTransport {
    val transportName: String
    val status: StateFlow<TransportStatus>
    val diagnosticMessage: StateFlow<String>
        get() = MutableStateFlow("").asStateFlow()

    suspend fun startTransport()
    suspend fun stopTransport()
    suspend fun restartDiscovery() {
        stopTransport()
        startTransport()
    }
    fun discoverPeers(): Flow<Peer>
    suspend fun sendData(targetPeerId: String?, data: ByteArray): Boolean
    fun receiveData(): Flow<IncomingTransportFrame>
    fun observeConnectionStatus(): Flow<TransportStatus>
    fun hasLiveConnections(): Boolean = false
    fun connectedPeerIds(): List<String> = emptyList()
}

/**
 * Milestone 9 interface contract for Wi-Fi Aware (NAN) high-bandwidth local audio/media transport.
 * Designed so it can be plugged into MeshCoordinator without modifying EmergencyPacket formats.
 */
interface WifiAwareTransportContract : MeshTransport {
    fun isWifiAwareSupported(): Boolean
    suspend fun establishHighBandwidthAudioSession(peerId: String): Boolean
}

/**
 * Milestone 9 interface contract for Internet-assisted live WebRTC audio when cellular/Wi-Fi
 * connectivity is present, accounting for signalling and ICE/STUN/TURN negotiation.
 */
interface InternetAudioTransportContract {
    data class IceServerConfig(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null
    )

    val isInternetReachable: StateFlow<Boolean>
    suspend fun initializeSignalling(localPeerId: String, iceServers: List<IceServerConfig>)
    suspend fun startLiveAudioCall(sessionId: String, remotePeerId: String): Boolean
    suspend fun endLiveAudioCall(sessionId: String)
}
