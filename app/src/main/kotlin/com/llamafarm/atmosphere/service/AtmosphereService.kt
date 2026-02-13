package com.llamafarm.atmosphere.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.MainActivity
import com.llamafarm.atmosphere.R
import com.llamafarm.atmosphere.bindings.AtmosphereNode
import com.llamafarm.atmosphere.bindings.AtmosphereException
import com.llamafarm.atmosphere.capabilities.CameraCapability
import com.llamafarm.atmosphere.capabilities.VoiceCapability
import com.llamafarm.atmosphere.capabilities.MeshCapabilityHandler
import com.llamafarm.atmosphere.cost.CostBroadcaster
import com.llamafarm.atmosphere.cost.CostCollector
import com.llamafarm.atmosphere.cost.CostFactors
import com.llamafarm.atmosphere.data.AtmospherePreferences
import com.llamafarm.atmosphere.discovery.LanDiscovery
// MeshConnection/relay imports removed — CRDT mesh is sole transport
// import com.llamafarm.atmosphere.network.MeshConnection
// import com.llamafarm.atmosphere.network.ConnectionState
// import com.llamafarm.atmosphere.network.MeshMessage
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.router.RouteConstraints
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.core.AtmosphereNative
// ClientServer removed — AIDL binder replaces TCP for Android IPC
import org.json.JSONArray as JsonArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified peer info for UI display.
 */
data class SimplePeerInfo(
    val peerId: String,
    val state: String,
    val transports: List<String>
)

/**
 * Foreground service that keeps the Atmosphere node running.
 * Handles mesh network operations and maintains connectivity.
 */
class AtmosphereService : Service() {

    companion object {
        private const val TAG = "AtmosphereService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "Atmosphere:ServiceWakeLock"

        /**
         * Start the Atmosphere service.
         */
        fun start(context: Context) {
            val intent = Intent(context, AtmosphereService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the Atmosphere service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, AtmosphereService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        // Service actions
        const val ACTION_START = "com.llamafarm.atmosphere.action.START"
        const val ACTION_STOP = "com.llamafarm.atmosphere.action.STOP"
    }

    // Service state
    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    // Binder for local clients
    inner class LocalBinder : Binder() {
        fun getService(): AtmosphereService = this@AtmosphereService
    }
    
    // Public accessors for BinderService / SDK
    fun isConnected(): Boolean = atmosphereHandle != 0L && getPeerCount() > 0
    fun getNodeId(): String? = _nodeId.value
    fun getMeshId(): String = "atmosphere-playground-mesh-v1" // Hardcoded for now
    fun getAtmosphereHandle(): Long = atmosphereHandle
    fun getCrdtPeerCount(): Int = getPeerCount()
    
    /**
     * Get wrapped CRDT core for legacy code compatibility.
     * Returns null if core not initialized.
     */
    fun getAtmosphereCore(): com.llamafarm.atmosphere.core.CrdtCoreWrapper? {
        return if (atmosphereHandle != 0L) {
            com.llamafarm.atmosphere.core.CrdtCoreWrapper(atmosphereHandle)
        } else {
            null
        }
    }
    
    private fun getPeerCount(): Int {
        if (atmosphereHandle == 0L) return 0
        return try {
            val peersJson = AtmosphereNative.peers(atmosphereHandle)
            val peers = JSONArray(peersJson)
            peers.length()
        } catch (e: Exception) {
            0
        }
    }
    
    // Helper methods for UI to access CRDT via JNI
    fun insertCrdtDocument(collection: String, docId: String, data: Map<String, Any>) {
        if (atmosphereHandle == 0L) return
        try {
            val json = JSONObject(data).toString()
            AtmosphereNative.insert(atmosphereHandle, collection, docId, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert CRDT document", e)
        }
    }
    
    fun queryCrdtCollection(collection: String): List<Map<String, Any>> {
        if (atmosphereHandle == 0L) return emptyList()
        return try {
            val json = AtmosphereNative.query(atmosphereHandle, collection)
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                buildMap<String, Any> {
                    obj.keys().forEach { key ->
                        put(key, obj.get(key))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query CRDT collection", e)
            emptyList()
        }
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Native Atmosphere node
    private var nativeNode: AtmosphereNode? = null
    
    // Cost monitoring
    private var costCollector: CostCollector? = null
    private var costBroadcaster: CostBroadcaster? = null
    
    // Capabilities
    private var cameraCapability: CameraCapability? = null
    private var voiceCapability: VoiceCapability? = null
    private var meshCapabilityHandler: MeshCapabilityHandler? = null
    
    // Relay mesh connection removed — CRDT mesh is sole transport
    
    // LAN discovery (mDNS/NSD)
    private var lanDiscovery: LanDiscovery? = null
    // Mesh ID is now hardcoded (no saved meshes)
    
    // BLE mesh transport removed - will be added back as Rust transport in atmosphere-core
    
    // Transport bridge: dedup cache for cross-transport forwarding
    private val seenNonces = java.util.LinkedHashMap<String, Long>(100, 0.75f, true)
    private val SEEN_NONCES_MAX = 500
    
    // Relay URL removed — CRDT mesh handles connectivity
    
    // Rust core handle (JNI)
    private var atmosphereHandle: Long = 0
    
    // Peer state exposed to UI
    private val _crdtPeers = MutableStateFlow<List<SimplePeerInfo>>(emptyList())
    val crdtPeers: StateFlow<List<SimplePeerInfo>> = _crdtPeers.asStateFlow()
    
    // Semantic router for capability matching
    private var semanticRouter: SemanticRouter? = null
    
    // Local inference engine
    private var localInferenceEngine: LocalInferenceEngine? = null
    
    // Sensor request handler for responding to mesh sensor requests
    private var sensorRequestHandler: com.llamafarm.atmosphere.capabilities.SensorRequestHandler? = null
    
    // Pending inference requests: requestId -> callback
    private val pendingRequests = ConcurrentHashMap<String, (String?, String?) -> Unit>()
    
    // Persistence
    private lateinit var preferences: AtmospherePreferences

    // Observable state
    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow(0)
    val connectedPeers: StateFlow<Int> = _connectedPeers.asStateFlow()
    
    // Connection state derived from CRDT mesh
    private val _activeTransport = MutableStateFlow<String?>("crdt")
    val activeTransport: StateFlow<String?> = _activeTransport.asStateFlow()

    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId.asStateFlow()
    
    private val _currentCost = MutableStateFlow<Float?>(null)
    val currentCost: StateFlow<Float?> = _currentCost.asStateFlow()

    // Mesh events for UI display (gossip, routing, peer events)
    data class MeshEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val type: String,  // "gossip", "route", "peer", "error", "lan"
        val title: String,
        val detail: String = "",
        val nodeId: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )
    private val _meshEvents = MutableStateFlow<List<MeshEvent>>(emptyList())
    val meshEvents: StateFlow<List<MeshEvent>> = _meshEvents.asStateFlow()

    private fun addMeshEvent(event: MeshEvent) {
        val current = _meshEvents.value.toMutableList()
        current.add(0, event)
        _meshEvents.value = current.take(50)
    }
    
    private val _costFactors = MutableStateFlow<CostFactors?>(null)
    val costFactors: StateFlow<CostFactors?> = _costFactors.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize persistence
        preferences = AtmospherePreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNode()
            ACTION_STOP -> stopNode()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNode()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun startNode() {
        if (_state.value == ServiceState.RUNNING || _state.value == ServiceState.STARTING) {
            Log.d(TAG, "Node already running or starting")
            return
        }

        _state.value = ServiceState.STARTING
        Log.i(TAG, "Starting Atmosphere node...")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Acquire wake lock
        acquireWakeLock()

        // Initialize node in background
        serviceScope.launch {
            try {
                // Initialize native node
                initializeNode()

                _state.value = ServiceState.RUNNING
                updateNotification("Connected • 0 peers")
                Log.i(TAG, "Node started successfully")
                
                // Start LAN discovery (mDNS) - for UI display only
                // Rust core handles actual UDP discovery and connections
                startLanDiscovery()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start node", e)
                _state.value = ServiceState.STOPPED
                stopSelf()
            }
        }
    }
    
    // NO connectToMesh / disconnectMesh — Rust core auto-discovers peers via UDP
    
    /**
     * Start LAN discovery to find Atmosphere peers on local network.
     * This is for UI display only — Rust core handles actual UDP discovery.
     */
    private fun startLanDiscovery() {
        lanDiscovery?.stopDiscovery()
        
        val discovery = LanDiscovery(applicationContext)
        discovery.onPeerFound = { peer ->
            Log.i(TAG, "🏠 LAN peer discovered: ${peer.name} at ${peer.host}:${peer.port}")
            
            addMeshEvent(MeshEvent(
                type = "peer",
                title = "LAN Peer Found",
                detail = "${peer.name} at ${peer.host}:${peer.port}"
            ))
        }
        
        discovery.startDiscovery()
        lanDiscovery = discovery
        Log.i(TAG, "🔍 LAN discovery started (UI display only)")
    }
    
    /**
     * Bridge a message to all transports EXCEPT the source.
     * Uses nonce-based dedup to prevent broadcast storms.
     */
    private fun bridgeMessage(msg: org.json.JSONObject, sourceTransport: String) {
        val nonce = msg.optString("nonce", "").ifEmpty {
            java.util.UUID.randomUUID().toString().take(16).also { msg.put("nonce", it) }
        }
        
        // Dedup
        synchronized(seenNonces) {
            if (seenNonces.containsKey(nonce)) return
            seenNonces[nonce] = System.currentTimeMillis()
            while (seenNonces.size > SEEN_NONCES_MAX) {
                val it = seenNonces.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
        }
        
        // TTL
        val ttl = msg.optInt("ttl", 5)
        if (ttl <= 0) return
        msg.put("ttl", ttl - 1)
        msg.put("hops", msg.optInt("hops", 0) + 1)
        
        val targets = mutableListOf<String>()
        
        // WebSocket relay removed — CRDT mesh handles cross-transport sync
        
        // BLE transport removed - will be added back as Rust transport
        // (no bridging needed here)
        
        if (targets.isNotEmpty()) {
            Log.i(TAG, "🌉 Bridged ${msg.optString("type", "?")} from $sourceTransport → ${targets.joinToString()}")
        }
    }
    
    /**
     * Start BLE mesh transport for offline mesh communication.
     */
    /**
     * LEGACY - BLE transport removed. Will be added back as Rust transport in atmosphere-core.
     */
    private fun startBleTransport() {
        Log.d(TAG, "startBleTransport called but BLE transport removed (will be Rust transport)")
        // No-op: BLE will be added back as proper Rust transport
    }
    
    /**
     * Send an LLM request through the mesh.
     * 
     * @param prompt The prompt to send
     * @param model Optional model name
     * @param onResponse Callback with (response, error)
     * @return Request ID or null if not connected
     */
    /**
     * Send an app request — routed through CRDT mesh.
     */
    fun sendAppRequest(
        capabilityId: String,
        endpoint: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        // TODO: Implement via CRDT mesh doc insertion (_app_requests collection)
        Log.w(TAG, "sendAppRequest not yet implemented over CRDT mesh")
        onResponse(JSONObject().apply {
            put("status", 501)
            put("error", "App requests not yet implemented over CRDT mesh")
        })
    }

    fun callTool(
        appName: String,
        toolName: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        // TODO: Implement via CRDT mesh doc insertion (_tool_calls collection)
        Log.w(TAG, "callTool not yet implemented over CRDT mesh")
        onResponse(JSONObject().apply {
            put("status", 501)
            put("error", "Tool calls not yet implemented over CRDT mesh")
        })
    }
    
    fun sendLlmRequest(
        prompt: String,
        model: String? = null,
        onResponse: (response: String?, error: String?) -> Unit
    ): String? {
        // Generate request ID
        val requestId = UUID.randomUUID().toString()
        
        // Store callback for later
        pendingRequests[requestId] = onResponse
        
        serviceScope.launch {
            try {
                // Get semantic router
                val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also {
                    semanticRouter = it
                }
                
                // Route the prompt to find best capability
                val decision = router.route(
                    query = prompt,
                    constraints = RouteConstraints(
                        maxLatencyMs = 5000f,  // 5 second timeout
                        preferLocal = false
                    )
                )
                
                if (decision == null) {
                    Log.w(TAG, "No capability found for prompt: $prompt")
                    addMeshEvent(MeshEvent(
                        type = "route",
                        title = "No Route Found",
                        detail = "\"${prompt.take(40)}...\" — no capabilities available"
                    ))
                    pendingRequests.remove(requestId)
                    onResponse(null, "No suitable capability found")
                    return@launch
                }
                
                Log.i(TAG, "🎯 Routed to: ${decision.capability.label} on ${decision.capability.nodeName} (score=${decision.scoreBreakdown.compositeScore})")
                addMeshEvent(MeshEvent(
                    type = "route",
                    title = "Routed → ${decision.capability.label}",
                    detail = "score=${String.format("%.0f", decision.scoreBreakdown.compositeScore * 100)}% via ${decision.matchMethod.name} on ${decision.capability.nodeName}",
                    nodeId = decision.capability.nodeId,
                    metadata = mapOf(
                        "query" to prompt.take(100),
                        "capability" to decision.capability.label,
                        "capabilityId" to decision.capability.capabilityId,
                        "node" to decision.capability.nodeName,
                        "model" to decision.capability.modelActual,
                        "modelTier" to decision.capability.modelTier.value,
                        "method" to decision.matchMethod.name,
                        "score" to String.format("%.3f", decision.scoreBreakdown.compositeScore),
                        "semanticScore" to String.format("%.3f", decision.scoreBreakdown.semanticScore),
                        "latencyScore" to String.format("%.3f", decision.scoreBreakdown.latencyScore),
                        "hopScore" to String.format("%.3f", decision.scoreBreakdown.hopScore),
                        "costScore" to String.format("%.3f", decision.scoreBreakdown.costScore),
                        "hops" to decision.capability.hops.toString(),
                        "latencyMs" to decision.capability.estimatedLatencyMs.toInt().toString(),
                        "explanation" to decision.explanation,
                        "alternatives" to decision.alternatives.take(3).joinToString("; ") { "${it.first.label}=${String.format("%.0f", it.second * 100)}%" }
                    )
                ))
                
                // Check if local or remote
                val localNodeId = _nodeId.value
                if (decision.capability.nodeId == localNodeId || decision.capability.hops == 0) {
                    // Execute locally
                    executeLocalInference(requestId, prompt, onResponse)
                } else {
                    // Send to remote node
                    sendRemoteInferenceRequest(requestId, decision.capability.nodeId, prompt, model, onResponse)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendLlmRequest", e)
                pendingRequests.remove(requestId)
                onResponse(null, "Routing error: ${e.message}")
            }
        }
        
        return requestId
    }
    
    /**
     * Send a chat request through the mesh for LLM inference.
     * 
     * @param messages List of message maps with "role" and "content" keys
     * @param model Model name or "auto" for automatic selection
     * @param onResponse Callback with (content, error)
     * @return Request ID or null if not connected
     */
    fun sendChatRequest(
        messages: List<Map<String, String>>,
        model: String = "auto",
        onResponse: (content: String?, error: String?) -> Unit
    ): String? {
        // Generate request ID
        val requestId = UUID.randomUUID().toString()
        
        // Store callback for later
        pendingRequests[requestId] = onResponse
        
        serviceScope.launch {
            try {
                // Extract the last user message as the query for routing
                val lastUserMessage = messages.lastOrNull { it["role"] == "user" }?.get("content")
                if (lastUserMessage == null) {
                    Log.w(TAG, "No user message found in chat request")
                    pendingRequests.remove(requestId)
                    onResponse(null, "No user message in request")
                    return@launch
                }
                
                // Get semantic router
                val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also {
                    semanticRouter = it
                }
                
                // Route based on the user's query
                val decision = router.route(
                    query = lastUserMessage,
                    constraints = RouteConstraints(
                        maxLatencyMs = 5000f,
                        preferLocal = false
                    )
                )
                
                if (decision == null) {
                    Log.w(TAG, "No capability found for chat request")
                    addMeshEvent(MeshEvent(
                        type = "route",
                        title = "No Route Found",
                        detail = "\"${lastUserMessage.take(40)}\" — no capabilities available"
                    ))
                    pendingRequests.remove(requestId)
                    onResponse(null, "No suitable capability found")
                    return@launch
                }
                
                Log.i(TAG, "🎯 Chat routed to: ${decision.capability.label} on ${decision.capability.nodeName} (score=${decision.scoreBreakdown.compositeScore})")
                addMeshEvent(MeshEvent(
                    type = "route",
                    title = "Routed → ${decision.capability.label}",
                    detail = "score=${String.format("%.2f", decision.scoreBreakdown.compositeScore)} method=${decision.matchMethod} on ${decision.capability.nodeName}",
                    nodeId = decision.capability.nodeId
                ))
                
                // Check if local or remote
                val localNodeId = _nodeId.value
                if (decision.capability.nodeId == localNodeId || decision.capability.hops == 0) {
                    // Execute locally (convert messages to single prompt)
                    val prompt = messages.joinToString("\n") { msg ->
                        "${msg["role"]}: ${msg["content"]}"
                    }
                    executeLocalInference(requestId, prompt, onResponse)
                } else {
                    // Send to remote node
                    sendRemoteChatRequest(requestId, decision.capability.nodeId, messages, model, onResponse)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendChatRequest", e)
                pendingRequests.remove(requestId)
                onResponse(null, "Routing error: ${e.message}")
            }
        }
        
        return requestId
    }
    
    /**
     * Execute inference locally using the LocalInferenceEngine.
     */
    private suspend fun executeLocalInference(
        requestId: String,
        prompt: String,
        onResponse: (String?, String?) -> Unit
    ) {
        try {
            // Get or create local inference engine
            val engine = localInferenceEngine ?: LocalInferenceEngine.getInstance(applicationContext).also {
                localInferenceEngine = it
            }
            
            // Check if native is available
            if (!LocalInferenceEngine.isNativeAvailable()) {
                Log.w(TAG, "Local inference not available (native library missing)")
                pendingRequests.remove(requestId)
                onResponse(null, "Local inference not available")
                return
            }
            
            // Check if model is loaded
            if (engine.state.value !is LocalInferenceEngine.State.ModelReady) {
                Log.w(TAG, "No model loaded for local inference")
                pendingRequests.remove(requestId)
                onResponse(null, "No model loaded")
                return
            }
            
            Log.i(TAG, "Executing local inference for request $requestId")
            
            // Generate response (collect streaming tokens)
            val response = StringBuilder()
            engine.generate(prompt).collect { token ->
                response.append(token)
            }
            
            val finalResponse = response.toString()
            Log.i(TAG, "Local inference complete: ${finalResponse.length} chars")
            
            // Call callback and remove from pending
            pendingRequests.remove(requestId)
            onResponse(finalResponse, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in local inference", e)
            pendingRequests.remove(requestId)
            onResponse(null, "Local inference error: ${e.message}")
        }
    }
    
    /**
     * Send inference request to a remote node via mesh connection.
     */
    private fun sendRemoteInferenceRequest(
        requestId: String,
        targetNodeId: String,
        prompt: String,
        model: String?,
        onResponse: (String?, String?) -> Unit
    ) {
        // Insert request into CRDT mesh via Rust core
        if (atmosphereHandle != 0L) {
            Log.i(TAG, "🔮 Inserting inference request into CRDT mesh for $targetNodeId (requestId=$requestId)")
            serviceScope.launch {
                try {
                    // Route to find project_path
                    val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also { semanticRouter = it }
                    val routeResult = router.route(prompt)
                    val projectPath = routeResult?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                    
                    val requestDoc = JSONObject().apply {
                        put("request_id", requestId)
                        put("target_node_id", targetNodeId)
                        put("prompt", prompt)
                        put("model", model ?: "auto")
                        put("project_path", projectPath)
                        put("status", "pending")
                        put("timestamp", System.currentTimeMillis() / 1000.0)
                    }
                    
                    AtmosphereNative.insert(atmosphereHandle, "_requests", requestId, requestDoc.toString())
                    Log.i(TAG, "🔮 CRDT request inserted: $requestId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert CRDT request", e)
                    pendingRequests.remove(requestId)
                    onResponse(null, "CRDT insert failed: ${e.message}")
                }
            }
            return
        }
        
        // Not connected to mesh
        Log.w(TAG, "Cannot send remote request: no mesh connection")
        pendingRequests.remove(requestId)
        onResponse(null, "Not connected to mesh")
    }
    
    /**
     * Send chat request to a remote node via mesh connection.
     */
    private fun sendRemoteChatRequest(
        requestId: String,
        targetNodeId: String,
        messages: List<Map<String, String>>,
        model: String,
        onResponse: (String?, String?) -> Unit
    ) {
        if (atmosphereHandle == 0L) {
            Log.w(TAG, "Cannot send remote chat request: mesh not running")
            pendingRequests.remove(requestId)
            onResponse(null, "Mesh not running")
            return
        }
        
        Log.i(TAG, "🔮 Inserting chat request into CRDT mesh for $targetNodeId (requestId=$requestId)")
        
        serviceScope.launch {
            try {
                val messagesJson = JSONArray()
                messages.forEach { msg ->
                    messagesJson.put(JSONObject().apply {
                        put("role", msg["role"] ?: "user")
                        put("content", msg["content"] ?: "")
                    })
                }
                
                // Route to find project_path
                val lastUserMsg = messages.lastOrNull { it["role"] == "user" }?.get("content") ?: ""
                val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also { semanticRouter = it }
                val routeResult = router.route(lastUserMsg)
                val projectPath = routeResult?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                
                val requestDoc = JSONObject().apply {
                    put("request_id", requestId)
                    put("target_node_id", targetNodeId)
                    put("messages", messagesJson)
                    put("model", model)
                    put("project_path", projectPath)
                    put("status", "pending")
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                }
                
                AtmosphereNative.insert(atmosphereHandle, "_requests", requestId, requestDoc.toString())
                Log.i(TAG, "🔮 CRDT chat request inserted: $requestId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert CRDT chat request", e)
                pendingRequests.remove(requestId)
                onResponse(null, "CRDT insert failed: ${e.message}")
            }
        }
    }
    
    // Relay message handling removed — CRDT mesh handles all message routing
    // Responses are watched via CRDT _responses collection polling
    
    /**
     * Send inference response back via CRDT mesh.
     */
    private fun sendInferenceResponse(requestId: String, response: String?, error: String?) {
        if (atmosphereHandle == 0L) return
        
        serviceScope.launch {
            try {
                val responseDoc = JSONObject().apply {
                    put("request_id", requestId)
                    put("response", response ?: "")
                    put("error", error ?: "")
                    put("status", if (error != null) "error" else "complete")
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                }
                
                AtmosphereNative.insert(atmosphereHandle, "_responses", requestId, responseDoc.toString())
                Log.d(TAG, "🔮 Sent inference_response via CRDT for $requestId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert CRDT response", e)
            }
        }
    }

    private fun stopNode() {
        if (_state.value == ServiceState.STOPPED || _state.value == ServiceState.STOPPING) {
            Log.d(TAG, "Node already stopped or stopping")
            return
        }

        _state.value = ServiceState.STOPPING
        Log.i(TAG, "Stopping Atmosphere node...")

        serviceScope.launch {
            try {
                // TODO: Gracefully shutdown native node
                shutdownNode()
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown", e)
            } finally {
                releaseWakeLock()
                _state.value = ServiceState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Node stopped")
            }
        }
    }

    private suspend fun initializeNode() {
        try {
            // Initialize identity
            val app = applicationContext as AtmosphereApplication
            val identity = app.identityManager
            val nodeId = identity.nodeId ?: identity.loadOrCreateIdentity()
            _nodeId.value = nodeId

            // Check if native library is available
            if (AtmosphereApplication.isNativeLoaded()) {
                Log.i(TAG, "Native library available - initializing real node")
                
                // Get data directory
                val dataDir = applicationContext.filesDir.absolutePath + "/atmosphere"
                java.io.File(dataDir).mkdirs()
                
                // Try to create native node (may fail if JNI methods not implemented)
                try {
                    nativeNode = AtmosphereNode.create(nodeId, dataDir)
                    nativeNode?.start()
                    Log.i(TAG, "Native node started: $nodeId")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native AtmosphereNode not available: ${e.message}")
                    // Continue without native node
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create native node: ${e.message}")
                }
                
                // Register default capabilities
                registerDefaultCapabilities()
                
                // Initialize capabilities and cost monitoring
                initializeCapabilities(nodeId)
            } else {
                Log.w(TAG, "Native library not available - running in mock mode")
                
                // Still initialize capabilities in mock mode
                initializeCapabilities(nodeId)
            }
            
            // Start CRDT mesh engine (runs alongside existing relay/WebSocket)
            startCrdtMesh(nodeId)
            initializeEmbedder()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize node", e)
            throw e
        }
    }
    
    private fun initializeCapabilities(nodeId: String) {
        Log.i(TAG, "Initializing capabilities and cost monitoring")
        
        // Initialize cost collector and start collecting
        costCollector = CostCollector(applicationContext).apply {
            startCollecting(intervalMs = 10_000L)  // Collect every 10s
        }
        
        // Initialize cost broadcaster (broadcasts via CRDT mesh)
        costBroadcaster = CostBroadcaster(applicationContext, nodeId)
        
        // Initialize camera capability
        cameraCapability = CameraCapability(applicationContext)
        
        // Initialize voice capability
        voiceCapability = VoiceCapability(applicationContext)
        
        // Initialize mesh capability handler (exposes capabilities to mesh)
        meshCapabilityHandler = MeshCapabilityHandler(applicationContext, nodeId)
        Log.i(TAG, "Mesh capability handler initialized with capabilities: ${meshCapabilityHandler?.getCapabilityNames()}")
        
        // Observe cost factors
        serviceScope.launch {
            costCollector?.costFactors?.collect { factors ->
                factors?.let {
                    _costFactors.value = it
                    _currentCost.value = it.calculateCost()
                }
            }
        }
        
        Log.i(TAG, "Capabilities initialized: camera=${cameraCapability?.isAvailable?.value}, " +
                   "stt=${voiceCapability?.sttAvailable?.value}, tts=${voiceCapability?.ttsAvailable?.value}")
    }

    /**
     * Initialize the text embedder from bundled ONNX model files.
     * Copies model.onnx and tokenizer.json from assets to internal storage if needed.
     */
    private fun initializeEmbedder() {
        try {
            // Create models directory in internal storage
            val modelsDir = java.io.File(filesDir, "models")
            modelsDir.mkdirs()
            
            val modelFile = java.io.File(modelsDir, "model.onnx")
            val tokenizerFile = java.io.File(modelsDir, "tokenizer.json")
            
            // Copy model.onnx from assets if not present or outdated
            if (!modelFile.exists()) {
                Log.i(TAG, "Copying model.onnx from assets (~22MB)...")
                assets.open("models/model.onnx").use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "model.onnx copied successfully")
            }
            
            // Copy tokenizer.json from assets if not present
            if (!tokenizerFile.exists()) {
                Log.i(TAG, "Copying tokenizer.json from assets...")
                assets.open("models/tokenizer.json").use { input ->
                    tokenizerFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "tokenizer.json copied successfully")
            }
            
            // Initialize embedder via JNI
            val success = AtmosphereNative.initEmbedder(modelsDir.absolutePath)
            if (success) {
                Log.i(TAG, "✅ Text embedder initialized successfully")
                
                // Test embedding
                val testEmbedding = AtmosphereNative.nativeEmbed("Hello world")
                if (testEmbedding != null && testEmbedding.size == 384) {
                    Log.i(TAG, "✅ Test embedding: ${testEmbedding.size} dimensions")
                } else {
                    Log.w(TAG, "⚠️ Test embedding failed or wrong dimensions")
                }
            } else {
                Log.w(TAG, "⚠️ Failed to initialize embedder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedder", e)
        }
    }
    
    /**
     * Start the CRDT mesh engine and local client TCP server.
     * Runs alongside the existing relay/WebSocket transport.
     */
    private fun startCrdtMesh(nodeId: String) {
        try {
            // Initialize Rust core via JNI
            val deviceName = android.os.Build.MODEL ?: "Android Device"
            atmosphereHandle = AtmosphereNative.init(
                appId = "atmosphere",
                peerId = nodeId.take(16),
                deviceName = deviceName
            )
            
            if (atmosphereHandle == 0L) {
                throw RuntimeException("Failed to initialize Atmosphere core")
            }
            
            // Start mesh networking
            val meshPort = AtmosphereNative.startMesh(atmosphereHandle)
            Log.i(TAG, "🔮 Rust core mesh started: peerId=${nodeId.take(16)}, port=$meshPort")

            // Register this device's capabilities into CRDT mesh
            registerDeviceCapabilities(nodeId.take(16))

            // Periodically update mesh state for UI + sync capabilities to GossipManager
            serviceScope.launch {
                var logged = false
                while (true) {
                    delay(3000)
                    if (atmosphereHandle == 0L) break
                    
                    // Get peers from Rust core
                    val peersJson = try {
                        AtmosphereNative.peers(atmosphereHandle)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get peers: ${e.message}")
                        continue
                    }
                    
                    val peersList = try {
                        val ja = JSONArray(peersJson)
                        (0 until ja.length()).map { i ->
                            val obj = ja.getJSONObject(i)
                            SimplePeerInfo(
                                peerId = obj.getString("peer_id"),
                                state = obj.optString("state", "unknown"),
                                transports = obj.optJSONArray("connected_transports")
                                    ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                                    ?: emptyList()
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse peers: ${e.message}")
                        emptyList()
                    }
                    
                    if (!logged && peersList.isNotEmpty()) {
                        Log.i(TAG, "🔮 Mesh peers: ${peersList.size} — ${peersList.map { it.peerId.take(8) }}")
                        logged = true
                    }
                    _crdtPeers.value = peersList

                    // Sync _capabilities into GossipManager gradient table
                    try {
                        val capsJson = AtmosphereNative.query(atmosphereHandle, "_capabilities")
                        val capsArray = JSONArray(capsJson)
                        val caps = (0 until capsArray.length()).map { i ->
                            val obj = capsArray.getJSONObject(i)
                            buildMap<String, Any> {
                                obj.keys().forEach { key ->
                                    put(key, obj.get(key))
                                }
                            }
                        }
                        val statusJson = AtmosphereNative.query(atmosphereHandle, "_status")
                        val statusArray = JSONArray(statusJson)
                        if (caps.isEmpty() && peersList.isNotEmpty()) {
                            Log.d(TAG, "🔮 CRDT _capabilities empty (status docs: ${statusArray.length()})")
                        }
                        if (caps.isNotEmpty()) {
                            Log.i(TAG, "🔮 CRDT _capabilities: ${caps.size} docs from mesh")
                            val gossip = com.llamafarm.atmosphere.core.GossipManager.getInstance(applicationContext)
                            val myPeerId = nodeId.take(16)
                            for (doc in caps) {
                                val peerIdVal = doc["peer_id"]?.toString() ?: doc["source"]?.toString() ?: continue
                                if (peerIdVal == myPeerId) continue // skip own caps
                                
                                // Fields may be nested under "llm" or flat at top level
                                @Suppress("UNCHECKED_CAST")
                                val llm = (doc["llm"] as? Map<String, Any>) ?: emptyMap()
                                
                                val name = llm["description"]?.toString()?.take(30)
                                    ?: doc["name"]?.toString()
                                    ?: doc["_id"]?.toString()?.substringAfterLast(":")
                                    ?: "unknown"
                                val desc = llm["description"]?.toString() ?: doc["description"]?.toString() ?: ""
                                val capId = doc["_id"]?.toString() ?: "$peerIdVal:$name"
                                val now = System.currentTimeMillis()
                                val projectPath = llm["project_path"]?.toString() ?: doc["project_path"]?.toString() ?: "discoverable/atmosphere-universal"
                                val modelActual = llm["model_name"]?.toString() ?: doc["model"]?.toString() ?: "unknown"
                                val modelTier = llm["model_tier"]?.toString() ?: doc["model_tier"]?.toString() ?: "small"
                                val modelParamsB = (llm["model_params_b"] as? Number)?.toFloat() ?: (doc["model_params_b"] as? Number)?.toFloat() ?: 1.7f
                                val hasRag = llm["has_rag"] == true || doc["has_rag"] == true
                                val docNodeName = doc["node_name"]?.toString() ?: peerIdVal.take(8)

                                // Parse keywords from CRDT doc (may be in llm or top-level)
                                val keywordsRaw = llm["keywords"] ?: doc["keywords"]
                                val keywordsList = when (keywordsRaw) {
                                    is List<*> -> keywordsRaw.mapNotNull { it?.toString() }
                                    else -> listOf(name)
                                }

                                val capObj = org.json.JSONObject().apply {
                                    put("node_id", peerIdVal)
                                    put("node_name", docNodeName)
                                    put("capability_id", capId)
                                    put("project_path", projectPath)
                                    put("model_alias", "default")
                                    put("model_actual", modelActual)
                                    put("model_family", com.llamafarm.atmosphere.core.CapabilityAnnouncement.extractModelFamily(modelActual))
                                    put("model_params_b", modelParamsB.toDouble())
                                    put("model_tier", modelTier)
                                    put("model_quantization", "")
                                    put("capability_type", "llm/chat")
                                    put("label", name)
                                    put("description", desc)
                                    put("has_rag", hasRag)
                                    put("has_tools", false)
                                    put("has_vision", false)
                                    put("has_streaming", true)
                                    put("hops", 0)
                                    put("ttl", 10)
                                    put("timestamp", now)
                                    put("expires_at", now + 300_000)
                                    put("keywords", org.json.JSONArray(keywordsList))
                                    put("good_for", org.json.JSONArray(keywordsList.take(5)))
                                    put("specializations", org.json.JSONArray(keywordsList.take(5)))
                                }
                                val envelope = org.json.JSONObject().apply {
                                    put("type", "capability_announce")
                                    put("node_name", docNodeName)
                                    put("timestamp", now)
                                    put("capabilities", org.json.JSONArray().put(capObj))
                                }
                                gossip.handleAnnouncement(peerIdVal, envelope)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync CRDT capabilities to gossip: ${e.message}")
                    }

                    // Check _responses CRDT for completed requests and resolve pending callbacks
                    if (pendingRequests.isNotEmpty()) {
                        try {
                            val responsesJson = AtmosphereNative.query(atmosphereHandle, "_responses")
                            val responsesArray = JSONArray(responsesJson)
                            for (i in 0 until responsesArray.length()) {
                                val doc = responsesArray.getJSONObject(i)
                                val reqId = doc.optString("request_id", "")
                                if (reqId.isEmpty()) continue
                                val callback = pendingRequests.remove(reqId) ?: continue
                                val content = doc.optString("content", null)
                                val status = doc["status"]?.toString() ?: "unknown"
                                Log.i(TAG, "📥 CRDT response for $reqId: status=$status, content=${content?.take(50)}")
                                if (status == "error") {
                                    callback(null, content ?: "Unknown error")
                                } else {
                                    callback(content, null)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to check CRDT responses: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CRDT mesh", e)
            // Start polling for sensor requests
            startSensorRequestPolling()

        }
    }

    // Sensor request handler
    private var sensorRequestHandler: com.llamafarm.atmosphere.capabilities.SensorRequestHandler? = null
    
    /**
     * Start polling _requests collection for sensor data requests.
     * Responds to sensor requests via _responses collection.
     */
    private fun startSensorRequestPolling() {
        sensorRequestHandler = com.llamafarm.atmosphere.capabilities.SensorRequestHandler(applicationContext)
        
        serviceScope.launch {
            while (isActive && atmosphereHandle != 0L) {
                delay(2000) // Poll every 2 seconds
                
                try {
                    // Query _requests collection for unhandled sensor requests
                    val requestsJson = AtmosphereNative.query(atmosphereHandle, "_requests")
                    val requestsArray = org.json.JSONArray(requestsJson)
                    
                    for (i in 0 until requestsArray.length()) {
                        val request = requestsArray.getJSONObject(i)
                        val requestId = request.optString("request_id", "")
                        val capabilityId = request.optString("capability_id", "")
                        val status = request.optString("status", "pending")
                        
                        // Only handle sensor requests that are pending
                        if (status == "pending" && capabilityId.startsWith("sensor:")) {
                            handleSensorRequest(requestId, request)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling sensor requests: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Handle a single sensor request.
     */
    private fun handleSensorRequest(requestId: String, request: org.json.JSONObject) {
        serviceScope.launch {
            try {
                val capabilityId = request.getString("capability_id")
                
                // Parse params
                val params = mutableMapOf<String, Any>()
                if (request.has("params")) {
                    val paramsObj = request.getJSONObject("params")
                    paramsObj.keys().forEach { key ->
                        params[key] = paramsObj.get(key)
                    }
                }
                
                Log.i(TAG, "📡 Handling sensor request: $capabilityId (id=$requestId)")
                
                // Use SensorRequestHandler to get sensor data
                val responseData = sensorRequestHandler?.handleRequest(requestId, capabilityId, params)
                
                // Insert response into _responses collection
                val responseDoc = org.json.JSONObject().apply {
                    put("request_id", requestId)
                    put("status", "success")
                    put("data", responseData)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                
                AtmosphereNative.insert(atmosphereHandle, "_responses", requestId, responseDoc.toString())
                Log.i(TAG, "✅ Sensor response sent for $requestId: ${responseData?.length() ?: 0} bytes")
                
                // Update request status to completed
                request.put("status", "completed")
                AtmosphereNative.insert(atmosphereHandle, "_requests", requestId, request.toString())
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle sensor request $requestId", e)
                
                // Send error response
                val errorDoc = org.json.JSONObject().apply {
                    put("request_id", requestId)
                    put("status", "error")
                    put("error", e.message ?: "Unknown error")
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                
                try {
                    AtmosphereNative.insert(atmosphereHandle, "_responses", requestId, errorDoc.toString())
                    
                    // Update request status to failed
                    request.put("status", "failed")
                    request.put("error", e.message)
                    AtmosphereNative.insert(atmosphereHandle, "_requests", requestId, request.toString())
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to send error response", e2)
                }
            }
        }
    }

    private fun registerDeviceCapabilities(peerId: String) {
        val handle = atmosphereHandle
        if (handle == 0L) return
        
        serviceScope.launch {
            try {
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                val cpuCores = Runtime.getRuntime().availableProcessors()
                val deviceModel = android.os.Build.MODEL ?: "Android"
                
                // Battery info
                val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val batteryLevel = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = bm?.isCharging ?: false
                
                // Device info object to reuse
                val deviceInfo = org.json.JSONObject().apply {
                    put("cpu_cores", cpuCores)
                    put("memory_gb", String.format("%.1f", totalRamGb).toDouble())
                    put("gpu_available", false)
                    put("platform", deviceModel)
                    put("battery_level", batteryLevel)
                    put("is_charging", isCharging)
                }
                
                // 1. Register Llama 3.2 1B (bundled GGUF model)
                registerLlama32Capability(peerId, deviceModel, deviceInfo)
                
                // 2. Detect and register Gemini Nano via ML Kit GenAI
                registerUniversalAICapabilities(peerId, deviceModel, deviceInfo)
                
                // 3. Register text embedding capability
                registerEmbeddingCapability(peerId, deviceModel, deviceInfo)

                // 4. Detect and register all phone sensors as mesh capabilities
                registerSensorCapabilities(peerId, deviceModel, deviceInfo)

                // Register device status doc
                val statusDoc = org.json.JSONObject().apply {
                    put("type", "announcement")
                    put("peer_id", peerId)
                    put("node_name", deviceModel)
                    put("platform", "android")
                    put("cpu_cores", cpuCores)
                    put("memory_gb", String.format("%.1f", totalRamGb).toDouble())
                    put("battery_level", batteryLevel)
                    put("is_charging", isCharging)
                    put("timestamp", System.currentTimeMillis())
                }
                AtmosphereNative.insert(handle, "_status", "device:$peerId", statusDoc.toString())
                Log.i(TAG, "📱 Device registered: $deviceModel, ${cpuCores} cores, ${String.format("%.1f", totalRamGb)}GB RAM, battery=$batteryLevel%")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to register device capabilities", e)
            }
        }
    }
    
    /**
     * Register Llama 3.2 1B capability (bundled GGUF model).
     */
    private fun registerLlama32Capability(peerId: String, deviceModel: String, deviceInfo: org.json.JSONObject) {
        val handle = atmosphereHandle
        if (handle == 0L) return
        
        try {
            val llmDoc = org.json.JSONObject().apply {
                put("id", "local:llama-3.2-1b:default")
                put("peer_id", peerId)
                put("peer_name", deviceModel)
                put("capability_type", "llm")
                put("description", "On-device Llama 3.2 1B Instruct (Q4_K_M) — fully offline inference on Android")
                put("model", "Llama-3.2-1B-Instruct-Q4_K_M")
                put("keywords", org.json.JSONArray(listOf("local", "on-device", "offline", "llama", "chat", "instruct", "mobile", "quantized")))
                put("device_info", deviceInfo)
                put("llm_info", org.json.JSONObject().apply {
                    put("model_name", "Llama-3.2-1B-Instruct-Q4_K_M")
                    put("model_tier", "tiny")
                    put("model_params_b", 1.0)
                    put("context_length", 4096)
                    put("quantization", "Q4_K_M")
                    put("supports_tools", false)
                    put("supports_vision", false)
                    put("has_rag", false)
                })
                put("cost", org.json.JSONObject().apply {
                    put("local", true)
                    put("estimated_cost", 0.0)
                    put("battery_impact", 0.3)
                })
                put("status", org.json.JSONObject().apply {
                    put("available", true)
                    put("load", 0.0)
                    put("last_seen", System.currentTimeMillis() / 1000)
                })
                put("hops", 0)
            }
            AtmosphereNative.insert(handle, "_capabilities", "local:llama-3.2-1b:default", llmDoc.toString())
            Log.i(TAG, "✅ Registered Llama 3.2 1B capability")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Llama 3.2 1B capability", e)
        }
    }
    
    
    
    /**
     * Register text embedding capability (all-MiniLM-L6-v2 via ONNX).
     */
    private fun registerEmbeddingCapability(peerId: String, deviceModel: String, deviceInfo: org.json.JSONObject) {
        val handle = atmosphereHandle
        if (handle == 0L) return
        
        try {
            val embeddingDoc = org.json.JSONObject().apply {
                put("id", "local:embedding:minilm-l6-v2")
                put("peer_id", peerId)
                put("peer_name", deviceModel)
                put("capability_type", "embedding")
                put("description", "Text embedding model all-MiniLM-L6-v2 (384-dim) — semantic search and similarity")
                put("model", "all-MiniLM-L6-v2")
                put("keywords", org.json.JSONArray(listOf("embedding", "semantic", "similarity", "search", "vector", "onnx", "on-device")))
                put("device_info", deviceInfo)
                put("embedding_info", org.json.JSONObject().apply {
                    put("model_name", "all-MiniLM-L6-v2")
                    put("dimensions", 384)
                    put("max_tokens", 256)
                    put("runtime", "onnx")
                })
                put("cost", org.json.JSONObject().apply {
                    put("local", true)
                    put("estimated_cost", 0.0)
                    put("battery_impact", 0.1)
                })
                put("status", org.json.JSONObject().apply {
                    put("available", true)
                    put("load", 0.0)
                    put("last_seen", System.currentTimeMillis() / 1000)
                })
                put("hops", 0)
            }
            
            AtmosphereNative.insert(handle, "_capabilities", "local:embedding:minilm-l6-v2", embeddingDoc.toString())
            Log.i(TAG, "✅ Registered text embedding capability (all-MiniLM-L6-v2)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register embedding capability", e)
        }
    }

        /**
         * Detect and register all available AI capabilities using universal detection.
         * Works across all Android vendors (Google, Samsung, Qualcomm, MediaTek).
         */
        private suspend fun registerUniversalAICapabilities(peerId: String, deviceModel: String, deviceInfo: org.json.JSONObject) {
            val handle = atmosphereHandle
            if (handle == 0L) return
            
            try {
                // Detect all AI capabilities on this device
                val detectedCapabilities = com.llamafarm.atmosphere.capabilities.UniversalAIDetector.detectAll(applicationContext)
                
                Log.i(TAG, "🔍 Detected ${detectedCapabilities.size} AI capabilities")
                
                for (cap in detectedCapabilities) {
                    try {
                        // Convert UniversalAIDetector.AICapability to CRDT capability document
                        val capDoc = org.json.JSONObject().apply {
                            put("_id", cap.id)
                            put("peer_id", peerId)
                            put("peer_name", deviceModel)
                            put("capability_type", cap.type)
                            put("name", cap.name)
                            put("vendor", cap.vendor)
                            put("runtime", cap.runtime)
                            put("available", cap.available)
                            
                            // Add version if present
                            cap.version?.let { put("version", it) }
                            
                            // Add device info
                            put("device_info", deviceInfo)
                            
                            // Add model-specific info
                            val modelInfoObj = org.json.JSONObject()
                            for ((key, value) in cap.modelInfo) {
                                modelInfoObj.put(key, value)
                            }
                            put("model_info", modelInfoObj)
                            
                            // Add standard capability metadata
                            put("cost", org.json.JSONObject().apply {
                                put("local", true)
                                put("estimated_cost", 0.0)
                                put("battery_impact", when(cap.type) {
                                    "accelerator" -> 0.1
                                    "embedding" -> 0.2
                                    "llm" -> 0.4
                                    "vision" -> 0.3
                                    else -> 0.2
                                })
                            })
                            
                            put("status", org.json.JSONObject().apply {
                                put("available", cap.available)
                                put("load", 0.0)
                                put("last_seen", System.currentTimeMillis() / 1000)
                            })
                            
                            put("hops", 0)
                            put("timestamp", System.currentTimeMillis())
                        }
                        
                        AtmosphereNative.insert(handle, "_capabilities", cap.id, capDoc.toString())
                        Log.i(TAG, "✅ Registered capability: ${cap.id} (${cap.vendor} - ${cap.name})")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register capability ${cap.id}", e)
                    }
                }
                
                // Summary log
                val byVendor = detectedCapabilities.groupBy { it.vendor }
                val summary = byVendor.map { (vendor, caps) ->
                    "$vendor: ${caps.size} (${caps.map { it.type }.distinct().joinToString()})"
                }.joinToString(", ")
                
                Log.i(TAG, "📱 AI Capabilities Summary: $summary")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detect universal AI capabilities", e)
            }
        }

        /**
         * Detect and register all phone sensors as mesh capabilities.
         * Transforms the phone into a sensor node - location, motion, environment, etc.
         */
        private suspend fun registerSensorCapabilities(peerId: String, deviceModel: String, deviceInfo: org.json.JSONObject) {
            val handle = atmosphereHandle
            if (handle == 0L) return
            
            try {
                // Detect all available sensors
                val sensors = com.llamafarm.atmosphere.capabilities.SensorCapabilityDetector.detectAll(applicationContext)
                
                Log.i(TAG, "🔍 Detected ${sensors.size} sensor capabilities")
                
                for (sensor in sensors) {
                    try {
                        // Convert to CRDT capability document
                        val sensorDoc = org.json.JSONObject().apply {
                            put("_id", sensor.id)
                            put("peer_id", peerId)
                            put("peer_name", deviceModel)
                            put("capability_type", sensor.type)
                            put("name", sensor.name)
                            put("category", sensor.category)
                            put("available", sensor.available)
                            
                            // Add permission info if required
                            sensor.requiresPermission?.let { 
                                put("requires_permission", it)
                            }
                            
                            // Add device info
                            put("device_info", deviceInfo)
                            
                            // Add sensor-specific metadata
                            val metadataObj = org.json.JSONObject()
                            for ((key, value) in sensor.metadata) {
                                when (value) {
                                    is List<*> -> metadataObj.put(key, org.json.JSONArray(value))
                                    is Map<*, *> -> {
                                        val mapObj = org.json.JSONObject()
                                        (value as Map<String, Any>).forEach { (k, v) ->
                                            mapObj.put(k, v)
                                        }
                                        metadataObj.put(key, mapObj)
                                    }
                                    else -> metadataObj.put(key, value)
                                }
                            }
                            put("metadata", metadataObj)
                            
                            // Add standard capability metadata
                            put("cost", org.json.JSONObject().apply {
                                put("local", true)
                                put("estimated_cost", 0.0)
                                put("battery_impact", when(sensor.category) {
                                    "location" -> 0.3
                                    "motion" -> 0.1
                                    "environment" -> 0.1
                                    "vision" -> 0.4
                                    "audio" -> 0.3
                                    else -> 0.1
                                })
                            })
                            
                            put("status", org.json.JSONObject().apply {
                                put("available", sensor.available)
                                put("requestable", true) // Supports request/response pattern
                                put("last_seen", System.currentTimeMillis() / 1000)
                            })
                            
                            put("hops", 0)
                            put("timestamp", System.currentTimeMillis())
                        }
                        
                        AtmosphereNative.insert(handle, "_capabilities", sensor.id, sensorDoc.toString())
                        Log.d(TAG, "✅ Registered sensor: ${sensor.id} (${sensor.name})")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register sensor ${sensor.id}", e)
                    }
                }
                
                // Summary log
                val byCategory = sensors.groupBy { it.category }
                val summary = byCategory.map { (category, items) ->
                    "$category: ${items.size}"
                }.joinToString(", ")
                
                Log.i(TAG, "📡 Sensor Capabilities: $summary (total: ${sensors.size})")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to detect sensor capabilities", e)
            }
        }

    private fun registerDefaultCapabilities() {
        try {
            // Register camera capability
            nativeNode?.registerCapability("""
                {"name": "camera", "description": "Take photos with device camera"}
            """.trimIndent())
            
            // Register location capability
            nativeNode?.registerCapability("""
                {"name": "location", "description": "Get device GPS location"}
            """.trimIndent())
            
            // Register microphone capability
            nativeNode?.registerCapability("""
                {"name": "microphone", "description": "Record audio from device mic"}
            """.trimIndent())
            
            Log.i(TAG, "Registered default capabilities")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register capabilities", e)
        }
    }

    private suspend fun shutdownNode() {
        try {
            // Clear pending requests
            pendingRequests.clear()
            
            // Stop cost broadcasting
            costBroadcaster?.destroy()
            costBroadcaster = null
            
            // Stop cost collection
            costCollector?.destroy()
            costCollector = null
            
            // Destroy capabilities
            cameraCapability?.destroy()
            cameraCapability = null
            
            voiceCapability?.destroy()
            voiceCapability = null
            
            // Destroy mesh capability handler
            meshCapabilityHandler?.destroy()
            meshCapabilityHandler = null
            
            // Cleanup local inference
            localInferenceEngine?.destroy()
            localInferenceEngine = null
            
            // Cleanup sensor request handler
            sensorRequestHandler = null
            
            // Stop Rust core mesh
            if (atmosphereHandle != 0L) {
                try {
                    AtmosphereNative.stop(atmosphereHandle)
                    Log.i(TAG, "Rust core stopped")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop Rust core: ${e.message}")
                }
                atmosphereHandle = 0
            }
            
            // BLE mesh removed - will be added back as Rust transport
            
            // Stop LAN discovery
            lanDiscovery?.stopDiscovery()
            lanDiscovery = null
            
            // Clear router
            semanticRouter = null
            
            // Stop native node
            nativeNode?.let { node ->
                Log.i(TAG, "Stopping native node...")
                node.stop()
                node.destroy()
                Log.i(TAG, "Native node stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native node", e)
        } finally {
            nativeNode = null
            _nodeId.value = null
            _connectedPeers.value = 0
            _currentCost.value = null
            _costFactors.value = null
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AtmosphereService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AtmosphereApplication.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("Atmosphere")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Get current service statistics.
     */
    fun getStats(): ServiceStats {
        return ServiceStats(
            state = _state.value,
            nodeId = _nodeId.value,
            connectedPeers = _connectedPeers.value,
            uptime = 0L // TODO: Track actual uptime
        )
    }
    
    /**
     * Get native node status as JSON.
     */
    fun getNativeStatus(): String? {
        return try {
            nativeNode?.statusJson()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get native status", e)
            null
        }
    }
    
    /**
     * Check if native node is running.
     */
    fun isNativeRunning(): Boolean {
        return try {
            nativeNode?.isRunning() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    // setMeshConnection / clearMeshConnection removed — CRDT mesh is sole transport
    
    /**
     * Get the camera capability instance.
     */
    fun getCameraCapability(): CameraCapability? = cameraCapability
    
    /**
     * Get the voice capability instance.
     */
    fun getVoiceCapability(): VoiceCapability? = voiceCapability
    
    /**
     * Get the cost collector instance.
     */
    fun getCostCollector(): CostCollector? = costCollector
    
    /**
     * Get the cost broadcaster instance.
     */
    fun getCostBroadcaster(): CostBroadcaster? = costBroadcaster
    
    /**
     * Get the mesh capability handler instance.
     */
    fun getMeshCapabilityHandler(): MeshCapabilityHandler? = meshCapabilityHandler
    
    /**
     * Force an immediate cost broadcast.
     */
    fun broadcastCostNow() {
        costBroadcaster?.broadcastNow()
    }

    data class ServiceStats(
        val state: ServiceState,
        val nodeId: String?,
        val connectedPeers: Int,
        val uptime: Long
    )
}
