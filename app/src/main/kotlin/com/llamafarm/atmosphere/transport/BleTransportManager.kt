package com.llamafarm.atmosphere.transport

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.llamafarm.atmosphere.core.AtmosphereNative
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BleTransportManager"

// Atmosphere BLE GATT service UUIDs - MUST match Rust side exactly
private val SERVICE_UUID = UUID.fromString("a0b1c2d3-e4f5-6789-abcd-ef0123456789")
private val TX_CHAR_UUID = UUID.fromString("a0b1c2d3-e4f5-6789-abcd-ef0123456001") // Writable (central → peripheral)
private val RX_CHAR_UUID = UUID.fromString("a0b1c2d3-e4f5-6789-abcd-ef0123456002") // Notify (peripheral → central)
private val PEER_INFO_CHAR_UUID = UUID.fromString("a0b1c2d3-e4f5-6789-abcd-ef0123456003") // Readable (peer info)

// BLE MTU and chunking
private const val MAX_BLE_CHUNK_SIZE = 512
private const val MAX_CONNECTIONS = 5

/**
 * BLE transport manager for Atmosphere mesh networking.
 * 
 * Implements both GATT server (peripheral) and GATT client (central) roles
 * to enable bidirectional mesh connectivity over BLE.
 * 
 * Features:
 * - GATT server with Atmosphere service
 * - GATT client for connecting to other Atmosphere peers
 * - Data fragmentation/reassembly matching Rust protocol
 * - Peer discovery via BLE scanning
 */
class BleTransportManager(
    private val context: Context,
    private val atmosphereHandle: Long,
    private val peerId: String,
    private val appId: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    // Connected central devices (peers connected to our GATT server)
    private val connectedCentrals = ConcurrentHashMap<BluetoothDevice, GattClientConnection>()
    
    // Outbound connections (we connected to their GATT servers)
    private val outboundConnections = ConcurrentHashMap<String, GattServerConnection>()
    
    // Discovered peers
    private val discoveredPeers = ConcurrentHashMap<String, BluetoothDevice>()
    
    // Fragment reassembly buffers per peer
    private val reassemblyBuffers = ConcurrentHashMap<String, ReassemblyBuffer>()
    
    // Map BLE device address → Atmosphere peer ID (resolved after hello/peer_info exchange)
    private val deviceToAtmoPeerId = ConcurrentHashMap<String, String>()
    
    // Sequence number for sending fragments
    private var sendSequence: UShort = 0u
    
    init {
        checkAvailability()
    }
    
    /**
     * Check if BLE is available and permissions are granted.
     */
    private fun checkAvailability() {
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasAdapter = bluetoothAdapter != null
        val hasPermissions = checkPermissions()
        
        _isAvailable.value = hasBle && hasAdapter && hasPermissions
        
        Log.i(TAG, "BLE available: $hasBle, adapter: $hasAdapter, permissions: $hasPermissions")
    }
    
    /**
     * Check required permissions.
     */
    private fun checkPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Start BLE transport (both server and scanner).
     */
    fun start() {
        if (!_isAvailable.value) {
            Log.e(TAG, "BLE not available or permissions denied")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }
        
        startGattServer()
        startAdvertising()
        startScanning()
        
        _isRunning.value = true
        startOutgoingPollLoop()
        
        Log.i(TAG, "BLE transport started")
    }
    
    /**
     * Start GATT server (peripheral role).
     */
    private fun startGattServer() {
        try {
            // Create peer info JSON
            val peerInfo = org.json.JSONObject().apply {
                put("peer_id", peerId)
                put("app_id", appId)
            }
            val peerInfoBytes = peerInfo.toString().toByteArray(Charsets.UTF_8)
            
            // Create TX characteristic (central writes to us)
            val txChar = BluetoothGattCharacteristic(
                TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // Create RX characteristic (we notify central)
            val rxChar = BluetoothGattCharacteristic(
                RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            // Add CCC descriptor for notifications
            val cccDescriptor = BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            rxChar.addDescriptor(cccDescriptor)
            
            // Create PeerInfo characteristic (central reads our identity)
            val peerInfoChar = BluetoothGattCharacteristic(
                PEER_INFO_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            peerInfoChar.value = peerInfoBytes
            
            // Create service
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(txChar)
            service.addCharacteristic(rxChar)
            service.addCharacteristic(peerInfoChar)
            
            // Start GATT server
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            gattServer?.addService(service)
            
            Log.i(TAG, "GATT server started with Atmosphere service")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start GATT server: missing permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GATT server", e)
        }
    }
    
    /**
     * GATT server callback for handling client connections and writes.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected: ${device.address}")
                    val connection = GattClientConnection(device)
                    connectedCentrals[device] = connection
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected: ${device.address}")
                    connectedCentrals.remove(device)
                }
            }
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
            if (characteristic.uuid == TX_CHAR_UUID && value != null) {
                Log.d(TAG, "Received ${value.size} bytes from ${device.address}")
                
                // Handle fragment
                handleIncomingFragment(device.address, value)
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
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
            if (descriptor.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                // CCC descriptor write - client enabling/disabling notifications
                Log.d(TAG, "Central ${device.address} ${if (value?.get(0)?.toInt() == 1) "enabled" else "disabled"} notifications")
            }
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }
    
    /**
     * Start BLE advertising.
     */
    private fun startAdvertising() {
        try {
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (bleAdvertiser == null) {
                Log.e(TAG, "BLE advertiser not available")
                return
            }
            
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            
            bleAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            
            Log.i(TAG, "BLE advertising started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start advertising: missing permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error: $errorCode")
        }
    }
    
    /**
     * Start BLE scanning for Atmosphere peers.
     */
    private fun startScanning() {
        try {
            bleScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bleScanner == null) {
                Log.e(TAG, "BLE scanner not available")
                return
            }
            
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            // Scan without filters — CoreBluetooth on macOS doesn't reliably include
            // service UUID or device name in advertisement data. We validate the service
            // after connecting (in onServicesDiscovered).
            bleScanner?.startScan(null, settings, scanCallback)
            
            Log.i(TAG, "BLE scan started (no filter — validate service after connect)")
            
            Log.i(TAG, "BLE scanning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start scanning: missing permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning", e)
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            
            // Filter ghost peers
            if (address == "00:00:00:00:00:00" || address.isEmpty()) {
                return
            }
            
            // Without service UUID filter, validate the scan result:
            // Accept if service UUID matches OR device name is "Atmosphere"
            val hasServiceUuid = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            val hasName = result.scanRecord?.deviceName == "Atmosphere" ||
                          (try { device.name } catch (_: SecurityException) { null }) == "Atmosphere"
            if (!hasServiceUuid && !hasName) {
                return // Not an Atmosphere peer
            }
            
            if (!discoveredPeers.containsKey(address) && !outboundConnections.containsKey(address)) {
                Log.i(TAG, "Discovered Atmosphere BLE peer: $address (uuid=$hasServiceUuid, name=$hasName)")
                discoveredPeers[address] = device
                
                // Notify Rust JNI of discovered peer
                try {
                    AtmosphereNative.blePeerDiscovered(atmosphereHandle, address, address)
                } catch (e: Throwable) {
                    Log.w(TAG, "blePeerDiscovered JNI not available: ${e.message}")
                }
                
                // Auto-connect if under connection limit
                if (outboundConnections.size < MAX_CONNECTIONS) {
                    connectToPeer(device)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error: $errorCode")
        }
    }
    
    /**
     * Connect to a discovered peer (central role).
     */
    private fun connectToPeer(device: BluetoothDevice) {
        try {
            Log.i(TAG, "Connecting to peer: ${device.address}")
            
            val connection = GattServerConnection(device.address)
            outboundConnections[device.address] = connection
            
            device.connectGatt(context, false, connection.gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to connect: missing permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer", e)
        }
    }
    
    /**
     * Handle incoming BLE fragment and reassemble if complete.
     */
    private fun handleIncomingFragment(peerId: String, fragment: ByteArray) {
        if (fragment.size < 6) {
            Log.w(TAG, "Fragment too short: ${fragment.size} bytes")
            return
        }
        
        // Parse fragment header: [sequence(2)] [total_fragments(2)] [fragment_index(2)] [payload...]
        val buffer = ByteBuffer.wrap(fragment).order(ByteOrder.BIG_ENDIAN)
        val sequence = buffer.short.toUShort()
        val totalFragments = buffer.short.toUShort()
        val fragmentIndex = buffer.short.toUShort()
        val payload = ByteArray(fragment.size - 6)
        buffer.get(payload)
        
        Log.d(TAG, "Fragment from $peerId: seq=$sequence, idx=$fragmentIndex/$totalFragments, payload=${payload.size}B")
        
        // Get or create reassembly buffer for this peer
        val reassemblyBuffer = reassemblyBuffers.getOrPut(peerId) { ReassemblyBuffer() }
        
        val completeData = reassemblyBuffer.addFragment(sequence, totalFragments, fragmentIndex, payload)
        
        if (completeData != null) {
            Log.i(TAG, "Reassembled complete message from $peerId: ${completeData.size} bytes")
            
            // Keepalive ping/pong intercept: 32-byte messages starting with APIN/APON
            if (completeData.size == 32 && isKeepalivePing(completeData)) {
                Log.d(TAG, "Keepalive ping from $peerId, sending pong")
                val pong = completeData.copyOf()
                // Replace APIN (0x4150494E) with APON (0x41504F4E)
                pong[0] = 0x41; pong[1] = 0x50; pong[2] = 0x4F; pong[3] = 0x4E
                sendToPeer(peerId, pong)
                return
            }
            if (completeData.size == 32 && isKeepalivePong(completeData)) {
                Log.d(TAG, "Keepalive pong from $peerId (ignored)")
                return
            }
            
            // Notify Rust core
            scope.launch {
                try {
                    AtmosphereNative.bleDataReceived(atmosphereHandle, peerId, completeData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to notify Rust core of BLE data", e)
                }
            }
        }
    }
    
    /** Check if data starts with APIN magic (0x4150494E) */
    private fun isKeepalivePing(data: ByteArray): Boolean =
        data[0] == 0x41.toByte() && data[1] == 0x50.toByte() && data[2] == 0x49.toByte() && data[3] == 0x4E.toByte()

    /** Check if data starts with APON magic (0x41504F4E) */
    private fun isKeepalivePong(data: ByteArray): Boolean =
        data[0] == 0x41.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4F.toByte() && data[3] == 0x4E.toByte()

    /**
     * Send data to a peer (fragments if needed).
     */
    fun sendToPeer(peerId: String, data: ByteArray) {
        val connection = outboundConnections[peerId]
        if (connection == null) {
            Log.w(TAG, "No connection to peer: $peerId")
            return
        }
        
        val fragments = fragmentMessage(data)
        
        scope.launch {
            for (fragment in fragments) {
                connection.sendData(fragment)
                delay(10) // Small delay between fragments to avoid overwhelming BLE
            }
        }
    }
    
    /**
     * Fragment message matching Rust protocol.
     */
    private fun fragmentMessage(data: ByteArray): List<ByteArray> {
        val payloadPerChunk = MAX_BLE_CHUNK_SIZE - 6 // 6 bytes for header
        val totalFragments = ((data.size + payloadPerChunk - 1) / payloadPerChunk).toUShort()
        val sequence = sendSequence++
        
        return (0 until totalFragments.toInt()).map { i ->
            val start = i * payloadPerChunk
            val end = minOf(start + payloadPerChunk, data.size)
            val chunk = data.sliceArray(start until end)
            
            val buffer = ByteBuffer.allocate(6 + chunk.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(sequence.toShort())
            buffer.putShort(totalFragments.toShort())
            buffer.putShort(i.toShort())
            buffer.put(chunk)
            
            buffer.array()
        }
    }
    
    /**
     * Start polling Rust for outgoing BLE data.
     * Runs a coroutine that checks each connected peer for pending data.
     */
    private fun startOutgoingPollLoop() {
        scope.launch {
            Log.i(TAG, "📤 Starting outgoing BLE poll loop")
            while (isRunning.value) {
                // Poll outgoing for all connected peers (outbound connections)
                for ((deviceId, conn) in outboundConnections) {
                    // Use Atmosphere peer_id if known, fall back to device address
                    val atmoPeerId = deviceToAtmoPeerId[deviceId] ?: deviceId
                    try {
                        val data = AtmosphereNative.blePollOutgoing(atmosphereHandle, atmoPeerId)
                        if (data != null && data.isNotEmpty()) {
                            Log.i(TAG, "📤 Outgoing BLE data for $atmoPeerId ($deviceId): ${data.size} bytes")
                            val fragments = fragmentMessage(data)
                            for (fragment in fragments) {
                                conn.sendData(fragment)
                                delay(10)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "blePollOutgoing failed for $atmoPeerId: ${e.message}")
                    }
                }
                delay(50) // Poll every 50ms
            }
        }
    }

    /**
     * Stop BLE transport.
     */
    fun stop() {
        try {
            bleScanner?.stopScan(scanCallback)
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            
            outboundConnections.values.forEach { it.disconnect() }
            outboundConnections.clear()
            connectedCentrals.clear()
            discoveredPeers.clear()
            
            _isRunning.value = false
            
            scope.cancel()
            
            Log.i(TAG, "BLE transport stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE transport", e)
        }
    }
    
    /**
     * Connection to a GATT server (we are central).
     */
    inner class GattServerConnection(val peerId: String) {
        private var gatt: BluetoothGatt? = null
        private var txCharacteristic: BluetoothGattCharacteristic? = null
        private var rxCharacteristic: BluetoothGattCharacteristic? = null
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT server: $peerId")
                        this@GattServerConnection.gatt = gatt
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Failed to discover services: missing permissions", e)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server: $peerId")
                        outboundConnections.remove(peerId)
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
                        rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
                        
                        // Subscribe to RX notifications
                        rxCharacteristic?.let { rx ->
                            try {
                                gatt.setCharacteristicNotification(rx, true)
                                val descriptor = rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Failed to subscribe to notifications: missing permissions", e)
                            }
                        }
                        
                        Log.i(TAG, "GATT service discovered and configured for $peerId")
                        
                        // Read peer info to get the real Atmosphere peer ID
                        val peerInfoChar = service.getCharacteristic(PEER_INFO_CHAR_UUID)
                        if (peerInfoChar != null) {
                            try {
                                gatt.readCharacteristic(peerInfoChar)
                            } catch (e: SecurityException) {
                                Log.w(TAG, "Failed to read peer info: ${e.message}")
                            }
                        }

                        // Send hello with our Atmosphere peer ID so the remote GATT server
                        // can map our Bluetooth device ID to our real peer ID
                        scope.launch {
                            delay(500) // Wait for descriptor write to complete
                            val hello = """{"type":"hello","peer_id":"${this@BleTransportManager.peerId}","app_id":"atmosphere"}"""
                            txCharacteristic?.let { tx ->
                                try {
                                    tx.value = hello.toByteArray(Charsets.UTF_8)
                                    gatt.writeCharacteristic(tx)
                                    Log.i(TAG, "Sent BLE hello with peer_id=${this@BleTransportManager.peerId}")
                                    
                                    // Notify Rust JNI that this peer is accepted (handshake sent)
                                    try {
                                        AtmosphereNative.blePeerAccepted(atmosphereHandle, peerId, peerId)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "blePeerAccepted JNI not available: ${e.message}")
                                    }
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Failed to send BLE hello: ${e.message}")
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "Atmosphere service not found on peer $peerId")
                    }
                }
            }
            
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == PEER_INFO_CHAR_UUID) {
                    try {
                        val peerInfoJson = String(characteristic.value, Charsets.UTF_8)
                        val peerInfo = org.json.JSONObject(peerInfoJson)
                        val remotePeerId = peerInfo.optString("peer_id", "")
                        if (remotePeerId.isNotEmpty()) {
                            Log.i(TAG, "Remote peer ID: $remotePeerId (device: $peerId)")
                            // Store device→peer mapping for outgoing poll loop
                            deviceToAtmoPeerId[peerId] = remotePeerId
                            // Re-register with real peer ID
                            try {
                                AtmosphereNative.blePeerAccepted(atmosphereHandle, remotePeerId, peerId)
                            } catch (e: Throwable) {
                                Log.w(TAG, "blePeerAccepted JNI not available: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse peer info: ${e.message}")
                    }
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == RX_CHAR_UUID) {
                    val data = characteristic.value
                    Log.d(TAG, "Received notification from $peerId: ${data.size} bytes")
                    handleIncomingFragment(peerId, data)
                }
            }
        }
        
        suspend fun sendData(data: ByteArray) {
            withContext(Dispatchers.IO) {
                txCharacteristic?.let { tx ->
                    tx.value = data
                    try {
                        gatt?.writeCharacteristic(tx)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Failed to write characteristic: missing permissions", e)
                    }
                }
            }
        }
        
        fun disconnect() {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to disconnect: missing permissions", e)
            }
        }
    }
    
    /**
     * Connected central device (they connected to our GATT server).
     */
    data class GattClientConnection(val device: BluetoothDevice)
}

/**
 * Fragment reassembly buffer for a single peer.
 */
class ReassemblyBuffer {
    private val fragments = mutableMapOf<UShort, MutableMap<UShort, ByteArray>>()
    private val lastActivity = mutableMapOf<UShort, Long>()
    
    fun addFragment(
        sequence: UShort,
        totalFragments: UShort,
        fragmentIndex: UShort,
        payload: ByteArray
    ): ByteArray? {
        val fragmentMap = fragments.getOrPut(sequence) { mutableMapOf() }
        fragmentMap[fragmentIndex] = payload
        lastActivity[sequence] = System.currentTimeMillis()
        
        // Check if all fragments received
        if (fragmentMap.size == totalFragments.toInt()) {
            val completeData = (0 until totalFragments.toInt())
                .mapNotNull { fragmentMap[it.toUShort()] }
                .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            
            fragments.remove(sequence)
            lastActivity.remove(sequence)
            
            return completeData
        }
        
        // Cleanup stale fragments (older than 10 seconds)
        cleanup()
        
        return null
    }
    
    private fun cleanup() {
        val now = System.currentTimeMillis()
        val stale = lastActivity.filter { (_, time) -> now - time > 10_000 }.keys
        stale.forEach { seq ->
            fragments.remove(seq)
            lastActivity.remove(seq)
        }
    }
}
