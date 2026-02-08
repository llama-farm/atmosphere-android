package com.llamafarm.atmosphere.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport types in priority order (lower = higher priority).
 */
enum class TransportType(val priority: Int) {
    LAN(1),          // Local network WebSocket - fastest
    WIFI_DIRECT(2),  // WiFi P2P - no router needed
    BLE_MESH(3),     // Bluetooth mesh - works offline
    MATTER(4),       // Smart home devices
    RELAY(5);        // Cloud relay - always works (fallback)
    
    companion object {
        fun fromString(s: String): TransportType? = values().find { 
            it.name.equals(s, ignoreCase = true) 
        }
    }
}

/**
 * Transport state.
 */
enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Configuration for each transport.
 */
data class TransportConfig(
    val lan: LanConfig = LanConfig(),
    val wifiDirect: WifiDirectConfig = WifiDirectConfig(),
    val bleMesh: BleMeshConfig = BleMeshConfig(),
    val matter: MatterConfig = MatterConfig(),
    val relay: RelayConfig = RelayConfig()
) {
    data class LanConfig(
        val enabled: Boolean = true,
        val port: Int = 11450,
        val mdns: Boolean = true
    )
    
    data class WifiDirectConfig(
        val enabled: Boolean = true,
        val autoAccept: Boolean = false
    )
    
    data class BleMeshConfig(
        val enabled: Boolean = true,
        val advertising: Boolean = true,
        val scanning: Boolean = true,
        val maxHops: Int = 3
    )
    
    data class MatterConfig(
        val enabled: Boolean = true,
        val autoCommission: Boolean = false
    )
    
    data class RelayConfig(
        val enabled: Boolean = true,
        val url: String = "wss://atmosphere-relay-production.up.railway.app",
        val fallbackUrls: List<String> = emptyList()
    )
    
    fun isEnabled(type: TransportType): Boolean = when (type) {
        TransportType.LAN -> lan.enabled
        TransportType.WIFI_DIRECT -> wifiDirect.enabled
        TransportType.BLE_MESH -> bleMesh.enabled
        TransportType.MATTER -> matter.enabled
        TransportType.RELAY -> relay.enabled
    }
    
    companion object {
        fun fromJson(json: JSONObject): TransportConfig {
            return TransportConfig(
                lan = json.optJSONObject("lan")?.let { 
                    LanConfig(
                        enabled = it.optBoolean("enabled", true),
                        port = it.optInt("port", 11450),
                        mdns = it.optBoolean("mdns", true)
                    )
                } ?: LanConfig(),
                wifiDirect = json.optJSONObject("wifi_direct")?.let {
                    WifiDirectConfig(
                        enabled = it.optBoolean("enabled", true),
                        autoAccept = it.optBoolean("auto_accept", false)
                    )
                } ?: WifiDirectConfig(),
                relay = json.optJSONObject("relay")?.let {
                    RelayConfig(
                        enabled = it.optBoolean("enabled", true),
                        url = it.optString("url", "wss://atmosphere-relay-production.up.railway.app"),
                        fallbackUrls = it.optJSONArray("fallback_urls")?.let { arr ->
                            (0 until arr.length()).map { i -> arr.getString(i) }
                        } ?: emptyList()
                    )
                } ?: RelayConfig()
            )
        }
    }
}

/**
 * Metrics for a transport connection.
 */
data class TransportMetrics(
    val samples: MutableList<Float> = mutableListOf(),
    var successes: Int = 0,
    var failures: Int = 0,
    var lastLatencyMs: Float = 0f,
    var lastUpdated: Long = System.currentTimeMillis()
) {
    val avgLatencyMs: Float
        get() = if (samples.isEmpty()) Float.MAX_VALUE else samples.takeLast(10).average().toFloat()
    
    val successRate: Float
        get() {
            val total = successes + failures
            return if (total == 0) 1f else successes.toFloat() / total
        }
    
    fun addSample(latencyMs: Float, success: Boolean) {
        samples.add(latencyMs)
        if (samples.size > 100) samples.removeAt(0)
        lastLatencyMs = latencyMs
        lastUpdated = System.currentTimeMillis()
        if (success) successes++ else failures++
    }
    
    fun score(): Float {
        val latencyScore = maxOf(0f, 100f - avgLatencyMs)
        val reliabilityScore = successRate * 100f
        return latencyScore * 0.6f + reliabilityScore * 0.4f
    }
}

/**
 * Abstract transport interface.
 */
interface Transport {
    val type: TransportType
    val connected: Boolean
    val metrics: TransportMetrics
    
    suspend fun connect(config: Any): Boolean
    suspend fun disconnect()
    suspend fun send(message: ByteArray): Boolean
    fun onMessage(handler: (ByteArray) -> Unit)
    
    suspend fun probe(): Float {
        val start = System.currentTimeMillis()
        return try {
            send("""{"type":"ping"}""".toByteArray())
            val latency = (System.currentTimeMillis() - start).toFloat()
            metrics.addSample(latency, true)
            latency
        } catch (e: Exception) {
            metrics.addSample(Float.MAX_VALUE, false)
            Float.MAX_VALUE
        }
    }
}

/**
 * Signed token for mesh authentication.
 */
data class MeshToken(
    val meshId: String,
    val nodeId: String?,
    val issuedAt: Long,
    val expiresAt: Long,
    val capabilities: List<String>,
    val issuerId: String,
    val nonce: String,
    val signature: String
) {
    fun isExpired(): Boolean = System.currentTimeMillis() / 1000 > expiresAt
    
    fun toJson(): JSONObject = JSONObject().apply {
        put("mesh_id", meshId)
        put("node_id", nodeId)
        put("issued_at", issuedAt)
        put("expires_at", expiresAt)
        put("capabilities", JSONArray(capabilities))
        put("issuer_id", issuerId)
        put("nonce", nonce)
        put("signature", signature)
    }
    
    companion object {
        fun fromJson(json: JSONObject): MeshToken {
            val capsArray = json.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = (0 until capsArray.length()).map { capsArray.getString(it) }
            
            return MeshToken(
                meshId = json.getString("mesh_id"),
                nodeId = json.optString("node_id", null),
                issuedAt = json.getLong("issued_at"),
                expiresAt = json.getLong("expires_at"),
                capabilities = capabilities,
                issuerId = json.getString("issuer_id"),
                nonce = json.getString("nonce"),
                signature = json.getString("signature")
            )
        }
    }
}
