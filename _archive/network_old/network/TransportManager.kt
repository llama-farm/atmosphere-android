package com.llamafarm.atmosphere.network

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.transport.WifiDirectConnectParams
import com.llamafarm.atmosphere.transport.WifiDirectTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TransportManager"

/**
 * Transport types in priority order (lower = higher priority).
 */
enum class TransportType(val priority: Int) {
    LAN(1),          // Local network WebSocket - fastest
    WIFI_DIRECT(2),  // WiFi P2P - no router needed
    BLE_MESH(3),     // Bluetooth mesh - works offline
    MATTER(4),       // Smart home devices
    RELAY(5);        // Cloud relay - always works (fallback)
    
    companion object {
        fun fromString(s: String): TransportType? = values().find { 
            it.name.equals(s, ignoreCase = true) 
        }
    }
}

/**
 * Configuration for each transport.
 */
data class TransportConfig(
    val lan: LanConfig = LanConfig(),
    val wifiDirect: WifiDirectConfig = WifiDirectConfig(),
    val bleMesh: BleMeshConfig = BleMeshConfig(),
    val matter: MatterConfig = MatterConfig(),
    val relay: RelayConfig = RelayConfig()
) {
    data class LanConfig(
        val enabled: Boolean = true,
        val port: Int = 11450,
        val mdns: Boolean = true
    )
    
    data class WifiDirectConfig(
        val enabled: Boolean = true,
        val autoAccept: Boolean = false
    )
    
    data class BleMeshConfig(
        val enabled: Boolean = true,
        val advertising: Boolean = true,
        val scanning: Boolean = true,
        val maxHops: Int = 3
    )
    
    data class MatterConfig(
        val enabled: Boolean = true,
        val autoCommission: Boolean = false
    )
    
    data class RelayConfig(
        val enabled: Boolean = true,
        val url: String = "wss://atmosphere-relay-production.up.railway.app",
        val fallbackUrls: List<String> = emptyList()
    )
    
    fun isEnabled(type: TransportType): Boolean = when (type) {
        TransportType.LAN -> lan.enabled
        TransportType.WIFI_DIRECT -> wifiDirect.enabled
        TransportType.BLE_MESH -> bleMesh.enabled
        TransportType.MATTER -> matter.enabled
        TransportType.RELAY -> relay.enabled
    }
    
    companion object {
        fun fromJson(json: JSONObject): TransportConfig {
            return TransportConfig(
                lan = json.optJSONObject("lan")?.let { 
                    LanConfig(
                        enabled = it.optBoolean("enabled", true),
                        port = it.optInt("port", 11450),
                        mdns = it.optBoolean("mdns", true)
                    )
                } ?: LanConfig(),
                wifiDirect = json.optJSONObject("wifi_direct")?.let {
                    WifiDirectConfig(
                        enabled = it.optBoolean("enabled", true),
                        autoAccept = it.optBoolean("auto_accept", false)
                    )
                } ?: WifiDirectConfig(),
                relay = json.optJSONObject("relay")?.let {
                    RelayConfig(
                        enabled = it.optBoolean("enabled", true),
                        url = it.optString("url", "wss://atmosphere-relay-production.up.railway.app"),
                        fallbackUrls = it.optJSONArray("fallback_urls")?.let { arr ->
                            (0 until arr.length()).map { i -> arr.getString(i) }
                        } ?: emptyList()
                    )
                } ?: RelayConfig()
            )
        }
    }
}

/**
 * Metrics for a transport connection.
 */
data class TransportMetrics(
    val samples: MutableList<Float> = mutableListOf(),
    var successes: Int = 0,
    var failures: Int = 0,
    var lastLatencyMs: Float = 0f,
    var lastUpdated: Long = System.currentTimeMillis()
) {
    val avgLatencyMs: Float
        get() = if (samples.isEmpty()) Float.MAX_VALUE else samples.takeLast(10).average().toFloat()
    
    val successRate: Float
        get() {
            val total = successes + failures
            return if (total == 0) 1f else successes.toFloat() / total
        }
    
    fun addSample(latencyMs: Float, success: Boolean) {
        samples.add(latencyMs)
        if (samples.size > 100) samples.removeAt(0)
        lastLatencyMs = latencyMs
        lastUpdated = System.currentTimeMillis()
        if (success) successes++ else failures++
    }
    
    fun score(): Float {
        val latencyScore = maxOf(0f, 100f - avgLatencyMs)
        val reliabilityScore = successRate * 100f
        return latencyScore * 0.6f + reliabilityScore * 0.4f
    }
}

/**
 * Abstract transport interface.
 */
interface Transport {
    val type: TransportType
    val connected: Boolean
    val metrics: TransportMetrics
    
    suspend fun connect(config: Any): Boolean
    suspend fun disconnect()
    suspend fun send(message: ByteArray): Boolean
    fun onMessage(handler: (ByteArray) -> Unit)
    
    suspend fun probe(): Float {
        val start = System.currentTimeMillis()
        return try {
            send("""{"type":"ping"}""".toByteArray())
            val latency = (System.currentTimeMillis() - start).toFloat()
            metrics.addSample(latency, true)
            latency
        } catch (e: Exception) {
            metrics.addSample(Float.MAX_VALUE, false)
            Float.MAX_VALUE
        }
    }
}

/**
 * Signed token for mesh authentication.
 */
data class MeshToken(
    val meshId: String,
    val nodeId: String?,
    val issuedAt: Long,
    val expiresAt: Long,
    val capabilities: List<String>,
    val issuerId: String,
    val nonce: String,
    val signature: String
) {
    fun isExpired(): Boolean = System.currentTimeMillis() / 1000 > expiresAt
    
    fun toJson(): JSONObject = JSONObject().apply {
        put("mesh_id", meshId)
        put("node_id", nodeId)
        put("issued_at", issuedAt)
        put("expires_at", expiresAt)
        put("capabilities", JSONArray(capabilities))
        put("issuer_id", issuerId)
        put("nonce", nonce)
        put("signature", signature)
    }
    
    companion object {
        fun fromJson(json: JSONObject): MeshToken {
            val capsArray = json.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = (0 until capsArray.length()).map { capsArray.getString(it) }
            
            return MeshToken(
                meshId = json.getString("mesh_id"),
                nodeId = json.optString("node_id", null),
                issuedAt = json.getLong("issued_at"),
                expiresAt = json.getLong("expires_at"),
                capabilities = capabilities,
                issuerId = json.getString("issuer_id"),
                nonce = json.getString("nonce"),
                signature = json.getString("signature")
            )
        }
    }
}

/**
 * Mesh invite containing token + connection info.
 */
data class MeshInvite(
    val token: MeshToken,
    val meshName: String,
    val endpoints: List<String>,
    val meshPublicKey: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("token", token.toJson())
        put("mesh_name", meshName)
        put("endpoints", JSONArray(endpoints))
        put("mesh_public_key", meshPublicKey)
    }
    
    companion object {
        fun fromJson(json: JSONObject): MeshInvite {
            val endpointsArray = json.optJSONArray("endpoints") ?: JSONArray()
            val endpoints = (0 until endpointsArray.length()).map { endpointsArray.getString(it) }
            
            return MeshInvite(
                token = MeshToken.fromJson(json.getJSONObject("token")),
                meshName = json.getString("mesh_name"),
                endpoints = endpoints,
                meshPublicKey = json.getString("mesh_public_key")
            )
        }
        
        fun decode(encoded: String): MeshInvite {
            val padding = (4 - encoded.length % 4) % 4
            val padded = encoded + "=".repeat(padding)
            val json = String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE))
            return fromJson(JSONObject(json))
        }
    }
}

/**
 * Connection pool for a single peer with multiple transports.
 */
class ConnectionPool(val peerId: String) {
    private val transports = ConcurrentHashMap<TransportType, Transport>()
    private var preferred: TransportType? = null
    
    suspend fun send(message: ByteArray): Boolean {
        // Try preferred first
        preferred?.let { pref ->
            transports[pref]?.let { transport ->
                if (transport.connected) {
                    try {
                        if (transport.send(message)) return true
                    } catch (e: Exception) {
                        Log.w(TAG, "Preferred transport $pref failed: ${e.message}")
                    }
                }
            }
        }
        
        // Fallback chain by priority
        for (type in TransportType.values().sortedBy { it.priority }) {
            val transport = transports[type]
            if (transport != null && transport.connected) {
                try {
                    if (transport.send(message)) {
                        preferred = type
                        return true
                    }
                } catch (e: Exception) {
                    // Try next transport
                }
            }
        }
        
        return false
    }
    
    fun addTransport(transport: Transport) {
        transports[transport.type] = transport
        if (preferred == null) preferred = transport.type
    }
    
    fun getBestTransport(): Transport? {
        var bestScore = -1f
        var best: Transport? = null
        
        for (transport in transports.values) {
            if (transport.connected) {
                val score = transport.metrics.score()
                if (score > bestScore) {
                    bestScore = score
                    best = transport
                }
            }
        }
        
        return best
    }
    
    suspend fun disconnectAll() {
        for (transport in transports.values) {
            transport.disconnect()
        }
        transports.clear()
        preferred = null
    }
}

/**
 * Orchestrates all transports for multi-path mesh connectivity.
 */
class TransportManager(
    private val context: Context,
    private val nodeId: String,
    private val meshId: String,
    private val config: TransportConfig = TransportConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pools = ConcurrentHashMap<String, ConnectionPool>()
    private var messageHandler: ((String, ByteArray) -> Unit)? = null
    
    private var relayTransport: RelayTransport? = null
    private var wifiDirectTransport: WifiDirectTransport? = null
    private var probeJob: Job? = null
    
    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()
    
    private val _transportStatus = MutableStateFlow<Map<TransportType, Boolean>>(emptyMap())
    val transportStatus: StateFlow<Map<TransportType, Boolean>> = _transportStatus.asStateFlow()
    
    fun onMessage(handler: (fromPeerId: String, message: ByteArray) -> Unit) {
        messageHandler = handler
    }
    
    /**
     * Start transport manager and connect to mesh.
     */
    suspend fun start(
        token: MeshToken? = null,
        isFounder: Boolean = false,
        meshPublicKey: String? = null,
        founderProof: String? = null,
        capabilities: List<String> = emptyList()
    ): Boolean {
        _connectionState.value = ConnectionState.CONNECTING
        
        var anyConnected = false
        
        // Start WiFi Direct transport (Priority 2)
        if (config.isEnabled(TransportType.WIFI_DIRECT)) {
            val wifiDirectSuccess = startWifiDirect(isFounder)
            if (wifiDirectSuccess) {
                anyConnected = true
                Log.i(TAG, "WiFi Direct transport started")
            }
        }
        
        // Start relay (always enabled as fallback - Priority 5)
        if (config.isEnabled(TransportType.RELAY)) {
            val relaySuccess = startRelay(
                token = token,
                isFounder = isFounder,
                meshPublicKey = meshPublicKey,
                founderProof = founderProof,
                capabilities = capabilities
            )
            
            if (relaySuccess) {
                anyConnected = true
                Log.i(TAG, "Relay transport started")
            }
        }
        
        if (anyConnected) {
            _connectionState.value = ConnectionState.CONNECTED
            updateTransportStatus()
            
            // Start probe loop
            probeJob = scope.launch { probeLoop() }
            
            Log.i(TAG, "TransportManager started for mesh $meshId")
            return true
        }
        
        _connectionState.value = ConnectionState.FAILED
        return false
    }
    
    /**
     * Start WiFi Direct transport.
     */
    private suspend fun startWifiDirect(isFounder: Boolean): Boolean {
        if (!WifiDirectTransport.isSupported(context)) {
            Log.w(TAG, "WiFi Direct not supported on this device")
            return false
        }
        
        val transport = WifiDirectTransport(
            context = context,
            nodeId = nodeId,
            config = config.wifiDirect
        )
        
        if (!transport.initialize()) {
            Log.e(TAG, "Failed to initialize WiFi Direct transport")
            return false
        }
        
        transport.onMessage { data ->
            try {
                val json = JSONObject(String(data))
                val fromPeer = json.optString("from", json.optString("node_id", "wifi-direct"))
                messageHandler?.invoke(fromPeer, data)
            } catch (e: Exception) {
                // Not JSON, deliver with generic peer ID
                messageHandler?.invoke("wifi-direct", data)
            }
        }
        
        transport.onPeerConnected = { peerId ->
            Log.i(TAG, "WiFi Direct peer connected: $peerId")
            updateTransportStatus()
        }
        
        transport.onPeerDisconnected = { peerId ->
            Log.i(TAG, "WiFi Direct peer disconnected: $peerId")
            updateTransportStatus()
        }
        
        // Connect to mesh - create group if founder, otherwise discover
        val success = transport.connectToMesh(
            meshId = meshId,
            createGroup = isFounder
        )
        
        if (success) {
            wifiDirectTransport = transport
        }
        
        return success
    }
    
    /**
     * Stop all transports.
     */
    suspend fun stop() {
        probeJob?.cancel()
        
        // Disconnect all peer pools
        for (pool in pools.values) {
            pool.disconnectAll()
        }
        pools.clear()
        
        // Stop WiFi Direct transport
        wifiDirectTransport?.stop()
        wifiDirectTransport = null
        
        // Stop relay
        relayTransport?.disconnect()
        relayTransport = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPeers.value = emptyList()
        
        Log.i(TAG, "TransportManager stopped")
    }
    
    /**
     * Send message to a specific peer.
     */
    suspend fun send(peerId: String, message: ByteArray): Boolean {
        return pools[peerId]?.send(message) ?: false
    }
    
    /**
     * Broadcast to all connected peers.
     */
    suspend fun broadcast(message: ByteArray): Int {
        var sent = 0
        for (pool in pools.values) {
            if (pool.send(message)) sent++
        }
        return sent
    }
    
    /**
     * Send via relay (broadcast to mesh).
     */
    suspend fun sendViaRelay(message: ByteArray): Boolean {
        return relayTransport?.send(message) ?: false
    }
    
    private suspend fun startRelay(
        token: MeshToken?,
        isFounder: Boolean,
        meshPublicKey: String?,
        founderProof: String?,
        capabilities: List<String>
    ): Boolean {
        val transport = RelayTransport(config.relay)
        
        transport.onMessage { data ->
            try {
                val json = JSONObject(String(data))
                val fromPeer = json.optString("from", "unknown")
                messageHandler?.invoke(fromPeer, data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse relay message: ${e.message}")
            }
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
                nodeName = "android-${nodeId.take(8)}"
            )
        )
        
        if (success) {
            relayTransport = transport
            Log.i(TAG, "Relay transport connected")
        }
        
        return success
    }
    
    private suspend fun probeLoop() {
        while (true) {
            delay(30_000) // Probe every 30 seconds
            
            try {
                // Probe relay
                relayTransport?.probe()
                
                // Probe WiFi Direct
                wifiDirectTransport?.probe()
                
                // Probe peer connections
                for (pool in pools.values) {
                    pool.getBestTransport()?.probe()
                }
                
                updateTransportStatus()
                _connectedPeers.value = pools.keys.toList()
                
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Probe error: ${e.message}")
            }
        }
    }
    
    private fun updateTransportStatus() {
        val status = mutableMapOf<TransportType, Boolean>()
        status[TransportType.RELAY] = relayTransport?.connected ?: false
        status[TransportType.WIFI_DIRECT] = wifiDirectTransport?.connected ?: false
        _transportStatus.value = status
    }
    
    /**
     * Send via WiFi Direct transport.
     */
    suspend fun sendViaWifiDirect(message: ByteArray): Boolean {
        return wifiDirectTransport?.send(message) ?: false
    }
    
    /**
     * Get WiFi Direct transport state.
     */
    fun getWifiDirectState() = wifiDirectTransport?.connectionState
    
    /**
     * Get discovered WiFi Direct peers.
     */
    fun getWifiDirectPeers() = wifiDirectTransport?.discoveredPeers
}

/**
 * Parameters for relay connection.
 */
data class RelayConnectParams(
    val meshId: String,
    val nodeId: String,
    val token: MeshToken?,
    val isFounder: Boolean,
    val meshPublicKey: String?,
    val founderProof: String?,
    val capabilities: List<String>,
    val nodeName: String
)

/**
 * Relay transport implementation.
 */
class RelayTransport(private val config: TransportConfig.RelayConfig) : Transport {
    override val type = TransportType.RELAY
    override var connected = false
    override val metrics = TransportMetrics()
    
    private var connection: MeshConnection? = null
    private var messageHandler: ((ByteArray) -> Unit)? = null
    
    override fun onMessage(handler: (ByteArray) -> Unit) {
        messageHandler = handler
    }
    
    suspend fun connect(params: RelayConnectParams): Boolean {
        val endpoint = "${config.url}/relay/${params.meshId}"
        
        // Build the connection with token auth
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            
            connection = MeshConnection(endpoint, "").apply {
                // Custom connect that handles token auth
            }
            
            // For now, use existing MeshConnection but send proper auth
            scope.launch {
                try {
                    // Connect via WebSocket
                    val ws = connectWebSocket(endpoint)
                    if (ws == null) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(false) {}
                        }
                        return@launch
                    }
                    
                    // Send registration/join message
                    val authMsg = if (params.isFounder && params.meshPublicKey != null) {
                        JSONObject().apply {
                            put("type", "register_mesh")
                            put("mesh_id", params.meshId)
                            put("node_id", params.nodeId)
                            put("mesh_public_key", params.meshPublicKey)
                            put("founder_proof", params.founderProof)
                            put("name", params.meshId.take(8))
                            put("display_name", params.nodeName)
                            put("capabilities", JSONArray(params.capabilities))
                        }
                    } else {
                        JSONObject().apply {
                            put("type", "join")
                            put("mesh_id", params.meshId)
                            put("node_id", params.nodeId)
                            params.token?.let { put("token", it.toJson()) }
                            put("capabilities", JSONArray(params.capabilities))
                            put("name", params.nodeName)
                        }
                    }
                    
                    // Send auth and wait for response
                    ws.send(authMsg.toString())
                    
                    // Wait for confirmation
                    // This would be handled in the WebSocket listener
                    connected = true
                    
                    if (!resumed) {
                        resumed = true
                        continuation.resume(true) {}
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Relay connect failed: ${e.message}", e)
                    if (!resumed) {
                        resumed = true
                        continuation.resume(false) {}
                    }
                }
            }
            
            continuation.invokeOnCancellation {
                if (!resumed) {
                    connection?.disconnect()
                }
            }
        }
    }
    
    private suspend fun connectWebSocket(endpoint: String): okhttp3.WebSocket? {
        // Use existing MeshConnection infrastructure
        return null // Placeholder - actual implementation uses OkHttp
    }
    
    override suspend fun connect(config: Any): Boolean {
        return if (config is RelayConnectParams) connect(config) else false
    }
    
    override suspend fun disconnect() {
        connection?.disconnect()
        connection = null
        connected = false
    }
    
    override suspend fun send(message: ByteArray): Boolean {
        if (!connected) return false
        
        try {
            val json = JSONObject(String(message))
            connection?.sendGossip("broadcast", json)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Relay send failed: ${e.message}")
            return false
        }
    }
    
    companion object {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
