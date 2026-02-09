package com.llamafarm.atmosphere.network

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WebSocket-based transport for LAN and Relay connections.
 * 
 * Features:
 * - Automatic reconnection with exponential backoff
 * - Ping/pong health checks
 * - Message handlers
 */
class WebSocketTransport(
    override val type: ResilientTransportManager.TransportType,
    private val nodeId: String = "",
    private val meshId: String = "",
) : ResilientTransportManager.Transport {

    companion object {
        private const val TAG = "WebSocketTransport"
        private const val PING_TIMEOUT_MS = 5000L
        private val RECONNECT_DELAYS = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var messageHandler: ((ByteArray) -> Unit)? = null
    private var pingDeferred: CompletableDeferred<Float>? = null
    private var pingStartTime: Long = 0
    
    // Auto-reconnect state
    private var lastAddress: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    override val isConnected: Boolean
        get() = webSocket != null

    override suspend fun connect(address: String): Boolean = suspendCoroutine { continuation ->
        Log.i(TAG, "Connecting to $address via ${type.name}")
        
        // Save for reconnection
        lastAddress = address
        shouldReconnect = true
        reconnectAttempt = 0

        val request = Request.Builder()
            .url(address)
            .build()

        val listener = object : WebSocketListener() {
            private var resumed = false

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened: ${type.name}")
                webSocket = ws
                reconnectAttempt = 0  // Reset on successful connect

                // For relay, send join message
                if (type == ResilientTransportManager.TransportType.RELAY && meshId.isNotEmpty()) {
                    val joinMsg = """{"type":"join","node_id":"$nodeId","mesh_id":"$meshId"}"""
                    ws.send(joinMsg)
                }

                if (!resumed) {
                    resumed = true
                    continuation.resume(true)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received text: ${text.take(100)}...")
                handleMessage(text.toByteArray())
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary: ${bytes.size} bytes")
                handleMessage(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                webSocket = null
                // Trigger auto-reconnect
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                webSocket = null
                if (!resumed) {
                    resumed = true
                    continuation.resume(false)
                }
                // Trigger auto-reconnect
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }

            private fun handleMessage(data: ByteArray) {
                // Check for pong response
                try {
                    val text = String(data)
                    if (text.contains("\"type\":\"pong\"")) {
                        val latency = System.currentTimeMillis() - pingStartTime
                        pingDeferred?.complete(latency.toFloat())
                        return
                    }
                } catch (_: Exception) {}

                messageHandler?.invoke(data)
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }
    
    /**
     * Schedule auto-reconnect with exponential backoff.
     */
    private fun scheduleReconnect() {
        val address = lastAddress ?: return
        
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            val delay = RECONNECT_DELAYS.getOrElse(reconnectAttempt) { RECONNECT_DELAYS.last() }
            Log.i(TAG, "Auto-reconnect in ${delay}ms (attempt ${reconnectAttempt + 1})...")
            delay(delay)
            
            reconnectAttempt++
            
            try {
                val success = connect(address)
                if (success) {
                    Log.i(TAG, "Auto-reconnect successful!")
                } else {
                    Log.w(TAG, "Auto-reconnect failed, will retry...")
                    // connect() will schedule another reconnect on failure
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-reconnect error: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    override suspend fun disconnect() {
        shouldReconnect = false  // Don't auto-reconnect on intentional disconnect
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Closing")
        webSocket = null
    }

    override suspend fun send(message: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(message.toByteString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    /**
     * Send JSON message.
     */
    suspend fun sendJson(json: String): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send JSON failed: ${e.message}")
            false
        }
    }

    override suspend fun ping(): Float {
        val ws = webSocket ?: throw IllegalStateException("Not connected")

        pingDeferred = CompletableDeferred()
        pingStartTime = System.currentTimeMillis()

        // Send ping message
        val pingMsg = """{"type":"ping","ts":$pingStartTime}"""
        ws.send(pingMsg)

        return try {
            withTimeout(PING_TIMEOUT_MS) {
                pingDeferred!!.await()
            }
        } finally {
            pingDeferred = null
        }
    }

    override fun setMessageHandler(handler: (ByteArray) -> Unit) {
        messageHandler = handler
    }
}

/**
 * Factory for creating LAN transports.
 */
fun createLanTransport(nodeId: String = ""): ResilientTransportManager.Transport {
    return WebSocketTransport(
        type = ResilientTransportManager.TransportType.LAN,
        nodeId = nodeId,
    )
}

/**
 * Factory for creating Relay transports.
 */
fun createRelayTransport(nodeId: String, meshId: String): ResilientTransportManager.Transport {
    return WebSocketTransport(
        type = ResilientTransportManager.TransportType.RELAY,
        nodeId = nodeId,
        meshId = meshId,
    )
}
