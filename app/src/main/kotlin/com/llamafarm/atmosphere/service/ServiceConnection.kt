package com.llamafarm.atmosphere.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.llamafarm.atmosphere.cost.CostFactors
// ConnectionState import removed — CRDT mesh replaces relay

private const val TAG = "ServiceConnector"

/**
 * State exposed by the AtmosphereService to the UI.
 */
data class ServiceStatus(
    val state: AtmosphereService.ServiceState = AtmosphereService.ServiceState.STOPPED,
    val nodeId: String? = null,
    val connectedPeers: Int = 0,
    val crdtConnected: Boolean = false,
    val activeTransport: String? = null,
    val currentCost: Float? = null,
    val costFactors: CostFactors? = null,
    val isNativeRunning: Boolean = false
) {
    val isRunning: Boolean get() = state == AtmosphereService.ServiceState.RUNNING
    
    val statusText: String get() = when {
        state == AtmosphereService.ServiceState.STOPPED -> "Offline"
        state == AtmosphereService.ServiceState.STARTING -> "Starting..."
        state == AtmosphereService.ServiceState.STOPPING -> "Stopping..."
        crdtConnected -> "Connected via CRDT Mesh"
        state == AtmosphereService.ServiceState.RUNNING -> "Online (No Peers)"
        else -> "Unknown"
    }
}

/**
 * Connects to the AtmosphereService and exposes its state as flows.
 * 
 * Usage:
 * ```kotlin
 * val connector = ServiceConnector(context)
 * connector.bind()
 * 
 * // Observe state
 * connector.status.collect { status ->
 *     updateUI(status)
 * }
 * 
 * // Clean up
 * connector.unbind()
 * ```
 */
class ServiceConnector(private val context: Context) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var service: AtmosphereService? = null
    private var isBound = false
    
    // Observable state
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()
    
    private val _isBound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _isBound.asStateFlow()
    
    // Service connection callback
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            
            service = (binder as? AtmosphereService.LocalBinder)?.getService()
            isBound = true
            _isBound.value = true
            
            // Start observing service state
            observeServiceState()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected unexpectedly")
            service = null
            isBound = false
            _isBound.value = false
            _status.value = ServiceStatus()
        }
    }
    
    /**
     * Bind to the AtmosphereService.
     * Returns true if binding was initiated successfully.
     */
    fun bind(): Boolean {
        if (isBound) {
            Log.d(TAG, "Already bound")
            return true
        }
        
        val intent = Intent(context, AtmosphereService::class.java)
        
        // First, ensure the service is started (for foreground service persistence)
        context.startForegroundService(intent)
        
        // Then bind to it
        return try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "Binding to service...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to service", e)
            false
        }
    }
    
    /**
     * Unbind from the service.
     */
    fun unbind() {
        if (!isBound) return
        
        try {
            context.unbindService(connection)
            isBound = false
            _isBound.value = false
            service = null
            Log.i(TAG, "Unbound from service")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding", e)
        }
    }
    
    /**
     * Start observing service state flows and combine into ServiceStatus.
     */
    private fun observeServiceState() {
        val svc = service ?: return
        
        // Combine all service flows into a single status
        scope.launch {
            combine(
                svc.state,
                svc.nodeId,
                svc.connectedPeers,
                svc.activeTransport,
                svc.currentCost,
                svc.costFactors
            ) { values ->
                val state = values[0] as AtmosphereService.ServiceState
                val nodeId = values[1] as? String
                val connectedPeers = values[2] as Int
                val transport = values[3] as? String
                val cost = values[4] as? Float
                val factors = values[5] as? CostFactors
                
                ServiceStatus(
                    state = state,
                    nodeId = nodeId,
                    connectedPeers = connectedPeers,
                    crdtConnected = svc.getCrdtPeerCount() > 0,
                    activeTransport = transport,
                    currentCost = cost,
                    costFactors = factors,
                    isNativeRunning = svc.isNativeRunning()
                )
            }.collect { status ->
                _status.value = status
                Log.d(TAG, "Status update: ${status.statusText}, crdt=${status.crdtConnected}")
            }
        }
    }
    
    // ========================================================================
    // Service Actions (proxied through connector)
    // ========================================================================
    
    /**
     * Start the Atmosphere service (if not already running).
     */
    fun startService() {
        AtmosphereService.start(context)
    }
    
    /**
     * Stop the Atmosphere service.
     */
    fun stopService() {
        AtmosphereService.stop(context)
    }
    
    /**
     * Connect to a saved mesh (mesh ID is now hardcoded in service).
     */
    fun connectToMesh(meshId: String) {
        // Mesh ID is hardcoded in Rust core for now
        Log.d(TAG, "Mesh connection is managed by service start/stop")
    }
    
    /**
     * Disconnect from current mesh.
     */
    fun disconnectMesh() {
        // Stop the service to disconnect
        stopService()
    }
    
    /**
     * Force an immediate cost broadcast.
     */
    fun broadcastCostNow() {
        service?.broadcastCostNow()
    }
    
    /**
     * Get the underlying service instance (for advanced operations).
     * Returns null if not bound.
     */
    fun getService(): AtmosphereService? = service
    
    /**
     * Send an LLM request through the mesh.
     * 
     * @param prompt The prompt to send
     * @param model Optional model name
     * @param onResponse Callback with (response, error)
     * @return Request ID or null if not connected
     */
    fun sendLlmRequest(
        prompt: String,
        model: String? = null,
        onResponse: (response: String?, error: String?) -> Unit
    ): String? {
        val svc = service
        if (svc == null) {
            onResponse(null, "Service not bound")
            return null
        }
        return svc.sendLlmRequest(prompt, model, onResponse)
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
        val svc = service
        if (svc == null) {
            onResponse(null, "Service not bound")
            return null
        }
        return svc.sendChatRequest(messages, model, onResponse)
    }
    
    /**
     * Send an app request through the service's mesh connection.
     */
    fun sendAppRequest(
        capabilityId: String,
        endpoint: String,
        params: org.json.JSONObject = org.json.JSONObject(),
        onResponse: (org.json.JSONObject) -> Unit
    ) {
        val svc = service
        if (svc == null) {
            onResponse(org.json.JSONObject().apply {
                put("status", 503)
                put("error", "Service not bound")
            })
            return
        }
        svc.sendAppRequest(capabilityId, endpoint, params, onResponse)
    }

    fun callTool(
        appName: String,
        toolName: String,
        params: org.json.JSONObject = org.json.JSONObject(),
        onResponse: (org.json.JSONObject) -> Unit
    ) {
        val svc = service
        if (svc == null) {
            onResponse(org.json.JSONObject().apply {
                put("status", 503)
                put("error", "Service not bound")
            })
            return
        }
        svc.callTool(appName, toolName, params, onResponse)
    }
    
    /**
     * Get the service's mesh events, or null if not bound.
     */
    fun getServiceMeshEvents(): StateFlow<List<AtmosphereService.MeshEvent>>? {
        return service?.meshEvents
    }
    
    /**
     * Get CRDT peers from service.
     */
    fun getServiceCrdtPeers(): StateFlow<List<SimplePeerInfo>>? {
        return service?.crdtPeers
    }
}

/**
 * Singleton ServiceConnector for application-wide use.
 * Initialize in Application.onCreate().
 */
object ServiceManager {
    private var connector: ServiceConnector? = null
    
    /**
     * Initialize the ServiceManager with application context.
     */
    fun initialize(context: Context) {
        if (connector == null) {
            connector = ServiceConnector(context.applicationContext)
            Log.i(TAG, "ServiceManager initialized")
        }
    }
    
    /**
     * Get the connector instance.
     */
    fun getConnector(): ServiceConnector {
        return connector ?: throw IllegalStateException(
            "ServiceManager not initialized. Call initialize() in Application.onCreate()"
        )
    }
    
    /**
     * Convenience method to get status flow.
     */
    val status: StateFlow<ServiceStatus>
        get() = getConnector().status
}
