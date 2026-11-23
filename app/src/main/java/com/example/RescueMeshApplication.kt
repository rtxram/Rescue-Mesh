package com.example

import android.app.Application
import com.example.audio.AudioClipManager
import com.example.audio.WebRtcInternetAudioAdapter
import com.example.audio.WifiAwareTransportAdapter
import com.example.domain.model.SessionStatus
import com.example.domain.usecase.AcknowledgeEmergencyUseCase
import com.example.domain.usecase.CreateSosUseCase
import com.example.domain.usecase.ForwardPendingMessagesUseCase
import com.example.domain.usecase.ReceiveEmergencyPacketUseCase
import com.example.domain.usecase.ResolveEmergencyUseCase
import com.example.domain.usecase.SendEmergencyImageUseCase
import com.example.domain.usecase.SendEmergencyPacketUseCase
import com.example.domain.usecase.SendEmergencyReplyUseCase
import com.example.domain.usecase.SendLiveLocationUpdateUseCase
import com.example.domain.usecase.StoreRelayPacketUseCase
import com.example.location.OfflineLocationProvider
import com.example.location.OfflineMapManager
import com.example.network.BleGattMeshTransport
import com.example.network.DuplicateDetectionManager
import com.example.network.MeshCoordinator
import com.example.network.MessageRouter
import com.example.network.PacketReceiver
import com.example.network.PeerDiscoveryManager
import com.example.network.PriorityScheduler
import com.example.network.StoreForwardQueue
import com.example.network.bitchat.BitchatMeshWrapper
import com.example.security.IdentityManager
import com.example.security.RescueCrypto
import com.example.storage.AudioFileStore
import com.example.storage.RescueMeshDatabase
import com.example.storage.RescueMeshRepository
import com.example.storage.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RescueMeshApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var currentAlias: String = "RescueNode"
        private set

    lateinit var database: RescueMeshDatabase
        private set
    lateinit var repository: RescueMeshRepository
        private set
    lateinit var preferencesRepository: UserPreferencesRepository
        private set
    lateinit var identityManager: IdentityManager
        private set
    lateinit var locationProvider: OfflineLocationProvider
        private set
    lateinit var offlineMapManager: OfflineMapManager
        private set
    lateinit var bleTransport: BleGattMeshTransport
        private set
    lateinit var meshCoordinator: MeshCoordinator
        private set
    lateinit var bitchatMeshWrapper: BitchatMeshWrapper
        private set
    lateinit var audioClipManager: AudioClipManager
        private set
    lateinit var wifiAwareAdapter: WifiAwareTransportAdapter
        private set
    lateinit var internetAudioAdapter: WebRtcInternetAudioAdapter
        private set

    // Use cases
    lateinit var createSosUseCase: CreateSosUseCase
        private set
    lateinit var sendEmergencyPacketUseCase: SendEmergencyPacketUseCase
        private set
    lateinit var receiveEmergencyPacketUseCase: ReceiveEmergencyPacketUseCase
        private set
    lateinit var storeRelayPacketUseCase: StoreRelayPacketUseCase
        private set
    lateinit var forwardPendingMessagesUseCase: ForwardPendingMessagesUseCase
        private set
    lateinit var acknowledgeEmergencyUseCase: AcknowledgeEmergencyUseCase
        private set
    lateinit var sendEmergencyReplyUseCase: SendEmergencyReplyUseCase
        private set
    lateinit var sendEmergencyImageUseCase: SendEmergencyImageUseCase
        private set
    lateinit var sendLiveLocationUpdateUseCase: SendLiveLocationUpdateUseCase
        private set
    lateinit var resolveEmergencyUseCase: ResolveEmergencyUseCase
        private set

    override fun onCreate() {
        super.onCreate()
        database = RescueMeshDatabase.getInstance(this)
        val audioStore = AudioFileStore(this)
        repository = RescueMeshRepository(database, audioStore)
        preferencesRepository = UserPreferencesRepository(this)
        identityManager = IdentityManager(this)
        locationProvider = OfflineLocationProvider(this)
        offlineMapManager = OfflineMapManager(this)

        appScope.launch {
            preferencesRepository.settings.collect { settings ->
                currentAlias = settings.deviceAlias
            }
        }

        bleTransport = BleGattMeshTransport(
            context = this,
            localPeerIdProvider = { identityManager.localPeerId },
            localAliasProvider = { currentAlias },
            localEd25519HexProvider = { RescueCrypto.bytesToHex(identityManager.identity.ed25519Public) },
            localX25519HexProvider = { RescueCrypto.bytesToHex(identityManager.identity.x25519Public) }
        )

        val duplicateDetection = DuplicateDetectionManager(repository)
        val storeForwardQueue = StoreForwardQueue(repository)
        val priorityScheduler = PriorityScheduler(storeForwardQueue)
        val peerDiscoveryManager = PeerDiscoveryManager(repository)
        val packetReceiver = PacketReceiver(duplicateDetection, identityManager)

        lateinit var coordinatorRef: MeshCoordinator
        val messageRouter = MessageRouter(
            repository = repository,
            storeForwardQueue = storeForwardQueue,
            identityManager = identityManager,
            preferencesRepository = preferencesRepository,
            onSessionTerminated = { sessionId, _ ->
                if (::audioClipManager.isInitialized) {
                    audioClipManager.stopVoiceLoop()
                    audioClipManager.setSpeakerListening(false, sessionId)
                }
                if (::internetAudioAdapter.isInitialized) {
                    internetAudioAdapter.endLiveAudioCall(sessionId)
                }
            },
            onIncomingAlert = { session -> coordinatorRef.notifyAlert(session) }
        )

        coordinatorRef = MeshCoordinator(
            transport = bleTransport,
            repository = repository,
            peerDiscoveryManager = peerDiscoveryManager,
            storeForwardQueue = storeForwardQueue,
            priorityScheduler = priorityScheduler,
            packetReceiver = packetReceiver,
            messageRouter = messageRouter,
            onPeriodicLocationTick = {
                val activeSessions = repository.observeAllSessions().first().filter {
                    it.status == SessionStatus.ACTIVE && (it.isOutgoing || it.sessionAccepted)
                }
                if (activeSessions.isNotEmpty()) {
                    val freshLoc = locationProvider.getLastKnownOrFallback()
                    if (freshLoc != null) {
                        for (session in activeSessions) {
                            sendLiveLocationUpdateUseCase(session, freshLoc)
                        }
                    }
                }
            }
        )
        meshCoordinator = coordinatorRef

        sendEmergencyPacketUseCase = SendEmergencyPacketUseCase(
            repository = repository,
            messageRouter = messageRouter,
            storeForwardQueue = storeForwardQueue,
            preferencesRepository = preferencesRepository,
            coordinatorProvider = { meshCoordinator }
        )
        createSosUseCase = CreateSosUseCase(
            repository = repository,
            identityManager = identityManager,
            preferencesRepository = preferencesRepository,
            sendEmergencyPacketUseCase = sendEmergencyPacketUseCase
        )
        receiveEmergencyPacketUseCase = ReceiveEmergencyPacketUseCase(packetReceiver, messageRouter)
        storeRelayPacketUseCase = StoreRelayPacketUseCase(storeForwardQueue)
        forwardPendingMessagesUseCase = ForwardPendingMessagesUseCase { meshCoordinator }
        acknowledgeEmergencyUseCase = AcknowledgeEmergencyUseCase(
            repository = repository,
            identityManager = identityManager,
            messageRouter = messageRouter,
            preferencesRepository = preferencesRepository,
            coordinatorProvider = { meshCoordinator }
        )
        sendEmergencyReplyUseCase = SendEmergencyReplyUseCase(
            repository = repository,
            identityManager = identityManager,
            preferencesRepository = preferencesRepository,
            sendEmergencyPacketUseCase = sendEmergencyPacketUseCase
        )
        sendEmergencyImageUseCase = SendEmergencyImageUseCase(
            repository = repository,
            identityManager = identityManager,
            preferencesRepository = preferencesRepository,
            sendEmergencyPacketUseCase = sendEmergencyPacketUseCase,
            forwardPendingMessagesUseCase = forwardPendingMessagesUseCase
        )
        sendLiveLocationUpdateUseCase = SendLiveLocationUpdateUseCase(
            repository = repository,
            identityManager = identityManager,
            sendEmergencyPacketUseCase = sendEmergencyPacketUseCase
        )
        resolveEmergencyUseCase = ResolveEmergencyUseCase(repository, sendEmergencyPacketUseCase)

        bitchatMeshWrapper = BitchatMeshWrapper(
            bleTransport = bleTransport,
            coordinator = meshCoordinator,
            repository = repository,
            createSosUseCase = createSosUseCase,
            acknowledgeEmergencyUseCase = acknowledgeEmergencyUseCase,
            sendEmergencyReplyUseCase = sendEmergencyReplyUseCase,
            sendEmergencyImageUseCase = sendEmergencyImageUseCase,
            sendLiveLocationUpdateUseCase = sendLiveLocationUpdateUseCase,
            resolveEmergencyUseCase = resolveEmergencyUseCase,
            forwardPendingMessagesUseCase = forwardPendingMessagesUseCase
        )

        audioClipManager = AudioClipManager(
            context = this,
            repository = repository,
            identityManager = identityManager,
            sendEmergencyPacketUseCase = sendEmergencyPacketUseCase,
            forwardPendingMessagesUseCase = forwardPendingMessagesUseCase
        )
        wifiAwareAdapter = WifiAwareTransportAdapter(this)
        internetAudioAdapter = WebRtcInternetAudioAdapter(this, preferencesRepository)
    }
}
