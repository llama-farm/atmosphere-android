package com.llamafarm.atmosphere.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "DaemonRepository"

/**
 * Data classes for daemon responses
 */
data class DaemonPeer(
    val nodeId: String,
    val nodeName: String,
    val capabilities: List<String>,
    val lastSeen: Long,
    val metadata: Map<String, String> = emptyMap()
)

data class DaemonCapability(
    val id: String,
    val label: String,
    val nodeId: String,
    val nodeName: String,
    val metadata: Map<String, String> = emptyMap()
)

data class BigLlamaStatus(
    val mode: String,  // "cloud", "lan", "offline"
    val modelName: String?,
    val endpoint: String?,
    val isAvailable: Boolean,
    val lastCheck: Long
)

data class DaemonInfo(
    val nodeId: String,
    val nodeName: String,
    val version: String,
    val uptime: Long,
    val peerCount: Int,
    val capabilityCount: Int
)

/**
 * Repository for communicating with the atmosphere-core daemon via HTTP proxy.
 * The daemon runs on Mac and is accessible via adb reverse (localhost:11462).
 */
class DaemonRepository(
    private val baseUrl: String = "http://127.0.0.1:11462"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State flows
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _peers = MutableStateFlow<List<DaemonPeer>>(emptyList())
    val peers: StateFlow<List<DaemonPeer>> = _peers.asStateFlow()
    
    private val _capabilities = MutableStateFlow<List<DaemonCapability>>(emptyList())
    val capabilities: StateFlow<List<DaemonCapability>> = _capabilities.asStateFlow()
    
    private val _bigLlamaStatus = MutableStateFlow<BigLlamaStatus?>(null)
    val bigLlamaStatus: StateFlow<BigLlamaStatus?> = _bigLlamaStatus.asStateFlow()
    
    private val _daemonInfo = MutableStateFlow<DaemonInfo?>(null)
    val daemonInfo: StateFlow<DaemonInfo?> = _daemonInfo.asStateFlow()
    
    private var pollingJob: Job? = null
    
    /**
     * Start polling the daemon every [intervalMs] milliseconds.
     */
    fun startPolling(intervalMs: Long = 5000) {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Polling already active")
            return
        }
        
        Log.i(TAG, "Starting daemon polling (interval=${intervalMs}ms)")
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    fetchAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    _isConnected.value = false
                }
                delay(intervalMs)
            }
        }
    }
    
    /**
     * Stop polling.
     */
    fun stopPolling() {
        Log.i(TAG, "Stopping daemon polling")
        pollingJob?.cancel()
        pollingJob = null
        _isConnected.value = false
    }
    
    /**
     * Fetch all daemon state in parallel.
     */
    private suspend fun fetchAll() {
        coroutineScope {
            val healthDeferred = async { getHealth() }
            val peersDeferred = async { getPeers() }
            val capsDeferred = async { getCapabilities() }
            val bigLlamaDeferred = async { getBigLlamaStatus() }
            
            val health = healthDeferred.await()
            _isConnected.value = health
            
            if (health) {
                _peers.value = peersDeferred.await()
                _capabilities.value = capsDeferred.await()
                _bigLlamaStatus.value = bigLlamaDeferred.await()
            }
        }
    }
    
    /**
     * Check daemon health.
     */
    suspend fun getHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            
            if (success) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val nodeId = json.optString("node_id", "")
                val nodeName = json.optString("node_name", "")
                val uptime = json.optLong("uptime", 0)
                
                _daemonInfo.value = DaemonInfo(
                    nodeId = nodeId,
                    nodeName = nodeName,
                    version = json.optString("version", "unknown"),
                    uptime = uptime,
                    peerCount = json.optInt("peer_count", 0),
                    capabilityCount = json.optInt("capability_count", 0)
                )
                
                Log.d(TAG, "✅ Daemon health OK: $nodeName ($nodeId)")
            } else {
                Log.w(TAG, "❌ Daemon health check failed: ${response.code}")
            }
            
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Health check error: ${e.message}")
            false
        }
    }
    
    /**
     * Get peers from daemon.
     */
    suspend fun getPeers(): List<DaemonPeer> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/peers")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to get peers: ${response.code}")
                response.close()
                return@withContext emptyList()
            }
            
            val json = JSONObject(response.body?.string() ?: "{}")
            val peersArray = json.optJSONArray("peers") ?: JSONArray()
            
            val peers = (0 until peersArray.length()).map { i ->
                val peer = peersArray.getJSONObject(i)
                val capsArray = peer.optJSONArray("capabilities") ?: JSONArray()
                val capabilities = (0 until capsArray.length()).map { j ->
                    capsArray.getString(j)
                }
                
                DaemonPeer(
                    nodeId = peer.getString("node_id"),
                    nodeName = peer.getString("node_name"),
                    capabilities = capabilities,
                    lastSeen = peer.optLong("last_seen", System.currentTimeMillis())
                )
            }
            
            response.close()
            Log.d(TAG, "Fetched ${peers.size} peers from daemon")
            peers
        } catch (e: Exception) {
            Log.e(TAG, "Get peers error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get capabilities from daemon.
     */
    suspend fun getCapabilities(): List<DaemonCapability> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/capabilities")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to get capabilities: ${response.code}")
                response.close()
                return@withContext emptyList()
            }
            
            val json = JSONObject(response.body?.string() ?: "{}")
            val capsArray = json.optJSONArray("capabilities") ?: JSONArray()
            
            val capabilities = (0 until capsArray.length()).map { i ->
                val cap = capsArray.getJSONObject(i)
                
                DaemonCapability(
                    id = cap.optString("_id", cap.optString("id", "")),
                    label = cap.optString("name", cap.optString("label", "")),
                    nodeId = cap.optString("peer_id", cap.optString("node_id", "")),
                    nodeName = cap.optString("description", cap.optString("node_name", ""))
                )
            }
            
            response.close()
            Log.d(TAG, "Fetched ${capabilities.size} capabilities from daemon")
            capabilities
        } catch (e: Exception) {
            Log.e(TAG, "Get capabilities error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get BigLlama connection status from daemon.
     */
    suspend fun getBigLlamaStatus(): BigLlamaStatus? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/bigllama/status")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to get BigLlama status: ${response.code}")
                response.close()
                return@withContext null
            }
            
            val json = JSONObject(response.body?.string() ?: "{}")
            
            val status = BigLlamaStatus(
                mode = json.optString("mode", "offline"),
                modelName = json.optString("model_name").takeIf { it.isNotEmpty() },
                endpoint = json.optString("endpoint").takeIf { it.isNotEmpty() },
                isAvailable = json.optBoolean("is_available", false),
                lastCheck = json.optLong("last_check", System.currentTimeMillis())
            )
            
            response.close()
            Log.d(TAG, "BigLlama status: ${status.mode}")
            status
        } catch (e: Exception) {
            Log.e(TAG, "Get BigLlama status error: ${e.message}")
            null
        }
    }
    
    /**
     * Send a chat request to daemon for streaming inference.
     * Returns request ID for tracking the streaming response.
     */
    suspend fun sendChatRequest(
        messages: List<Map<String, String>>,
        model: String = "auto"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("messages", JSONArray(messages.map { JSONObject(it) }))
                put("model", model)
                put("stream", true)
            }
            
            val mediaType = "application/json".toMediaType()
            val body = requestBody.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Chat request failed: ${response.code}")
                response.close()
                return@withContext null
            }
            
            val json = JSONObject(response.body?.string() ?: "{}")
            val requestId = json.optString("request_id")
            
            response.close()
            Log.d(TAG, "Chat request sent: $requestId")
            requestId
        } catch (e: Exception) {
            Log.e(TAG, "Send chat request error: ${e.message}")
            null
        }
    }
    
    /**
     * Clean up resources.
     */
    fun close() {
        stopPolling()
        scope.cancel()
    }
}
