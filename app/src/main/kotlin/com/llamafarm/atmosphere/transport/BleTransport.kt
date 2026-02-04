package com.llamafarm.atmosphere.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val TAG = "BleTransport"

// ============================================================================
// UUIDs (Must match Mac/Python implementation)
// ============================================================================

object BleUuids {
    // Atmosphere Mesh Service UUID (128-bit, unique to Atmosphere)
    // Base: A7M05PH3-xxxx-4000-8000-00805F9B34FB (Atmosphere prefix)
    val MESH_SERVICE_UUID: UUID = UUID.fromString("A7A05F30-0001-4000-8000-00805F9B34FB")
    
    // TX Characteristic - clients write here to send messages
    val TX_CHAR_UUID: UUID = UUID.fromString("A7A05F30-0002-4000-8000-00805F9B34FB")
    
    // RX Characteristic - server notifies clients of incoming messages
    val RX_CHAR_UUID: UUID = UUID.fromString("A7A05F30-0003-4000-8000-00805F9B34FB")
    
    // INFO Characteristic - read-only node information
    val INFO_CHAR_UUID: UUID = UUID.fromString("A7A05F30-0004-4000-8000-00805F9B34FB")
    
    // MESH_ID Characteristic - identifies which mesh this node belongs to
    val MESH_ID_CHAR_UUID: UUID = UUID.fromString("A7A05F30-0005-4000-8000-00805F9B34FB")
    
    // Client Characteristic Configuration Descriptor (standard BLE)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    
    // Manufacturer ID for service data (Atmosphere = 0xA7F0)
    const val ATMOSPHERE_MANUFACTURER_ID = 0xA7F0
}

// ============================================================================
// Message Types (matching Python implementation)
// ============================================================================

enum class MessageType(val value: Int) {
    // Discovery
    HELLO(0x01),
    HELLO_ACK(0x02),
    GOODBYE(0x03),
    
    // Routing
    ROUTE_REQ(0x10),
    ROUTE_REP(0x11),
    
    // Data
    DATA(0x20),
    DATA_ACK(0x21),
    
    // Mesh management
    MESH_INFO(0x30),
    CAPABILITY(0x31);
    
    companion object {
        fun fromValue(value: Int): MessageType = entries.find { it.value == value } ?: DATA
    }
}

object MessageFlags {
    const val ENCRYPTED = 0x01
    const val BROADCAST = 0x02
    const val PRIORITY = 0x04
    const val RELIABLE = 0x08
}

// ============================================================================
// Data Classes
// ============================================================================

/**
 * 8-byte message header.
 */
data class MessageHeader(
    val version: Int = 1,
    val msgType: MessageType = MessageType.DATA,
    val ttl: Int = 5,
    val flags: Int = 0,
    val seq: Int = 0,
    val fragIndex: Int = 0,
    val fragTotal: Int = 1
) {
    fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(version.toByte())
        buffer.put(msgType.value.toByte())
        buffer.put(ttl.toByte())
        buffer.put(flags.toByte())
        buffer.putShort(seq.toShort())
        buffer.put(fragIndex.toByte())
        buffer.put(fragTotal.toByte())
        return buffer.array()
    }
    
    companion object {
        fun unpack(data: ByteArray): MessageHeader {
            if (data.size < 8) throw IllegalArgumentException("Header too short: ${data.size} bytes")
            
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return MessageHeader(
                version = buffer.get().toInt() and 0xFF,
                msgType = MessageType.fromValue(buffer.get().toInt() and 0xFF),
                ttl = buffer.get().toInt() and 0xFF,
                flags = buffer.get().toInt() and 0xFF,
                seq = buffer.short.toInt() and 0xFFFF,
                fragIndex = buffer.get().toInt() and 0xFF,
                fragTotal = buffer.get().toInt() and 0xFF
            )
        }
    }
}

/**
 * Complete BLE mesh message.
 */
data class BleMessage(
    val header: MessageHeader,
    val payload: ByteArray,
    val sourceId: String = ""
) {
    fun toBytes(): ByteArray = header.pack() + payload
    
    companion object {
        fun fromBytes(data: ByteArray, sourceId: String = ""): BleMessage {
            val header = MessageHeader.unpack(data)
            val payload = data.copyOfRange(8, data.size)
            return BleMessage(header, payload, sourceId)
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleMessage
        return header == other.header && payload.contentEquals(other.payload) && sourceId == other.sourceId
    }
    
    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + sourceId.hashCode()
        return result
    }
}

/**
 * Information about an Atmosphere node.
 */
data class NodeInfo(
    val nodeId: String,
    val name: String = "",
    val capabilities: List<String> = emptyList(),
    val platform: String = "",
    val version: String = "1.0",
    var rssi: Int = 0,
    var lastSeen: Long = System.currentTimeMillis()
)

/**
 * Connected peer state.
 */
data class PeerConnection(
    val device: BluetoothDevice,
    var gatt: BluetoothGatt? = null,
    var txCharacteristic: BluetoothGattCharacteristic? = null,
    var rxCharacteristic: BluetoothGattCharacteristic? = null,
    var info: NodeInfo? = null,
    var state: ConnectionState = ConnectionState.DISCONNECTED
) {
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCOVERING_SERVICES
    }
}

// ============================================================================
// Message Fragmenter
// ============================================================================

class MessageFragmenter(private val mtu: Int = 236) {
    private var seqCounter = 0
    private val pendingReassembly = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val reassemblyTimestamps = ConcurrentHashMap<String, Long>()
    
    companion object {
        const val MAX_MESSAGE_SIZE = 64 * 1024 // 64KB
        const val REASSEMBLY_TIMEOUT_MS = 30_000L
    }
    
    @Synchronized
    private fun nextSeq(): Int {
        seqCounter = (seqCounter + 1) and 0xFFFF
        return seqCounter
    }
    
    fun fragment(
        payload: ByteArray,
        msgType: MessageType = MessageType.DATA,
        ttl: Int = 5,
        flags: Int = 0
    ): List<ByteArray> {
        require(payload.size <= MAX_MESSAGE_SIZE) { 
            "Message too large: ${payload.size} > $MAX_MESSAGE_SIZE"
        }
        
        val seq = nextSeq()
        val totalFrags = ((payload.size + mtu - 1) / mtu).coerceAtLeast(1)
        
        return (0 until totalFrags).map { i ->
            val start = i * mtu
            val end = minOf(start + mtu, payload.size)
            
            val header = MessageHeader(
                version = 1,
                msgType = msgType,
                ttl = ttl,
                flags = flags,
                seq = seq,
                fragIndex = i,
                fragTotal = totalFrags
            )
            
            header.pack() + payload.copyOfRange(start, end)
        }
    }
    
    fun reassemble(data: ByteArray, sourceId: String): BleMessage? {
        val header = MessageHeader.unpack(data)
        val payload = data.copyOfRange(8, data.size)
        
        // Single-fragment message
        if (header.fragTotal == 1) {
            return BleMessage(header, payload, sourceId)
        }
        
        // Multi-fragment message
        val key = "$sourceId:${header.seq}"
        val now = System.currentTimeMillis()
        
        // Cleanup stale reassembly
        cleanupStaleReassembly()
        
        // Initialize or update reassembly state
        pendingReassembly.getOrPut(key) { mutableMapOf() }[header.fragIndex] = payload
        reassemblyTimestamps[key] = now
        
        val fragments = pendingReassembly[key] ?: return null
        
        // Check if we have all fragments
        if (fragments.size == header.fragTotal) {
            pendingReassembly.remove(key)
            reassemblyTimestamps.remove(key)
            
            // Reassemble in order
            val completePayload = (0 until header.fragTotal)
                .mapNotNull { fragments[it] }
                .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            
            return BleMessage(
                header = header.copy(fragIndex = 0, fragTotal = 1),
                payload = completePayload,
                sourceId = sourceId
            )
        }
        
        return null
    }
    
    private fun cleanupStaleReassembly() {
        val now = System.currentTimeMillis()
        val staleKeys = reassemblyTimestamps.filter { 
            now - it.value > REASSEMBLY_TIMEOUT_MS 
        }.keys
        
        staleKeys.forEach { key ->
            pendingReassembly.remove(key)
            reassemblyTimestamps.remove(key)
        }
    }
}

// ============================================================================
// LRU Cache for seen messages (loop prevention)
// ============================================================================

class LruCache<K>(private val maxSize: Int) {
    private val cache = LinkedHashMap<K, Long>(maxSize, 0.75f, true)
    
    @Synchronized
    fun contains(key: K): Boolean {
        return cache.containsKey(key)
    }
    
    @Synchronized
    fun add(key: K) {
        cache[key] = System.currentTimeMillis()
        while (cache.size > maxSize) {
            val oldestKey = cache.keys.firstOrNull() ?: return
            cache.remove(oldestKey)
        }
    }
}

// ============================================================================
// BLE Transport
// ============================================================================

/**
 * BLE Transport for Atmosphere mesh.
 * 
 * Operates in both central (scanner/client) and peripheral (advertiser/server) modes
 * for full mesh connectivity.
 * 
 * Features:
 * - Advertises mesh_id in service data for mesh filtering
 * - Multi-hop messaging with TTL-based forwarding
 * - Message fragmentation for large payloads
 * - Loop prevention with seen message tracking
 * - Both GATT server and client modes
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val nodeName: String = "Atmosphere-${Build.MODEL.take(8)}",
    private val capabilities: List<String> = listOf("relay"),
    private val meshId: String? = null // Optional: filter to specific mesh
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nodeId = UUID.randomUUID().toString().replace("-", "").take(16)
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    
    // Connected peers
    private val connectedPeers = ConcurrentHashMap<String, PeerConnection>()
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    
    // Message handling
    private val fragmenter = MessageFragmenter()
    private val seenMessages = LruCache<String>(1000)
    
    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _peers = MutableStateFlow<List<NodeInfo>>(emptyList())
    val peers: StateFlow<List<NodeInfo>> = _peers.asStateFlow()
    
    private val _messages = MutableSharedFlow<BleMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<BleMessage> = _messages.asSharedFlow()
    
    // Callbacks
    var onPeerDiscovered: ((NodeInfo) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    var onMessage: ((BleMessage) -> Unit)? = null
    
    // Node info for INFO characteristic
    private val nodeInfo: Map<String, Any> = mapOf(
        "id" to nodeId,
        "name" to nodeName,
        "platform" to "Android",
        "capabilities" to capabilities,
        "version" to "1.0"
    )
    
    // ========================================================================
    // Lifecycle
    // ========================================================================
    
    fun start() {
        if (_isRunning.value) return
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }
        
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return
        }
        
        _isRunning.value = true
        Log.i(TAG, "Starting BLE transport: $nodeName ($nodeId)")
        
        // Start GATT server (peripheral mode)
        setupGattServer()
        
        // Start advertising
        startAdvertising()
        
        // Start scanning (central mode)
        startScanning()
    }
    
    fun stop() {
        _isRunning.value = false
        Log.i(TAG, "Stopping BLE transport")
        
        // Stop scanning
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
        
        // Stop advertising
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
        
        // Disconnect all GATT clients
        gattClients.values.forEach { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT: ${e.message}")
            }
        }
        gattClients.clear()
        
        // Close GATT server
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        
        connectedPeers.clear()
        _peers.value = emptyList()
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    // ========================================================================
    // GATT Server (Peripheral Mode)
    // ========================================================================
    
    private fun setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)?.apply {
            val service = BluetoothGattService(
                BleUuids.MESH_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            // TX Characteristic (clients write to send messages)
            val txChar = BluetoothGattCharacteristic(
                BleUuids.TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // RX Characteristic (we notify when we have messages)
            val rxChar = BluetoothGattCharacteristic(
                BleUuids.RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            // Add CCCD for notifications
            rxChar.addDescriptor(BluetoothGattDescriptor(
                BleUuids.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
            
            // INFO Characteristic (node capabilities)
            val infoChar = BluetoothGattCharacteristic(
                BleUuids.INFO_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            infoChar.value = encodeNodeInfo()
            
            // MESH_ID Characteristic (identifies which mesh this node belongs to)
            val meshIdChar = BluetoothGattCharacteristic(
                BleUuids.MESH_ID_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            meshIdChar.value = (meshId ?: "").toByteArray(Charsets.UTF_8)
            
            service.addCharacteristic(txChar)
            service.addCharacteristic(rxChar)
            service.addCharacteristic(infoChar)
            service.addCharacteristic(meshIdChar)
            
            addService(service)
            Log.i(TAG, "GATT server setup complete (mesh=$meshId)")
        }
    }
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT server: device connected: $address")
                    connectedPeers.getOrPut(address) { 
                        PeerConnection(device, state = PeerConnection.ConnectionState.CONNECTED)
                    }
                    updatePeersList()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT server: device disconnected: $address")
                    connectedPeers.remove(address)
                    onPeerLost?.invoke(address)
                    updatePeersList()
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data: ByteArray? = when (characteristic.uuid) {
                BleUuids.INFO_CHAR_UUID -> {
                    val info = encodeNodeInfo()
                    if (offset > 0 && offset < info.size) {
                        info.copyOfRange(offset, info.size)
                    } else if (offset == 0) {
                        info
                    } else {
                        ByteArray(0)
                    }
                }
                BleUuids.MESH_ID_CHAR_UUID -> {
                    val meshIdBytes = (meshId ?: "").toByteArray(Charsets.UTF_8)
                    if (offset > 0 && offset < meshIdBytes.size) {
                        meshIdBytes.copyOfRange(offset, meshIdBytes.size)
                    } else if (offset == 0) {
                        meshIdBytes
                    } else {
                        ByteArray(0)
                    }
                }
                else -> null
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, data)
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
            if (characteristic.uuid == BleUuids.TX_CHAR_UUID && value != null) {
                handleIncomingData(value, device.address)
            }
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
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
            // Handle CCCD subscription
            if (descriptor.uuid == BleUuids.CCCD_UUID) {
                Log.d(TAG, "CCCD write request from ${device.address}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }
    
    // ========================================================================
    // Advertising (Peripheral Mode)
    // ========================================================================
    
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE advertising not supported")
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        
        // MINIMAL advertising - just service UUID (fits easily in 31 bytes)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuids.MESH_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        
        // NO scan response - keep it simple for now
        // Other devices can read our INFO characteristic after connecting
        
        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "✅ Advertising started (service UUID only)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}")
        }
    }
    
    /**
     * Build service data containing mesh_id and node_id.
     * Format: [mesh_id prefix (8 bytes)][node_id prefix (8 bytes)]
     */
    private fun buildServiceData(): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        
        // Mesh ID (first 8 bytes of hex string, or zeros)
        val meshBytes = meshId?.take(16)?.let { hexToBytes(it) } ?: ByteArray(8)
        buffer.put(meshBytes.copyOf(8))
        
        // Node ID (first 8 bytes)
        val nodeBytes = hexToBytes(nodeId.take(16))
        buffer.put(nodeBytes.copyOf(8))
        
        return buffer.array()
    }
    
    /**
     * Build manufacturer data with additional node info.
     */
    private fun buildManufacturerData(): ByteArray {
        // Format: [version (1)][capabilities bitmap (1)][reserved (2)]
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1.toByte()) // Version 1
        buffer.put(encodeCapabilities().toByte())
        buffer.putShort(0) // Reserved
        return buffer.array()
    }
    
    private fun encodeCapabilities(): Int {
        var flags = 0
        if ("relay" in capabilities) flags = flags or 0x01
        if ("llm" in capabilities) flags = flags or 0x02
        if ("camera" in capabilities) flags = flags or 0x04
        if ("ble-mesh" in capabilities) flags = flags or 0x08
        return flags
    }
    
    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace("-", "")
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: error $errorCode")
        }
    }
    
    // ========================================================================
    // Scanning (Central Mode)
    // ========================================================================
    
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanning not supported")
            return
        }
        
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.MESH_SERVICE_UUID))
            .build()
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "Started scanning for Atmosphere nodes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning: ${e.message}")
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val rssi = result.rssi
            
            // Already connected?
            if (connectedPeers.containsKey(address)) {
                connectedPeers[address]?.info?.rssi = rssi
                connectedPeers[address]?.info?.lastSeen = System.currentTimeMillis()
                return
            }
            
            // Extract mesh_id from service data
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BleUuids.MESH_SERVICE_UUID))
            val discoveredMeshId = extractMeshIdFromServiceData(serviceData)
            
            // Filter by mesh_id if we have one configured
            if (meshId != null && discoveredMeshId != null) {
                if (!meshId.startsWith(discoveredMeshId, ignoreCase = true) &&
                    !discoveredMeshId.startsWith(meshId, ignoreCase = true)) {
                    Log.d(TAG, "Ignoring node from different mesh: $discoveredMeshId (we are $meshId)")
                    return
                }
            }
            
            Log.i(TAG, "Discovered Atmosphere node: ${device.name ?: address} (RSSI: $rssi, mesh: $discoveredMeshId)")
            connectToDevice(device, rssi)
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: error $errorCode")
        }
    }
    
    /**
     * Extract mesh_id from service data.
     * Format: [mesh_id (8 bytes)][node_id (8 bytes)]
     */
    private fun extractMeshIdFromServiceData(serviceData: ByteArray?): String? {
        if (serviceData == null || serviceData.size < 8) return null
        return serviceData.take(8).joinToString("") { "%02x".format(it) }
    }
    
    private fun connectToDevice(device: BluetoothDevice, rssi: Int) {
        val address = device.address
        if (gattClients.containsKey(address)) return
        
        Log.i(TAG, "Connecting to: $address")
        
        connectedPeers[address] = PeerConnection(
            device = device,
            state = PeerConnection.ConnectionState.CONNECTING,
            info = NodeInfo(nodeId = address, rssi = rssi)
        )
        
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gattClients[address] = gatt
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to: $address")
                    connectedPeers[address]?.state = PeerConnection.ConnectionState.DISCOVERING_SERVICES
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from: $address")
                    gattClients.remove(address)
                    connectedPeers.remove(address)
                    onPeerLost?.invoke(address)
                    updatePeersList()
                    gatt.close()
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed for $address")
                gatt.disconnect()
                return
            }
            
            val service = gatt.getService(BleUuids.MESH_SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "Mesh service not found on $address")
                gatt.disconnect()
                return
            }
            
            val peer = connectedPeers[address]
            peer?.gatt = gatt
            peer?.txCharacteristic = service.getCharacteristic(BleUuids.TX_CHAR_UUID)
            peer?.rxCharacteristic = service.getCharacteristic(BleUuids.RX_CHAR_UUID)
            peer?.state = PeerConnection.ConnectionState.CONNECTED
            
            // Read node info
            service.getCharacteristic(BleUuids.INFO_CHAR_UUID)?.let { infoChar ->
                gatt.readCharacteristic(infoChar)
            }
            
            // Subscribe to RX notifications
            peer?.rxCharacteristic?.let { rxChar ->
                gatt.setCharacteristicNotification(rxChar, true)
                rxChar.getDescriptor(BleUuids.CCCD_UUID)?.let { cccd ->
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
            
            Log.i(TAG, "Services discovered for $address")
            updatePeersList()
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            
            if (characteristic.uuid == BleUuids.INFO_CHAR_UUID) {
                val address = gatt.device.address
                val info = decodeNodeInfo(characteristic.value)
                
                connectedPeers[address]?.info = info?.copy(
                    rssi = connectedPeers[address]?.info?.rssi ?: 0,
                    lastSeen = System.currentTimeMillis()
                )
                
                info?.let { 
                    Log.i(TAG, "Received node info: ${it.name} (${it.nodeId})")
                    onPeerDiscovered?.invoke(it)
                }
                
                updatePeersList()
            }
        }
        
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                handleIncomingData(characteristic.value, gatt.device.address)
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                handleIncomingData(value, gatt.device.address)
            }
        }
    }
    
    // ========================================================================
    // Message Handling
    // ========================================================================
    
    private fun handleIncomingData(data: ByteArray, sourceAddress: String) {
        try {
            val message = fragmenter.reassemble(data, sourceAddress) ?: return
            
            // Check for duplicates (loop prevention)
            val msgId = "$sourceAddress:${message.header.seq}"
            if (seenMessages.contains(msgId)) return
            seenMessages.add(msgId)
            
            // Deliver locally
            scope.launch {
                _messages.emit(message)
            }
            onMessage?.invoke(message)
            
            // Forward if TTL > 1 (flood routing)
            if (message.header.ttl > 1) {
                forwardMessage(data, sourceAddress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming data: ${e.message}")
        }
    }
    
    private fun forwardMessage(data: ByteArray, sourceAddress: String) {
        // Decrement TTL
        val header = MessageHeader.unpack(data)
        if (header.ttl <= 1) return
        
        val newHeader = header.copy(ttl = header.ttl - 1)
        val newData = newHeader.pack() + data.copyOfRange(8, data.size)
        
        // Forward to all peers except source
        connectedPeers.forEach { (address, peer) ->
            if (address != sourceAddress && peer.state == PeerConnection.ConnectionState.CONNECTED) {
                sendToPeer(peer, newData)
            }
        }
        
        // Also notify via GATT server
        notifyGattClients(newData)
    }
    
    // ========================================================================
    // Sending Messages
    // ========================================================================
    
    fun send(
        payload: ByteArray,
        msgType: MessageType = MessageType.DATA,
        ttl: Int = 5,
        target: String? = null
    ): Boolean {
        val fragments = fragmenter.fragment(payload, msgType, ttl)
        var sent = false
        
        connectedPeers.forEach { (address, peer) ->
            if (target != null && address != target) return@forEach
            if (peer.state != PeerConnection.ConnectionState.CONNECTED) return@forEach
            
            fragments.forEach { fragment ->
                if (sendToPeer(peer, fragment)) {
                    sent = true
                }
            }
        }
        
        // Also notify via GATT server (for connected clients)
        if (target == null) {
            fragments.forEach { fragment ->
                notifyGattClients(fragment)
            }
        }
        
        return sent
    }
    
    private fun sendToPeer(peer: PeerConnection, data: ByteArray): Boolean {
        return try {
            val gatt = peer.gatt ?: gattClients[peer.device.address]
            val txChar = peer.txCharacteristic ?: gatt?.getService(BleUuids.MESH_SERVICE_UUID)
                ?.getCharacteristic(BleUuids.TX_CHAR_UUID)
            
            if (gatt != null && txChar != null) {
                txChar.value = data
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                gatt.writeCharacteristic(txChar)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send to ${peer.device.address}: ${e.message}")
            false
        }
    }
    
    private fun notifyGattClients(data: ByteArray) {
        val rxChar = gattServer?.getService(BleUuids.MESH_SERVICE_UUID)
            ?.getCharacteristic(BleUuids.RX_CHAR_UUID) ?: return
        
        rxChar.value = data
        
        connectedPeers.values.forEach { peer ->
            try {
                gattServer?.notifyCharacteristicChanged(peer.device, rxChar, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify ${peer.device.address}: ${e.message}")
            }
        }
    }
    
    fun broadcastHello() {
        val helloData = encodeNodeInfo()
        send(helloData, MessageType.HELLO)
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun updatePeersList() {
        _peers.value = connectedPeers.values
            .filter { it.state == PeerConnection.ConnectionState.CONNECTED }
            .mapNotNull { it.info }
    }
    
    private fun encodeNodeInfo(): ByteArray {
        // Simple JSON encoding (could use CBOR for better efficiency)
        val capsJson = capabilities.joinToString(",") { "\"$it\"" }
        val json = buildString {
            append("{")
            append("\"id\":\"$nodeId\",")
            append("\"name\":\"$nodeName\",")
            append("\"platform\":\"Android\",")
            append("\"capabilities\":[$capsJson],")
            append("\"version\":\"1.0\"")
            meshId?.let { append(",\"mesh_id\":\"$it\"") }
            append("}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }
    
    private fun decodeNodeInfo(data: ByteArray?): NodeInfo? {
        if (data == null || data.isEmpty()) return null
        
        return try {
            val json = String(data, Charsets.UTF_8)
            // Simple JSON parsing
            val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
            val platform = Regex(""""platform"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
            val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "1.0"
            
            NodeInfo(
                nodeId = id,
                name = name,
                platform = platform,
                version = version
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode node info: ${e.message}")
            null
        }
    }
    
    fun getPeerCount(): Int = connectedPeers.count { 
        it.value.state == PeerConnection.ConnectionState.CONNECTED 
    }
    
    companion object {
        /**
         * Derive mesh encryption key from invite token.
         */
        fun deriveMeshKey(inviteToken: String): ByteArray {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(
                inviteToken.toCharArray(),
                "atmosphere-mesh-key".toByteArray(),
                100000,
                256
            )
            return factory.generateSecret(spec).encoded
        }
        
        /**
         * Required permissions for BLE transport.
         */
        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }
    }
}
