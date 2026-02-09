package com.llamafarm.atmosphere.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Resilient Multi-Transport Manager for Atmosphere Mesh.
 *
 * Design Philosophy:
 * - Connect ALL available transports simultaneously
 * - Route messages via BEST transport (lowest latency, highest reliability)
 * - Instant failover when primary fails (no reconnection delay)
 * - Continuous health monitoring keeps connections warm
 * - Graceful degradation as transports fail/recover
 */
class ResilientTransportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nodeId: String,
    private val meshId: String,
) {
    companion object {
        private const val TAG = "ResilientTransport"
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val MAX_LATENCY_SAMPLES = 10
    }

    // Transport types in order of typical preference
    enum class TransportType {
        LAN,          // Direct TCP/WebSocket on local network (~1-5ms)
        WIFI_DIRECT,  // P2P WiFi connection (~5-20ms)
        BLE,          // Bluetooth Low Energy mesh (~50-100ms)
        MATTER,       // Matter/Thread smart home protocol (~30-80ms)
        RELAY         // Cloud relay fallback (~100-500ms)
    }

    enum class TransportState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED,
        UNAVAILABLE
    }

    data class TransportMetrics(
        val transportType: TransportType,
        var state: TransportState = TransportState.DISCONNECTED,
        var latencyMs: Float = Float.MAX_VALUE,
        val latencySamples: MutableList<Float> = mutableListOf(),
        var packetsSent: Int = 0,
        var packetsFailed: Int = 0,
        var consecutiveFailures: Int = 0,
        var lastSuccess: Long? = null,
        var lastFailure: Long? = null,
        var batteryCost: Float = 0.1f,  // 0.0-1.0
        var connectedAt: Long? = null,
        var reconnectAttempts: Int = 0,
    ) {
        fun recordLatency(latency: Float) {
            latencySamples.add(latency)
            if (latencySamples.size > MAX_LATENCY_SAMPLES) {
                latencySamples.removeAt(0)
            }
            latencyMs = latencySamples.average().toFloat()
            lastSuccess = System.currentTimeMillis()
            consecutiveFailures = 0
        }

        fun recordFailure() {
            packetsFailed++
            consecutiveFailures++
            lastFailure = System.currentTimeMillis()
        }

        fun recordSuccess() {
            packetsSent++
            lastSuccess = System.currentTimeMillis()
            consecutiveFailures = 0
        }

        val packetLoss: Float
            get() {
                val total = packetsSent + packetsFailed
                return if (total == 0) 0f else packetsFailed.toFloat() / total
            }

        val isHealthy: Boolean
            get() = state == TransportState.CONNECTED && consecutiveFailures < 3

        /**
         * Calculate transport score. Higher = better.
         * - 40% weight on latency (lower is better)
         * - 40% weight on reliability (less packet loss is better)
         * - 20% weight on battery cost (lower drain is better)
         */
        fun score(): Float {
            if (!isHealthy) return 0f

            // Normalize latency: 0ms = 1.0, 500ms+ = 0.0
            val latencyScore = max(0f, 1f - (latencyMs / 500f))

            // Reliability: 0% loss = 1.0, 100% loss = 0.0
            val reliabilityScore = 1f - packetLoss

            // Battery: 0.0 cost = 1.0 score, 1.0 cost = 0.0 score
            val batteryScore = 1f - batteryCost

            // Penalize recent failures
            var recencyPenalty = 0f
            lastFailure?.let {
                val sinceFailure = (System.currentTimeMillis() - it) / 1000f
                if (sinceFailure < 30 && consecutiveFailures > 0) {
                    recencyPenalty = 0.2f * (1f - sinceFailure / 30f)
                }
            }

            return (latencyScore * 0.4f +
                    reliabilityScore * 0.4f +
                    batteryScore * 0.2f -
                    recencyPenalty)
        }
    }

    // Per-peer transport connections
    private val peerTransports = ConcurrentHashMap<String, MutableMap<TransportType, Transport>>()
    private val peerMetrics = ConcurrentHashMap<String, MutableMap<TransportType, TransportMetrics>>()

    // Transport factories
    private val transportFactories = mutableMapOf<TransportType, () -> Transport>()

    // Background jobs
    private var healthMonitorJob: Job? = null

    // Stats
    private val _stats = MutableStateFlow(TransportStats())
    val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    // Events
    private val _events = MutableSharedFlow<TransportEvent>()
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    data class TransportStats(
        val messagesSent: Int = 0,
        val messagesReceived: Int = 0,
        val failovers: Int = 0,
        val reconnects: Int = 0,
    )

    sealed class TransportEvent {
        data class PeerConnected(val peerId: String, val transport: TransportType) : TransportEvent()
        data class PeerDisconnected(val peerId: String, val transport: TransportType) : TransportEvent()
        data class Failover(val peerId: String, val from: TransportType, val to: TransportType) : TransportEvent()
        data class MessageReceived(val peerId: String, val message: ByteArray) : TransportEvent()
    }

    interface Transport {
        val type: TransportType
        val isConnected: Boolean
        suspend fun connect(address: String): Boolean
        suspend fun disconnect()
        suspend fun send(message: ByteArray): Boolean
        suspend fun ping(): Float  // Returns latency in ms
        fun setMessageHandler(handler: (ByteArray) -> Unit)
    }

    /**
     * Start the transport manager and health monitor.
     */
    fun start() {
        Log.i(TAG, "Starting ResilientTransportManager")
        healthMonitorJob = scope.launch {
            healthMonitorLoop()
        }
    }

    /**
     * Stop the transport manager.
     */
    fun stop() {
        healthMonitorJob?.cancel()
        scope.launch {
            peerTransports.values.forEach { transports ->
                transports.values.forEach { it.disconnect() }
            }
            peerTransports.clear()
            peerMetrics.clear()
        }
    }

    /**
     * Register a transport factory.
     */
    fun registerTransport(type: TransportType, factory: () -> Transport) {
        transportFactories[type] = factory
    }

    /**
     * Connect to a peer via ALL available transports simultaneously.
     */
    suspend fun connectPeer(
        peerId: String,
        lanAddress: String? = null,
        relayUrl: String? = null,
        bleAddress: String? = null,
        wifiDirectAddress: String? = null,
        matterAddress: String? = null,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Connecting to peer $peerId via all available transports")

        val addresses = mapOf(
            TransportType.LAN to lanAddress,
            TransportType.RELAY to relayUrl,
            TransportType.BLE to bleAddress,
            TransportType.WIFI_DIRECT to wifiDirectAddress,
            TransportType.MATTER to matterAddress,
        )

        // Initialize peer data structures
        peerTransports.getOrPut(peerId) { mutableMapOf() }
        peerMetrics.getOrPut(peerId) { mutableMapOf() }

        // Connect ALL transports in parallel
        val jobs = addresses.mapNotNull { (type, address) ->
            if (address != null && transportFactories.containsKey(type)) {
                async {
                    connectTransport(peerId, type, address)
                }
            } else null
        }

        val results = jobs.awaitAll()
        val successful = results.count { it }
        Log.i(TAG, "Connected to $peerId via $successful/${jobs.size} transports")
    }

    private suspend fun connectTransport(
        peerId: String,
        type: TransportType,
        address: String
    ): Boolean {
        try {
            // Skip if already connected
            peerTransports[peerId]?.get(type)?.let { existing ->
                if (existing.isConnected) return true
            }

            val factory = transportFactories[type] ?: return false
            val transport = factory()

            // Initialize metrics
            val metrics = TransportMetrics(
                transportType = type,
                state = TransportState.CONNECTING,
                batteryCost = when (type) {
                    TransportType.LAN -> 0.1f
                    TransportType.RELAY -> 0.2f
                    TransportType.WIFI_DIRECT -> 0.3f
                    TransportType.BLE -> 0.15f
                    TransportType.MATTER -> 0.15f
                }
            )

            // Set message handler
            transport.setMessageHandler { message ->
                scope.launch {
                    _stats.update { it.copy(messagesReceived = it.messagesReceived + 1) }
                    _events.emit(TransportEvent.MessageReceived(peerId, message))
                }
            }

            // Connect
            val success = transport.connect(address)

            if (success) {
                metrics.state = TransportState.CONNECTED
                metrics.connectedAt = System.currentTimeMillis()
                peerTransports[peerId]?.put(type, transport)
                peerMetrics[peerId]?.put(type, metrics)
                Log.d(TAG, "Connected to $peerId via ${type.name}")
                _events.emit(TransportEvent.PeerConnected(peerId, type))
                return true
            } else {
                metrics.state = TransportState.FAILED
                return false
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to $peerId via ${type.name}: ${e.message}")
            return false
        }
    }

    /**
     * Send message to peer via best available transport.
     * Automatically fails over to next best if primary fails.
     */
    suspend fun send(peerId: String, message: ByteArray): Boolean {
        val transports = getTransportsByScore(peerId)
        if (transports.isEmpty()) {
            Log.w(TAG, "No transports available for $peerId")
            return false
        }

        var primaryFailed = false
        var lastTransport: TransportType? = null

        for ((transport, metrics) in transports) {
            if (!metrics.isHealthy) continue

            try {
                val success = transport.send(message)
                if (success) {
                    metrics.recordSuccess()
                    _stats.update { it.copy(messagesSent = it.messagesSent + 1) }

                    if (primaryFailed && lastTransport != null) {
                        _stats.update { it.copy(failovers = it.failovers + 1) }
                        _events.emit(TransportEvent.Failover(peerId, lastTransport, transport.type))
                        Log.i(TAG, "Failover successful: $peerId via ${transport.type.name}")
                    }

                    return true
                } else {
                    metrics.recordFailure()
                    primaryFailed = true
                    lastTransport = transport.type
                }
            } catch (e: Exception) {
                Log.w(TAG, "Send via ${transport.type.name} failed: ${e.message}")
                metrics.recordFailure()
                primaryFailed = true
                lastTransport = transport.type
            }
        }

        Log.e(TAG, "All transports failed for $peerId")
        return false
    }

    /**
     * Get all transports for a peer sorted by score (best first).
     */
    private fun getTransportsByScore(peerId: String): List<Pair<Transport, TransportMetrics>> {
        val transports = peerTransports[peerId] ?: return emptyList()
        val metrics = peerMetrics[peerId] ?: return emptyList()

        return transports.mapNotNull { (type, transport) ->
            metrics[type]?.let { m -> transport to m }
        }.sortedByDescending { it.second.score() }
    }

    /**
     * Background health monitor.
     */
    private suspend fun healthMonitorLoop() {
        while (true) {
            try {
                checkAllTransports()
            } catch (e: Exception) {
                Log.e(TAG, "Health monitor error: ${e.message}")
            }
            delay(HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkAllTransports() {
        peerTransports.forEach { (peerId, transports) ->
            transports.forEach { (type, transport) ->
                val metrics = peerMetrics[peerId]?.get(type) ?: return@forEach

                try {
                    if (metrics.state == TransportState.CONNECTED) {
                        // Ping and measure latency
                        val latency = withTimeout(5000) { transport.ping() }
                        metrics.recordLatency(latency)
                    } else if (metrics.state == TransportState.FAILED) {
                        // Attempt reconnection
                        scheduleReconnect(peerId, type)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Health check failed for $peerId via ${type.name}: ${e.message}")
                    metrics.recordFailure()
                    if (metrics.consecutiveFailures >= 3) {
                        metrics.state = TransportState.FAILED
                        scheduleReconnect(peerId, type)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(peerId: String, type: TransportType) {
        val metrics = peerMetrics[peerId]?.get(type) ?: return
        if (metrics.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for $peerId via ${type.name}")
            return
        }

        scope.launch {
            delay(RECONNECT_DELAY_MS)
            metrics.reconnectAttempts++
            // Would need stored address to reconnect
            // For now, just mark as needing reconnection
            Log.d(TAG, "Would reconnect $peerId via ${type.name} (attempt ${metrics.reconnectAttempts})")
        }
    }

    /**
     * Get current status of all connections.
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "nodeId" to nodeId,
            "meshId" to meshId,
            "stats" to _stats.value,
            "peers" to peerMetrics.mapValues { (_, metrics) ->
                metrics.mapValues { (type, m) ->
                    mapOf(
                        "state" to m.state.name,
                        "latencyMs" to if (m.latencyMs == Float.MAX_VALUE) null else m.latencyMs,
                        "packetLoss" to m.packetLoss,
                        "score" to m.score(),
                        "consecutiveFailures" to m.consecutiveFailures,
                    )
                }
            }
        )
    }
}
