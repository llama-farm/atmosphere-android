package com.llamafarm.atmosphere.service

import android.util.Base64
import android.util.Log
import com.llamafarm.atmosphere.core.AtmosphereCore
import org.json.JSONObject
import java.util.UUID

/**
 * Mesh management extensions for AtmosphereService.
 * Provides Ditto-style mesh creation, joining, and invite generation.
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

    // Update the AtmosphereCore instance
    val core = getAtmosphereCore()
    if (core != null) {
        core.meshId = meshId
        core.sharedSecret = secret
        Log.i("MeshManagement", "✅ Created new mesh: $meshId")
    } else {
        Log.w("MeshManagement", "AtmosphereCore not initialized, mesh credentials not applied")
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

    // Update the AtmosphereCore instance
    val core = getAtmosphereCore()
    if (core != null) {
        core.meshId = token.meshId
        core.sharedSecret = token.secret
        Log.i("MeshManagement", "✅ Joined mesh: ${token.meshId}")
        
        // TODO: Connect to BigLlama if provided
        if (token.bigLlamaUrl != null) {
            Log.i("MeshManagement", "BigLlama URL: ${token.bigLlamaUrl}")
            // connectToBigLlama(token.bigLlamaUrl)
        }
    } else {
        Log.w("MeshManagement", "AtmosphereCore not initialized, cannot join mesh")
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
    val core = getAtmosphereCore() ?: run {
        Log.e("MeshManagement", "AtmosphereCore not initialized")
        return null
    }

    return MeshInviteToken(
        meshId = core.meshId,
        secret = core.sharedSecret,
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
    val peers: List<com.llamafarm.atmosphere.core.PeerInfo>,
    val bigLlamaUrl: String? = null
)

fun AtmosphereService.getMeshInfo(): MeshInfo? {
    val core = getAtmosphereCore() ?: return null
    
    val peers = core.connectedPeers()
    
    // TODO: Determine mode (playground vs token) based on mesh origin
    val mode = "token" // For now, assume token mode
    
    return MeshInfo(
        meshId = core.meshId,
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
    val core = getAtmosphereCore() ?: run {
        Log.e("MeshManagement", "AtmosphereCore not initialized")
        return
    }

    val newMeshId = UUID.randomUUID().toString()
    val newSecret = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
        .joinToString("") { "%02x".format(it) }

    core.meshId = newMeshId
    core.sharedSecret = newSecret

    Log.i("MeshManagement", "✅ Left mesh, new mesh_id: $newMeshId")

    // TODO: Clear persisted mesh credentials
    // preferences.setCurrentMeshId(newMeshId)
    // preferences.setCurrentMeshSecret(newSecret)
    // preferences.clearBigLlamaUrl()
}
