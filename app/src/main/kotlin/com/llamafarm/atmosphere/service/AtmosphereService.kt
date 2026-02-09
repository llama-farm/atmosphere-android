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
import com.llamafarm.atmosphere.network.MeshConnection
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.network.MeshMessage
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.router.RouteConstraints
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
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
    fun isConnected(): Boolean = meshConnection?.connectionState?.value == com.llamafarm.atmosphere.network.ConnectionState.CONNECTED
    fun getNodeId(): String? = _nodeId.value
    fun getMeshId(): String? = currentMeshId
    fun getRelayPeerCount(): Int = _relayPeers.value.size

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
    
    // Mesh connection (for cost broadcasting)
    private var meshConnection: MeshConnection? = null
    
    // LAN discovery (mDNS/NSD)
    private var lanDiscovery: LanDiscovery? = null
    private var currentMeshId: String? = null
    
    // BLE mesh transport
    private var bleMeshManager: com.llamafarm.atmosphere.transport.BleMeshManager? = null
    
    // Transport bridge: dedup cache for cross-transport forwarding
    private val seenNonces = java.util.LinkedHashMap<String, Long>(100, 0.75f, true)
    private val SEEN_NONCES_MAX = 500
    
    // Current relay URL for mesh
    private var currentRelayUrl: String? = null
    
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
    
    // Mesh connection state exposed to clients
    private val _meshConnectionState = MutableStateFlow(com.llamafarm.atmosphere.network.ConnectionState.DISCONNECTED)
    val meshConnectionState: StateFlow<com.llamafarm.atmosphere.network.ConnectionState> = _meshConnectionState.asStateFlow()
    
    private val _activeTransport = MutableStateFlow<String?>(null)
    val activeTransport: StateFlow<String?> = _activeTransport.asStateFlow()

    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId.asStateFlow()
    
    private val _currentCost = MutableStateFlow<Float?>(null)
    val currentCost: StateFlow<Float?> = _currentCost.asStateFlow()

    // Relay peers visible in the mesh
    private val _relayPeers = MutableStateFlow<List<com.llamafarm.atmosphere.network.RelayPeer>>(emptyList())
    val relayPeers: StateFlow<List<com.llamafarm.atmosphere.network.RelayPeer>> = _relayPeers.asStateFlow()

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
                if (bleMeshManager == null || bleMeshManager?.isRunning() != true) {
                    Log.i(TAG, "🔵 Retrying BLE transport after permission grant")
                    startBleTransport()
                }
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
        Log.i(TAG, "Connecting to mesh: ${mesh.meshName}")
        
        // Restart BLE with correct mesh ID if it changed
        if (bleMeshManager != null && bleMeshManager?.isRunning() == true) {
            bleMeshManager?.stop()
            bleMeshManager = null
            startBleTransport()
        }
        Log.i(TAG, "📡 Available endpoints: ${mesh.endpoints.map { "${it.type}=${it.address}" }}")
        
        // Clean up existing connection
        meshConnection?.disconnect()
        
        // Check if LAN discovery has found a peer for this mesh (even if not saved yet)
        val discoveredLanPeer = lanDiscovery?.getPeerForMesh(mesh.meshId)
        if (discoveredLanPeer != null) {
            Log.i(TAG, "🏠 LAN peer already discovered: ${discoveredLanPeer.name} at ${discoveredLanPeer.host}:${discoveredLanPeer.port}")
        }
        
        // Try LAN first, then relay
        // LAN endpoints are "local" or "lan" type with ws:// URLs
        val lanEndpoint = mesh.endpoints
            .filter { it.type == "local" || it.type == "lan" }
            .maxByOrNull { it.priority }
        
        val relayEndpoint = mesh.endpoints
            .firstOrNull { it.type == "relay" }
        
        // Build connection URL - prefer LAN when available
        val connectionUrl: String
        val transportType: String
        
        if (lanEndpoint != null) {
            // Convert HTTP URL to WebSocket mesh endpoint
            val lanAddr = lanEndpoint.address
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .trimEnd('/')
            connectionUrl = "$lanAddr/api/mesh/ws"
            transportType = "lan"
            Log.i(TAG, "🏠 Using LAN connection: $connectionUrl")
        } else if (discoveredLanPeer != null) {
            // Use dynamically discovered LAN peer (mDNS)
            connectionUrl = discoveredLanPeer.wsUrl
            transportType = "lan"
            Log.i(TAG, "🏠 Using discovered LAN peer: $connectionUrl")
        } else if (relayEndpoint != null) {
            connectionUrl = relayEndpoint.address
            transportType = "relay"
            Log.i(TAG, "☁️ Using relay connection: $connectionUrl")
        } else {
            connectionUrl = "wss://atmosphere-relay-production.up.railway.app/relay/${mesh.meshId}"
            transportType = "relay"
            Log.i(TAG, "☁️ Using default relay: $connectionUrl")
        }
        
        currentRelayUrl = connectionUrl
        
        // Create new connection with token for authentication
        meshConnection = MeshConnection(applicationContext, connectionUrl, mesh.relayToken).apply {
            // Monitor connection state
            serviceScope.launch {
                connectionState.collect { state ->
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            Log.i(TAG, "✅ Connected to ${mesh.meshName} via $transportType")
                            _meshConnectionState.value = ConnectionState.CONNECTED
                            _activeTransport.value = transportType
                            updateNotification("Connected to ${mesh.meshName} via $transportType")
                            meshRepository.updateMeshConnection(mesh.meshId, transportType, true)
                            
                            // AUTO-TEST
                            serviceScope.launch {
                                delay(3000)
                                Log.i(TAG, "🚀 TRIGGERING AUTO-TEST REQUEST")
                                val reqId = sendLlmRequest("Describe the atmosphere of Mars", "auto") { resp, err ->
                                    if (err != null) {
                                        Log.e(TAG, "❌ TEST FAILED: $err")
                                    } else {
                                        Log.i(TAG, "✅ TEST SUCCESS: Received response: ${resp?.take(50)}...")
                                    }
                                }
                                Log.i(TAG, "Test request sent with ID: $reqId")
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "Disconnected from ${mesh.meshName}")
                            _meshConnectionState.value = ConnectionState.DISCONNECTED
                            _activeTransport.value = null
                            updateNotification("Disconnected")
                        }
                        ConnectionState.FAILED -> {
                            Log.e(TAG, "Connection failed for ${mesh.meshName}")
                            _meshConnectionState.value = ConnectionState.FAILED
                        }
                        else -> { /* ignore intermediate states */ }
                    }
                }
            }
            
            // Listen for incoming messages
            serviceScope.launch {
                messages.collect { message ->
                    this@AtmosphereService.handleMeshMessage(message)
                }
            }
            
            connect()
        }
    }
    
    /**
     * Disconnect from current mesh.
     */
    fun disconnectMesh() {
        meshConnection?.disconnect()
        meshConnection = null
        currentRelayUrl = null
        currentMeshId = null
        _meshConnectionState.value = ConnectionState.DISCONNECTED
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
            
            // If we're connected to a mesh via relay and this peer is on the same mesh,
            // reconnect via LAN for lower latency
            val meshId = currentMeshId
            if (meshId != null && peer.meshId == meshId && _activeTransport.value == "relay") {
                Log.i(TAG, "🚀 LAN peer matches current mesh! Switching from relay to LAN...")
                
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
                    
                    // Reconnect (will prefer LAN now)
                    connectToMesh(updatedMesh)
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
        
        // Forward to WebSocket (if not from WebSocket)
        if (sourceTransport != "websocket") {
            meshConnection?.sendBroadcast(msg)?.also { targets.add("ws") }
        }
        
        // Forward to BLE (if not from BLE)
        if (sourceTransport != "ble") {
            val ble = bleMeshManager
            if (ble != null && ble.isRunning()) {
                serviceScope.launch {
                    val bytes = msg.toString().toByteArray(Charsets.UTF_8)
                    ble.broadcast(bytes)
                    targets.add("ble")
                }
            }
        }
        
        if (targets.isNotEmpty()) {
            Log.i(TAG, "🌉 Bridged ${msg.optString("type", "?")} from $sourceTransport → ${targets.joinToString()}")
        }
    }
    
    /**
     * Start BLE mesh transport for offline mesh communication.
     */
    private fun startBleTransport() {
        val nodeId = _nodeId.value ?: return
        
        if (!com.llamafarm.atmosphere.transport.BleMeshManager.isBleSupported(applicationContext)) {
            Log.w(TAG, "BLE not supported on this device")
            return
        }
        
        // Use current mesh ID or a default for discovery
        val meshId = currentMeshId ?: "default"
        
        val manager = com.llamafarm.atmosphere.transport.BleMeshManager(
            context = applicationContext,
            nodeId = nodeId,
            meshId = meshId
        )
        
        // Bridge BLE messages to gossip
        serviceScope.launch {
            manager.incomingMessages.collect { msg ->
                Log.i(TAG, "📡 BLE message from ${msg.fromNodeId} (${msg.payload.size} bytes, ${msg.hopCount} hops)")
                
                addMeshEvent(MeshEvent(
                    type = "peer",
                    title = "BLE Message",
                    detail = "From ${msg.fromNodeId.take(8)} (${msg.hopCount} hops)",
                    metadata = mapOf("transport" to "ble", "hops" to msg.hopCount.toString())
                ))
                
                try {
                    val text = String(msg.payload, Charsets.UTF_8)
                    val json = org.json.JSONObject(text)
                    val type = json.optString("type", "")
                    
                    when (type) {
                        "capability_announce", "gossip.announce", "capability.announce" -> {
                            // Process gossip via BLE + bridge to WebSocket
                            val gossipManager = com.llamafarm.atmosphere.core.GossipManager.getInstance(applicationContext)
                            gossipManager.handleAnnouncement(msg.fromNodeId, json)
                            bridgeMessage(json, "ble")
                            Log.i(TAG, "🔵 BLE gossip processed from ${msg.fromNodeId}")
                            
                            addMeshEvent(MeshEvent(
                                type = "gossip",
                                title = "BLE Capabilities",
                                detail = "Via BLE from ${msg.fromNodeId.take(8)}",
                                metadata = mapOf("transport" to "ble")
                            ))
                        }
                        "llm_response", "chat_response" -> {
                            // LLM response via BLE
                            val requestId = json.optString("request_id", "")
                            val response = json.optString("response", "")
                            Log.i(TAG, "📥 BLE LLM response for $requestId")
                            
                            pendingRequests.remove(requestId)?.invoke(response, null)
                        }
                        else -> {
                            Log.d(TAG, "Unhandled BLE message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse BLE message: ${e.message}")
                }
            }
        }
        
        // Start without encryption for now (mesh token would be needed for encrypted mode)
        val started = manager.start(meshToken = null)
        if (started) {
            bleMeshManager = manager
            Log.i(TAG, "🔵 BLE mesh transport started for mesh $meshId")
            addMeshEvent(MeshEvent(
                type = "peer",
                title = "BLE Started",
                detail = "Scanning & advertising on mesh $meshId"
            ))
        } else {
            Log.w(TAG, "Failed to start BLE mesh transport")
        }
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
     * Send an app request to a mesh app capability endpoint.
     */
    fun sendAppRequest(
        capabilityId: String,
        endpoint: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        val connection = meshConnection
        if (connection == null) {
            onResponse(JSONObject().apply {
                put("status", 503)
                put("error", "No mesh connection")
            })
            return
        }
        connection.sendAppRequest(capabilityId, endpoint, params, onResponse)
    }

    fun callTool(
        appName: String,
        toolName: String,
        params: JSONObject = JSONObject(),
        onResponse: (JSONObject) -> Unit
    ) {
        val connection = meshConnection
        if (connection == null) {
            onResponse(JSONObject().apply {
                put("status", 503)
                put("error", "No mesh connection")
            })
            return
        }
        connection.callTool(appName, toolName, params, onResponse)
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
        val connection = meshConnection
        if (connection == null) {
            // Try BLE fallback
            val ble = bleMeshManager
            if (ble != null && ble.isRunning()) {
                Log.i(TAG, "🔵 No WebSocket, trying BLE for inference request")
                serviceScope.launch {
                    val blePayload = JSONObject().apply {
                        put("type", "inference_request")
                        put("prompt", prompt)
                        put("request_id", requestId)
                        if (model != null) put("model", model)
                    }.toString().toByteArray(Charsets.UTF_8)
                    
                    val sent = ble.broadcast(blePayload)
                    if (!sent) {
                        pendingRequests.remove(requestId)
                        onResponse(null, "BLE broadcast failed")
                    } else {
                        Log.i(TAG, "🔵 Sent inference request via BLE")
                    }
                }
                return
            }
            
            Log.w(TAG, "Cannot send remote request: not connected to mesh")
            pendingRequests.remove(requestId)
            onResponse(null, "Not connected to mesh")
            return
        }
        
        Log.i(TAG, "Sending inference request to $targetNodeId (requestId=$requestId)")
        
        // Build payload
        val payload = JSONObject().apply {
            put("prompt", prompt)
            if (model != null) put("model", model)
        }
        
        // Send inference request via mesh connection (properly wrapped for relay)
        connection.sendInferenceRequest(
            targetNodeId = targetNodeId,
            capabilityId = model ?: "default",  // Use model as capability hint
            requestId = requestId,
            payload = payload
        )
        
        Log.d(TAG, "Sent inference_request to $targetNodeId")
        
        // Callback will be invoked when response arrives (handled in message listener)
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
        val connection = meshConnection
        if (connection == null) {
            Log.w(TAG, "Cannot send remote chat request: not connected to mesh")
            pendingRequests.remove(requestId)
            onResponse(null, "Not connected to mesh")
            return
        }
        
        Log.i(TAG, "Sending chat request to $targetNodeId (requestId=$requestId)")
        
        // Build messages array
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }
        
        // Build payload
        val payload = JSONObject().apply {
            put("messages", messagesArray)
            put("model", model)
        }
        
        // Send message via mesh connection
        val message = JSONObject().apply {
            put("type", "inference_request")
            put("target_node_id", targetNodeId)
            put("request_id", requestId)
            put("payload", payload)
        }
        
        connection.sendMessage(message)
        
        Log.d(TAG, "Sent chat request to $targetNodeId")
        
        // Callback will be invoked when response arrives (handled in message listener)
    }
    
    /**
     * Handle incoming mesh messages, particularly inference_response.
     */
    private fun handleMeshMessage(message: MeshMessage) {
        when (message) {
            is MeshMessage.CapabilityAnnounce -> {
                val capCount = message.announcement?.optJSONArray("capabilities")?.length() ?: message.capabilities?.size ?: 0
                Log.i(TAG, "📡 Capability announcement from ${message.sourceNodeId}: $capCount capabilities")
                addMeshEvent(MeshEvent(
                    type = "gossip",
                    title = "Capabilities Received",
                    detail = "$capCount capabilities from mesh",
                    nodeId = message.sourceNodeId
                ))
                
                // Bridge gossip to BLE (came from WebSocket/LAN)
                message.announcement?.let { bridgeMessage(it, "websocket") }
                
                // Log gradient table state after announcement
                val gossipManager = com.llamafarm.atmosphere.core.GossipManager.getInstance(applicationContext)
                val stats = gossipManager.getStats()
                Log.i(TAG, "📊 Gradient table: ${stats["total_capabilities"]} total (${stats["local_capabilities"]} local, ${stats["remote_capabilities"]} remote)")
            }
            
            is MeshMessage.InferenceResponse -> {
                val requestId = message.requestId
                val callback = pendingRequests.remove(requestId)
                
                if (callback != null) {
                    // Extract response from payload
                    val response = message.response ?: message.payload?.optString("response")?.takeIf { it.isNotEmpty() }
                    val error = message.error ?: message.payload?.optString("error")?.takeIf { it.isNotEmpty() }
                    
                    // Check for response FIRST (successful case), then error
                    if (!response.isNullOrEmpty()) {
                        Log.i(TAG, "✅ Received inference response for $requestId: ${response.length} chars")
                        addMeshEvent(MeshEvent(
                            type = "inference",
                            title = "LLM Response",
                            detail = "${response.length} chars"
                        ))
                        callback(response, null)
                    } else if (!error.isNullOrEmpty()) {
                        Log.w(TAG, "Inference error for $requestId: $error")
                        callback(null, error)
                    } else {
                        Log.w(TAG, "Empty inference response for $requestId")
                        callback(null, "Empty response")
                    }
                } else {
                    Log.w(TAG, "Received inference_response for unknown requestId: $requestId")
                }
            }
            
            is MeshMessage.InferenceRequest -> {
                // Handle incoming inference requests (when other nodes request us)
                handleIncomingInferenceRequest(message)
            }
            
            is MeshMessage.Joined -> {
                addMeshEvent(MeshEvent(
                    type = "connected",
                    title = "Joined Mesh",
                    detail = message.meshName ?: "unknown"
                ))
            }
            
            is MeshMessage.PeerList -> {
                _relayPeers.value = message.peers
                val peerNames = message.peers.map { it.name }
                Log.i(TAG, "📋 Updated peers: $peerNames")
                addMeshEvent(MeshEvent(
                    type = "peer",
                    title = "Peers Updated",
                    detail = "${message.peers.size} peers: ${peerNames.joinToString(", ")}"
                ))
            }
            
            else -> {
                Log.d(TAG, "Received mesh message: ${message::class.simpleName}")
            }
        }
    }
    
    /**
     * Handle incoming inference requests from other nodes.
     */
    private fun handleIncomingInferenceRequest(request: MeshMessage.InferenceRequest) {
        val requestId = request.requestId
        val payload = request.payload ?: return
        
        Log.i(TAG, "Received inference request $requestId from ${request.sourceNodeId}")
        
        serviceScope.launch {
            try {
                val prompt = payload.optString("prompt")
                if (prompt.isEmpty()) {
                    sendInferenceResponse(requestId, null, "No prompt provided")
                    return@launch
                }
                
                // Execute locally
                executeLocalInference(requestId, prompt) { response, error ->
                    sendInferenceResponse(requestId, response, error)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling inference request", e)
                sendInferenceResponse(requestId, null, "Error: ${e.message}")
            }
        }
    }
    
    /**
     * Send inference response back to requester.
     */
    private fun sendInferenceResponse(requestId: String, response: String?, error: String?) {
        val connection = meshConnection ?: return
        
        val payload = JSONObject().apply {
            if (response != null) put("response", response)
            if (error != null) put("error", error)
        }
        
        val message = JSONObject().apply {
            put("type", "inference_response")
            put("request_id", requestId)
            put("payload", payload)
        }
        
        connection.sendMessage(message)
        Log.d(TAG, "Sent inference_response for $requestId")
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
        
        // Initialize cost broadcaster (will start when mesh connection is set)
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
            
            // Stop BLE mesh
            bleMeshManager?.stop()
            bleMeshManager = null
            
            // Stop LAN discovery
            lanDiscovery?.stopDiscovery()
            lanDiscovery = null
            
            // Disconnect mesh
            meshConnection?.disconnect()
            meshConnection = null
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
    
    /**
     * Set the mesh connection for cost broadcasting.
     * Call this when joining a mesh.
     */
    fun setMeshConnection(connection: MeshConnection) {
        meshConnection = connection
        
        // Start cost broadcasting
        _nodeId.value?.let { nodeId ->
            costBroadcaster?.start(connection, intervalMs = 30_000L)
            Log.i(TAG, "Started cost broadcasting for $nodeId")
        }
        
        // Register capabilities with mesh
        meshCapabilityHandler?.setMeshConnection(connection)
    }
    
    /**
     * Stop mesh connection and cost broadcasting.
     */
    fun clearMeshConnection() {
        costBroadcaster?.stop()
        meshCapabilityHandler?.clearMeshConnection()
        meshConnection = null
    }
    
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
