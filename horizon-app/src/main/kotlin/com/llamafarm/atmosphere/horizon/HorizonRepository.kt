package com.llamafarm.atmosphere.horizon

import android.util.Log
import com.llamafarm.atmosphere.sdk.AtmosphereClient
import org.json.JSONObject

private const val TAG = "HorizonRepo"

/**
 * Data layer that wraps mesh calls to the HORIZON backend via AtmosphereClient SDK.
 * All requests route through the Atmosphere mesh via callTool.
 */
class HorizonRepository(
    private val client: AtmosphereClient
) {
    // Cache last-known state for offline resilience
    var cachedMission: MissionSummary? = null; private set
    var cachedAnomalies: List<Anomaly>? = null; private set
    var cachedTranscripts: List<VoiceTranscript>? = null; private set
    var cachedHilItems: List<HilItem>? = null; private set
    var cachedBrief: IntelBrief? = null; private set
    var lastUpdateMs: Long = 0L; private set

    private suspend fun callTool(toolName: String, params: Map<String, Any> = emptyMap()): JSONObject {
        val response = client.callTool("horizon", toolName, params)
        // The mesh returns a tool_response envelope: {type, request_id, status, body, tool}
        // Unwrap to just the body (the actual API data)
        return response.optJSONObject("body") ?: response
    }

    private fun checkError(j: JSONObject) {
        if (j.optBoolean("error", false)) {
            throw RuntimeException(j.optString("message", "Request failed"))
        }
        val status = j.optInt("status", 200)
        if (status >= 400) {
            throw RuntimeException(j.optString("error", "Request failed ($status)"))
        }
    }

    // ── Mission ──────────────────────────────────────────────────────────────

    suspend fun getMissionSummary(): Result<MissionSummary> = runCatching {
        val r = callTool("get_mission_summary")
        checkError(r)
        MissionSummary.fromJson(r).also {
            cachedMission = it
            lastUpdateMs = System.currentTimeMillis()
        }
    }

    // ── Anomalies ────────────────────────────────────────────────────────────

    suspend fun getActiveAnomalies(): Result<List<Anomaly>> = runCatching {
        val r = callTool("get_active_anomalies")
        checkError(r)
        // Server returns by_severity.{critical,warning,caution}[] — flatten into one list
        val bySeverity = r.optJSONObject("by_severity")
        val arr = if (bySeverity != null) {
            val flat = org.json.JSONArray()
            for (severity in listOf("critical", "warning", "caution", "info")) {
                val items = bySeverity.optJSONArray(severity) ?: continue
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { item ->
                        if (!item.has("severity")) item.put("severity", severity)
                        flat.put(item)
                    }
                }
            }
            flat
        } else {
            r.optJSONArray("anomalies") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        }
        Anomaly.listFromJson(arr).also { cachedAnomalies = it }
    }

    suspend fun runScan(): Result<JSONObject> = runCatching {
        val r = callTool("run_anomaly_scan")
        checkError(r); r
    }

    suspend fun acknowledgeAnomaly(id: String): Result<JSONObject> = runCatching {
        val r = callTool("acknowledge_anomaly", mapOf("id" to id))
        checkError(r); r
    }

    suspend fun resolveAnomaly(id: String): Result<JSONObject> = runCatching {
        val r = callTool("resolve_anomaly", mapOf("id" to id))
        checkError(r); r
    }

    // ── Knowledge ────────────────────────────────────────────────────────────

    suspend fun queryKnowledge(query: String): Result<KnowledgeResult> = runCatching {
        val r = callTool("query_knowledge", mapOf("question" to query))
        checkError(r)
        KnowledgeResult.fromJson(r)
    }

    suspend fun getSuggestions(): Result<List<String>> = runCatching {
        val r = callTool("get_suggestions")
        checkError(r)
        val arr = r.optJSONArray("suggestions") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.optString(it) }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    suspend fun getTranscripts(minutes: Int = 60): Result<List<VoiceTranscript>> = runCatching {
        val r = callTool("get_transcripts", mapOf("minutes" to minutes))
        checkError(r)
        val arr = r.optJSONArray("transcripts") ?: org.json.JSONArray()
        VoiceTranscript.listFromJson(arr).also { cachedTranscripts = it }
    }

    suspend fun startVoiceMonitoring(): Result<JSONObject> = runCatching {
        val r = callTool("start_voice_monitoring")
        checkError(r); r
    }

    suspend fun stopVoiceMonitoring(): Result<JSONObject> = runCatching {
        val r = callTool("stop_voice_monitoring")
        checkError(r); r
    }

    // ── Agent ────────────────────────────────────────────────────────────────

    suspend fun getHilItems(): Result<List<HilItem>> = runCatching {
        val r = callTool("get_needs_input")
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        HilItem.listFromJson(arr).also { cachedHilItems = it }
    }

    suspend fun getHandledItems(): Result<List<HandledItem>> = runCatching {
        val r = callTool("get_handled")
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        HandledItem.listFromJson(arr)
    }

    suspend fun approveAction(id: String): Result<JSONObject> = runCatching {
        val r = callTool("approve_action", mapOf("id" to id))
        checkError(r); r
    }

    suspend fun rejectAction(id: String): Result<JSONObject> = runCatching {
        val r = callTool("reject_action", mapOf("id" to id))
        checkError(r); r
    }

    // ── OSINT ────────────────────────────────────────────────────────────────

    suspend fun searchIntel(query: String = "", category: String = ""): Result<List<IntelItem>> = runCatching {
        val params = mutableMapOf<String, Any>()
        if (query.isNotBlank()) params["query"] = query
        if (category.isNotBlank()) params["category"] = category
        val r = callTool("search_intel", params)
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        IntelItem.listFromJson(arr)
    }

    suspend fun generateBrief(): Result<IntelBrief> = runCatching {
        val r = callTool("generate_brief")
        checkError(r)
        IntelBrief.fromJson(r).also { cachedBrief = it }
    }

    suspend fun getLatestBrief(): Result<IntelBrief> = runCatching {
        val r = callTool("get_latest_brief")
        checkError(r)
        val briefObj = r.optJSONObject("brief") ?: r
        IntelBrief.fromJson(briefObj).also { cachedBrief = it }
    }
}
