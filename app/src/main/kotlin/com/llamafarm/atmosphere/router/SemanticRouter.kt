package com.llamafarm.atmosphere.router

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

private const val TAG = "SemanticRouter"

/**
 * A registered capability with its embedding.
 */
data class Capability(
    val name: String,
    val description: String,
    val nodeId: String,
    val handler: String,  // "local", "remote:{node_id}", "mesh"
    val keywords: List<String> = emptyList(),
    val embedding: FloatArray? = null,
    val cost: Float = 1.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Capability) return false
        return name == other.name && nodeId == other.nodeId
    }
    
    override fun hashCode(): Int = name.hashCode() * 31 + nodeId.hashCode()
}

/**
 * Result of routing an intent.
 */
data class RouteResult(
    val capability: Capability,
    val score: Float,
    val method: String  // "embedding", "keyword", "exact"
)

/**
 * Semantic Router for Android.
 * 
 * Routes intents to capabilities using:
 * 1. Exact match on capability name
 * 2. Keyword matching (fast, works offline)
 * 3. Embedding similarity (requires mesh connection to Mac/LlamaFarm)
 * 
 * Usage:
 *     val router = SemanticRouter(context)
 *     router.registerCapability("camera", "Take photos and videos")
 *     
 *     val result = router.route("take a picture of the living room")
 *     // result.capability.name == "camera"
 */
class SemanticRouter(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val capabilities = ConcurrentHashMap<String, MutableList<Capability>>()
    
    // Remote embedding service (via mesh)
    private var embeddingService: EmbeddingService? = null
    
    // Cache for intent embeddings
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()
    private val maxCacheSize = 500
    
    // Keyword index for fast matching
    private val keywordIndex = ConcurrentHashMap<String, MutableList<Capability>>()
    
    /**
     * Initialize the router with optional remote embedding service.
     */
    fun initialize(embeddingService: EmbeddingService? = null) {
        this.embeddingService = embeddingService
        Log.i(TAG, "SemanticRouter initialized (embeddings: ${embeddingService != null})")
    }
    
    /**
     * Register a local capability.
     */
    fun registerCapability(
        name: String,
        description: String,
        nodeId: String = "local",
        handler: String = "local",
        keywords: List<String> = emptyList(),
        cost: Float = 1.0f
    ) {
        val capability = Capability(
            name = name,
            description = description,
            nodeId = nodeId,
            handler = handler,
            keywords = extractKeywords(description) + keywords,
            cost = cost
        )
        
        capabilities.getOrPut(name) { mutableListOf() }.add(capability)
        
        // Index keywords
        capability.keywords.forEach { keyword ->
            keywordIndex.getOrPut(keyword.lowercase()) { mutableListOf() }.add(capability)
        }
        
        Log.d(TAG, "Registered capability: $name (keywords: ${capability.keywords})")
    }
    
    /**
     * Register a remote capability discovered via mesh.
     */
    fun registerRemoteCapability(
        name: String,
        description: String,
        nodeId: String,
        embedding: FloatArray? = null,
        cost: Float = 1.0f
    ) {
        val capability = Capability(
            name = name,
            description = description,
            nodeId = nodeId,
            handler = "remote:$nodeId",
            keywords = extractKeywords(description),
            embedding = embedding,
            cost = cost
        )
        
        capabilities.getOrPut(name) { mutableListOf() }.add(capability)
        
        capability.keywords.forEach { keyword ->
            keywordIndex.getOrPut(keyword.lowercase()) { mutableListOf() }.add(capability)
        }
        
        Log.d(TAG, "Registered remote capability: $name from $nodeId")
    }
    
    /**
     * Remove capabilities from a node (e.g., when node disconnects).
     */
    fun removeNodeCapabilities(nodeId: String) {
        capabilities.values.forEach { list ->
            list.removeAll { it.nodeId == nodeId }
        }
        keywordIndex.values.forEach { list ->
            list.removeAll { it.nodeId == nodeId }
        }
        Log.d(TAG, "Removed capabilities from node: $nodeId")
    }
    
    /**
     * Route an intent to the best capability.
     * 
     * @param intent Natural language intent (e.g., "take a photo")
     * @param preferLocal Prefer local capabilities over remote
     * @return Best matching capability with score, or null if none found
     */
    suspend fun route(intent: String, preferLocal: Boolean = false): RouteResult? {
        val normalizedIntent = intent.trim().lowercase()
        
        // 1. Try exact match first
        val exactMatch = findExactMatch(normalizedIntent)
        if (exactMatch != null) {
            return RouteResult(exactMatch, 1.0f, "exact")
        }
        
        // 2. Try keyword matching (fast, works offline)
        val keywordMatches = findKeywordMatches(normalizedIntent)
        
        // 3. Try embedding similarity if service available
        val embeddingMatches = if (embeddingService != null) {
            findEmbeddingMatches(intent)
        } else {
            emptyList()
        }
        
        // Combine and rank results
        val allMatches = mutableMapOf<Capability, Float>()
        
        // Add keyword matches with scores
        keywordMatches.forEach { (cap, score) ->
            allMatches[cap] = maxOf(allMatches[cap] ?: 0f, score * 0.7f)  // 70% weight
        }
        
        // Add embedding matches with scores
        embeddingMatches.forEach { (cap, score) ->
            val existing = allMatches[cap] ?: 0f
            allMatches[cap] = maxOf(existing, score)  // Embedding scores are already weighted
        }
        
        if (allMatches.isEmpty()) {
            Log.d(TAG, "No matches found for intent: $intent")
            return null
        }
        
        // Sort by score, prefer local if requested
        val sorted = allMatches.entries.sortedWith(
            compareByDescending<Map.Entry<Capability, Float>> { it.value }
                .thenBy { if (preferLocal && it.key.nodeId != "local") 1 else 0 }
                .thenBy { it.key.cost }
        )
        
        val best = sorted.first()
        val method = if (best.key in embeddingMatches.map { it.first }) "embedding" else "keyword"
        
        Log.d(TAG, "Routed '$intent' -> ${best.key.name} (score: ${best.value}, method: $method)")
        return RouteResult(best.key, best.value, method)
    }
    
    /**
     * Route and execute an intent.
     */
    suspend fun routeAndExecute(
        intent: String,
        payload: JSONObject = JSONObject(),
        onResult: (JSONObject) -> Unit
    ) {
        val result = route(intent)
        
        if (result == null) {
            onResult(JSONObject().apply {
                put("error", "No capability found for intent")
                put("intent", intent)
            })
            return
        }
        
        when {
            result.capability.handler == "local" -> {
                // Execute locally
                executeLocal(result.capability, intent, payload, onResult)
            }
            result.capability.handler.startsWith("remote:") -> {
                // Execute on remote node via mesh
                executeRemote(result.capability, intent, payload, onResult)
            }
            else -> {
                onResult(JSONObject().apply {
                    put("error", "Unknown handler: ${result.capability.handler}")
                })
            }
        }
    }
    
    /**
     * Get all registered capabilities.
     */
    fun getCapabilities(): List<Capability> {
        return capabilities.values.flatten()
    }
    
    /**
     * Get capabilities by name.
     */
    fun getCapability(name: String): List<Capability> {
        return capabilities[name] ?: emptyList()
    }
    
    // === Private Methods ===
    
    private fun findExactMatch(intent: String): Capability? {
        // Check if intent matches a capability name exactly
        return capabilities[intent]?.firstOrNull()
    }
    
    private fun findKeywordMatches(intent: String): List<Pair<Capability, Float>> {
        val words = intent.split(Regex("\\s+")).map { it.lowercase() }
        val matches = mutableMapOf<Capability, Int>()
        
        words.forEach { word ->
            // Exact keyword match
            keywordIndex[word]?.forEach { cap ->
                matches[cap] = (matches[cap] ?: 0) + 2
            }
            
            // Partial match (word contains keyword or vice versa)
            keywordIndex.keys.filter { 
                it.contains(word) || word.contains(it) 
            }.forEach { keyword ->
                keywordIndex[keyword]?.forEach { cap ->
                    matches[cap] = (matches[cap] ?: 0) + 1
                }
            }
        }
        
        // Normalize scores
        val maxScore = matches.values.maxOrNull() ?: 1
        return matches.map { (cap, score) ->
            cap to score.toFloat() / maxScore
        }.filter { it.second > 0.3f }  // Threshold
    }
    
    private suspend fun findEmbeddingMatches(intent: String): List<Pair<Capability, Float>> {
        val service = embeddingService ?: return emptyList()
        
        try {
            // Get or compute intent embedding
            val intentEmbedding = embeddingCache.getOrPut(intent) {
                service.embed(intent)
            }
            
            // Manage cache size
            if (embeddingCache.size > maxCacheSize) {
                val toRemove = embeddingCache.keys.take(maxCacheSize / 4)
                toRemove.forEach { embeddingCache.remove(it) }
            }
            
            // Compare with all capabilities that have embeddings
            val matches = mutableListOf<Pair<Capability, Float>>()
            
            capabilities.values.flatten().forEach { cap ->
                val capEmbedding = cap.embedding ?: run {
                    // Compute embedding for capability description
                    try {
                        service.embed(cap.description)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (capEmbedding != null) {
                    val similarity = cosineSimilarity(intentEmbedding, capEmbedding)
                    if (similarity > 0.5f) {  // Threshold
                        matches.add(cap to similarity)
                    }
                }
            }
            
            return matches.sortedByDescending { it.second }
            
        } catch (e: Exception) {
            Log.w(TAG, "Embedding matching failed: ${e.message}")
            return emptyList()
        }
    }
    
    private fun extractKeywords(text: String): List<String> {
        // Extract meaningful keywords from description
        val stopWords = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "must", "can", "this", "that"
        )
        
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0f
    }
    
    private suspend fun executeLocal(
        capability: Capability,
        intent: String,
        payload: JSONObject,
        onResult: (JSONObject) -> Unit
    ) {
        // This would be implemented by the app registering handlers
        onResult(JSONObject().apply {
            put("capability", capability.name)
            put("handler", "local")
            put("status", "not_implemented")
            put("message", "Local handler not registered for ${capability.name}")
        })
    }
    
    private suspend fun executeRemote(
        capability: Capability,
        intent: String,
        payload: JSONObject,
        onResult: (JSONObject) -> Unit
    ) {
        // This would use the mesh connection to call the remote node
        onResult(JSONObject().apply {
            put("capability", capability.name)
            put("handler", capability.handler)
            put("nodeId", capability.nodeId)
            put("status", "pending")
            put("message", "Remote execution requires mesh connection")
        })
    }
}

/**
 * Interface for embedding service (via mesh or local model).
 */
interface EmbeddingService {
    /**
     * Generate embedding for text.
     * @return Float array of embedding vector
     */
    suspend fun embed(text: String): FloatArray
    
    /**
     * Generate embeddings for multiple texts.
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}

/**
 * Embedding service that calls a remote node via mesh.
 */
class MeshEmbeddingService(
    private val meshConnection: Any,  // MeshConnection or TransportManager
    private val targetNodeId: String? = null  // null = broadcast, find first responder
) : EmbeddingService {
    
    override suspend fun embed(text: String): FloatArray {
        // TODO: Implement mesh call to remote embedding service
        // For now, return empty - will be implemented with mesh integration
        throw NotImplementedError("Mesh embedding service not yet implemented")
    }
    
    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }
}

/**
 * HTTP-based embedding service (calls LlamaFarm directly).
 */
class HttpEmbeddingService(
    private val baseUrl: String = "http://localhost:11540"
) : EmbeddingService {
    
    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val url = java.net.URL("$baseUrl/v1/embeddings")
        val connection = url.openConnection() as java.net.HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("model", "nomic-ai/nomic-embed-text-v1.5")
                put("input", text)
            }
            
            connection.outputStream.write(payload.toString().toByteArray())
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val embedding = json.getJSONArray("data")
                    .getJSONObject(0)
                    .getJSONArray("embedding")
                
                FloatArray(embedding.length()) { i -> 
                    embedding.getDouble(i).toFloat() 
                }
            } else {
                throw RuntimeException("Embedding failed: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
    
    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        val url = java.net.URL("$baseUrl/v1/embeddings")
        val connection = url.openConnection() as java.net.HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val payload = JSONObject().apply {
                put("model", "nomic-ai/nomic-embed-text-v1.5")
                put("input", JSONArray(texts))
            }
            
            connection.outputStream.write(payload.toString().toByteArray())
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val data = json.getJSONArray("data")
                
                // Sort by index and extract embeddings
                val embeddings = mutableListOf<Pair<Int, FloatArray>>()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val index = item.getInt("index")
                    val embedding = item.getJSONArray("embedding")
                    embeddings.add(index to FloatArray(embedding.length()) { j ->
                        embedding.getDouble(j).toFloat()
                    })
                }
                
                embeddings.sortedBy { it.first }.map { it.second }
            } else {
                throw RuntimeException("Batch embedding failed: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
}
