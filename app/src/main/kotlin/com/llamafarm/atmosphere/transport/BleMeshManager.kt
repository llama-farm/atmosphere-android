package com.llamafarm.atmosphere.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.llamafarm.atmosphere.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "BleMeshManager"

/**
 * Encrypted BLE mesh message with TTL and signature.
 */
data class EncryptedBleMessage(
    val messageId: String,
    val fromNodeId: String,
    val toNodeId: String?, // null = broadcast
    val meshId: String,
    val ttl: Int,
    val timestamp: Long,
    val encryptedPayload: ByteArray,
    val nonce: ByteArray,
    val signature: ByteArray,
    val hopCount: Int = 0
) {
    /**
     * Serialize to bytes for transmission.
     */
    fun toBytes(): ByteArray {
        val json = JSONObject().apply {
            put("id", messageId)
            put("from", fromNodeId)
            put("to", toNodeId ?: JSONObject.NULL)
            put("mesh", meshId)
            put("ttl", ttl)
            put("ts", timestamp)
            put("payload", android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP))
            put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
            put("sig", android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP))
            put("hops", hopCount)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Create a forwarded copy with decremented TTL.
     */
    fun forward(): EncryptedBleMessage? {
        if (ttl <= 1) return null
        return copy(ttl = ttl - 1, hopCount = hopCount + 1)
    }
    
    companion object {
        fun fromBytes(data: ByteArray): EncryptedBleMessage? {
            return try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                EncryptedBleMessage(
                    messageId = json.getString("id"),
                    fromNodeId = json.getString("from"),
                    toNodeId = json.optString("to").takeIf { it.isNotEmpty() && it != "null" },
                    meshId = json.getString("mesh"),
                    ttl = json.getInt("ttl"),
                    timestamp = json.getLong("ts"),
                    encryptedPayload = android.util.Base64.decode(json.getString("payload"), android.util.Base64.NO_WRAP),
                    nonce = android.util.Base64.decode(json.getString("nonce"), android.util.Base64.NO_WRAP),
                    signature = android.util.Base64.decode(json.getString("sig"), android.util.Base64.NO_WRAP),
                    hopCount = json.optInt("hops", 0)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse encrypted message: ${e.message}")
                null
            }
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedBleMessage
        return messageId == other.messageId
    }
    
    override fun hashCode(): Int = messageId.hashCode()
}

/**
 * Mesh encryption utilities using AES-256-GCM.
 */
object MeshCrypto {
    private const val AES_KEY_SIZE = 256
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    
    /**
     * Derive mesh encryption key from mesh token.
     */
    fun deriveKey(meshToken: String, meshId: String): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            meshToken.toCharArray(),
            "atmosphere-ble-mesh-$meshId".toByteArray(Charsets.UTF_8),
            100_000,
            AES_KEY_SIZE
        )
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
    
    /**
     * Encrypt payload using AES-256-GCM.
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, nonce)
    }
    
    /**
     * Decrypt payload using AES-256-GCM.
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, key: SecretKey): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed: ${e.message}")
            null
        }
    }
    
    /**
     * Generate HMAC-SHA256 signature.
     */
    fun sign(data: ByteArray, key: SecretKey): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data)
    }
    
    /**
     * Verify HMAC-SHA256 signature.
     */
    fun verify(data: ByteArray, signature: ByteArray, key: SecretKey): Boolean {
        val expected = sign(data, key)
        return expected.contentEquals(signature)
    }
    
    /**
     * Generate unique message ID.
     */
    fun generateMessageId(): String {
        val random = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    }
}

/**
 * BLE Mesh Manager - High-level manager that integrates BleTransport with TransportManager.
 * 
 * Responsibilities:
 * - Manages BleTransport lifecycle
 * - Handles mesh token encryption/decryption
 * - Implements multi-hop forwarding with TTL
 * - Tracks seen messages to prevent loops
 * - Bridges to TransportManager
 */
class BleMeshManager(
    private val context: Context,
    private val nodeId: String,
    private val meshId: String,
    private val config: TransportConfig.BleMeshConfig = TransportConfig.BleMeshConfig()
) : Transport {
    
    override val type = TransportType.BLE_MESH
    override var connected = false
    override val metrics = TransportMetrics()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var bleTransport: BleTransport? = null
    private var meshKey: SecretKey? = null
    private var messageHandler: ((ByteArray) -> Unit)? = null
    
    // Deduplication - track seen message IDs
    private val seenMessageIds = object : LinkedHashMap<String, Long>(500, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 500
        }
    }
    
    // State
    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()
    
    private val _discoveredPeers = MutableStateFlow<List<NodeInfo>>(emptyList())
    val discoveredPeers: StateFlow<List<NodeInfo>> = _discoveredPeers.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()
    
    enum class State {
        STOPPED, STARTING, RUNNING, ERROR
    }
    
    data class DecryptedMessage(
        val fromNodeId: String,
        val payload: ByteArray,
        val hopCount: Int,
        val timestamp: Long
    )
    
    // ========================================================================
    // Transport Interface Implementation
    // ========================================================================
    
    override suspend fun connect(config: Any): Boolean {
        return start(meshToken = config as? String)
    }
    
    override suspend fun disconnect() {
        stop()
    }
    
    override suspend fun send(message: ByteArray): Boolean {
        return broadcast(message)
    }
    
    override fun onMessage(handler: (ByteArray) -> Unit) {
        messageHandler = handler
    }
    
    // ========================================================================
    // Lifecycle
    // ========================================================================
    
    /**
     * Start BLE mesh with the given mesh token for encryption.
     */
    fun start(meshToken: String? = null): Boolean {
        if (!config.enabled) {
            Log.i(TAG, "BLE mesh disabled in config")
            return false
        }
        
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            _state.value = State.ERROR
            return false
        }
        
        _state.value = State.STARTING
        
        // Derive encryption key from mesh token
        meshKey = meshToken?.let { MeshCrypto.deriveKey(it, meshId) }
        
        // Create and configure BleTransport
        bleTransport = BleTransport(
            context = context,
            nodeName = "Atmosphere-${nodeId.take(8)}",
            capabilities = listOf("relay", "ble-mesh")
        ).apply {
            onPeerDiscovered = { info ->
                updatePeers(info)
            }
            onPeerLost = { peerId ->
                removePeer(peerId)
            }
            onMessage = { message ->
                handleBleMessage(message)
            }
        }
        
        bleTransport?.start()
        
        connected = true
        _state.value = State.RUNNING
        
        // Start periodic hello broadcasts
        scope.launch {
            while (isActive && _state.value == State.RUNNING) {
                broadcastHello()
                delay(30_000) // Every 30 seconds
            }
        }
        
        Log.i(TAG, "BLE Mesh Manager started for mesh $meshId")
        return true
    }
    
    fun stop() {
        bleTransport?.stop()
        bleTransport = null
        meshKey = null
        connected = false
        _state.value = State.STOPPED
        _discoveredPeers.value = emptyList()
        
        Log.i(TAG, "BLE Mesh Manager stopped")
    }
    
    // ========================================================================
    // Messaging
    // ========================================================================
    
    /**
     * Send an encrypted message to a specific peer.
     */
    suspend fun sendTo(targetNodeId: String, payload: ByteArray, ttl: Int = config.maxHops): Boolean {
        return sendMessage(payload, targetNodeId, ttl)
    }
    
    /**
     * Broadcast an encrypted message to all peers.
     */
    suspend fun broadcast(payload: ByteArray, ttl: Int = config.maxHops): Boolean {
        return sendMessage(payload, null, ttl)
    }
    
    private suspend fun sendMessage(payload: ByteArray, targetNodeId: String?, ttl: Int): Boolean {
        val transport = bleTransport ?: return false
        
        val encryptedMessage = createEncryptedMessage(payload, targetNodeId, ttl)
        val messageBytes = encryptedMessage.toBytes()
        
        // Track as sent to prevent echo
        seenMessageIds[encryptedMessage.messageId] = System.currentTimeMillis()
        
        val sent = transport.send(
            payload = messageBytes,
            msgType = MessageType.DATA,
            ttl = ttl
        )
        
        if (sent) {
            metrics.addSample(50f, true) // Assume 50ms latency for BLE
        } else {
            metrics.addSample(Float.MAX_VALUE, false)
        }
        
        return sent
    }
    
    private fun createEncryptedMessage(
        payload: ByteArray,
        targetNodeId: String?,
        ttl: Int
    ): EncryptedBleMessage {
        val key = meshKey
        val (encryptedPayload, nonce) = if (key != null) {
            MeshCrypto.encrypt(payload, key)
        } else {
            // Unencrypted fallback (not recommended)
            Pair(payload, ByteArray(12))
        }
        
        val messageId = MeshCrypto.generateMessageId()
        val timestamp = System.currentTimeMillis()
        
        // Sign the message
        val signData = "$messageId:$nodeId:$meshId:$timestamp".toByteArray()
        val signature = if (key != null) {
            MeshCrypto.sign(signData, key)
        } else {
            ByteArray(32)
        }
        
        return EncryptedBleMessage(
            messageId = messageId,
            fromNodeId = nodeId,
            toNodeId = targetNodeId,
            meshId = meshId,
            ttl = ttl,
            timestamp = timestamp,
            encryptedPayload = encryptedPayload,
            nonce = nonce,
            signature = signature
        )
    }
    
    private fun handleBleMessage(message: BleMessage) {
        scope.launch {
            try {
                // Try to parse as encrypted mesh message
                val encrypted = EncryptedBleMessage.fromBytes(message.payload) ?: return@launch
                
                // Check mesh ID
                if (encrypted.meshId != meshId) {
                    Log.d(TAG, "Ignoring message from different mesh: ${encrypted.meshId}")
                    return@launch
                }
                
                // Check for duplicates
                if (seenMessageIds.containsKey(encrypted.messageId)) {
                    Log.d(TAG, "Duplicate message: ${encrypted.messageId}")
                    return@launch
                }
                seenMessageIds[encrypted.messageId] = System.currentTimeMillis()
                
                // Verify signature
                val key = meshKey
                if (key != null) {
                    val signData = "${encrypted.messageId}:${encrypted.fromNodeId}:${encrypted.meshId}:${encrypted.timestamp}".toByteArray()
                    if (!MeshCrypto.verify(signData, encrypted.signature, key)) {
                        Log.w(TAG, "Invalid signature from ${encrypted.fromNodeId}")
                        return@launch
                    }
                }
                
                // Check if message is for us or broadcast
                val isForUs = encrypted.toNodeId == null || encrypted.toNodeId == nodeId
                
                if (isForUs) {
                    // Decrypt and deliver
                    val plaintext = if (key != null) {
                        MeshCrypto.decrypt(encrypted.encryptedPayload, encrypted.nonce, key)
                    } else {
                        encrypted.encryptedPayload
                    }
                    
                    if (plaintext != null) {
                        val decrypted = DecryptedMessage(
                            fromNodeId = encrypted.fromNodeId,
                            payload = plaintext,
                            hopCount = encrypted.hopCount,
                            timestamp = encrypted.timestamp
                        )
                        
                        _incomingMessages.emit(decrypted)
                        
                        // Also call the Transport interface handler
                        messageHandler?.invoke(plaintext)
                    }
                }
                
                // Forward if TTL > 1 and not a direct message to us
                if (encrypted.ttl > 1 && (encrypted.toNodeId == null || encrypted.toNodeId != nodeId)) {
                    val forwarded = encrypted.forward()
                    if (forwarded != null) {
                        bleTransport?.send(
                            payload = forwarded.toBytes(),
                            msgType = MessageType.DATA,
                            ttl = forwarded.ttl
                        )
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling BLE message: ${e.message}")
            }
        }
    }
    
    private fun broadcastHello() {
        if (!config.advertising) return
        
        scope.launch {
            val hello = JSONObject().apply {
                put("type", "hello")
                put("node_id", nodeId)
                put("mesh_id", meshId)
                put("platform", "android")
                put("capabilities", JSONArray(listOf("relay", "ble-mesh")))
            }.toString().toByteArray()
            
            broadcast(hello, ttl = 1) // Hello only 1 hop
        }
    }
    
    // ========================================================================
    // Peer Management
    // ========================================================================
    
    private fun updatePeers(info: NodeInfo) {
        val current = _discoveredPeers.value.toMutableList()
        val existing = current.indexOfFirst { it.nodeId == info.nodeId }
        
        if (existing >= 0) {
            current[existing] = info
        } else {
            current.add(info)
        }
        
        _discoveredPeers.value = current
    }
    
    private fun removePeer(peerId: String) {
        _discoveredPeers.value = _discoveredPeers.value.filter { it.nodeId != peerId }
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun getPeerCount(): Int = _discoveredPeers.value.size
    
    fun isRunning(): Boolean = _state.value == State.RUNNING
    
    companion object {
        /**
         * Check if device supports BLE.
         */
        fun isBleSupported(context: Context): Boolean {
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        }
        
        /**
         * Get required permissions for BLE mesh.
         */
        fun getRequiredPermissions(): Array<String> = BleTransport.getRequiredPermissions()
    }
}
