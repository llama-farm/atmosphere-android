package com.llamafarm.atmosphere.service

import android.util.Base64
import android.util.Log
import com.llamafarm.atmosphere.core.AtmosphereNative
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/**
 * Mesh management extensions for AtmosphereService.
 * Provides Ditto-style mesh creation, joining, and invite generation.
 * Uses SimplePeerInfo from AtmosphereService.kt
 */

data class MeshInviteToken(
    val meshId: String,
    val secret: String,
    val appId: String = "atmosphere",
    val bigLlamaUrl: String? = null,
    val created: Long = System.currentTimeMillis() / 1000,
    val expires: Long? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("mesh_id", meshId)
            put("secret", secret)
            put("app_id", appId)
            bigLlamaUrl?.let { put("bigllama_url", it) }
            put("created", created)
            expires?.let { put("expires", it) }
        }.toString()
    }

    fun toBase64(): String {
        return Base64.encodeToString(toJson().toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        fun fromBase64(token: String): MeshInviteToken? {
            return try {
                val json = JSONObject(String(Base64.decode(token, Base64.DEFAULT)))
                MeshInviteToken(
                    meshId = json.getString("mesh_id"),
                    secret = json.getString("secret"),
                    appId = json.optString("app_id", "atmosphere"),
                    bigLlamaUrl = json.optString("bigllama_url", null),
                    created = json.optLong("created", System.currentTimeMillis() / 1000),
                    expires = if (json.has("expires")) json.getLong("expires") else null
                )
            } catch (e: Exception) {
                Log.e("MeshManagement", "Failed to parse invite token", e)
                null
            }
        }
    }
}

/**
 * Create a new mesh with fresh credentials.
 * Returns the invite token that can be shared via QR code.
 */
fun AtmosphereService.createMesh(bigLlamaUrl: String? = null): MeshInviteToken {
    val meshId = UUID.randomUUID().toString()
    val secret = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
        .joinToString("") { "%02x".format(it) }

    val handle = getAtmosphereHandle()
    if (handle != 0L) {
        // TODO: Update mesh credentials via JNI (need to add JNI function for this)
        Log.i("MeshManagement", "✅ Created new mesh: $meshId (handle: $handle)")
    } else {
        Log.w("MeshManagement", "Atmosphere not initialized, mesh credentials not applied")
    }

    // TODO: Persist mesh credentials to preferences
    // preferences.setCurrentMeshId(meshId)
    // preferences.setCurrentMeshSecret(secret)

    return MeshInviteToken(
        meshId = meshId,
        secret = secret,
        bigLlamaUrl = bigLlamaUrl
    )
}

/**
 * Join an existing mesh using an invite token.
 */
fun AtmosphereService.joinMesh(inviteToken: String): Boolean {
    val token = MeshInviteToken.fromBase64(inviteToken)
    if (token == null) {
        Log.e("MeshManagement", "Invalid invite token")
        return false
    }

    // Check expiration
    if (token.expires != null && System.currentTimeMillis() / 1000 > token.expires) {
        Log.e("MeshManagement", "Invite token expired")
        return false
    }

    val handle = getAtmosphereHandle()
    if (handle != 0L) {
        // TODO: Update mesh credentials via JNI (need to add JNI function for this)
        Log.i("MeshManagement", "✅ Joined mesh: ${token.meshId} (handle: $handle)")
        
        // TODO: Connect to BigLlama if provided
        if (token.bigLlamaUrl != null) {
            Log.i("MeshManagement", "BigLlama URL: ${token.bigLlamaUrl}")
            // connectToBigLlama(token.bigLlamaUrl)
        }
    } else {
        Log.w("MeshManagement", "Atmosphere not initialized, cannot join mesh")
        return false
    }

    // TODO: Persist mesh credentials to preferences
    // preferences.setCurrentMeshId(token.meshId)
    // preferences.setCurrentMeshSecret(token.secret)
    // if (token.bigLlamaUrl != null) preferences.setBigLlamaUrl(token.bigLlamaUrl)

    return true
}

/**
 * Generate an invite token for the current mesh.
 * Can be encoded as QR code or shared as text.
 */
fun AtmosphereService.generateInvite(bigLlamaUrl: String? = null): MeshInviteToken? {
    val handle = getAtmosphereHandle()
    if (handle == 0L) {
        Log.e("MeshManagement", "Atmosphere not initialized")
        return null
    }

    // TODO: Get mesh ID from JNI (for now, use hardcoded playground mesh)
    val meshId = getMeshId() ?: "atmosphere-playground-mesh-v1"
    val secret = "placeholder-secret"  // TODO: Get from preferences or JNI

    return MeshInviteToken(
        meshId = meshId,
        secret = secret,
        bigLlamaUrl = bigLlamaUrl
    )
}

/**
 * Get current mesh information.
 */
data class MeshInfo(
    val meshId: String,
    val mode: String, // "playground" or "token"
    val peerCount: Int,
    val peers: List<SimplePeerInfo>,
    val bigLlamaUrl: String? = null
)

fun AtmosphereService.getMeshInfo(): MeshInfo? {
    val handle = getAtmosphereHandle()
    if (handle == 0L) return null
    
    // Get peers via JNI
    val peersJson = try {
        AtmosphereNative.peers(handle)
    } catch (e: Exception) {
        Log.e("MeshManagement", "Failed to get peers", e)
        return null
    }
    
    val peers: List<SimplePeerInfo> = try {
        val peerArray = JSONArray(peersJson)
        (0 until peerArray.length()).map { i ->
            val p = peerArray.getJSONObject(i)
            val transportsArray = p.optJSONArray("connected_transports")
            val transportsList = if (transportsArray != null) {
                (0 until transportsArray.length()).map { j -> transportsArray.getString(j) }
            } else {
                listOf("lan")
            }
            SimplePeerInfo(
                peerId = p.getString("peer_id"),
                state = p.optString("state", "Connected"),
                transports = transportsList
            )
        }
    } catch (e: Exception) {
        Log.e("MeshManagement", "Failed to parse peers", e)
        emptyList<SimplePeerInfo>()
    }
    
    // TODO: Determine mode (playground vs token) based on mesh origin
    val mode = "playground" // For now, assume playground mode
    val meshId = getMeshId() ?: "atmosphere-playground-mesh-v1"
    
    return MeshInfo(
        meshId = meshId,
        mode = mode,
        peerCount = peers.size,
        peers = peers,
        bigLlamaUrl = null // TODO: Get from preferences
    )
}

/**
 * Leave the current mesh by generating new credentials.
 * Peers with old credentials will be rejected on next handshake.
 */
fun AtmosphereService.leaveMesh() {
    val handle = getAtmosphereHandle()
    if (handle == 0L) {
        Log.e("MeshManagement", "Atmosphere not initialized")
        return
    }

    val newMeshId = UUID.randomUUID().toString()
    val newSecret = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
        .joinToString("") { "%02x".format(it) }

    // TODO: Update mesh credentials via JNI (need to add JNI function for this)

    Log.i("MeshManagement", "✅ Left mesh, new mesh_id: $newMeshId")

    // TODO: Clear persisted mesh credentials
    // preferences.setCurrentMeshId(newMeshId)
    // preferences.setCurrentMeshSecret(newSecret)
    // preferences.clearBigLlamaUrl()
}
