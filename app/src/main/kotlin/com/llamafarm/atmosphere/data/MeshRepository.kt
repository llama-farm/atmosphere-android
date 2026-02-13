package com.llamafarm.atmosphere.data

/**
 * Data classes for mesh state. Previously part of the HTTP-based MeshRepository.
 * All mesh data now comes from CRDT/JNI via MeshDebugViewModel.
 */

data class HttpMeshPeer(
    val nodeId: String,
    val nodeName: String,
    val capabilities: List<String>,
    val lastSeen: Long,
    val metadata: Map<String, String> = emptyMap()
)

data class HttpMeshCapability(
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

data class HttpMeshNodeInfo(
    val nodeId: String,
    val nodeName: String,
    val version: String,
    val uptime: Long,
    val peerCount: Int,
    val capabilityCount: Int
)
