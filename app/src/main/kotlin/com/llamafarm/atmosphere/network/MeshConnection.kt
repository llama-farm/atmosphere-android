package com.llamafarm.atmosphere.network

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.core.CapabilityAnnouncement
import com.llamafarm.atmosphere.core.GossipManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MeshConnection"

/**
 * Simple WebSocket-based mesh connection.
 * 
 * Connects to relay server and:
 * - Sends/receives capability announcements (gossip protocol)
 * - Routes inference requests to appropriate nodes
 * - Handles mesh-level communication
 * 
 * This is the simplified "just works" transport layer.
 */
class MeshConnection(
    private val context: Context,
    private val relayUrl: String = "ws://relay.atmosphere.io/mesh",
    private val relayToken: String = ""
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gossipManager = GossipManager.getInstance(context)
    
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _messages = MutableSharedFlow<MeshMessage>()
    val messages: SharedFlow<MeshMessage> = _messages.asSharedFlow()
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        // NOTE: Don't use WebSocket-level pingInterval - relay uses application-level pings
        .build()
    
    private var pingJob: Job? = null
    
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(25_000)  // Send ping every 25 seconds
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    sendMessage(JSONObject().apply { put("type", "ping") })
                }
            }
        }
    }
    
    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }
    
    init {
        // Configure gossip manager to send messages via this connection
        gossipManager.setMessageSender { message ->
            sendMessage(message)
        }
    }
    
    /**
     * Connect to relay server.
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connected or connecting")
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        val request = Request.Builder()
            .url(relayUrl)
            .addHeader("X-Node-ID", gossipManager.nodeId)
            .addHeader("X-Node-Name", gossipManager.nodeName)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to relay: $relayUrl")
                _connectionState.value = ConnectionState.CONNECTED
                
                // Start broadcasting capabilities
                gossipManager.startBroadcasting()
                
                // Start application-level ping loop (relay doesn't respond to WebSocket pings)
                startPingLoop()
                
                // Send initial join message with token
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("node_id", gossipManager.nodeId)
                    put("name", gossipManager.nodeName)
                    // Parse and include token if available
                    if (relayToken.isNotEmpty()) {
                        try {
                            val tokenJson = JSONObject(relayToken)
                            // If token is wrapped in "token" field, extract it
                            val actualToken = if (tokenJson.has("token")) {
                                tokenJson.getJSONObject("token")
                            } else {
                                tokenJson
                            }
                            put("token", actualToken)
                            Log.i(TAG, "🔑 Sending token with keys: ${actualToken.keys().asSequence().toList()}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse token: ${e.message}")
                        }
                    }
                    put("capabilities", org.json.JSONArray())
                }
                Log.i(TAG, "📤 Sending JOIN: $joinMsg")
                sendMessage(joinMsg)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.FAILED
                stopPingLoop()
                
                scope.launch {
                    _messages.emit(MeshMessage.Error("Connection failed", t))
                }
                
                scheduleReconnect()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopPingLoop()
                gossipManager.stopBroadcasting()
                scheduleReconnect()
            }
        })
    }
    
    /**
     * Disconnect from relay.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingLoop()
        gossipManager.stopBroadcasting()
        
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        
        Log.i(TAG, "Disconnected from relay")
    }
    
    /**
     * Send a message to the relay.
     */
    /**
     * Send a broadcast message through the WebSocket (relay or LAN).
     * Used for cross-transport bridging.
     * Returns true if sent.
     */
    fun sendBroadcast(payload: JSONObject): Boolean {
        val ws = webSocket ?: return false
        val msg = JSONObject().apply {
            put("type", "broadcast")
            put("payload", payload)
        }
        return ws.send(msg.toString())
    }
    
    fun sendMessage(message: JSONObject) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send message: not connected")
            return
        }
        
        val json = message.toString()
        val success = ws.send(json)
        
        if (!success) {
            Log.w(TAG, "Failed to send message (buffer full?)")
        }
    }
    
    /**
     * Send inference request to a specific node.
     * Uses "broadcast" type with target_node for relay to forward to specific peer.
     */
    fun sendInferenceRequest(
        targetNodeId: String,
        capabilityId: String,
        requestId: String,
        payload: JSONObject
    ) {
        // Build the inner payload with the actual inference request
        val innerPayload = JSONObject().apply {
            put("type", "inference_request")
            put("node_id", gossipManager.nodeId)  // Source node
            put("target_node", targetNodeId)
            put("capability_id", capabilityId)
            put("request_id", requestId)
            put("prompt", payload.optString("prompt", ""))
            if (payload.has("model")) put("model", payload.optString("model"))
        }
        
        // Use broadcast type - relay will forward to all peers including target
        val relayMessage = JSONObject().apply {
            put("type", "broadcast")
            put("payload", innerPayload)
        }
        
        Log.d(TAG, "📤 Sending inference request to $targetNodeId via relay broadcast")
        sendMessage(relayMessage)
    }
    
    /**
     * Handle incoming message from relay.
     */
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "unknown")
            
            // Log ALL incoming messages for debugging
            Log.i(TAG, "🔵 WS received: type=$type (${text.length} bytes)")
            
            when (type) {
                "capability_announce" -> {
                    // Another node is announcing capabilities
                    val nodeId = json.getString("node_id")
                    gossipManager.handleAnnouncement(nodeId, json)
                    
                    val capList = extractCapabilityLabels(json)
                    scope.launch {
                        _messages.emit(MeshMessage.CapabilityAnnounce(nodeId, json, capList, nodeId))
                    }
                }
                
                "inference_request" -> {
                    // Someone wants to run inference on this node
                    val requestId = json.getString("request_id")
                    val targetNodeId = json.getString("target_node_id")
                    val payload = json.getJSONObject("payload")
                    
                    // Only handle if targeted at us
                    if (targetNodeId == gossipManager.nodeId) {
                        scope.launch {
                            _messages.emit(MeshMessage.InferenceRequest(requestId, targetNodeId, payload))
                        }
                    }
                }
                
                "inference_response" -> {
                    // Response to our inference request
                    val requestId = json.getString("request_id")
                    val payload = json.getJSONObject("payload")
                    
                    scope.launch {
                        _messages.emit(MeshMessage.InferenceResponse(requestId, payload))
                    }
                }
                
                "ping" -> {
                    // Relay keepalive - respond with pong
                    sendMessage(JSONObject().apply {
                        put("type", "pong")
                    })
                }
                
                "message" -> {
                    // Relay forwards all peer messages wrapped as type: "message"
                    // The actual content is in "payload"
                    val fromNode = json.optString("from", "unknown")
                    val payload = json.optJSONObject("payload")
                    
                    if (payload != null) {
                        val payloadType = payload.optString("type", "")
                        Log.i(TAG, "📨 Received message from $fromNode: type=$payloadType (payload keys: ${payload.keys().asSequence().toList().take(5)})")
                        
                        when (payloadType) {
                            "gossip.announce", "capability.announce", "capability_announce" -> {
                                // Capability announcement via gossip (all formats)
                                val nodeId = payload.optString("node_id", fromNode)
                                val capabilities = payload.optJSONArray("capabilities")
                                Log.i(TAG, "🔔 Gossip announcement from $nodeId with ${capabilities?.length() ?: 0} capabilities")
                                
                                if (capabilities != null && capabilities.length() > 0) {
                                    gossipManager.handleAnnouncement(nodeId, payload)
                                    val capList = extractCapabilityLabels(payload)
                                    scope.launch {
                                        _messages.emit(MeshMessage.CapabilityAnnounce(nodeId, payload, capList, fromNode))
                                    }
                                }
                            }
                            
                            "llm_response", "chat_response" -> {
                                // LLM response routed through relay
                                val requestId = payload.optString("request_id", "")
                                Log.i(TAG, "📥 LLM response received for request: $requestId")
                                scope.launch {
                                    _messages.emit(MeshMessage.InferenceResponse(requestId, payload))
                                }
                            }
                            
                            else -> {
                                Log.d(TAG, "Unhandled payload type: $payloadType")
                            }
                        }
                    }
                }
                
                "joined" -> {
                    Log.i(TAG, "✅ Joined mesh via relay")
                    val meshName = json.optString("mesh", "unknown")
                    val nodeCount = json.optInt("node_count", 0)
                    scope.launch {
                        _messages.emit(MeshMessage.Joined(meshName, sourceNodeId = null))
                    }
                }
                
                "peers" -> {
                    // Parse peer list from relay
                    val peersArray = json.optJSONArray("peers")
                    val peerList = mutableListOf<com.llamafarm.atmosphere.network.RelayPeer>()
                    if (peersArray != null) {
                        for (i in 0 until peersArray.length()) {
                            val p = peersArray.getJSONObject(i)
                            val caps = mutableListOf<String>()
                            p.optJSONArray("capabilities")?.let { arr ->
                                for (j in 0 until arr.length()) caps.add(arr.getString(j))
                            }
                            peerList.add(RelayPeer(
                                nodeId = p.getString("node_id"),
                                name = p.optString("name", p.getString("node_id").take(8)),
                                capabilities = caps,
                                connected = true
                            ))
                        }
                    }
                    Log.i(TAG, "📋 Peers: ${peerList.size} (${peerList.map { it.name }})")
                    scope.launch {
                        _messages.emit(MeshMessage.PeerList(peerList))
                    }
                }
                
                "peer_joined" -> {
                    val peerNodeId = json.optString("node_id", "unknown")
                    val peerName = json.optString("name", peerNodeId.take(8))
                    Log.i(TAG, "👋 Peer joined: $peerName ($peerNodeId)")
                }
                
                "peer_left" -> {
                    val peerNodeId = json.optString("node_id", "unknown")
                    Log.i(TAG, "👋 Peer left: $peerNodeId")
                }

                "error" -> {
                    val code = json.optString("code", "UNKNOWN")
                    val msg = json.optString("message", "No message")
                    Log.e(TAG, "❌ RELAY ERROR: $code - $msg")
                    scope.launch {
                        _messages.emit(MeshMessage.Error(msg, Exception("Relay error: $code")))
                    }
                }
                
                else -> {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }
    
    /**
     * Schedule reconnection attempt.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return  // Already scheduled
        }
        
        _connectionState.value = ConnectionState.RECONNECTING
        
        reconnectJob = scope.launch {
            var delay = 5000L  // Start with 5 seconds
            val maxDelay = 60000L  // Cap at 1 minute
            
            while (isActive && _connectionState.value != ConnectionState.CONNECTED) {
                Log.i(TAG, "Reconnecting in ${delay/1000}s...")
                delay(delay)
                
                try {
                    connect()
                    
                    // Wait to see if connection succeeds
                    delay(2000)
                    
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        Log.i(TAG, "Reconnection successful")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnection attempt failed: ${e.message}")
                }
                
                // Exponential backoff
                delay = (delay * 2).coerceAtMost(maxDelay)
            }
        }
    }
    
    /**
     * Get current connection stats.
     */
    /**
     * Extract capability labels from a gossip announcement JSON.
     * Handles both flat and nested capability formats.
     */
    private fun extractCapabilityLabels(json: JSONObject): List<String> {
        val result = mutableListOf<String>()
        val caps = json.optJSONArray("capabilities") ?: return result
        for (i in 0 until caps.length()) {
            try {
                val item = caps.get(i)
                when (item) {
                    is JSONObject -> {
                        // Full capability object — extract label, id, or description
                        val label = item.optString("label", "")
                            .ifEmpty { item.optString("id", "") }
                            .ifEmpty { item.optString("name", "") }
                            .ifEmpty { item.optString("description", "capability-$i") }
                        result.add(label)
                    }
                    is String -> result.add(item)
                    else -> result.add(item.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract capability at index $i: ${e.message}")
            }
        }
        return result
    }
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "state" to _connectionState.value.name,
            "relay_url" to relayUrl,
            "node_id" to gossipManager.nodeId,
            "node_name" to gossipManager.nodeName
        ) + gossipManager.getStats()
    }
}
