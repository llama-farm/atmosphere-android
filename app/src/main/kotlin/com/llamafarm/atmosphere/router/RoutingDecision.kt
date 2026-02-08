package com.llamafarm.atmosphere.router

import com.llamafarm.atmosphere.core.CapabilityAnnouncement
import com.llamafarm.atmosphere.core.ModelTier

/**
 * Routing constraints for selecting capabilities.
 */
data class RouteConstraints(
    val maxLatencyMs: Float? = null,
    val preferLocal: Boolean = false,
    val requireRag: Boolean = false,
    val requireVision: Boolean = false,
    val requireTools: Boolean = false,
    val modelSizeMin: ModelTier? = null,
    val modelSizeMax: ModelTier? = null,
    val maxHops: Int? = null,
    val excludeNodeIds: List<String> = emptyList(),
    val preferNodeIds: List<String> = emptyList(),
    val maxCostPer1kTokens: Float? = null,
    val minTokensPerSecond: Float? = null
)

/**
 * Score breakdown for debugging routing decisions.
 */
data class ScoreBreakdown(
    val semanticScore: Float = 0f,       // 0.4 weight
    val latencyScore: Float = 0f,        // 0.25 weight
    val capabilityScore: Float = 0f,     // 0.2 weight (RAG, tools, specializations)
    val hopScore: Float = 0f,            // 0.1 weight
    val costScore: Float = 0f,           // 0.05 weight
    val compositeScore: Float = 0f       // Final weighted score
) {
    fun toExplanation(): String {
        return buildString {
            appendLine("Score Breakdown:")
            appendLine("  Semantic:    ${(semanticScore * 100).toInt()}% (weight 40%)")
            appendLine("  Latency:     ${(latencyScore * 100).toInt()}% (weight 25%)")
            appendLine("  Capability:  ${(capabilityScore * 100).toInt()}% (weight 20%)")
            appendLine("  Hop:         ${(hopScore * 100).toInt()}% (weight 10%)")
            appendLine("  Cost:        ${(costScore * 100).toInt()}% (weight 5%)")
            appendLine("  Composite:   ${(compositeScore * 100).toInt()}%")
        }
    }
}

/**
 * Final routing decision with explanation.
 */
data class RoutingDecision(
    val capability: CapabilityAnnouncement,
    val scoreBreakdown: ScoreBreakdown,
    val matchMethod: HashMatcher.MatchMethod,
    val explanation: String,
    val alternatives: List<Pair<CapabilityAnnouncement, Float>> = emptyList()
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("capability_id", capability.capabilityId)
            put("node_id", capability.nodeId)
            put("node_name", capability.nodeName)
            put("label", capability.label)
            put("model_tier", capability.modelTier.value)
            put("hops", capability.hops)
            put("estimated_latency_ms", capability.estimatedLatencyMs)
            put("composite_score", scoreBreakdown.compositeScore)
            put("match_method", matchMethod.name)
            put("explanation", explanation)
            
            put("score_breakdown", org.json.JSONObject().apply {
                put("semantic", scoreBreakdown.semanticScore)
                put("latency", scoreBreakdown.latencyScore)
                put("capability", scoreBreakdown.capabilityScore)
                put("hop", scoreBreakdown.hopScore)
                put("cost", scoreBreakdown.costScore)
            })
            
            if (alternatives.isNotEmpty()) {
                val altsArray = org.json.JSONArray()
                alternatives.take(3).forEach { (cap, score) ->
                    altsArray.put(org.json.JSONObject().apply {
                        put("capability_id", cap.capabilityId)
                        put("label", cap.label)
                        put("node_id", cap.nodeId)
                        put("score", score)
                    })
                }
                put("alternatives", altsArray)
            }
        }
    }
    
    fun toDetailedString(): String {
        return buildString {
            appendLine("=== Routing Decision ===")
            appendLine("Selected: ${capability.label} (${capability.capabilityId})")
            appendLine("Node: ${capability.nodeName} (${capability.nodeId})")
            appendLine("Model: ${capability.modelActual} (${capability.modelTier.value})")
            appendLine("Hops: ${capability.hops}")
            appendLine("Latency: ${capability.estimatedLatencyMs}ms")
            appendLine("Match Method: $matchMethod")
            appendLine()
            append(scoreBreakdown.toExplanation())
            appendLine()
            appendLine("Explanation: $explanation")
            
            if (alternatives.isNotEmpty()) {
                appendLine()
                appendLine("Alternatives:")
                alternatives.take(3).forEach { (cap, score) ->
                    appendLine("  - ${cap.label} (${cap.nodeId}): ${(score * 100).toInt()}%")
                }
            }
        }
    }
}
