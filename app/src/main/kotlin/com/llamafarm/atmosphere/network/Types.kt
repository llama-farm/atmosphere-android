package com.llamafarm.atmosphere.network

import org.json.JSONObject

/**
 * Multi-path endpoints for mesh connectivity.
 */
data class MeshEndpoints(
    val local: String? = null,
    val public: String? = null,
    val relay: String? = null
) {
    fun toOrderedList(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        local?.let { result.add("local" to it) }
        public?.let { result.add("public" to it) }
        relay?.let { result.add("relay" to it) }
        return result
    }

    companion object {
        fun fromJson(json: JSONObject): MeshEndpoints {
            return MeshEndpoints(
                local = json.optString("local", null),
                public = json.optString("public", null),
                relay = json.optString("relay", null)
            )
        }

        fun fromSingle(endpoint: String): MeshEndpoints {
            return MeshEndpoints(local = endpoint)
        }
    }
}

/**
 * Represents a peer from the relay WebSocket.
 */
data class RelayPeer(
    val nodeId: String,
    val name: String,
    val capabilities: List<String> = emptyList(),
    val connected: Boolean = true
)

/**
 * Routing info from the semantic router.
 */
data class RoutingInfo(
    val complexity: String,
    val taskType: String,
    val modelSize: String,
    val domain: String?,
    val confidence: Float,
    val backend: String?
)

/**
 * Transport status for multi-transport connections.
 */
data class TransportStatus(
    val type: String,
    val connected: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null,
    val state: TransportState = if (connected) TransportState.CONNECTED else TransportState.DISCONNECTED
) {
    /**
     * Transport state for UI display.
     */
    enum class TransportState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AVAILABLE,
        PROBING,
        FAILED
    }
}

/**
 * Connection state for mesh connections.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Mesh message types for WebSocket communication.
 * These are flexible to accommodate different message patterns.
 */
sealed class MeshMessage {
    open val sourceNodeId: String? get() = null

    // Connection lifecycle
    data class Joined(val meshName: String?, override val sourceNodeId: String? = null) : MeshMessage()
    data class Left(val nodeId: String, override val sourceNodeId: String? = null) : MeshMessage()
    
    // Capability discovery
    data class CapabilityAnnounce(
        val nodeId: String,
        val announcement: JSONObject? = null,
        val capabilities: List<String>? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    // Peer management
    data class PeerList(val peers: List<RelayPeer>, override val sourceNodeId: String? = null) : MeshMessage()
    
    // Inference/LLM
    data class InferenceRequest(
        val requestId: String,
        val targetNodeId: String? = null,
        val payload: JSONObject? = null,
        val prompt: String? = null,
        val model: String? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    data class InferenceResponse(
        val requestId: String,
        val payload: JSONObject? = null,
        val response: String? = null,
        val error: String? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    data class LlmResponse(
        val response: String,
        val routing: RoutingInfo? = null,
        val error: String? = null,
        val requestId: String? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    data class ChatResponse(
        val response: String,
        val routing: RoutingInfo? = null,
        val error: String? = null,
        val requestId: String? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    // App platform
    data class AppResponse(
        val requestId: String,
        val status: Int = 200,
        val body: JSONObject = JSONObject(),
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    data class PushDelivery(
        val capabilityId: String,
        val eventType: String,
        val data: JSONObject = JSONObject(),
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    // Errors
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val code: String? = null,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
    
    // Unknown/raw
    data class Unknown(
        val type: String,
        val raw: String,
        override val sourceNodeId: String? = null
    ) : MeshMessage()
}
