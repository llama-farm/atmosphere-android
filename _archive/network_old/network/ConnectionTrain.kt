package com.llamafarm.atmosphere.network

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.data.SavedMesh
import com.llamafarm.atmosphere.transport.BleTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "ConnectionTrain"

/**
 * Transport status for a single connection method.
 */
data class TransportStatus(
    val type: String,                    // "ble", "lan", "relay", "public"
    val state: TransportState,
    val latencyMs: Long? = null,
    val lastCheck: Long = System.currentTimeMillis(),
    val error: String? = null,
    val address: String? = null
) {
    enum class TransportState {
        UNKNOWN,
        PROBING,
        AVAILABLE,
        CONNECTED,
        FAILED,
        DISABLED
    }
    
    val isUsable: Boolean get() = state == TransportState.AVAILABLE || state == TransportState.CONNECTED
}

/**
 * Event emitted when transport status changes.
 */
sealed class TransportEvent {
    data class StatusChanged(val type: String, val status: TransportStatus) : TransportEvent()
    data class ActiveTransportChanged(val type: String, val latencyMs: Long?) : TransportEvent()
    data class ConnectionEstablished(val type: String, val meshName: String?) : TransportEvent()
    data class ConnectionLost(val type: String, val error: String?) : TransportEvent()
    data class AllFailed(val errors: Map<String, String>) : TransportEvent()
}

/**
 * Connection Train - Probes multiple transports in parallel and auto-switches to best.
 * 
 * The "train" metaphor: like a train with multiple cars (transports) that can be
 * connected or disconnected dynamically. The train keeps moving (connected) as
 * long as at least one car is attached.
 * 
 * Features:
 * - Probe BLE, LAN, and Relay transports simultaneously
 * - Track status, latency, and last successful connection for each
 * - Automatically switch to best available transport
 * - Emit events for UI updates
 * - Support graceful degradation (relay fallback)
 * 
 * Usage:
 *     val train = ConnectionTrain(context, mesh, nodeId, capabilities, nodeName)
 *     train.events.collect { event -> updateUI(event) }
 *     train.connect()  // Starts probing all transports
 *     train.activeTransport  // Currently best transport
 */
class ConnectionTrain(
    private val context: Context,
    private val savedMesh: SavedMesh,
    private val nodeId: String,
    private val capabilities: List<String> = emptyList(),
    private val nodeName: String = "Android"
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Transport statuses
    private val _statuses = ConcurrentHashMap<String, TransportStatus>()
    private val _statusFlow = MutableStateFlow<Map<String, TransportStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, TransportStatus>> = _statusFlow.asStateFlow()
    
    // Events for UI
    private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()
    
    // Active connections
    private var activeConnection: MeshConnection? = null
    private var bleTransport: BleTransport? = null
    private val _activeTransport = MutableStateFlow<String?>(null)
    val activeTransport: StateFlow<String?> = _activeTransport.asStateFlow()
    
    // Overall connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Mesh name from connection
    private val _meshName = MutableStateFlow<String?>(null)
    val meshName: StateFlow<String?> = _meshName.asStateFlow()
    
    // Configuration
    var probeIntervalMs: Long = 30_000  // Re-probe every 30s
    var switchThresholdMs: Long = 100   // Switch if new transport is 100ms+ faster
    
    private var probeJob: Job? = null
    
    init {
        // Initialize statuses for all known endpoints
        savedMesh.endpoints.forEach { endpoint ->
            _statuses[endpoint.type] = TransportStatus(
                type = endpoint.type,
                state = TransportStatus.TransportState.UNKNOWN,
                address = endpoint.address
            )
        }
        updateStatusFlow()
    }
    
    /**
     * Start connecting - probes all transports in parallel.
     */
    fun connect() {
        Log.i(TAG, "🚂 Connection train starting for mesh: ${savedMesh.meshName}")
        
        // Start initial probe
        scope.launch {
            probeAllTransports()
        }
        
        // Start periodic re-probing
        startPeriodicProbe()
    }
    
    /**
     * Disconnect from all transports.
     */
    fun disconnect() {
        Log.i(TAG, "🛑 Connection train stopping")
        
        probeJob?.cancel()
        probeJob = null
        
        activeConnection?.disconnect()
        activeConnection = null
        
        bleTransport?.stop()
        bleTransport = null
        
        _activeTransport.value = null
        _isConnected.value = false
        _meshName.value = null
        
        // Mark all as disconnected
        _statuses.keys.forEach { type ->
            _statuses[type] = _statuses[type]?.copy(
                state = TransportStatus.TransportState.UNKNOWN
            ) ?: return@forEach
        }
        updateStatusFlow()
    }
    
    /**
     * Force switch to a specific transport type.
     */
    suspend fun switchTo(type: String): Boolean {
        val endpoint = savedMesh.endpoints.find { it.type == type } ?: return false
        return tryConnect(type, endpoint.address)
    }
    
    /**
     * Get the best available transport (by latency/priority).
     */
    fun getBestTransport(): String? {
        return _statuses.values
            .filter { it.isUsable }
            .sortedWith(compareBy<TransportStatus> { it.latencyMs ?: Long.MAX_VALUE }
                .thenByDescending { getPriority(it.type) })
            .firstOrNull()
            ?.type
    }
    
    /**
     * Probe all transports simultaneously.
     */
    private suspend fun probeAllTransports() {
        Log.i(TAG, "🔍 Parallel Probing ${savedMesh.endpoints.size} transports (Phase 2)...")
        
        // Launch all probes in parallel
        val probeJobs = savedMesh.endpoints.map { endpoint ->
            scope.async {
                probeTransport(endpoint.type, endpoint.address)
            }
        }

        // Wait for results with a reasonable timeout for parallel racing
        try {
            withTimeout(10_000) {
                probeJobs.awaitAll()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Parallel probe: Some transports timed out, racing with what we have")
        }
        
        // Selection Logic: Board the fastest train car available
        val best = getBestTransport()
        if (best != null) {
            val endpoint = savedMesh.endpoints.find { it.type == best }
            if (endpoint != null && (!_isConnected.value || best != _activeTransport.value)) {
                Log.i(TAG, "🎯 Racing result: $best is best. Boarding...")
                tryConnect(best, endpoint.address)
            }
        } else {
            // No usable transport found yet
            Log.e(TAG, "❌ All probes failed or timed out. Entering retry loop.")
            scope.launch {
                _events.emit(TransportEvent.AllFailed(_statuses.values
                    .filter { it.state == TransportStatus.TransportState.FAILED }
                    .associate { it.type to (it.error ?: "Timeout") }))
            }
        }
    }
    
    /**
     * Probe a single transport.
     */
    private suspend fun probeTransport(type: String, address: String) {
        Log.d(TAG, "🔍 Probing $type: $address")
        
        updateStatus(type) { it.copy(state = TransportStatus.TransportState.PROBING) }
        
        val startTime = System.currentTimeMillis()
        
        when (type) {
            "ble" -> probeBle(type, address)
            "lan", "local", "public" -> probeWebSocket(type, address)
            "relay" -> probeRelay(type, address)
            else -> {
                updateStatus(type) { it.copy(
                    state = TransportStatus.TransportState.FAILED,
                    error = "Unknown transport type"
                )}
            }
        }
    }
    
    /**
     * Probe BLE transport.
     * BLE requires special handling - we check if peer is discoverable.
     * 
     * NOTE: BLE is now treated as a backup option. We don't mark it as immediately
     * available because it requires actual peer discovery which takes time and
     * may not succeed if no BLE peers are in range.
     */
    private suspend fun probeBle(type: String, address: String) {
        // BLE probing is passive - mark as unknown/probing, not available
        // Actual BLE discovery happens when we try to connect
        // For now, don't mark as available until we actually find peers
        updateStatus(type) { it.copy(
            state = TransportStatus.TransportState.PROBING,  // Not immediately available
            latencyMs = null,  // Unknown until we connect
            lastCheck = System.currentTimeMillis()
        )}
        
        // In the future, we could start a quick BLE scan here
        // and only mark as available if peers are found
        Log.d(TAG, "BLE probe: waiting for peer discovery (not immediately available)")
    }
    
    /**
     * Probe WebSocket transport (LAN/public).
     */
    private suspend fun probeWebSocket(type: String, address: String) {
        val startTime = System.currentTimeMillis()
        
        try {
            val connection = MeshConnection(address, savedMesh.relayToken)
            var probeSuccess = false
            var probeMeshName: String? = null
            
            val result = withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    connection.connectWithAuth(
                        nodeId = nodeId,
                        meshToken = try { JSONObject(savedMesh.relayToken) } catch (e: Exception) { null },
                        capabilities = capabilities,
                        nodeName = nodeName,
                        onConnected = {
                            // WebSocket opened
                        },
                        onJoined = { meshName ->
                            probeSuccess = true
                            probeMeshName = meshName
                            connection.disconnect()
                            if (cont.isActive) cont.resume(true)
                        },
                        onError = { error ->
                            if (cont.isActive) cont.resume(false)
                        }
                    )
                    
                    cont.invokeOnCancellation {
                        connection.disconnect()
                    }
                }
            }
            
            val latency = System.currentTimeMillis() - startTime
            
            if (result == true) {
                updateStatus(type) { it.copy(
                    state = TransportStatus.TransportState.AVAILABLE,
                    latencyMs = latency,
                    lastCheck = System.currentTimeMillis(),
                    error = null
                )}
                Log.i(TAG, "✅ $type available (${latency}ms)")
            } else {
                updateStatus(type) { it.copy(
                    state = TransportStatus.TransportState.FAILED,
                    lastCheck = System.currentTimeMillis(),
                    error = "Connection timeout or error"
                )}
                Log.w(TAG, "❌ $type failed")
            }
            
        } catch (e: Exception) {
            updateStatus(type) { it.copy(
                state = TransportStatus.TransportState.FAILED,
                lastCheck = System.currentTimeMillis(),
                error = e.message
            )}
            Log.e(TAG, "❌ $type probe error: ${e.message}")
        }
    }
    
    /**
     * Probe Relay transport.
     */
    private suspend fun probeRelay(type: String, address: String) {
        // Relay uses same WebSocket probing
        probeWebSocket(type, address)
    }
    
    /**
     * Try to connect using a specific transport.
     */
    private suspend fun tryConnect(type: String, address: String): Boolean {
        Log.i(TAG, "🔗 Connecting via $type: $address")
        
        updateStatus(type) { it.copy(state = TransportStatus.TransportState.PROBING) }
        
        return when (type) {
            "ble" -> tryConnectBle(type, address)
            else -> tryConnectWebSocket(type, address)
        }
    }
    
    /**
     * Connect via BLE transport.
     */
    private suspend fun tryConnectBle(type: String, address: String): Boolean {
        try {
            Log.i(TAG, "🔵 Starting BLE transport for mesh: ${savedMesh.meshId}")
            
            // Stop existing BLE transport if any
            bleTransport?.stop()
            
            // Extract mesh ID from BLE address (format: ble://meshId)
            val meshId = address.removePrefix("ble://")
            
            // Create and start BLE transport
            bleTransport = BleTransport(
                context = context,
                nodeName = nodeName,
                capabilities = capabilities,
                meshId = meshId
            ).apply {
                onPeerDiscovered = { info ->
                    Log.i(TAG, "🔵 BLE peer discovered: ${info.name} (${info.nodeId})")
                    scope.launch {
                        _events.emit(TransportEvent.StatusChanged(type, TransportStatus(
                            type = type,
                            state = TransportStatus.TransportState.CONNECTED,
                            latencyMs = 50
                        )))
                    }
                }
                onPeerLost = { peerId ->
                    Log.i(TAG, "🔴 BLE peer lost: $peerId")
                }
                onMessage = { msg ->
                    Log.d(TAG, "📨 BLE message from ${msg.sourceId}: ${msg.payload.size} bytes")
                    // Handle BLE messages here
                }
            }
            
            bleTransport?.start()
            
            // Mark as connected
            _activeTransport.value = type
            _isConnected.value = true
            _meshName.value = savedMesh.meshName
            
            updateStatus(type) { it.copy(
                state = TransportStatus.TransportState.CONNECTED,
                latencyMs = 50,
                lastCheck = System.currentTimeMillis(),
                error = null
            )}
            
            scope.launch {
                _events.emit(TransportEvent.ConnectionEstablished(type, savedMesh.meshName))
                _events.emit(TransportEvent.ActiveTransportChanged(type, 50))
            }
            
            Log.i(TAG, "✅ BLE transport started and advertising")
            return true
            
        } catch (e: Exception) {
            updateStatus(type) { it.copy(
                state = TransportStatus.TransportState.FAILED,
                error = e.message
            )}
            Log.e(TAG, "❌ BLE connection error: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Connect via WebSocket transport (LAN/relay/public).
     */
    private suspend fun tryConnectWebSocket(type: String, address: String): Boolean {
        try {
            val connection = MeshConnection(address, savedMesh.relayToken)
            var connected = false
            
            val result = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    connection.connectWithAuth(
                        nodeId = nodeId,
                        meshToken = try { JSONObject(savedMesh.relayToken) } catch (e: Exception) { null },
                        capabilities = capabilities,
                        nodeName = nodeName,
                        onConnected = { },
                        onJoined = { meshName ->
                            connected = true
                            _meshName.value = meshName
                            if (cont.isActive) cont.resume(true)
                        },
                        onError = { error ->
                            if (cont.isActive) cont.resume(false)
                        }
                    )
                    
                    cont.invokeOnCancellation {
                        connection.disconnect()
                    }
                }
            }
            
            if (result == true) {
                // Success - replace active connection
                activeConnection?.disconnect()
                activeConnection = connection
                
                // Stop BLE if switching from it
                if (_activeTransport.value == "ble") {
                    bleTransport?.stop()
                    bleTransport = null
                }
                
                _activeTransport.value = type
                _isConnected.value = true
                
                updateStatus(type) { it.copy(
                    state = TransportStatus.TransportState.CONNECTED,
                    lastCheck = System.currentTimeMillis(),
                    error = null
                )}
                
                scope.launch {
                    _events.emit(TransportEvent.ConnectionEstablished(type, _meshName.value))
                    _events.emit(TransportEvent.ActiveTransportChanged(type, _statuses[type]?.latencyMs))
                }
                
                // Monitor connection health
                monitorConnection(connection, type)
                
                Log.i(TAG, "✅ Connected via $type")
                return true
            } else {
                updateStatus(type) { it.copy(
                    state = TransportStatus.TransportState.FAILED,
                    error = "Connection failed"
                )}
                return false
            }
            
        } catch (e: Exception) {
            updateStatus(type) { it.copy(
                state = TransportStatus.TransportState.FAILED,
                error = e.message
            )}
            Log.e(TAG, "❌ $type connection error: ${e.message}")
            return false
        }
    }
    
    /**
     * Monitor active connection and handle disconnects.
     */
    private fun monitorConnection(connection: MeshConnection, type: String) {
        scope.launch {
            connection.connectionState.collect { state ->
                when (state) {
                    ConnectionState.DISCONNECTED, ConnectionState.FAILED -> {
                        if (_activeTransport.value == type) {
                            Log.w(TAG, "⚠️ Active transport $type disconnected")
                            
                            _isConnected.value = false
                            _activeTransport.value = null
                            
                            updateStatus(type) { it.copy(
                                state = TransportStatus.TransportState.FAILED,
                                error = "Disconnected"
                            )}
                            
                            _events.emit(TransportEvent.ConnectionLost(type, "Disconnected"))
                            
                            // Try to reconnect via another transport
                            delay(1000)
                            probeAllTransports()
                        }
                    }
                    else -> { }
                }
            }
        }
    }
    
    /**
     * Start periodic re-probing to find better transports.
     */
    private fun startPeriodicProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (isActive) {
                delay(probeIntervalMs)
                
                // Only probe non-active transports
                savedMesh.endpoints
                    .filter { it.type != _activeTransport.value }
                    .forEach { endpoint ->
                        probeTransport(endpoint.type, endpoint.address)
                    }
                
                // Check if we should switch
                val best = getBestTransport()
                val current = _activeTransport.value
                
                if (best != null && best != current) {
                    val bestLatency = _statuses[best]?.latencyMs ?: Long.MAX_VALUE
                    val currentLatency = _statuses[current]?.latencyMs ?: Long.MAX_VALUE
                    
                    if (bestLatency + switchThresholdMs < currentLatency) {
                        Log.i(TAG, "🔄 Switching from $current to $best (${currentLatency}ms -> ${bestLatency}ms)")
                        val endpoint = savedMesh.endpoints.find { it.type == best }
                        if (endpoint != null) {
                            tryConnect(best, endpoint.address)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Update status for a transport and emit event.
     */
    private fun updateStatus(type: String, update: (TransportStatus) -> TransportStatus) {
        val current = _statuses[type] ?: TransportStatus(type, TransportStatus.TransportState.UNKNOWN)
        val new = update(current)
        _statuses[type] = new
        updateStatusFlow()
        
        scope.launch {
            _events.emit(TransportEvent.StatusChanged(type, new))
        }
    }
    
    private fun updateStatusFlow() {
        _statusFlow.value = _statuses.toMap()
    }
    
    /**
     * Get priority for transport type (higher = preferred).
     * 
     * NOTE: We now prefer verified WebSocket connections over BLE.
     * BLE requires actual peer discovery and is better for local-only scenarios.
     * WebSocket (LAN/relay) is more reliable for remote mesh connections.
     */
    private fun getPriority(type: String): Int = when (type) {
        "lan", "local" -> 100  // LAN is fastest for verified connections
        "relay" -> 80     // Relay is reliable fallback
        "public" -> 60    // Public might have NAT issues
        "ble" -> 40       // BLE is for local mesh only, needs peer discovery
        else -> 0
    }
    
    /**
     * Get the active MeshConnection for sending messages.
     */
    fun getActiveConnection(): MeshConnection? = activeConnection
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
