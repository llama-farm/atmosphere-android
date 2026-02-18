package com.llamafarm.atmosphere.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.llamafarm.atmosphere.core.AtmosphereNative
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "WifiAwareManager"

// Fragment header: 8 bytes = seq(4) + idx(2) + total(2)
private const val FRAG_HEADER_SIZE = 8
// Wi-Fi Aware sendMessage max payload is 255 bytes
private const val MAX_MESSAGE_SIZE = 255
private const val MAX_FRAG_PAYLOAD = MAX_MESSAGE_SIZE - FRAG_HEADER_SIZE  // 247 bytes per fragment

/**
 * Wi-Fi Aware transport for Atmosphere mesh networking.
 *
 * All data flows through the Rust multiplexer via JNI channels:
 *   Incoming: onMessageReceived → reassemble fragments → wifiAwareDataReceived (JNI) → multiplexer
 *   Outgoing: multiplexer → wifiAwarePollOutgoing (JNI) → fragment → sendMessage
 *
 * Hello handshake exchanges Atmosphere peer IDs over sendMessage before
 * accepting the peer into the multiplexer via wifiAwarePeerAccepted.
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

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive

    // Discovered peers: PeerHandle → PeerDiscoveryInfo
    private val discoveredPeers = mutableMapOf<PeerHandle, PeerDiscoveryInfo>()

    // Accepted peers: PeerHandle → Atmosphere peer_id (after hello handshake)
    private val acceptedPeers = mutableMapOf<PeerHandle, String>()
    // Reverse map: peer_id → PeerHandle (for outgoing poll)
    private val peerHandleByAtmoId = mutableMapOf<String, PeerHandle>()

    // Fragment reassembly: peerHandle → (seq → fragments[])
    private val reassemblyBuffers = mutableMapOf<PeerHandle, MutableMap<Int, Array<ByteArray?>>>()
    private val reassemblyTotals = mutableMapOf<PeerHandle, MutableMap<Int, Int>>()
    private var nextSeq = 0

    private val serviceName = "atmosphere-mesh"
    private val serviceSpecificInfo = "atmo".toByteArray()

    // Our local Atmosphere peer_id (read from JNI or set externally)
    private var localPeerId: String = ""

    init {
        checkAvailability()
    }

    fun setLocalPeerId(peerId: String) {
        localPeerId = peerId
    }

    private fun checkAvailability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _isAvailable.value = false
            return
        }
        awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE)
            as? android.net.wifi.aware.WifiAwareManager
        val hasWifiAware = awareManager != null
        val hasPermissions = checkPermissions()
        _isAvailable.value = hasWifiAware && hasPermissions
        Log.i(TAG, "Wi-Fi Aware available: $hasWifiAware, permissions: $hasPermissions")
    }

    private fun checkPermissions(): Boolean {
        val location = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return location && wifi
    }

    // ========================================================================
    // Session lifecycle
    // ========================================================================

    suspend fun start() = suspendCancellableCoroutine { continuation ->
        if (!_isAvailable.value) {
            continuation.resumeWith(Result.failure(
                IllegalStateException("Wi-Fi Aware not available")
            ))
            return@suspendCancellableCoroutine
        }

        val cb = object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "Wi-Fi Aware session attached")
                wifiAwareSession = session
                _isSessionActive.value = true
                startPublish(session)
                startSubscribe(session)
                continuation.resumeWith(Result.success(Unit))
            }

            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach failed")
                _isSessionActive.value = false
                continuation.resumeWith(Result.failure(RuntimeException("Attach failed")))
            }
        }
        awareManager?.attach(cb, null)
    }

    private fun startPublish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(serviceSpecificInfo)
            .setTerminateNotificationEnabled(true)
            .build()

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.i(TAG, "Wi-Fi Aware publish started")
                publishDiscoverySession = session
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }
            override fun onSessionTerminated() {
                Log.w(TAG, "Publish session terminated")
                publishDiscoverySession = null
            }
        }, null)
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setServiceSpecificInfo(serviceSpecificInfo)
            .setTerminateNotificationEnabled(true)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {
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
                handleIncomingMessage(peerHandle, message)
            }
            override fun onSessionTerminated() {
                Log.w(TAG, "Subscribe session terminated")
                subscribeDiscoverySession = null
            }
        }, null)
    }

    // ========================================================================
    // Peer discovery + hello handshake
    // ========================================================================

    private fun handlePeerDiscovered(peerHandle: PeerHandle, serviceInfo: ByteArray) {
        discoveredPeers[peerHandle] = PeerDiscoveryInfo(
            peerHandle = peerHandle,
            serviceInfo = serviceInfo,
            discoveredAt = System.currentTimeMillis()
        )

        // Notify Rust about discovery (adds to JniWifiAwareManager.peers)
        scope.launch {
            try {
                AtmosphereNative.wifiAwarePeerDiscovered(
                    atmosphereHandle, peerHandle.toString(), serviceInfo.decodeToString()
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to notify Rust about peer discovery", e)
            }
        }

        // Send hello to exchange Atmosphere peer IDs
        sendHello(peerHandle)
    }

    private fun sendHello(peerHandle: PeerHandle) {
        if (localPeerId.isEmpty()) {
            Log.w(TAG, "Cannot send hello — localPeerId not set")
            return
        }
        val hello = """{"type":"hello","peer_id":"$localPeerId","app_id":"atmosphere"}""".toByteArray()
        sendRawMessage(peerHandle, hello)
        Log.i(TAG, "Sent Wi-Fi Aware hello to $peerHandle (peer_id=$localPeerId)")
    }

    // ========================================================================
    // Incoming message handling
    // ========================================================================

    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        // Keepalive ping/pong intercept: 32-byte APIN/APON
        if (message.size == 32 && message[0] == 0x41.toByte() && message[1] == 0x50.toByte()) {
            if (message[2] == 0x49.toByte() && message[3] == 0x4E.toByte()) {
                Log.d(TAG, "Keepalive ping from $peerHandle, sending pong")
                val pong = message.copyOf()
                pong[2] = 0x4F.toByte()
                sendRawMessage(peerHandle, pong)
                return
            }
            if (message[2] == 0x4F.toByte() && message[3] == 0x4E.toByte()) {
                return // pong — ignore
            }
        }

        // Hello message detection (starts with '{')
        if (message.isNotEmpty() && message[0] == '{'.code.toByte()) {
            try {
                val json = message.decodeToString()
                if (json.contains("\"type\":\"hello\"")) {
                    handleHello(peerHandle, json)
                    return
                }
            } catch (_: Exception) {}
        }

        // Fragment: 8-byte header + payload
        if (message.size >= FRAG_HEADER_SIZE) {
            handleFragment(peerHandle, message)
        }
    }

    private fun handleHello(peerHandle: PeerHandle, json: String) {
        // Parse peer_id from hello JSON
        val peerIdMatch = Regex("\"peer_id\":\"([^\"]+)\"").find(json)
        val remotePeerId = peerIdMatch?.groupValues?.get(1) ?: peerHandle.toString()

        Log.i(TAG, "Received Wi-Fi Aware hello from $peerHandle: peer_id=$remotePeerId")

        // If we haven't sent hello yet, send one back
        if (!acceptedPeers.containsKey(peerHandle)) {
            sendHello(peerHandle)
        }

        // Register peer for data flow
        acceptedPeers[peerHandle] = remotePeerId
        peerHandleByAtmoId[remotePeerId] = peerHandle

        // Accept into multiplexer via JNI
        scope.launch {
            try {
                AtmosphereNative.wifiAwarePeerAccepted(
                    atmosphereHandle, remotePeerId, peerHandle.toString()
                )
                Log.i(TAG, "✅ Wi-Fi Aware peer accepted into multiplexer: $remotePeerId")

                // Start outgoing poll loop for this peer
                startOutgoingPollLoop(remotePeerId, peerHandle)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to accept Wi-Fi Aware peer", e)
            }
        }
    }

    // ========================================================================
    // Fragment reassembly (incoming)
    // ========================================================================

    private fun handleFragment(peerHandle: PeerHandle, message: ByteArray) {
        val buf = ByteBuffer.wrap(message, 0, FRAG_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val seq = buf.getInt()
        val idx = buf.getShort().toInt() and 0xFFFF
        val total = buf.getShort().toInt() and 0xFFFF

        if (total == 0 || idx >= total || total > 500) {
            Log.w(TAG, "Invalid fragment header: seq=$seq idx=$idx total=$total")
            return
        }

        val payload = message.copyOfRange(FRAG_HEADER_SIZE, message.size)

        val peerFrags = reassemblyBuffers.getOrPut(peerHandle) { mutableMapOf() }
        val peerTotals = reassemblyTotals.getOrPut(peerHandle) { mutableMapOf() }

        val frags = peerFrags.getOrPut(seq) { arrayOfNulls(total) }
        peerTotals[seq] = total
        
        if (idx < frags.size) {
            frags[idx] = payload
        }

        // Check if all fragments received
        val received = frags.count { it != null }
        if (received == total) {
            // Reassemble
            val fullData = frags.filterNotNull().fold(ByteArray(0)) { acc, b -> acc + b }
            peerFrags.remove(seq)
            peerTotals.remove(seq)

            val peerId = acceptedPeers[peerHandle] ?: peerHandle.toString()
            Log.i(TAG, "Reassembled Wi-Fi Aware message from $peerId: ${fullData.size} bytes ($total fragments)")

            // Feed into multiplexer via JNI
            scope.launch {
                try {
                    AtmosphereNative.wifiAwareDataReceived(
                        atmosphereHandle, peerId, fullData
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to feed Wi-Fi Aware data to Rust", e)
                }
            }
        }
    }

    // ========================================================================
    // Fragmentation + sending (outgoing)
    // ========================================================================

    /**
     * Fragment data and send via Wi-Fi Aware sendMessage.
     * Each fragment: [seq:4][idx:2][total:2][payload:≤247]
     */
    private fun sendFragmented(peerHandle: PeerHandle, data: ByteArray) {
        val seq = nextSeq++
        val totalFragments = (data.size + MAX_FRAG_PAYLOAD - 1) / MAX_FRAG_PAYLOAD

        for (i in 0 until totalFragments) {
            val offset = i * MAX_FRAG_PAYLOAD
            val end = minOf(offset + MAX_FRAG_PAYLOAD, data.size)
            val payload = data.copyOfRange(offset, end)

            val fragment = ByteBuffer.allocate(FRAG_HEADER_SIZE + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(seq)
                .putShort(i.toShort())
                .putShort(totalFragments.toShort())
                .put(payload)
                .array()

            sendRawMessage(peerHandle, fragment)
        }

        Log.d(TAG, "📤 Sent ${data.size} bytes to $peerHandle in $totalFragments fragments (seq=$seq)")
    }

    private fun sendRawMessage(peerHandle: PeerHandle, data: ByteArray) {
        val session = publishDiscoverySession ?: subscribeDiscoverySession
        if (session == null) {
            Log.e(TAG, "No active session to send message")
            return
        }
        session.sendMessage(peerHandle, 0, data)
    }

    // ========================================================================
    // Outgoing poll loop — polls multiplexer via JNI
    // ========================================================================

    private val outgoingPollJobs = mutableMapOf<String, Job>()

    private fun startOutgoingPollLoop(peerId: String, peerHandle: PeerHandle) {
        // Don't start duplicate poll loops
        if (outgoingPollJobs.containsKey(peerId)) return

        val job = scope.launch {
            Log.i(TAG, "Starting outgoing poll loop for $peerId")
            while (isActive) {
                try {
                    val data = AtmosphereNative.wifiAwarePollOutgoing(atmosphereHandle, peerId)
                    if (data != null) {
                        sendFragmented(peerHandle, data)
                    } else {
                        delay(50) // No data — back off
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error polling outgoing for $peerId", e)
                    delay(1000)
                }
            }
        }
        outgoingPollJobs[peerId] = job
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    fun stop() {
        outgoingPollJobs.values.forEach { it.cancel() }
        outgoingPollJobs.clear()

        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        wifiAwareSession?.close()

        publishDiscoverySession = null
        subscribeDiscoverySession = null
        wifiAwareSession = null

        acceptedPeers.clear()
        peerHandleByAtmoId.clear()
        reassemblyBuffers.clear()
        reassemblyTotals.clear()
        discoveredPeers.clear()

        _isSessionActive.value = false
        scope.cancel()

        Log.i(TAG, "Wi-Fi Aware session stopped")
    }

    fun getDiscoveredPeers(): List<PeerDiscoveryInfo> = discoveredPeers.values.toList()
}

data class PeerDiscoveryInfo(
    val peerHandle: PeerHandle,
    val serviceInfo: ByteArray,
    val discoveredAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerDiscoveryInfo) return false
        return peerHandle == other.peerHandle &&
                serviceInfo.contentEquals(other.serviceInfo) &&
                discoveredAt == other.discoveredAt
    }

    override fun hashCode(): Int {
        var result = peerHandle.hashCode()
        result = 31 * result + serviceInfo.contentHashCode()
        result = 31 * result + discoveredAt.hashCode()
        return result
    }
}
