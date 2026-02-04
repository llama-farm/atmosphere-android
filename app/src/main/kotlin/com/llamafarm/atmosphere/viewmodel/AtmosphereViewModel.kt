package com.llamafarm.atmosphere.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.bindings.AtmosphereException
import com.llamafarm.atmosphere.bindings.AtmosphereNode
import com.llamafarm.atmosphere.bindings.MeshPeer
import com.llamafarm.atmosphere.data.AtmospherePreferences
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.network.MeshConnection
import com.llamafarm.atmosphere.network.MeshMessage
import com.llamafarm.atmosphere.network.RelayPeer
import com.llamafarm.atmosphere.network.RoutingInfo
import com.llamafarm.atmosphere.router.DefaultCapabilities
import com.llamafarm.atmosphere.router.HttpEmbeddingService
import com.llamafarm.atmosphere.router.RouteResult
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.service.AtmosphereService
import com.llamafarm.atmosphere.transport.BleTransport
import com.llamafarm.atmosphere.transport.NodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "AtmosphereViewModel"

/**
 * ViewModel for the Atmosphere app.
 * Manages UI state and coordinates with the background service.
 */
class AtmosphereViewModel(application: Application) : AndroidViewModel(application) {

    // Node state
    data class NodeState(
        val isRunning: Boolean = false,
        val nodeId: String? = null,
        val status: String = "Offline",
        val connectedPeers: Int = 0,
        val capabilities: List<String> = emptyList()
    )

    private val _nodeState = MutableStateFlow(NodeState())
    val nodeState: StateFlow<NodeState> = _nodeState.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Mesh connection state
    private val _isConnectedToMesh = MutableStateFlow(false)
    val isConnectedToMesh: StateFlow<Boolean> = _isConnectedToMesh.asStateFlow()
    
    private val _meshName = MutableStateFlow<String?>(null)
    val meshName: StateFlow<String?> = _meshName.asStateFlow()
    
    // Peer list (from native node)
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    
    // Relay peer list (from WebSocket)
    private val _relayPeers = MutableStateFlow<List<RelayPeer>>(emptyList())
    val relayPeers: StateFlow<List<RelayPeer>> = _relayPeers.asStateFlow()
    
    // Combined peer count
    val peerCount: Int
        get() = _peers.value.size + _relayPeers.value.size
    
    // Relay connection state (for UI)
    private val _relayConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val relayConnectionState: StateFlow<ConnectionState> = _relayConnectionState.asStateFlow()
    
    // Cost tracking - THE CROWN JEWEL
    private val _localCost = MutableStateFlow(1.0f)
    val localCost: StateFlow<Float> = _localCost.asStateFlow()
    
    private val _peerCosts = MutableStateFlow<Map<String, Float>>(emptyMap())
    val peerCosts: StateFlow<Map<String, Float>> = _peerCosts.asStateFlow()
    
    // Cost collector for local device metrics
    private val costCollector = (application as? com.llamafarm.atmosphere.AtmosphereApplication)?.costCollector
    
    // Preferences
    private val preferences = AtmospherePreferences(application)
    
    // Native node reference (for direct calls when service isn't available)
    private var nativeNode: AtmosphereNode? = null
    
    // WebSocket mesh connection (pure Kotlin, no Rust needed)
    private var meshWebSocket: MeshConnection? = null
    
    // BLE Transport for local mesh (no internet required!)
    private var bleTransport: BleTransport? = null
    private val _blePeers = MutableStateFlow<List<NodeInfo>>(emptyList())
    val blePeers: StateFlow<List<NodeInfo>> = _blePeers.asStateFlow()
    
    private val _bleEnabled = MutableStateFlow(false)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()
    
    // Semantic router for intent-based routing
    val semanticRouter = SemanticRouter(application)
    
    // Last route result (local semantic router)
    private val _lastRouteResult = MutableStateFlow<RouteResult?>(null)
    val lastRouteResult: StateFlow<RouteResult?> = _lastRouteResult.asStateFlow()
    
    // 🎯 LAST ROUTING INFO FROM SERVER (THE CROWN JEWEL!)
    private val _lastRoutingInfo = MutableStateFlow<RoutingInfo?>(null)
    val lastRoutingInfo: StateFlow<RoutingInfo?> = _lastRoutingInfo.asStateFlow()
    
    // Saved mesh info for UI display (MUST be before init block!)
    private val _savedMeshName = MutableStateFlow<String?>(null)
    val savedMeshName: StateFlow<String?> = _savedMeshName.asStateFlow()
    
    private val _hasSavedMesh = MutableStateFlow(false)
    val hasSavedMesh: StateFlow<Boolean> = _hasSavedMesh.asStateFlow()
    
    // 📡 MESH EVENT LOG - Shows gossip, peer events, routing decisions
    data class MeshEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val type: String,  // "gossip", "peer_joined", "peer_left", "cost", "route", "chat", "error"
        val title: String,
        val detail: String? = null,
        val nodeId: String? = null
    )
    
    private val _meshEvents = MutableStateFlow<List<MeshEvent>>(emptyList())
    val meshEvents: StateFlow<List<MeshEvent>> = _meshEvents.asStateFlow()
    
    private fun addMeshEvent(event: MeshEvent) {
        val current = _meshEvents.value.toMutableList()
        current.add(0, event)  // Add to front
        // Keep last 50 events
        if (current.size > 50) {
            _meshEvents.value = current.take(50)
        } else {
            _meshEvents.value = current
        }
        Log.d(TAG, "📡 MESH EVENT: [${event.type}] ${event.title} - ${event.detail ?: ""}")
    }
    
    // Mesh WebSocket connection state
    val meshConnectionState: StateFlow<ConnectionState>
        get() = meshWebSocket?.connectionState ?: MutableStateFlow(ConnectionState.DISCONNECTED)
    
    // WebSocket mesh name (separate from native mesh)
    val webSocketMeshName: StateFlow<String?>
        get() = meshWebSocket?.meshName ?: MutableStateFlow(null)

    init {
        // Auto-start everything on app launch
        Log.i(TAG, "🚀 ViewModel initializing - auto-connect to mesh")
        // NOTE: Don't call startService() - native library may not be available
        // The mesh WebSocket connection works without the native node
        loadSavedState()  // Will auto-reconnect to mesh if saved
        startPeerPolling()
        initializeRouter()
        startCostCollection()
    }
    
    /**
     * Start collecting local device cost factors.
     */
    private fun startCostCollection() {
        costCollector?.startCollecting(30_000L)  // Every 30 seconds
        
        viewModelScope.launch {
            costCollector?.costFactors?.collect { factors ->
                factors?.let {
                    val cost = it.calculateCost()
                    _localCost.value = cost
                    Log.d(TAG, "Local cost updated: $cost (battery=${it.batteryLevel}%, charging=${it.isCharging}, network=${it.networkType})")
                }
            }
        }
    }
    
    /**
     * Initialize the semantic router with default capabilities.
     */
    private fun initializeRouter() {
        viewModelScope.launch {
            try {
                // Register default local capabilities
                DefaultCapabilities.registerAll(semanticRouter)
                
                // Try to connect to LlamaFarm for embeddings (if on same network)
                try {
                    val embeddingService = HttpEmbeddingService("http://192.168.1.100:11540")
                    semanticRouter.initialize(embeddingService)
                    Log.i(TAG, "Semantic router initialized with remote embeddings")
                } catch (e: Exception) {
                    // Fall back to keyword-only matching
                    semanticRouter.initialize(null)
                    Log.i(TAG, "Semantic router initialized with keyword matching only")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize semantic router", e)
            }
        }
    }
    
    /**
     * Route an intent to a capability.
     * 
     * @param intent Natural language intent (e.g., "take a photo")
     * @return RouteResult with matched capability and score
     */
    suspend fun routeIntent(intent: String): RouteResult? {
        return withContext(Dispatchers.Default) {
            try {
                val result = semanticRouter.route(intent)
                _lastRouteResult.value = result
                result
            } catch (e: Exception) {
                Log.e(TAG, "Route failed: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Route and execute an intent.
     */
    fun routeAndExecute(intent: String, onResult: (JSONObject) -> Unit) {
        viewModelScope.launch {
            semanticRouter.routeAndExecute(intent, JSONObject(), onResult)
        }
    }
    
    /**
     * Register a remote capability discovered from a mesh peer.
     */
    fun registerRemoteCapability(
        name: String,
        description: String,
        nodeId: String,
        cost: Float = 1.0f
    ) {
        semanticRouter.registerRemoteCapability(
            name = name,
            description = description,
            nodeId = nodeId,
            cost = cost
        )
        Log.d(TAG, "Registered remote capability: $name from $nodeId")
    }

    private fun loadSavedState() {
        viewModelScope.launch {
            Log.i(TAG, "=== loadSavedState() starting ===")
            
            // Load persisted node ID
            preferences.nodeId.first()?.let { savedId ->
                _nodeState.value = _nodeState.value.copy(nodeId = savedId)
                Log.d(TAG, "Loaded node ID: $savedId")
            }
            
            // Check if we have a saved mesh (for UI display)
            val token = preferences.lastMeshToken.first()
            val savedMeshName = preferences.lastMeshName.first()
            val endpoint = preferences.lastMeshEndpoint.first()
            val autoReconnect = preferences.autoReconnectMesh.first()
            
            Log.i(TAG, "Saved mesh state: name=$savedMeshName, hasToken=${token != null}, hasEndpoint=${endpoint != null}, autoReconnect=$autoReconnect")
            
            _hasSavedMesh.value = token != null
            _savedMeshName.value = savedMeshName
            
            // Check for auto-reconnect to mesh
            if (autoReconnect && token != null && endpoint != null) {
                Log.i(TAG, "🔄 Auto-reconnecting to mesh: $savedMeshName")
                attemptReconnect()
            } else {
                Log.i(TAG, "Not auto-reconnecting: autoReconnect=$autoReconnect, token=${token != null}, endpoint=${endpoint != null}")
            }
        }
    }
    
    /**
     * Attempt to reconnect to saved mesh using full endpoints map.
     */
    fun attemptReconnect() {
        viewModelScope.launch {
            val token = preferences.lastMeshToken.first() ?: return@launch
            val endpoint = preferences.lastMeshEndpoint.first() ?: return@launch
            val savedMeshName = preferences.lastMeshName.first()
            val endpointsJson = preferences.lastMeshEndpointsJson.first()
            
            Log.i(TAG, "Attempting reconnect to: $savedMeshName")
            
            // Parse token string back to JSONObject for relay auth
            val tokenObject = try {
                JSONObject(token)
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse saved token as JSON, using as string")
                null
            }
            
            // Parse endpoints map if available
            val endpoints: Map<String, String>? = try {
                endpointsJson?.let { json ->
                    val obj = JSONObject(json)
                    mutableMapOf<String, String>().apply {
                        obj.keys().forEach { key ->
                            put(key, obj.getString(key))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse saved endpoints JSON")
                null
            }
            
            joinMesh(endpoint, token, tokenObject, endpoints)
        }
    }
    
    /**
     * Start periodic polling for peer updates.
     */
    private fun startPeerPolling() {
        viewModelScope.launch {
            while (true) {
                delay(5000) // Poll every 5 seconds
                if (_isConnectedToMesh.value) {
                    refreshPeers()
                }
            }
        }
    }
    
    /**
     * Refresh peer list from native node.
     */
    private fun refreshPeers() {
        try {
            nativeNode?.let { node ->
                val peerList = node.getPeersList()
                _peers.value = peerList
                _nodeState.value = _nodeState.value.copy(connectedPeers = peerList.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh peers", e)
        }
    }

    /**
     * Start the Atmosphere service.
     */
    fun startService() {
        val context = getApplication<Application>()
        AtmosphereService.start(context)
        _nodeState.value = _nodeState.value.copy(status = "Connecting")
        
        // Initialize native node if needed
        initializeNativeNode()
    }
    
    /**
     * Initialize the native node directly.
     */
    private fun initializeNativeNode() {
        viewModelScope.launch {
            try {
                if (nativeNode == null) {
                    val nodeId = _nodeState.value.nodeId ?: AtmosphereNode.generateNodeId()
                    val dataDir = getApplication<Application>().filesDir.absolutePath + "/atmosphere"
                    java.io.File(dataDir).mkdirs()
                    
                    nativeNode = AtmosphereNode.create(nodeId, dataDir)
                    nativeNode?.start()
                    
                    // Save node ID
                    preferences.setNodeId(nodeId)
                    
                    _nodeState.value = _nodeState.value.copy(
                        isRunning = true,
                        nodeId = nodeId,
                        status = "Online"
                    )
                    
                    Log.i(TAG, "Native node initialized: $nodeId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize native node", e)
                _error.value = "Failed to start node: ${e.message}"
            }
        }
    }

    /**
     * Stop the Atmosphere service.
     */
    fun stopService() {
        val context = getApplication<Application>()
        AtmosphereService.stop(context)
        
        // Stop native node
        nativeNode?.let {
            try {
                it.stop()
                it.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native node", e)
            }
        }
        nativeNode = null
        
        _nodeState.value = _nodeState.value.copy(
            isRunning = false,
            status = "Offline",
            connectedPeers = 0
        )
        _isConnectedToMesh.value = false
        _meshName.value = null
        _peers.value = emptyList()
    }

    /**
     * Update node state from service.
     */
    fun updateFromService(stats: AtmosphereService.ServiceStats) {
        _nodeState.value = _nodeState.value.copy(
            isRunning = stats.state == AtmosphereService.ServiceState.RUNNING,
            nodeId = stats.nodeId,
            status = when (stats.state) {
                AtmosphereService.ServiceState.RUNNING -> if (_isConnectedToMesh.value) "Connected" else "Online"
                AtmosphereService.ServiceState.STARTING -> "Connecting"
                AtmosphereService.ServiceState.STOPPING -> "Disconnecting"
                AtmosphereService.ServiceState.STOPPED -> "Offline"
            },
            connectedPeers = stats.connectedPeers
        )
    }
    
    /**
     * Join a mesh network via WebSocket (pure Kotlin - no Rust needed).
     * 
     * This is the preferred method for connecting to a mesh from Android.
     * Uses OkHttp WebSocket to connect to the Atmosphere API.
     * Tries multiple endpoints with fallback (local -> relay).
     * 
     * @param endpoint Primary WebSocket endpoint (used if endpoints map not available)
     * @param token Legacy token string or JSON-encoded signed token
     * @param tokenObject For v2 invites, the full signed token JSONObject
     * @param endpoints Optional map of endpoints to try (local, public, relay)
     */
    fun joinMesh(
        endpoint: String, 
        token: String, 
        tokenObject: JSONObject? = null,
        endpoints: Map<String, String>? = null
    ) {
        viewModelScope.launch {
            try {
                // Build ordered list of endpoints to try
                val endpointList = mutableListOf<Pair<String, String>>()
                if (endpoints != null) {
                    // Try local first (faster, lower latency), fall back to relay
                    endpoints["local"]?.let { endpointList.add("local" to it) }
                    endpoints["public"]?.let { endpointList.add("public" to it) }
                    endpoints["relay"]?.let { endpointList.add("relay" to it) }
                }
                if (endpointList.isEmpty()) {
                    endpointList.add("primary" to endpoint)
                }
                
                Log.i(TAG, "Joining mesh, will try ${endpointList.size} endpoints: ${endpointList.map { it.first }}")
                if (tokenObject != null) {
                    Log.i(TAG, "Using signed token (v2)")
                }
                
                // Disconnect existing connection if any
                meshWebSocket?.disconnect()
                
                // Generate a node ID for this device
                val nodeId = preferences.getOrCreateNodeId()
                
                // Get capabilities to announce
                val capabilities = semanticRouter.getCapabilities()
                    .map { it.name }
                    .toList()
                    .take(10)
                
                // Try each endpoint until one works
                var connected = false
                var lastError: String? = null
                
                for ((name, ep) in endpointList) {
                    if (connected) break
                    
                    Log.i(TAG, "Trying $name endpoint: $ep")
                    _nodeState.value = _nodeState.value.copy(status = "Connecting ($name)...")
                    
                    meshWebSocket = MeshConnection(ep, token)
                    
                    // Use suspendCancellableCoroutine for timeout handling
                    val result = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                            meshWebSocket?.connectWithAuth(
                                nodeId = nodeId,
                                meshToken = tokenObject,
                                capabilities = capabilities,
                                nodeName = android.os.Build.MODEL,
                                onConnected = {
                                    Log.i(TAG, "$name: WebSocket opened")
                                },
                                onJoined = { meshName ->
                                    Log.i(TAG, "$name: Joined mesh $meshName")
                                    viewModelScope.launch {
                                        _isConnectedToMesh.value = true
                                        _meshName.value = meshName
                                        _savedMeshName.value = meshName
                                        _hasSavedMesh.value = true
                                        _nodeState.value = _nodeState.value.copy(status = "Connected via $name")
                                        // Save full connection info including endpoints map
                                        val endpointsJson = endpoints?.let { 
                                            JSONObject(it).toString() 
                                        }
                                        preferences.saveMeshConnectionFull(ep, token, meshName, endpointsJson, null)
                                        // Log connection event
                                        addMeshEvent(MeshEvent(
                                            type = "connected",
                                            title = "🔗 Connected via $name",
                                            detail = meshName ?: ep.take(30)
                                        ))
                                    }
                                    if (cont.isActive) cont.resume(true, null)
                                },
                                onError = { error ->
                                    Log.w(TAG, "$name: Connection error: $error")
                                    lastError = error
                                    if (cont.isActive) cont.resume(false, null)
                                }
                            )
                        }
                    }
                    
                    connected = result == true
                    if (!connected) {
                        Log.w(TAG, "$name endpoint failed, trying next...")
                        meshWebSocket?.disconnect()
                    }
                }
                
                if (!connected) {
                    Log.e(TAG, "All endpoints failed. Last error: $lastError")
                    _error.value = "Connection failed: ${lastError ?: "timeout"}"
                    _isConnectedToMesh.value = false
                    _relayConnectionState.value = ConnectionState.FAILED
                    _nodeState.value = _nodeState.value.copy(status = "Disconnected")
                } else {
                    _relayConnectionState.value = ConnectionState.CONNECTED
                }
                
                // Listen for messages in background
                viewModelScope.launch {
                    meshWebSocket?.messages?.collect { message ->
                        handleMeshMessage(message)
                    }
                }
                
                // Subscribe to peer updates from the connection
                viewModelScope.launch {
                    meshWebSocket?.peers?.collect { peers ->
                        _relayPeers.value = peers
                        _nodeState.value = _nodeState.value.copy(
                            connectedPeers = peers.size + _peers.value.size
                        )
                    }
                }
                
                // Subscribe to connection state changes
                viewModelScope.launch {
                    meshWebSocket?.connectionState?.collect { state ->
                        _relayConnectionState.value = state
                        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
                            _isConnectedToMesh.value = false
                            _relayPeers.value = emptyList()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join mesh", e)
                _error.value = "Connection error: ${e.message}"
                _isConnectedToMesh.value = false
            }
        }
    }
    
    /**
     * Handle incoming mesh messages.
     */
    private fun handleMeshMessage(message: MeshMessage) {
        when (message) {
            is MeshMessage.MeshStatus -> {
                Log.d(TAG, "Mesh status: ${message.peerCount} peers, caps: ${message.capabilities}")
                _nodeState.value = _nodeState.value.copy(connectedPeers = message.peerCount)
                addMeshEvent(MeshEvent(
                    type = "status",
                    title = "Mesh Status",
                    detail = "${message.peerCount} peers, ${message.capabilities.size} capabilities"
                ))
            }
            is MeshMessage.CostUpdate -> {
                Log.d(TAG, "Cost update from ${message.nodeId}: ${message.cost}")
                // Store peer cost for routing decisions
                _peerCosts.value = _peerCosts.value + (message.nodeId to message.cost)
                addMeshEvent(MeshEvent(
                    type = "cost",
                    title = "Cost Update",
                    detail = "cost=${String.format("%.2f", message.cost)}",
                    nodeId = message.nodeId
                ))
            }
            is MeshMessage.PeerList -> {
                Log.i(TAG, "Received peer list: ${message.peers.size} peers")
                _relayPeers.value = message.peers
                _nodeState.value = _nodeState.value.copy(
                    connectedPeers = message.peers.size + _peers.value.size
                )
                addMeshEvent(MeshEvent(
                    type = "peers",
                    title = "Peer List",
                    detail = message.peers.joinToString(", ") { it.name }
                ))
                // Register remote capabilities from peers
                message.peers.forEach { peer ->
                    peer.capabilities.forEach { cap ->
                        registerRemoteCapability(
                            name = cap,
                            description = "Remote capability from ${peer.name}",
                            nodeId = peer.nodeId,
                            cost = 1.5f // Slightly higher cost for remote
                        )
                    }
                }
            }
            is MeshMessage.PeerJoined -> {
                Log.i(TAG, "Peer joined: ${message.peer.name}")
                val currentPeers = _relayPeers.value.toMutableList()
                if (currentPeers.none { it.nodeId == message.peer.nodeId }) {
                    currentPeers.add(message.peer)
                    _relayPeers.value = currentPeers
                    _nodeState.value = _nodeState.value.copy(
                        connectedPeers = currentPeers.size + _peers.value.size
                    )
                }
                addMeshEvent(MeshEvent(
                    type = "peer_joined",
                    title = "📥 Peer Joined",
                    detail = "${message.peer.name} (${message.peer.capabilities.size} caps)",
                    nodeId = message.peer.nodeId
                ))
                // Register remote capabilities
                message.peer.capabilities.forEach { cap ->
                    registerRemoteCapability(
                        name = cap,
                        description = "Remote capability from ${message.peer.name}",
                        nodeId = message.peer.nodeId,
                        cost = 1.5f
                    )
                }
            }
            is MeshMessage.PeerLeft -> {
                Log.i(TAG, "Peer left: ${message.nodeId}")
                val currentPeers = _relayPeers.value.filter { it.nodeId != message.nodeId }
                _relayPeers.value = currentPeers
                _nodeState.value = _nodeState.value.copy(
                    connectedPeers = currentPeers.size + _peers.value.size
                )
                addMeshEvent(MeshEvent(
                    type = "peer_left",
                    title = "📤 Peer Left",
                    nodeId = message.nodeId
                ))
            }
            is MeshMessage.ChatResponse -> {
                Log.d(TAG, "Chat response received: ${message.content.take(50)}...")
                addMeshEvent(MeshEvent(
                    type = "chat",
                    title = "💬 Chat Response",
                    detail = message.content.take(80) + if (message.content.length > 80) "..." else ""
                ))
                // Response handling done via callback in MeshConnection
            }
            is MeshMessage.LlmResponse -> {
                Log.d(TAG, "LLM response received: ${message.response.take(50)}...")
                // 🎯 CAPTURE ROUTING INFO (THE CROWN JEWEL!)
                message.routing?.let { routing ->
                    _lastRoutingInfo.value = routing
                    addMeshEvent(MeshEvent(
                        type = "route",
                        title = "🎯 ${routing.complexity} → ${routing.modelSize}",
                        detail = "${routing.taskType} | ${routing.backend ?: "unknown"} | conf=${String.format("%.0f%%", routing.confidence * 100)}"
                    ))
                }
                addMeshEvent(MeshEvent(
                    type = "chat",
                    title = "💬 LLM Response",
                    detail = message.response.take(80) + if (message.response.length > 80) "..." else ""
                ))
            }
            is MeshMessage.Error -> {
                Log.e(TAG, "Mesh error: ${message.message}")
                _error.value = message.message
                addMeshEvent(MeshEvent(
                    type = "error",
                    title = "❌ Error",
                    detail = message.message
                ))
            }
            is MeshMessage.Joined -> {
                addMeshEvent(MeshEvent(
                    type = "joined",
                    title = "✅ Joined Mesh",
                    detail = message.meshName
                ))
            }
            is MeshMessage.Unknown -> {
                addMeshEvent(MeshEvent(
                    type = "unknown",
                    title = "📨 ${message.type}",
                    detail = message.raw.take(100)
                ))
            }
            else -> {
                // Other messages handled by specific callbacks
            }
        }
    }
    
    /**
     * Send an LLM prompt to the remote mesh.
     * 
     * @param prompt The prompt to send
     * @param model Optional model name
     * @param onResponse Callback with (response, error)
     */
    fun sendLlmPrompt(
        prompt: String,
        model: String? = null,
        onResponse: (response: String?, error: String?) -> Unit
    ) {
        val connection = meshWebSocket
        if (connection == null || !connection.isConnected) {
            onResponse(null, "Not connected to mesh")
            return
        }
        
        connection.sendLlmRequest(prompt, model) { response, error ->
            // Callback from WebSocket thread, post to main
            viewModelScope.launch(Dispatchers.Main) {
                onResponse(response, error)
            }
        }
    }
    
    /**
     * Send a chat message through the relay for LLM inference.
     * Uses the chat_request format for proper mesh routing.
     * 
     * @param messages List of message maps with "role" and "content" keys
     * @param model Optional model name ("auto" for automatic selection)
     * @param onResponse Callback with (content, error)
     */
    fun sendChatMessage(
        messages: List<Map<String, String>>,
        model: String = "auto",
        onResponse: (content: String?, error: String?) -> Unit
    ) {
        val connection = meshWebSocket
        if (connection == null || !connection.isConnected) {
            onResponse(null, "Not connected to mesh")
            return
        }
        
        connection.sendChatRequest(messages, model, "auto") { content, error ->
            viewModelScope.launch(Dispatchers.Main) {
                onResponse(content, error)
            }
        }
    }
    
    /**
     * Convenience method to send a simple user message.
     * 
     * @param content The user's message content
     * @param systemPrompt Optional system prompt
     * @param onResponse Callback with (content, error)
     */
    fun sendUserMessage(
        content: String,
        systemPrompt: String? = null,
        onResponse: (content: String?, error: String?) -> Unit
    ) {
        val messages = mutableListOf<Map<String, String>>()
        systemPrompt?.let {
            messages.add(mapOf("role" to "system", "content" to it))
        }
        messages.add(mapOf("role" to "user", "content" to content))
        sendChatMessage(messages, "auto", onResponse)
    }
    
    /**
     * Legacy: Join mesh via native Rust node (if available).
     */
    fun joinMeshNative(endpoint: String, token: String) {
        viewModelScope.launch {
            try {
                // Ensure node is running
                if (nativeNode == null) {
                    initializeNativeNode()
                    delay(500) // Give it time to initialize
                }
                
                nativeNode?.let { node ->
                    Log.i(TAG, "Joining mesh at $endpoint")
                    node.joinMesh(endpoint, token)
                    
                    // Update state
                    _isConnectedToMesh.value = true
                    
                    // Try to get mesh name from status
                    val statusJson = node.statusJson()
                    val status = JSONObject(statusJson)
                    _meshName.value = status.optString("mesh_name", null)
                    
                    _nodeState.value = _nodeState.value.copy(status = "Connected")
                    
                    // Save connection for auto-reconnect
                    preferences.saveMeshConnection(endpoint, token, _meshName.value)
                    
                    // Discover peers
                    discoverPeers()
                    
                    Log.i(TAG, "Successfully joined mesh")
                }
            } catch (e: AtmosphereException) {
                Log.e(TAG, "Failed to join mesh", e)
                _error.value = "Failed to join mesh: ${e.message}"
                _isConnectedToMesh.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error joining mesh", e)
                _error.value = "Connection error: ${e.message}"
                _isConnectedToMesh.value = false
            }
        }
    }
    
    /**
     * Disconnect from mesh.
     * @param clearSaved If true, also clears saved mesh connection (prevents auto-reconnect)
     */
    fun disconnectMesh(clearSaved: Boolean = true) {
        viewModelScope.launch {
            try {
                // Stop BLE transport
                stopBle()
                
                // Disconnect WebSocket connection
                meshWebSocket?.disconnect()
                meshWebSocket = null
                
                // Disconnect native node if present
                nativeNode?.disconnectMesh()
                
                _isConnectedToMesh.value = false
                _meshName.value = null
                _peers.value = emptyList()
                _relayPeers.value = emptyList()
                _blePeers.value = emptyList()
                _relayConnectionState.value = ConnectionState.DISCONNECTED
                _nodeState.value = _nodeState.value.copy(
                    status = if (_nodeState.value.isRunning) "Online" else "Offline",
                    connectedPeers = 0
                )
                
                // Clear saved connection only if requested
                if (clearSaved) {
                    preferences.clearMeshConnection()
                    _hasSavedMesh.value = false
                    _savedMeshName.value = null
                }
                
                Log.i(TAG, "Disconnected from mesh (clearSaved=$clearSaved)")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from mesh", e)
            }
        }
    }
    
    // ============================================================================
    // BLE Transport - Local mesh without internet!
    // ============================================================================
    
    /**
     * Start BLE transport for local mesh discovery.
     * Works even without internet - devices discover each other via Bluetooth.
     */
    fun startBle(meshId: String? = null) {
        Log.i(TAG, "🔵 startBle() called, meshId=$meshId")
        
        if (bleTransport != null) {
            Log.d(TAG, "BLE already running")
            return
        }
        
        try {
            Log.i(TAG, "🔵 Creating BleTransport...")
            val app = getApplication<Application>()
            val nodeId = _nodeState.value.nodeId ?: "android-${System.currentTimeMillis()}"
            
            bleTransport = BleTransport(
                context = app,
                nodeName = "Atmosphere-Android",
                capabilities = listOf("relay", "llm", "embeddings"),
                meshId = meshId ?: _meshName.value
            ).apply {
                onPeerDiscovered = { info ->
                    Log.i(TAG, "🔵 BLE peer discovered: ${info.name} (${info.nodeId})")
                    addMeshEvent(MeshEvent(type = "ble_peer", title = "BLE Peer Discovered", detail = "${info.name} via Bluetooth"))
                    viewModelScope.launch {
                        _blePeers.value = _blePeers.value + info
                    }
                }
                onPeerLost = { peerId ->
                    Log.i(TAG, "🔴 BLE peer lost: $peerId")
                    addMeshEvent(MeshEvent(type = "ble_peer", title = "BLE Peer Lost", detail = peerId))
                    viewModelScope.launch {
                        _blePeers.value = _blePeers.value.filter { it.nodeId != peerId }
                    }
                }
                onMessage = { msg ->
                    Log.d(TAG, "📨 BLE message from ${msg.sourceId}: ${msg.payload.size} bytes")
                    // Handle BLE mesh messages (gossip, chat requests, etc.)
                    handleBleMessage(msg)
                }
            }
            
            Log.i(TAG, "🔵 Calling bleTransport.start()...")
            bleTransport?.start()
            Log.i(TAG, "🔵 bleTransport.start() returned, setting _bleEnabled=true")
            _bleEnabled.value = true
            addMeshEvent(MeshEvent(type = "ble", title = "BLE Started", detail = "Scanning for local Atmosphere nodes"))
            Log.i(TAG, "✅ BLE transport started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start BLE transport: ${e.message}", e)
            addMeshEvent(MeshEvent(type = "error", title = "BLE Failed", detail = e.message ?: "Unknown error"))
            _bleEnabled.value = false
        }
    }
    
    /**
     * Stop BLE transport.
     */
    fun stopBle() {
        bleTransport?.stop()
        bleTransport = null
        _bleEnabled.value = false
        _blePeers.value = emptyList()
        Log.i(TAG, "BLE transport stopped")
    }
    
    /**
     * Handle incoming BLE mesh message.
     */
    private fun handleBleMessage(msg: com.llamafarm.atmosphere.transport.BleMessage) {
        try {
            when (msg.header.msgType) {
                com.llamafarm.atmosphere.transport.MessageType.HELLO -> {
                    // Peer announcing itself
                    val payload = String(msg.payload, Charsets.UTF_8)
                    Log.d(TAG, "BLE HELLO from ${msg.sourceId}: $payload")
                }
                com.llamafarm.atmosphere.transport.MessageType.DATA -> {
                    // Generic data message - could be chat, gossip, etc.
                    val payload = String(msg.payload, Charsets.UTF_8)
                    Log.d(TAG, "BLE DATA from ${msg.sourceId}: $payload")
                }
                else -> {
                    Log.d(TAG, "BLE message type: ${msg.header.msgType}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling BLE message", e)
        }
    }
    
    /**
     * Get BLE peer count.
     */
    fun getBleePeerCount(): Int = _blePeers.value.size
    
    /**
     * Forget saved mesh (clears saved connection without affecting current connection state).
     */
    fun forgetSavedMesh() {
        viewModelScope.launch {
            preferences.clearMeshConnection()
            _hasSavedMesh.value = false
            _savedMeshName.value = null
            Log.i(TAG, "Forgot saved mesh connection")
        }
    }
    
    /**
     * Called when app resumes from background/sleep.
     * Checks mesh connection state and reconnects if needed.
     */
    fun onAppResume() {
        viewModelScope.launch {
            val wsConnected = meshWebSocket?.isConnected == true
            val isConnected = _isConnectedToMesh.value
            
            Log.i(TAG, "📱 onAppResume: wsConnected=$wsConnected, isConnected=$isConnected")
            
            // Check if we should be connected but aren't
            val autoReconnect = preferences.autoReconnectMesh.first()
            val endpoint = preferences.lastMeshEndpoint.first()
            val token = preferences.lastMeshToken.first()
            
            Log.d(TAG, "Resume check: autoReconnect=$autoReconnect, hasEndpoint=${endpoint != null}, hasToken=${token != null}")
            
            if (autoReconnect && endpoint != null && token != null) {
                if (!wsConnected && !isConnected) {
                    val savedMeshName = preferences.lastMeshName.first()
                    Log.i(TAG, "🔄 Reconnecting to mesh on app resume: $savedMeshName")
                    // Parse token string back to JSONObject for relay auth
                    val tokenObject = try {
                        JSONObject(token)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse saved token as JSON, using as string")
                        null
                    }
                    joinMesh(endpoint, token, tokenObject)
                } else if (wsConnected && !isConnected) {
                    // WebSocket connected but state says not connected - fix state
                    _isConnectedToMesh.value = true
                    Log.i(TAG, "Fixed mesh connection state on resume")
                } else {
                    Log.d(TAG, "Already connected, no action needed")
                }
            } else {
                Log.d(TAG, "No saved mesh to reconnect to")
            }
        }
    }
    
    /**
     * Discover peers on the mesh.
     */
    fun discoverPeers() {
        viewModelScope.launch {
            try {
                nativeNode?.let { node ->
                    val peerList = node.discoverPeersList()
                    _peers.value = peerList
                    _nodeState.value = _nodeState.value.copy(connectedPeers = peerList.size)
                    Log.i(TAG, "Discovered ${peerList.size} peers")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to discover peers", e)
            }
        }
    }
    
    /**
     * Connect to a specific peer.
     */
    fun connectToPeer(address: String) {
        viewModelScope.launch {
            try {
                nativeNode?.connectToPeer(address)
                refreshPeers()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to peer", e)
                _error.value = "Failed to connect: ${e.message}"
            }
        }
    }
    
    /**
     * Send a gossip message.
     */
    fun sendGossip(message: String) {
        viewModelScope.launch {
            try {
                nativeNode?.sendGossip(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send gossip", e)
                _error.value = "Failed to send: ${e.message}"
            }
        }
    }

    /**
     * Clear any error state.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Set node name.
     */
    fun setNodeName(name: String) {
        viewModelScope.launch {
            preferences.setNodeName(name)
        }
    }

    override fun onCleared() {
        super.onCleared()
        
        // Clean up BLE transport
        stopBle()
        
        // Clean up WebSocket connection
        meshWebSocket?.disconnect()
        meshWebSocket = null
        
        // Clean up native node
        nativeNode?.let {
            try {
                it.stop()
                it.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up native node", e)
            }
        }
        nativeNode = null
    }
}
