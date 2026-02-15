package com.llamafarm.atmosphere.horizon

import kotlinx.serialization.Serializable

/**
 * Data models for HORIZON app integration via Atmosphere mesh.
 */

@Serializable
data class MissionSummary(
    val callsign: String = "",
    val phase: String = "",
    val route: String = "",
    val connectivity: String = "",
    val anomalyCount: Int = 0,
    val pendingActions: Int = 0
)

@Serializable
data class Anomaly(
    val id: String,
    val title: String,
    val description: String,
    val severity: String,  // critical, warning, caution, info
    val category: String,
    val timestamp: String,
    val acknowledged: Boolean = false,
    val resolved: Boolean = false
)

@Serializable
data class AnomalySummary(
    val totalActive: Int = 0,
    val criticalCount: Int = 0,
    val warningCount: Int = 0,
    val bySeverity: Map<String, List<Anomaly>> = emptyMap()
)

@Serializable
data class AgentAction(
    val id: String,
    val sender: String,
    val channel: String,
    val content: String,
    val draftedResponse: String = "",
    val reasoning: String = "",
    val status: String,  // needs_input, approved, rejected
    val hilPriority: String = "medium",  // critical, high, medium, low
    val timestamp: String
)

@Serializable
data class AgentStatus(
    val monitoring: Boolean = false,
    val totalMessages: Int = 0,
    val needsInputCount: Int = 0,
    val hilCriticalCount: Int = 0
)

@Serializable
data class IntelBrief(
    val mission: String,
    val route: String,
    val generatedAt: String,
    val triggeredBy: String,
    val summary: String,
    val threats: List<String> = emptyList(),
    val weather: List<String> = emptyList(),
    val recommendations: List<String> = emptyList()
)

@Serializable
data class OsintItem(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val location: String? = null,
    val timestamp: String,
    val important: Boolean = false
)

@Serializable
data class FuelState(
    val onboardLbs: Int = 0,
    val burnRateLbsHr: Int = 0,
    val hoursRemaining: Float = 0f,
    val reserveMarginLbs: Int = 0,
    val status: String = "normal"  // critical, warning, normal
)

/**
 * App request/response wrappers for mesh communication.
 */
@Serializable
data class AppRequest(
    val type: String = "app_request",
    val requestId: String,
    val capabilityId: String,
    val endpoint: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class AppResponse(
    val type: String = "app_response",
    val requestId: String,
    val status: Int,
    val body: Map<String, Any>? = null
)

@Serializable
data class PushEvent(
    val type: String = "push_delivery",
    val event: String,
    val data: Map<String, Any>,
    val timestamp: String
)
