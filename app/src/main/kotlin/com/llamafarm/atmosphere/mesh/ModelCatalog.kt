package com.llamafarm.atmosphere.mesh

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ModelCatalog"

/**
 * Model type enumeration.
 */
enum class ModelType {
    VISION,
    LLM,
    AUDIO,
    EMBEDDING,
    UNKNOWN;
    
    companion object {
        fun fromString(type: String): ModelType {
            return when (type.lowercase()) {
                "vision" -> VISION
                "llm" -> LLM
                "audio" -> AUDIO
                "embedding" -> EMBEDDING
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Information about a peer that has a model.
 */
data class PeerModelInfo(
    val nodeId: String,
    val nodeName: String,
    val httpEndpoint: String?,
    val websocketAvailable: Boolean,
    val latencyMs: Float?,
    val reliability: Float = 1.0f,  // 0.0-1.0, based on past transfer success
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * A model catalog entry - represents a model available on the mesh.
 * May be available from multiple peers.
 */
data class ModelCatalogEntry(
    val modelId: String,
    val name: String,
    val type: ModelType,
    val format: String,  // "pt", "gguf", "tflite", "onnx"
    val sizeBytes: Long,
    val sha256: String,
    val version: String,
    val capabilities: List<String>,
    val classes: List<String>?,
    val classCount: Int?,
    val source: String,  // "huggingface", "llamafarm_training", "imported"
    val sourceRef: String,
    val metadata: Map<String, Any>,
    
    // Mesh availability
    val availableOnPeers: List<PeerModelInfo>,
    val lastSeen: Long = System.currentTimeMillis(),
    val ttl: Long = 300_000L  // 5 minutes default
) {
    /**
     * Check if this entry has expired.
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastSeen > ttl
    }
    
    /**
     * Get the best peer to download from.
     * Prefers: HTTP endpoint > low latency > high reliability.
     */
    fun getBestPeer(): PeerModelInfo? {
        if (availableOnPeers.isEmpty()) return null
        
        // First, prefer peers with HTTP endpoints
        val httpPeers = availableOnPeers.filter { it.httpEndpoint != null }
        if (httpPeers.isNotEmpty()) {
            return httpPeers.minByOrNull { 
                (it.latencyMs ?: 1000f) / it.reliability
            }
        }
        
        // Fall back to WebSocket peers
        return availableOnPeers.minByOrNull {
            (it.latencyMs ?: 1000f) / it.reliability
        }
    }
    
    companion object {
        /**
         * Parse a model catalog entry from JSON (from gossip message).
         */
        fun fromJson(json: JSONObject, peerInfo: PeerModelInfo): ModelCatalogEntry {
            val modelId = json.getString("model_id")
            val name = json.getString("name")
            val type = ModelType.fromString(json.getString("type"))
            val format = json.getString("format")
            val sizeBytes = json.getLong("size_bytes")
            val sha256 = json.getString("sha256")
            val version = json.getString("version")
            
            val capabilitiesArray = json.getJSONArray("capabilities")
            val capabilities = (0 until capabilitiesArray.length()).map {
                capabilitiesArray.getString(it)
            }
            
            val classes = if (json.has("classes")) {
                val classesArray = json.getJSONArray("classes")
                (0 until classesArray.length()).map { classesArray.getString(it) }
            } else null
            
            val classCount = json.optInt("class_count", 0).takeIf { it > 0 }
            
            val source = json.getString("source")
            val sourceRef = json.getString("source_ref")
            
            val metadataJson = json.optJSONObject("metadata")
            val metadata = if (metadataJson != null) {
                metadataJson.keys().asSequence().associateWith { key ->
                    metadataJson.get(key)
                }
            } else emptyMap()
            
            return ModelCatalogEntry(
                modelId = modelId,
                name = name,
                type = type,
                format = format,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                version = version,
                capabilities = capabilities,
                classes = classes,
                classCount = classCount,
                source = source,
                sourceRef = sourceRef,
                metadata = metadata,
                availableOnPeers = listOf(peerInfo),
                lastSeen = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Manages the catalog of models available on the mesh.
 * Merges catalogs from multiple peers, tracks where each model is available.
 */
class ModelCatalog {
    
    // Map: modelId -> ModelCatalogEntry
    private val catalog = ConcurrentHashMap<String, ModelCatalogEntry>()
    
    // Exposed state flow for UI
    private val _availableModels = MutableStateFlow<List<ModelCatalogEntry>>(emptyList())
    val availableModels: StateFlow<List<ModelCatalogEntry>> = _availableModels.asStateFlow()
    
    /**
     * Process a model catalog message from a peer.
     */
    fun processCatalogMessage(nodeId: String, nodeName: String, catalogJson: JSONObject) {
        Log.d(TAG, "Processing catalog from $nodeName ($nodeId)")
        
        val modelsArray = catalogJson.getJSONArray("models")
        
        // Extract transfer endpoints
        val transferEndpoints = catalogJson.optJSONObject("transfer_endpoints")
        val httpEndpoint = transferEndpoints?.optString("http")
        val websocketAvailable = transferEndpoints?.optBoolean("websocket", false) ?: false
        
        val peerInfo = PeerModelInfo(
            nodeId = nodeId,
            nodeName = nodeName,
            httpEndpoint = httpEndpoint,
            websocketAvailable = websocketAvailable,
            latencyMs = null  // TODO: Get from GossipManager cost factors
        )
        
        var addedCount = 0
        var updatedCount = 0
        
        for (i in 0 until modelsArray.length()) {
            val modelJson = modelsArray.getJSONObject(i)
            val modelId = modelJson.getString("model_id")
            
            val existing = catalog[modelId]
            
            if (existing == null) {
                // New model - add to catalog
                val entry = ModelCatalogEntry.fromJson(modelJson, peerInfo)
                catalog[modelId] = entry
                addedCount++
                Log.d(TAG, "Added model: $modelId from $nodeName")
            } else {
                // Model exists - check if this peer is already listed
                val peerExists = existing.availableOnPeers.any { it.nodeId == nodeId }
                
                if (!peerExists) {
                    // Add this peer to the list of providers
                    val updatedEntry = existing.copy(
                        availableOnPeers = existing.availableOnPeers + peerInfo,
                        lastSeen = System.currentTimeMillis()
                    )
                    catalog[modelId] = updatedEntry
                    updatedCount++
                    Log.d(TAG, "Updated model $modelId - now available from ${updatedEntry.availableOnPeers.size} peers")
                } else {
                    // Peer already listed - just update lastSeen
                    val updatedPeers = existing.availableOnPeers.map { peer ->
                        if (peer.nodeId == nodeId) {
                            peer.copy(lastSeen = System.currentTimeMillis())
                        } else {
                            peer
                        }
                    }
                    val updatedEntry = existing.copy(
                        availableOnPeers = updatedPeers,
                        lastSeen = System.currentTimeMillis()
                    )
                    catalog[modelId] = updatedEntry
                }
            }
        }
        
        Log.i(TAG, "Catalog update from $nodeName: $addedCount new, $updatedCount updated, total=${catalog.size}")
        
        // Emit updated catalog to UI
        emitCatalog()
    }
    
    /**
     * Get a model by ID.
     */
    fun getModel(modelId: String): ModelCatalogEntry? {
        return catalog[modelId]
    }
    
    /**
     * Get all models of a specific type.
     */
    fun getModelsByType(type: ModelType): List<ModelCatalogEntry> {
        return catalog.values.filter { it.type == type && !it.isExpired() }
    }
    
    /**
     * Get all models.
     */
    fun getAllModels(): List<ModelCatalogEntry> {
        return catalog.values.filter { !it.isExpired() }
    }
    
    /**
     * Clean up expired catalog entries.
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        catalog.forEach { (id, entry) ->
            // Remove expired peers
            val validPeers = entry.availableOnPeers.filter { peer ->
                now - peer.lastSeen <= entry.ttl
            }
            
            if (validPeers.isEmpty()) {
                // No valid peers left - remove entry
                toRemove.add(id)
            } else if (validPeers.size < entry.availableOnPeers.size) {
                // Some peers expired - update entry
                catalog[id] = entry.copy(availableOnPeers = validPeers)
            }
        }
        
        toRemove.forEach { id ->
            catalog.remove(id)
            Log.d(TAG, "Removed expired model: $id")
        }
        
        if (toRemove.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${toRemove.size} expired models")
            emitCatalog()
        }
    }
    
    /**
     * Update peer reliability based on transfer success/failure.
     */
    fun updatePeerReliability(nodeId: String, success: Boolean) {
        catalog.values.forEach { entry ->
            val updatedPeers = entry.availableOnPeers.map { peer ->
                if (peer.nodeId == nodeId) {
                    // Exponential moving average: new_reliability = 0.9 * old + 0.1 * (success ? 1.0 : 0.0)
                    val newReliability = if (success) {
                        0.9f * peer.reliability + 0.1f
                    } else {
                        0.9f * peer.reliability
                    }
                    peer.copy(reliability = newReliability.coerceIn(0.0f, 1.0f))
                } else {
                    peer
                }
            }
            
            if (updatedPeers != entry.availableOnPeers) {
                catalog[entry.modelId] = entry.copy(availableOnPeers = updatedPeers)
            }
        }
    }
    
    /**
     * Clear all catalog entries.
     */
    fun clear() {
        catalog.clear()
        emitCatalog()
        Log.i(TAG, "Cleared model catalog")
    }
    
    /**
     * Get statistics.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "total_models" to catalog.size,
            "vision_models" to catalog.values.count { it.type == ModelType.VISION },
            "llm_models" to catalog.values.count { it.type == ModelType.LLM },
            "audio_models" to catalog.values.count { it.type == ModelType.AUDIO },
            "embedding_models" to catalog.values.count { it.type == ModelType.EMBEDDING },
            "total_peers" to catalog.values.flatMap { it.availableOnPeers }.distinctBy { it.nodeId }.size
        )
    }
    
    /**
     * Emit current catalog to StateFlow.
     */
    private fun emitCatalog() {
        _availableModels.value = catalog.values.filter { !it.isExpired() }.sortedBy { it.name }
    }
}
