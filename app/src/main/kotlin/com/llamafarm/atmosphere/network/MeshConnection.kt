package com.llamafarm.atmosphere.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "MeshConnection"

/**
 * Multi-path endpoints for mesh connectivity.
 * Supports local, public (internet), and relay fallback connections.
 */
data class MeshEndpoints(
    val local: String? = null,    // ws://192.168.x.x:11451 - same network
    val public: String? = null,   // ws://73.45.x.x:11451 - internet (requires port forward)
    val relay: String? = null     // wss://relay.atmosphere.io/mesh/xxx - fallback relay
) {
    /**
     * Get ordered list of endpoints to try.
     * Order: local (fastest) -> public -> relay (most reliable)
     */
    fun toOrderedList(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        local?.let { result.add("local" to it) }
        public?.let { result.add("public" to it) }
        relay?.let { result.add("relay" to it) }
        return result
    }
    
    companion object {
        /**
         * Parse endpoints from JSON object.
         */
        fun fromJson(json: JSONObject): MeshEndpoints {
            return MeshEndpoints(
                local = json.optString("local", null),
                public = json.optString("public", null),
                relay = json.optString("relay", null)
            )
        }
        
        /**
         * Create from a single endpoint (legacy support).
         */
        fun fromSingle(endpoint: String): MeshEndpoints {
            return MeshEndpoints(local = endpoint)
        }
    }
}

/**
 * Sealed class representing different mesh message types.
 */
sealed class MeshMessage {
    data class Joined(val meshName: String?) : MeshMessage()
    data class LlmResponse(val response: String, val requestId: String? = null) : MeshMessage()
    data class CostUpdate(val nodeId: String, val cost: Float) : MeshMessage()
    data class Ping(val timestamp: Long) : MeshMessage()
    data class MeshStatus(val peerCount: Int, val capabilities: List<String>) : MeshMessage()
    data class Error(val message: String) : MeshMessage()
    data class Unknown(val type: String, val raw: String) : MeshMessage()
}

/**
 * Connection state for the mesh WebSocket.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Pure Kotlin/OkHttp WebSocket client for mesh connectivity.
 * 
 * Connects to the Atmosphere mesh endpoint and handles:
 * - Join/authentication
 * - LLM request/response
 * - Cost gossip
 * - Mesh status updates
 */
class MeshConnection(
    private val endpoint: String,
    private val token: String
) {
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestCounter = AtomicInteger(0)
    
    // Pending LLM response callbacks by request ID
    private val pendingResponses = mutableMapOf<String, (String?, String?) -> Unit>()
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Mesh name after joining
    private val _meshName = MutableStateFlow<String?>(null)
    val meshName: StateFlow<String?> = _meshName.asStateFlow()
    
    // Message stream for subscribers
    private val _messages = MutableSharedFlow<MeshMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<MeshMessage> = _messages.asSharedFlow()
    
    // Error state
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)  // Fast fail for fallback
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    
    /**
     * Connect to the mesh endpoint.
     * 
     * @param onConnected Called when WebSocket opens (before join confirmation)
     * @param onJoined Called when mesh join is confirmed
     * @param onError Called on connection errors
     */
    fun connect(
        onConnected: (() -> Unit)? = null,
        onJoined: ((meshName: String?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        connectWithAuth(
            nodeId = null,
            meshToken = null,
            capabilities = emptyList(),
            onConnected = onConnected,
            onJoined = onJoined,
            onError = onError
        )
    }
    
    /**
     * Connect with full token-based authentication.
     * 
     * @param nodeId This node's ID
     * @param meshToken Signed token from mesh founder (null for legacy mode)
     * @param capabilities List of capabilities this node provides
     * @param onConnected Called when WebSocket opens
     * @param onJoined Called when mesh join is confirmed
     * @param onError Called on connection errors
     */
    fun connectWithAuth(
        nodeId: String?,
        meshToken: JSONObject?,
        capabilities: List<String> = emptyList(),
        nodeName: String? = null,
        onConnected: (() -> Unit)? = null,
        onJoined: ((meshName: String?) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null
        
        // Build WebSocket URL - ensure /api/ws suffix
        val wsUrl = buildWebSocketUrl(endpoint)
        Log.i(TAG, "Connecting to: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                onConnected?.invoke()
                
                // Send join message with token-based auth
                val joinMessage = JSONObject().apply {
                    put("type", "join")
                    nodeId?.let { put("node_id", it) }
                    
                    // Include signed token if provided
                    meshToken?.let { put("token", it) }
                    
                    // Legacy token fallback
                    if (meshToken == null && token.isNotEmpty()) {
                        put("token", token)
                    }
                    
                    if (capabilities.isNotEmpty()) {
                        put("capabilities", JSONArray(capabilities))
                    }
                    nodeName?.let { put("name", it) }
                }
                ws.send(joinMessage.toString())
                Log.d(TAG, "Sent join message with auth")
            }
            
            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleMessage(text, onJoined)
            }
            
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
                ws.close(1000, null)
            }
            
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                _meshName.value = null
            }
            
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Connection failed"
                Log.e(TAG, "WebSocket failure: $errorMsg", t)
                _connectionState.value = ConnectionState.FAILED
                _lastError.value = errorMsg
                onError?.invoke(errorMsg)
                
                scope.launch {
                    _messages.emit(MeshMessage.Error(errorMsg))
                }
                
                // Notify pending requests of failure
                synchronized(pendingResponses) {
                    pendingResponses.values.forEach { callback ->
                        callback(null, errorMsg)
                    }
                    pendingResponses.clear()
                }
            }
        })
    }
    
    /**
     * Build the WebSocket URL from the endpoint.
     */
    private fun buildWebSocketUrl(endpoint: String): String {
        var url = endpoint.trim()
        
        // Convert http(s) to ws(s)
        url = url.replace("http://", "ws://")
        url = url.replace("https://", "wss://")
        
        // Ensure ws:// prefix
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://$url"
        }
        
        // Add /api/ws if not present (but NOT for relay URLs which have /relay/ path)
        if (!url.contains("/relay/") && !url.endsWith("/api/ws") && !url.endsWith("/ws")) {
            url = url.trimEnd('/') + "/api/ws"
        }
        
        return url
    }
    
    /**
     * Handle incoming WebSocket messages.
     */
    private fun handleMessage(text: String, onJoined: ((String?) -> Unit)?) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "unknown")
            
            val message = when (type) {
                "joined" -> {
                    val meshName = json.optString("mesh", null)
                    _connectionState.value = ConnectionState.CONNECTED
                    _meshName.value = meshName
                    onJoined?.invoke(meshName)
                    MeshMessage.Joined(meshName)
                }
                
                "llm_response" -> {
                    val response = json.optString("response", "")
                    val requestId = json.optString("request_id", null)
                    
                    // Notify pending callback if any
                    requestId?.let { id ->
                        synchronized(pendingResponses) {
                            pendingResponses.remove(id)?.invoke(response, null)
                        }
                    }
                    
                    MeshMessage.LlmResponse(response, requestId)
                }
                
                "cost_update" -> {
                    val nodeId = json.optString("node_id", "unknown")
                    val cost = json.optDouble("cost", 1.0).toFloat()
                    MeshMessage.CostUpdate(nodeId, cost)
                }
                
                "ping" -> {
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    // Respond with pong
                    webSocket?.send(JSONObject().apply {
                        put("type", "pong")
                        put("timestamp", timestamp)
                    }.toString())
                    MeshMessage.Ping(timestamp)
                }
                
                "mesh_status" -> {
                    val data = json.optJSONObject("data")
                    val peerCount = data?.optInt("peer_count", 0) ?: 0
                    val capsArray = data?.optJSONArray("capabilities")
                    val capabilities = mutableListOf<String>()
                    capsArray?.let {
                        for (i in 0 until it.length()) {
                            capabilities.add(it.getString(i))
                        }
                    }
                    MeshMessage.MeshStatus(peerCount, capabilities)
                }
                
                "error" -> {
                    val errorMsg = json.optString("message", "Unknown error")
                    val requestId = json.optString("request_id", null)
                    
                    // Notify pending callback if any
                    requestId?.let { id ->
                        synchronized(pendingResponses) {
                            pendingResponses.remove(id)?.invoke(null, errorMsg)
                        }
                    }
                    
                    _lastError.value = errorMsg
                    MeshMessage.Error(errorMsg)
                }
                
                else -> {
                    MeshMessage.Unknown(type, text)
                }
            }
            
            // Emit to message stream
            scope.launch {
                _messages.emit(message)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }
    
    /**
     * Send an LLM request to the mesh.
     * 
     * @param prompt The prompt to send
     * @param model Optional model to use
     * @param onResponse Callback with (response, error)
     * @return Request ID
     */
    fun sendLlmRequest(
        prompt: String,
        model: String? = null,
        onResponse: ((response: String?, error: String?) -> Unit)? = null
    ): String {
        val requestId = "req-${requestCounter.incrementAndGet()}"
        
        if (_connectionState.value != ConnectionState.CONNECTED) {
            onResponse?.invoke(null, "Not connected to mesh")
            return requestId
        }
        
        // Register callback
        onResponse?.let {
            synchronized(pendingResponses) {
                pendingResponses[requestId] = it
            }
        }
        
        val message = JSONObject().apply {
            put("type", "llm_request")
            put("prompt", prompt)
            put("request_id", requestId)
            model?.let { put("model", it) }
        }
        
        val sent = webSocket?.send(message.toString()) ?: false
        if (!sent) {
            synchronized(pendingResponses) {
                pendingResponses.remove(requestId)
            }
            onResponse?.invoke(null, "Failed to send message")
        } else {
            Log.d(TAG, "Sent LLM request: $requestId")
        }
        
        return requestId
    }
    
    /**
     * Send a gossip message to the mesh.
     */
    fun sendGossip(topic: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", "gossip")
            put("topic", topic)
            put("data", data)
        }
        webSocket?.send(message.toString())
    }
    
    /**
     * Send an intent to be routed through the mesh.
     */
    fun sendIntent(intent: String, onResponse: ((Any?, String?) -> Unit)? = null): String {
        val requestId = "intent-${requestCounter.incrementAndGet()}"
        
        val message = JSONObject().apply {
            put("type", "intent")
            put("text", intent)
            put("request_id", requestId)
        }
        
        webSocket?.send(message.toString())
        return requestId
    }
    
    /**
     * Disconnect from the mesh.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from mesh")
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _meshName.value = null
        
        synchronized(pendingResponses) {
            pendingResponses.clear()
        }
    }
    
    /**
     * Check if connected.
     */
    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED
    val nodeId: String? get() = null  // TODO: Track assigned node ID
    val meshId: String? get() = _meshName.value
    val peerCount: Int get() = 0  // TODO: Track from mesh_status messages
    val isRelayConnected: Boolean get() = endpoint.contains("relay")
    
    /**
     * SDK-compatible join result.
     */
    data class JoinResult(
        val success: Boolean,
        val meshId: String? = null,
        val nodeId: String? = null,
        val error: String? = null
    )
    
    /**
     * SDK-compatible join method.
     */
    suspend fun join(meshId: String?, credentialsJson: String?): JoinResult {
        return try {
            // For now, we just check if already connected
            if (isConnected) {
                JoinResult(success = true, meshId = _meshName.value)
            } else {
                JoinResult(success = false, error = "Not connected. Call connect() first.")
            }
        } catch (e: Exception) {
            JoinResult(success = false, error = e.message)
        }
    }
    
    /**
     * SDK-compatible leave method.
     */
    fun leave() {
        disconnect()
    }
    
    companion object {
        /**
         * Connect to mesh with multi-endpoint fallback.
         * 
         * Tries endpoints in order: local -> public -> relay
         * Returns the first successful connection.
         * 
         * @param endpoints Multi-path endpoints to try
         * @param token Authentication token
         * @param connectionTimeoutMs Timeout per endpoint attempt
         * @param onConnected Called with (endpointType, meshName) on success
         * @param onProgress Called with (endpointType, status) during attempts
         * @param onError Called if all endpoints fail
         * @return MeshConnection if successful, null otherwise
         */
        suspend fun connectWithFallback(
            endpoints: MeshEndpoints,
            token: String,
            connectionTimeoutMs: Long = 10000,
            onConnected: ((endpointType: String, meshName: String?) -> Unit)? = null,
            onProgress: ((endpointType: String, status: String) -> Unit)? = null,
            onError: ((String) -> Unit)? = null
        ): MeshConnection? {
            val orderedEndpoints = endpoints.toOrderedList()
            
            if (orderedEndpoints.isEmpty()) {
                onError?.invoke("No endpoints available")
                return null
            }
            
            val errors = mutableListOf<String>()
            
            for ((endpointType, endpoint) in orderedEndpoints) {
                onProgress?.invoke(endpointType, "Trying $endpointType connection...")
                Log.i(TAG, "Attempting $endpointType connection to: $endpoint")
                
                try {
                    val connection = MeshConnection(endpoint, token)
                    
                    // Try to connect with timeout
                    val result = withTimeoutOrNull(connectionTimeoutMs) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            var resumed = false
                            
                            connection.connect(
                                onConnected = {
                                    // WebSocket opened, wait for join confirmation
                                },
                                onJoined = { meshName ->
                                    if (!resumed) {
                                        resumed = true
                                        continuation.resume(true)
                                        onConnected?.invoke(endpointType, meshName)
                                    }
                                },
                                onError = { error ->
                                    if (!resumed) {
                                        resumed = true
                                        continuation.resume(false)
                                        errors.add("$endpointType: $error")
                                    }
                                }
                            )
                            
                            continuation.invokeOnCancellation {
                                if (!resumed) {
                                    connection.disconnect()
                                }
                            }
                        }
                    }
                    
                    if (result == true) {
                        Log.i(TAG, "Successfully connected via $endpointType")
                        return connection
                    } else {
                        Log.w(TAG, "$endpointType connection failed")
                        connection.disconnect()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "$endpointType connection error: ${e.message}", e)
                    errors.add("$endpointType: ${e.message}")
                }
                
                // Small delay before trying next endpoint
                delay(500)
            }
            
            // All endpoints failed
            val errorMsg = if (errors.isNotEmpty()) {
                "All connection methods failed:\n${errors.joinToString("\n")}"
            } else {
                "All connection methods failed"
            }
            onError?.invoke(errorMsg)
            return null
        }
    }
}
