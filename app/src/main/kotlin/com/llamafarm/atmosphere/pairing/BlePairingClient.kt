package com.llamafarm.atmosphere.pairing

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BLE Proximity Pairing Client for Android.
 * 
 * Flow (from new device's perspective):
 * 1. Discover nearby Atmosphere BLE nodes
 * 2. Send pair_request with our node_id + public_key
 * 3. Receive pair_challenge (shows PIN on other device)
 * 4. User enters PIN → we compute HMAC and send pair_confirm
 * 5. Receive pair_token with signed MeshToken
 * 6. Join mesh with token
 */
class BlePairingClient(
    private val nodeId: String,
    private val publicKey: String = "",  // Ed25519 public key (base64)
) {
    companion object {
        private const val TAG = "BlePairingClient"
    }
    
    // Current pairing state
    enum class State {
        IDLE,
        REQUESTING,
        WAITING_FOR_PIN,     // User needs to enter PIN displayed on other device
        CONFIRMING,
        SUCCESS,
        FAILED
    }
    
    var state: State = State.IDLE
        private set
    
    var lastError: String? = null
        private set
    
    var receivedToken: JSONObject? = null
        private set
    
    private var targetNodeId: String? = null
    private var meshId: String? = null
    private var sharedSecret: ByteArray? = null
    
    /**
     * Create a pair_request message to send via BLE.
     */
    fun createPairRequest(): JSONObject {
        state = State.REQUESTING
        return JSONObject().apply {
            put("type", "pair_request")
            put("node_id", nodeId)
            put("public_key", publicKey)
        }
    }
    
    /**
     * Handle response from the existing mesh node.
     * Returns: next message to send, or null if waiting for user input.
     */
    fun handleResponse(response: JSONObject): JSONObject? {
        val type = response.optString("type", "")
        
        return when (type) {
            "pair_challenge" -> {
                // Other device is showing a PIN
                targetNodeId = response.optString("node_id")
                meshId = response.optString("mesh_id")
                
                // Derive shared secret (must match server's derivation)
                val material = "$publicKey:$targetNodeId:$meshId"
                val md = MessageDigest.getInstance("SHA-256")
                // Simple PBKDF2-like derivation to match Python's pbkdf2_hmac
                sharedSecret = pbkdf2("HmacSHA256", material.toByteArray(), meshId?.toByteArray() ?: ByteArray(0), 1000, 32)
                
                state = State.WAITING_FOR_PIN
                Log.i(TAG, "Pair challenge received from $targetNodeId — enter PIN shown on their screen")
                null  // Wait for user to enter PIN
            }
            
            "pair_token" -> {
                // Success! We got a token
                receivedToken = response
                state = State.SUCCESS
                Log.i(TAG, "✅ Pairing successful! Token received")
                null
            }
            
            "pair_rejected" -> {
                val reason = response.optString("reason", "unknown")
                lastError = reason
                
                if (reason == "invalid_pin") {
                    val remaining = response.optInt("attempts_remaining", 0)
                    Log.w(TAG, "Invalid PIN. $remaining attempts remaining")
                    state = State.WAITING_FOR_PIN  // Let user retry
                } else {
                    state = State.FAILED
                    Log.e(TAG, "Pairing rejected: $reason")
                }
                null
            }
            
            else -> {
                Log.w(TAG, "Unknown pairing response type: $type")
                null
            }
        }
    }
    
    /**
     * Submit the PIN entered by the user.
     * Returns the pair_confirm message to send via BLE.
     */
    fun submitPin(pin: String): JSONObject? {
        val secret = sharedSecret ?: run {
            lastError = "No shared secret"
            state = State.FAILED
            return null
        }
        
        // Compute HMAC(pin, shared_secret) — must match Python's hmac.new()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hmacBytes = mac.doFinal(pin.toByteArray(Charsets.UTF_8))
        val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }
        
        state = State.CONFIRMING
        
        return JSONObject().apply {
            put("type", "pair_confirm")
            put("node_id", nodeId)
            put("pin_hmac", hmacHex)
        }
    }
    
    /**
     * Reset state for a new pairing attempt.
     */
    fun reset() {
        state = State.IDLE
        lastError = null
        receivedToken = null
        targetNodeId = null
        meshId = null
        sharedSecret = null
    }
    
    /**
     * PBKDF2 key derivation (matches Python's hashlib.pbkdf2_hmac).
     */
    private fun pbkdf2(algorithm: String, password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(password, algorithm))
        
        val blocks = (keyLength + mac.macLength - 1) / mac.macLength
        val result = ByteArray(keyLength)
        var offset = 0
        
        for (block in 1..blocks) {
            // U1 = PRF(password, salt || INT(block))
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                ((block shr 24) and 0xFF).toByte(),
                ((block shr 16) and 0xFF).toByte(),
                ((block shr 8) and 0xFF).toByte(),
                (block and 0xFF).toByte()
            ))
            var u = mac.doFinal()
            val t = u.copyOf()
            
            for (i in 1 until iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            
            val len = minOf(mac.macLength, keyLength - offset)
            System.arraycopy(t, 0, result, offset, len)
            offset += len
        }
        
        return result
    }
}
