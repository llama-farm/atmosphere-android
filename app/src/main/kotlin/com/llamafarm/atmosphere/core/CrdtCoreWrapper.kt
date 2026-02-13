package com.llamafarm.atmosphere.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Temporary wrapper to bridge old AtmosphereCore API to new JNI implementation.
 * This allows AtmosphereBinderService to compile without major rewrites.
 * TODO: Refactor BinderService to use JNI directly.
 */
class CrdtCoreWrapper(private val handle: Long) {
    
    val peerId: String
        get() = try {
            val healthJson = AtmosphereNative.health(handle)
            val health = JSONObject(healthJson)
            health.optString("peer_id", "unknown")
        } catch (e: Exception) {
            "unknown"
        }
    
    /**
     * Insert a document into a CRDT collection.
     */
    fun insert(collection: String, data: Map<String, Any>) {
        try {
            val docId = data["_id"]?.toString() ?: java.util.UUID.randomUUID().toString()
            val docJson = JSONObject(data).toString()
            AtmosphereNative.insert(handle, collection, docId, docJson)
        } catch (e: Exception) {
            Log.e("CrdtCoreWrapper", "Failed to insert: ${e.message}", e)
        }
    }
    
    /**
     * Sync now - not directly supported in current JNI, so this is a no-op.
     */
    fun syncNow() {
        // Sync happens automatically in Rust core
        Log.d("CrdtCoreWrapper", "syncNow() called - sync is automatic in Rust core")
    }
    
    /**
     * Query a collection - returns list of document maps.
     */
    fun query(collection: String): List<Map<String, Any>> {
        return try {
            val json = AtmosphereNative.query(handle, collection)
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { key ->
                    obj.get(key)
                }
            }
        } catch (e: Exception) {
            Log.e("CrdtCoreWrapper", "Failed to query: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get a single document.
     */
    fun get(collection: String, docId: String): Map<String, Any>? {
        return try {
            val json = AtmosphereNative.get(handle, collection, docId)
            if (json.isEmpty()) return null
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { key ->
                obj.get(key)
            }
        } catch (e: Exception) {
            Log.e("CrdtCoreWrapper", "Failed to get: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get connected peers.
     */
    fun connectedPeers(): List<PeerInfo> {
        return try {
            val json = AtmosphereNative.peers(handle)
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PeerInfo(
                    peerId = obj.optString("peer_id", "unknown"),
                    transport = obj.optString("transport", "lan"),
                    lastSeen = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("CrdtCoreWrapper", "Failed to get peers: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Peer info data class for compatibility.
     */
    data class PeerInfo(
        val peerId: String,
        val transport: String,
        val lastSeen: Long
    )
    
    /**
     * App ID (hardcoded for now).
     */
    val appId: String = "atmosphere"
    
    /**
     * Listen port (hardcoded mesh port).
     */
    val listenPort: Int = 11452
    
    /**
     * Observe changes - not supported yet, returns a dummy observer ID.
     */
    fun observe(collection: String, callback: (ChangeEvent) -> Unit): String {
        val observerId = java.util.UUID.randomUUID().toString()
        Log.w("CrdtCoreWrapper", "observe() not yet implemented in JNI - returning dummy ID")
        // TODO: Implement observe via polling or add to JNI
        return observerId
    }
    
    /**
     * Remove an observer - no-op for now.
     */
    fun removeObserver(observerId: String) {
        Log.d("CrdtCoreWrapper", "removeObserver() called - not yet implemented")
    }
    
    /**
     * Change event data class.
     */
    data class ChangeEvent(
        val collection: String,
        val docId: String,
        val kind: String  // "insert", "update", "delete"
    )
}
