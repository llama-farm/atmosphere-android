package com.llamafarm.atmosphere.horizon

import android.util.Log
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "HorizonRepo"

/**
 * Data layer that wraps mesh calls to the HORIZON backend.
 * All requests route through the Atmosphere mesh via sendAppRequest.
 */
class HorizonRepository(
    private val viewModel: AtmosphereViewModel
) {
    // Cache last-known state for offline resilience
    var cachedMission: MissionSummary? = null; private set
    var cachedAnomalies: List<Anomaly>? = null; private set
    var cachedTranscripts: List<VoiceTranscript>? = null; private set
    var cachedHilItems: List<HilItem>? = null; private set
    var cachedBrief: IntelBrief? = null; private set
    var lastUpdateMs: Long = 0L; private set

    private val horizonCapId: String
        get() {
            // Find horizon capability from app registry
            val caps = com.llamafarm.atmosphere.apps.AppRegistry.getInstance()
                .getByApp("horizon")
            return caps.firstOrNull()?.id ?: "horizon"
        }

    private suspend fun request(endpoint: String, params: JSONObject = JSONObject()): JSONObject =
        suspendCancellableCoroutine { cont ->
            viewModel.sendAppRequest(horizonCapId, endpoint, params) { response ->
                if (cont.isActive) cont.resume(response)
            }
        }

    // ── Mission ──────────────────────────────────────────────────────────────

    suspend fun getMissionSummary(): Result<MissionSummary> = runCatching {
        val r = request("/api/mission/summary")
        checkError(r)
        MissionSummary.fromJson(r).also {
            cachedMission = it
            lastUpdateMs = System.currentTimeMillis()
        }
    }

    // ── Anomalies ────────────────────────────────────────────────────────────

    suspend fun getActiveAnomalies(): Result<List<Anomaly>> = runCatching {
        val r = request("/api/anomaly/active")
        checkError(r)
        val arr = r.optJSONArray("anomalies") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        Anomaly.listFromJson(arr).also { cachedAnomalies = it }
    }

    suspend fun runScan(): Result<JSONObject> = runCatching {
        val r = request("/api/anomaly/scan", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    suspend fun acknowledgeAnomaly(id: String): Result<JSONObject> = runCatching {
        val r = request("/api/anomaly/$id/acknowledge", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    suspend fun resolveAnomaly(id: String): Result<JSONObject> = runCatching {
        val r = request("/api/anomaly/$id/resolve", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    // ── Knowledge ────────────────────────────────────────────────────────────

    suspend fun queryKnowledge(query: String): Result<KnowledgeResult> = runCatching {
        val r = request("/api/knowledge/query", JSONObject().put("query", query).put("_method", "POST"))
        checkError(r)
        KnowledgeResult.fromJson(r)
    }

    suspend fun getSuggestions(): Result<List<String>> = runCatching {
        val r = request("/api/knowledge/suggestions")
        checkError(r)
        val arr = r.optJSONArray("suggestions") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.optString(it) }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    suspend fun getTranscripts(minutes: Int = 60): Result<List<VoiceTranscript>> = runCatching {
        val r = request("/api/voice/transcripts", JSONObject().put("minutes", minutes))
        checkError(r)
        val arr = r.optJSONArray("transcripts") ?: org.json.JSONArray()
        VoiceTranscript.listFromJson(arr).also { cachedTranscripts = it }
    }

    suspend fun startVoiceMonitoring(): Result<JSONObject> = runCatching {
        val r = request("/api/voice/start", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    suspend fun stopVoiceMonitoring(): Result<JSONObject> = runCatching {
        val r = request("/api/voice/stop", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    // ── Agent ────────────────────────────────────────────────────────────────

    suspend fun getHilItems(): Result<List<HilItem>> = runCatching {
        val r = request("/api/agent/needs-input")
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        HilItem.listFromJson(arr).also { cachedHilItems = it }
    }

    suspend fun getHandledItems(): Result<List<HandledItem>> = runCatching {
        val r = request("/api/agent/handled")
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        HandledItem.listFromJson(arr)
    }

    suspend fun approveAction(id: String): Result<JSONObject> = runCatching {
        val r = request("/api/agent/approve/$id", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    suspend fun rejectAction(id: String): Result<JSONObject> = runCatching {
        val r = request("/api/agent/reject/$id", JSONObject().put("_method", "POST"))
        checkError(r); r
    }

    // ── OSINT ────────────────────────────────────────────────────────────────

    suspend fun searchIntel(query: String = "", category: String = ""): Result<List<IntelItem>> = runCatching {
        val p = JSONObject()
        if (query.isNotBlank()) p.put("query", query)
        if (category.isNotBlank()) p.put("category", category)
        val r = request("/api/osint/search", p)
        checkError(r)
        val arr = r.optJSONArray("items") ?: r.optJSONArray("data") ?: org.json.JSONArray()
        IntelItem.listFromJson(arr)
    }

    suspend fun generateBrief(): Result<IntelBrief> = runCatching {
        val r = request("/api/osint/brief/generate", JSONObject().put("_method", "POST"))
        checkError(r)
        IntelBrief.fromJson(r).also { cachedBrief = it }
    }

    suspend fun getLatestBrief(): Result<IntelBrief> = runCatching {
        val r = request("/api/osint/brief/latest")
        checkError(r)
        IntelBrief.fromJson(r).also { cachedBrief = it }
    }

    private fun checkError(j: JSONObject) {
        val status = j.optInt("status", 200)
        if (status >= 400) {
            throw RuntimeException(j.optString("error", "Request failed ($status)"))
        }
    }
}
