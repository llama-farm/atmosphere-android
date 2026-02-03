package com.llamafarm.atmosphere.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WifiDirectManager"

// ============================================================================
// Data Classes
// ============================================================================

/**
 * Information about a discovered WiFi Direct peer.
 */
data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val meshId: String?,
    val nodeId: String?,
    val port: Int = ATMOSPHERE_PORT,
    val isGroupOwner: Boolean = false,
    var rssi: Int = 0,
    var lastSeen: Long = System.currentTimeMillis()
)

/**
 * WiFi Direct group information.
 */
data class WifiDirectGroup(
    val networkName: String,
    val passphrase: String,
    val ownerAddress: InetAddress?,
    val isOwner: Boolean,
    val clients: List<WifiP2pDevice> = emptyList()
)

/**
 * WiFi Direct connection state.
 */
enum class WifiDirectState {
    DISABLED,           // WiFi Direct not available
    IDLE,               // Available but not connected
    DISCOVERING,        // Scanning for peers
    CONNECTING,         // Connecting to a peer
    CONNECTED,          // Connected to a group
    GROUP_OWNER,        // Acting as group owner
    FAILED              // Connection failed
}

/**
 * Constants for Atmosphere WiFi Direct.
 */
const val ATMOSPHERE_PORT = 11452
const val ATMOSPHERE_SERVICE_TYPE = "_atmosphere._tcp"
const val ATMOSPHERE_SERVICE_NAME = "Atmosphere"

// ============================================================================
// WiFi Direct Manager
// ============================================================================

/**
 * Manages WiFi Direct P2P operations for Atmosphere mesh networking.
 * 
 * Handles:
 * - Peer discovery using DNS-SD service advertising
 * - Group creation and joining with "atmosphere_{mesh_id}" naming
 * - Connection management
 * - State broadcasting
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(
    private val context: Context,
    private val nodeId: String,
    private val nodeName: String = "Atmosphere-${Build.MODEL.take(8)}"
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WiFiDirectBroadcastReceiver? = null
    
    // Current mesh ID (set when joining/creating)
    private var currentMeshId: String? = null
    
    // Discovered peers
    private val discoveredPeers = ConcurrentHashMap<String, WifiDirectPeer>()
    
    // Service discovery request (needs to be tracked for removal)
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    
    // ========================================================================
    // State Flows
    // ========================================================================
    
    private val _state = MutableStateFlow(WifiDirectState.DISABLED)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()
    
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    private val _peers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val peers: StateFlow<List<WifiDirectPeer>> = _peers.asStateFlow()
    
    private val _currentGroup = MutableStateFlow<WifiDirectGroup?>(null)
    val currentGroup: StateFlow<WifiDirectGroup?> = _currentGroup.asStateFlow()
    
    private val _groupOwnerAddress = MutableStateFlow<InetAddress?>(null)
    val groupOwnerAddress: StateFlow<InetAddress?> = _groupOwnerAddress.asStateFlow()
    
    private val _thisDeviceInfo = MutableStateFlow<WifiP2pDevice?>(null)
    val thisDeviceInfo: StateFlow<WifiP2pDevice?> = _thisDeviceInfo.asStateFlow()
    
    // ========================================================================
    // Callbacks
    // ========================================================================
    
    var onPeerDiscovered: ((WifiDirectPeer) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    var onConnected: ((WifiDirectGroup) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // ========================================================================
    // Lifecycle
    // ========================================================================
    
    /**
     * Initialize WiFi Direct manager.
     */
    fun initialize(): Boolean {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for WiFi Direct")
            return false
        }
        
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported on this device")
            _state.value = WifiDirectState.DISABLED
            return false
        }
        
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) { 
            Log.w(TAG, "WiFi P2P channel disconnected")
            _state.value = WifiDirectState.DISABLED
        }
        
        if (channel == null) {
            Log.e(TAG, "Failed to initialize WiFi P2P channel")
            return false
        }
        
        // Register broadcast receiver
        registerReceiver()
        
        _state.value = WifiDirectState.IDLE
        Log.i(TAG, "WiFi Direct manager initialized")
        return true
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stopDiscovery()
        stopLocalService()
        removeGroup()
        unregisterReceiver()
        
        channel?.close()
        channel = null
        wifiP2pManager = null
        
        discoveredPeers.clear()
        _peers.value = emptyList()
        _currentGroup.value = null
        _state.value = WifiDirectState.DISABLED
        
        scope.cancel()
        Log.i(TAG, "WiFi Direct manager cleaned up")
    }
    
    private fun hasRequiredPermissions(): Boolean {
        val hasLocation = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasWifiState = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasChangeWifi = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasNearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        return hasLocation && hasWifiState && hasChangeWifi && hasNearbyDevices
    }
    
    // ========================================================================
    // Broadcast Receiver
    // ========================================================================
    
    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        receiver = WiFiDirectBroadcastReceiver()
        context.registerReceiver(receiver, intentFilter)
    }
    
    private fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        receiver = null
    }
    
    private inner class WiFiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _isEnabled.value = enabled
                    
                    if (!enabled) {
                        _state.value = WifiDirectState.DISABLED
                        Log.w(TAG, "WiFi Direct disabled")
                    } else if (_state.value == WifiDirectState.DISABLED) {
                        _state.value = WifiDirectState.IDLE
                        Log.i(TAG, "WiFi Direct enabled")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }
                
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        _state.value = WifiDirectState.IDLE
                        _currentGroup.value = null
                        _groupOwnerAddress.value = null
                        onDisconnected?.invoke()
                        Log.i(TAG, "Disconnected from WiFi Direct group")
                    }
                }
                
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    _thisDeviceInfo.value = device
                    Log.d(TAG, "This device: ${device?.deviceName} (${device?.deviceAddress})")
                }
            }
        }
    }
    
    // ========================================================================
    // Discovery
    // ========================================================================
    
    /**
     * Start discovering Atmosphere peers using DNS-SD.
     */
    fun startDiscovery(meshId: String) {
        currentMeshId = meshId
        _state.value = WifiDirectState.DISCOVERING
        
        // Setup DNS-SD service discovery
        setupServiceDiscovery(meshId)
        
        // Start discovery
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started for mesh: $meshId")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: ${reasonToString(reason)}")
                _state.value = WifiDirectState.IDLE
                onError?.invoke("Discovery failed: ${reasonToString(reason)}")
            }
        })
        
        // Start local service advertising
        startLocalService(meshId)
    }
    
    /**
     * Stop peer discovery.
     */
    fun stopDiscovery() {
        wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery stopped")
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to stop discovery: ${reasonToString(reason)}")
            }
        })
        
        // Remove service request
        serviceRequest?.let { req ->
            wifiP2pManager?.removeServiceRequest(channel, req, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Service request removed")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to remove service request: ${reasonToString(reason)}")
                }
            })
        }
        serviceRequest = null
        
        if (_state.value == WifiDirectState.DISCOVERING) {
            _state.value = WifiDirectState.IDLE
        }
    }
    
    private fun setupServiceDiscovery(meshId: String) {
        // DNS-SD TXT record listener
        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device ->
            Log.d(TAG, "DNS-SD TXT record: $fullDomain from ${device.deviceName}")
            
            // Check if this is an Atmosphere service for our mesh
            val peerMeshId = record["mesh_id"]
            val peerNodeId = record["node_id"]
            val port = record["port"]?.toIntOrNull() ?: ATMOSPHERE_PORT
            
            if (peerMeshId == meshId) {
                val peer = WifiDirectPeer(
                    deviceAddress = device.deviceAddress,
                    deviceName = device.deviceName,
                    meshId = peerMeshId,
                    nodeId = peerNodeId,
                    port = port,
                    isGroupOwner = device.isGroupOwner,
                    lastSeen = System.currentTimeMillis()
                )
                
                discoveredPeers[device.deviceAddress] = peer
                updatePeersList()
                onPeerDiscovered?.invoke(peer)
                
                Log.i(TAG, "Found Atmosphere peer: ${peer.deviceName} (${peer.nodeId})")
            }
        }
        
        // DNS-SD service response listener
        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
            Log.d(TAG, "DNS-SD service: $instanceName ($registrationType) from ${device.deviceName}")
        }
        
        wifiP2pManager?.setDnsSdResponseListeners(channel, serviceListener, txtListener)
        
        // Create service request for Atmosphere services
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(ATMOSPHERE_SERVICE_TYPE)
        
        wifiP2pManager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Service request added")
                
                // Start service discovery
                wifiP2pManager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Service discovery started")
                    }
                    
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Service discovery failed: ${reasonToString(reason)}")
                    }
                })
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to add service request: ${reasonToString(reason)}")
            }
        })
    }
    
    private fun requestPeers() {
        wifiP2pManager?.requestPeers(channel) { peerList ->
            val currentPeers = peerList?.deviceList?.toList() ?: emptyList()
            
            // Update our discovered peers with any new WiFi Direct peers
            for (device in currentPeers) {
                if (!discoveredPeers.containsKey(device.deviceAddress)) {
                    // Basic peer without service info (will be updated by DNS-SD)
                    discoveredPeers[device.deviceAddress] = WifiDirectPeer(
                        deviceAddress = device.deviceAddress,
                        deviceName = device.deviceName,
                        meshId = null,
                        nodeId = null,
                        isGroupOwner = device.isGroupOwner,
                        lastSeen = System.currentTimeMillis()
                    )
                }
            }
            
            // Remove peers that are no longer visible
            val currentAddresses = currentPeers.map { it.deviceAddress }.toSet()
            discoveredPeers.keys.filter { it !in currentAddresses }.forEach { address ->
                discoveredPeers.remove(address)
                onPeerLost?.invoke(address)
            }
            
            updatePeersList()
            Log.d(TAG, "Peers updated: ${discoveredPeers.size} peers")
        }
    }
    
    private fun updatePeersList() {
        _peers.value = discoveredPeers.values.toList()
    }
    
    // ========================================================================
    // Service Advertising
    // ========================================================================
    
    private fun startLocalService(meshId: String) {
        // Create TXT record with our mesh info
        val txtRecord = mapOf(
            "mesh_id" to meshId,
            "node_id" to nodeId,
            "name" to nodeName,
            "port" to ATMOSPHERE_PORT.toString(),
            "version" to "1.0"
        )
        
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            "atmosphere_$meshId",  // Instance name
            ATMOSPHERE_SERVICE_TYPE,  // Service type
            txtRecord
        )
        
        wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Local service registered for mesh: $meshId")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to register local service: ${reasonToString(reason)}")
            }
        })
    }
    
    private fun stopLocalService() {
        wifiP2pManager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Local services cleared")
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to clear local services: ${reasonToString(reason)}")
            }
        })
    }
    
    // ========================================================================
    // Group Management
    // ========================================================================
    
    /**
     * Create a WiFi Direct group (become group owner).
     * The group name will be "atmosphere_{meshId}".
     */
    fun createGroup(meshId: String) {
        currentMeshId = meshId
        _state.value = WifiDirectState.CONNECTING
        
        wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group creation initiated for mesh: $meshId")
                _state.value = WifiDirectState.GROUP_OWNER
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to create group: ${reasonToString(reason)}")
                _state.value = WifiDirectState.FAILED
                onError?.invoke("Failed to create group: ${reasonToString(reason)}")
            }
        })
        
        // Start advertising after creating group
        startLocalService(meshId)
    }
    
    /**
     * Connect to a discovered peer (join their group or have them join ours).
     */
    fun connectToPeer(peer: WifiDirectPeer) {
        _state.value = WifiDirectState.CONNECTING
        
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0  // Prefer to be client
        }
        
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connection initiated to: ${peer.deviceName}")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to connect to ${peer.deviceName}: ${reasonToString(reason)}")
                _state.value = WifiDirectState.IDLE
                onError?.invoke("Connection failed: ${reasonToString(reason)}")
            }
        })
    }
    
    /**
     * Join a specific group by device address.
     */
    fun joinGroup(deviceAddress: String, groupOwnerIntent: Int = 0) {
        _state.value = WifiDirectState.CONNECTING
        
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            this.groupOwnerIntent = groupOwnerIntent
        }
        
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Join group initiated to: $deviceAddress")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to join group: ${reasonToString(reason)}")
                _state.value = WifiDirectState.IDLE
                onError?.invoke("Join group failed: ${reasonToString(reason)}")
            }
        })
    }
    
    /**
     * Remove current WiFi Direct group.
     */
    fun removeGroup() {
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group removed")
                _state.value = WifiDirectState.IDLE
                _currentGroup.value = null
                _groupOwnerAddress.value = null
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to remove group: ${reasonToString(reason)}")
            }
        })
    }
    
    /**
     * Cancel ongoing connection attempt.
     */
    fun cancelConnect() {
        wifiP2pManager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connection cancelled")
                _state.value = WifiDirectState.IDLE
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to cancel connection: ${reasonToString(reason)}")
            }
        })
    }
    
    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            if (info?.groupFormed == true) {
                val isOwner = info.isGroupOwner
                val ownerAddress = info.groupOwnerAddress
                
                _groupOwnerAddress.value = ownerAddress
                _state.value = if (isOwner) WifiDirectState.GROUP_OWNER else WifiDirectState.CONNECTED
                
                Log.i(TAG, "Connected to group. Owner: $isOwner, GO address: $ownerAddress")
                
                // Request group info for more details
                requestGroupInfo()
            }
        }
    }
    
    private fun requestGroupInfo() {
        wifiP2pManager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                val groupInfo = WifiDirectGroup(
                    networkName = group.networkName,
                    passphrase = group.passphrase,
                    ownerAddress = _groupOwnerAddress.value,
                    isOwner = group.isGroupOwner,
                    clients = group.clientList.toList()
                )
                
                _currentGroup.value = groupInfo
                onConnected?.invoke(groupInfo)
                
                Log.i(TAG, "Group info: ${group.networkName}, " +
                    "owner=${group.isGroupOwner}, clients=${group.clientList.size}")
            }
        }
    }
    
    // ========================================================================
    // Utilities
    // ========================================================================
    
    private fun reasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "UNKNOWN ($reason)"
    }
    
    /**
     * Get discovered peer by device address.
     */
    fun getPeer(deviceAddress: String): WifiDirectPeer? = discoveredPeers[deviceAddress]
    
    /**
     * Get all Atmosphere peers (with mesh_id).
     */
    fun getAtmospherePeers(): List<WifiDirectPeer> = 
        discoveredPeers.values.filter { it.meshId != null }
    
    /**
     * Get peers for a specific mesh.
     */
    fun getPeersForMesh(meshId: String): List<WifiDirectPeer> =
        discoveredPeers.values.filter { it.meshId == meshId }
    
    companion object {
        /**
         * Required permissions for WiFi Direct.
         */
        fun getRequiredPermissions(): Array<String> {
            val base = arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base + Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                base
            }
        }
        
        /**
         * Check if WiFi Direct is supported on this device.
         */
        fun isSupported(context: Context): Boolean {
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        }
    }
}
