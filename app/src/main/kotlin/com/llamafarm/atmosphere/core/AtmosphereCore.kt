package com.llamafarm.atmosphere.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Atmosphere Core — CRDT mesh networking + document sync.
 * Pure Kotlin implementation. Runs inside AtmosphereService as the daemon engine.
 */

data class StoreEvent(
    val collection: String,
    val docId: String,
    val kind: String, // "inserted", "updated", "removed"
)

data class PeerInfo(
    val peerId: String,
    val metadata: Map<String, Any> = emptyMap(),
    val transport: String = "",
    var lastSeen: Long = System.currentTimeMillis(),
    var host: String? = null,
    var port: Int = 0,
)

data class AtmoDocument(
    val docId: String,
    val data: MutableMap<String, Any> = mutableMapOf(),
    val version: MutableMap<String, Long> = mutableMapOf(),
) {
    fun toJson(): Map<String, Any> {
        val result = HashMap(data)
        result["_id"] = docId
        return result
    }

    fun merge(other: AtmoDocument) {
        for ((peer, counter) in other.version) {
            if (counter > (version[peer] ?: 0L)) {
                version[peer] = counter
                data.putAll(other.data)
            }
        }
    }
}

class AtmosphereCore(
    val appId: String = "atmosphere",
    val peerId: String = UUID.randomUUID().toString().replace("-", "").take(16),
    val metadata: Map<String, Any> = emptyMap(),
    var listenPort: Int = 0,
    val enableLan: Boolean = true,
    var meshId: String = "atmosphere-playground-mesh-v1",
    var sharedSecret: String = java.security.MessageDigest.getInstance("SHA-256").digest("atmosphere-playground-v1".toByteArray()).joinToString("") { "%02x".format(it) },
) {
    companion object {
        const val DISCOVERY_PORT = 11452
        val MAGIC = "ATMO".toByteArray()
    }

    private val collections = ConcurrentHashMap<String, ConcurrentHashMap<String, AtmoDocument>>()
    private val observers = ConcurrentHashMap<Int, Pair<String?, (StoreEvent) -> Unit>>()
    private var nextObserverId = 1
    private var counter = 0L

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<StoreEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<StoreEvent> = _events.asSharedFlow()

    fun insert(collection: String, data: Map<String, Any>): String {
        counter++
        val col = collections.getOrPut(collection) { ConcurrentHashMap() }
        val docId = (data["_id"] as? String) ?: UUID.randomUUID().toString()
        val doc = AtmoDocument(
            docId = docId,
            data = data.filterKeys { it != "_id" }.toMutableMap(),
            version = mutableMapOf(peerId to counter),
        )
        col[docId] = doc

        val event = StoreEvent(collection, docId, "inserted")
        fireObservers(event)
        _events.tryEmit(event)

        return docId
    }

    fun query(collection: String): List<Map<String, Any>> {
        return collections[collection]?.values?.map { it.toJson() } ?: emptyList()
    }

    fun get(collection: String, docId: String): Map<String, Any>? {
        return collections[collection]?.get(docId)?.toJson()
    }

    fun observe(collection: String?, callback: (StoreEvent) -> Unit): Int {
        val id = nextObserverId++
        observers[id] = Pair(collection, callback)
        return id
    }

    fun removeObserver(id: Int) {
        observers.remove(id)
    }

    fun connectedPeers(): List<PeerInfo> = peers.values.toList()

    fun startSync() {
        if (running) return
        running = true

        if (enableLan) {
            // Start TCP server first so we have a real port before broadcasting
            scope.launch {
                runTcpServer()  // Sets listenPort on bind
            }
            // Small delay to ensure server binds before we broadcast the port
            scope.launch {
                kotlinx.coroutines.delay(500)
                runDiscoveryBroadcast()
            }
            scope.launch {
                kotlinx.coroutines.delay(500)
                runDiscoveryListener()
            }
            // Periodic re-sync with all known peers (every 3s)
            scope.launch {
                kotlinx.coroutines.delay(2000)
                while (running) {
                    for ((peerId, info) in peers.toMap()) {
                        val host = info.host ?: continue
                        val port = info.port
                        if (port > 0) {
                            try {
                                connectAndSync(host, port, peerId)
                            } catch (_: Exception) {}
                        }
                    }
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    fun stopSync() {
        running = false
        scope.cancel()
    }

    // --- Internal ---

    private fun fireObservers(event: StoreEvent) {
        observers.values.forEach { (col, callback) ->
            if (col == null || col == event.collection) {
                try { callback(event) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runDiscoveryBroadcast() {
        val socket = DatagramSocket()
        socket.broadcast = true

        while (running) {
            try {
                val msg = JSONObject().apply {
                    put("peer_id", peerId)
                    put("app_id", appId)
                    put("tcp_port", listenPort)
                }.toString().toByteArray()
                val packet = MAGIC + msg
                val addr = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(packet, packet.size, addr, DISCOVERY_PORT))
            } catch (_: Exception) {}
            delay(5000)
        }
        socket.close()
    }

    private suspend fun runDiscoveryListener() {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(DISCOVERY_PORT))
        socket.soTimeout = 1000

        val buf = ByteArray(1024)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = buf.copyOf(packet.length)
                if (!data.take(4).toByteArray().contentEquals(MAGIC)) continue

                val json = JSONObject(String(data, 4, data.size - 4))
                val remotePeer = json.optString("peer_id", "")
                val remoteApp = json.optString("app_id", "")
                val remotePort = json.optInt("tcp_port", 0)

                if (remotePeer == peerId || remoteApp != appId) continue

                val hostAddr = packet.address.hostAddress ?: continue
                if (!peers.containsKey(remotePeer)) {
                    peers[remotePeer] = PeerInfo(
                        peerId = remotePeer,
                        transport = "lan",
                        host = hostAddr,
                        port = remotePort,
                    )
                    scope.launch {
                        connectAndSync(hostAddr, remotePort, remotePeer)
                    }
                } else {
                    peers[remotePeer]?.lastSeen = System.currentTimeMillis()
                    peers[remotePeer]?.host = hostAddr
                    peers[remotePeer]?.port = remotePort
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {}
        }
        socket.close()
    }

    private suspend fun runTcpServer() {
        val server = ServerSocket(listenPort)
        listenPort = server.localPort
        server.soTimeout = 1000

        while (running) {
            try {
                val conn = server.accept()
                scope.launch { handleIncoming(conn) }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {}
        }
        server.close()
    }

    private fun connectAndSync(host: String, port: Int, remotePeerId: String) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            // Generate HMAC auth hash
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val authData = "$peerId:$timestamp"
            val secretBytes = sharedSecret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA256"))
            val authHash = mac.doFinal(authData.toByteArray()).joinToString("") { "%02x".format(it) }

            sendFrame(out, JSONObject().apply {
                put("type", "hello")
                put("peer_id", peerId)
                put("app_id", appId)
                put("mesh_id", meshId)
                put("auth_hash", authHash)
                put("timestamp", timestamp)
            }.toString().toByteArray())

            val resp = recvFrame(inp)
            if (resp != null) {
                val msg = JSONObject(String(resp))
                if (msg.optString("type") == "hello_ack") {
                    sendAllDocs(out)
                    recvDocs(inp)
                }
            }

            socket.close()
        } catch (_: Exception) {}
    }

    private fun handleIncoming(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            val data = recvFrame(inp) ?: return
            val msg = JSONObject(String(data))
            
            // Validate type and app_id
            if (msg.optString("type") != "hello" || msg.optString("app_id") != appId) {
                android.util.Log.d("AtmosphereCore", "Rejected peer: wrong type or app_id")
                socket.close()
                return
            }
            
            // Validate mesh_id
            val remoteMeshId = msg.optString("mesh_id", "")
            if (remoteMeshId != meshId) {
                android.util.Log.d("AtmosphereCore", "Rejected peer: mesh_id mismatch ($remoteMeshId != $meshId)")
                socket.close()
                return
            }
            
            // Validate auth_hash
            val remotePeer = msg.optString("peer_id", "")
            val timestamp = msg.optString("timestamp", "")
            val authHash = msg.optString("auth_hash", "")
            
            val expectedAuthData = "$remotePeer:$timestamp"
            val secretBytes = sharedSecret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA256"))
            val expectedHash = mac.doFinal(expectedAuthData.toByteArray()).joinToString("") { "%02x".format(it) }
            
            if (authHash != expectedHash) {
                android.util.Log.d("AtmosphereCore", "Rejected peer $remotePeer: invalid auth_hash")
                socket.close()
                return
            }
            
            // Validate timestamp (allow 5 minute skew)
            try {
                val remoteTime = timestamp.toLong()
                val localTime = System.currentTimeMillis() / 1000
                if (kotlin.math.abs(localTime - remoteTime) > 300) {
                    android.util.Log.d("AtmosphereCore", "Rejected peer $remotePeer: timestamp too old/new")
                    socket.close()
                    return
                }
            } catch (_: Exception) {
                android.util.Log.d("AtmosphereCore", "Rejected peer $remotePeer: invalid timestamp")
                socket.close()
                return
            }
            
            android.util.Log.d("AtmosphereCore", "✅ Accepted peer $remotePeer (mesh: $remoteMeshId)")

            sendFrame(out, JSONObject().apply {
                put("type", "hello_ack")
                put("peer_id", peerId)
                put("app_id", appId)
            }.toString().toByteArray())

            recvDocs(inp)
            sendAllDocs(out)
            socket.close()
        } catch (e: Exception) {
            android.util.Log.d("AtmosphereCore", "Incoming connection error: $e")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendAllDocs(out: DataOutputStream) {
        for ((colName, col) in collections) {
            for ((_, doc) in col) {
                val msg = JSONObject().apply {
                    put("type", "sync_diff")
                    put("collection", colName)
                    put("doc_id", doc.docId)
                    put("data", JSONObject(doc.data))
                    put("version", JSONObject(doc.version as Map<*, *>))
                }
                sendFrame(out, msg.toString().toByteArray())
            }
        }
        sendFrame(out, """{"type":"sync_done"}""".toByteArray())
    }

    private fun recvDocs(inp: DataInputStream) {
        while (true) {
            val data = recvFrame(inp) ?: break
            val msg = JSONObject(String(data))
            when (msg.optString("type")) {
                "sync_done" -> break
                "sync_diff" -> {
                    val colName = msg.getString("collection")
                    val docId = msg.getString("doc_id")
                    val docData = mutableMapOf<String, Any>()
                    val jsonData = msg.optJSONObject("data")
                    jsonData?.keys()?.forEach { key ->
                        docData[key] = jsonData.get(key)
                    }
                    val doc = AtmoDocument(docId, docData)
                    val versionJson = msg.optJSONObject("version")
                    versionJson?.keys()?.forEach { key ->
                        doc.version[key] = versionJson.getLong(key)
                    }

                    val col = collections.getOrPut(colName) { ConcurrentHashMap() }
                    val existing = col[docId]
                    if (existing != null) {
                        existing.merge(doc)
                    } else {
                        col[docId] = doc
                    }

                    val event = StoreEvent(colName, docId, "updated")
                    fireObservers(event)
                    _events.tryEmit(event)
                }
            }
        }
    }

    private fun sendFrame(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
        out.flush()
    }

    private fun recvFrame(inp: DataInputStream): ByteArray? {
        return try {
            val length = inp.readInt()
            if (length > 16 * 1024 * 1024) return null
            val data = ByteArray(length)
            inp.readFully(data)
            data
        } catch (_: Exception) {
            null
        }
    }
}
