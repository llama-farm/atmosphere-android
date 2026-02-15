package com.llamafarm.atmosphere.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.core.AtmosphereNative
import com.llamafarm.atmosphere.network.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MeshDebugVM"

/**
 * ViewModel for the Mesh Debugger dashboard.
 * All data comes from THIS DEVICE's Rust core via JNI — not from any remote peer.
 * The Mac is just a peer. This phone has its own CRDT store, gradient table, gossip state.
 */
class MeshDebugViewModel(application: Application) : AndroidViewModel(application) {

    // Local mesh state from JNI
    private val _peers = MutableStateFlow<List<MeshPeerInfo>>(emptyList())
    val peers: StateFlow<List<MeshPeerInfo>> = _peers.asStateFlow()

    private val _capabilities = MutableStateFlow<List<MeshCapabilityInfo>>(emptyList())
    val capabilities: StateFlow<List<MeshCapabilityInfo>> = _capabilities.asStateFlow()

    private val _health = MutableStateFlow<MeshHealth?>(null)
    val health: StateFlow<MeshHealth?> = _health.asStateFlow()

    private val _gradientTable = MutableStateFlow<List<GradientTableEntry>>(emptyList())
    val gradientTable: StateFlow<List<GradientTableEntry>> = _gradientTable.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _stats = MutableStateFlow<MeshStats?>(null)
    val stats: StateFlow<MeshStats?> = _stats.asStateFlow()

    private val _deviceMetrics = MutableStateFlow<DeviceMetrics?>(null)
    val deviceMetrics: StateFlow<DeviceMetrics?> = _deviceMetrics.asStateFlow()

    private val _requests = MutableStateFlow<List<MeshRequestInfo>>(emptyList())
    val requests: StateFlow<List<MeshRequestInfo>> = _requests.asStateFlow()

    private val _transfers = MutableStateFlow<List<com.llamafarm.atmosphere.network.TransferInfo>>(emptyList())
    val transfers: StateFlow<List<com.llamafarm.atmosphere.network.TransferInfo>> = _transfers.asStateFlow()

    // Project state derived from local JNI/CRDT capability docs
    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    private val _projectsLoading = MutableStateFlow(false)
    val projectsLoading: StateFlow<Boolean> = _projectsLoading.asStateFlow()


    // Routing test state
    private val _routingHistory = MutableStateFlow<List<RoutingTestResult>>(emptyList())
    val routingHistory: StateFlow<List<RoutingTestResult>> = _routingHistory.asStateFlow()

    private val _isRoutingLoading = MutableStateFlow(false)
    val isRoutingLoading: StateFlow<Boolean> = _isRoutingLoading.asStateFlow()

    // Log filter
    private val _logFilter = MutableStateFlow("all")
    val logFilter: StateFlow<String> = _logFilter.asStateFlow()

    private val _logPaused = MutableStateFlow(false)
    val logPaused: StateFlow<Boolean> = _logPaused.asStateFlow()

    // API status (for settings screen compat)
    private val _apiStatus = MutableStateFlow("Local JNI")
    val apiStatus: StateFlow<String> = _apiStatus.asStateFlow()
    private val _apiBaseUrl = MutableStateFlow("jni://local")
    val apiBaseUrl: StateFlow<String> = _apiBaseUrl.asStateFlow()

    data class RoutingTestResult(
        val timestamp: Long = System.currentTimeMillis(),
        val query: String,
        val result: RoutingResult?,
        val error: String? = null
    )

    private var startTime = System.currentTimeMillis()

    init {
        addLog("info", "dashboard", "Mesh debugger started (local JNI mode)")
        startLocalPolling()
    }

    /**
     * Poll the local Rust core via JNI every 3 seconds.
     * Shows what THIS DEVICE knows — its CRDT store, discovered peers, synced capabilities.
     */
    private fun startLocalPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val handle = getHandle()
                    if (handle != null && handle != 0L) {
                        _isConnected.value = true
                        _apiStatus.value = "Local Rust Core"

                        // --- Peers ---
                        try {
                            val peersJson = AtmosphereNative.peers(handle)
                            _peers.value = parsePeers(peersJson)
                        } catch (e: Exception) {
                            Log.w(TAG, "peers() failed", e)
                        }

                        // --- Capabilities (synced via CRDT from other peers) ---
                        try {
                            val capsJson = AtmosphereNative.capabilities(handle)
                            val caps = parseCapabilities(capsJson)
                            _capabilities.value = caps
                            _projects.value = deriveProjectsFromCapabilities(caps)
                            // Build gradient table from capabilities
                            _gradientTable.value = caps.map { cap ->
                                GradientTableEntry(
                                    capability = cap.id,
                                    node = cap.nodeId,
                                    type = cap.type,
                                    model = cap.model,
                                    tier = cap.tier,
                                    load = cap.load,
                                    queueDepth = cap.queueDepth,
                                    avgInferenceMs = cap.avgInferenceMs,
                                    available = cap.available,
                                    score = cap.cost,
                                    cpuCores = cap.cpuCores,
                                    memoryGb = cap.memoryGb,
                                    gpuAvailable = cap.gpuAvailable,
                                    description = cap.description
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "capabilities() failed", e)
                        }

                        // --- Health ---
                        try {
                            val healthJson = AtmosphereNative.health(handle)
                            val svc = com.llamafarm.atmosphere.service.ServiceManager.getConnector().getService()
                            val serviceUptime = svc?.getServiceUptimeSeconds() ?: 0L
                            _health.value = parseMeshHealth(healthJson, serviceUptime)
                        } catch (e: Exception) {
                            Log.w(TAG, "health() failed", e)
                        }

                        // --- Stats ---
                        val svc = com.llamafarm.atmosphere.service.ServiceManager.getConnector().getService()
                        val serviceUptime = svc?.getServiceUptimeSeconds() ?: ((System.currentTimeMillis() - startTime) / 1000)
                        _stats.value = MeshStats(
                            uptimeSeconds = serviceUptime,
                            totalRequests = _requests.value.size,
                            avgLatencyMs = 0f,
                            errorCount = 0,
                            raw = JSONObject()
                        )

                        // --- Device Metrics ---
                        _deviceMetrics.value = collectLocalDeviceMetrics()

                        // --- Requests from CRDT ---
                        try {
                            val reqsData = AtmosphereNative.query(handle, "_requests")
                            _requests.value = parseRequests(reqsData)
                        } catch (_: Exception) {}

                        // --- Transfers from CRDT ---
                        try {
                            val transfersData = AtmosphereNative.query(handle, "_transfers")
                            _transfers.value = parseTransfers(transfersData)
                        } catch (_: Exception) {}

                    } else {
                        _isConnected.value = false
                        _apiStatus.value = "Rust core not started"
                    }
                } catch (e: Exception) {
                    _isConnected.value = false
                    _apiStatus.value = "Error: ${e.message}"
                    Log.e(TAG, "Poll failed", e)
                }
                delay(3000)
            }
        }
    }

    private fun getHandle(): Long? {
        return try {
            val service = com.llamafarm.atmosphere.service.ServiceManager.getConnector().getService()
            service?.getAtmosphereHandle()
        } catch (e: Exception) {
            null
        }
    }

    // --- Parsers (match the actual JSON from Rust JNI) ---

    private fun parsePeers(json: String): List<MeshPeerInfo> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                MeshPeerInfo(
                    peerId = obj.optString("peer_id", "unknown"),
                    name = obj.optString("name", obj.optString("peer_id", "").take(8)),
                    platform = obj.optString("type", "unknown"),
                    transport = obj.optString("transport", "lan"),
                    latencyMs = if (obj.has("latency_ms")) obj.optInt("latency_ms") else null,
                    lastSeen = obj.optLong("last_seen", System.currentTimeMillis()),
                    status = if (obj.optBoolean("connected", true)) "connected" else "disconnected",
                    metadata = emptyMap()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parsePeers failed: ${json.take(200)}", e)
            emptyList()
        }
    }

    private fun parseCapabilities(json: String): List<MeshCapabilityInfo> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                // CRDT docs use "llm_info" not "llm"
                val llm = obj.optJSONObject("llm_info") ?: obj.optJSONObject("llm")
                val costObj = obj.optJSONObject("cost")
                val deviceInfo = obj.optJSONObject("device_info")
                val capId = obj.optString("id", obj.optString("_id", "unknown"))
                MeshCapabilityInfo(
                    id = capId,
                    name = obj.optString("name", capId),
                    nodeId = obj.optString("peer_id", "local"),
                    nodeName = obj.optString("peer_name", obj.optString("peer_id", "").take(8)),
                    // CRDT uses "capability_type" not "type"
                    type = obj.optString("capability_type", obj.optString("type", "llm")),
                    // CRDT uses "model_name" not "model" inside llm_info
                    model = llm?.optString("model_name", llm?.optString("model", null)),
                    tier = llm?.optString("model_tier", llm?.optString("tier", null)),
                    paramsB = llm?.optString("params_b"),
                    hasRag = llm?.optBoolean("has_rag", false) == true ||
                             obj.optString("description", "").lowercase().let { 
                                 it.contains("rag") || it.contains("document retrieval") 
                             },
                    hasVision = llm?.optBoolean("supports_vision", false) ?: 
                                llm?.optBoolean("has_vision", false) ?: false,
                    hasTools = llm?.optBoolean("supports_tools", false) ?: 
                               llm?.optBoolean("has_tools", false) ?: false,
                    load = obj.optDouble("load", 0.0).toFloat(),
                    queueDepth = obj.optInt("queue_depth", 0),
                    avgInferenceMs = if (obj.has("avg_inference_ms")) obj.optDouble("avg_inference_ms").toFloat() else null,
                    available = run {
                        val statusVal = obj.opt("status")
                        when (statusVal) {
                            is org.json.JSONObject -> statusVal.optBoolean("available", true)
                            is String -> statusVal == "available"
                            else -> true
                        }
                    },
                    semanticTags = parseStringArray(obj.optJSONArray("keywords")),
                    cost = costObj?.optDouble("estimated_cost")?.toFloat() 
                           ?: if (obj.has("cost") && !obj.isNull("cost") && obj.optJSONObject("cost") == null) 
                              obj.optDouble("cost").toFloat() else null,
                    description = obj.optString("description", null),
                    cpuCores = deviceInfo?.optInt("cpu_cores"),
                    memoryGb = deviceInfo?.optDouble("memory_gb")?.toFloat(),
                    gpuAvailable = deviceInfo?.optBoolean("gpu_available", false) ?: false,
                    contextLength = llm?.optInt("context_length")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseCapabilities failed: ${json.take(200)}", e)
            emptyList()
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun parseMeshHealth(json: String, serviceUptime: Long = 0L): MeshHealth? {
        return try {
            val obj = JSONObject(json)
            // Use Build.MODEL as fallback if nodeName is empty
            val nodeName = obj.optString("name", "").let { name ->
                if (name.isEmpty()) "${Build.MANUFACTURER} ${Build.MODEL}" else name
            }
            // Use service uptime if JNI doesn't provide it
            val uptimeSeconds = obj.optLong("uptime_secs", 0).let { uptime ->
                if (uptime == 0L) serviceUptime else uptime
            }
            MeshHealth(
                status = obj.optString("status", "unknown"),
                peerId = obj.optString("peer_id", ""),
                nodeName = nodeName,
                version = obj.optString("version", "0.1.0"),
                meshPort = obj.optInt("mesh_port", 0),
                peerCount = obj.optInt("peer_count", 0),
                capabilityCount = obj.optInt("capability_count", 0),
                uptimeSeconds = uptimeSeconds,
                transports = mapOf("lan" to true),
                raw = obj
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseMeshHealth failed", e)
            null
        }
    }

    private fun deriveProjectsFromCapabilities(caps: List<MeshCapabilityInfo>): List<ProjectInfo> {
        return caps
            .groupBy { cap ->
                val raw = cap.id.substringBefore(':').ifBlank { "local" }
                if (raw.contains('/')) raw else "local/$raw"
            }
            .map { (projectPath, group) ->
                val namespace = projectPath.substringBefore('/')
                val projectId = projectPath.substringAfter('/', projectPath)
                val sample = group.first()
                ProjectInfo(
                    namespace = namespace,
                    projectId = projectId,
                    name = projectId,
                    model = sample.model,
                    modelProvider = null,
                    description = sample.description,
                    hasRag = group.any { it.hasRag },
                    hasTools = group.any { it.hasTools },
                    isDiscoverable = true
                )
            }
            .sortedBy { "${it.namespace}/${it.projectId}" }
    }

    private fun parseRequests(json: String): List<MeshRequestInfo> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                MeshRequestInfo(
                    requestId = obj.optString("_id", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    prompt = obj.optString("query", obj.optString("prompt", "")),
                    projectPath = obj.optString("project_path"),
                    status = obj.optString("status", "pending"),
                    inferenceMs = if (obj.has("inference_ms")) obj.optDouble("inference_ms").toFloat() else null,
                    targetNode = obj.optString("target_node")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTransfers(json: String): List<com.llamafarm.atmosphere.network.TransferInfo> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val progress = obj.optDouble("progress", 0.0).toFloat()
                com.llamafarm.atmosphere.network.TransferInfo(
                    id = obj.optString("_id", obj.optString("transfer_id", "")),
                    modelId = obj.optString("model_id", ""),
                    modelName = obj.optString("model_name", null),
                    fromPeer = obj.optString("from_peer", ""),
                    fromPeerName = obj.optString("from_peer_name", null),
                    toPeer = obj.optString("to_peer", ""),
                    toPeerName = obj.optString("to_peer_name", null),
                    status = obj.optString("status", "pending"),
                    progress = progress,
                    bytesTransferred = if (obj.has("bytes_transferred")) obj.optLong("bytes_transferred") else null,
                    totalBytes = if (obj.has("total_bytes")) obj.optLong("total_bytes") else null,
                    createdAt = obj.optLong("created_at", 0),
                    updatedAt = obj.optLong("updated_at", 0)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseTransfers failed", e)
            emptyList()
        }
    }

    // --- Device Metrics ---

    private fun collectLocalDeviceMetrics(): DeviceMetrics {
        val ctx = getApplication<Application>()
        val batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager?.isCharging

        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val ramGb = memInfo.totalMem / (1024f * 1024f * 1024f)

        val cpuCores = Runtime.getRuntime().availableProcessors()

        return DeviceMetrics(
            platform = "${Build.MANUFACTURER} ${Build.MODEL}",
            gpu = null,
            ramGb = ramGb,
            cpuCores = cpuCores,
            batteryLevel = batteryLevel,
            isCharging = isCharging
        )
    }

    // --- Log management ---

    private fun addLog(level: String, source: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            source = source,
            message = message
        )
        _logs.value = (listOf(entry) + _logs.value).take(500)
    }

    // --- Actions ---

    fun testRoute(query: String) {
        addLog("info", "routing", "Test route: $query")
        viewModelScope.launch {
            _isRoutingLoading.value = true
            try {
                val handle = getHandle()
                if (handle != null && handle != 0L) {
                    val requestId = "test-${System.currentTimeMillis()}"
                    val requestDoc = JSONObject().apply {
                        put("query", query)
                        put("status", "pending")
                        put("timestamp", System.currentTimeMillis())
                    }
                    AtmosphereNative.insert(handle, "_requests", requestId, requestDoc.toString())
                    addLog("info", "routing", "Request $requestId inserted into CRDT mesh")

                    // Poll for response
                    var attempts = 0
                    while (attempts < 15) {
                        delay(1000)
                        try {
                            val respJson = AtmosphereNative.get(handle, "_responses", requestId)
                            if (respJson.isNotEmpty() && respJson != "null" && respJson != "{}") {
                                val resp = JSONObject(respJson)
                                val result = RoutingResult(
                                    target = resp.optString("peer_id", ""),
                                    score = resp.optDouble("score", 0.0).toFloat(),
                                    breakdown = emptyMap(),
                                    raw = resp
                                )
                                _routingHistory.value = listOf(
                                    RoutingTestResult(query = query, result = result)
                                ) + _routingHistory.value.take(49)
                                addLog("info", "routing", "Response from ${result.target}")
                                _isRoutingLoading.value = false
                                return@launch
                            }
                        } catch (_: Exception) {}
                        attempts++
                    }
                    _routingHistory.value = listOf(
                        RoutingTestResult(query = query, result = null, error = "Timeout (15s)")
                    ) + _routingHistory.value.take(49)
                    addLog("warn", "routing", "Request timed out")
                }
            } catch (e: Exception) {
                _routingHistory.value = listOf(
                    RoutingTestResult(query = query, result = null, error = e.message)
                ) + _routingHistory.value.take(49)
            }
            _isRoutingLoading.value = false
        }
    }

    fun pingPeer(peerId: String, onResult: (Int?) -> Unit) {
        addLog("info", "ping", "Pinging $peerId...")
        onResult(null) // TODO: CRDT-based ping
    }

    fun setLogFilter(filter: String) { _logFilter.value = filter }
    fun toggleLogPause() { _logPaused.value = !_logPaused.value }
    fun clearLogs() { _logs.value = emptyList() }

    // Compat stub for settings screen
    fun setApiUrl(url: String) {
        addLog("info", "config", "Ignored — debugger uses local JNI, not HTTP")
    }

    /**
     * Initiate a model transfer from a mesh peer to this device.
     * Writes a transfer request to the _transfers CRDT collection.
     */
    fun initiateTransfer(modelId: String, fromPeer: String, modelName: String? = null) {
        viewModelScope.launch {
            try {
                val handle = getHandle()
                if (handle != null && handle != 0L) {
                    val localPeerId = _health.value?.peerId ?: "unknown"
                    val transferId = "xfer_${java.util.UUID.randomUUID()}"
                    val now = System.currentTimeMillis()
                    
                    val transferDoc = JSONObject().apply {
                        put("transfer_id", transferId)
                        put("model_id", modelId)
                        put("model_name", modelName ?: modelId)
                        put("from_peer", fromPeer)
                        put("to_peer", localPeerId)
                        put("status", "pending")
                        put("progress", 0.0)
                        put("created_at", now)
                        put("updated_at", now)
                    }
                    
                    AtmosphereNative.insert(handle, "_transfers", transferId, transferDoc.toString())
                    addLog("info", "transfers", "Initiated transfer: $modelName from $fromPeer")
                } else {
                    addLog("error", "transfers", "Cannot initiate transfer: Rust core not available")
                }
            } catch (e: Exception) {
                addLog("error", "transfers", "Failed to initiate transfer: ${e.message}")
                Log.e(TAG, "initiateTransfer failed", e)
            }
        }
    }

    /**
     * Cancel an active transfer by updating its status in the CRDT.
     */
    fun cancelTransfer(transferId: String) {
        viewModelScope.launch {
            try {
                val handle = getHandle()
                if (handle != null && handle != 0L) {
                    val transferDoc = JSONObject().apply {
                        put("status", "cancelled")
                        put("updated_at", System.currentTimeMillis())
                    }
                    
                    // Update the existing transfer document
                    AtmosphereNative.insert(handle, "_transfers", transferId, transferDoc.toString())
                    addLog("info", "transfers", "Cancelled transfer: $transferId")
                } else {
                    addLog("error", "transfers", "Cannot cancel transfer: Rust core not available")
                }
            } catch (e: Exception) {
                addLog("error", "transfers", "Failed to cancel transfer: ${e.message}")
                Log.e(TAG, "cancelTransfer failed", e)
            }
        }
    }

    // --- Project management (local JNI-derived projects) ---

    fun loadProjects() {
        viewModelScope.launch {
            _projectsLoading.value = true
            try {
                _projects.value = deriveProjectsFromCapabilities(_capabilities.value)
            } catch (e: Exception) {
                Log.e(TAG, "loadProjects failed", e)
            }
            _projectsLoading.value = false
        }
    }

    fun exposeProject(namespace: String, projectId: String) {
        // Local JNI mode: discovered CRDT projects are always visible.
        addLog("info", "projects", "Expose ignored in local JNI mode: $namespace/$projectId")
    }

    fun hideProject(projectId: String) {
        // Local JNI mode: hiding is not persisted yet; this screen is read-only.
        addLog("info", "projects", "Hide ignored in local JNI mode: $projectId")
    }

    override fun onCleared() {
        super.onCleared()
    }
}
