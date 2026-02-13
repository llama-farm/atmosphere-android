package ai.atmosphere.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thin client that connects to the local Atmosphere Daemon.
 * The daemon handles all mesh networking — this just sends commands.
 */

data class AtmoEvent(
    val collection: String,
    val docId: String,
    val kind: String,
    val doc: Map<String, Any>? = null,
)

class AtmoClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 11460,
) {
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    @Volatile private var running = false

    private val responseQueue = LinkedBlockingQueue<JSONObject>()
    private val _events = MutableSharedFlow<AtmoEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<AtmoEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendLock = Object()

    var connected = false
        private set

    fun connect() {
        try {
            socket = Socket(host, port).also {
                it.soTimeout = 0
                output = DataOutputStream(it.getOutputStream())
                input = DataInputStream(it.getInputStream())
            }
            running = true
            connected = true
            scope.launch { listenLoop() }
        } catch (e: Exception) {
            connected = false
            throw e
        }
    }

    fun disconnect() {
        running = false
        connected = false
        scope.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }

    fun insert(collection: String, doc: Map<String, Any>): String {
        val resp = request(JSONObject().apply {
            put("cmd", "insert")
            put("collection", collection)
            put("doc", JSONObject(doc))
        })
        return resp.optString("doc_id", "")
    }

    fun query(collection: String): List<Map<String, Any>> {
        val resp = request(JSONObject().apply {
            put("cmd", "query")
            put("collection", collection)
        })
        val arr = resp.optJSONArray("docs") ?: return emptyList()
        return (0 until arr.length()).map { jsonToMap(arr.getJSONObject(it)) }
    }

    fun subscribe(collection: String) {
        request(JSONObject().apply {
            put("cmd", "subscribe")
            put("collection", collection)
        })
    }

    fun peers(): List<Map<String, Any>> {
        val resp = request(JSONObject().apply { put("cmd", "peers") })
        val arr = resp.optJSONArray("peers") ?: return emptyList()
        return (0 until arr.length()).map { jsonToMap(arr.getJSONObject(it)) }
    }

    fun info(): Map<String, Any> = jsonToMap(request(JSONObject().apply { put("cmd", "info") }))

    fun getBigLlamaStatus(): Map<String, Any> {
        val resp = request(JSONObject().apply { put("cmd", "bigllama_status") })
        return jsonToMap(resp)
    }

    // --- Internal ---

    private fun request(msg: JSONObject): JSONObject {
        if (!connected) return JSONObject().apply { put("ok", false); put("error", "not connected") }
        responseQueue.clear()
        synchronized(sendLock) { sendFrame(msg) }
        return responseQueue.poll(10, TimeUnit.SECONDS) ?: JSONObject().apply {
            put("ok", false); put("error", "timeout")
        }
    }

    private suspend fun listenLoop() {
        while (running) {
            try {
                val data = recvFrame() ?: break
                val msg = JSONObject(String(data))
                if (msg.has("event")) {
                    _events.tryEmit(AtmoEvent(
                        collection = msg.optString("collection", ""),
                        docId = msg.optString("doc_id", ""),
                        kind = msg.optString("kind", ""),
                        doc = msg.optJSONObject("doc")?.let { jsonToMap(it) },
                    ))
                } else {
                    responseQueue.put(msg)
                }
            } catch (_: Exception) {
                if (running) { connected = false; break }
            }
        }
    }

    private fun sendFrame(msg: JSONObject) {
        val data = msg.toString().toByteArray()
        output?.writeInt(data.size)
        output?.write(data)
        output?.flush()
    }

    private fun recvFrame(): ByteArray? = try {
        val length = input?.readInt() ?: return null
        if (length > 16 * 1024 * 1024) null
        else ByteArray(length).also { input?.readFully(it) }
    } catch (_: Exception) { null }

    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val v = json.get(key)
            map[key] = if (v is JSONObject) jsonToMap(v) else v
        }
        return map
    }
}
