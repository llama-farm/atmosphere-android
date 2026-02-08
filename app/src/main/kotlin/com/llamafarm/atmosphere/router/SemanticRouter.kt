package com.llamafarm.atmosphere.router

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.core.CapabilityAnnouncement
import com.llamafarm.atmosphere.core.GossipManager
import com.llamafarm.atmosphere.core.ModelTier
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "SemanticRouter"

/**
 * Semantic Router - Hash-first cascading capability matcher.
 * 
 * Uses a 3-tier cascade for routing:
 * 1. Hash matching (64-bit SimHash, super fast)
 * 2. Keyword overlap (Jaccard similarity)
 * 3. Fuzzy text matching (fallback)
 * 
 * Integrates with GossipManager to access all mesh capabilities.
 * Returns RoutingDecision with full explanation.
 * 
 * Usage:
 *     val router = SemanticRouter.getInstance(context)
 *     val decision = router.route("What do llamas eat?")
 *     // decision.capability -> best match
 *     // decision.explanation -> why it was chosen
 */
class SemanticRouter private constructor(
    private val context: Context
) {
    
    companion object {
        @Volatile
        private var instance: SemanticRouter? = null
        
        fun getInstance(context: Context): SemanticRouter {
            return instance ?: synchronized(this) {
                instance ?: SemanticRouter(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        // Scoring weights
        private const val WEIGHT_SEMANTIC = 0.40f
        private const val WEIGHT_LATENCY = 0.25f
        private const val WEIGHT_CAPABILITY = 0.20f
        private const val WEIGHT_HOP = 0.10f
        private const val WEIGHT_COST = 0.05f
    }
    
    private val gossipManager = GossipManager.getInstance(context)
    
    // Legacy capability registry for local capabilities
    private val capabilities = mutableMapOf<String, MutableList<Capability>>()
    private val keywordIndex = mutableMapOf<String, MutableList<Capability>>()
    // private var embeddingService: EmbeddingService? = null // TODO: Re-implement with new embedding API
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private val maxCacheSize = 1000
    
    /**
     * Extract keywords from text for indexing.
     */
    private fun extractKeywords(text: String): List<String> {
        return text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }
            .distinct()
    }
    
    /**
     * Register a local capability.
     */
    fun registerCapability(capability: Capability) {
        capabilities.getOrPut(capability.id) { mutableListOf() }.add(capability)
        
        // Index by keywords
        capability.keywords.forEach { keyword ->
            keywordIndex.getOrPut(keyword.lowercase()) { mutableListOf() }.add(capability)
        }
        
        // Also index by description keywords
        extractKeywords(capability.description).forEach { keyword ->
            keywordIndex.getOrPut(keyword) { mutableListOf() }.add(capability)
        }
        
        Log.d(TAG, "Registered capability: ${capability.name} (${capability.id})")
    }
    
    /**
     * Register a remote capability discovered from mesh.
     */
    fun registerRemoteCapability(
        name: String,
        description: String,
        nodeId: String,
        cost: Float = 1.0f
    ) {
        val capability = Capability(
            id = "$nodeId:$name",
            name = name,
            description = description,
            nodeId = nodeId,
            cost = cost,
            handler = "remote:$nodeId"
        )
        registerCapability(capability)
    }
    
    /**
     * Route a query to the best capability.
     * 
     * @param query Natural language query
     * @param queryHash Optional pre-computed 64-bit SimHash (0 = compute from keywords)
     * @param constraints Optional routing constraints
     * @return RoutingDecision with explanation, or null if no match
     */
    fun route(
        query: String,
        queryHash: Long = 0L,
        constraints: RouteConstraints? = null
    ): RoutingDecision? {
        // Get all known capabilities from gradient table
        val allCapabilities = gossipManager.getAllCapabilities()
        Log.d(TAG, "🔍 Route query: '$query' - found ${allCapabilities.size} capabilities")
        allCapabilities.forEach { cap ->
            Log.d(TAG, "  📦 ${cap.capabilityId} @ ${cap.nodeId}")
        }
        
        if (allCapabilities.isEmpty()) {
            Log.w(TAG, "No capabilities available for routing")
            return null
        }
        
        // Apply constraints filter
        val eligibleCapabilities = if (constraints != null) {
            filterByConstraints(allCapabilities, constraints)
        } else {
            allCapabilities
        }
        
        if (eligibleCapabilities.isEmpty()) {
            Log.w(TAG, "No capabilities match constraints")
            return null
        }
        
        // Find best match using hash-first cascade
        var matchResult = HashMatcher.findBestMatch(
            query = query,
            queryHash = queryHash,
            capabilities = eligibleCapabilities,
            minScore = 0.1f  // Lower threshold - be more permissive
        )
        
        // BEST EFFORT: If no match found but we have capabilities, use the best one anyway!
        // This is especially important when there's only one choice.
        if (matchResult == null && eligibleCapabilities.isNotEmpty()) {
            Log.i(TAG, "🎯 Best-effort routing: No semantic match, but ${eligibleCapabilities.size} capability available")
            
            // Score all capabilities and pick the best based on overall quality
            val scored = eligibleCapabilities.map { cap ->
                // Compute a basic score from: model tier, cost, hops
                val tierScore = when (cap.modelTier) {
                    ModelTier.XL -> 1.0f
                    ModelTier.LARGE -> 0.8f
                    ModelTier.MEDIUM -> 0.6f
                    ModelTier.SMALL -> 0.4f
                    ModelTier.TINY -> 0.2f
                }
                val hopPenalty = cap.hops * 0.05f
                // Compute rough cost from battery and load
                val cf = cap.costFactors
                val costFactor = if (cf != null) {
                    (if (cf.onBattery) 0.5f else 0f) + cf.cpuLoad * 0.3f + cf.memoryPercent * 0.2f
                } else 0.5f
                val costScore = 1.0f / (1.0f + costFactor)
                
                val overallScore = tierScore * 0.5f + costScore * 0.3f - hopPenalty
                Pair(cap, overallScore)
            }.sortedByDescending { it.second }
            
            val best = scored.first()
            Log.i(TAG, "  → Selected ${best.first.capabilityId} (quality score: ${best.second})")
            
            matchResult = HashMatcher.MatchResult(
                capability = best.first,
                score = best.second.coerceIn(0.1f, 1.0f),
                method = HashMatcher.MatchMethod.FALLBACK,
                explanation = "Best-effort routing: selected best available capability"
            )
        }
        
        if (matchResult == null) {
            Log.d(TAG, "No capabilities available for query: $query")
            return null
        }
        
        // Compute composite score with all factors
        val scoreBreakdown = computeCompositeScore(
            capability = matchResult.capability,
            semanticScore = matchResult.score,
            constraints = constraints
        )
        
        // Find alternatives
        val alternatives = HashMatcher.findAllMatches(
            query = query,
            queryHash = queryHash,
            capabilities = eligibleCapabilities,
            minScore = 0.3f,
            maxResults = 5
        ).filter { it.capability.capabilityId != matchResult.capability.capabilityId }
         .map { it.capability to computeCompositeScore(it.capability, it.score, constraints).compositeScore }
         .sortedByDescending { it.second }
        
        // Build explanation
        val explanation = buildExplanation(
            matchResult = matchResult,
            scoreBreakdown = scoreBreakdown,
            constraints = constraints
        )
        
        Log.i(TAG, "Routed '$query' -> ${matchResult.capability.label} (${matchResult.capability.nodeId}) " +
                  "score=${(scoreBreakdown.compositeScore * 100).toInt()}% method=${matchResult.method}")
        
        return RoutingDecision(
            capability = matchResult.capability,
            scoreBreakdown = scoreBreakdown,
            matchMethod = matchResult.method,
            explanation = explanation,
            alternatives = alternatives
        )
    }
    
    /**
     * Filter capabilities by constraints.
     */
    private fun filterByConstraints(
        capabilities: List<CapabilityAnnouncement>,
        constraints: RouteConstraints
    ): List<CapabilityAnnouncement> {
        return capabilities.filter { cap ->
            // Latency constraint
            if (constraints.maxLatencyMs != null && cap.estimatedLatencyMs > constraints.maxLatencyMs) {
                return@filter false
            }
            
            // Local preference
            if (constraints.preferLocal && cap.hops > 0) {
                return@filter false
            }
            
            // Feature requirements
            if (constraints.requireRag && !cap.hasRag) {
                return@filter false
            }
            if (constraints.requireVision && !cap.hasVision) {
                return@filter false
            }
            if (constraints.requireTools && !cap.hasTools) {
                return@filter false
            }
            
            // Model size constraints
            if (constraints.modelSizeMin != null && cap.modelTier.ordinal < constraints.modelSizeMin.ordinal) {
                return@filter false
            }
            if (constraints.modelSizeMax != null && cap.modelTier.ordinal > constraints.modelSizeMax.ordinal) {
                return@filter false
            }
            
            // Hop limit
            if (constraints.maxHops != null && cap.hops > constraints.maxHops) {
                return@filter false
            }
            
            // Node filters
            if (cap.nodeId in constraints.excludeNodeIds) {
                return@filter false
            }
            
            // Cost constraint
            if (constraints.maxCostPer1kTokens != null && 
                cap.apiCostPer1kTokens > constraints.maxCostPer1kTokens) {
                return@filter false
            }
            
            // Tokens per second requirement
            if (constraints.minTokensPerSecond != null &&
                cap.tokensPerSecond < constraints.minTokensPerSecond) {
                return@filter false
            }
            
            // Check expiration
            !cap.isExpired()
        }
    }
    
    /**
     * Compute composite score with all factors.
     */
    private fun computeCompositeScore(
        capability: CapabilityAnnouncement,
        semanticScore: Float,
        constraints: RouteConstraints?
    ): ScoreBreakdown {
        // Semantic score (already computed by HashMatcher)
        val semantic = semanticScore
        
        // Latency score (lower is better, normalized)
        val maxLatency = constraints?.maxLatencyMs ?: 2000f
        val latency = 1f - min(1f, capability.estimatedLatencyMs / maxLatency)
        
        // Capability score (RAG, tools, specializations)
        var capabilityScore = 0.5f  // Base score
        if (capability.hasRag) capabilityScore += 0.15f
        if (capability.hasTools) capabilityScore += 0.15f
        if (capability.hasVision) capabilityScore += 0.1f
        if (capability.specializations.isNotEmpty()) capabilityScore += 0.1f
        capabilityScore = min(1f, capabilityScore)
        
        // Hop score (prefer fewer hops)
        val hopScore = max(0f, 1f - (capability.hops / 10f))
        
        // Cost score (lower is better, free = 1.0)
        val costScore = if (capability.apiCostPer1kTokens == 0f) {
            1.0f
        } else {
            max(0f, 1f - (capability.apiCostPer1kTokens / 0.01f))  // Normalize to $0.01/1k
        }
        
        // Composite weighted score
        val composite = (semantic * WEIGHT_SEMANTIC) +
                       (latency * WEIGHT_LATENCY) +
                       (capabilityScore * WEIGHT_CAPABILITY) +
                       (hopScore * WEIGHT_HOP) +
                       (costScore * WEIGHT_COST)
        
        return ScoreBreakdown(
            semanticScore = semantic,
            latencyScore = latency,
            capabilityScore = capabilityScore,
            hopScore = hopScore,
            costScore = costScore,
            compositeScore = composite
        )
    }
    
    /**
     * Build human-readable explanation.
     */
    private fun buildExplanation(
        matchResult: HashMatcher.MatchResult,
        scoreBreakdown: ScoreBreakdown,
        constraints: RouteConstraints?
    ): String {
        val cap = matchResult.capability
        
        return buildString {
            append("Matched '${cap.label}' on ${cap.nodeName} ")
            append("via ${matchResult.method.name.lowercase().replace('_', ' ')} ")
            append("(${(matchResult.score * 100).toInt()}% match). ")
            
            // Model info
            append("Model: ${cap.modelTier.value} (${cap.modelParamsB}B params). ")
            
            // Performance
            if (cap.hops == 0) {
                append("Local device (0 hops). ")
            } else {
                append("${cap.hops} hop${if (cap.hops > 1) "s" else ""} away. ")
            }
            append("Est. latency: ${cap.estimatedLatencyMs.toInt()}ms. ")
            
            // Features
            val features = mutableListOf<String>()
            if (cap.hasRag) features.add("RAG")
            if (cap.hasTools) features.add("tools")
            if (cap.hasVision) features.add("vision")
            if (features.isNotEmpty()) {
                append("Features: ${features.joinToString()}. ")
            }
            
            // Specializations
            if (cap.specializations.isNotEmpty()) {
                append("Specialized in: ${cap.specializations.take(3).joinToString()}. ")
            }
            
            // Cost
            if (cap.apiCostPer1kTokens == 0f) {
                append("Free (local). ")
            } else {
                append("Cost: $${String.format("%.4f", cap.apiCostPer1kTokens)}/1k tokens. ")
            }
            
            // Constraints applied
            if (constraints != null) {
                val applied = mutableListOf<String>()
                if (constraints.maxLatencyMs != null) applied.add("max latency ${constraints.maxLatencyMs.toInt()}ms")
                if (constraints.preferLocal) applied.add("prefer local")
                if (constraints.requireRag) applied.add("require RAG")
                if (constraints.modelSizeMin != null) applied.add("min size ${constraints.modelSizeMin.value}")
                if (applied.isNotEmpty()) {
                    append("Constraints: ${applied.joinToString()}. ")
                }
            }
        }
    }
    
    /**
     * Get all available capabilities.
     */
    fun getAllCapabilities(): List<CapabilityAnnouncement> {
        return gossipManager.getAllCapabilities()
    }
    
    /**
     * Get capability by ID.
     */
    fun getCapability(capabilityId: String): CapabilityAnnouncement? {
        return gossipManager.getCapability(capabilityId)
    }
    
    /**
     * Get routing statistics.
     */
    fun getStats(): Map<String, Any> {
        return gossipManager.getStats()
    }
}
