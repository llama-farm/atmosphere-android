package com.llamafarm.atmosphere.core

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GossipManager"

/**
 * Gossip protocol manager for capability announcements.
 * 
 * Responsibilities:
 * - Broadcast local capabilities periodically
 * - Receive and store remote capability announcements
 * - Maintain a gradient table of all known mesh capabilities
 * - Provide query interface for routing decisions
 */
class GossipManager private constructor(
    private val context: Context
) {
    companion object {
        @Volatile
        private var instance: GossipManager? = null
        
        fun getInstance(context: Context): GossipManager {
            return instance ?: synchronized(this) {
                instance ?: GossipManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        // Protocol constants
        private const val ANNOUNCE_INTERVAL_MS = 30_000L  // 30 seconds
        private const val CAPABILITY_TTL_MS = 300_000L    // 5 minutes
        private const val MAX_HOPS = 10
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Gradient table: all known capabilities from across the mesh
    private val gradientTable = ConcurrentHashMap<String, CapabilityAnnouncement>()
    
    // Local capabilities to announce
    private val localCapabilities = ConcurrentHashMap<String, CapabilityAnnouncement>()
    
    // Node ID for this device
    var nodeId: String = ""
        private set
    
    var nodeName: String = Build.MODEL
        private set
    
    // Callback for sending messages to relay
    private var messageSender: ((JSONObject) -> Unit)? = null
    
    // Flow of gradient table updates
    private val _gradientUpdates = MutableSharedFlow<CapabilityAnnouncement>()
    val gradientUpdates: SharedFlow<CapabilityAnnouncement> = _gradientUpdates.asSharedFlow()
    
    // Background announcement job
    private var announceJob: Job? = null
    
    /**
     * Initialize the gossip manager.
     */
    fun initialize(nodeId: String, nodeName: String? = null) {
        this.nodeId = nodeId
        this.nodeName = nodeName ?: Build.MODEL
        
        Log.i(TAG, "GossipManager initialized (nodeId=$nodeId, nodeName=${this.nodeName})")
    }
    
    /**
     * Set the callback for sending messages to the relay.
     */
    fun setMessageSender(sender: (JSONObject) -> Unit) {
        this.messageSender = sender
    }
    
    /**
     * Start periodic capability broadcasts.
     */
    fun startBroadcasting() {
        announceJob?.cancel()
        announceJob = scope.launch {
            // Immediate first broadcast
            broadcastCapabilities()
            
            // Then periodic
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                broadcastCapabilities()
            }
        }
        
        Log.i(TAG, "Started broadcasting capabilities (interval=${ANNOUNCE_INTERVAL_MS}ms)")
    }
    
    /**
     * Stop periodic broadcasts.
     */
    fun stopBroadcasting() {
        announceJob?.cancel()
        announceJob = null
        Log.i(TAG, "Stopped broadcasting capabilities")
    }
    
    /**
     * Register a local capability for announcement.
     */
    fun registerLocalCapability(capability: CapabilityAnnouncement) {
        localCapabilities[capability.capabilityId] = capability
        
        // Also add to gradient table (0 hops, it's local)
        gradientTable[capability.capabilityId] = capability.copy(hops = 0)
        
        Log.d(TAG, "Registered local capability: ${capability.label} (${capability.capabilityId})")
        
        // Broadcast immediately
        scope.launch {
            broadcastCapabilities()
        }
    }
    
    /**
     * Unregister a local capability.
     */
    fun unregisterLocalCapability(capabilityId: String) {
        localCapabilities.remove(capabilityId)
        gradientTable.remove(capabilityId)
        
        Log.d(TAG, "Unregistered local capability: $capabilityId")
    }
    
    /**
     * Broadcast all local capabilities to the mesh via relay.
     */
    fun broadcastCapabilities() {
        val sender = messageSender
        if (sender == null) {
            Log.w(TAG, "Cannot broadcast: no message sender configured")
            return
        }
        
        if (localCapabilities.isEmpty()) {
            Log.d(TAG, "No local capabilities to broadcast")
            return
        }
        
        // Get current device cost factors
        val costFactors = getCurrentCostFactors()
        
        // Build announcement message
        val message = JSONObject().apply {
            put("type", "capability_announce")
            put("node_id", nodeId)
            put("node_name", nodeName)
            put("timestamp", System.currentTimeMillis())
            put("cost_factors", costFactors.toJson())
            
            val caps = JSONArray()
            localCapabilities.values.forEach { cap ->
                // Update cost factors and timestamp for each capability
                val updated = cap.copy(
                    costFactors = costFactors,
                    timestamp = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + CAPABILITY_TTL_MS
                )
                caps.put(updated.toJson())
            }
            put("capabilities", caps)
        }
        
        sender(message)
        
        Log.d(TAG, "Broadcast ${localCapabilities.size} capabilities")
    }
    
    /**
     * Handle incoming capability announcement from another node.
     */
    fun handleAnnouncement(sourceNodeId: String, announcement: JSONObject) {
        try {
            Log.i(TAG, "🔔 handleAnnouncement from $sourceNodeId, keys=${announcement.keys().asSequence().toList()}")
            
            // Check if this is a model_catalog message
            val messageType = announcement.optString("type", "capability_announce")
            if (messageType == "model_catalog") {
                handleModelCatalog(sourceNodeId, announcement)
                return
            }
            
            val timestamp = announcement.optLong("timestamp", System.currentTimeMillis())
            val sourceNodeName = announcement.optString("node_name", sourceNodeId)
            
            val costFactorsJson = announcement.optJSONObject("cost_factors")
            val sourceCostFactors = if (costFactorsJson != null) {
                NodeCostFactors.fromJson(costFactorsJson)
            } else {
                null
            }
            
            val capsArray = announcement.getJSONArray("capabilities")
            Log.i(TAG, "📦 Processing ${capsArray.length()} capabilities from $sourceNodeName")
            var addedCount = 0
            var updatedCount = 0
            var errorCount = 0
            
            for (i in 0 until capsArray.length()) {
                val capJson = capsArray.getJSONObject(i)
                val capability: CapabilityAnnouncement
                try {
                    capability = CapabilityAnnouncement.fromJson(capJson)
                } catch (e: Exception) {
                    errorCount++
                    Log.e(TAG, "❌ Failed to parse capability $i: ${e.message}")
                    Log.e(TAG, "   JSON keys: ${capJson.keys().asSequence().toList()}")
                    continue
                }
                
                // Debug: log timestamp info
                val now = System.currentTimeMillis()
                Log.d(TAG, "⏰ Capability ${capability.capabilityId}: timestamp=${capability.timestamp}, expiresAt=${capability.expiresAt}, now=$now, expired=${capability.isExpired()}")
                
                // Skip if expired
                if (capability.isExpired()) {
                    Log.d(TAG, "Skipping expired capability: ${capability.capabilityId}")
                    continue
                }
                
                // Skip if TTL exhausted
                if (capability.ttl <= 0) {
                    Log.d(TAG, "Skipping capability with TTL=0: ${capability.capabilityId}")
                    continue
                }
                
                // Skip if hops exceeded
                if (capability.hops >= MAX_HOPS) {
                    Log.d(TAG, "Skipping capability with hops=$MAX_HOPS: ${capability.capabilityId}")
                    continue
                }
                
                // Check if we already have this capability
                val existing = gradientTable[capability.capabilityId]
                
                if (existing == null) {
                    // New capability - add with incremented hops
                    val updated = capability.copy(
                        hops = capability.hops + 1,
                        viaNode = sourceNodeId,
                        ttl = capability.ttl - 1
                    )
                    gradientTable[capability.capabilityId] = updated
                    addedCount++
                    
                    // Emit update event
                    scope.launch {
                        _gradientUpdates.emit(updated)
                    }
                } else if (capability.hops + 1 < existing.hops || capability.timestamp > existing.timestamp) {
                    // Better route (fewer hops) or newer announcement
                    val updated = capability.copy(
                        hops = capability.hops + 1,
                        viaNode = sourceNodeId,
                        ttl = capability.ttl - 1
                    )
                    gradientTable[capability.capabilityId] = updated
                    updatedCount++
                    
                    // Emit update event
                    scope.launch {
                        _gradientUpdates.emit(updated)
                    }
                }
            }
            
            Log.i(TAG, "✅ Processed announcement from $sourceNodeName: added=$addedCount, updated=$updatedCount, errors=$errorCount, total_in_table=${gradientTable.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Error handling announcement: ${e.message}", e)
        }
    }
    
    /**
     * Get all known capabilities (local + remote).
     */
    fun getAllCapabilities(): List<CapabilityAnnouncement> {
        return gradientTable.values.toList()
    }
    
    /**
     * Get capabilities by node.
     */
    fun getCapabilitiesByNode(nodeId: String): List<CapabilityAnnouncement> {
        return gradientTable.values.filter { it.nodeId == nodeId }
    }
    
    /**
     * Get a specific capability by ID.
     */
    fun getCapability(capabilityId: String): CapabilityAnnouncement? {
        return gradientTable[capabilityId]
    }
    
    /**
     * Clean up expired capabilities.
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        gradientTable.forEach { (id, cap) ->
            if (cap.isExpired() && !localCapabilities.containsKey(id)) {
                toRemove.add(id)
            }
        }
        
        toRemove.forEach { id ->
            gradientTable.remove(id)
            Log.d(TAG, "Removed expired capability: $id")
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} expired capabilities")
        }
    }
    
    /**
     * Get current device cost factors.
     */
    private fun getCurrentCostFactors(): NodeCostFactors {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val pluggedIn = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                        batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val memoryPercent = (usedMemory.toFloat() / totalMemory.toFloat()) * 100f
        val memoryAvailableGb = freeMemory.toFloat() / (1024f * 1024f * 1024f)
        
        return NodeCostFactors(
            nodeId = nodeId,
            timestamp = System.currentTimeMillis(),
            onBattery = !pluggedIn,
            batteryPercent = batteryPercent,
            pluggedIn = pluggedIn,
            cpuLoad = 0f,  // TODO: Implement CPU monitoring
            gpuLoad = 0f,  // TODO: Implement GPU monitoring
            memoryPercent = memoryPercent,
            memoryAvailableGb = memoryAvailableGb,
            bandwidthMbps = null,  // TODO: Implement bandwidth monitoring
            isMetered = false,  // TODO: Check if on metered connection
            latencyMs = null
        )
    }
    
    /**
     * Get statistics about the gradient table.
     */
    fun getStats(): Map<String, Any> {
        val local = gradientTable.values.count { it.hops == 0 }
        val remote = gradientTable.values.count { it.hops > 0 }
        val avgHops = gradientTable.values.map { it.hops }.average().takeIf { !it.isNaN() } ?: 0.0
        
        return mapOf(
            "total_capabilities" to gradientTable.size,
            "local_capabilities" to local,
            "remote_capabilities" to remote,
            "average_hops" to avgHops,
            "node_id" to nodeId,
            "node_name" to nodeName
        )
    }
    
    /**
     * Handle model catalog message.
     */
    private fun handleModelCatalog(sourceNodeId: String, catalogMsg: JSONObject) {
        try {
            val nodeName = catalogMsg.optString("node_name", sourceNodeId)
            Log.i(TAG, "📚 Received model_catalog from $nodeName")
            
            // Forward to ModelCatalog for processing
            // This will be called from MeshCapabilityHandler which has access to ModelCatalog
            scope.launch {
                _modelCatalogUpdates.emit(catalogMsg to sourceNodeId)
            }
            
            // Also check if there are any new models we should download
            val modelsArray = catalogMsg.optJSONArray("models")
            if (modelsArray != null) {
                for (i in 0 until modelsArray.length()) {
                    val modelJson = modelsArray.getJSONObject(i)
                    val modelId = modelJson.optString("model_id", "")
                    val version = modelJson.optString("version", "")
                    
                    // Trigger download check in VisionModelManager
                    // (will be implemented in MeshCapabilityHandler)
                    Log.d(TAG, "Model available: $modelId v$version from $nodeName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling model catalog: ${e.message}", e)
        }
    }
    
    // Flow for model catalog updates
    private val _modelCatalogUpdates = MutableSharedFlow<Pair<JSONObject, String>>()
    val modelCatalogUpdates: SharedFlow<Pair<JSONObject, String>> = _modelCatalogUpdates.asSharedFlow()
    
    /**
     * Clear all capabilities (for testing/reset).
     */
    fun clear() {
        gradientTable.clear()
        localCapabilities.clear()
        Log.i(TAG, "Cleared all capabilities")
    }
}
