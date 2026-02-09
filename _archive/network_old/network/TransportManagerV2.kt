package com.llamafarm.atmosphere.network

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.transport.BleTransport
import com.llamafarm.atmosphere.transport.BleMeshManager
import com.llamafarm.atmosphere.transport.WifiDirectTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TransportManagerV2"

/**
 * Multi-transport mesh manager with concurrent transport support.
 * 
 * Priority order (lower = higher priority):
 * 1. LAN (WebSocket) - fastest, same network
 * 2. WiFi Direct - no router needed
 * 3. BLE Mesh - works offline, multi-hop
 * 4. Matter - smart home devices
 * 5. Relay - always works (cloud fallback)
 * 
 * All enabled transports run concurrently. Messages are sent via
 * the best available transport. Fallback is automatic.
 */
class TransportManagerV2(
    private val context: Context,
    private val nodeId: String,
    private val meshId: String,
    private val config: TransportConfig = TransportConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Transport instances (running concurrently)
    private var lanTransport: MeshConnection? = null
    private var wifiDirectTransport: WifiDirectTransport? = null
    private var bleTransport: BleTransport? = null
    private var relayTransport: RelayTransport? = null
    
    // Message handler (receives from ALL transports)
    private var messageHandler: ((TransportType, String, ByteArray) -> Unit)? = null
    
    // State
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _transportStatus = MutableStateFlow<Map<TransportType, TransportStatus>>(emptyMap())
    val transportStatus: StateFlow<Map<TransportType, TransportStatus>> = _transportStatus.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()
    
    data class TransportStatus(
        val connected: Boolean,
        val peers: Int = 0,
        val latencyMs: Float = 0f,
        val error: String? = null
    )
    
    /**
     * Set message handler for all transports.
     */
    fun onMessage(handler: (transport: TransportType, fromPeer: String, message: ByteArray) -> Unit) {
        messageHandler = handler
    }
    
    /**
     * Start all enabled transports concurrently.
     */
    suspend fun startAll(
        token: MeshToken? = null,
        endpoints: Map<String, String>? = null,
        isFounder: Boolean = false,
        meshPublicKey: String? = null,
        founderProof: String? = null,
        capabilities: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.CONNECTING
        
        val results = mutableMapOf<TransportType, Boolean>()
        val jobs = mutableListOf<Deferred<Pair<TransportType, Boolean>>>()
        
        // Start LAN (WebSocket) if enabled and endpoint provided
        if (config.isEnabled(TransportType.LAN) && endpoints?.containsKey("local") == true) {
            jobs.add(async {
                TransportType.LAN to startLan(endpoints["local"]!!, token)
            })
        }
        
        // Start WiFi Direct if enabled
        if (config.isEnabled(TransportType.WIFI_DIRECT)) {
            jobs.add(async {
                TransportType.WIFI_DIRECT to startWifiDirect(isFounder)
            })
        }
        
        // Start BLE Mesh if enabled
        if (config.isEnabled(TransportType.BLE_MESH)) {
            jobs.add(async {
                TransportType.BLE_MESH to startBle(isFounder, capabilities)
            })
        }
        
        // Start Relay if enabled (always as fallback)
        if (config.isEnabled(TransportType.RELAY)) {
            jobs.add(async {
                TransportType.RELAY to startRelay(
                    endpoint = endpoints?.get("relay") ?: "${config.relay.url}/relay/$meshId",
                    token = token,
                    isFounder = isFounder,
                    meshPublicKey = meshPublicKey,
                    founderProof = founderProof,
                    capabilities = capabilities
                )
            })
        }
        
        // Wait for all transports to attempt connection (with timeout)
        try {
            withTimeout(15_000) {
                jobs.forEach { job ->
                    val (type, success) = job.await()
                    results[type] = success
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Transport startup timeout, some transports may still be connecting")
        }
        
        val anyConnected = results.values.any { it }
        _connectionState.value = if (anyConnected) ConnectionState.CONNECTED else ConnectionState.FAILED
        
        updateStatus()
        startProbeLoop()
        
        Log.i(TAG, "Transport status: $results")
        anyConnected
    }
    
    /**
     * Start LAN WebSocket transport.
     */
    private suspend fun startLan(endpoint: String, token: MeshToken?): Boolean {
        return try {
            val connection = MeshConnection(endpoint, token?.toJson()?.toString() ?: "")
            
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                connection.connectWithAuth(
                    nodeId = nodeId,
                    meshToken = token?.toJson(),
                    capabilities = emptyList(),
                    nodeName = "android-$nodeId",
                    onConnected = { 
                        Log.i(TAG, "LAN WebSocket connected")
                    },
                    onJoined = { 
                        Log.i(TAG, "LAN joined mesh")
                        if (cont.isActive) cont.resume(true, null)
                    },
                    onError = { error ->
                        Log.w(TAG, "LAN error: $error")
                        if (cont.isActive) cont.resume(false, null)
                    }
                )
            }
            
            if (success) {
                lanTransport = connection
                // Set up message handler
                scope.launch {
                    connection.messages.collect { msg ->
                        when (msg) {
                            is MeshMessage.LlmResponse -> {
                                messageHandler?.invoke(
                                    TransportType.LAN,
                                    "mesh",
                                    msg.response.toByteArray()
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "LAN start failed: ${e.message}")
            false
        }
    }
    
    /**
     * Start WiFi Direct transport.
     */
    private suspend fun startWifiDirect(isFounder: Boolean): Boolean {
        if (!WifiDirectTransport.isSupported(context)) {
            Log.w(TAG, "WiFi Direct not supported")
            return false
        }
        
        return try {
            val transport = WifiDirectTransport(
                context = context,
                nodeId = nodeId,
                config = config.wifiDirect
            )
            
            if (!transport.initialize()) {
                return false
            }
            
            transport.onMessage { data ->
                val json = try { JSONObject(String(data)) } catch (e: Exception) { null }
                val fromPeer = json?.optString("from") ?: "wifi-direct"
                messageHandler?.invoke(TransportType.WIFI_DIRECT, fromPeer, data)
            }
            
            transport.onPeerConnected = { peerId ->
                _connectedPeers.value = _connectedPeers.value + peerId
                updateStatus()
            }
            
            transport.onPeerDisconnected = { peerId ->
                _connectedPeers.value = _connectedPeers.value - peerId
                updateStatus()
            }
            
            val success = transport.connectToMesh(meshId, createGroup = isFounder)
            if (success) {
                wifiDirectTransport = transport
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "WiFi Direct start failed: ${e.message}")
            false
        }
    }
    
    /**
     * Start BLE Mesh transport.
     */
    private suspend fun startBle(isFounder: Boolean, capabilities: List<String>): Boolean {
        // Check if Bluetooth is available
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not available or not enabled")
            return false
        }
        
        return try {
            val transport = BleTransport(
                context = context,
                nodeName = "Android-${nodeId.take(8)}",
                capabilities = capabilities,
                meshId = meshId
            )
            
            // Set up message handler via flow collection
            scope.launch {
                transport.peers.collect { peers ->
                    _connectedPeers.value = _connectedPeers.value + peers.map { it.nodeId }.toSet()
                    updateStatus()
                }
            }
            
            // Start BLE (advertising + scanning)
            transport.start()
            bleTransport = transport
            
            Log.i(TAG, "BLE transport started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "BLE start failed: ${e.message}")
            false
        }
    }
    
    /**
     * Start relay transport.
     */
    private suspend fun startRelay(
        endpoint: String,
        token: MeshToken?,
        isFounder: Boolean,
        meshPublicKey: String?,
        founderProof: String?,
        capabilities: List<String>
    ): Boolean {
        return try {
            val transport = RelayTransport(config.relay)
            
            transport.onMessage { data ->
                val json = try { JSONObject(String(data)) } catch (e: Exception) { null }
                val fromPeer = json?.optString("from") ?: "relay"
                messageHandler?.invoke(TransportType.RELAY, fromPeer, data)
            }
            
            val success = transport.connect(
                RelayConnectParams(
                    meshId = meshId,
                    nodeId = nodeId,
                    token = token,
                    isFounder = isFounder,
                    meshPublicKey = meshPublicKey,
                    founderProof = founderProof,
                    capabilities = capabilities,
                    nodeName = "android-$nodeId"
                )
            )
            
            if (success) {
                relayTransport = transport
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Relay start failed: ${e.message}")
            false
        }
    }
    
    /**
     * Send message via best available transport.
     * Priority: LAN > WiFi Direct > BLE > Relay
     */
    suspend fun send(message: ByteArray): Boolean {
        // Try LAN first (fastest)
        lanTransport?.let { transport ->
            if (transport.connectionState.value == ConnectionState.CONNECTED) {
                // LAN uses text-based WebSocket
                // Would need to add binary support or convert
            }
        }
        
        // Try WiFi Direct
        wifiDirectTransport?.let { transport ->
            if (transport.connected) {
                try {
                    if (transport.send(message)) return true
                } catch (e: Exception) {
                    Log.w(TAG, "WiFi Direct send failed: ${e.message}")
                }
            }
        }
        
        // Try BLE
        bleTransport?.let { transport ->
            if (transport.isRunning.value && transport.peers.value.isNotEmpty()) {
                try {
                    // Broadcast to all connected BLE peers
                    if (transport.send(message)) return true
                } catch (e: Exception) {
                    Log.w(TAG, "BLE send failed: ${e.message}")
                }
            }
        }
        
        // Fallback to relay
        relayTransport?.let { transport ->
            if (transport.connected) {
                try {
                    return transport.send(message)
                } catch (e: Exception) {
                    Log.w(TAG, "Relay send failed: ${e.message}")
                }
            }
        }
        
        return false
    }
    
    /**
     * Send via specific transport.
     */
    suspend fun sendVia(transport: TransportType, message: ByteArray): Boolean {
        return when (transport) {
            TransportType.LAN -> false // TODO: implement
            TransportType.WIFI_DIRECT -> wifiDirectTransport?.send(message) ?: false
            TransportType.BLE_MESH -> bleTransport?.send(message) ?: false
            TransportType.MATTER -> false // Not implemented
            TransportType.RELAY -> relayTransport?.send(message) ?: false
        }
    }
    
    /**
     * Stop all transports.
     */
    suspend fun stopAll() {
        probeJob?.cancel()
        
        lanTransport?.disconnect()
        lanTransport = null
        
        wifiDirectTransport?.stop()
        wifiDirectTransport = null
        
        bleTransport?.stop()
        bleTransport = null
        
        relayTransport?.disconnect()
        relayTransport = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPeers.value = emptySet()
        
        Log.i(TAG, "All transports stopped")
    }
    
    private var probeJob: Job? = null
    
    private fun startProbeLoop() {
        probeJob = scope.launch {
            while (isActive) {
                delay(30_000)
                updateStatus()
            }
        }
    }
    
    private fun updateStatus() {
        val status = mutableMapOf<TransportType, TransportStatus>()
        
        status[TransportType.LAN] = TransportStatus(
            connected = lanTransport?.connectionState?.value == ConnectionState.CONNECTED
        )
        
        status[TransportType.WIFI_DIRECT] = TransportStatus(
            connected = wifiDirectTransport?.connected ?: false,
            peers = wifiDirectTransport?.discoveredPeers?.value?.size ?: 0
        )
        
        status[TransportType.BLE_MESH] = TransportStatus(
            connected = bleTransport?.isRunning?.value ?: false,
            peers = bleTransport?.peers?.value?.size ?: 0
        )
        
        status[TransportType.MATTER] = TransportStatus(
            connected = false,
            error = "Not implemented"
        )
        
        status[TransportType.RELAY] = TransportStatus(
            connected = relayTransport?.connected ?: false
        )
        
        _transportStatus.value = status
    }
}
