package com.llamafarm.atmosphere.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.bindings.AtmosphereException
import com.llamafarm.atmosphere.bindings.AtmosphereNode
import com.llamafarm.atmosphere.bindings.MeshPeer
import com.llamafarm.atmosphere.service.ServiceManager
import com.llamafarm.atmosphere.service.ServiceStatus
import com.llamafarm.atmosphere.data.AtmospherePreferences
import com.llamafarm.atmosphere.data.SavedMesh
import com.llamafarm.atmosphere.data.SavedMeshRepository
import com.llamafarm.atmosphere.data.DaemonRepository
import com.llamafarm.atmosphere.data.DaemonPeer
import com.llamafarm.atmosphere.data.DaemonCapability
import com.llamafarm.atmosphere.data.BigLlamaStatus
import com.llamafarm.atmosphere.data.DaemonInfo
// RelayPeer import removed — CRDT mesh replaces relay
import com.llamafarm.atmosphere.network.TransportStatus
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.router.RoutingDecision
import com.llamafarm.atmosphere.core.GossipManager
import com.llamafarm.atmosphere.service.AtmosphereService
// MeshConnection import removed — CRDT mesh replaces relay
// ConnectionState import removed — CRDT mesh replaces relay
import com.llamafarm.atmosphere.network.MeshMessage
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
    
    // CRDT peer list (from CRDT mesh)
    private val _relayPeers = MutableStateFlow<List<com.llamafarm.atmosphere.core.PeerInfo>>(emptyList())
    val relayPeers: StateFlow<List<com.llamafarm.atmosphere.core.PeerInfo>> = _relayPeers.asStateFlow()
    
    // Combined peer count
    val peerCount: Int
        get() = _peers.value.size + _relayPeers.value.size
    
    // CRDT connection state (for UI)
    private val _crdtConnected = MutableStateFlow(false)
    val crdtConnected: StateFlow<Boolean> = _crdtConnected.asStateFlow()
    
    // Cost tracking - THE CROWN JEWEL
    private val _localCost = MutableStateFlow(1.0f)
    val localCost: StateFlow<Float> = _localCost.asStateFlow()
    
    private val _peerCosts = MutableStateFlow<Map<String, Float>>(emptyMap())
    val peerCosts: StateFlow<Map<String, Float>> = _peerCosts.asStateFlow()
    
    // Cost collector for local device metrics
    private val costCollector = (application as? com.llamafarm.atmosphere.AtmosphereApplication)?.costCollector
    
    // Preferences
    private val preferences = AtmospherePreferences(application)
    
    // Saved mesh repository
    private val meshRepository = SavedMeshRepository(preferences)
    
    // All saved meshes
    private val _savedMeshes = MutableStateFlow<List<SavedMesh>>(emptyList())
    val savedMeshes: StateFlow<List<SavedMesh>> = _savedMeshes.asStateFlow()
    
    // Currently connected mesh ID
    private val _currentMeshId = MutableStateFlow<String?>(null)
    val currentMeshId: StateFlow<String?> = _currentMeshId.asStateFlow()
    
    // Transport statuses (from ConnectionTrain via Service)
    private val _transportStatuses = MutableStateFlow<Map<String, TransportStatus>>(emptyMap())
    val transportStatuses: StateFlow<Map<String, TransportStatus>> = _transportStatuses.asStateFlow()
    
    // Active transport type (from Service)
    private val _activeTransportType = MutableStateFlow<String?>(null)
    val activeTransportType: StateFlow<String?> = _activeTransportType.asStateFlow()
    
    // Service connector for real-time state
    val serviceConnector = try {
        ServiceManager.getConnector()
    } catch (e: Exception) {
        Log.w(TAG, "ServiceManager not initialized, creating new connector")
        null
    }
    
    // Expose service status for UI
    val serviceStatus: StateFlow<ServiceStatus> = serviceConnector?.status ?: MutableStateFlow(ServiceStatus())
    
    // Native node reference (for direct calls when service isn't available)
    private var nativeNode: AtmosphereNode? = null
    
    // BLE Transport removed - will be added back as Rust transport in atmosphere-core
    private val _blePeers = MutableStateFlow<List<NodeInfo>>(emptyList())
    val blePeers: StateFlow<List<NodeInfo>> = _blePeers.asStateFlow()
    
    private val _bleEnabled = MutableStateFlow(false)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()
    
    // ============================================================================
    // Daemon Integration - atmosphere-core daemon (localhost:11462)
    // ============================================================================
    
    // Daemon repository for HTTP communication
    private val daemonRepository = DaemonRepository()
    
    // Daemon connection state
    private val _daemonConnected = MutableStateFlow(false)
    val daemonConnected: StateFlow<Boolean> = _daemonConnected.asStateFlow()
    
    // Daemon peers (from CRDT)
    private val _daemonPeers = MutableStateFlow<List<DaemonPeer>>(emptyList())
    val daemonPeers: StateFlow<List<DaemonPeer>> = _daemonPeers.asStateFlow()
    
    // Daemon capabilities (from CRDT)
    private val _daemonCapabilities = MutableStateFlow<List<DaemonCapability>>(emptyList())
    val daemonCapabilities: StateFlow<List<DaemonCapability>> = _daemonCapabilities.asStateFlow()
    
    // BigLlama status from daemon
    private val _bigLlamaStatus = MutableStateFlow<BigLlamaStatus?>(null)
    val bigLlamaStatus: StateFlow<BigLlamaStatus?> = _bigLlamaStatus.asStateFlow()
    
    // Daemon info
    private val _daemonInfo = MutableStateFlow<DaemonInfo?>(null)
    val daemonInfo: StateFlow<DaemonInfo?> = _daemonInfo.asStateFlow()
    
    // Semantic router for intent-based routing
    val semanticRouter = SemanticRouter.getInstance(application)
    private val gossipManager = GossipManager.getInstance(application)
    
    // Last route result (local semantic router)
    private val _lastRouteResult = MutableStateFlow<RoutingDecision?>(null)
    val lastRouteResult: StateFlow<RoutingDecision?> = _lastRouteResult.asStateFlow()
    
    // 🎯 LAST ROUTING DECISION (THE CROWN JEWEL!)
    private val _lastRoutingDecision = MutableStateFlow<RoutingDecision?>(null)
    val lastRoutingDecision: StateFlow<RoutingDecision?> = _lastRoutingDecision.asStateFlow()
    
    // Gossip stats from GossipManager
    private val _gossipStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val gossipStats: StateFlow<Map<String, Any>> = _gossipStats.asStateFlow()
    
    // Saved mesh info for UI display (MUST be before init block!)
    private val _savedMeshName = MutableStateFlow<String?>(null)
    val savedMeshName: StateFlow<String?> = _savedMeshName.asStateFlow()
    
    private val _hasSavedMesh = MutableStateFlow(false)
    val hasSavedMesh: StateFlow<Boolean> = _hasSavedMesh.asStateFlow()
    
    // 📡 MESH EVENT LOG - Shows gossip, peer events, routing decisions
    data class MeshEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val type: String,  // "gossip", "peer_joined", "peer_left", "cost", "route", "chat", "error", "lan"
        val title: String,
        val detail: String? = null,
        val nodeId: String? = null,
        val metadata: Map<String, String> = emptyMap()  // Extra key-value pairs for detail view
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
    
    // CRDT mesh connection state
    val meshConnectionState: StateFlow<Boolean>
        get() = crdtConnected
    
    // WebSocket mesh name (separate from native mesh)
    val webSocketMeshName: StateFlow<String?>
        get() = MutableStateFlow(null)

    init {
        // Auto-start everything on app launch
        Log.i(TAG, "🚀 ViewModel initializing - starting service")
        // Start the service to ensure it runs in background
        startService()
        
        // Initialize local components
        initializeSavedMeshes()
        loadSavedState()
        startPeerPolling()
        initializeRouter()
        startCostCollection()
        
        // Observe service status for real-time UI updates
        observeServiceStatus()
        
        // Observe mesh events from service
        observeServiceMeshEvents()
        
        // Start daemon polling
        startDaemonPolling()
        
        // Poll gossip stats periodically (get initial stats immediately!)
        viewModelScope.launch {
            _gossipStats.value = gossipManager.getStats()  // Initial stats
            while (true) {
                delay(3000)
                val newStats = gossipManager.getStats()
                Log.d(TAG, "📊 Gossip stats update: ${newStats["total_capabilities"]} caps")
                _gossipStats.value = newStats
            }
        }
        
        // Observe CRDT peers from service
        viewModelScope.launch {
            var attempts = 0
            while (attempts < 30) {
                val peers = serviceConnector?.getServiceCrdtPeers()
                if (peers != null) {
                    peers.collect { peerList ->
                        _relayPeers.value = peerList
                    }
                    break
                }
                attempts++
                delay(1000)
            }
        }
    }
    
    /**
     * Observe service status and update node state accordingly.
     */
    private fun observeServiceStatus() {
        viewModelScope.launch {
            serviceStatus.collect { status ->
                Log.d(TAG, "📡 Service status update: ${status.statusText}")
                
                // Update node state from service
                _nodeState.value = _nodeState.value.copy(
                    isRunning = status.isRunning,
                    nodeId = status.nodeId ?: _nodeState.value.nodeId,
                    status = status.statusText,
                    connectedPeers = status.connectedPeers
                )
                
                // Update mesh connection state
                _isConnectedToMesh.value = status.crdtConnected
                _crdtConnected.value = status.crdtConnected
                _activeTransportType.value = status.activeTransport
                
                // Update local cost
                status.currentCost?.let { cost ->
                    _localCost.value = cost
                }
                
                // Add mesh event for status changes
                if (status.crdtConnected && !_isConnectedToMesh.value) {
                    addMeshEvent(MeshEvent(
                        type = "connected",
                        title = "Connected to Mesh",
                        detail = "via ${status.activeTransport ?: "unknown"}"
                    ))
                }
            }
        }
    }
    
    /**
     * Observe mesh events from the service.
     * Bridges service-level events into the ViewModel's meshEvents flow.
     * Retries until the service is bound.
     */
    private fun observeServiceMeshEvents() {
        viewModelScope.launch {
            // Wait for service connector to be bound first
            Log.i(TAG, "📡 Waiting for service to bind before observing mesh events...")
            serviceConnector?.bound?.first { it }
            Log.i(TAG, "📡 Service bound! Starting mesh events observation...")
            
            // Now get the mesh events flow
            var attempts = 0
            while (attempts < 60) {  // Increased timeout
                val events = serviceConnector?.getServiceMeshEvents()
                if (events != null) {
                    Log.i(TAG, "📡 Connected to service mesh events flow (attempt $attempts)")
                    events.collect { svcEvents ->
                        val vmEvents = svcEvents.map { svcEvent ->
                            MeshEvent(
                                timestamp = svcEvent.timestamp,
                                type = svcEvent.type,
                                title = svcEvent.title,
                                detail = svcEvent.detail,
                                nodeId = svcEvent.nodeId,
                                metadata = svcEvent.metadata
                            )
                        }
                        _meshEvents.value = vmEvents  // Always update, even if empty
                        if (vmEvents.isNotEmpty()) {
                            Log.d(TAG, "📡 Mesh events updated: ${vmEvents.size} events")
                        }
                    }
                    break  // collect never returns unless cancelled
                }
                attempts++
                if (attempts % 10 == 0) {
                    Log.w(TAG, "Still waiting for service mesh events (attempt $attempts/60)")
                }
                delay(1000)
            }
            if (attempts >= 60) {
                Log.e(TAG, "❌ Failed to connect to service mesh events after 60 attempts")
            }
        }
    }
    
    /**
     * Start polling the atmosphere-core daemon for state updates.
     * Collects daemon state flows and updates ViewModel state.
     */
    /**
     * Observe CRDT mesh state directly from AtmosphereService's in-process core.
     * This supplements the HTTP-based daemon polling with real-time local state.
     */
    private fun observeCrdtMeshState() {
        viewModelScope.launch {
            // Wait for service to bind
            serviceConnector?.bound?.first { it } ?: return@launch
            Log.i(TAG, "🔮 Starting CRDT mesh state observation")
            
            while (true) {
                delay(3000)
                try {
                    val service = serviceConnector?.getService() ?: continue
                    val core = service.getAtmosphereCore() ?: continue
                    
                    // Update daemon connected (local core is always "connected")
                    _daemonConnected.value = true
                    
                    // Update peers from CRDT mesh
                    val crdtPeers = core.connectedPeers()
                    _daemonPeers.value = crdtPeers.map { p ->
                        DaemonPeer(
                            nodeId = p.peerId,
                            nodeName = p.peerId.take(8),
                            capabilities = emptyList(),
                            lastSeen = p.lastSeen,
                            metadata = mapOf("transport" to p.transport)
                        )
                    }
                    
                    // Update daemon info from local core
                    _daemonInfo.value = DaemonInfo(
                        nodeId = core.peerId,
                        nodeName = "BigLlama-Android",
                        version = "1.0",
                        uptime = 0,
                        peerCount = crdtPeers.size,
                        capabilityCount = _daemonCapabilities.value.size
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "CRDT state poll error: ${e.message}")
                }
            }
        }
    }
    
    private fun startDaemonPolling() {
        Log.i(TAG, "🔧 Starting daemon polling")
        daemonRepository.startPolling(intervalMs = 5000)
        
        // Also observe CRDT mesh directly from service
        observeCrdtMeshState()
        
        // Observe daemon state flows (HTTP-based, kept as fallback)
        viewModelScope.launch {
            daemonRepository.isConnected.collect { connected ->
                // Only update if CRDT core isn't providing state
                if (serviceConnector?.getService()?.getAtmosphereCore() == null) {
                    _daemonConnected.value = connected
                }
                if (connected) {
                    Log.d(TAG, "✅ Daemon connected")
                } else {
                    Log.d(TAG, "❌ Daemon disconnected")
                }
            }
        }
        
        viewModelScope.launch {
            daemonRepository.peers.collect { peers ->
                _daemonPeers.value = peers
                Log.d(TAG, "Daemon peers updated: ${peers.size}")
            }
        }
        
        viewModelScope.launch {
            daemonRepository.capabilities.collect { caps ->
                _daemonCapabilities.value = caps
                Log.d(TAG, "Daemon capabilities updated: ${caps.size}")
            }
        }
        
        viewModelScope.launch {
            daemonRepository.bigLlamaStatus.collect { status ->
                _bigLlamaStatus.value = status
                status?.let {
                    Log.d(TAG, "BigLlama status: ${it.mode}")
                }
            }
        }
        
        viewModelScope.launch {
            daemonRepository.daemonInfo.collect { info ->
                _daemonInfo.value = info
            }
        }
    }
    
    /**
     * Observe mesh messages — relay removed, CRDT mesh handles all messaging.
     * Kept as stub for callers that haven't been updated yet.
     */
    @Deprecated("Relay removed — CRDT mesh handles messaging")
    fun observeMeshMessages(connection: Any) {
        Log.w(TAG, "observeMeshMessages called but relay is removed — CRDT mesh handles all messaging")
    }
    
    /**
     * Initialize saved meshes from storage and migrate legacy format.
     */
    private fun initializeSavedMeshes() {
        viewModelScope.launch {
            try {
                // Migrate legacy single-mesh connection to new format
                preferences.migrateLegacyMeshConnection()
                
                // Load all saved meshes
                refreshSavedMeshes()
                
                Log.i(TAG, "📦 Loaded ${_savedMeshes.value.size} saved meshes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize saved meshes", e)
            }
        }
    }
    
    /**
     * Refresh saved meshes from storage.
     */
    suspend fun refreshSavedMeshes() {
        _savedMeshes.value = meshRepository.getAllMeshes()
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
     * Note: SemanticRouter is now a singleton and doesn't need explicit initialization.
     */
    private fun initializeRouter() {
        viewModelScope.launch {
            try {
                // Initialize GossipManager with our node ID (persisted across restarts)
                val nodeId = preferences.getOrCreateNodeId()
                _nodeState.value = _nodeState.value.copy(nodeId = nodeId)
                gossipManager.initialize(nodeId, "Atmosphere-Android")
                
                Log.i(TAG, "Semantic router and gossip manager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize router", e)
            }
        }
    }
    
    /**
     * Route an intent to a capability.
     * 
     * @param intent Natural language intent (e.g., "take a photo")
     * @return RoutingDecision with matched capability and score
     */
    suspend fun routeIntent(intent: String): RoutingDecision? {
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
     * Note: Execution is now handled separately after routing.
     */
    fun routeAndExecute(intent: String, onResult: (JSONObject) -> Unit) {
        viewModelScope.launch {
            try {
                val decision = semanticRouter.route(intent)
                if (decision != null) {
                    // TODO: Execute the capability based on decision
                    Log.i(TAG, "Routed to: ${decision.capability.label} on ${decision.capability.nodeName}")
                    onResult(JSONObject().apply {
                        put("success", true)
                        put("capability", decision.capability.label)
                        put("node", decision.capability.nodeName)
                    })
                } else {
                    onResult(JSONObject().apply {
                        put("success", false)
                        put("error", "No capability found for intent")
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route and execute failed", e)
                onResult(JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                })
            }
        }
    }

    private fun loadSavedState() {
        viewModelScope.launch {
            Log.i(TAG, "=== loadSavedState() starting ===")
            
            // Load persisted node ID
            preferences.nodeId.first()?.let { savedId ->
                _nodeState.value = _nodeState.value.copy(nodeId = savedId)
                Log.d(TAG, "Loaded node ID: $savedId")
            }
            
            // Load ALL saved meshes for "My Meshes" display
            val allMeshes = preferences.getSavedMeshes()
            _savedMeshes.value = allMeshes
            Log.i(TAG, "Loaded ${allMeshes.size} saved meshes")
            
            // Check if we have a saved mesh (for UI display) - legacy single mesh
            val token = preferences.lastMeshToken.first()
            val savedMeshName = preferences.lastMeshName.first()
            val endpoint = preferences.lastMeshEndpoint.first()
            val autoReconnect = preferences.autoReconnectMesh.first()
            
            Log.i(TAG, "Saved mesh state: name=$savedMeshName, hasToken=${token != null}, hasEndpoint=${endpoint != null}, autoReconnect=$autoReconnect")
            
            _hasSavedMesh.value = token != null || allMeshes.isNotEmpty()
            _savedMeshName.value = savedMeshName
            
            // Note: Auto-reconnect is now handled by AtmosphereService on start
        }
    }
    
    /**
     * Reconnect to a specific saved mesh using legacy method.
     */
    fun reconnectToMeshLegacy(mesh: SavedMesh) {
        // Delegate to service via intent
        val intent = android.content.Intent(getApplication(), AtmosphereService::class.java).apply {
            action = "com.llamafarm.atmosphere.action.CONNECT_MESH"
            putExtra("meshId", mesh.meshId)
        }
        getApplication<Application>().startService(intent)
    }
    
    /**
     * Update last connected time for a mesh.
     */
    private fun updateMeshLastConnected(meshId: String) {
        viewModelScope.launch {
            val meshes = _savedMeshes.value.map { mesh ->
                if (mesh.meshId == meshId) {
                    mesh.copy(lastConnected = System.currentTimeMillis())
                } else {
                    mesh
                }
            }
            _savedMeshes.value = meshes
            preferences.saveMeshes(meshes)
        }
    }
    
    /**
     * Forget a specific saved mesh.
     */
    fun forgetMesh(meshId: String) {
        viewModelScope.launch {
            Log.i(TAG, "Forgetting mesh: $meshId")
            preferences.removeMesh(meshId)
            _savedMeshes.value = _savedMeshes.value.filter { it.meshId != meshId }
            
            // If this was the currently connected mesh, disconnect
            if (_meshName.value == _savedMeshes.value.find { it.meshId == meshId }?.meshName) {
                disconnectMesh(clearSaved = false)
            }
        }
    }
    
    /**
     * Reload saved meshes from storage.
     */
    fun reloadSavedMeshes() {
        viewModelScope.launch {
            _savedMeshes.value = preferences.getSavedMeshes()
        }
    }
    
    /**
     * Attempt to reconnect to saved mesh using full endpoints map.
     */
    fun attemptReconnect() {
        viewModelScope.launch {
            // Find best mesh to reconnect to
            val meshes = meshRepository.getAutoReconnectMeshes()
            if (meshes.isNotEmpty()) {
                connectToSavedMesh(meshes.first().meshId)
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
    }
    
    /**
     * Stop the Atmosphere service.
     */
    fun stopService() {
        val context = getApplication<Application>()
        AtmosphereService.stop(context)
        
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
                Log.e(TAG, "🔥 joinMesh called! Token len=${token.length}, tokenObject=${tokenObject != null}")
                
                // Extract mesh info - try multiple sources
                var meshId: String? = null
                var meshName: String? = null
                
                // 1. Try tokenObject first (Mac format stores full invite here)
                if (tokenObject != null) {
                    meshId = tokenObject.optString("mesh_id").takeIf { it.isNotEmpty() }
                    meshName = tokenObject.optString("mesh_name").takeIf { it.isNotEmpty() }
                    Log.e(TAG, "🔥 From tokenObject: meshId=$meshId, meshName=$meshName")
                }
                
                // 2. If not found in tokenObject, parse the token string
                if (meshId.isNullOrEmpty() && token.isNotEmpty()) {
                    try {
                        val json = JSONObject(token)
                        Log.e(TAG, "🔥 Token JSON keys: ${json.keys().asSequence().toList()}")
                        
                        // Try root level first
                        meshId = json.optString("mesh_id").takeIf { it.isNotEmpty() }
                        
                        // Then try nested 'token' object (v1 format)
                        if (meshId.isNullOrEmpty()) {
                            meshId = json.optJSONObject("token")?.optString("mesh_id")?.takeIf { it.isNotEmpty() }
                        }
                        
                        // Try 'mesh' object (v1 format)
                        if (meshId.isNullOrEmpty()) {
                            val meshObj = json.optJSONObject("mesh")
                            meshId = meshObj?.optString("id")?.takeIf { it.isNotEmpty() }
                                ?: meshObj?.optString("mesh_id")?.takeIf { it.isNotEmpty() }
                            if (meshName.isNullOrEmpty()) {
                                meshName = meshObj?.optString("name")?.takeIf { it.isNotEmpty() }
                            }
                        }
                        
                        // Try 'm' object (v2 short format)
                        if (meshId.isNullOrEmpty()) {
                            val mObj = json.optJSONObject("m")
                            meshId = mObj?.optString("id")?.takeIf { it.isNotEmpty() }
                                ?: mObj?.optString("i")?.takeIf { it.isNotEmpty() }
                            if (meshName.isNullOrEmpty()) {
                                meshName = mObj?.optString("n")?.takeIf { it.isNotEmpty() }
                            }
                        }
                        
                        // Fallback: mesh_name at root
                        if (meshName.isNullOrEmpty()) {
                            meshName = json.optString("mesh_name").takeIf { it.isNotEmpty() }
                        }
                        
                        Log.e(TAG, "🔥 From token string: meshId=$meshId, meshName=$meshName")
                    } catch (e: Exception) {
                        Log.e(TAG, "🔥 Failed to parse token as JSON: ${e.message}")
                    }
                }
                
                // 3. Also try tokenObject for nested 'mesh' or 'm' objects we might have missed
                if (meshId.isNullOrEmpty() && tokenObject != null) {
                    val meshObj = tokenObject.optJSONObject("mesh") ?: tokenObject.optJSONObject("m")
                    meshId = meshObj?.optString("id")?.takeIf { it.isNotEmpty() }
                        ?: meshObj?.optString("mesh_id")?.takeIf { it.isNotEmpty() }
                        ?: meshObj?.optString("i")?.takeIf { it.isNotEmpty() }
                    if (meshName.isNullOrEmpty()) {
                        meshName = meshObj?.optString("name")?.takeIf { it.isNotEmpty() }
                            ?: meshObj?.optString("n")?.takeIf { it.isNotEmpty() }
                    }
                    Log.e(TAG, "🔥 From tokenObject nested mesh: meshId=$meshId, meshName=$meshName")
                }
                
                // 3. Final fallback
                if (meshId.isNullOrEmpty()) {
                    Log.e(TAG, "🔥 CRITICAL: Could not extract mesh_id from any source!")
                    meshId = "unknown"
                }
                if (meshName.isNullOrEmpty()) {
                    meshName = "New Mesh"
                }
                
                Log.e(TAG, "🔥 Final values: meshId=$meshId, meshName=$meshName")
                
                val finalEndpoints = endpoints ?: mapOf("primary" to endpoint)
                
                Log.e(TAG, "🔥 Joining mesh: $meshName ($meshId) via $endpoint")
                
                // Save to repository
                val savedMesh = saveMeshFromJoin(
                    meshId = meshId,
                    meshName = meshName,
                    founderId = tokenObject?.optString("issuer_id") ?: "unknown",
                    founderName = "Unknown",
                    token = token,
                    endpoints = finalEndpoints
                )
                
                // Connect via Service
                connectToSavedMesh(savedMesh.meshId)
                
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
        // ... existing logic ...
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
        val connector = serviceConnector
        if (connector == null) {
            onResponse(null, "Service not available")
            return
        }
        connector.sendLlmRequest(prompt, model, onResponse)
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
        val connector = serviceConnector
        if (connector == null) {
            onResponse(null, "Service not available")
            return
        }
        connector.sendChatRequest(messages, model, onResponse)
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
        // ... existing logic ...
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
                
                // Stop connection via service
                val intent = android.content.Intent(getApplication(), AtmosphereService::class.java).apply {
                    action = "com.llamafarm.atmosphere.action.DISCONNECT_MESH"
                }
                getApplication<Application>().startService(intent)
                
                // Update local state
                _isConnectedToMesh.value = false
                _meshName.value = null
                _currentMeshId.value = null
                _peers.value = emptyList()
                _relayPeers.value = emptyList()
                _blePeers.value = emptyList()
                _crdtConnected.value = false
                _transportStatuses.value = emptyMap()
                _activeTransportType.value = null
                
                // Clear saved connection only if requested
                if (clearSaved) {
                    preferences.clearMeshConnection()
                    _hasSavedMesh.value = false
                    _savedMeshName.value = null
                    
                    // Also update repository to disable auto-reconnect for this mesh
                    _currentMeshId.value?.let { id ->
                        meshRepository.setAutoReconnect(id, false)
                    }
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
    /**
     * LEGACY - BLE transport removed. Will be re-added as Rust transport in atmosphere-core.
     */
    fun startBle(meshId: String? = null) {
        Log.i(TAG, "startBle() called but BLE transport is disabled (will be added back as Rust transport)")
        _bleEnabled.value = false
    }
    
    /**
     * LEGACY - BLE transport removed.
     */
    fun stopBle() {
        _bleEnabled.value = false
        _blePeers.value = emptyList()
        Log.i(TAG, "stopBle() called (BLE transport removed)")
    }
    
    /**
     * LEGACY - BLE message handling removed.
     */
    private fun handleBleMessage(msg: Any) {
        // No-op: BLE transport removed
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
    
    // ============================================================================
    // Saved Mesh Management (NEW)
    // ============================================================================
    
    /**
     * Save a mesh after successful join.
     */
    suspend fun saveMeshFromJoin(
        meshId: String,
        meshName: String,
        founderId: String,
        founderName: String,
        token: String,
        endpoints: Map<String, String>
    ): SavedMesh {
        val mesh = SavedMesh.fromJoin(
            meshId = meshId,
            meshName = meshName,
            founderId = founderId,
            founderName = founderName,
            token = token,
            endpointsMap = endpoints
        )
        
        meshRepository.saveMesh(mesh)
        refreshSavedMeshes()
        
        Log.i(TAG, "💾 Saved mesh: $meshName (${mesh.endpoints.size} endpoints)")
        return mesh
    }
    
    /**
     * Connect to a saved mesh using ConnectionTrain.
     */
    fun connectToSavedMesh(meshId: String) {
        val intent = android.content.Intent(getApplication(), AtmosphereService::class.java).apply {
            action = "com.llamafarm.atmosphere.action.CONNECT_MESH"
            putExtra("meshId", meshId)
        }
        getApplication<Application>().startService(intent)
    }
    
    // Alias for MeshScreen compatibility
    fun reconnectToMesh(meshId: String) = connectToSavedMesh(meshId)
    
    fun removeSavedMesh(meshId: String) {
        viewModelScope.launch {
            meshRepository.removeMesh(meshId)
            refreshSavedMeshes()
        }
    }
    
    fun setMeshAutoReconnect(meshId: String, enabled: Boolean) {
        viewModelScope.launch {
            meshRepository.setAutoReconnect(meshId, enabled)
            refreshSavedMeshes()
        }
    }
    
    fun onAppResume() {
        // Service handles auto-reconnect, we just refresh UI state
        refreshPeers()
    }
    
    fun discoverPeers() {
        // Trigger discovery via native node if available
        // Or just wait for service updates
    }
    
    // ============================================================================
    // App Platform - Mesh App Discovery & Interaction
    // ============================================================================
    
    /**
     * Send a request to a mesh app endpoint.
     * Routes through the service's MeshConnection.
     */
    fun sendAppRequest(
        capabilityId: String,
        endpoint: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        val connector = serviceConnector
        if (connector == null) {
            onResponse(JSONObject().apply {
                put("status", 503)
                put("error", "Service not available")
            })
            return
        }
        
        // Route through service - it has the active MeshConnection
        connector.sendAppRequest(capabilityId, endpoint, params) { response ->
            onResponse(response)
        }
        
        addMeshEvent(MeshEvent(
            type = "app_request",
            title = "App Request",
            detail = "$capabilityId → $endpoint",
            metadata = mapOf("capability" to capabilityId, "endpoint" to endpoint)
        ))
    }

    /**
     * Call a tool on a mesh app by name.
     */
    fun callTool(
        appName: String,
        toolName: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        val connector = serviceConnector
        if (connector == null) {
            onResponse(JSONObject().apply {
                put("status", 503)
                put("error", "Service not available")
            })
            return
        }

        connector.callTool(appName, toolName, params) { response ->
            onResponse(response)
        }

        addMeshEvent(MeshEvent(
            type = "tool_call",
            title = "Tool Call",
            detail = "$appName/$toolName",
            metadata = mapOf("app" to appName, "tool" to toolName)
        ))
    }
    
    /**
     * Clean up resources when ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel clearing - stopping daemon polling")
        daemonRepository.close()
        stopBle()
    }
}
