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
import com.llamafarm.atmosphere.data.SavedMeshRepository
import com.llamafarm.atmosphere.network.ConnectionTrain
import com.llamafarm.atmosphere.network.MeshConnection
import com.llamafarm.atmosphere.network.TransportEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    
    // Connection Train for resilient mesh connectivity
    private var connectionTrain: ConnectionTrain? = null
    
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
     * Connect to a specific mesh using ConnectionTrain.
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
        Log.i(TAG, "Starting ConnectionTrain for ${mesh.meshName}")
        
        // Clean up existing train
        connectionTrain?.destroy()
        
        val nodeId = _nodeId.value ?: "unknown"
        val capabilities = listOf("camera", "microphone", "location") // From registry
        
        // Create and start new train
        connectionTrain = ConnectionTrain(
            context = applicationContext,
            savedMesh = mesh,
            nodeId = nodeId,
            capabilities = capabilities,
            nodeName = android.os.Build.MODEL
        ).apply {
            // Observe events
            serviceScope.launch {
                events.collect { event: TransportEvent ->
                    handleTransportEvent(event, mesh)
                }
            }
            
            // Observe active transport
            serviceScope.launch {
                activeTransport.collect { type ->
                    _activeTransport.value = type
                    if (type != null) {
                        updateNotification("Connected via $type")
                    } else {
                        updateNotification("Disconnected")
                    }
                }
            }
            
            connect()
        }
    }
    
    private suspend fun handleTransportEvent(event: TransportEvent, mesh: com.llamafarm.atmosphere.data.SavedMesh) {
        when (event) {
            is TransportEvent.ConnectionEstablished -> {
                Log.i(TAG, "✅ Connected to ${mesh.meshName} via ${event.type}")
                _meshConnectionState.value = com.llamafarm.atmosphere.network.ConnectionState.CONNECTED
                
                // Set mesh connection for cost broadcasting
                connectionTrain?.getActiveConnection()?.let { conn ->
                    setMeshConnection(conn)
                }
                
                // Update last connected time
                meshRepository.updateMeshConnection(mesh.meshId, event.type, true)
            }
            is TransportEvent.ConnectionLost -> {
                Log.w(TAG, "❌ Connection lost: ${event.error}")
                _meshConnectionState.value = com.llamafarm.atmosphere.network.ConnectionState.DISCONNECTED
                clearMeshConnection()
            }
            is TransportEvent.AllFailed -> {
                Log.e(TAG, "❌ All transports failed")
                _meshConnectionState.value = com.llamafarm.atmosphere.network.ConnectionState.FAILED
            }
            else -> {}
        }
    }
    
    /**
     * Disconnect from current mesh.
     */
    fun disconnectMesh() {
        connectionTrain?.destroy()
        connectionTrain = null
        clearMeshConnection()
        _meshConnectionState.value = com.llamafarm.atmosphere.network.ConnectionState.DISCONNECTED
        updateNotification("Online (Disconnected)")
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
                
                // Create native node using generated identity
                nativeNode = AtmosphereNode.create(nodeId, dataDir)
                nativeNode?.start()
                
                Log.i(TAG, "Native node started: $nodeId")
                
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
            
            // Disconnect mesh
            meshConnection?.disconnect()
            meshConnection = null
            
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
