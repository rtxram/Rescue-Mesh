package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.domain.model.Peer
import com.example.domain.model.PeerConnectionState
import com.example.protocol.PacketFragmenter
import com.example.protocol.PacketReassembler
import com.example.security.RescueCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class HelloWirePayload(
    val id: String,
    val alias: String,
    val ed: String = "",
    val x: String = ""
)

/**
 * Dual-role Bluetooth Low Energy (BLE) GATT Mesh Transport modeled on Bitchat Android:
 * - Concurrent BLE Peripheral (GATT Server + Legacy <=31B Advertising + ScanResponse)
 *   and Central (BLE Scanner + GATT Client)
 * - RescueMesh Service UUID with RX (write) and TX (notify) characteristics
 * - Explicit GATT Peer Hello ('R','H') handshake over BLE so devices identify each other
 *   even under Android RPA MAC randomization or asymmetric scanning
 * - Serialized ATT MTU-aware chunked writes & notifications for API 24..36
 */
class BleGattMeshTransport(
    private val context: Context,
    private val localPeerIdProvider: () -> String,
    private val localAliasProvider: () -> String,
    private val localEd25519HexProvider: () -> String = { "" },
    private val localX25519HexProvider: () -> String = { "" }
) : MeshTransport {

    companion object {
        private const val TAG = "BleGattMeshTransport"
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val CHAR_TX_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val HELLO_MAGIC_0: Byte = 0x52 // 'R'
        private const val HELLO_MAGIC_1: Byte = 0x48 // 'H'
        private const val CONNECT_COOLDOWN_MILLIS = 10_000L
    }

    override val transportName: String = "BLE GATT Mesh"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startStopMutex = Mutex()

    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val _diagnosticMessage = MutableStateFlow("Mesh idle")
    override val diagnosticMessage: StateFlow<String> = _diagnosticMessage.asStateFlow()

    private val discoveredPeersFlow = MutableSharedFlow<Peer>(extraBufferCapacity = 64)
    private val incomingFramesFlow = MutableSharedFlow<IncomingTransportFrame>(extraBufferCapacity = 128)
    private val reassembler = PacketReassembler()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedClientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()
    private val lastConnectAttemptAt = ConcurrentHashMap<String, Long>()
    private val failedConnectCountByAddress = ConcurrentHashMap<String, Int>()
    private val gattReadyAddresses = ConcurrentHashMap.newKeySet<String>()
    private val discoverTriggeredAddresses = ConcurrentHashMap.newKeySet<String>()
    private val retriedDiscoveryAddresses = ConcurrentHashMap.newKeySet<String>()
    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverSubscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val gattConnectMutex = Mutex()

    private val addressToPeerId = ConcurrentHashMap<String, String>()
    private val clientAddressByPeerId = ConcurrentHashMap<String, String>()
    private val serverAddressByPeerId = ConcurrentHashMap<String, String>()
    private val negotiatedMtuByAddress = ConcurrentHashMap<String, Int>()
    private val lastRssiByAddress = ConcurrentHashMap<String, Int>()

    private val writeMutexByAddress = ConcurrentHashMap<String, Mutex>()
    private val notifyMutexByAddress = ConcurrentHashMap<String, Mutex>()
    private val consecutiveWriteFailures = ConcurrentHashMap<String, Int>()
    private val consecutiveNotifyFailures = ConcurrentHashMap<String, Int>()
    private val pendingWriteAck = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingNotifyAck = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeScanCallback: ScanCallback? = null
    @Volatile
    private var serviceAddedDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var isAdvertisingActive = false
    @Volatile
    private var isScanningActive = false
    @Volatile
    private var gattServerReady = false
    @Volatile
    private var sosSprayActive = false
    @Volatile
    private var sosSessionPrefixBytes: ByteArray = ByteArray(3)

    override suspend fun startTransport() {
        startStopMutex.withLock {
            val btAdapter = adapter
            if (btAdapter == null) {
                _status.value = TransportStatus.HARDWARE_UNAVAILABLE
                _diagnosticMessage.value = "Bluetooth LE hardware unavailable"
                return
            }
            if (!hasRequiredBlePermissions()) {
                _status.value = TransportStatus.PERMISSION_MISSING
                _diagnosticMessage.value = "Missing Bluetooth / Location runtime permissions"
                return
            }
            if (!btAdapter.isEnabled) {
                _status.value = TransportStatus.BLUETOOTH_DISABLED
                _diagnosticMessage.value = "Bluetooth is turned off"
                return
            }
            if (!isLocationEnabledForLegacyBle()) {
                _status.value = TransportStatus.LOCATION_DISABLED
                _diagnosticMessage.value = "Turn on device Location (required for BLE scan on Android <= 11)"
                return
            }

            if (_status.value == TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING &&
                gattServerReady &&
                isScanningActive &&
                (isAdvertisingActive || btAdapter.bluetoothLeAdvertiser == null)
            ) {
                return
            }

            _status.value = TransportStatus.STARTING
            _diagnosticMessage.value = "Starting BLE GATT server, advertiser & scanner..."
            startGattServerAndWait()
            startAdvertising(btAdapter, includeScanResponse = true)
            startScanning(btAdapter)
            _status.value = TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING
            updateDiagnosticSummary(btAdapter)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopTransport() {
        startStopMutex.withLock {
            try {
                if (hasRequiredBlePermissions()) {
                    activeAdvertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
                    activeScanCallback?.let { runCatching { scanner?.stopScan(it) } }
                }
                activeAdvertiseCallback = null
                activeScanCallback = null
                isAdvertisingActive = false
                isScanningActive = false

                connectedClientGatts.values.forEach { gatt ->
                    runCatching {
                        gatt.disconnect()
                        gatt.close()
                    }
                }
                connectedClientGatts.clear()
                connectingAddresses.clear()
                lastConnectAttemptAt.clear()
                failedConnectCountByAddress.clear()
                gattReadyAddresses.clear()
                discoverTriggeredAddresses.clear()
                retriedDiscoveryAddresses.clear()
                connectedServerDevices.clear()
                serverSubscribedDevices.clear()
                addressToPeerId.clear()
                clientAddressByPeerId.clear()
                serverAddressByPeerId.clear()
                negotiatedMtuByAddress.clear()
                lastRssiByAddress.clear()
                consecutiveWriteFailures.clear()
                consecutiveNotifyFailures.clear()
                pendingWriteAck.values.forEach { it.complete(false) }
                pendingWriteAck.clear()
                pendingNotifyAck.values.forEach { it.complete(false) }
                pendingNotifyAck.clear()

                runCatching { gattServer?.clearServices() }
                runCatching { gattServer?.close() }
                gattServer = null
                txCharacteristic = null
                serviceAddedDeferred?.complete(false)
                serviceAddedDeferred = null
                gattServerReady = false
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE transport", e)
            } finally {
                _status.value = TransportStatus.STOPPED
                _diagnosticMessage.value = "Mesh paused"
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun restartDiscovery() {
        startStopMutex.withLock {
            val btAdapter = adapter ?: return
            if (!hasRequiredBlePermissions() || !btAdapter.isEnabled || !isLocationEnabledForLegacyBle()) {
                return
            }
            activeScanCallback?.let { runCatching { scanner?.stopScan(it) } }
            activeScanCallback = null
            isScanningActive = false

            activeAdvertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
            activeAdvertiseCallback = null
            isAdvertisingActive = false

            delay(200L)
            startGattServerAndWait()
            startAdvertising(btAdapter, includeScanResponse = true)
            startScanning(btAdapter)
            _status.value = TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING
            updateDiagnosticSummary(btAdapter)
        }
    }

    override fun discoverPeers(): Flow<Peer> = discoveredPeersFlow.asSharedFlow()

    override fun receiveData(): Flow<IncomingTransportFrame> = incomingFramesFlow.asSharedFlow()

    override fun observeConnectionStatus(): Flow<TransportStatus> = status

    override fun hasLiveConnections(): Boolean =
        gattReadyAddresses.isNotEmpty() || serverSubscribedDevices.isNotEmpty()

    override fun connectedPeerIds(): List<String> =
        (clientAddressByPeerId.keys + serverAddressByPeerId.keys).distinct()

    @SuppressLint("MissingPermission")
    override suspend fun sendData(targetPeerId: String?, data: ByteArray): Boolean {
        if (!hasRequiredBlePermissions() || _status.value != TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING) {
            return false
        }
        return if (targetPeerId != null) {
            val sent = sendToSinglePeer(targetPeerId, data)
            if (sent) true else sendToAllConnected(data)
        } else {
            sendToAllConnected(data)
        }
    }

    private suspend fun sendToAllConnected(data: ByteArray): Boolean {
        val allAddresses = (gattReadyAddresses + serverSubscribedDevices.keys +
            clientAddressByPeerId.values + serverAddressByPeerId.values).distinct()
        var any = false
        for (addr in allAddresses) {
            if (sendToAddress(addr, data)) any = true
        }
        return any
    }

    private suspend fun sendToSinglePeer(peerId: String, data: ByteArray): Boolean {
        val clientAddr = clientAddressByPeerId.entries
            .firstOrNull { it.key.equals(peerId, ignoreCase = true) }?.value
        if (clientAddr != null && gattReadyAddresses.contains(clientAddr)) {
            if (sendViaClientAddress(clientAddr, data)) return true
        }
        val serverAddr = serverAddressByPeerId.entries
            .firstOrNull { it.key.equals(peerId, ignoreCase = true) }?.value
        if (serverAddr != null && serverSubscribedDevices.containsKey(serverAddr)) {
            if (sendViaServerAddress(serverAddr, data)) return true
        }
        return false
    }

    private suspend fun sendToAddress(address: String, data: ByteArray): Boolean {
        if (gattReadyAddresses.contains(address) && sendViaClientAddress(address, data)) {
            return true
        }
        if (serverSubscribedDevices.containsKey(address) && sendViaServerAddress(address, data)) {
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaClientAddress(address: String, data: ByteArray): Boolean {
        val clientGatt = connectedClientGatts[address] ?: return false
        val rxChar = clientGatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_RX_UUID) ?: return false
        val attPayloadMax = ((negotiatedMtuByAddress[address] ?: 23) - 3).coerceIn(20, 240)
        val chunks = PacketFragmenter.fragment(data, mtuPayloadBudget = attPayloadMax)
        val mutex = writeMutexByAddress.computeIfAbsent(address) { Mutex() }

        return mutex.withLock {
            for (chunk in chunks) {
                var chunkDelivered = false
                for (attempt in 0 until 3) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingWriteAck[address] = ack
                    val initiated = writeCharacteristicCompat(clientGatt, rxChar, chunk)
                    if (!initiated) {
                        pendingWriteAck.remove(address)
                        delay(45L * (attempt + 1))
                        continue
                    }
                    val confirmed = withTimeoutOrNull(1_200L) { ack.await() } ?: false
                    pendingWriteAck.remove(address)
                    if (confirmed) {
                        chunkDelivered = true
                        break
                    }
                    delay(45L * (attempt + 1))
                }
                if (!chunkDelivered) {
                    val fails = (consecutiveWriteFailures[address] ?: 0) + 1
                    consecutiveWriteFailures[address] = fails
                    if (fails >= 3) {
                        evictDeadClientAddress(address)
                    }
                    return@withLock false
                }
            }
            consecutiveWriteFailures[address] = 0
            true
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaServerAddress(address: String, data: ByteArray): Boolean {
        val server = gattServer ?: return false
        val tx = txCharacteristic ?: return false
        val device = serverSubscribedDevices[address] ?: return false
        val attPayloadMax = ((negotiatedMtuByAddress[address] ?: 23) - 3).coerceIn(20, 240)
        val chunks = PacketFragmenter.fragment(data, mtuPayloadBudget = attPayloadMax)
        val mutex = notifyMutexByAddress.computeIfAbsent(address) { Mutex() }

        return mutex.withLock {
            for (chunk in chunks) {
                var chunkDelivered = false
                for (attempt in 0 until 3) {
                    val ack = CompletableDeferred<Boolean>()
                    pendingNotifyAck[address] = ack
                    val initiated = notifyDeviceCompat(server, device, tx, chunk)
                    if (!initiated) {
                        pendingNotifyAck.remove(address)
                        delay(45L * (attempt + 1))
                        continue
                    }
                    val confirmed = withTimeoutOrNull(1_200L) { ack.await() } ?: false
                    pendingNotifyAck.remove(address)
                    if (confirmed) {
                        chunkDelivered = true
                        break
                    }
                    delay(45L * (attempt + 1))
                }
                if (!chunkDelivered) {
                    val fails = (consecutiveNotifyFailures[address] ?: 0) + 1
                    consecutiveNotifyFailures[address] = fails
                    if (fails >= 3) {
                        serverSubscribedDevices.remove(address)
                        addressToPeerId[address]?.let { pid -> serverAddressByPeerId.remove(pid, address) }
                    }
                    return@withLock false
                }
            }
            consecutiveNotifyFailures[address] = 0
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeCharacteristic failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDeviceCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notifyCharacteristicChanged failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startGattServerAndWait() {
        if (gattServer != null && gattServerReady) return
        val deferred = CompletableDeferred<Boolean>()
        serviceAddedDeferred = deferred
        startGattServerIfNeeded()
        if (!gattServerReady) {
            withTimeoutOrNull(1_500L) { deferred.await() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServerIfNeeded() {
        if (gattServer != null && gattServerReady) {
            serviceAddedDeferred?.complete(true)
            return
        }
        val mgr = bluetoothManager ?: run {
            serviceAddedDeferred?.complete(false)
            return
        }
        runCatching { gattServer?.close() }
        gattServerReady = false

        val server = mgr.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                val ok = (status == BluetoothGatt.GATT_SUCCESS)
                gattServerReady = ok
                serviceAddedDeferred?.complete(ok)
                Log.d(TAG, "GATT service added status=$status")
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedServerDevices[device.address] = device
                    Log.d(TAG, "GATT server connected by ${device.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedServerDevices.remove(device.address)
                    serverSubscribedDevices.remove(device.address)
                    consecutiveNotifyFailures.remove(device.address)
                    pendingNotifyAck.remove(device.address)?.complete(false)
                    val peerId = addressToPeerId[device.address]
                    if (peerId != null) {
                        serverAddressByPeerId.remove(peerId, device.address)
                        if (!clientAddressByPeerId.containsKey(peerId)) {
                            scope.launch {
                                discoveredPeersFlow.emit(
                                    Peer(
                                        peerId = peerId,
                                        displayName = "Node-${peerId.take(4)}",
                                        bluetoothAddress = device.address,
                                        connectionState = PeerConnectionState.DISCONNECTED,
                                        lastSeenAtMillis = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                negotiatedMtuByAddress[device.address] = mtu
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                pendingNotifyAck[device.address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                val value = if (serverSubscribedDevices.containsKey(device.address)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (characteristic.uuid == CHAR_RX_UUID && value != null) {
                    connectedServerDevices[device.address] = device
                    val complete = reassembler.acceptChunk("srv:${device.address}", value)
                    if (complete != null) {
                        handleCompleteIncomingBytes(
                            address = device.address,
                            bytes = complete,
                            fromServerPath = true
                        )
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val isDisable = value != null && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                if (isDisable) {
                    serverSubscribedDevices.remove(device.address)
                } else {
                    connectedServerDevices[device.address] = device
                    serverSubscribedDevices[device.address] = device
                    consecutiveNotifyFailures.remove(device.address)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (!isDisable) {
                    // Send our Hello identity back to the Central client once notifications are subscribed
                    scope.launch {
                        delay(120L)
                        val sent = sendViaServerAddress(device.address, buildHelloFrame())
                        if (!sent) {
                            delay(250L)
                            sendViaServerAddress(device.address, buildHelloFrame())
                        }
                    }
                }
            }
        }) ?: run {
            serviceAddedDeferred?.complete(false)
            return
        }

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            CHAR_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            CHAR_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        val added = runCatching { server.addService(service) }.getOrDefault(false)
        if (!added) {
            serviceAddedDeferred?.complete(false)
        }
        txCharacteristic = tx
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(btAdapter: BluetoothAdapter, includeScanResponse: Boolean) {
        val adv = btAdapter.bluetoothLeAdvertiser
        if (adv == null) {
            isAdvertisingActive = false
            updateDiagnosticSummary(btAdapter)
            return
        }
        advertiser = adv
        activeAdvertiseCallback?.let { runCatching { adv.stopAdvertising(it) } }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Primary AdvertiseData strictly <= 31 bytes: 3B flags + 18B 128-bit UUID = 21 bytes
        val primaryData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // ScanResponse strictly <= 31 bytes: 18B header + 8B PeerID + (optional 4B SOS beacon) = 26..30 bytes
        val beaconBytes = if (sosSprayActive) {
            compactPeerIdBytes() + byteArrayOf(0x01) + sosSessionPrefixBytes
        } else {
            compactPeerIdBytes()
        }
        val scanResponse = if (includeScanResponse) {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(SERVICE_UUID), beaconBytes)
                .build()
        } else {
            null
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertisingActive = true
                updateDiagnosticSummary(btAdapter)
                Log.d(TAG, "BLE advertising started (scanResponse=$includeScanResponse sosSpray=$sosSprayActive)")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertisingActive = false
                Log.w(TAG, "BLE advertising failed errorCode=$errorCode includeScanResponse=$includeScanResponse")
                if (includeScanResponse) {
                    // Retry with minimal 21-byte primary payload only (peerId still exchanged via GATT Hello)
                    scope.launch {
                        delay(150L)
                        startAdvertising(btAdapter, includeScanResponse = false)
                    }
                } else {
                    _diagnosticMessage.value = "BLE advertise failed (code $errorCode); scan-only"
                }
            }
        }
        activeAdvertiseCallback = callback
        runCatching {
            if (scanResponse != null) {
                adv.startAdvertising(settings, primaryData, scanResponse, callback)
            } else {
                adv.startAdvertising(settings, primaryData, callback)
            }
        }
    }

    suspend fun setSosSprayActive(active: Boolean, sessionId: String? = null) {
        val prefix = sessionId?.take(6)?.let { hex ->
            val b = runCatching { RescueCrypto.hexToBytes(hex) }.getOrNull()
            if (b != null && b.size >= 3) b.copyOfRange(0, 3)
            else hex.toByteArray(StandardCharsets.UTF_8).copyOf(3)
        } ?: ByteArray(3)
        val changed = (sosSprayActive != active) || !sosSessionPrefixBytes.contentEquals(prefix)
        sosSprayActive = active
        sosSessionPrefixBytes = prefix
        // Update only advertising ScanResponse without interrupting active BLE scanning (avoids OS scan-throttle)
        if (changed && _status.value == TransportStatus.ACTIVE_ADVERTISING_AND_SCANNING) {
            startStopMutex.withLock {
                val btAdapter = adapter ?: return@withLock
                if (hasRequiredBlePermissions() && btAdapter.isEnabled) {
                    startAdvertising(btAdapter, includeScanResponse = true)
                    updateDiagnosticSummary(btAdapter)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(btAdapter: BluetoothAdapter) {
        val sc = btAdapter.bluetoothLeScanner
        if (sc == null) {
            isScanningActive = false
            updateDiagnosticSummary(btAdapter)
            return
        }
        scanner = sc
        activeScanCallback?.let { runCatching { sc.stopScan(it) } }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanMode = if (sosSprayActive) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                isScanningActive = false
                Log.w(TAG, "BLE scan failed errorCode=$errorCode")
                _diagnosticMessage.value = "BLE scan error (code $errorCode)"
            }
        }
        activeScanCallback = callback
        val started = runCatching {
            sc.startScan(listOf(filter), settings, callback)
            true
        }.getOrDefault(false)
        isScanningActive = started
        updateDiagnosticSummary(btAdapter)
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        lastRssiByAddress[address] = result.rssi

        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
        val decodedPeerId = decodeScannedPeerId(serviceData) ?: addressToPeerId[address]

        if (decodedPeerId != null) {
            if (decodedPeerId.equals(localPeerIdProvider(), ignoreCase = true)) return
            addressToPeerId[address] = decodedPeerId
            clientAddressByPeerId[decodedPeerId] = address

            val isConnected = connectedClientGatts.containsKey(address) && gattReadyAddresses.contains(address)
            val state = when {
                isConnected -> PeerConnectionState.CONNECTED
                connectingAddresses.contains(address) -> PeerConnectionState.CONNECTING
                else -> PeerConnectionState.DISCOVERED
            }
            scope.launch {
                discoveredPeersFlow.emit(
                    Peer(
                        peerId = decodedPeerId,
                        displayName = "RescueNode-${decodedPeerId.take(4)}",
                        bluetoothAddress = address,
                        connectionState = state,
                        rssi = result.rssi,
                        isRelayEnabled = true,
                        lastSeenAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }

        // Single-attempt guarded GATT connection (prevents Android GATT 133 concurrent connection storms)
        connectToPeerIfNeeded(device, decodedPeerId, result.rssi)
    }

    @SuppressLint("MissingPermission")
    private fun evictDeadClientAddress(address: String) {
        val deadGatt = connectedClientGatts.remove(address)
        connectingAddresses.remove(address)
        gattReadyAddresses.remove(address)
        discoverTriggeredAddresses.remove(address)
        retriedDiscoveryAddresses.remove(address)
        consecutiveWriteFailures.remove(address)
        pendingWriteAck.remove(address)?.complete(false)
        if (deadGatt != null) {
            refreshGattCache(deadGatt)
            runCatching {
                deadGatt.disconnect()
                deadGatt.close()
            }
        }
        val knownId = addressToPeerId[address]
        if (knownId != null) {
            clientAddressByPeerId.remove(knownId, address)
            if (!serverAddressByPeerId.containsKey(knownId)) {
                scope.launch {
                    discoveredPeersFlow.emit(
                        Peer(
                            peerId = knownId,
                            displayName = "RescueNode-${knownId.take(4)}",
                            bluetoothAddress = address,
                            connectionState = PeerConnectionState.DISCONNECTED,
                            rssi = lastRssiByAddress[address] ?: -70,
                            lastSeenAtMillis = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeerIfNeeded(device: BluetoothDevice, initialPeerId: String?, rssi: Int) {
        val address = device.address ?: return
        if (connectedClientGatts.containsKey(address) || connectingAddresses.contains(address)) return
        val now = System.currentTimeMillis()
        val fails = failedConnectCountByAddress[address] ?: 0
        val cooldown = (4_000L * (1 shl minOf(fails, 2))).coerceAtMost(12_000L)
        val prevAttempt = lastConnectAttemptAt[address]
        if (prevAttempt != null && (now - prevAttempt) < cooldown) return

        connectingAddresses.add(address)
        lastConnectAttemptAt[address] = now
        discoverTriggeredAddresses.remove(address)
        retriedDiscoveryAddresses.remove(address)

        var mtuFallbackJob: Job? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    Log.w(TAG, "GATT client disconnect/error addr=$address status=$status newState=$newState")
                    mtuFallbackJob?.cancel()
                    connectingAddresses.remove(address)
                    connectedClientGatts.remove(address)
                    gattReadyAddresses.remove(address)
                    discoverTriggeredAddresses.remove(address)
                    retriedDiscoveryAddresses.remove(address)
                    consecutiveWriteFailures.remove(address)
                    pendingWriteAck.remove(address)?.complete(false)
                    failedConnectCountByAddress[address] = (failedConnectCountByAddress[address] ?: 0) + 1
                    refreshGattCache(gatt)
                    runCatching { gatt.close() }
                    val knownId = addressToPeerId[address] ?: initialPeerId
                    if (knownId != null) {
                        clientAddressByPeerId.remove(knownId, address)
                        if (!serverAddressByPeerId.containsKey(knownId)) {
                            scope.launch {
                                discoveredPeersFlow.emit(
                                    Peer(
                                        peerId = knownId,
                                        displayName = "RescueNode-${knownId.take(4)}",
                                        bluetoothAddress = address,
                                        connectionState = PeerConnectionState.DISCONNECTED,
                                        rssi = rssi,
                                        lastSeenAtMillis = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectingAddresses.remove(address)
                    connectedClientGatts[address] = gatt

                    // Request larger ATT MTU first; discoverServices strictly after onMtuChanged
                    val mtuRequested = runCatching { gatt.requestMtu(247) }.getOrDefault(false)
                    if (!mtuRequested) {
                        if (discoverTriggeredAddresses.add(address)) {
                            gatt.discoverServices()
                        }
                    } else {
                        mtuFallbackJob = scope.launch {
                            delay(1_800L)
                            if (discoverTriggeredAddresses.add(address)) {
                                runCatching { gatt.discoverServices() }
                            }
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mtuFallbackJob?.cancel()
                    connectingAddresses.remove(address)
                    connectedClientGatts.remove(address)
                    gattReadyAddresses.remove(address)
                    discoverTriggeredAddresses.remove(address)
                    retriedDiscoveryAddresses.remove(address)
                    consecutiveWriteFailures.remove(address)
                    pendingWriteAck.remove(address)?.complete(false)
                    runCatching { gatt.close() }
                    val knownId = addressToPeerId[address] ?: initialPeerId
                    if (knownId != null) {
                        clientAddressByPeerId.remove(knownId, address)
                        if (!serverAddressByPeerId.containsKey(knownId)) {
                            scope.launch {
                                discoveredPeersFlow.emit(
                                    Peer(
                                        peerId = knownId,
                                        displayName = "RescueNode-${knownId.take(4)}",
                                        bluetoothAddress = address,
                                        connectionState = PeerConnectionState.DISCONNECTED,
                                        rssi = rssi,
                                        lastSeenAtMillis = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                mtuFallbackJob?.cancel()
                negotiatedMtuByAddress[address] = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                if (discoverTriggeredAddresses.add(address)) {
                    runCatching { gatt.discoverServices() }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(SERVICE_UUID) else null
                val tx = service?.getCharacteristic(CHAR_TX_UUID)
                val rx = service?.getCharacteristic(CHAR_RX_UUID)
                if (tx == null || rx == null) {
                    if (retriedDiscoveryAddresses.add(address)) {
                        Log.w(TAG, "GATT discovery incomplete for addr=$address status=$status; refreshing & retrying once")
                        refreshGattCache(gatt)
                        scope.launch {
                            delay(250L)
                            if (connectedClientGatts.containsKey(address)) {
                                runCatching { gatt.discoverServices() }
                            }
                        }
                        return
                    }
                    Log.w(TAG, "GATT discovery incomplete after retry for addr=$address status=$status")
                    connectingAddresses.remove(address)
                    connectedClientGatts.remove(address)
                    gattReadyAddresses.remove(address)
                    discoverTriggeredAddresses.remove(address)
                    retriedDiscoveryAddresses.remove(address)
                    failedConnectCountByAddress[address] = (failedConnectCountByAddress[address] ?: 0) + 1
                    refreshGattCache(gatt)
                    runCatching {
                        gatt.disconnect()
                        gatt.close()
                    }
                    return
                }
                gatt.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    if (!started) {
                        markClientReadyAndSendHello(address)
                    }
                } else {
                    markClientReadyAndSendHello(address)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                markClientReadyAndSendHello(address)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                pendingWriteAck[address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // Android 13+ (API 33+)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == CHAR_TX_UUID) {
                    val complete = reassembler.acceptChunk("cli:$address", value)
                    if (complete != null) {
                        handleCompleteIncomingBytes(address, complete, fromServerPath = false)
                    }
                }
            }

            // Android 7 - 12 (API 24 - 32)
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == CHAR_TX_UUID) {
                    val bytes = characteristic.value?.copyOf() ?: return
                    val complete = reassembler.acceptChunk("cli:$address", bytes)
                    if (complete != null) {
                        handleCompleteIncomingBytes(address, complete, fromServerPath = false)
                    }
                }
            }
        }

        scope.launch {
            gattConnectMutex.withLock {
                if (connectedClientGatts.containsKey(address)) {
                    connectingAddresses.remove(address)
                    return@withLock
                }
                val gatt = runCatching {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                if (gatt == null) {
                    connectingAddresses.remove(address)
                    failedConnectCountByAddress[address] = (failedConnectCountByAddress[address] ?: 0) + 1
                }
                delay(120L)
            }
        }
    }

    private fun markClientReadyAndSendHello(address: String) {
        gattReadyAddresses.add(address)
        failedConnectCountByAddress.remove(address)
        consecutiveWriteFailures.remove(address)
        scope.launch {
            delay(80L)
            val sent = sendViaClientAddress(address, buildHelloFrame())
            if (!sent) {
                delay(220L)
                sendViaClientAddress(address, buildHelloFrame())
            }
        }
    }

    private fun handleCompleteIncomingBytes(
        address: String,
        bytes: ByteArray,
        fromServerPath: Boolean
    ) {
        if (bytes.size > 2 && bytes[0] == HELLO_MAGIC_0 && bytes[1] == HELLO_MAGIC_1) {
            val payloadStr = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_8)
            val hello = runCatching { json.decodeFromString<HelloWirePayload>(payloadStr) }.getOrNull() ?: return
            if (hello.id.isBlank() || hello.id.equals(localPeerIdProvider(), ignoreCase = true)) return
            // Identity binding: the announced peerId must be the fingerprint of the announced
            // Ed25519 key, and both keys must be well-formed hex. Otherwise this hello is
            // a spoof attempt trying to poison the peer's encryption key.
            val edPubBytes = RescueCrypto.hexToBytes(hello.ed)
            val xPubBytes = RescueCrypto.hexToBytes(hello.x)
            if (edPubBytes.size != 32 || xPubBytes.size != 32) return
            if (!RescueCrypto.derivePeerId(edPubBytes).equals(hello.id, ignoreCase = true)) return

            addressToPeerId[address] = hello.id
            if (fromServerPath) {
                serverAddressByPeerId[hello.id] = address
            } else {
                clientAddressByPeerId[hello.id] = address
            }

            scope.launch {
                discoveredPeersFlow.emit(
                    Peer(
                        peerId = hello.id,
                        displayName = hello.alias.ifBlank { "RescueNode-${hello.id.take(4)}" },
                        bluetoothAddress = address,
                        ed25519PublicKeyHex = hello.ed,
                        x25519PublicKeyHex = hello.x,
                        connectionState = PeerConnectionState.CONNECTED,
                        rssi = lastRssiByAddress[address] ?: -65,
                        isRelayEnabled = true,
                        lastSeenAtMillis = System.currentTimeMillis()
                    )
                )
            }
            return
        }

        scope.launch {
            incomingFramesFlow.emit(
                IncomingTransportFrame(
                    sourceAddress = address,
                    rawBytes = bytes,
                    rssi = lastRssiByAddress[address]
                )
            )
        }
    }

    private fun buildHelloFrame(): ByteArray {
        val hello = HelloWirePayload(
            id = localPeerIdProvider(),
            alias = localAliasProvider(),
            ed = localEd25519HexProvider(),
            x = localX25519HexProvider()
        )
        val jsonBytes = json.encodeToString(HelloWirePayload.serializer(), hello)
            .toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(HELLO_MAGIC_0, HELLO_MAGIC_1) + jsonBytes
    }

    private fun compactPeerIdBytes(): ByteArray {
        val hexBytes = RescueCrypto.hexToBytes(localPeerIdProvider())
        return if (hexBytes.size >= 8) {
            hexBytes.copyOfRange(0, 8)
        } else {
            localPeerIdProvider().take(8).toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun decodeScannedPeerId(serviceData: ByteArray?): String? {
        if (serviceData == null || serviceData.isEmpty()) return null
        return if (serviceData.size >= 8) {
            RescueCrypto.bytesToHex(serviceData.copyOfRange(0, 8))
        } else {
            String(serviceData, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }

    private fun updateDiagnosticSummary(btAdapter: BluetoothAdapter) {
        val advStatus = when {
            isAdvertisingActive && sosSprayActive -> "SOS Spraying"
            isAdvertisingActive -> "Advertising"
            btAdapter.bluetoothLeAdvertiser == null -> "Central-only (no peripheral adv)"
            else -> "Advertise inactive"
        }
        val scanStatus = if (isScanningActive) "Scanning" else "Scan inactive"
        _diagnosticMessage.value = "$advStatus · $scanStatus"
    }

    fun isLocationEnabledForLegacyBle(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun hasRequiredBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
