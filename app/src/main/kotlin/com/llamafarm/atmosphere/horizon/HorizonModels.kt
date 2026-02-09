package com.llamafarm.atmosphere.horizon

import org.json.JSONArray
import org.json.JSONObject

// ── Mission ──────────────────────────────────────────────────────────────────

data class MissionSummary(
    val callsign: String = "REACH 421",
    val aircraft: String = "C-17A",
    val route: String = "KDOV → OKBK",
    val phase: String = "EN ROUTE",
    val position: String = "—",
    val altitude: String = "—",
    val fuel: String = "—",
    val cargo: String = "—",
    val pax: String = "—",
    val groundSpeed: String = "—"
) {
    companion object {
        fun fromJson(j: JSONObject): MissionSummary = MissionSummary(
            callsign = j.optString("callsign", "REACH 421"),
            aircraft = j.optString("aircraft", "C-17A"),
            route = j.optString("route", "KDOV → OKBK"),
            phase = j.optString("phase", "EN ROUTE"),
            position = j.optString("position", "—"),
            altitude = j.optString("altitude", "—"),
            fuel = j.optString("fuel", "—"),
            cargo = j.optString("cargo", "—"),
            pax = j.optString("pax", "—"),
            groundSpeed = j.optString("ground_speed", "—")
        )
    }
}

// ── Anomaly ──────────────────────────────────────────────────────────────────

enum class AnomalySeverity { CRITICAL, WARNING, CAUTION, INFO }

data class Anomaly(
    val id: String,
    val title: String,
    val severity: AnomalySeverity,
    val description: String,
    val aiAnalysis: String = "",
    val recommendedAction: String = "",
    val timestamp: Long = 0,
    val acknowledged: Boolean = false,
    val resolved: Boolean = false
) {
    companion object {
        fun fromJson(j: JSONObject): Anomaly = Anomaly(
            id = j.optString("id", ""),
            title = j.optString("title", "Unknown"),
            severity = when (j.optString("severity", "info").lowercase()) {
                "critical" -> AnomalySeverity.CRITICAL
                "warning" -> AnomalySeverity.WARNING
                "caution" -> AnomalySeverity.CAUTION
                else -> AnomalySeverity.INFO
            },
            description = j.optString("description", ""),
            aiAnalysis = j.optString("ai_analysis", ""),
            recommendedAction = j.optString("recommended_action", ""),
            timestamp = j.optLong("timestamp", 0),
            acknowledged = j.optBoolean("acknowledged", false),
            resolved = j.optBoolean("resolved", false)
        )

        fun listFromJson(arr: JSONArray): List<Anomaly> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }
}

// ── Knowledge ────────────────────────────────────────────────────────────────

data class KnowledgeResult(
    val answer: String,
    val confidence: Float,
    val sources: List<String>
) {
    companion object {
        fun fromJson(j: JSONObject): KnowledgeResult {
            val srcArr = j.optJSONArray("sources") ?: JSONArray()
            return KnowledgeResult(
                answer = j.optString("answer", ""),
                confidence = j.optDouble("confidence", 0.0).toFloat(),
                sources = (0 until srcArr.length()).map { srcArr.optString(it, "") }
            )
        }
    }
}

// ── Voice ────────────────────────────────────────────────────────────────────

data class VoiceTranscript(
    val id: String,
    val channel: String,
    val speaker: String,
    val text: String,
    val timestamp: Long,
    val priority: String = "normal",
    val keywords: List<String> = emptyList()
) {
    companion object {
        fun fromJson(j: JSONObject): VoiceTranscript {
            val kwArr = j.optJSONArray("keywords") ?: JSONArray()
            return VoiceTranscript(
                id = j.optString("id", ""),
                channel = j.optString("channel", ""),
                speaker = j.optString("speaker", ""),
                text = j.optString("text", ""),
                timestamp = j.optLong("timestamp", 0),
                priority = j.optString("priority", "normal"),
                keywords = (0 until kwArr.length()).map { kwArr.optString(it) }
            )
        }

        fun listFromJson(arr: JSONArray): List<VoiceTranscript> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }
}

// ── Agent / HIL ──────────────────────────────────────────────────────────────

data class HilItem(
    val id: String,
    val title: String,
    val description: String,
    val source: String = "",
    val urgency: String = "normal",
    val proposedAction: String = "",
    val timestamp: Long = 0
) {
    companion object {
        fun fromJson(j: JSONObject): HilItem = HilItem(
            id = j.optString("id", ""),
            title = j.optString("title", ""),
            description = j.optString("description", ""),
            source = j.optString("source", ""),
            urgency = j.optString("urgency", "normal"),
            proposedAction = j.optString("proposed_action", ""),
            timestamp = j.optLong("timestamp", 0)
        )

        fun listFromJson(arr: JSONArray): List<HilItem> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }
}

data class HandledItem(
    val id: String,
    val title: String,
    val action: String,
    val timestamp: Long = 0
) {
    companion object {
        fun fromJson(j: JSONObject): HandledItem = HandledItem(
            id = j.optString("id", ""),
            title = j.optString("title", ""),
            action = j.optString("action", ""),
            timestamp = j.optLong("timestamp", 0)
        )

        fun listFromJson(arr: JSONArray): List<HandledItem> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }
}

// ── OSINT ────────────────────────────────────────────────────────────────────

data class IntelBrief(
    val id: String = "",
    val threatAssessment: String = "",
    val weather: String = "",
    val notams: String = "",
    val recommendations: String = "",
    val generatedAt: Long = 0
) {
    companion object {
        fun fromJson(j: JSONObject): IntelBrief = IntelBrief(
            id = j.optString("id", ""),
            threatAssessment = j.optString("threat_assessment", ""),
            weather = j.optString("weather", ""),
            notams = j.optString("notams", ""),
            recommendations = j.optString("recommendations", ""),
            generatedAt = j.optLong("generated_at", 0)
        )
    }
}

data class IntelItem(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val source: String = "",
    val timestamp: Long = 0
) {
    companion object {
        fun fromJson(j: JSONObject): IntelItem = IntelItem(
            id = j.optString("id", ""),
            title = j.optString("title", ""),
            category = j.optString("category", ""),
            summary = j.optString("summary", ""),
            source = j.optString("source", ""),
            timestamp = j.optLong("timestamp", 0)
        )

        fun listFromJson(arr: JSONArray): List<IntelItem> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
    }
}

// ── Connectivity ─────────────────────────────────────────────────────────────

enum class HorizonConnectivity { CONNECTED, DEGRADED, DENIED, OFFLINE }
