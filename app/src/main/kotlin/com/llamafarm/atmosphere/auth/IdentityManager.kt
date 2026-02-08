package com.llamafarm.atmosphere.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher

/**
 * Manages Node Identity and decentralized Mesh Token generation.
 * Uses Ed25519 for signing (via SpongyCastle or Tink if available, 
 * or fallback to standard Java signatures if Android 11+).
 * 
 * NOTE: For full Ed25519 on older Android, we'd need BouncyCastle.
 * This implementation uses standard Java KeyPairGenerator for portability.
 */
class IdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "IdentityManager"
        private const val PREFS_NAME = "atmosphere_identity"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_MESH_ID = "mesh_id"
        private const val ALGORITHM = "Ed25519"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var privateKey: PrivateKey? = null
    var publicKey: PublicKey? = null
    var nodeId: String? = null
    var meshId: String? = null

    init {
        loadIdentity()
    }

    /**
     * Load existing identity or generate a new one.
     */
    fun loadOrCreateIdentity(): String {
        if (nodeId == null) {
            generateIdentity()
        }
        // Fallback if generation failed
        if (nodeId == null) {
            nodeId = "android-${java.util.UUID.randomUUID().toString().take(12)}"
            prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
            Log.w(TAG, "Using fallback node ID: $nodeId")
        }
        return nodeId!!
    }

    private fun loadIdentity() {
        val privBase64 = prefs.getString(KEY_PRIVATE, null)
        val pubBase64 = prefs.getString(KEY_PUBLIC, null)
        nodeId = prefs.getString(KEY_NODE_ID, null)
        meshId = prefs.getString(KEY_MESH_ID, null)

        if (privBase64 != null && pubBase64 != null) {
            try {
                val kf = KeyFactory.getInstance(ALGORITHM)
                val privBytes = Base64.decode(privBase64, Base64.DEFAULT)
                val pubBytes = Base64.decode(pubBase64, Base64.DEFAULT)
                
                privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                publicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                Log.i(TAG, "Identity loaded: $nodeId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load identity", e)
            }
        }
    }

    private fun generateIdentity() {
        try {
            Log.i(TAG, "Generating new identity...")
            
            // Try Ed25519 first (Android 13+), fall back to EC
            val kpg = try {
                KeyPairGenerator.getInstance(ALGORITHM)
            } catch (e: Exception) {
                Log.w(TAG, "Ed25519 not available, falling back to EC")
                KeyPairGenerator.getInstance("EC").apply {
                    initialize(256)
                }
            }
            
            val kp = kpg.generateKeyPair()
            
            privateKey = kp.private
            publicKey = kp.public
            
            val pubBytes = publicKey!!.encoded
            // Node ID is a short hash of the public key
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(pubBytes)
            nodeId = bytesToHex(hash).take(16)
            
            prefs.edit().apply {
                putString(KEY_PRIVATE, Base64.encodeToString(privateKey!!.encoded, Base64.DEFAULT))
                putString(KEY_PUBLIC, Base64.encodeToString(publicKey!!.encoded, Base64.DEFAULT))
                putString(KEY_NODE_ID, nodeId)
                apply()
            }
            Log.i(TAG, "New identity generated: $nodeId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate identity", e)
        }
    }

    /**
     * Create a signed mesh token (Decentralized Auth).
     * Only founders or authorized nodes should call this.
     */
    fun createToken(
        targetNodeId: String?,
        targetMeshId: String,
        capabilities: List<String> = listOf("participant"),
        ttlSeconds: Long = 86400 // 24 hours
    ): String? {
        val privKey = privateKey ?: return null
        
        try {
            val now = System.currentTimeMillis() / 1000
            val expires = now + ttlSeconds
            val nonce = UUID.randomUUID().toString().take(16)
            
            val tokenData = JSONObject().apply {
                put("mesh_id", targetMeshId)
                put("node_id", targetNodeId) // Optional binding
                put("issued_at", now)
                put("expires_at", expires)
                put("capabilities", JSONArray(capabilities))
                put("issuer_id", nodeId)
                put("nonce", nonce)
            }

            // Create canonical string for signing
            val canonical = tokenData.toString()
            
            // Sign
            val sig = Signature.getInstance(ALGORITHM)
            sig.initSign(privKey)
            sig.update(canonical.toByteArray())
            val signature = Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
            
            tokenData.put("signature", signature)
            
            // Compact encoding (Base64 URL Safe)
            return Base64.encodeToString(tokenData.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create token", e)
            return null
        }
    }

    /**
     * Sign a message (e.g., for founder proof).
     */
    fun sign(message: ByteArray): String? {
        val privKey = privateKey ?: return null
        return try {
            val sig = Signature.getInstance(ALGORITHM)
            sig.initSign(privKey)
            sig.update(message)
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Signing failed", e)
            null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789abcdef"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(hexChars)
    }
}

private fun JSONArray(list: List<String>): org.json.JSONArray {
    val array = org.json.JSONArray()
    list.forEach { array.put(it) }
    return array
}
