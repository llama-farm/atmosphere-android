package com.llamafarm.atmosphere.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model capability tier based on parameter count.
 */
enum class ModelTier(val value: String) {
    TINY("tiny"),      // < 2B params
    SMALL("small"),    // 2-4B params
    MEDIUM("medium"),  // 7-14B params
    LARGE("large"),    // 30-34B params
    XL("xl");          // 70B+ params
    
    companion object {
        fun fromParams(paramsBillions: Float): ModelTier = when {
            paramsBillions < 2 -> TINY
            paramsBillions < 5 -> SMALL
            paramsBillions < 20 -> MEDIUM
            paramsBillions < 50 -> LARGE
            else -> XL
        }
        
        fun fromModelName(modelName: String): ModelTier {
            val lower = modelName.lowercase()
            
            // Extract parameter count
            val bMatch = Regex("""(\d+\.?\d*)b""").find(lower)
            if (bMatch != null) {
                val params = bMatch.groupValues[1].toFloatOrNull() ?: 7f
                return fromParams(params)
            }
            
            val mMatch = Regex("""(\d+)m""").find(lower)
            if (mMatch != null) {
                val params = (mMatch.groupValues[1].toFloatOrNull() ?: 350f) / 1000f
                return fromParams(params)
            }
            
            // Fallback heuristics
            return when {
                lower.contains("tiny") || lower.contains("mini") -> TINY
                lower.contains("small") -> SMALL
                lower.contains("large") || lower.contains("xl") -> LARGE
                else -> MEDIUM
            }
        }
        
        fun fromValue(value: String): ModelTier = values().find { it.value == value } ?: MEDIUM
    }
}

/**
 * Capability categories.
 */
enum class CapabilityType(val value: String) {
    // Language
    LLM_CHAT("llm/chat"),
    LLM_COMPLETE("llm/complete"),
    LLM_EMBED("llm/embed"),
    
    // Audio
    AUDIO_TRANSCRIBE("audio/transcribe"),
    AUDIO_GENERATE("audio/generate"),
    
    // Vision
    VISION_ANALYZE("vision/analyze"),
    VISION_GENERATE("vision/generate"),
    SENSOR_CAMERA("sensor/camera"),
    
    // IoT
    IOT_SENSOR("iot/sensor"),
    IOT_ACTUATOR("iot/actuator"),
    
    // Agents
    AGENT_TASK("agent/task"),
    AGENT_TOOL("agent/tool"),
    
    // Actions
    ACTION_NOTIFY("action/notify"),
    ACTION_STORE("action/store");
    
    companion object {
        fun fromValue(value: String): CapabilityType = 
            values().find { it.value == value } ?: LLM_CHAT
    }
}

/**
 * Node cost factors for routing decisions.
 */
data class NodeCostFactors(
    val nodeId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val onBattery: Boolean = false,
    val batteryPercent: Float = 100f,
    val pluggedIn: Boolean = true,
    val cpuLoad: Float = 0f,
    val gpuLoad: Float = 0f,
    val memoryPercent: Float = 0f,
    val memoryAvailableGb: Float = 0f,
    val bandwidthMbps: Float? = null,
    val isMetered: Boolean = false,
    val latencyMs: Float? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("node_id", nodeId)
        put("timestamp", timestamp)
        put("on_battery", onBattery)
        put("battery_percent", batteryPercent)
        put("plugged_in", pluggedIn)
        put("cpu_load", cpuLoad)
        put("gpu_load", gpuLoad)
        put("memory_percent", memoryPercent)
        put("memory_available_gb", memoryAvailableGb)
        bandwidthMbps?.let { put("bandwidth_mbps", it) }
        put("is_metered", isMetered)
        latencyMs?.let { put("latency_ms", it) }
    }
    
    companion object {
        fun fromJson(json: JSONObject): NodeCostFactors = NodeCostFactors(
            nodeId = json.optString("node_id", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            onBattery = json.optBoolean("on_battery", false),
            batteryPercent = json.optDouble("battery_percent", 100.0).toFloat(),
            pluggedIn = json.optBoolean("plugged_in", true),
            cpuLoad = json.optDouble("cpu_load", 0.0).toFloat(),
            gpuLoad = json.optDouble("gpu_load", 0.0).toFloat(),
            memoryPercent = json.optDouble("memory_percent", 0.0).toFloat(),
            memoryAvailableGb = json.optDouble("memory_available_gb", 0.0).toFloat(),
            bandwidthMbps = if (json.has("bandwidth_mbps")) json.optDouble("bandwidth_mbps").toFloat() else null,
            isMetered = json.optBoolean("is_metered", false),
            latencyMs = if (json.has("latency_ms")) json.optDouble("latency_ms").toFloat() else null,
        )
    }
}

/**
 * Complete capability description for mesh routing.
 * 
 * This is what gets gossiped between nodes.
 */
data class CapabilityAnnouncement(
    // === IDENTITY ===
    val nodeId: String,
    val nodeName: String,
    val capabilityId: String,
    
    // === PROJECT ROUTING ===
    val projectPath: String,
    val modelAlias: String,  // "default" - what API calls use
    
    // === MODEL METADATA ===
    val modelActual: String,  // "unsloth/Qwen3-1.7B-GGUF:Q4_K_M"
    val modelFamily: String,  // "qwen3", "llama3"
    val modelParamsB: Float,  // 1.7, 7.0, 70.0
    val modelQuantization: String,
    val modelTier: ModelTier,
    
    // === CAPABILITY TYPE ===
    val capabilityType: CapabilityType,
    val triggers: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    
    // === SEMANTIC MATCHING ===
    val label: String = "",
    val description: String = "",
    val embedding: List<Float>? = null,
    val embeddingHash: Long = 0L,  // 64-bit SimHash
    val keywords: List<String> = emptyList(),
    
    // === INTELLIGENCE PROFILE ===
    val goodFor: List<String> = emptyList(),
    val notGoodFor: List<String> = emptyList(),
    val hasRag: Boolean = false,
    val hasVision: Boolean = false,
    val hasTools: Boolean = false,
    val hasStreaming: Boolean = true,
    val contextLength: Int = 4096,
    val specializations: List<String> = emptyList(),
    
    // === COST FACTORS ===
    val costFactors: NodeCostFactors? = null,
    val estimatedLatencyMs: Float = 100f,
    val tokensPerSecond: Float = 50f,
    val apiCostPer1kTokens: Float = 0f,  // 0 = local/free
    
    // === ROUTING METADATA ===
    val hops: Int = 0,
    val viaNode: String? = null,
    val ttl: Int = 10,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 300_000,  // 5 min
    
    // === SECURITY ===
    val signature: String = "",
) {
    /**
     * Check if this capability has expired.
     */
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    
    /**
     * Compute similarity between this capability's SimHash and another hash.
     * Returns value 0-1 where 1 = identical.
     *
     * Uses the unified SimHash algorithm that produces identical results
     * on Python and Kotlin.
     *
     * @see com.llamafarm.atmosphere.router.SimHash
     */
    fun similarityHash(otherHash: Long): Float {
        return com.llamafarm.atmosphere.router.SimHash.similarity(embeddingHash, otherHash)
    }
    
    /**
     * Compute SimHash from description and label if not already set.
     * Call this after creating a capability locally.
     */
    fun computeSimHash(): Long {
        if (description.isNotEmpty() || label.isNotEmpty()) {
            return com.llamafarm.atmosphere.router.SimHash.computeSimHash("$label $description")
        }
        if (keywords.isNotEmpty()) {
            return com.llamafarm.atmosphere.router.SimHash.computeSimHashFromTokens(keywords)
        }
        return 0L
    }
    
    /**
     * Serialize to JSON for gossip.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        // Identity
        put("node_id", nodeId)
        put("node_name", nodeName)
        put("capability_id", capabilityId)
        
        // Project
        put("project_path", projectPath)
        put("model_alias", modelAlias)
        
        // Model
        put("model_actual", modelActual)
        put("model_family", modelFamily)
        put("model_params_b", modelParamsB)
        put("model_quantization", modelQuantization)
        put("model_tier", modelTier.value)
        
        // Type
        put("capability_type", capabilityType.value)
        put("triggers", JSONArray(triggers))
        put("tools", JSONArray(tools))
        
        // Semantic
        put("label", label)
        put("description", description)
        embedding?.let { put("embedding", JSONArray(it)) }
        put("embedding_hash", embeddingHash)
        put("keywords", JSONArray(keywords))
        
        // Intelligence
        put("good_for", JSONArray(goodFor))
        put("not_good_for", JSONArray(notGoodFor))
        put("has_rag", hasRag)
        put("has_vision", hasVision)
        put("has_tools", hasTools)
        put("has_streaming", hasStreaming)
        put("context_length", contextLength)
        put("specializations", JSONArray(specializations))
        
        // Cost
        costFactors?.let { put("cost_factors", it.toJson()) }
        put("estimated_latency_ms", estimatedLatencyMs)
        put("tokens_per_second", tokensPerSecond)
        put("api_cost_per_1k_tokens", apiCostPer1kTokens)
        
        // Routing
        put("hops", hops)
        viaNode?.let { put("via_node", it) }
        put("ttl", ttl)
        put("timestamp", timestamp)
        put("expires_at", expiresAt)
        
        // Security
        put("signature", signature)
    }
    
    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): CapabilityAnnouncement {
            return CapabilityAnnouncement(
                // Identity
                nodeId = json.getString("node_id"),
                nodeName = json.getString("node_name"),
                capabilityId = json.getString("capability_id"),
                
                // Project
                projectPath = json.getString("project_path"),
                modelAlias = json.getString("model_alias"),
                
                // Model
                modelActual = json.getString("model_actual"),
                modelFamily = json.getString("model_family"),
                modelParamsB = json.optDouble("model_params_b", 7.0).toFloat(),
                modelQuantization = json.optString("model_quantization", ""),
                modelTier = ModelTier.fromValue(json.getString("model_tier")),
                
                // Type
                capabilityType = CapabilityType.fromValue(json.getString("capability_type")),
                triggers = json.optJSONArray("triggers")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                tools = json.optJSONArray("tools")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                
                // Semantic
                label = json.optString("label", ""),
                description = json.optString("description", ""),
                embedding = json.optJSONArray("embedding")?.let { arr ->
                    (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                },
                embeddingHash = json.optLong("embedding_hash", 0),
                keywords = json.optJSONArray("keywords")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                
                // Intelligence
                goodFor = json.optJSONArray("good_for")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                notGoodFor = json.optJSONArray("not_good_for")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                hasRag = json.optBoolean("has_rag", false),
                hasVision = json.optBoolean("has_vision", false),
                hasTools = json.optBoolean("has_tools", false),
                hasStreaming = json.optBoolean("has_streaming", true),
                contextLength = json.optInt("context_length", 4096),
                specializations = json.optJSONArray("specializations")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                
                // Cost
                costFactors = json.optJSONObject("cost_factors")?.let { 
                    NodeCostFactors.fromJson(it) 
                },
                estimatedLatencyMs = json.optDouble("estimated_latency_ms", 100.0).toFloat(),
                tokensPerSecond = json.optDouble("tokens_per_second", 50.0).toFloat(),
                apiCostPer1kTokens = json.optDouble("api_cost_per_1k_tokens", 0.0).toFloat(),
                
                // Routing
                hops = json.optInt("hops", 0),
                viaNode = json.optString("via_node", null),
                ttl = json.optInt("ttl", 10),
                // Handle timestamps from Mac (seconds as float) vs Android (milliseconds as long)
                // Use optDouble to handle float values, then convert to long
                timestamp = json.optDouble("timestamp", System.currentTimeMillis().toDouble()).toLong().let { ts ->
                    if (ts < 10_000_000_000L) ts * 1000 else ts
                },
                expiresAt = json.optDouble("expires_at", (System.currentTimeMillis() + 300_000).toDouble()).toLong().let { ts ->
                    if (ts < 10_000_000_000L) ts * 1000 else ts
                },
                
                // Security
                signature = json.optString("signature", ""),
            )
        }
        
        /**
         * Extract model family from name.
         */
        fun extractModelFamily(modelName: String): String {
            val lower = modelName.lowercase()
            val families = listOf(
                "qwen", "llama", "mistral", "phi", "gemma", "mixtral",
                "whisper", "stable-diffusion", "flux"
            )
            
            for (family in families) {
                if (lower.contains(family)) {
                    val match = Regex("""$family(\d*)""").find(lower)
                    return if (match != null && match.groupValues[1].isNotEmpty()) {
                        "$family${match.groupValues[1]}"
                    } else {
                        family
                    }
                }
            }
            
            return "unknown"
        }
    }
}
