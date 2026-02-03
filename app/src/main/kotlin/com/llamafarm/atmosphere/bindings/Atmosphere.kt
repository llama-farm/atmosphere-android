// Atmosphere Android - Kotlin JNI Bindings
// Auto-generated from atmosphere.udl - DO NOT EDIT MANUALLY
// Generated: 2026-02-03

package com.llamafarm.atmosphere.bindings

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
     *   "connected_peers": 3
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
    
    /**
     * Release native resources.
     */
    fun destroy() {
        nativeDestroy(handle)
    }
    
    protected fun finalize() {
        destroy()
    }
    
    // Native instance methods
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeNodeId(handle: Long): String
    private external fun nativeDataDir(handle: Long): String
    private external fun nativeStatusJson(handle: Long): String
    private external fun nativeRegisterCapability(handle: Long, json: String): Int
    private external fun nativeRouteIntent(handle: Long, json: String): String
    private external fun nativeDestroy(handle: Long)
}
