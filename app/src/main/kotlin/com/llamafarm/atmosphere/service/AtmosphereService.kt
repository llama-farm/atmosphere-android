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
import com.llamafarm.atmosphere.data.SavedMesh
import com.llamafarm.atmosphere.data.SavedMeshRepository
import com.llamafarm.atmosphere.discovery.LanDiscovery
// MeshConnection/relay imports removed — CRDT mesh is sole transport
// import com.llamafarm.atmosphere.network.MeshConnection
// import com.llamafarm.atmosphere.network.ConnectionState
// import com.llamafarm.atmosphere.network.MeshMessage
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.router.RouteConstraints
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.core.AtmosphereCore
// ClientServer removed — AIDL binder replaces TCP for Android IPC
import com.llamafarm.atmosphere.core.PeerInfo as CrdtPeerInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    fun isConnected(): Boolean = atmosphereCore != null && atmosphereCore!!.connectedPeers().isNotEmpty()
    fun getNodeId(): String? = _nodeId.value
    fun getMeshId(): String? = currentMeshId
    fun getAtmosphereCore(): AtmosphereCore? = atmosphereCore
    fun getCrdtPeerCount(): Int = atmosphereCore?.connectedPeers()?.size ?: 0

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
    private var currentMeshId: String? = null
    
    // BLE mesh transport removed - will be added back as Rust transport in atmosphere-core
    
    // Transport bridge: dedup cache for cross-transport forwarding
    private val seenNonces = java.util.LinkedHashMap<String, Long>(100, 0.75f, true)
    private val SEEN_NONCES_MAX = 500
    
    // Relay URL removed — CRDT mesh handles connectivity
    
    // CRDT mesh engine (atmosphere-core daemon)
    private var atmosphereCore: AtmosphereCore? = null
    
    // CRDT state exposed to UI
    private val _crdtPeers = MutableStateFlow<List<CrdtPeerInfo>>(emptyList())
    val crdtPeers: StateFlow<List<CrdtPeerInfo>> = _crdtPeers.asStateFlow()
    
    // Semantic router for capability matching
    private var semanticRouter: SemanticRouter? = null
    
    // Local inference engine
    private var localInferenceEngine: LocalInferenceEngine? = null
    
    // Pending inference requests: requestId -> callback
    private val pendingRequests = ConcurrentHashMap<String, (String?, String?) -> Unit>()
    
    // Persistence
    private lateinit var preferences: AtmospherePreferences
    private lateinit var meshRepository: SavedMeshRepository

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
        meshRepository = SavedMeshRepository(preferences)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.llamafarm.atmosphere.action.CONNECT_MESH" -> {
                val meshId = intent.getStringExtra("meshId")
                if (meshId != null) connectToMesh(meshId)
            }
            "com.llamafarm.atmosphere.action.DISCONNECT_MESH" -> disconnectMesh()
            ACTION_START -> startNode()
            ACTION_STOP -> stopNode()
            "ACTION_RETRY_BLE" -> {
                // BLE transport removed - will be added back as Rust transport
                Log.d(TAG, "ACTION_RETRY_BLE ignored (BLE transport removed)")
            }
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
                
                // Start LAN discovery (mDNS)
                startLanDiscovery()
                
                // Start BLE mesh transport
                startBleTransport()
                
                // Auto-connect to saved mesh if configured
                checkAutoConnect()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start node", e)
                _state.value = ServiceState.STOPPED
                stopSelf()
            }
        }
    }
    
    private suspend fun checkAutoConnect() {
        // Load saved meshes with auto-reconnect
        val autoConnectMeshes = meshRepository.getAutoReconnectMeshes()
        if (autoConnectMeshes.isNotEmpty()) {
            val mesh = autoConnectMeshes.first() // Connect to first available for now
            Log.i(TAG, "Auto-connecting to mesh: ${mesh.meshName}")
            connectToMesh(mesh)
        }
    }
    
    /**
     * Connect to a specific mesh by ID.
     */
    fun connectToMesh(meshId: String) {
        serviceScope.launch {
            val mesh = meshRepository.getMesh(meshId)
            if (mesh != null) {
                connectToMesh(mesh)
            } else {
                Log.e(TAG, "Cannot connect: Mesh $meshId not found")
            }
        }
    }
    
    private fun connectToMesh(mesh: com.llamafarm.atmosphere.data.SavedMesh) {
        currentMeshId = mesh.meshId
        Log.i(TAG, "🔮 Connecting to mesh via CRDT: ${mesh.meshName}")
        
        // BLE transport removed - will be added back as Rust transport
        // (no action needed here)
        
        // CRDT mesh handles connectivity — no relay WebSocket needed
        updateNotification("Connected to ${mesh.meshName} via CRDT mesh")
    }
    
    /**
     * Disconnect from current mesh.
     */
    fun disconnectMesh() {
        currentMeshId = null
        _activeTransport.value = null
        updateNotification("Online (Disconnected)")
    }
    
    /**
     * Start LAN discovery to find Atmosphere peers on local network.
     */
    private fun startLanDiscovery() {
        lanDiscovery?.stopDiscovery()
        
        val discovery = LanDiscovery(applicationContext)
        discovery.onPeerFound = { peer ->
            Log.i(TAG, "🏠 LAN peer discovered: ${peer.name} at ${peer.host}:${peer.port} mesh=${peer.meshId}")
            
            addMeshEvent(MeshEvent(
                type = "peer",
                title = "LAN Peer Found",
                detail = "${peer.name} at ${peer.host}:${peer.port}"
            ))
            
            // If LAN peer found for current mesh, save the endpoint
            val meshId = currentMeshId
            if (meshId != null && peer.meshId == meshId && _activeTransport.value != "lan") {
                Log.i(TAG, "🏠 LAN peer matches current mesh — saving endpoint")
                
                serviceScope.launch {
                    // Update saved mesh with LAN endpoint
                    val mesh = meshRepository.getMesh(meshId) ?: return@launch
                    val existingEndpoints = mesh.endpoints.toMutableList()
                    
                    // Remove old LAN endpoints, add new one
                    existingEndpoints.removeAll { it.type == "lan" || it.type == "local" }
                    existingEndpoints.add(SavedMesh.Endpoint(
                        type = "lan",
                        address = peer.httpUrl,
                        priority = 90
                    ))
                    
                    val updatedMesh = mesh.copy(endpoints = existingEndpoints)
                    meshRepository.saveMesh(updatedMesh)
                }
            } else if (meshId != null && peer.meshId == meshId && _activeTransport.value != "lan") {
                // Not connected yet or connected via something else — add LAN endpoint
                serviceScope.launch {
                    val mesh = meshRepository.getMesh(meshId) ?: return@launch
                    val existingEndpoints = mesh.endpoints.toMutableList()
                    existingEndpoints.removeAll { it.type == "lan" || it.type == "local" }
                    existingEndpoints.add(SavedMesh.Endpoint(
                        type = "lan",
                        address = peer.httpUrl,
                        priority = 90
                    ))
                    meshRepository.saveMesh(mesh.copy(endpoints = existingEndpoints))
                    Log.i(TAG, "📝 Saved LAN endpoint for mesh ${mesh.meshName}")
                }
            }
        }
        
        discovery.startDiscovery()
        lanDiscovery = discovery
        Log.i(TAG, "🔍 LAN discovery started")
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
        // Insert request into CRDT mesh — daemon will pick it up
        val core = atmosphereCore
        if (core != null) {
            Log.i(TAG, "🔮 Inserting inference request into CRDT mesh for $targetNodeId (requestId=$requestId)")
            serviceScope.launch {
                try {
                    // Route to find project_path
                    val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also { semanticRouter = it }
                    val routeResult = router.route(prompt)
                    val projectPath = routeResult?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                    
                    val requestDoc = mapOf(
                        "request_id" to requestId,
                        "target_node_id" to targetNodeId,
                        "prompt" to prompt,
                        "model" to (model ?: "auto"),
                        "project_path" to projectPath,
                        "status" to "pending",
                        "timestamp" to (System.currentTimeMillis() / 1000.0)
                    )
                    core.insert("_requests", requestDoc)
                    Log.i(TAG, "🔮 CRDT request inserted: $requestId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to insert CRDT request", e)
                    pendingRequests.remove(requestId)
                    onResponse(null, "CRDT insert failed: ${e.message}")
                }
            }
            return
        }
        
        // BLE fallback removed - BLE transport will be added back as Rust transport
        Log.w(TAG, "Cannot send remote request: no CRDT mesh")
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
        val core = atmosphereCore
        if (core == null) {
            Log.w(TAG, "Cannot send remote chat request: CRDT mesh not running")
            pendingRequests.remove(requestId)
            onResponse(null, "CRDT mesh not running")
            return
        }
        
        Log.i(TAG, "🔮 Inserting chat request into CRDT mesh for $targetNodeId (requestId=$requestId)")
        
        serviceScope.launch {
            try {
                val messagesJson = messages.map { msg ->
                    mapOf("role" to (msg["role"] ?: "user"), "content" to (msg["content"] ?: ""))
                }
                // Route to find project_path
                val lastUserMsg = messages.lastOrNull { it["role"] == "user" }?.get("content") ?: ""
                val router = semanticRouter ?: SemanticRouter.getInstance(applicationContext).also { semanticRouter = it }
                val routeResult = router.route(lastUserMsg)
                val projectPath = routeResult?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                
                val requestDoc = mapOf(
                    "request_id" to requestId,
                    "target_node_id" to targetNodeId,
                    "messages" to messagesJson,
                    "model" to model,
                    "project_path" to projectPath,
                    "status" to "pending",
                    "timestamp" to (System.currentTimeMillis() / 1000.0)
                )
                core.insert("_requests", requestDoc)
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
        val core = atmosphereCore ?: return
        
        serviceScope.launch {
            try {
                val responseDoc = mapOf(
                    "request_id" to requestId,
                    "response" to (response ?: ""),
                    "error" to (error ?: ""),
                    "status" to if (error != null) "error" else "complete",
                    "timestamp" to (System.currentTimeMillis() / 1000.0)
                )
                core.insert("_responses", responseDoc)
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
     * Start the CRDT mesh engine and local client TCP server.
     * Runs alongside the existing relay/WebSocket transport.
     */
    private fun startCrdtMesh(nodeId: String) {
        try {
            val core = AtmosphereCore(
                appId = "atmosphere",
                peerId = nodeId.take(16),
                metadata = mapOf("type" to "android-daemon", "name" to "BigLlama-Android"),
                listenPort = 0,  // OS-assigned for mesh TCP
                enableLan = true,
            )
            atmosphereCore = core
            core.startSync()
            Log.i(TAG, "🔮 CRDT mesh started: peerId=${core.peerId} (mesh port assigned async)")

            // Periodically update CRDT state for UI + sync capabilities to GossipManager
            serviceScope.launch {
                var logged = false
                while (true) {
                    delay(3000)
                    val c = atmosphereCore ?: break
                    if (!logged && c.listenPort > 0) {
                        Log.i(TAG, "🔮 CRDT mesh port assigned: ${c.listenPort}, peers: ${c.connectedPeers().size}")
                        logged = true
                    }
                    val peers = c.connectedPeers()
                    if (peers.isNotEmpty()) {
                        Log.i(TAG, "🔮 CRDT peers: ${peers.size} — ${peers.map { it.peerId.take(8) }}")
                    }
                    _crdtPeers.value = peers

                    // Sync CRDT _capabilities into GossipManager gradient table
                    try {
                        val caps = c.query("_capabilities")
                        if (caps.isNotEmpty()) {
                            val gossip = com.llamafarm.atmosphere.core.GossipManager.getInstance(applicationContext)
                            for (doc in caps) {
                                val peerIdVal = doc["peer_id"]?.toString() ?: doc["source"]?.toString() ?: continue
                                if (peerIdVal == c.peerId) continue // skip own caps
                                val name = doc["name"]?.toString() ?: "unknown"
                                val desc = doc["description"]?.toString() ?: ""
                                val capId = doc["_id"]?.toString() ?: "$peerIdVal:$name"
                                val now = System.currentTimeMillis()
                                val projectPath = doc["project_path"]?.toString() ?: "discoverable/$name"
                                val modelActual = doc["model"]?.toString() ?: "unknown"
                                val modelTier = doc["model_tier"]?.toString() ?: "small"
                                val modelParamsB = (doc["model_params_b"] as? Number)?.toFloat() ?: 1.7f
                                val hasRag = doc["has_rag"] == true
                                val docNodeName = doc["node_name"]?.toString() ?: peerIdVal.take(8)

                                // Parse keywords from CRDT doc
                                val keywordsRaw = doc["keywords"]
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CRDT mesh", e)
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
            
            // Stop CRDT mesh
            atmosphereCore?.stopSync()
            atmosphereCore = null
            
            // BLE mesh removed - will be added back as Rust transport
            
            // Stop LAN discovery
            lanDiscovery?.stopDiscovery()
            lanDiscovery = null
            
            // Clear mesh state
            currentMeshId = null
            
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
