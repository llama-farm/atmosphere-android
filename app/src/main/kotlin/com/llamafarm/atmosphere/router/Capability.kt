package com.llamafarm.atmosphere.router

/**
 * Simple capability for local registration and routing.
 * Used by the older parts of SemanticRouter for local capability management.
 */
data class Capability(
    val id: String,
    val name: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val nodeId: String = "local",
    val cost: Float = 0f,
    val handler: String = "local",
    var embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Capability
        return id == other.id && nodeId == other.nodeId
    }

    override fun hashCode(): Int {
        return 31 * id.hashCode() + nodeId.hashCode()
    }
}

/**
 * Result of routing + invoking a capability.
 */
data class InvokeResult(
    val capabilityId: String?,
    val nodeId: String?,
    val response: String?,
    val error: String? = null
)

/**
 * Result of a routing decision (old format).
 */
data class RouteResult(
    val capability: Capability,
    val method: String,  // "exact", "keyword", "embedding", "fallback"
    val score: Float
)
