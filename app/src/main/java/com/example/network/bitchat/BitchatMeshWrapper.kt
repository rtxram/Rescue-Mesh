package com.example.network.bitchat

import com.example.domain.model.EmergencyContext
import com.example.domain.model.EmergencyReply
import com.example.domain.model.EmergencySession
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.example.domain.usecase.AcknowledgeEmergencyUseCase
import com.example.domain.usecase.CreateSosUseCase
import com.example.domain.usecase.ForwardPendingMessagesUseCase
import com.example.domain.usecase.ResolveEmergencyUseCase
import com.example.domain.usecase.SendEmergencyImageUseCase
import com.example.domain.usecase.SendEmergencyReplyUseCase
import com.example.domain.usecase.SendLiveLocationUpdateUseCase
import com.example.network.BleGattMeshTransport
import com.example.network.MeshCoordinator
import com.example.network.TransportStatus
import com.example.storage.RescueMeshRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Protocol specification constants aligning RescueMesh's mesh wrapper with Bitchat's
 * open-source decentralized BLE GATT Store-Carry-Forward architecture.
 */
object BitchatProtocolSpec {
    const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CHAR_RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CHAR_TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    const val PROTOCOL_VERSION: Byte = 2
    const val UNLIMITED_HOPS: Int = MeshConstants.UNLIMITED_HOPS
    const val SPRAY_SCAN_CADENCE_MS: Long = 2_000L
    const val VOICE_SEGMENT_SECONDS: Int = MeshConstants.VOICE_SEGMENT_SECONDS
}

data class BitchatMeshTelemetry(
    val transportStatus: TransportStatus,
    val liveLinkCount: Int,
    val pendingRelayCount: Int,
    val isCoordinatorRunning: Boolean,
    val diagnosticSummary: String
)

/**
 * Clean wrapper around the Bitchat-modeled decentralized mesh stack (dual-role BLE GATT +
 * Ed25519/X25519 E2EE envelopes + Room store-carry-forward spray + unlimited hop relay +
 * two-way session accept/terminate synchronization).
 */
class BitchatMeshWrapper(
    private val bleTransport: BleGattMeshTransport,
    private val coordinator: MeshCoordinator,
    private val repository: RescueMeshRepository,
    private val createSosUseCase: CreateSosUseCase,
    private val acknowledgeEmergencyUseCase: AcknowledgeEmergencyUseCase,
    private val sendEmergencyReplyUseCase: SendEmergencyReplyUseCase,
    private val sendEmergencyImageUseCase: SendEmergencyImageUseCase,
    private val sendLiveLocationUpdateUseCase: SendLiveLocationUpdateUseCase,
    private val resolveEmergencyUseCase: ResolveEmergencyUseCase,
    private val forwardPendingMessagesUseCase: ForwardPendingMessagesUseCase
) {
    val incomingAlerts: Flow<EmergencySession> = coordinator.incomingAlertEvents
    val transportStatus: StateFlow<TransportStatus> = coordinator.transportStatus
    val diagnosticMessage: StateFlow<String> = coordinator.diagnosticMessage

    fun startMesh() = coordinator.start()

    suspend fun stopMesh() = coordinator.stop()

    suspend fun restartDiscovery() = coordinator.restartDiscovery()

    suspend fun flushMeshSprayQueue(): Int = forwardPendingMessagesUseCase()

    fun hasLiveBleConnections(): Boolean = bleTransport.hasLiveConnections()

    fun observeTelemetry(): Flow<BitchatMeshTelemetry> = combine(
        coordinator.transportStatus,
        coordinator.diagnosticMessage,
        repository.observePendingRelayCount(),
        coordinator.isRunning
    ) { status, diag, pending, running ->
        BitchatMeshTelemetry(
            transportStatus = status,
            liveLinkCount = bleTransport.connectedPeerIds().size,
            pendingRelayCount = pending,
            isCoordinatorRunning = running,
            diagnosticSummary = diag
        )
    }

    suspend fun sprayEmergencySos(
        context: EmergencyContext,
        location: LocationSnapshot?,
        recipientPeerId: String
    ): EmergencySession = createSosUseCase(
        context = context,
        location = location,
        recipientPeerId = recipientPeerId
    )

    suspend fun acceptEmergencySession(
        sessionId: String,
        responderLocation: LocationSnapshot?
    ) {
        acknowledgeEmergencyUseCase(
            sessionId = sessionId,
            isFinalDelivery = true,
            responderLocation = responderLocation
        )
    }

    suspend fun sendChatMessage(sessionId: String, text: String): EmergencyReply? =
        sendEmergencyReplyUseCase(sessionId, text)

    suspend fun sendCompressedPhoto(sessionId: String, jpegBytes: ByteArray): EmergencyReply? =
        sendEmergencyImageUseCase(sessionId, jpegBytes)

    suspend fun broadcastLiveLocation(session: EmergencySession, location: LocationSnapshot) {
        sendLiveLocationUpdateUseCase(session, location)
    }

    suspend fun terminateSessionAcrossMesh(sessionId: String, cancelled: Boolean) {
        resolveEmergencyUseCase(sessionId = sessionId, cancelled = cancelled)
    }
}
