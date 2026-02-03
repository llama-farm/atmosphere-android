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
import com.llamafarm.atmosphere.router.DefaultCapabilities
import com.llamafarm.atmosphere.router.HttpEmbeddingService
import com.llamafarm.atmosphere.router.RouteResult
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.service.AtmosphereService
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
    
    // Peer list
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    
    // Preferences
    private val preferences = AtmospherePreferences(application)
    
    // Native node reference (for direct calls when service isn't available)
    private var nativeNode: AtmosphereNode? = null
    
    // WebSocket mesh connection (pure Kotlin, no Rust needed)
    private var meshWebSocket: MeshConnection? = null
    
    // Semantic router for intent-based routing
    val semanticRouter = SemanticRouter(application)
    
    // Last route result
    private val _lastRouteResult = MutableStateFlow<RouteResult?>(null)
    val lastRouteResult: StateFlow<RouteResult?> = _lastRouteResult.asStateFlow()
    
    // Mesh WebSocket connection state
    val meshConnectionState: StateFlow<ConnectionState>
        get() = meshWebSocket?.connectionState ?: MutableStateFlow(ConnectionState.DISCONNECTED)
    
    // WebSocket mesh name (separate from native mesh)
    val webSocketMeshName: StateFlow<String?>
        get() = meshWebSocket?.meshName ?: MutableStateFlow(null)

    init {
        loadSavedState()
        startPeerPolling()
        initializeRouter()
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
        semanticRouter.registerRemoteCapability(name, description, nodeId, null, cost)
        Log.d(TAG, "Registered remote capability: $name from $nodeId")
    }

    private fun loadSavedState() {
        viewModelScope.launch {
            // Load persisted node ID
            preferences.nodeId.first()?.let { savedId ->
                _nodeState.value = _nodeState.value.copy(nodeId = savedId)
            }
            
            // Check for auto-reconnect to mesh
            val autoReconnect = preferences.autoReconnectMesh.first()
            if (autoReconnect) {
                val endpoint = preferences.lastMeshEndpoint.first()
                val token = preferences.lastMeshToken.first()
                val savedMeshName = preferences.lastMeshName.first()
                
                if (endpoint != null && token != null) {
                    Log.i(TAG, "Auto-reconnecting to mesh: $savedMeshName")
                    joinMesh(endpoint, token)
                }
            }
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
     */
    fun joinMesh(endpoint: String, token: String) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Joining mesh via WebSocket at $endpoint")
                
                // Disconnect existing connection if any
                meshWebSocket?.disconnect()
                
                // Create new connection
                meshWebSocket = MeshConnection(endpoint, token)
                
                meshWebSocket?.connect(
                    onConnected = {
                        Log.i(TAG, "WebSocket connected, waiting for join confirmation...")
                    },
                    onJoined = { meshName ->
                        Log.i(TAG, "Successfully joined mesh: $meshName")
                        viewModelScope.launch {
                            _isConnectedToMesh.value = true
                            _meshName.value = meshName
                            _nodeState.value = _nodeState.value.copy(status = "Connected")
                            
                            // Save connection for auto-reconnect
                            preferences.saveMeshConnection(endpoint, token, meshName)
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "WebSocket error: $error")
                        viewModelScope.launch {
                            _error.value = "Connection error: $error"
                            _isConnectedToMesh.value = false
                        }
                    }
                )
                
                // Also listen for messages in background
                viewModelScope.launch {
                    meshWebSocket?.messages?.collect { message ->
                        handleMeshMessage(message)
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
            }
            is MeshMessage.CostUpdate -> {
                Log.d(TAG, "Cost update from ${message.nodeId}: ${message.cost}")
            }
            is MeshMessage.Error -> {
                Log.e(TAG, "Mesh error: ${message.message}")
                _error.value = message.message
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
        if (connection == null || !connection.isConnected()) {
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
     */
    fun disconnectMesh() {
        viewModelScope.launch {
            try {
                // Disconnect WebSocket connection
                meshWebSocket?.disconnect()
                meshWebSocket = null
                
                // Disconnect native node if present
                nativeNode?.disconnectMesh()
                
                _isConnectedToMesh.value = false
                _meshName.value = null
                _peers.value = emptyList()
                _nodeState.value = _nodeState.value.copy(
                    status = if (_nodeState.value.isRunning) "Online" else "Offline",
                    connectedPeers = 0
                )
                
                // Clear saved connection
                preferences.clearMeshConnection()
                
                Log.i(TAG, "Disconnected from mesh")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from mesh", e)
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
