package com.llamafarm.atmosphere.cost

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.network.MeshConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "CostBroadcaster"

/**
 * Broadcast state for monitoring.
 */
data class BroadcastState(
    val isRunning: Boolean = false,
    val lastBroadcast: Long? = null,
    val broadcastCount: Int = 0,
    val lastCost: Float? = null,
    val errors: Int = 0
)

/**
 * Broadcasts cost factors to the mesh network.
 * 
 * Periodically collects cost data from CostCollector and sends it
 * over the WebSocket connection for mesh routing decisions.
 * 
 * Message format:
 * ```json
 * {
 *   "type": "cost",
 *   "node_id": "...",
 *   "factors": {
 *     "battery_level": 85,
 *     "is_charging": false,
 *     "cpu_usage": 0.25,
 *     "memory_pressure": 0.45,
 *     "thermal_state": "none",
 *     "network_type": "wifi",
 *     "signal_strength": 4,
 *     "timestamp": 1706972400000,
 *     "cost": 1.2
 *   }
 * }
 * ```
 */
class CostBroadcaster(
    private val context: Context,
    private val nodeId: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val collector = CostCollector(context)
    
    private var broadcastJob: Job? = null
    private var meshConnection: MeshConnection? = null
    
    private val _state = MutableStateFlow(BroadcastState())
    val state: StateFlow<BroadcastState> = _state.asStateFlow()
    
    // Configurable settings
    var broadcastIntervalMs: Long = 30_000L  // 30 seconds default
    var collectIntervalMs: Long = 10_000L    // Collect more frequently than broadcast
    
    /**
     * Start broadcasting cost factors.
     * 
     * @param connection The mesh connection to broadcast over
     * @param intervalMs Broadcast interval in milliseconds (default 30s)
     */
    fun start(connection: MeshConnection, intervalMs: Long = 30_000L) {
        if (_state.value.isRunning) {
            Log.w(TAG, "Already broadcasting")
            return
        }
        
        meshConnection = connection
        broadcastIntervalMs = intervalMs
        
        // Start the cost collector
        collector.startCollecting(collectIntervalMs)
        
        // Start broadcasting
        broadcastJob = scope.launch {
            Log.i(TAG, "Starting cost broadcasts (interval: ${intervalMs}ms)")
            _state.value = _state.value.copy(isRunning = true)
            
            while (isActive) {
                try {
                    broadcast()
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast error", e)
                    _state.value = _state.value.copy(
                        errors = _state.value.errors + 1
                    )
                }
                delay(broadcastIntervalMs)
            }
        }
    }
    
    /**
     * Stop broadcasting.
     */
    fun stop() {
        Log.i(TAG, "Stopping cost broadcasts")
        
        broadcastJob?.cancel()
        broadcastJob = null
        collector.stopCollecting()
        meshConnection = null
        
        _state.value = _state.value.copy(isRunning = false)
    }
    
    /**
     * Perform a single broadcast of current cost factors.
     */
    private fun broadcast() {
        val connection = meshConnection
        if (connection == null) {
            Log.w(TAG, "Not connected to mesh, skipping broadcast")
            return
        }
        
        val factors = collector.collectFactors()
        val cost = factors.calculateCost()
        
        val message = JSONObject().apply {
            put("type", "cost_update")
            put("node_id", nodeId)
            put("cost", cost)
            put("factors", factors.toJson())
        }
        
        // TODO: Update to use new gossip API
        // Send cost update via mesh connection
        try {
            connection.sendMessage(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast cost update: ${e.message}")
        }
        
        val now = System.currentTimeMillis()
        _state.value = _state.value.copy(
            lastBroadcast = now,
            broadcastCount = _state.value.broadcastCount + 1,
            lastCost = cost
        )
        
        Log.d(TAG, "Broadcast cost: $cost (battery=${factors.batteryLevel}%, " +
                   "cpu=${String.format("%.1f", factors.cpuUsage * 100)}%, " +
                   "network=${factors.networkType})")
    }
    
    /**
     * Get the current cost factors without broadcasting.
     */
    fun getCurrentFactors(): CostFactors? = collector.costFactors.value
    
    /**
     * Get the current calculated cost.
     */
    fun getCurrentCost(): Float? = collector.costFactors.value?.calculateCost()
    
    /**
     * Force an immediate broadcast.
     */
    fun broadcastNow() {
        scope.launch {
            try {
                broadcast()
            } catch (e: Exception) {
                Log.e(TAG, "Immediate broadcast failed", e)
            }
        }
    }
    
    /**
     * Update the broadcast interval.
     */
    fun setInterval(intervalMs: Long) {
        broadcastIntervalMs = intervalMs
        
        // Restart if running
        if (_state.value.isRunning) {
            meshConnection?.let { connection ->
                stop()
                start(connection, intervalMs)
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        collector.destroy()
        scope.cancel()
    }
    
    companion object {
        /**
         * Create a cost message JSON for manual sending.
         */
        fun createCostMessage(nodeId: String, factors: CostFactors): JSONObject {
            return JSONObject().apply {
                put("type", "cost")
                put("node_id", nodeId)
                put("factors", factors.toJson())
            }
        }
        
        /**
         * Parse a cost message received from the mesh.
         */
        fun parseCostMessage(json: JSONObject): Triple<String, Float, CostFactors>? {
            return try {
                val nodeId = json.getString("node_id")
                val factorsJson = json.getJSONObject("factors")
                val cost = factorsJson.getDouble("cost").toFloat()
                
                val factors = CostFactors(
                    batteryLevel = factorsJson.getInt("battery_level"),
                    isCharging = factorsJson.getBoolean("is_charging"),
                    cpuUsage = factorsJson.getDouble("cpu_usage").toFloat(),
                    memoryPressure = factorsJson.getDouble("memory_pressure").toFloat(),
                    thermalState = ThermalState.valueOf(
                        factorsJson.getString("thermal_state").uppercase()
                    ),
                    networkType = NetworkType.valueOf(
                        factorsJson.getString("network_type").uppercase()
                    ),
                    signalStrength = factorsJson.getInt("signal_strength"),
                    timestamp = factorsJson.optLong("timestamp", System.currentTimeMillis())
                )
                
                Triple(nodeId, cost, factors)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse cost message", e)
                null
            }
        }
    }
}
