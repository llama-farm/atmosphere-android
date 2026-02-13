package com.llamafarm.atmosphere.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "MeshApiClient"

// ============================================================================
// Data models for all dashboard API responses
// ============================================================================

data class MeshHealth(
    val status: String,
    val peerId: String,
    val nodeName: String,
    val version: String,
    val meshPort: Int,
    val peerCount: Int,
    val capabilityCount: Int,
    val uptimeSeconds: Long,
    val transports: Map<String, Boolean>,
    val raw: JSONObject
)

data class MeshPeerInfo(
    val peerId: String,
    val name: String,
    val platform: String,
    val transport: String,
    val latencyMs: Int?,
    val lastSeen: Long,
    val status: String,
    val metadata: Map<String, String>
)

data class MeshCapabilityInfo(
    val id: String,
    val name: String,
    val nodeId: String,
    val nodeName: String,
    val type: String,
    val model: String?,
    val tier: String?,
    val paramsB: String?,
    val hasRag: Boolean,
    val hasVision: Boolean,
    val hasTools: Boolean,
    val load: Float,
    val queueDepth: Int,
    val avgInferenceMs: Float?,
    val available: Boolean,
    val semanticTags: List<String>,
    val cost: Float?,
    val description: String? = null,
    val cpuCores: Int? = null,
    val memoryGb: Float? = null,
    val gpuAvailable: Boolean = false,
    val contextLength: Int? = null
)

data class GradientTableEntry(
    val capability: String,
    val node: String,
    val type: String,
    val model: String?,
    val tier: String?,
    val load: Float,
    val queueDepth: Int,
    val avgInferenceMs: Float?,
    val available: Boolean,
    val score: Float?,
    val deviceCost: Float? = null,
    val cpuCores: Int? = null,
    val memoryGb: Float? = null,
    val gpuAvailable: Boolean = false,
    val batteryLevel: Float? = null,
    val isPluggedIn: Boolean? = null,
    val description: String? = null
)

data class MeshRequestInfo(
    val requestId: String,
    val timestamp: Long,
    val prompt: String,
    val projectPath: String?,
    val status: String,
    val inferenceMs: Float?,
    val targetNode: String?
)

data class MeshStats(
    val uptimeSeconds: Long,
    val totalRequests: Int,
    val avgLatencyMs: Float,
    val errorCount: Int,
    val raw: JSONObject
)

data class DeviceMetrics(
    val platform: String,
    val gpu: String?,
    val ramGb: Float?,
    val cpuCores: Int?,
    val batteryLevel: Int?,
    val isCharging: Boolean?
)

data class RoutingResult(
    val target: String?,
    val score: Float?,
    val breakdown: Map<String, Float>,
    val raw: JSONObject
)

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val source: String,
    val message: String
)

/**
 * HTTP API client for the Atmosphere mesh dashboard.
 * Mirrors the JavaScript fetch calls in dashboard.html.
 */
class MeshApiClient(
    baseUrlInit: String = "http://localhost:11462"
) {
    private var baseUrl: String = baseUrlInit

    /**
     * Update the base URL for the mesh API.
     * On Android, this should point to the Mac/BigLlama peer's HTTP endpoint
     * since the phone's Rust core runs via JNI (no local HTTP server).
     */
    fun setBaseUrl(url: String) {
        Log.i(TAG, "Base URL updated: $url")
        baseUrl = url.trimEnd('/')
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // All state flows
    private val _health = MutableStateFlow<MeshHealth?>(null)
    val health: StateFlow<MeshHealth?> = _health.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeerInfo>>(emptyList())
    val peers: StateFlow<List<MeshPeerInfo>> = _peers.asStateFlow()

    private val _capabilities = MutableStateFlow<List<MeshCapabilityInfo>>(emptyList())
    val capabilities: StateFlow<List<MeshCapabilityInfo>> = _capabilities.asStateFlow()

    private val _gradientTable = MutableStateFlow<List<GradientTableEntry>>(emptyList())
    val gradientTable: StateFlow<List<GradientTableEntry>> = _gradientTable.asStateFlow()

    private val _requests = MutableStateFlow<List<MeshRequestInfo>>(emptyList())
    val requests: StateFlow<List<MeshRequestInfo>> = _requests.asStateFlow()

    private val _stats = MutableStateFlow<MeshStats?>(null)
    val stats: StateFlow<MeshStats?> = _stats.asStateFlow()

    private val _deviceMetrics = MutableStateFlow<DeviceMetrics?>(null)
    val deviceMetrics: StateFlow<DeviceMetrics?> = _deviceMetrics.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var pollingJob: Job? = null
    private var sseJob: Job? = null

    fun startPolling(intervalMs: Long = 3000) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                fetchAll()
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        sseJob?.cancel()
        pollingJob = null
        sseJob = null
    }

    private suspend fun fetchAll() {
        try {
            coroutineScope {
                val jobs = listOf(
                    async { fetchHealth() },
                    async { fetchPeers() },
                    async { fetchCapabilities() },
                    async { fetchGradientTable() },
                    async { fetchRequests() },
                    async { fetchStats() },
                    async { fetchDeviceMetrics() },
                )
                jobs.awaitAll()
                _isConnected.value = _health.value != null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fetch error: ${e.message}")
            _isConnected.value = false
        }
    }

    private fun getJson(path: String): JSONObject? {
        return try {
            val request = Request.Builder().url("$baseUrl$path").get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                response.close()
                JSONObject(body)
            } else {
                response.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchHealth() {
        val json = getJson("/health") ?: run { _health.value = null; return }
        val transportsJson = json.optJSONObject("transports") ?: JSONObject()
        val transports = mutableMapOf<String, Boolean>()
        transportsJson.keys().forEach { transports[it] = transportsJson.optBoolean(it, false) }

        _health.value = MeshHealth(
            status = json.optString("status", "unknown"),
            peerId = json.optString("peer_id", "unknown"),
            nodeName = json.optString("node_name", "Unknown"),
            version = json.optString("version", "?"),
            meshPort = json.optInt("mesh_port", 0),
            peerCount = json.optInt("peer_count", 0),
            capabilityCount = json.optInt("capability_count", 0),
            uptimeSeconds = json.optLong("uptime_seconds", json.optLong("peer_uptime_seconds", 0)),
            transports = transports,
            raw = json
        )
    }

    private fun fetchPeers() {
        val json = getJson("/api/peers") ?: return
        val arr = json.optJSONArray("peers") ?: return
        _peers.value = (0 until arr.length()).mapNotNull { i ->
            try {
                val p = arr.getJSONObject(i)
                val meta = mutableMapOf<String, String>()
                p.optJSONObject("metadata")?.let { m ->
                    m.keys().forEach { k -> meta[k] = m.optString(k, "") }
                }
                MeshPeerInfo(
                    peerId = p.getString("peer_id"),
                    name = p.optString("name", p.optString("node_name", "Unknown")),
                    platform = meta["platform"] ?: p.optString("platform", "unknown"),
                    transport = p.optString("transport", "tcp"),
                    latencyMs = p.optInt("latency_ms", -1).takeIf { it > 0 },
                    lastSeen = p.optLong("last_seen", 0),
                    status = p.optString("status", "connected"),
                    metadata = meta
                )
            } catch (e: Exception) { null }
        }
    }

    private fun fetchCapabilities() {
        val json = getJson("/api/capabilities") ?: return
        val arr = json.optJSONArray("capabilities") ?: return
        _capabilities.value = (0 until arr.length()).mapNotNull { i ->
            try {
                val c = arr.getJSONObject(i)
                val tags = mutableListOf<String>()
                c.optJSONArray("semantic_tags")?.let { t ->
                    for (j in 0 until t.length()) tags.add(t.getString(j))
                }
                MeshCapabilityInfo(
                    id = c.optString("_id", c.optString("id", "")),
                    name = c.optString("name", c.optString("capability", c.optString("label", "unknown"))),
                    nodeId = c.optString("peer_id", c.optString("node_id", "")),
                    nodeName = c.optString("node_name", c.optString("description", "")),
                    type = c.optString("type", "unknown"),
                    model = c.optString("model").takeIf { it.isNotEmpty() },
                    tier = c.optString("tier").takeIf { it.isNotEmpty() },
                    paramsB = c.optString("params_b").takeIf { it.isNotEmpty() },
                    hasRag = c.optBoolean("has_rag", false),
                    hasVision = c.optBoolean("has_vision", false),
                    hasTools = c.optBoolean("has_tools", false),
                    load = c.optDouble("load", 0.0).toFloat(),
                    queueDepth = c.optInt("queue_depth", 0),
                    avgInferenceMs = c.optDouble("avg_inference_ms", -1.0).toFloat().takeIf { it > 0 },
                    available = c.optBoolean("available", true),
                    semanticTags = tags,
                    cost = c.optDouble("cost", -1.0).toFloat().takeIf { it >= 0 }
                )
            } catch (e: Exception) { null }
        }
    }

    private fun fetchGradientTable() {
        val json = getJson("/api/gradient-table") ?: return
        val arr = json.optJSONArray("gradient_table") ?: return
        _gradientTable.value = (0 until arr.length()).mapNotNull { i ->
            try {
                val g = arr.getJSONObject(i)
                GradientTableEntry(
                    capability = g.optString("capability", g.optString("project", "unknown")),
                    node = g.optString("node", ""),
                    type = g.optString("type", "unknown"),
                    model = g.optString("model").takeIf { it.isNotEmpty() },
                    tier = g.optString("tier").takeIf { it.isNotEmpty() },
                    load = g.optDouble("load", 0.0).toFloat(),
                    queueDepth = g.optInt("queue_depth", 0),
                    avgInferenceMs = g.optDouble("avg_inference_ms", -1.0).toFloat().takeIf { it > 0 },
                    available = g.optBoolean("available", true),
                    score = g.optDouble("score", -1.0).toFloat().takeIf { it >= 0 }
                )
            } catch (e: Exception) { null }
        }
    }

    private fun fetchRequests() {
        val json = getJson("/api/requests") ?: return
        val arr = json.optJSONArray("requests") ?: return
        _requests.value = (0 until arr.length()).mapNotNull { i ->
            try {
                val r = arr.getJSONObject(i)
                MeshRequestInfo(
                    requestId = r.optString("request_id", r.optString("_id", "unknown")),
                    timestamp = r.optLong("timestamp", 0),
                    prompt = r.optString("prompt", ""),
                    projectPath = r.optString("project_path").takeIf { it.isNotEmpty() },
                    status = r.optString("status", "pending"),
                    inferenceMs = r.optDouble("inference_ms", -1.0).toFloat().takeIf { it > 0 },
                    targetNode = r.optString("target_node").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) { null }
        }
    }

    private fun fetchStats() {
        val json = getJson("/api/stats") ?: return
        _stats.value = MeshStats(
            uptimeSeconds = json.optLong("peer_uptime_seconds", json.optLong("uptime", 0)),
            totalRequests = json.optInt("total_requests", 0),
            avgLatencyMs = json.optDouble("avg_latency_ms", 0.0).toFloat(),
            errorCount = json.optInt("error_count", 0),
            raw = json
        )
    }

    private fun fetchDeviceMetrics() {
        val json = getJson("/api/device-metrics") ?: return
        _deviceMetrics.value = DeviceMetrics(
            platform = json.optString("platform", "unknown"),
            gpu = json.optString("gpu").takeIf { it.isNotEmpty() },
            ramGb = json.optDouble("ram_gb", -1.0).toFloat().takeIf { it > 0 },
            cpuCores = json.optInt("cpu_cores", -1).takeIf { it > 0 },
            batteryLevel = json.optInt("battery_level", -1).takeIf { it >= 0 },
            isCharging = if (json.has("is_charging")) json.optBoolean("is_charging") else null
        )
    }

    /**
     * Start SSE log stream.
     */
    fun startLogStream() {
        if (sseJob?.isActive == true) return
        sseJob = scope.launch {
            try {
                val sseClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS) // No timeout for SSE
                    .build()
                val request = Request.Builder().url("$baseUrl/api/logs/stream").get().build()
                val response = sseClient.newCall(request).execute()
                if (!response.isSuccessful) return@launch

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@launch))
                var line: String?
                while (isActive) {
                    line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        try {
                            val json = JSONObject(data)
                            val entry = LogEntry(
                                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                                level = json.optString("level", "info"),
                                source = json.optString("source", "mesh"),
                                message = json.optString("message", json.optString("msg", ""))
                            )
                            val current = _logs.value.toMutableList()
                            current.add(entry)
                            if (current.size > 500) current.removeAt(0)
                            _logs.value = current
                        } catch (_: Exception) {}
                    }
                }
                response.close()
            } catch (e: Exception) {
                Log.d(TAG, "SSE stream error: ${e.message}")
            }
        }
    }

    /**
     * Add a local log entry.
     */
    fun addLocalLog(level: String, source: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, source, message)
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > 500) current.removeAt(0)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Test routing for a query.
     */
    suspend fun testRouting(query: String): RoutingResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/route?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                .get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return@withContext null }
            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()
            val breakdown = mutableMapOf<String, Float>()
            json.optJSONObject("breakdown")?.let { b ->
                b.keys().forEach { k -> breakdown[k] = b.optDouble(k, 0.0).toFloat() }
            }
            RoutingResult(
                target = json.optString("target").takeIf { it.isNotEmpty() },
                score = json.optDouble("score", -1.0).toFloat().takeIf { it >= 0 },
                breakdown = breakdown,
                raw = json
            )
        } catch (e: Exception) { null }
    }

    /**
     * Ping a peer.
     */
    suspend fun pingPeer(peerId: String): Int? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("peer_id", peerId).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/test/ping").post(body).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()
            if (json.optBoolean("ok")) json.optInt("rtt_ms") else null
        } catch (e: Exception) { null }
    }

    /**
     * Execute test inference.
     */
    suspend fun testInference(query: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("query", query).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/test/inference").post(body).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()
            json
        } catch (e: Exception) { null }
    }

    fun close() {
        stopPolling()
        scope.cancel()
    }
}
