package com.llamafarm.atmosphere.viewmodel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data classes for JNI mesh state (source of truth for UI).
 * These replace HTTP-based MeshRepository state.
 */

/**
 * Peer information from AtmosphereNative.peers()
 */
data class JniPeer(
    val peerId: String,
    val name: String?,
    val ip: String?,
    val transport: String,  // "lan", "ble", "wifi_direct", "biglama"
    val latency: Int?,
    val state: String  // "connected", "disconnected"
) {
    companion object {
        fun fromJson(obj: JSONObject): JniPeer {
            return JniPeer(
                peerId = obj.getString("peer_id"),
                name = obj.optString("name").takeIf { it.isNotEmpty() },
                ip = obj.optString("ip").takeIf { it.isNotEmpty() },
                transport = obj.optString("transport", "lan"),
                latency = obj.optInt("latency_ms", -1).takeIf { it > 0 },
                state = obj.optString("state", "connected")
            )
        }
    }
}

/**
 * Capability information from AtmosphereNative.capabilities()
 */
data class JniCapability(
    val id: String,
    val name: String,
    val model: String?,
    val projectPath: String?,
    val peerId: String,
    val peerName: String?
) {
    companion object {
        fun fromJson(obj: JSONObject): JniCapability {
            return JniCapability(
                id = obj.optString("id", obj.optString("capability_id", "")),
                name = obj.optString("name", obj.optString("label", "unknown")),
                model = obj.optString("model").takeIf { it.isNotEmpty() },
                projectPath = obj.optString("project_path").takeIf { it.isNotEmpty() },
                peerId = obj.optString("peer_id", obj.optString("node_id", "")),
                peerName = obj.optString("peer_name", obj.optString("node_name", ""))
            )
        }
    }
}

/**
 * Health/status information from AtmosphereNative.health()
 */
data class JniHealth(
    val status: String,  // "running", "starting", "stopped"
    val peerId: String,
    val nodeName: String,
    val meshPort: Int,
    val peerCount: Int,
    val capabilityCount: Int,
    val transports: Map<String, Boolean>  // transport name -> enabled
) {
    companion object {
        fun fromJson(obj: JSONObject): JniHealth {
            val transportsJson = obj.optJSONObject("transports") ?: JSONObject()
            val transports = mutableMapOf<String, Boolean>()
            transportsJson.keys().forEach { key ->
                transports[key] = transportsJson.optBoolean(key, false)
            }
            
            return JniHealth(
                status = obj.optString("status", "stopped"),
                peerId = obj.optString("peer_id", "unknown"),
                nodeName = obj.optString("node_name", "Unknown Node"),
                meshPort = obj.optInt("mesh_port", 0),
                peerCount = obj.optInt("peer_count", 0),
                capabilityCount = obj.optInt("capability_count", 0),
                transports = transports
            )
        }
    }
}

/**
 * Helper to parse JNI JSON responses
 */
object JniParser {
    fun parsePeers(json: String): List<JniPeer> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    JniPeer.fromJson(array.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun parseCapabilities(json: String): List<JniCapability> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    JniCapability.fromJson(array.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun parseHealth(json: String): JniHealth? {
        return try {
            JniHealth.fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }
}
