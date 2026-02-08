package com.llamafarm.atmosphere.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LanDiscovery"
private const val SERVICE_TYPE = "_atmosphere._tcp."

/**
 * Discovered Atmosphere peer on the local network via mDNS/NSD.
 */
data class LanPeer(
    val nodeId: String,
    val name: String,
    val host: String,
    val port: Int,
    val meshId: String?
) {
    val wsUrl: String get() = "ws://$host:$port/api/mesh/ws"
    val httpUrl: String get() = "http://$host:$port"
}

/**
 * Uses Android NSD (mDNS) to discover Atmosphere nodes on the local network.
 * 
 * Advertises `_atmosphere._tcp.local.` and discovers peers.
 * When a peer is found with a matching mesh_id, the service can connect
 * directly via LAN WebSocket instead of going through the cloud relay.
 */
class LanDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private val _peers = MutableStateFlow<Map<String, LanPeer>>(emptyMap())
    val peers: StateFlow<Map<String, LanPeer>> = _peers.asStateFlow()
    
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    
    var onPeerFound: ((LanPeer) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    
    // NSD resolveService can only handle one at a time (FAILURE_ALREADY_ACTIVE = 3)
    private val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    
    /**
     * Start discovering Atmosphere peers on the local network.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering")
            return
        }
        
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "🔍 LAN discovery started for $serviceType")
                isDiscovering = true
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "🏠 Found service: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                // Resolve to get host/port/properties
                resolveService(serviceInfo)
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "👋 Service lost: ${serviceInfo.serviceName}")
                val current = _peers.value.toMutableMap()
                val lost = current.entries.find { it.value.name == serviceInfo.serviceName }
                if (lost != null) {
                    current.remove(lost.key)
                    _peers.value = current
                    onPeerLost?.invoke(lost.key)
                }
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "LAN discovery stopped")
                isDiscovering = false
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Discovery start failed: error=$errorCode")
                isDiscovering = false
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Discovery stop failed: error=$errorCode")
            }
        }
        
        discoveryListener = listener
        
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery: ${e.message}", e)
        }
    }
    
    /**
     * Stop discovery.
     */
    fun stopDiscovery() {
        if (!isDiscovering) return
        
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            }
        }
        discoveryListener = null
        isDiscovering = false
    }
    
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processResolveQueue()
    }
    
    private fun processResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        resolveServiceInternal(next)
    }
    
    private fun resolveServiceInternal(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Failed to resolve ${si.serviceName}: error=$errorCode")
                resolving.set(false)
                processResolveQueue()
            }
            
            override fun onServiceResolved(si: NsdServiceInfo) {
                val host = si.host?.hostAddress
                if (host == null) {
                    resolving.set(false)
                    processResolveQueue()
                    return
                }
                val port = si.port
                
                // Extract properties (TXT records)
                val attrs = si.attributes
                val nodeId = attrs["node_id"]?.let { String(it) } ?: ""
                val meshId = attrs["mesh_id"]?.let { String(it) }?.takeIf { it.isNotEmpty() }
                
                if (nodeId.isEmpty()) {
                    Log.w(TAG, "Resolved ${si.serviceName} but no node_id in TXT records")
                    resolving.set(false)
                    processResolveQueue()
                    return
                }
                
                val peer = LanPeer(
                    nodeId = nodeId,
                    name = si.serviceName,
                    host = host,
                    port = port,
                    meshId = meshId
                )
                
                Log.i(TAG, "✅ Resolved LAN peer: ${peer.name} at ${peer.host}:${peer.port} mesh=${peer.meshId} ws=${peer.wsUrl}")
                
                val current = _peers.value.toMutableMap()
                current[nodeId] = peer
                _peers.value = current
                
                onPeerFound?.invoke(peer)
                
                resolving.set(false)
                processResolveQueue()
            }
        })
    }
    
    /**
     * Find a LAN peer that belongs to a specific mesh.
     */
    fun getPeerForMesh(meshId: String): LanPeer? {
        return _peers.value.values.find { it.meshId == meshId }
    }
    
    /**
     * Get all discovered peers.
     */
    fun getAllPeers(): List<LanPeer> = _peers.value.values.toList()
}
