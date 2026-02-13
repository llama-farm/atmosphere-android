// Atmosphere Android - Kotlin JNI Bindings
// Auto-generated from atmosphere.udl - DO NOT EDIT MANUALLY
// Generated: 2026-02-03
// Updated: 2026-02-04 - Added networking functions

package com.llamafarm.atmosphere.bindings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Error types that can occur in Atmosphere operations
 */
sealed class AtmosphereException(message: String) : Exception(message) {
    class NetworkError(message: String = "Network error") : AtmosphereException(message)
    class ConfigError(message: String = "Configuration error") : AtmosphereException(message)
    class CapabilityNotFound(message: String = "Capability not found") : AtmosphereException(message)
    class NodeNotRunning(message: String = "Node not running") : AtmosphereException(message)
    class SerializationError(message: String = "Serialization error") : AtmosphereException(message)
}

/**
 * Represents a peer in the mesh network.
 */
data class MeshPeer(
    val nodeId: String,
    val name: String,
    val address: String,
    val connected: Boolean,
    val latencyMs: Int?,
    val capabilities: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): MeshPeer {
            return MeshPeer(
                nodeId = json.optString("node_id", "unknown"),
                name = json.optString("name", "Unknown Node"),
                address = json.optString("address", ""),
                connected = json.optBoolean("connected", false),
                latencyMs = if (json.has("latency_ms") && !json.isNull("latency_ms")) 
                    json.getInt("latency_ms") else null,
                capabilities = json.optJSONArray("capabilities")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )
        }
    }
}

/**
 * Atmosphere mesh network node.
 * 
 * This class represents a node in the Atmosphere network that can
 * register capabilities, connect to peers, and route intents.
 */
class AtmosphereNode private constructor(private val handle: Long) {
    
    companion object {
        init {
            System.loadLibrary("atmosphere_android")
        }
        
        /**
         * Create a new Atmosphere node with the given ID and data directory.
         *
         * @param nodeId Unique identifier for this node
         * @param dataDir Directory path for storing node data
         * @return A new AtmosphereNode instance
         * @throws AtmosphereException if configuration is invalid
         */
        @Throws(AtmosphereException::class)
        fun create(nodeId: String, dataDir: String): AtmosphereNode {
            val handle = nativeCreateNode(nodeId, dataDir)
            if (handle == 0L) {
                throw AtmosphereException.ConfigError("Failed to create node")
            }
            return AtmosphereNode(handle)
        }
        
        /**
         * Generate a new random node ID (UUID v4)
         */
        fun generateNodeId(): String {
            return nativeGenerateNodeId()
        }
        
        // Native methods
        @JvmStatic
        private external fun nativeCreateNode(nodeId: String, dataDir: String): Long
        
        @JvmStatic
        private external fun nativeGenerateNodeId(): String
    }
    
    /**
     * Start the node and connect to the mesh network.
     * @throws AtmosphereException if the node fails to start
     */
    @Throws(AtmosphereException::class)
    fun start() {
        val result = nativeStart(handle)
        if (result != 0) {
            throw AtmosphereException.NetworkError("Failed to start node: error code $result")
        }
    }
    
    /**
     * Stop the node and disconnect from the network.
     * This is safe to call even if the node is not running.
     */
    fun stop() {
        nativeStop(handle)
    }
    
    /**
     * Check if the node is currently running.
     */
    fun isRunning(): Boolean {
        return nativeIsRunning(handle)
    }
    
    /**
     * Get the node's unique identifier.
     */
    fun nodeId(): String {
        return nativeNodeId(handle)
    }
    
    /**
     * Get the node's data directory path.
     */
    fun dataDir(): String {
        return nativeDataDir(handle)
    }
    
    /**
     * Get the current node status as a JSON string.
     * 
     * The returned JSON has the following structure:
     * ```json
     * {
     *   "node_id": "...",
     *   "is_running": true,
     *   "capabilities_count": 5,
     *   "connected_peers": 3,
     *   "mesh_connected": true,
     *   "mesh_id": "...",
     *   "mesh_name": "..."
     * }
     * ```
     */
    fun statusJson(): String {
        return nativeStatusJson(handle)
    }
    
    /**
     * Register a capability that this node provides.
     *
     * @param capabilityJson JSON representation of the capability
     * @throws AtmosphereException if the JSON is invalid
     */
    @Throws(AtmosphereException::class)
    fun registerCapability(capabilityJson: String) {
        val result = nativeRegisterCapability(handle, capabilityJson)
        if (result != 0) {
            throw AtmosphereException.SerializationError("Invalid capability JSON")
        }
    }
    
    /**
     * Route an intent to a capable node in the mesh network.
     *
     * @param intentJson JSON representation of the intent
     * @return JSON string with the intent result
     * @throws AtmosphereException if routing fails
     */
    @Throws(AtmosphereException::class)
    fun routeIntent(intentJson: String): String {
        val result = nativeRouteIntent(handle, intentJson)
        if (result.startsWith("ERROR:")) {
            throw AtmosphereException.CapabilityNotFound(result.removePrefix("ERROR:"))
        }
        return result
    }
    
    // ========================================================================
    // NEW: Networking Functions
    // ========================================================================
    
    /**
     * Join a mesh network via WebSocket.
     *
     * @param endpoint The WebSocket endpoint (e.g., "ws://192.168.1.100:11451/api/ws")
     * @param token The authentication token for joining
     * @throws AtmosphereException if connection fails
     */
    @Throws(AtmosphereException::class)
    fun joinMesh(endpoint: String, token: String) {
        val result = nativeJoinMesh(handle, endpoint, token)
        if (result != 0) {
            throw AtmosphereException.NetworkError("Failed to join mesh: error code $result")
        }
    }
    
    /**
     * Disconnect from the current mesh network.
     */
    fun disconnectMesh() {
        nativeDisconnectMesh(handle)
    }
    
    /**
     * Discover peers on the mesh network.
     *
     * @return JSON array string of discovered peers
     */
    fun discoverPeers(): String {
        return nativeDiscoverPeers(handle)
    }
    
    /**
     * Discover peers and return as a list of MeshPeer objects.
     */
    fun discoverPeersList(): List<MeshPeer> {
        val json = discoverPeers()
        return parsePeersJson(json)
    }
    
    /**
     * Connect to a specific peer by address.
     *
     * @param address The peer's address (e.g., "192.168.1.100:11451")
     * @throws AtmosphereException if connection fails
     */
    @Throws(AtmosphereException::class)
    fun connectToPeer(address: String) {
        val result = nativeConnectToPeer(handle, address)
        if (result != 0) {
            throw AtmosphereException.NetworkError("Failed to connect to peer: error code $result")
        }
    }
    
    /**
     * Get the list of connected peers as JSON.
     *
     * @return JSON array string of connected peers
     */
    fun getPeers(): String {
        return nativeGetPeers(handle)
    }
    
    /**
     * Get the list of connected peers as MeshPeer objects.
     */
    fun getPeersList(): List<MeshPeer> {
        val json = getPeers()
        return parsePeersJson(json)
    }
    
    /**
     * Send a gossip message to the mesh.
     *
     * @param message The message to broadcast
     * @throws AtmosphereException if sending fails
     */
    @Throws(AtmosphereException::class)
    fun sendGossip(message: String) {
        val result = nativeSendGossip(handle, message)
        if (result != 0) {
            throw AtmosphereException.NetworkError("Failed to send gossip: error code $result")
        }
    }
    
    /**
     * Release native resources.
     */
    fun destroy() {
        nativeDestroy(handle)
    }
    
    protected fun finalize() {
        destroy()
    }
    
    // Helper function to parse peers JSON
    private fun parsePeersJson(json: String): List<MeshPeer> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { 
                MeshPeer.fromJson(array.getJSONObject(it)) 
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Native instance methods - existing
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeNodeId(handle: Long): String
    private external fun nativeDataDir(handle: Long): String
    private external fun nativeStatusJson(handle: Long): String
    private external fun nativeRegisterCapability(handle: Long, json: String): Int
    private external fun nativeRouteIntent(handle: Long, json: String): String
    private external fun nativeDestroy(handle: Long)
    
    // Native instance methods - networking (NEW)
    private external fun nativeJoinMesh(handle: Long, endpoint: String, token: String): Int
    private external fun nativeDisconnectMesh(handle: Long)
    private external fun nativeDiscoverPeers(handle: Long): String
    private external fun nativeConnectToPeer(handle: Long, address: String): Int
    private external fun nativeGetPeers(handle: Long): String
    private external fun nativeSendGossip(handle: Long, message: String): Int
}
