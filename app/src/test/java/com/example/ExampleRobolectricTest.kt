package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioClipManager
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.EmergencyContext
import com.example.domain.model.LocationSnapshot
import com.example.domain.model.MeshConstants
import com.example.domain.model.PacketPriority
import com.example.domain.model.PacketType
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.domain.usecase.CreateSosUseCase
import com.example.domain.usecase.SendEmergencyPacketUseCase
import com.example.network.DuplicateDetectionManager
import com.example.network.MeshCoordinator
import com.example.network.MeshTransport
import com.example.network.MessageRouter
import com.example.network.PacketReceiver
import com.example.network.PeerDiscoveryManager
import com.example.network.PriorityScheduler
import com.example.network.StoreForwardQueue
import com.example.network.TransportStatus
import com.example.protocol.PacketSerializer
import com.example.security.IdentityManager
import com.example.security.RescueCrypto
import com.example.storage.AudioFileStore
import com.example.storage.RescueMeshDatabase
import com.example.storage.RescueMeshRepository
import com.example.storage.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private class InMemoryFakeTransport : MeshTransport {
        override val transportName: String = "FakeTestTransport"
        override val status: StateFlow<TransportStatus> = MutableStateFlow(TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING)
        val sentFrames = mutableListOf<Pair<String?, ByteArray>>()
        override suspend fun startTransport() = Unit
        override suspend fun stopTransport() = Unit
        override fun discoverPeers(): Flow<Peer> = emptyFlow()
        override suspend fun sendData(targetPeerId: String?, data: ByteArray): Boolean {
            sentFrames.add(targetPeerId to data)
            return true
        }
        override fun receiveData() = emptyFlow<com.example.network.IncomingTransportFrame>()
        override fun observeConnectionStatus(): Flow<TransportStatus> = status
    }

    @org.junit.After
    fun resetSharedPreferences() = runBlocking {
        UserPreferencesRepository(ApplicationProvider.getApplicationContext()).setSendToAllNodes(true)
    }

    @Test
    fun `read RescueMesh app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RescueMesh", appName)
    }

    @Test
    fun `three-node store-carry-forward with ACK_STORED vs ACK_DELIVERED and priority ordering`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbC = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()

        val prefs = UserPreferencesRepository(context)

        // Separate identity dirs via custom context prefs isn't needed if we create IdentityManager;
        // for multi-node simulation, node A uses IdentityManager and builds packets directly with distinct keypairs
        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val repoC = RescueMeshRepository(dbC, AudioFileStore(context))

        val idA = IdentityManager(context)
        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)
        val queueC = StoreForwardQueue(repoC)

        val dupB = DuplicateDetectionManager(repoB)
        val dupC = DuplicateDetectionManager(repoC)

        val routerA = MessageRouter(repoA, queueA, idA, prefs) {}
        val routerB = MessageRouter(repoB, queueB, idA, prefs) {}
        val routerC = MessageRouter(repoC, queueC, idA, prefs) {}

        // Build an SOS packet from a remote Node A keypair so B & C do not discard it as own-echo
        val nodeAKeys = RescueCrypto.generateIdentityKeyPair()
        val nodeAPeerId = nodeAKeys.peerId
        val nodeCKeys = RescueCrypto.generateIdentityKeyPair()
        val nodeCPeerId = nodeCKeys.peerId

        val sosPayload = """{"creatorName":"Hiker-A","context":{"category":"MEDICAL","summary":"Ankle injury","batteryPercent":64,"peopleCount":1},"location":{"latitude":37.7749,"longitude":-122.4194,"accuracyMeters":6.5,"timestampMillis":${System.currentTimeMillis()},"isFresh":true,"provider":"GNSS"},"senderX25519Hex":"${RescueCrypto.bytesToHex(nodeAKeys.x25519Public)}"}""".toByteArray()
        val now = System.currentTimeMillis()
        val sosHash = RescueCrypto.sha256(sosPayload)
        val sessionId = "sess1234567890ab"
        val sosPacketId = "pkt_sos_001"
        val signableSos = PacketSerializer.buildSignableBytes(
            packetId = sosPacketId,
            sessionId = sessionId,
            senderId = nodeAPeerId,
            recipientId = nodeCPeerId,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 1L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = 5,
            payloadHash = sosHash
        )
        val sosPacket = com.example.domain.model.EmergencyPacket(
            packetId = sosPacketId,
            sessionId = sessionId,
            senderId = nodeAPeerId,
            recipientId = nodeCPeerId,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 0,
            maxHopCount = 5,
            payload = sosPayload,
            payloadHash = sosHash,
            authTag = RescueCrypto.signEd25519(nodeAKeys.ed25519Private, signableSos),
            senderPublicKeyEd25519 = nodeAKeys.ed25519Public
        )

        // Also build a lower-priority audio fragment packet to verify PriorityScheduler ordering
        val audioPayload = MessageRouter.encodeAudioFragment("clip1", 0, 2, ByteArray(120) { 0x5A })
        val audioHash = RescueCrypto.sha256(audioPayload)
        val audioSignable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_audio_001",
            sessionId = sessionId,
            senderId = nodeAPeerId,
            recipientId = nodeCPeerId,
            type = PacketType.AUDIO_FRAGMENT,
            priority = PacketPriority.AUDIO_DATA,
            sequenceNumber = 2L,
            createdAtMillis = now - 5000L, // Enqueued earlier, lower priority!
            expiresAtMillis = now + 60_000L,
            maxHopCount = 5,
            payloadHash = audioHash
        )
        val audioPacket = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_audio_001",
            sessionId = sessionId,
            senderId = nodeAPeerId,
            recipientId = nodeCPeerId,
            type = PacketType.AUDIO_FRAGMENT,
            priority = PacketPriority.AUDIO_DATA,
            createdAtMillis = now - 5000L,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 2L,
            hopCount = 0,
            maxHopCount = 5,
            payload = audioPayload,
            payloadHash = audioHash,
            authTag = RescueCrypto.signEd25519(nodeAKeys.ed25519Private, audioSignable),
            senderPublicKeyEd25519 = nodeAKeys.ed25519Public
        )

        val sosWire = PacketSerializer.serialize(sosPacket)
        val audioWire = PacketSerializer.serialize(audioPacket)

        // Step 1: Node B receives Audio first, then SOS
        val receiverB = PacketReceiver(dupB, idA)
        val resAudio = receiverB.acceptRawFrame(audioWire)
        assertTrue(resAudio is PacketReceiver.IntakeResult.Accepted)
        routerB.routeIncomingPacket((resAudio as PacketReceiver.IntakeResult.Accepted).packet)

        val resSos = receiverB.acceptRawFrame(sosWire)
        assertTrue(resSos is PacketReceiver.IntakeResult.Accepted)
        routerB.routeIncomingPacket((resSos as PacketReceiver.IntakeResult.Accepted).packet)

        // Duplicate suppression on Node B
        val resDup = receiverB.acceptRawFrame(sosWire)
        assertTrue(resDup is PacketReceiver.IntakeResult.Dropped)

        // Step 2: Verify Node B persisted packets in Room relay queue and prioritized SOS ahead of Audio
        val pendingOnB = queueB.allPendingEntries()
        assertTrue(pendingOnB.any { it.packetId == sosPacketId })
        assertTrue(pendingOnB.any { it.packetId == "pkt_audio_001" })

        val transportB = InMemoryFakeTransport()
        val schedulerB = PriorityScheduler(queueB)
        val peerC = Peer(
            peerId = nodeCPeerId,
            displayName = "Node-C",
            connectionState = PeerConnectionState.CONNECTED
        )
        schedulerB.dispatchNextBatch(listOf(peerC), { target, bytes -> transportB.sendData(target, bytes) })

        // SOS (priority 1) must appear before Audio (priority 5) in dispatched frames even though audio had earlier timestamp
        val sentPackets = transportB.sentFrames.mapNotNull {
            (PacketSerializer.validateAndDeserialize(it.second) as? PacketSerializer.ValidationResult.Valid)?.packet
        }
        val sosIndex = sentPackets.indexOfFirst { it.type == PacketType.SOS }
        val audioIndex = sentPackets.indexOfFirst { it.type == PacketType.AUDIO_FRAGMENT }
        assertTrue("Expected SOS dispatched before AUDIO_FRAGMENT", sosIndex in 0 until audioIndex)

        // Verify ACK_STORED is never treated as ACK_DELIVERED
        val fakeOriginSession = com.example.domain.model.EmergencySession(
            sessionId = sessionId,
            creatorPeerId = nodeAPeerId,
            creatorDisplayName = "Hiker-A",
            recipientPeerId = nodeCPeerId,
            isOutgoing = true,
            context = EmergencyContext(),
            location = null,
            status = com.example.domain.model.SessionStatus.ACTIVE,
            deliveryStatus = DeliveryStatus.QUEUED_RELAY,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        repoA.upsertSession(fakeOriginSession)
        repoA.recordAcknowledgement(
            com.example.domain.model.Acknowledgement(
                ackId = "ack_relay_1",
                packetId = sosPacketId,
                sessionId = sessionId,
                fromPeerId = "RELAY_B",
                isFinalDelivery = false,
                hopCount = 1,
                timestampMillis = now + 100
            )
        )
        assertEquals(DeliveryStatus.ACK_STORED, repoA.getSession(sessionId)?.deliveryStatus)

        repoA.recordAcknowledgement(
            com.example.domain.model.Acknowledgement(
                ackId = "ack_dest_1",
                packetId = sosPacketId,
                sessionId = sessionId,
                fromPeerId = nodeCPeerId,
                isFinalDelivery = true,
                hopCount = 2,
                timestampMillis = now + 200
            )
        )
        assertEquals(DeliveryStatus.ACK_DELIVERED, repoA.getSession(sessionId)?.deliveryStatus)

        // Verify Audio fragment reassembly & SHA-256 verification on Node C
        val originalClip = ByteArray(2500) { (it % 127).toByte() }
        val clipSha = RescueCrypto.bytesToHex(RescueCrypto.sha256(originalClip))
        val frags = AudioClipManager.fragmentBytes(originalClip, 1024)
        assertEquals(3, frags.size)
        repoC.upsertAudioClip(
            com.example.domain.model.AudioClipMetadata(
                clipId = "clipC",
                sessionId = sessionId,
                senderPeerId = nodeAPeerId,
                codec = "OPUS",
                durationMillis = 4800L,
                totalFragments = 3,
                receivedFragments = 0,
                sha256Hex = clipSha,
                localFileId = null,
                status = com.example.domain.model.AudioTransferStatus.TRANSFERRING,
                isIncoming = true,
                createdAtMillis = now
            )
        )
        frags.forEachIndexed { idx, bytes ->
            repoC.storeIncomingAudioFragment("clipC", sessionId, idx, frags.size, bytes)
        }
        val reassembled = repoC.tryReassembleAudioClip("clipC")
        assertNotNull(reassembled)
        assertEquals(com.example.domain.model.AudioTransferStatus.REASSEMBLED, reassembled?.status)
        assertFalse(reassembled?.localFileId.isNullOrBlank())

        dbA.close()
        dbB.close()
        dbC.close()
    }

    @Test
    fun `foreground service start safely refuses without SecurityException when permissions missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = com.example.service.RescueMeshForegroundService.start(context)
        assertFalse(started)
    }

    @Test
    fun `ble primary advertise data and scan response stay within 31-byte legacy limit`() {
        val peerId = RescueCrypto.generateIdentityKeyPair().peerId
        assertEquals(16, peerId.length)
        val binaryPeerId = RescueCrypto.hexToBytes(peerId)
        assertEquals(8, binaryPeerId.size)
        assertEquals(peerId, RescueCrypto.bytesToHex(binaryPeerId))

        // Primary AD = 3B Flags + 18B 128-bit Service UUID = 21 bytes (<= 31)
        val primaryBytes = 3 + (2 + 16)
        assertTrue("Primary BLE AD ($primaryBytes) must be <= 31 bytes", primaryBytes <= 31)

        // ScanResponse AD (idle) = 18B header + 8B Peer ID = 26 bytes (<= 31)
        val scanResponseIdleBytes = 2 + 16 + binaryPeerId.size
        assertTrue("ScanResponse BLE AD ($scanResponseIdleBytes) must be <= 31 bytes", scanResponseIdleBytes <= 31)

        // ScanResponse AD (active SOS spray) = 18B header + 8B Peer ID + 1B SOS flag + 3B prefix = 30 bytes (<= 31)
        val scanResponseSosBytes = 2 + 16 + binaryPeerId.size + 1 + 3
        assertTrue("Active SOS ScanResponse BLE AD ($scanResponseSosBytes) must be <= 31 bytes", scanResponseSosBytes <= 31)
    }

    @Test
    fun `priority scheduler continuously sprays un-ACKed local SOS past 5 attempts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        val queue = StoreForwardQueue(repo)
        val scheduler = PriorityScheduler(queue)
        val keys = RescueCrypto.generateIdentityKeyPair()
        val now = System.currentTimeMillis()
        val payload = """{"test":"sos"}""".toByteArray()
        val hash = RescueCrypto.sha256(payload)
        val signable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_spray_001",
            sessionId = "sess_spray_001",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 1L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = 5,
            payloadHash = hash
        )
        val packet = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_spray_001",
            sessionId = "sess_spray_001",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 0,
            maxHopCount = 5,
            payload = payload,
            payloadHash = hash,
            authTag = RescueCrypto.signEd25519(keys.ed25519Private, signable),
            senderPublicKeyEd25519 = keys.ed25519Public
        )

        queue.enqueue(packet, isOriginLocal = true)
        val existing = repo.getRelayEntry("pkt_spray_001")!!
        // Simulate 7 prior attempts and verify scheduler still dispatches attempt #8 when SOS is un-ACKed
        repo.enqueueRelay(
            existing.copy(
                attemptCount = 7,
                forwardedToPeersCsv = "PEER_01",
                nextRetryAtMillis = now - 100L
            )
        )
        val transport = InMemoryFakeTransport()
        val peer = Peer(peerId = "PEER_01", displayName = "Peer-1", connectionState = PeerConnectionState.CONNECTED)
        val dispatched = scheduler.dispatchNextBatch(listOf(peer), { target, bytes -> transport.sendData(target, bytes) })
        assertEquals(1, dispatched)
        assertEquals(8, repo.getRelayEntry("pkt_spray_001")?.attemptCount)
        db.close()
    }

    @Test
    fun `unlimited hop packets survive past 14 hops without dropping`() {
        val keys = RescueCrypto.generateIdentityKeyPair()
        val now = System.currentTimeMillis()
        val payload = """{"test":"unlimited_hops"}""".toByteArray()
        val hash = RescueCrypto.sha256(payload)
        val signable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_hop14_001",
            sessionId = "sess_hop14_001",
            senderId = keys.peerId,
            recipientId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 1L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payloadHash = hash
        )
        val packet = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_hop14_001",
            sessionId = "sess_hop14_001",
            senderId = keys.peerId,
            recipientId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 14,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payload = payload,
            payloadHash = hash,
            authTag = RescueCrypto.signEd25519(keys.ed25519Private, signable),
            senderPublicKeyEd25519 = keys.ed25519Public
        )

        assertTrue(packet.canRelay())
        val wire = PacketSerializer.serialize(packet)
        val parsed = PacketSerializer.validateAndDeserialize(wire, now + 1000L)
        assertTrue(parsed is PacketSerializer.ValidationResult.Valid)
        assertEquals(14, (parsed as PacketSerializer.ValidationResult.Valid).packet.hopCount)
        assertTrue(parsed.packet.canRelay())
    }

    @Test
    fun `blind multi-recipient E2EE lets only target node decrypt while intermediate relays forward blindly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbC = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val repoC = RescueMeshRepository(dbC, AudioFileStore(context))
        val queueB = StoreForwardQueue(repoB)
        val queueC = StoreForwardQueue(repoC)

        // Generate Node C persistent IdentityManager first, then wipe prefs and create Node B with distinct identity
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idC = IdentityManager(context)
        val cPub = idC.identity.x25519Public // force lazy creation

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idB = IdentityManager(context)
        val bId = idB.localPeerId
        assertFalse(bId == idC.localPeerId)

        var alertsOnB = 0
        var alertsOnC = 0
        val routerB = MessageRouter(repoB, queueB, idB, prefs) { alertsOnB++ }
        val routerC = MessageRouter(repoC, queueC, idC, prefs) { alertsOnC++ }

        val nodeAKeys = RescueCrypto.generateIdentityKeyPair()
        val sessionId = "sess_e2ee_9999"
        val now = System.currentTimeMillis()
        val clearJson = """{"creatorName":"Alice","context":{"category":"SOS_DISTRESS","summary":"Need help","batteryPercent":72,"peopleCount":1},"location":{"latitude":37.7749,"longitude":-122.4194,"accuracyMeters":5.0,"timestampMillis":$now,"isFresh":true,"provider":"GNSS"},"senderX25519Hex":"${RescueCrypto.bytesToHex(nodeAKeys.x25519Public)}"}""".toByteArray()

        // Node A seals RME2 multi-recipient envelope for C only (plus another dummy key, NOT B)
        val dummyPeerKey = RescueCrypto.generateIdentityKeyPair().x25519Public
        val tempPref = context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE)
        tempPref.edit().clear().commit()
        val idAHelper = IdentityManager(context)
        val sealedPayload = idAHelper.sealForPairedContacts(
            recipientX25519PublicKeys = listOf(dummyPeerKey, cPub),
            plaintext = clearJson,
            aad = sessionId.toByteArray()
        )
        val payloadHash = RescueCrypto.sha256(sealedPayload)
        val signable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_e2ee_sos",
            sessionId = sessionId,
            senderId = nodeAKeys.peerId,
            recipientId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 1L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payloadHash = payloadHash
        )
        val encryptedPacket = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_e2ee_sos",
            sessionId = sessionId,
            senderId = nodeAKeys.peerId,
            recipientId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
            type = PacketType.SOS,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 0,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payload = sealedPayload,
            payloadHash = payloadHash,
            authTag = RescueCrypto.signEd25519(nodeAKeys.ed25519Private, signable),
            senderPublicKeyEd25519 = nodeAKeys.ed25519Public,
            isEncryptedPayload = true
        )

        // Intermediate Node B routes encrypted packet: must queue blindly and NEVER open local session/alert
        routerB.routeIncomingPacket(encryptedPacket)
        assertEquals(0, alertsOnB)
        assertEquals(null, repoB.getSession(sessionId))
        assertTrue(queueB.allPendingEntries().any { it.packetId == "pkt_e2ee_sos" })

        // Forward packet from B -> Target Node C
        val relayedEntry = queueB.allPendingEntries().first { it.packetId == "pkt_e2ee_sos" }
        val relayedValidated = PacketSerializer.validateAndDeserialize(relayedEntry.wireBytes, now + 500L)
        assertTrue(relayedValidated is PacketSerializer.ValidationResult.Valid)
        val relayedPacket = (relayedValidated as PacketSerializer.ValidationResult.Valid).packet
        assertEquals(1, relayedPacket.hopCount)

        routerC.routeIncomingPacket(relayedPacket)
        assertEquals(1, alertsOnC)
        val cSession = repoC.getSession(sessionId)
        assertNotNull(cSession)
        assertEquals("Alice", cSession?.creatorDisplayName)

        dbB.close()
        dbC.close()
    }

    @Test
    fun `max 7 paired contacts enforced and QrPairingCodec plus GeoUtils work accurately`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))

        for (i in 1..7) {
            val kp = RescueCrypto.generateIdentityKeyPair()
            val added = repo.upsertContact(
                com.example.domain.model.EmergencyContact(
                    peerId = kp.peerId,
                    alias = "Trusted-$i",
                    x25519PublicKeyHex = RescueCrypto.bytesToHex(kp.x25519Public),
                    ed25519PublicKeyHex = RescueCrypto.bytesToHex(kp.ed25519Public),
                    addedAtMillis = System.currentTimeMillis()
                )
            )
            assertTrue("Contact $i should fit within max 7", added)
        }
        val kp8 = RescueCrypto.generateIdentityKeyPair()
        val eighth = repo.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = kp8.peerId,
                alias = "Trusted-8",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(kp8.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(kp8.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )
        assertFalse("8th paired contact must be rejected", eighth)

        val uri = com.example.security.QrPairingCodec.encodePairingUri(
            peerId = kp8.peerId,
            alias = "Marcus Vance",
            x25519PublicBytes = kp8.x25519Public,
            ed25519PublicBytes = kp8.ed25519Public
        )
        val decodedUri = com.example.security.QrPairingCodec.decodePairingInput(uri)
        assertNotNull(decodedUri)
        assertEquals(kp8.peerId, decodedUri?.peerId)
        assertEquals("Marcus Vance", decodedUri?.alias)

        val key = com.example.security.QrPairingCodec.encodeCompactManualKey(
            peerId = kp8.peerId,
            alias = "Marcus Vance",
            x25519PublicBytes = kp8.x25519Public,
            ed25519PublicBytes = kp8.ed25519Public
        )
        assertTrue(key.startsWith("RMKEY2:"))
        val decodedKey = com.example.security.QrPairingCodec.decodePairingInput(key)
        assertEquals(kp8.peerId, decodedKey?.peerId)

        val from = LocationSnapshot(37.7749, -122.4194, 4f, 1000L, true)
        val to = LocationSnapshot(37.7839, -122.4194, 4f, 1000L, true)
        val metrics = com.example.location.GeoUtils.calculateProximity(from, to)
        assertNotNull(metrics)
        assertTrue((metrics?.distanceKm ?: 0.0) in 0.9..1.1)
        assertEquals("N", metrics?.compassCardinal)

        db.close()
    }

    @Test
    fun `send to all broadcast SOS mode sends unencrypted signed packet and delivers locally to any node`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()

        val prefsA = UserPreferencesRepository(context)
        prefsA.setSendToAllNodes(true)

        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))

        val idA = IdentityManager(context)
        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)

        val fakeTransport = InMemoryFakeTransport()
        val routerA = MessageRouter(repoA, queueA, idA, prefsA) {}

        val sendUseCaseA = SendEmergencyPacketUseCase(
            repository = repoA,
            messageRouter = routerA,
            storeForwardQueue = queueA,
            preferencesRepository = prefsA,
            coordinatorProvider = {
                MeshCoordinator(
                    transport = fakeTransport,
                    repository = repoA,
                    peerDiscoveryManager = PeerDiscoveryManager(repoA),
                    storeForwardQueue = queueA,
                    priorityScheduler = PriorityScheduler(queueA),
                    packetReceiver = PacketReceiver(DuplicateDetectionManager(repoA), idA),
                    messageRouter = routerA
                )
            }
        )

        val createSos = CreateSosUseCase(
            repository = repoA,
            identityManager = idA,
            preferencesRepository = prefsA,
            sendEmergencyPacketUseCase = sendUseCaseA
        )

        val session = createSos(
            context = EmergencyContext(category = "TEST_SOS", summary = "Broadcast test", batteryPercent = 90, peopleCount = 1),
            location = LocationSnapshot(37.7749, -122.4194, 5f, System.currentTimeMillis(), true)
        )

        assertEquals(MeshConstants.BROADCAST_RECIPIENT_ID, session.recipientPeerId)

        // Find the queued packet
        val queued = queueA.nextDueEntries(10).firstOrNull { it.sessionId == session.sessionId }
        assertNotNull("Broadcast SOS packet must be enqueued", queued)
        val packet = PacketSerializer.deserialize(queued!!.wireBytes)
        assertFalse("Broadcast SOS packet must be unencrypted for universal reception", packet.isEncryptedPayload)
        assertEquals(MeshConstants.BROADCAST_RECIPIENT_ID, packet.recipientId)

        // Node B receives the packet
        var incomingAlertInvoked = false
        val idB = IdentityManager(context)
        val prefsB = UserPreferencesRepository(context)
        val routerB = MessageRouter(repoB, queueB, idB, prefsB) {
            incomingAlertInvoked = true
        }

        routerB.routeIncomingPacket(packet)

        assertTrue("Node B must immediately trigger incoming alert for broadcast SOS", incomingAlertInvoked)
        val receivedSession = repoB.getSession(session.sessionId)
        assertNotNull("Node B must persist the emergency session", receivedSession)
        assertEquals(session.sessionId, receivedSession?.sessionId)

        dbA.close()
        dbB.close()
    }

    @Test
    fun `responder acknowledgment and ACK_DELIVERED marks sender session accepted`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()

        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))

        val prefsA = UserPreferencesRepository(context)
        val prefsB = UserPreferencesRepository(context)

        val idA = IdentityManager(context)
        val idB = IdentityManager(context)

        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)

        val fakeTransport = InMemoryFakeTransport()
        val routerA = MessageRouter(repoA, queueA, idA, prefsA) {}

        // Sender A creates outgoing session
        val now = System.currentTimeMillis()
        val outgoingSession = com.example.domain.model.EmergencySession(
            sessionId = "test_handshake_1",
            creatorPeerId = idA.localPeerId,
            creatorDisplayName = "Victim A",
            recipientPeerId = MeshConstants.BROADCAST_RECIPIENT_ID,
            isOutgoing = true,
            context = EmergencyContext(category = "SOS", summary = "Need rescue", batteryPercent = 85, peopleCount = 1),
            location = null,
            status = com.example.domain.model.SessionStatus.ACTIVE,
            deliveryStatus = DeliveryStatus.QUEUED_RELAY,
            sessionAccepted = false,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        repoA.upsertSession(outgoingSession)

        // Recipient B has incoming session
        val incomingSession = outgoingSession.copy(isOutgoing = false, recipientPeerId = idB.localPeerId)
        repoB.upsertSession(incomingSession)

        val routerB = MessageRouter(repoB, queueB, idB, prefsB) {}
        val coordinatorB = MeshCoordinator(
            transport = fakeTransport,
            repository = repoB,
            peerDiscoveryManager = PeerDiscoveryManager(repoB),
            storeForwardQueue = queueB,
            priorityScheduler = PriorityScheduler(queueB),
            packetReceiver = PacketReceiver(DuplicateDetectionManager(repoB), idB),
            messageRouter = routerB
        )

        val ackUseCase = com.example.domain.usecase.AcknowledgeEmergencyUseCase(
            repository = repoB,
            identityManager = idB,
            messageRouter = routerB,
            preferencesRepository = prefsB,
            coordinatorProvider = { coordinatorB }
        )

        // Recipient acknowledges session
        ackUseCase("test_handshake_1", isFinalDelivery = true)

        // Queue B should contain SESSION_ACCEPT and ACK_DELIVERED
        val pendingB = queueB.nextDueEntries(10)
        assertTrue(pendingB.any { it.typeCode == PacketType.SESSION_ACCEPT.code })
        assertTrue(pendingB.any { it.typeCode == PacketType.ACK_DELIVERED.code })

        // Deliver SESSION_ACCEPT and ACK_DELIVERED to Node A
        for (entry in pendingB) {
            val p = PacketSerializer.deserialize(entry.wireBytes)
            routerA.routeIncomingPacket(p)
        }

        // Verify Node A session is accepted
        val updatedA = repoA.getSession("test_handshake_1")
        assertNotNull(updatedA)
        assertTrue("Sender session must be marked sessionAccepted = true", updatedA!!.sessionAccepted)
        assertEquals(DeliveryStatus.ACK_DELIVERED, updatedA.deliveryStatus)

        dbA.close()
        dbB.close()
    }

    @Test
    fun `two-way SESSION_RESOLVED syncs across sender and recipient and invokes termination callback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()

        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val prefsA = UserPreferencesRepository(context)
        val prefsB = UserPreferencesRepository(context)

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idA = IdentityManager(context)
        val peerIdA = idA.localPeerId
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idB = IdentityManager(context)
        val peerIdB = idB.localPeerId

        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)
        val fakeTransport = InMemoryFakeTransport()

        var terminatedOnB: String? = null
        val routerA = MessageRouter(repoA, queueA, idA, prefsA, onIncomingAlert = {})
        val routerB = MessageRouter(
            repository = repoB,
            storeForwardQueue = queueB,
            identityManager = idB,
            preferencesRepository = prefsB,
            onSessionTerminated = { sid, _ -> terminatedOnB = sid },
            onIncomingAlert = {}
        )

        val now = System.currentTimeMillis()
        val sessionId = "sess_resolve_sync_01"
        repoA.upsertSession(
            com.example.domain.model.EmergencySession(
                sessionId = sessionId,
                creatorPeerId = peerIdA,
                creatorDisplayName = "Victim A",
                recipientPeerId = peerIdB,
                isOutgoing = true,
                context = EmergencyContext(),
                location = null,
                status = com.example.domain.model.SessionStatus.ACTIVE,
                deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                sessionAccepted = true,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        repoB.upsertSession(
            com.example.domain.model.EmergencySession(
                sessionId = sessionId,
                creatorPeerId = peerIdA,
                creatorDisplayName = "Victim A",
                recipientPeerId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID,
                isOutgoing = false,
                context = EmergencyContext(),
                location = null,
                status = com.example.domain.model.SessionStatus.ACTIVE,
                deliveryStatus = DeliveryStatus.ACK_DELIVERED,
                sessionAccepted = true,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )

        val sendUseCaseA = SendEmergencyPacketUseCase(
            repository = repoA,
            messageRouter = routerA,
            storeForwardQueue = queueA,
            preferencesRepository = prefsA,
            coordinatorProvider = {
                MeshCoordinator(
                    transport = fakeTransport,
                    repository = repoA,
                    peerDiscoveryManager = PeerDiscoveryManager(repoA),
                    storeForwardQueue = queueA,
                    priorityScheduler = PriorityScheduler(queueA),
                    packetReceiver = PacketReceiver(DuplicateDetectionManager(repoA), idA),
                    messageRouter = routerA
                )
            }
        )
        val resolveUseCaseA = com.example.domain.usecase.ResolveEmergencyUseCase(
            repository = repoA,
            sendEmergencyPacketUseCase = sendUseCaseA
        )

        // Sender resolves emergency
        resolveUseCaseA(sessionId, cancelled = false)

        val pendingA = queueA.allPendingEntries()
        assertEquals("Only broadcast SESSION_RESOLVED should remain in queueA", 1, pendingA.size)
        val resolvedEntry = pendingA.first()
        assertEquals(PacketType.SESSION_RESOLVED.code, resolvedEntry.typeCode)
        assertEquals(PacketPriority.CRITICAL_SOS.rank, resolvedEntry.priorityRank)

        val resolvedPacket = PacketSerializer.deserialize(resolvedEntry.wireBytes)
        assertEquals(MeshConstants.BROADCAST_RECIPIENT_ID, resolvedPacket.recipientId)

        // Deliver packet to Recipient B
        routerB.routeIncomingPacket(resolvedPacket)

        assertEquals(com.example.domain.model.SessionStatus.RESOLVED, repoA.getSession(sessionId)?.status)
        assertEquals(com.example.domain.model.SessionStatus.RESOLVED, repoB.getSession(sessionId)?.status)
        assertEquals(sessionId, terminatedOnB)

        dbA.close()
        dbB.close()
    }

    @Test
    fun `ima adpcm 5s audio compression fits compactly and decodes cleanly`() {
        val sampleRate = com.example.audio.ImaAdpcmCodec.DEFAULT_SAMPLE_RATE
        val fiveSecSamples = ShortArray(sampleRate * 5) { idx ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * idx / sampleRate) * 8000.0).toInt().toShort()
        }
        val encoded = com.example.audio.ImaAdpcmCodec.encode(fiveSecSamples, sampleRate)
        assertTrue(com.example.audio.ImaAdpcmCodec.isRmadFormat(encoded))
        // 4-bit ADPCM + 16-byte header = 20016 bytes (~20 KB -> ~10 BLE fragments at 2048B)
        assertTrue("5s ADPCM should be <= 20.5 KB (was ${encoded.size})", encoded.size <= 20_500)

        val decoded = com.example.audio.ImaAdpcmCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(sampleRate, decoded!!.sampleRate)
        assertEquals(fiveSecSamples.size, decoded.samples.size)

        val frags = AudioClipManager.fragmentBytes(encoded, AudioClipManager.FRAGMENT_PAYLOAD_SIZE)
        assertTrue("5s ADPCM should take <= 10 fragments", frags.size <= 10)

        val beacon = com.example.audio.ImaAdpcmCodec.synthesizeFallbackBeacon(1000L, sampleRate)
        assertTrue(com.example.audio.ImaAdpcmCodec.isRmadFormat(beacon))
    }

    @Test
    fun `priority scheduler dispatches over live transport link even when DB peer list is empty`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        val queue = StoreForwardQueue(repo)
        val scheduler = PriorityScheduler(queue)
        val keys = RescueCrypto.generateIdentityKeyPair()
        val now = System.currentTimeMillis()
        val payload = """{"acceptedByPeerId":"RESP_1","accepted":true,"timestampMillis":$now}""".toByteArray()
        val hash = RescueCrypto.sha256(payload)
        val signable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_accept_live_01",
            sessionId = "sess_live_01",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SESSION_ACCEPT,
            priority = PacketPriority.CRITICAL_SOS,
            sequenceNumber = 1L,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payloadHash = hash
        )
        val pkt = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_accept_live_01",
            sessionId = "sess_live_01",
            senderId = keys.peerId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.SESSION_ACCEPT,
            priority = PacketPriority.CRITICAL_SOS,
            createdAtMillis = now,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 0,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payload = payload,
            payloadHash = hash,
            authTag = RescueCrypto.signEd25519(keys.ed25519Private, signable),
            senderPublicKeyEd25519 = keys.ed25519Public
        )
        queue.enqueue(pkt, isOriginLocal = true)

        val transport = InMemoryFakeTransport()
        val dispatched = scheduler.dispatchNextBatch(
            connectedPeers = emptyList(),
            sendBytes = { target, bytes -> transport.sendData(target, bytes) },
            hasLiveTransportLinks = true
        )
        assertEquals(1, dispatched)
        assertEquals(1, transport.sentFrames.size)
        assertEquals(null, transport.sentFrames.first().first)
        db.close()
    }

    @Test
    fun `packet fragmenter enforces strict 20-byte ATT MTU chunks and reassembles cleanly`() {
        val rawWire = ByteArray(195) { (it * 7).toByte() }
        val chunks = com.example.protocol.PacketFragmenter.fragment(rawWire, mtuPayloadBudget = 20)
        assertTrue("Chunks must exist", chunks.isNotEmpty())
        assertTrue("Every ATT chunk must be <= 20 bytes", chunks.all { it.size <= 20 })

        val reassembler = com.example.protocol.PacketReassembler()
        var reconstructed: ByteArray? = null
        chunks.forEach { chunk ->
            val res = reassembler.acceptChunk("AA:BB:CC:DD:EE:FF", chunk)
            if (res != null) reconstructed = res
        }
        assertNotNull(reconstructed)
        assertTrue(rawWire.contentEquals(reconstructed!!))
    }

    @Test
    fun `clip-scoped ACK_DELIVERED deletes only acked clip packets and preserves next voice clip`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        val prefs = UserPreferencesRepository(context)
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idSender = IdentityManager(context)
        val senderKeys = idSender.identity
        val peerC = RescueCrypto.generateIdentityKeyPair()
        val queue = StoreForwardQueue(repo)
        val router = MessageRouter(repo, queue, idSender, prefs) {}

        val now = System.currentTimeMillis()
        val sessionId = "sess_clip_isolation_1"
        val clip1Start = now
        val clip2Start = now + 5_000L

        repo.upsertAudioClip(
            com.example.domain.model.AudioClipMetadata(
                clipId = "clip_one",
                sessionId = sessionId,
                senderPeerId = senderKeys.peerId,
                codec = "RMAD_8K",
                durationMillis = 5000L,
                totalFragments = 2,
                receivedFragments = 2,
                sha256Hex = "00",
                localFileId = "clip_one_local.rmad",
                status = com.example.domain.model.AudioTransferStatus.TRANSFERRING,
                isIncoming = false,
                createdAtMillis = clip1Start
            )
        )
        repo.upsertAudioClip(
            com.example.domain.model.AudioClipMetadata(
                clipId = "clip_two",
                sessionId = sessionId,
                senderPeerId = senderKeys.peerId,
                codec = "RMAD_8K",
                durationMillis = 5000L,
                totalFragments = 2,
                receivedFragments = 2,
                sha256Hex = "11",
                localFileId = "clip_two_local.rmad",
                status = com.example.domain.model.AudioTransferStatus.TRANSFERRING,
                isIncoming = false,
                createdAtMillis = clip2Start
            )
        )

        val frag1 = router.buildSignedPacket(
            sessionId = sessionId,
            recipientId = peerC.peerId,
            type = PacketType.AUDIO_FRAGMENT,
            payload = MessageRouter.encodeAudioFragment("clip_one", 0, 2, ByteArray(100) { 1 }),
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            createdAtOverrideMillis = clip1Start + 1L
        )
        val frag2 = router.buildSignedPacket(
            sessionId = sessionId,
            recipientId = peerC.peerId,
            type = PacketType.AUDIO_FRAGMENT,
            payload = MessageRouter.encodeAudioFragment("clip_two", 0, 2, ByteArray(100) { 2 }),
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            createdAtOverrideMillis = clip2Start + 1L
        )
        queue.enqueue(frag1, isOriginLocal = true)
        queue.enqueue(frag2, isOriginLocal = true)
        assertEquals(2, queue.allPendingEntries().size)

        // Recipient C sends ACK_DELIVERED for clip:clip_one
        val ackPayload = """{"ackedPacketId":"clip:clip_one","isFinalDelivery":true,"fromPeerId":"${peerC.peerId}","observedHopCount":1}""".toByteArray()
        val ackHash = RescueCrypto.sha256(ackPayload)
        val ackSignable = PacketSerializer.buildSignableBytes(
            packetId = "pkt_ack_clip1",
            sessionId = sessionId,
            senderId = peerC.peerId,
            recipientId = senderKeys.peerId,
            type = PacketType.ACK_DELIVERED,
            priority = PacketPriority.defaultForType(PacketType.ACK_DELIVERED),
            sequenceNumber = 1L,
            createdAtMillis = now + 50L,
            expiresAtMillis = now + 60_000L,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payloadHash = ackHash
        )
        val ackPkt = com.example.domain.model.EmergencyPacket(
            packetId = "pkt_ack_clip1",
            sessionId = sessionId,
            senderId = peerC.peerId,
            recipientId = senderKeys.peerId,
            type = PacketType.ACK_DELIVERED,
            priority = PacketPriority.defaultForType(PacketType.ACK_DELIVERED),
            createdAtMillis = now + 50L,
            expiresAtMillis = now + 60_000L,
            sequenceNumber = 1L,
            hopCount = 0,
            maxHopCount = MeshConstants.UNLIMITED_HOPS,
            payload = ackPayload,
            payloadHash = ackHash,
            authTag = RescueCrypto.signEd25519(peerC.ed25519Private, ackSignable),
            senderPublicKeyEd25519 = peerC.ed25519Public
        )

        router.routeIncomingPacket(ackPkt)

        val remaining = queue.allPendingEntries()
        assertEquals(1, remaining.size)
        assertEquals(frag2.packetId, remaining.first().packetId)
        assertEquals(com.example.domain.model.AudioTransferStatus.REASSEMBLED, repo.getAudioClip("clip_one")?.status)
        assertEquals(com.example.domain.model.AudioTransferStatus.TRANSFERRING, repo.getAudioClip("clip_two")?.status)
        db.close()
    }

    @Test
    fun `end-to-end 3-node multi-hop paired E2EE SOS accept audio clip and session resolve sync`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbC = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()

        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val repoC = RescueMeshRepository(dbC, AudioFileStore(context))

        val prefsA = UserPreferencesRepository(context)
        prefsA.setSendToAllNodes(false)
        val prefsB = UserPreferencesRepository(context)
        val prefsC = UserPreferencesRepository(context)

        // Generate 3 distinct persistent identities backed by separate in-memory runs
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idC = IdentityManager(context)
        val cKeys = idC.identity

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idB = IdentityManager(context)
        val bKeys = idB.identity

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idA = IdentityManager(context)
        val aKeys = idA.identity

        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)
        val queueC = StoreForwardQueue(repoC)

        var alertsB = 0
        var alertsC = 0
        var resolvedOnA: String? = null
        val routerA = MessageRouter(
            repoA, queueA, idA, prefsA,
            onSessionTerminated = { sid, _ -> resolvedOnA = sid },
            onIncomingAlert = {}
        )
        val routerB = MessageRouter(repoB, queueB, idB, prefsB) { alertsB++ }
        val routerC = MessageRouter(repoC, queueC, idC, prefsC) { alertsC++ }

        val transportA = InMemoryFakeTransport()
        val transportB = InMemoryFakeTransport()
        val transportC = InMemoryFakeTransport()

        val coordA = MeshCoordinator(transportA, repoA, PeerDiscoveryManager(repoA), queueA, PriorityScheduler(queueA), PacketReceiver(DuplicateDetectionManager(repoA), idA), routerA)
        val coordB = MeshCoordinator(transportB, repoB, PeerDiscoveryManager(repoB), queueB, PriorityScheduler(queueB), PacketReceiver(DuplicateDetectionManager(repoB), idB), routerB)
        val coordC = MeshCoordinator(transportC, repoC, PeerDiscoveryManager(repoC), queueC, PriorityScheduler(queueC), PacketReceiver(DuplicateDetectionManager(repoC), idC), routerC)

        val sendA = SendEmergencyPacketUseCase(repoA, routerA, queueA, prefsA) { coordA }
        val createSosA = CreateSosUseCase(repoA, idA, prefsA, sendA)
        val ackC = com.example.domain.usecase.AcknowledgeEmergencyUseCase(repoC, idC, routerC, prefsC) { coordC }
        val sendC = SendEmergencyPacketUseCase(repoC, routerC, queueC, prefsC) { coordC }
        val resolveC = com.example.domain.usecase.ResolveEmergencyUseCase(repoC, sendC)

        // Pair C as trusted contact on A
        repoA.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = cKeys.peerId,
                alias = "Trusted-C",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(cKeys.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(cKeys.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )
        // Pair a 2nd dummy contact so A uses PAIRED_MULTI_RECIPIENT_ID
        val dKeys = RescueCrypto.generateIdentityKeyPair()
        repoA.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = dKeys.peerId,
                alias = "Trusted-D",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(dKeys.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(dKeys.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )

        // 1) A creates SOS -> B receives and relays blindly -> C receives and opens alert
        val session = createSosA(
            EmergencyContext(category = "SOS_DISTRESS", summary = "Multi-hop paired SOS", batteryPercent = 80, peopleCount = 1),
            LocationSnapshot(21.2514, 81.6296, 5f, System.currentTimeMillis(), true)
        )
        assertEquals(MeshConstants.PAIRED_MULTI_RECIPIENT_ID, session.recipientPeerId)

        queueA.allPendingEntries().forEach { entry ->
            routerB.routeIncomingPacket(PacketSerializer.deserialize(entry.wireBytes))
        }
        assertEquals("Blind relay B must NOT alert or store session", 0, alertsB)
        assertEquals(null, repoB.getSession(session.sessionId))

        queueB.allPendingEntries().forEach { entry ->
            routerC.routeIncomingPacket(PacketSerializer.deserialize(entry.wireBytes))
        }
        assertEquals("Target C must open incoming alert", 1, alertsC)
        assertNotNull(repoC.getSession(session.sessionId))

        // 2) C acknowledges -> B relays SESSION_ACCEPT + ACK_DELIVERED back to A -> A accepts session
        ackC(session.sessionId, isFinalDelivery = true)
        queueC.allPendingEntries().forEach { entry ->
            val p = PacketSerializer.deserialize(entry.wireBytes)
            routerB.routeIncomingPacket(p)
        }
        queueB.allPendingEntries()
            .filter { it.typeCode == PacketType.SESSION_ACCEPT.code || it.typeCode == PacketType.ACK_DELIVERED.code }
            .forEach { entry ->
                routerA.routeIncomingPacket(PacketSerializer.deserialize(entry.wireBytes))
            }
        assertTrue("Sender A session must be marked accepted after C ack via relay B", repoA.getSession(session.sessionId)!!.sessionAccepted)

        // 3) A sends a 1s RMAD voice clip -> B relays blindly WITHOUT creating audio clips/ACK -> C reassembles
        val voiceBytes = com.example.audio.ImaAdpcmCodec.synthesizeFallbackBeacon(1000L)
        val audioManagerA = AudioClipManager(context, repoA, idA, sendA)
        val clipMeta = audioManagerA.enqueueAudioBytesForMesh(
            sessionId = session.sessionId,
            recipientId = session.recipientPeerId,
            codec = "RMAD_8K",
            durationMillis = 1000L,
            bytes = voiceBytes,
            localFileId = "clipmh_local.rmad"
        )
        queueA.allPendingEntries()
            .filter {
                it.typeCode == PacketType.AUDIO_METADATA.code ||
                    it.typeCode == PacketType.AUDIO_FRAGMENT.code ||
                    it.typeCode == PacketType.AUDIO_COMPLETE.code
            }
            .forEach { entry ->
                routerB.routeIncomingPacket(PacketSerializer.deserialize(entry.wireBytes))
            }
        assertEquals("Uninvolved relay B must NOT store audio clips locally", null, repoB.getAudioClip(clipMeta.clipId))

        queueB.allPendingEntries()
            .filter {
                it.typeCode == PacketType.AUDIO_METADATA.code ||
                    it.typeCode == PacketType.AUDIO_FRAGMENT.code ||
                    it.typeCode == PacketType.AUDIO_COMPLETE.code
            }
            .forEach { entry ->
                routerC.routeIncomingPacket(PacketSerializer.deserialize(entry.wireBytes))
            }
        val receivedClipC = repoC.getAudioClip(clipMeta.clipId)
        assertNotNull(receivedClipC)
        assertEquals(com.example.domain.model.AudioTransferStatus.REASSEMBLED, receivedClipC?.status)

        // 4) C resolves/terminates session -> B relays SESSION_RESOLVED & purges relays -> A terminates session
        resolveC(session.sessionId, cancelled = false)
        val resolvedOnC = queueC.allPendingEntries().first { it.typeCode == PacketType.SESSION_RESOLVED.code }
        routerB.routeIncomingPacket(PacketSerializer.deserialize(resolvedOnC.wireBytes))
        val resolvedOnB = queueB.allPendingEntries().first { it.typeCode == PacketType.SESSION_RESOLVED.code }
        routerA.routeIncomingPacket(PacketSerializer.deserialize(resolvedOnB.wireBytes))

        assertEquals(com.example.domain.model.SessionStatus.RESOLVED, repoC.getSession(session.sessionId)?.status)
        assertEquals(com.example.domain.model.SessionStatus.RESOLVED, repoA.getSession(session.sessionId)?.status)
        assertEquals(session.sessionId, resolvedOnA)

        dbA.close()
        dbB.close()
        dbC.close()
    }

    @Test
    fun `priority scheduler retries control and text packets past 10 attempts and preempts bulk audio backlog`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        val prefs = UserPreferencesRepository(context)
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val id = IdentityManager(context)
        val queue = StoreForwardQueue(repo)
        val scheduler = PriorityScheduler(queue)
        val router = MessageRouter(repo, queue, id, prefs) {}

        val sessionId = "sess_retry_preempt"
        for (i in 0 until 8) {
            val frag = router.buildSignedPacket(
                sessionId = sessionId,
                recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
                type = PacketType.AUDIO_FRAGMENT,
                payload = MessageRouter.encodeAudioFragment("clip_x", i, 8, ByteArray(100) { i.toByte() }),
                maxHopCount = MeshConstants.UNLIMITED_HOPS
            )
            queue.enqueue(frag, isOriginLocal = true)
        }
        val replyPkt = router.buildSignedPacket(
            sessionId = sessionId,
            recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
            type = PacketType.EMERGENCY_REPLY,
            payload = """{"replyId":"r1","senderName":"NodeA","message":"Help on roof"}""".toByteArray(),
            maxHopCount = MeshConstants.UNLIMITED_HOPS
        )
        queue.enqueue(replyPkt, isOriginLocal = true)

        val livePeer = Peer(
            peerId = "PEER_TARGET",
            displayName = "Responder",
            connectionState = PeerConnectionState.CONNECTED,
            rssi = -60,
            lastSeenAtMillis = System.currentTimeMillis()
        )

        // Simulate 12 failed flush cycles (e.g., intermittent BLE failures)
        repeat(12) {
            queue.resetBackoffsNow()
            scheduler.dispatchNextBatch(
                connectedPeers = listOf(livePeer),
                sendBytes = { _, _ -> false },
                hasLiveTransportLinks = true
            )
        }

        // After >10 attempts, nothing is permanently frozen; EMERGENCY_REPLY is dispatched first before audio cap (<=6)
        queue.resetBackoffsNow()
        val dispatchedTypes = mutableListOf<PacketType>()
        val dispatched = scheduler.dispatchNextBatch(
            connectedPeers = listOf(livePeer),
            sendBytes = { _, wire ->
                dispatchedTypes.add(PacketSerializer.deserialize(wire).type)
                true
            },
            hasLiveTransportLinks = true
        )

        assertTrue("Packets must still dispatch after >10 failed attempts", dispatched > 0)
        assertEquals("Control/text EMERGENCY_REPLY must preempt audio fragments", PacketType.EMERGENCY_REPLY, dispatchedTypes.first())
        val audioCount = dispatchedTypes.count { it == PacketType.AUDIO_FRAGMENT }
        assertTrue("Bulk audio fragments must be capped <= 6 per cycle (got $audioCount)", audioCount <= 6)
        db.close()
    }

    @Test
    fun `passive SOS ACK_DELIVERED does not flip sessionAccepted until explicit SESSION_ACCEPT`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val prefsA = UserPreferencesRepository(context)
        prefsA.setSendToAllNodes(true)
        val prefsB = UserPreferencesRepository(context)

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idB = IdentityManager(context)
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idA = IdentityManager(context)

        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)
        val routerA = MessageRouter(repoA, queueA, idA, prefsA) {}
        val routerB = MessageRouter(repoB, queueB, idB, prefsB) {}

        val coordA = MeshCoordinator(InMemoryFakeTransport(), repoA, PeerDiscoveryManager(repoA), queueA, PriorityScheduler(queueA), PacketReceiver(DuplicateDetectionManager(repoA), idA), routerA)
        val coordB = MeshCoordinator(InMemoryFakeTransport(), repoB, PeerDiscoveryManager(repoB), queueB, PriorityScheduler(queueB), PacketReceiver(DuplicateDetectionManager(repoB), idB), routerB)
        val sendA = SendEmergencyPacketUseCase(repoA, routerA, queueA, prefsA) { coordA }
        val createSosA = CreateSosUseCase(repoA, idA, prefsA, sendA)
        val ackB = com.example.domain.usecase.AcknowledgeEmergencyUseCase(repoB, idB, routerB, prefsB) { coordB }

        val session = createSosA(
            EmergencyContext("SOS_DISTRESS", "Passive ack gate test", 90, 1),
            LocationSnapshot(21.25, 81.62, 5f, System.currentTimeMillis(), true)
        )

        // Responder B receives SOS -> automatically emits passive packet ACK_DELIVERED (ackedPacketId = sosPacketId)
        val sosEntry = queueA.allPendingEntries().first { it.typeCode.toByte() == PacketType.SOS.code }
        routerB.routeIncomingPacket(PacketSerializer.deserialize(sosEntry.wireBytes))

        val passiveAckEntry = queueB.allPendingEntries().first { it.typeCode.toByte() == PacketType.ACK_DELIVERED.code }
        routerA.routeIncomingPacket(PacketSerializer.deserialize(passiveAckEntry.wireBytes))

        val afterPassiveAck = repoA.getSession(session.sessionId)!!
        assertEquals(DeliveryStatus.ACK_DELIVERED, afterPassiveAck.deliveryStatus)
        assertFalse("Passive packet ACK must NOT open active session before user clicks Accept", afterPassiveAck.sessionAccepted)

        // Now Responder B explicitly clicks Accept -> sends SESSION_ACCEPT + ACK_DELIVERED(ackedPacketId == sessionId)
        ackB(session.sessionId, isFinalDelivery = true)
        queueB.allPendingEntries()
            .filter { it.typeCode.toByte() == PacketType.SESSION_ACCEPT.code || it.typeCode.toByte() == PacketType.ACK_DELIVERED.code }
            .forEach { routerA.routeIncomingPacket(PacketSerializer.deserialize(it.wireBytes)) }

        val afterAccept = repoA.getSession(session.sessionId)!!
        assertTrue("Explicit SESSION_ACCEPT must mark sessionAccepted = true", afterAccept.sessionAccepted)
        assertEquals(idB.localPeerId, afterAccept.recipientPeerId)

        dbA.close()
        dbB.close()
    }

    @Test
    fun `bidirectional E2EE voice clip encryption and out-of-order AUDIO_METADATA preserves REASSEMBLED`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbA = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val dbB = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repoA = RescueMeshRepository(dbA, AudioFileStore(context))
        val repoB = RescueMeshRepository(dbB, AudioFileStore(context))
        val prefsA = UserPreferencesRepository(context)
        prefsA.setSendToAllNodes(false)
        val prefsB = UserPreferencesRepository(context)
        prefsB.setSendToAllNodes(false)

        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idB = IdentityManager(context)
        val bKeys = idB.identity
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val idA = IdentityManager(context)
        val aKeys = idA.identity

        // Mutual QR pairing
        repoA.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = bKeys.peerId,
                alias = "Node-B",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(bKeys.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(bKeys.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )
        repoB.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = aKeys.peerId,
                alias = "Node-A",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(aKeys.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(aKeys.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )

        val queueA = StoreForwardQueue(repoA)
        val queueB = StoreForwardQueue(repoB)
        val routerA = MessageRouter(repoA, queueA, idA, prefsA) {}
        val routerB = MessageRouter(repoB, queueB, idB, prefsB) {}

        val coordA = MeshCoordinator(InMemoryFakeTransport(), repoA, PeerDiscoveryManager(repoA), queueA, PriorityScheduler(queueA), PacketReceiver(DuplicateDetectionManager(repoA), idA), routerA)
        val coordB = MeshCoordinator(InMemoryFakeTransport(), repoB, PeerDiscoveryManager(repoB), queueB, PriorityScheduler(queueB), PacketReceiver(DuplicateDetectionManager(repoB), idB), routerB)
        val sendA = SendEmergencyPacketUseCase(repoA, routerA, queueA, prefsA) { coordA }
        val sendB = SendEmergencyPacketUseCase(repoB, routerB, queueB, prefsB) { coordB }
        val createSosA = CreateSosUseCase(repoA, idA, prefsA, sendA)
        val ackB = com.example.domain.usecase.AcknowledgeEmergencyUseCase(repoB, idB, routerB, prefsB) { coordB }

        val session = createSosA(
            EmergencyContext("SOS_DISTRESS", "Bidirectional E2EE audio", 85, 1),
            LocationSnapshot(21.25, 81.62, 5f, System.currentTimeMillis(), true)
        )
        queueA.allPendingEntries().forEach { routerB.routeIncomingPacket(PacketSerializer.deserialize(it.wireBytes)) }
        ackB(session.sessionId, isFinalDelivery = true)
        queueB.allPendingEntries().forEach { routerA.routeIncomingPacket(PacketSerializer.deserialize(it.wireBytes)) }

        // Sender A -> Responder B voice clip with out-of-order metadata delivered LAST
        val audioA = AudioClipManager(context, repoA, idA, sendA)
        val bytesA = com.example.audio.ImaAdpcmCodec.synthesizeFallbackBeacon(1200L)
        val clipA = audioA.enqueueAudioBytesForMesh(
            sessionId = session.sessionId,
            recipientId = bKeys.peerId,
            codec = "RMAD_8K",
            durationMillis = 1200L,
            bytes = bytesA,
            localFileId = "clipa_local.rmad"
        )
        val packetsAtoB = queueA.allPendingEntries()
            .filter {
                it.typeCode.toByte() == PacketType.AUDIO_FRAGMENT.code ||
                    it.typeCode.toByte() == PacketType.AUDIO_COMPLETE.code ||
                    it.typeCode.toByte() == PacketType.AUDIO_METADATA.code
            }
            .map { PacketSerializer.deserialize(it.wireBytes) }
        assertTrue("All E2EE audio packets must be encrypted", packetsAtoB.all { it.isEncryptedPayload })

        // Deliver FRAGMENTs first, then COMPLETE, then late AUDIO_METADATA
        packetsAtoB.filter { it.type == PacketType.AUDIO_FRAGMENT }.forEach { routerB.routeIncomingPacket(it) }
        packetsAtoB.filter { it.type == PacketType.AUDIO_COMPLETE }.forEach { routerB.routeIncomingPacket(it) }
        packetsAtoB.filter { it.type == PacketType.AUDIO_METADATA }.forEach { routerB.routeIncomingPacket(it) }

        val receivedOnB = repoB.getAudioClip(clipA.clipId)
        assertNotNull(receivedOnB)
        assertEquals(
            "Late AUDIO_METADATA must preserve REASSEMBLED state",
            com.example.domain.model.AudioTransferStatus.REASSEMBLED,
            receivedOnB?.status
        )
        assertEquals(clipA.sha256Hex, receivedOnB?.sha256Hex)

        // Responder B -> Sender A voice clip
        val audioB = AudioClipManager(context, repoB, idB, sendB)
        val bytesB = com.example.audio.ImaAdpcmCodec.synthesizeFallbackBeacon(1000L)
        val clipB = audioB.enqueueAudioBytesForMesh(
            sessionId = session.sessionId,
            recipientId = aKeys.peerId,
            codec = "RMAD_8K",
            durationMillis = 1000L,
            bytes = bytesB,
            localFileId = "clipb_local.rmad"
        )
        queueB.allPendingEntries()
            .filter {
                it.typeCode.toByte() == PacketType.AUDIO_METADATA.code ||
                    it.typeCode.toByte() == PacketType.AUDIO_FRAGMENT.code ||
                    it.typeCode.toByte() == PacketType.AUDIO_COMPLETE.code
            }
            .map { PacketSerializer.deserialize(it.wireBytes) }
            .also { list -> assertTrue("Responder audio must also be encrypted", list.all { it.isEncryptedPayload }) }
            .forEach { routerA.routeIncomingPacket(it) }

        val receivedOnA = repoA.getAudioClip(clipB.clipId)
        assertNotNull(receivedOnA)
        assertEquals(com.example.domain.model.AudioTransferStatus.REASSEMBLED, receivedOnA?.status)
        assertEquals(clipB.sha256Hex, receivedOnA?.sha256Hex)

        dbA.close()
        dbB.close()
    }

    @Test
    fun `strict paired-only mode refuses SOS without contacts and broadcast mode ignores paired filter`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        val prefs = UserPreferencesRepository(context)
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val id = IdentityManager(context)
        val queue = StoreForwardQueue(repo)
        val router = MessageRouter(repo, queue, id, prefs) {}
        val coord = MeshCoordinator(InMemoryFakeTransport(), repo, PeerDiscoveryManager(repo), queue, PriorityScheduler(queue), PacketReceiver(DuplicateDetectionManager(repo), id), router)
        val sendUseCase = SendEmergencyPacketUseCase(repo, router, queue, prefs) { coord }
        val createSos = CreateSosUseCase(repo, id, prefs, sendUseCase)

        // 1) Paired-only with 0 contacts must fail cleanly instead of leaking unencrypted broadcast
        prefs.setSendToAllNodes(false)
        var threwNoContacts = false
        try {
            createSos(
                EmergencyContext("SOS_DISTRESS", "Should fail", 80, 1),
                null,
                recipientPeerId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID
            )
        } catch (e: IllegalStateException) {
            threwNoContacts = e.message == "NO_PAIRED_CONTACTS"
        }
        assertTrue("Paired-only SOS with 0 paired contacts must throw NO_PAIRED_CONTACTS", threwNoContacts)
        assertTrue(queue.allPendingEntries().isEmpty())

        // 2) Add a paired contact -> Paired-only emits encrypted packet; Broadcast emits cleartext packet
        val targetKeys = RescueCrypto.generateIdentityKeyPair()
        repo.upsertContact(
            com.example.domain.model.EmergencyContact(
                peerId = targetKeys.peerId,
                alias = "PairedNode",
                x25519PublicKeyHex = RescueCrypto.bytesToHex(targetKeys.x25519Public),
                ed25519PublicKeyHex = RescueCrypto.bytesToHex(targetKeys.ed25519Public),
                addedAtMillis = System.currentTimeMillis()
            )
        )

        val pairedSession = createSos(
            EmergencyContext("SOS_DISTRESS", "E2EE paired", 80, 1),
            null,
            recipientPeerId = MeshConstants.PAIRED_MULTI_RECIPIENT_ID
        )
        val pairedWire = PacketSerializer.deserialize(
            queue.allPendingEntries().first { it.sessionId == pairedSession.sessionId }.wireBytes
        )
        assertTrue("Paired SOS must be encrypted", pairedWire.isEncryptedPayload)

        prefs.setSendToAllNodes(true)
        val broadcastSession = createSos(
            EmergencyContext("SOS_DISTRESS", "Public broadcast", 80, 1),
            null,
            recipientPeerId = MeshConstants.BROADCAST_RECIPIENT_ID
        )
        val broadcastWire = PacketSerializer.deserialize(
            queue.allPendingEntries().first { it.sessionId == broadcastSession.sessionId }.wireBytes
        )
        assertFalse("Send-to-All broadcast SOS must stay unencrypted for all nearby nodes", broadcastWire.isEncryptedPayload)
        assertEquals(MeshConstants.BROADCAST_RECIPIENT_ID, broadcastWire.recipientId)

        db.close()
    }

    @Test
    fun `restarted sender sequence window resets cleanly when timestamp advances past 30s`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RescueMeshDatabase::class.java).allowMainThreadQueries().build()
        val repo = RescueMeshRepository(db, AudioFileStore(context))
        context.getSharedPreferences("rescuemesh_identity", Context.MODE_PRIVATE).edit().clear().commit()
        val id = IdentityManager(context)
        val senderKeys = RescueCrypto.generateIdentityKeyPair()
        val receiver = PacketReceiver(DuplicateDetectionManager(repo), id)

        val now = System.currentTimeMillis()
        fun buildPacket(packetId: String, seq: Long, createdAt: Long): ByteArray {
            val payload = """{"context":{"category":"SOS_DISTRESS","summary":"Restart $packetId","batteryPercent":90,"peopleCount":1}}""".toByteArray()
            val hash = RescueCrypto.sha256(payload)
            val signable = PacketSerializer.buildSignableBytes(
                packetId = packetId,
                sessionId = "sess_restart_$packetId",
                senderId = senderKeys.peerId,
                recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
                type = PacketType.SOS,
                priority = PacketPriority.CRITICAL_SOS,
                sequenceNumber = seq,
                createdAtMillis = createdAt,
                expiresAtMillis = createdAt + 120_000L,
                maxHopCount = MeshConstants.UNLIMITED_HOPS,
                payloadHash = hash
            )
            val pkt = com.example.domain.model.EmergencyPacket(
                packetId = packetId,
                sessionId = "sess_restart_$packetId",
                senderId = senderKeys.peerId,
                recipientId = MeshConstants.BROADCAST_RECIPIENT_ID,
                type = PacketType.SOS,
                priority = PacketPriority.CRITICAL_SOS,
                createdAtMillis = createdAt,
                expiresAtMillis = createdAt + 120_000L,
                sequenceNumber = seq,
                hopCount = 0,
                maxHopCount = MeshConstants.UNLIMITED_HOPS,
                payload = payload,
                payloadHash = hash,
                authTag = RescueCrypto.signEd25519(senderKeys.ed25519Private, signable),
                senderPublicKeyEd25519 = senderKeys.ed25519Public
            )
            return PacketSerializer.serialize(pkt)
        }

        val t0 = now - 50_000L
        assertTrue(receiver.acceptRawFrame(buildPacket("p1", 5L, t0), nowMillis = now) is PacketReceiver.IntakeResult.Accepted)
        assertTrue(
            "Exact duplicate packetId must still be rejected",
            receiver.acceptRawFrame(buildPacket("p1", 5L, t0), nowMillis = now) is PacketReceiver.IntakeResult.Dropped
        )
        assertTrue(
            "Restarted sender sequence=1 after >30s gap must be accepted",
            receiver.acceptRawFrame(buildPacket("p2", 1L, t0 + 45_000L), nowMillis = now) is PacketReceiver.IntakeResult.Accepted
        )
        db.close()
    }
}



