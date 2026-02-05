package com.llamafarm.atmosphere.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SavedMesh"

/**
 * Represents a saved mesh connection for persistence and reconnection.
 * 
 * Each saved mesh contains all the information needed to reconnect:
 * - Mesh identity (id, name, founder)
 * - Authentication token
 * - Multiple connection endpoints (BLE, LAN, Relay)
 * - Connection metadata (when joined, last connected)
 * - User preferences (auto-reconnect)
 */
data class SavedMesh(
    val meshId: String,
    val meshName: String,
    val founderId: String,
    val founderName: String,
    val relayToken: String,
    val endpoints: List<Endpoint>,
    val joinedAt: Long,
    val lastConnected: Long,
    val autoReconnect: Boolean = true
) {
    /**
     * Connection endpoint for a specific transport.
     */
    data class Endpoint(
        val type: String,           // "ble", "lan", "relay", "public"
        val address: String,        // URL or device address
        val priority: Int = 0,      // Higher = preferred
        val lastSuccessful: Long? = null,
        val lastLatencyMs: Long? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("address", address)
            put("priority", priority)
            lastSuccessful?.let { put("lastSuccessful", it) }
            lastLatencyMs?.let { put("lastLatencyMs", it) }
        }
        
        companion object {
            fun fromJson(json: JSONObject): Endpoint = Endpoint(
                type = json.getString("type"),
                address = json.getString("address"),
                priority = json.optInt("priority", 0),
                lastSuccessful = if (json.has("lastSuccessful")) json.getLong("lastSuccessful") else null,
                lastLatencyMs = if (json.has("lastLatencyMs")) json.getLong("lastLatencyMs") else null
            )
        }
    }
    
    /**
     * Get the best endpoint for reconnection.
     * Prefers: most recently successful > highest priority > relay (most reliable)
     */
    fun getBestEndpoint(): Endpoint? {
        return endpoints
            .sortedWith(compareByDescending<Endpoint> { it.lastSuccessful ?: 0 }
                .thenByDescending { it.priority }
                .thenBy { if (it.type == "relay") 0 else 1 })
            .firstOrNull()
    }
    
    /**
     * Get endpoint by type.
     */
    fun getEndpoint(type: String): Endpoint? = endpoints.find { it.type == type }
    
    /**
     * Get endpoints as a map (for legacy joinMesh compatibility).
     */
    fun getEndpointsMap(): Map<String, String> = endpoints.associate { it.type to it.address }
    
    /**
     * Check if this mesh has a relay endpoint (always reachable).
     */
    fun hasRelay(): Boolean = endpoints.any { it.type == "relay" }
    
    /**
     * Update endpoint stats after a connection attempt.
     */
    fun withUpdatedEndpoint(type: String, successful: Boolean, latencyMs: Long?): SavedMesh {
        val updatedEndpoints = endpoints.map { ep ->
            if (ep.type == type) {
                ep.copy(
                    lastSuccessful = if (successful) System.currentTimeMillis() else ep.lastSuccessful,
                    lastLatencyMs = latencyMs ?: ep.lastLatencyMs,
                    priority = if (successful) ep.priority + 1 else ep.priority
                )
            } else ep
        }
        return copy(
            endpoints = updatedEndpoints,
            lastConnected = if (successful) System.currentTimeMillis() else lastConnected
        )
    }
    
    fun toJson(): JSONObject = JSONObject().apply {
        put("meshId", meshId)
        put("meshName", meshName)
        put("founderId", founderId)
        put("founderName", founderName)
        put("relayToken", relayToken)
        put("endpoints", JSONArray().apply {
            endpoints.forEach { put(it.toJson()) }
        })
        put("joinedAt", joinedAt)
        put("lastConnected", lastConnected)
        put("autoReconnect", autoReconnect)
    }
    
    companion object {
        fun fromJson(json: JSONObject): SavedMesh {
            val endpointsArray = json.getJSONArray("endpoints")
            val endpoints = (0 until endpointsArray.length()).map { i ->
                Endpoint.fromJson(endpointsArray.getJSONObject(i))
            }
            
            return SavedMesh(
                meshId = json.getString("meshId"),
                meshName = json.getString("meshName"),
                founderId = json.optString("founderId", ""),
                founderName = json.optString("founderName", "Unknown"),
                relayToken = json.getString("relayToken"),
                endpoints = endpoints,
                joinedAt = json.getLong("joinedAt"),
                lastConnected = json.getLong("lastConnected"),
                autoReconnect = json.optBoolean("autoReconnect", true)
            )
        }
        
        /**
         * Create SavedMesh from a mesh join (QR code scan or invite).
         */
        fun fromJoin(
            meshId: String,
            meshName: String,
            founderId: String,
            founderName: String,
            token: String,
            endpointsMap: Map<String, String>
        ): SavedMesh {
            val now = System.currentTimeMillis()
            val endpoints = mutableListOf<Endpoint>()
            
            // Add endpoints from map with appropriate priorities
            endpointsMap.forEach { (type, address) ->
                val priority = when (type) {
                    "local" -> 100     // Prefer local (fastest)
                    "lan" -> 90
                    "public" -> 50
                    "relay" -> 10      // Relay is fallback (most reliable)
                    else -> 0
                }
                endpoints.add(Endpoint(type, address, priority))
            }
            
            // Always add BLE endpoint for local mesh discovery
            // BLE works without internet and enables device-to-device communication
            if (!endpoints.any { it.type == "ble" }) {
                endpoints.add(Endpoint(
                    type = "ble",
                    address = "ble://$meshId",  // BLE uses mesh ID for advertising
                    priority = 80  // High priority (between LAN and local)
                ))
                Log.i(TAG, "✅ Added BLE endpoint for mesh $meshId")
            }
            
            return SavedMesh(
                meshId = meshId,
                meshName = meshName,
                founderId = founderId,
                founderName = founderName,
                relayToken = token,
                endpoints = endpoints,
                joinedAt = now,
                lastConnected = now,
                autoReconnect = true
            )
        }
    }
}

/**
 * Repository for managing saved meshes.
 * Uses JSON array stored in DataStore for persistence.
 */
class SavedMeshRepository(private val preferences: AtmospherePreferences) {
    
    /**
     * Save a mesh (add or update).
     */
    suspend fun saveMesh(mesh: SavedMesh) {
        val meshes = getAllMeshes().toMutableList()
        val existingIndex = meshes.indexOfFirst { it.meshId == mesh.meshId }
        
        if (existingIndex >= 0) {
            meshes[existingIndex] = mesh
        } else {
            meshes.add(mesh)
        }
        
        saveMeshes(meshes)
    }
    
    /**
     * Update mesh connection stats.
     */
    suspend fun updateMeshConnection(meshId: String, endpointType: String, successful: Boolean, latencyMs: Long? = null) {
        val meshes = getAllMeshes().toMutableList()
        val index = meshes.indexOfFirst { it.meshId == meshId }
        
        if (index >= 0) {
            meshes[index] = meshes[index].withUpdatedEndpoint(endpointType, successful, latencyMs)
            saveMeshes(meshes)
        }
    }
    
    /**
     * Remove a mesh by ID.
     */
    suspend fun removeMesh(meshId: String) {
        val meshes = getAllMeshes().filter { it.meshId != meshId }
        saveMeshes(meshes)
    }
    
    /**
     * Get all saved meshes.
     */
    suspend fun getAllMeshes(): List<SavedMesh> {
        return preferences.getSavedMeshes()
    }
    
    /**
     * Get a specific mesh by ID.
     */
    suspend fun getMesh(meshId: String): SavedMesh? {
        return getAllMeshes().find { it.meshId == meshId }
    }
    
    /**
     * Get meshes with auto-reconnect enabled.
     */
    suspend fun getAutoReconnectMeshes(): List<SavedMesh> {
        return getAllMeshes().filter { it.autoReconnect }
    }
    
    /**
     * Set auto-reconnect for a mesh.
     */
    suspend fun setAutoReconnect(meshId: String, enabled: Boolean) {
        val meshes = getAllMeshes().map { mesh ->
            if (mesh.meshId == meshId) mesh.copy(autoReconnect = enabled) else mesh
        }
        saveMeshes(meshes)
    }
    
    private suspend fun saveMeshes(meshes: List<SavedMesh>) {
        preferences.saveMeshes(meshes)
    }
}
