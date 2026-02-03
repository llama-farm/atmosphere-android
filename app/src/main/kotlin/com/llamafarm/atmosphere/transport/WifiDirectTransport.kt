package com.llamafarm.atmosphere.transport

import android.content.Context
import android.os.Build
import android.util.Log
import com.llamafarm.atmosphere.network.Transport
import com.llamafarm.atmosphere.network.TransportConfig
import com.llamafarm.atmosphere.network.TransportMetrics
import com.llamafarm.atmosphere.network.TransportType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WifiDirectTransport"

// ============================================================================
// Socket Message Protocol
// ============================================================================

/**
 * Simple framing protocol for socket messages:
 * - 4 bytes: message length (big-endian)
 * - N bytes: message payload
 */
object SocketProtocol {
    const val HEADER_SIZE = 4
    const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1MB
    
    fun encodeMessage(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }
    
    fun readMessage(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw IOException("Invalid message length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }
}

// ============================================================================
// Peer Socket Connection
// ============================================================================

/**
 * Represents a socket connection to a WiFi Direct peer.
 */
class PeerSocketConnection(
    private val socket: Socket,
    private val peerId: String,
    private val onMessage: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val output: DataOutputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val input: DataInputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
    
    private val running = AtomicBoolean(true)
    private val sendQueue = kotlinx.coroutines.channels.Channel<ByteArray>(64)
    
    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed && running.get()
    val address: InetAddress get() = socket.inetAddress
    val port: Int get() = socket.port
    
    fun start() {
        // Reader coroutine
        scope.launch {
            try {
                while (running.get() && !socket.isClosed) {
                    val message = SocketProtocol.readMessage(input)
                    onMessage(message)
                }
            } catch (e: EOFException) {
                Log.d(TAG, "Connection closed by peer: $peerId")
            } catch (e: SocketException) {
                if (running.get()) {
                    Log.w(TAG, "Socket error for $peerId: ${e.message}")
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Read error for $peerId: ${e.message}")
                }
            } finally {
                close()
            }
        }
        
        // Writer coroutine
        scope.launch {
            try {
                for (message in sendQueue) {
                    if (!running.get()) break
                    val encoded = SocketProtocol.encodeMessage(message)
                    output.write(encoded)
                    output.flush()
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Write error for $peerId: ${e.message}")
                }
            } finally {
                close()
            }
        }
        
        Log.i(TAG, "Started connection to $peerId (${socket.inetAddress}:${socket.port})")
    }
    
    suspend fun send(message: ByteArray): Boolean {
        if (!isConnected) return false
        return try {
            sendQueue.send(message)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue message for $peerId: ${e.message}")
            false
        }
    }
    
    fun close() {
        if (!running.getAndSet(false)) return
        
        try {
            sendQueue.close()
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection to $peerId: ${e.message}")
        }
        
        scope.cancel()
        onDisconnected()
        Log.d(TAG, "Closed connection to $peerId")
    }
}

// ============================================================================
// WiFi Direct Transport
// ============================================================================

/**
 * WiFi Direct Transport for Atmosphere mesh.
 * 
 * Uses WiFi P2P for discovery and group formation, then establishes
 * socket connections for actual message passing.
 * 
 * Architecture:
 * - Group Owner: Runs a server socket accepting connections from clients
 * - Clients: Connect to the group owner's server socket
 * - Messages are forwarded through the group owner for mesh communication
 */
class WifiDirectTransport(
    private val context: Context,
    private val nodeId: String,
    private val config: TransportConfig.WifiDirectConfig = TransportConfig.WifiDirectConfig()
) : Transport {
    
    override val type = TransportType.WIFI_DIRECT
    override var connected = false
    override val metrics = TransportMetrics()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // WiFi Direct manager for P2P operations
    private lateinit var wifiDirectManager: WifiDirectManager
    
    // Socket connections
    private var serverSocket: ServerSocket? = null
    private val peerConnections = ConcurrentHashMap<String, PeerSocketConnection>()
    
    // State
    private var currentMeshId: String? = null
    private var isGroupOwner = false
    private var messageHandler: ((ByteArray) -> Unit)? = null
    
    // Connection state
    private val _connectionState = MutableStateFlow<WifiDirectState>(WifiDirectState.IDLE)
    val connectionState: StateFlow<WifiDirectState> = _connectionState.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()
    
    // Callbacks for external notifications
    var onPeerConnected: ((String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    
    // ========================================================================
    // Transport Interface Implementation
    // ========================================================================
    
    override fun onMessage(handler: (ByteArray) -> Unit) {
        messageHandler = handler
    }
    
    override suspend fun connect(config: Any): Boolean {
        if (config !is WifiDirectConnectParams) {
            Log.e(TAG, "Invalid config type for WifiDirectTransport")
            return false
        }
        
        return connectToMesh(
            meshId = config.meshId,
            createGroup = config.createGroup
        )
    }
    
    override suspend fun disconnect() {
        stop()
    }
    
    override suspend fun send(message: ByteArray): Boolean {
        if (!connected || peerConnections.isEmpty()) return false
        
        var sent = false
        val start = System.currentTimeMillis()
        
        // Send to all connected peers
        for ((peerId, connection) in peerConnections) {
            if (connection.isConnected) {
                try {
                    if (connection.send(message)) {
                        sent = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to $peerId: ${e.message}")
                }
            }
        }
        
        val latency = (System.currentTimeMillis() - start).toFloat()
        metrics.addSample(latency, sent)
        
        return sent
    }
    
    /**
     * Send to a specific peer by ID.
     */
    suspend fun sendToPeer(peerId: String, message: ByteArray): Boolean {
        val connection = peerConnections[peerId]
        if (connection == null || !connection.isConnected) {
            return false
        }
        
        val start = System.currentTimeMillis()
        val success = connection.send(message)
        val latency = (System.currentTimeMillis() - start).toFloat()
        metrics.addSample(latency, success)
        
        return success
    }
    
    override suspend fun probe(): Float {
        if (!connected || peerConnections.isEmpty()) {
            return Float.MAX_VALUE
        }
        
        val pingMsg = JSONObject().apply {
            put("type", "ping")
            put("timestamp", System.currentTimeMillis())
        }.toString().toByteArray()
        
        val start = System.currentTimeMillis()
        val success = send(pingMsg)
        val latency = (System.currentTimeMillis() - start).toFloat()
        
        metrics.addSample(latency, success)
        return if (success) latency else Float.MAX_VALUE
    }
    
    // ========================================================================
    // Lifecycle
    // ========================================================================
    
    /**
     * Initialize the WiFi Direct transport.
     */
    fun initialize(): Boolean {
        if (!WifiDirectManager.isSupported(context)) {
            Log.e(TAG, "WiFi Direct not supported on this device")
            return false
        }
        
        wifiDirectManager = WifiDirectManager(
            context = context,
            nodeId = nodeId,
            nodeName = "Atmosphere-${Build.MODEL.take(8)}"
        )
        
        if (!wifiDirectManager.initialize()) {
            Log.e(TAG, "Failed to initialize WiFi Direct manager")
            return false
        }
        
        setupWifiDirectCallbacks()
        Log.i(TAG, "WiFi Direct transport initialized")
        return true
    }
    
    private fun setupWifiDirectCallbacks() {
        wifiDirectManager.onPeerDiscovered = { peer ->
            Log.i(TAG, "Peer discovered: ${peer.deviceName} (${peer.nodeId})")
            
            // Auto-connect if in same mesh and auto_accept enabled
            if (config.autoAccept && peer.meshId == currentMeshId) {
                scope.launch {
                    wifiDirectManager.connectToPeer(peer)
                }
            }
        }
        
        wifiDirectManager.onConnected = { group ->
            isGroupOwner = group.isOwner
            
            scope.launch {
                if (isGroupOwner) {
                    // Start server socket as group owner
                    startServerSocket()
                } else {
                    // Connect to group owner as client
                    group.ownerAddress?.let { address ->
                        connectToGroupOwner(address)
                    }
                }
            }
        }
        
        wifiDirectManager.onDisconnected = {
            scope.launch {
                handleDisconnection()
            }
        }
        
        wifiDirectManager.onError = { error ->
            Log.e(TAG, "WiFi Direct error: $error")
            _connectionState.value = WifiDirectState.FAILED
        }
        
        // Mirror state from manager
        scope.launch {
            wifiDirectManager.state.collect { state ->
                _connectionState.value = state
                connected = state == WifiDirectState.CONNECTED || 
                           state == WifiDirectState.GROUP_OWNER
            }
        }
    }
    
    /**
     * Connect to a mesh via WiFi Direct.
     */
    suspend fun connectToMesh(meshId: String, createGroup: Boolean = false): Boolean {
        currentMeshId = meshId
        
        // Initialize if needed
        if (!::wifiDirectManager.isInitialized && !initialize()) {
            return false
        }
        
        return if (createGroup) {
            // Become group owner
            wifiDirectManager.createGroup(meshId)
            true
        } else {
            // Discover and join existing group
            wifiDirectManager.startDiscovery(meshId)
            true
        }
    }
    
    /**
     * Stop the WiFi Direct transport.
     */
    fun stop() {
        connected = false
        
        // Close all peer connections
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        
        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        
        // Cleanup WiFi Direct
        if (::wifiDirectManager.isInitialized) {
            wifiDirectManager.removeGroup()
            wifiDirectManager.stopDiscovery()
            wifiDirectManager.cleanup()
        }
        
        _connectedPeers.value = emptyList()
        _connectionState.value = WifiDirectState.IDLE
        
        scope.cancel()
        Log.i(TAG, "WiFi Direct transport stopped")
    }
    
    // ========================================================================
    // Server Socket (Group Owner)
    // ========================================================================
    
    private suspend fun startServerSocket() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(ATMOSPHERE_PORT).apply {
                reuseAddress = true
                soTimeout = 0 // No timeout, wait indefinitely
            }
            
            Log.i(TAG, "Server socket listening on port $ATMOSPHERE_PORT")
            connected = true
            
            // Accept connections
            scope.launch {
                acceptConnections()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server socket: ${e.message}")
            connected = false
        }
    }
    
    private suspend fun acceptConnections() = withContext(Dispatchers.IO) {
        while (connected && serverSocket != null && !serverSocket!!.isClosed) {
            try {
                val clientSocket = serverSocket!!.accept()
                handleNewConnection(clientSocket)
            } catch (e: SocketException) {
                if (connected) {
                    Log.w(TAG, "Server socket error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection: ${e.message}")
            }
        }
    }
    
    private fun handleNewConnection(socket: Socket) {
        val peerAddress = socket.inetAddress.hostAddress ?: return
        val peerId = "p2p-$peerAddress"
        
        Log.i(TAG, "New connection from: $peerAddress")
        
        val connection = PeerSocketConnection(
            socket = socket,
            peerId = peerId,
            onMessage = { message ->
                handleIncomingMessage(peerId, message)
            },
            onDisconnected = {
                handlePeerDisconnected(peerId)
            }
        )
        
        peerConnections[peerId] = connection
        connection.start()
        
        updateConnectedPeers()
        onPeerConnected?.invoke(peerId)
        
        // Send handshake
        scope.launch {
            sendHandshake(connection)
        }
    }
    
    // ========================================================================
    // Client Socket (Group Member)
    // ========================================================================
    
    private suspend fun connectToGroupOwner(address: InetAddress) = withContext(Dispatchers.IO) {
        val maxRetries = 5
        var retryCount = 0
        var lastError: Exception? = null
        
        while (retryCount < maxRetries && !connected) {
            try {
                // Wait a bit for GO to start server socket
                delay(1000L * (retryCount + 1))
                
                Log.i(TAG, "Connecting to group owner at ${address.hostAddress}:$ATMOSPHERE_PORT (attempt ${retryCount + 1})")
                
                val socket = Socket()
                socket.connect(
                    InetSocketAddress(address, ATMOSPHERE_PORT),
                    10000 // 10 second timeout
                )
                
                val peerId = "go-${address.hostAddress}"
                val connection = PeerSocketConnection(
                    socket = socket,
                    peerId = peerId,
                    onMessage = { message ->
                        handleIncomingMessage(peerId, message)
                    },
                    onDisconnected = {
                        handlePeerDisconnected(peerId)
                    }
                )
                
                peerConnections[peerId] = connection
                connection.start()
                
                connected = true
                _connectionState.value = WifiDirectState.CONNECTED
                updateConnectedPeers()
                onPeerConnected?.invoke(peerId)
                
                // Send handshake
                sendHandshake(connection)
                
                Log.i(TAG, "Connected to group owner: ${address.hostAddress}")
                return@withContext
                
            } catch (e: Exception) {
                lastError = e
                retryCount++
                Log.w(TAG, "Connection attempt $retryCount failed: ${e.message}")
            }
        }
        
        Log.e(TAG, "Failed to connect to group owner after $maxRetries attempts: ${lastError?.message}")
        _connectionState.value = WifiDirectState.FAILED
    }
    
    private suspend fun sendHandshake(connection: PeerSocketConnection) {
        val handshake = JSONObject().apply {
            put("type", "handshake")
            put("node_id", nodeId)
            put("mesh_id", currentMeshId)
            put("platform", "Android")
            put("version", "1.0")
            put("timestamp", System.currentTimeMillis())
        }.toString().toByteArray()
        
        connection.send(handshake)
    }
    
    // ========================================================================
    // Message Handling
    // ========================================================================
    
    private fun handleIncomingMessage(fromPeerId: String, data: ByteArray) {
        try {
            // Parse message to check type
            val json = JSONObject(String(data))
            val msgType = json.optString("type")
            
            when (msgType) {
                "ping" -> handlePing(fromPeerId, json)
                "pong" -> handlePong(fromPeerId, json)
                "handshake" -> handleHandshake(fromPeerId, json)
                else -> {
                    // Forward to message handler
                    messageHandler?.invoke(data)
                    
                    // If we're group owner, forward to other peers
                    if (isGroupOwner && peerConnections.size > 1) {
                        forwardMessage(fromPeerId, data)
                    }
                }
            }
        } catch (e: Exception) {
            // Not JSON or parse error, forward as-is
            messageHandler?.invoke(data)
        }
    }
    
    private fun handlePing(fromPeerId: String, json: JSONObject) {
        val timestamp = json.optLong("timestamp")
        
        scope.launch {
            val pong = JSONObject().apply {
                put("type", "pong")
                put("original_timestamp", timestamp)
                put("timestamp", System.currentTimeMillis())
            }.toString().toByteArray()
            
            sendToPeer(fromPeerId, pong)
        }
    }
    
    private fun handlePong(fromPeerId: String, json: JSONObject) {
        val originalTimestamp = json.optLong("original_timestamp")
        val latency = System.currentTimeMillis() - originalTimestamp
        metrics.addSample(latency.toFloat(), true)
        Log.d(TAG, "Pong from $fromPeerId, latency: ${latency}ms")
    }
    
    private fun handleHandshake(fromPeerId: String, json: JSONObject) {
        val peerNodeId = json.optString("node_id")
        val peerMeshId = json.optString("mesh_id")
        Log.i(TAG, "Handshake from $fromPeerId: node=$peerNodeId, mesh=$peerMeshId")
    }
    
    private fun forwardMessage(fromPeerId: String, data: ByteArray) {
        scope.launch {
            for ((peerId, connection) in peerConnections) {
                if (peerId != fromPeerId && connection.isConnected) {
                    try {
                        connection.send(data)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to forward to $peerId: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun handlePeerDisconnected(peerId: String) {
        peerConnections.remove(peerId)
        updateConnectedPeers()
        onPeerDisconnected?.invoke(peerId)
        
        // Check if we lost all connections
        if (peerConnections.isEmpty() && !isGroupOwner) {
            connected = false
            _connectionState.value = WifiDirectState.IDLE
        }
        
        Log.i(TAG, "Peer disconnected: $peerId")
    }
    
    private suspend fun handleDisconnection() {
        // Close all peer connections
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        
        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        
        connected = false
        isGroupOwner = false
        _connectionState.value = WifiDirectState.IDLE
        _connectedPeers.value = emptyList()
        
        Log.i(TAG, "Disconnected from WiFi Direct group")
    }
    
    private fun updateConnectedPeers() {
        _connectedPeers.value = peerConnections.keys.toList()
    }
    
    // ========================================================================
    // Public API
    // ========================================================================
    
    /**
     * Get discovered WiFi Direct peers.
     */
    val discoveredPeers: StateFlow<List<WifiDirectPeer>>
        get() = if (::wifiDirectManager.isInitialized) {
            wifiDirectManager.peers
        } else {
            MutableStateFlow(emptyList())
        }
    
    /**
     * Get current group information.
     */
    val currentGroup: StateFlow<WifiDirectGroup?>
        get() = if (::wifiDirectManager.isInitialized) {
            wifiDirectManager.currentGroup
        } else {
            MutableStateFlow(null)
        }
    
    /**
     * Manually connect to a discovered peer.
     */
    fun connectToPeer(peer: WifiDirectPeer) {
        if (::wifiDirectManager.isInitialized) {
            wifiDirectManager.connectToPeer(peer)
        }
    }
    
    /**
     * Get peer count.
     */
    fun getPeerCount(): Int = peerConnections.count { it.value.isConnected }
    
    companion object {
        /**
         * Required permissions.
         */
        fun getRequiredPermissions(): Array<String> = WifiDirectManager.getRequiredPermissions()
        
        /**
         * Check if WiFi Direct is supported.
         */
        fun isSupported(context: Context): Boolean = WifiDirectManager.isSupported(context)
    }
}

/**
 * Parameters for WiFi Direct connection.
 */
data class WifiDirectConnectParams(
    val meshId: String,
    val createGroup: Boolean = false,
    val autoAccept: Boolean = false
)
