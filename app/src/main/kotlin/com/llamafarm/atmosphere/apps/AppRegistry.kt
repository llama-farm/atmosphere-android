package com.llamafarm.atmosphere.apps

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AppRegistry"

/**
 * Registry for discovered mesh app capabilities.
 * Tracks app capabilities from gossip announcements and push events.
 */
class AppRegistry {
    
    private val capabilities = ConcurrentHashMap<String, AppCapability>()
    
    private val _appCapabilities = MutableStateFlow<List<AppCapability>>(emptyList())
    val appCapabilities: StateFlow<List<AppCapability>> = _appCapabilities.asStateFlow()
    
    private val _pushEvents = MutableStateFlow<List<PushEvent>>(emptyList())
    val pushEvents: StateFlow<List<PushEvent>> = _pushEvents.asStateFlow()

    /**
     * Process a gossip capability_announce and extract any app capabilities.
     * Call this from the gossip handler when announcements arrive.
     */
    fun handleAnnouncement(nodeId: String, announcement: JSONObject) {
        val nodeName = announcement.optString("node_name", nodeId)
        val capsArray = announcement.optJSONArray("capabilities") ?: return

        var added = 0
        for (i in 0 until capsArray.length()) {
            val capJson = capsArray.optJSONObject(i) ?: continue
            val appCap = AppCapability.fromCapabilityJson(capJson, nodeId, nodeName) ?: continue
            
            capabilities[appCap.id] = appCap
            added++
            Log.i(TAG, "📱 Registered app capability: ${appCap.id} (${appCap.type}) from $nodeName")
        }

        if (added > 0) {
            refreshFlow()
            Log.i(TAG, "📱 Total app capabilities: ${capabilities.size}")
        }
    }

    /**
     * Handle a push_delivery message from the mesh.
     */
    fun handlePushDelivery(json: JSONObject) {
        val event = PushEvent(
            capabilityId = json.optString("capability_id", ""),
            eventType = json.optString("event_type", ""),
            data = json.optJSONObject("data") ?: JSONObject(),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
        
        val current = _pushEvents.value.toMutableList()
        current.add(0, event)
        if (current.size > 100) _pushEvents.value = current.take(100)
        else _pushEvents.value = current
        
        Log.i(TAG, "📨 Push event: ${event.eventType} from ${event.capabilityId}")
    }

    fun getAppCapabilities(): List<AppCapability> = capabilities.values
        .filter { !it.isExpired() }
        .toList()

    fun findByKeyword(query: String): List<AppCapability> {
        val q = query.lowercase()
        return getAppCapabilities().filter { cap ->
            cap.keywords.any { it.lowercase().contains(q) } ||
            cap.description.lowercase().contains(q) ||
            cap.appName.lowercase().contains(q) ||
            cap.id.lowercase().contains(q)
        }
    }

    fun getEndpoints(capabilityId: String): Map<String, AppEndpoint> =
        capabilities[capabilityId]?.endpoints ?: emptyMap()

    fun getByApp(appName: String): List<AppCapability> =
        getAppCapabilities().filter { it.appName.equals(appName, ignoreCase = true) }

    fun cleanupExpired() {
        val removed = capabilities.entries.removeAll { it.value.isExpired() }
        if (removed) refreshFlow()
    }

    private fun refreshFlow() {
        _appCapabilities.value = getAppCapabilities()
    }

    companion object {
        @Volatile
        private var instance: AppRegistry? = null
        
        fun getInstance(): AppRegistry =
            instance ?: synchronized(this) {
                instance ?: AppRegistry().also { instance = it }
            }
    }
}

/**
 * A real-time push event delivered from a mesh app.
 */
data class PushEvent(
    val capabilityId: String,
    val eventType: String,
    val data: JSONObject,
    val timestamp: Long = System.currentTimeMillis()
)
