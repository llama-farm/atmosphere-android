package com.llamafarm.atmosphere.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.llamafarm.atmosphere.core.AtmosphereNative
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "WifiAwareManager"

/**
 * Wi-Fi Aware transport manager for Atmosphere mesh networking.
 * 
 * Implements peer discovery via publish/subscribe and data transfer
 * via Wi-Fi Aware Network Specifier + TCP sockets.
 * 
 * Requirements:
 * - Android 8.0 (API 26) or higher
 * - NEARBY_WIFI_DEVICES permission (API 33+)
 * - ACCESS_FINE_LOCATION permission
 * - Wi-Fi Aware hardware support
 */
@RequiresApi(Build.VERSION_CODES.O)
class WifiAwareManager(
    private val context: Context,
    private val atmosphereHandle: Long
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var awareManager: android.net.wifi.aware.WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable
    
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive
    
    // Discovered peers: PeerHandle -> DiscoveryInfo
    private val discoveredPeers = mutableMapOf<PeerHandle, PeerDiscoveryInfo>()
    
    // Active connections: PeerId -> Connection
    private val activeConnections = mutableMapOf<String, WifiAwareConnection>()
    
    // Service name for Atmosphere mesh
    private val serviceName = "atmosphere-mesh"
    
    // Match filter for discovering Atmosphere peers
    private val serviceSpecificInfo = "atmo".toByteArray()
    
    init {
        checkAvailability()
    }
    
    /**
     * Check if Wi-Fi Aware is available and permissions are granted.
     */
    private fun checkAvailability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _isAvailable.value = false
            return
        }
        
        awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? android.net.wifi.aware.WifiAwareManager
        
        val hasWifiAware = awareManager != null
        val hasPermissions = checkPermissions()
        
        _isAvailable.value = hasWifiAware && hasPermissions
        
        Log.i(TAG, "Wi-Fi Aware available: $hasWifiAware, permissions: $hasPermissions")
    }
    
    /**
     * Check required permissions.
     */
    private fun checkPermissions(): Boolean {
        val locationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val wifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        return locationPermission && wifiPermission
    }
    
    /**
     * Start Wi-Fi Aware session and begin discovery.
     */
    suspend fun start() = suspendCoroutine { continuation ->
        if (!_isAvailable.value) {
            continuation.resumeWithException(
                IllegalStateException("Wi-Fi Aware not available or permissions denied")
            )
            return@suspendCoroutine
        }
        
        val attachCallback = object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "Wi-Fi Aware session attached")
                wifiAwareSession = session
                _isSessionActive.value = true
                
                // Start both publish and subscribe for full mesh discovery
                startPublish(session)
                startSubscribe(session)
                
                continuation.resume(Unit)
            }
            
            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach failed")
                _isSessionActive.value = false
                continuation.resumeWithException(
                    RuntimeException("Failed to attach Wi-Fi Aware session")
                )
            }
        }
        
        awareManager?.attach(attachCallback, null)
    }
    
    /**
     * Start publishing our presence to the mesh.
     */
    private fun startPublish(session: WifiAwareSession) {
        val publishConfig = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(serviceSpecificInfo)
            .setTerminateNotificationEnabled(true)
            .build()
        
        val publishCallback = object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.i(TAG, "Wi-Fi Aware publish started")
                publishDiscoverySession = session
            }
            
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "Received message from peer: ${message.decodeToString()}")
                handleIncomingMessage(peerHandle, message)
            }
            
            override fun onSessionConfigFailed() {
                Log.e(TAG, "Publish session config failed")
            }
            
            override fun onSessionTerminated() {
                Log.w(TAG, "Publish session terminated")
                publishDiscoverySession = null
            }
        }
        
        session.publish(publishConfig, publishCallback, null)
    }
    
    /**
     * Start subscribing to discover other Atmosphere peers.
     */
    private fun startSubscribe(session: WifiAwareSession) {
        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(serviceSpecificInfo)
            .setTerminateNotificationEnabled(true)
            .build()
        
        val subscribeCallback = object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.i(TAG, "Wi-Fi Aware subscribe started")
                subscribeDiscoverySession = session
            }
            
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>
            ) {
                Log.i(TAG, "Discovered Wi-Fi Aware peer: $peerHandle")
                handlePeerDiscovered(peerHandle, serviceSpecificInfo)
            }
            
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(TAG, "Received message from peer: ${message.decodeToString()}")
                handleIncomingMessage(peerHandle, message)
            }
            
            override fun onSessionConfigFailed() {
                Log.e(TAG, "Subscribe session config failed")
            }
            
            override fun onSessionTerminated() {
                Log.w(TAG, "Subscribe session terminated")
                subscribeDiscoverySession = null
            }
        }
        
        session.subscribe(subscribeConfig, subscribeCallback, null)
    }
    
    /**
     * Handle peer discovery event.
     */
    private fun handlePeerDiscovered(peerHandle: PeerHandle, serviceInfo: ByteArray) {
        val peerInfo = PeerDiscoveryInfo(
            peerHandle = peerHandle,
            serviceInfo = serviceInfo,
            discoveredAt = System.currentTimeMillis()
        )
        
        discoveredPeers[peerHandle] = peerInfo
        
        // Notify Rust core about discovered peer
        scope.launch {
            try {
                AtmosphereNative.wifiAwarePeerDiscovered(
                    atmosphereHandle,
                    peerHandle.toString(),
                    serviceInfo.decodeToString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify Rust core about peer discovery", e)
            }
        }
        
        Log.i(TAG, "Peer discovered: $peerHandle")
    }
    
    /**
     * Handle incoming message from peer.
     */
    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        // Keepalive ping/pong intercept: 32-byte messages starting with APIN/APON
        if (message.size == 32 && message[0] == 0x41.toByte() && message[1] == 0x50.toByte()) {
            if (message[2] == 0x49.toByte() && message[3] == 0x4E.toByte()) {
                // APIN ping - reply with APON pong
                Log.d(TAG, "Keepalive ping from $peerHandle, sending pong")
                val pong = message.copyOf()
                pong[2] = 0x4F.toByte()  // I -> O
                sendMessage(peerHandle, pong)
                return
            }
            if (message[2] == 0x4F.toByte() && message[3] == 0x4E.toByte()) {
                // APON pong - ignore
                Log.d(TAG, "Keepalive pong from $peerHandle (ignored)")
                return
            }
        }

        scope.launch {
            try {
                val peerId = peerHandle.toString()
                AtmosphereNative.wifiAwareDataReceived(
                    atmosphereHandle,
                    peerId,
                    message
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify Rust core about received data", e)
            }
        }
    }
    
    /**
     * Connect to a discovered peer using Network Specifier.
     */
    suspend fun connectToPeer(peerHandle: PeerHandle, port: Int = 0): WifiAwareConnection? {
        val session = subscribeDiscoverySession ?: publishDiscoverySession
        if (session == null) {
            Log.e(TAG, "No active discovery session")
            return null
        }
        
        return try {
            createNetworkConnection(session, peerHandle, port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer", e)
            null
        }
    }
    
    /**
     * Create network connection using Wi-Fi Aware Network Specifier.
     */
    private suspend fun createNetworkConnection(
        session: DiscoverySession,
        peerHandle: PeerHandle,
        port: Int
    ): WifiAwareConnection = suspendCoroutine { continuation ->
        
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase("atmosphere-mesh-2024")
            .setPort(port)
            .build()
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
        
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.i(TAG, "Wi-Fi Aware network available: $network")
                
                val connection = WifiAwareConnection(
                    network = network,
                    peerHandle = peerHandle,
                    port = port
                )
                
                activeConnections[peerHandle.toString()] = connection
                
                continuation.resume(connection)
            }
            
            override fun onUnavailable() {
                Log.e(TAG, "Wi-Fi Aware network unavailable")
                continuation.resumeWithException(
                    RuntimeException("Network unavailable")
                )
            }
            
            override fun onLost(network: android.net.Network) {
                Log.w(TAG, "Wi-Fi Aware network lost: $network")
                activeConnections.remove(peerHandle.toString())
            }
        }
        
        connectivityManager.requestNetwork(networkRequest, networkCallback)
    }
    
    /**
     * Send data to a peer via discovered session.
     */
    fun sendMessage(peerHandle: PeerHandle, data: ByteArray) {
        val session = publishDiscoverySession ?: subscribeDiscoverySession
        if (session == null) {
            Log.e(TAG, "No active session to send message")
            return
        }
        
        session.sendMessage(peerHandle, 0, data)
        Log.d(TAG, "Sent ${data.size} bytes to peer $peerHandle")
    }
    
    /**
     * Stop Wi-Fi Aware session and cleanup.
     */
    fun stop() {
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        wifiAwareSession?.close()
        
        publishDiscoverySession = null
        subscribeDiscoverySession = null
        wifiAwareSession = null
        
        activeConnections.clear()
        discoveredPeers.clear()
        
        _isSessionActive.value = false
        
        scope.cancel()
        
        Log.i(TAG, "Wi-Fi Aware session stopped")
    }
    
    /**
     * Get list of discovered peers.
     */
    fun getDiscoveredPeers(): List<PeerDiscoveryInfo> {
        return discoveredPeers.values.toList()
    }
}

/**
 * Information about a discovered Wi-Fi Aware peer.
 */
data class PeerDiscoveryInfo(
    val peerHandle: PeerHandle,
    val serviceInfo: ByteArray,
    val discoveredAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerDiscoveryInfo) return false
        
        if (peerHandle != other.peerHandle) return false
        if (!serviceInfo.contentEquals(other.serviceInfo)) return false
        if (discoveredAt != other.discoveredAt) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = peerHandle.hashCode()
        result = 31 * result + serviceInfo.contentHashCode()
        result = 31 * result + discoveredAt.hashCode()
        return result
    }
}

/**
 * Wi-Fi Aware network connection wrapper.
 */
class WifiAwareConnection(
    val network: android.net.Network,
    val peerHandle: PeerHandle,
    val port: Int
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    /**
     * Open TCP socket over Wi-Fi Aware network.
     */
    suspend fun connect(remoteAddress: String, remotePort: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                socket = network.socketFactory.createSocket(remoteAddress, remotePort)
                inputStream = socket?.getInputStream()
                outputStream = socket?.getOutputStream()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open socket", e)
                false
            }
        }
    }
    
    /**
     * Send data over the connection.
     */
    suspend fun send(data: ByteArray) {
        withContext(Dispatchers.IO) {
            outputStream?.write(data)
            outputStream?.flush()
        }
    }
    
    /**
     * Receive data from the connection.
     */
    suspend fun receive(buffer: ByteArray): Int {
        return withContext(Dispatchers.IO) {
            inputStream?.read(buffer) ?: -1
        }
    }
    
    /**
     * Close the connection.
     */
    fun close() {
        inputStream?.close()
        outputStream?.close()
        socket?.close()
    }
}
