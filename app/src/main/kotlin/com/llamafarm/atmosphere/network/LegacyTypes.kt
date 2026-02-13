package com.llamafarm.atmosphere.network

/**
 * LEGACY type definitions for backwards compatibility.
 * These types were part of the old relay transport layer which has been removed.
 * Kept as stubs to maintain compilation while UI is being updated.
 */

// Node information (BLE peer discovery)
data class NodeInfo(
    val nodeId: String = "",
    val name: String = "Unknown",
    val platform: String = "unknown",
    val rssi: Int? = null,
    val capabilities: List<String> = emptyList()
)

// Transport status
data class TransportStatus(
    val transport: String = "",
    val state: TransportState = TransportState.DISABLED,
    val peerCount: Int = 0,
    val latencyMs: Long? = null
) {
    enum class TransportState {
        DISABLED,
        CONNECTING,
        CONNECTED,
        ERROR,
        AVAILABLE,
        PROBING,
        FAILED
    }
}

enum class TransportType {
    RELAY,
    LAN,
    WIFI_DIRECT,
    BLE,
    BLE_MESH,
    MATTER
}

// Mesh message types (relay)
sealed class MeshMessage {
    data class Text(val content: String, val from: String) : MeshMessage()
}

// Routing info (relay)
data class RoutingInfo(
    val protocol: String = "crdt",
    val latencyMs: Long? = null
)

// Mesh endpoints (relay)
data class MeshEndpoints(
    val relay: String? = null,
    val lan: String? = null,
    val peer: String? = null,
    val local: String? = null,
    val public: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, String>): MeshEndpoints {
            return MeshEndpoints(
                relay = map["relay"],
                lan = map["lan"],
                peer = map["peer"],
                local = map["local"],
                public = map["public"]
            )
        }
        
        fun fromSingle(endpoint: String): MeshEndpoints {
            return MeshEndpoints(relay = endpoint)
        }
    }
}
