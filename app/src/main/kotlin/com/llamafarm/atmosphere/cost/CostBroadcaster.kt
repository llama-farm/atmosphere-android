package com.llamafarm.atmosphere.cost

import android.content.Context
import android.util.Log
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
    
    private val _state = MutableStateFlow(BroadcastState())
    val state: StateFlow<BroadcastState> = _state.asStateFlow()
    
    // Configurable settings
    var broadcastIntervalMs: Long = 30_000L  // 30 seconds default
    var collectIntervalMs: Long = 10_000L    // Collect more frequently than broadcast
    
    /**
     * Start collecting cost factors.
     * LEGACY - Broadcasting removed. Cost info now read from CRDT gradient table.
     * 
     * @param intervalMs Collection interval in milliseconds (default 30s)
     */
    fun start(intervalMs: Long = 30_000L) {
        if (_state.value.isRunning) {
            Log.w(TAG, "Already collecting")
            return
        }
        
        broadcastIntervalMs = intervalMs
        
        // Start the cost collector (still useful for local metrics)
        collector.startCollecting(collectIntervalMs)
        
        _state.value = _state.value.copy(isRunning = true)
        Log.i(TAG, "Started cost collection (relay broadcasting removed - use CRDT gradient table)")
    }
    
    /**
     * Stop collecting costs.
     */
    fun stop() {
        Log.i(TAG, "Stopping cost collection")
        
        broadcastJob?.cancel()
        broadcastJob = null
        collector.stopCollecting()
        
        _state.value = _state.value.copy(isRunning = false)
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
     * LEGACY - No-op. Relay broadcasting removed.
     */
    fun broadcastNow() {
        // No-op: Relay broadcasting removed
    }
    
    /**
     * Update the collection interval.
     */
    fun setInterval(intervalMs: Long) {
        broadcastIntervalMs = intervalMs
        
        // Restart if running
        if (_state.value.isRunning) {
            stop()
            start(intervalMs)
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
