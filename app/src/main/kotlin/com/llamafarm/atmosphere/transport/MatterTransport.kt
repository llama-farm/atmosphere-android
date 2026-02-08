package com.llamafarm.atmosphere.transport

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MatterTransport"

/**
 * Matter device types supported.
 */
enum class MatterDeviceType(val clusterId: Int) {
    LIGHT(6),           // On/Off cluster
    DIMMER(8),          // Level Control cluster
    COLOR_LIGHT(768),   // Color Control cluster
    THERMOSTAT(513),    // Thermostat cluster
    LOCK(257),          // Door Lock cluster
    SENSOR(1026),       // Temperature Measurement cluster
    SWITCH(59),         // Switch cluster
    UNKNOWN(-1)
}

/**
 * Discovered Matter device.
 */
data class MatterDevice(
    val nodeId: Long,
    val name: String,
    val type: MatterDeviceType,
    val vendor: String? = null,
    val product: String? = null,
    val endpoints: List<Int> = emptyList(),
    val online: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val id: String get() = "matter-$nodeId"
    
    fun toCapabilityId(): String = when (type) {
        MatterDeviceType.LIGHT -> "matter/light/$nodeId"
        MatterDeviceType.DIMMER -> "matter/dimmer/$nodeId"
        MatterDeviceType.COLOR_LIGHT -> "matter/color-light/$nodeId"
        MatterDeviceType.THERMOSTAT -> "matter/thermostat/$nodeId"
        MatterDeviceType.LOCK -> "matter/lock/$nodeId"
        MatterDeviceType.SENSOR -> "matter/sensor/$nodeId"
        MatterDeviceType.SWITCH -> "matter/switch/$nodeId"
        MatterDeviceType.UNKNOWN -> "matter/device/$nodeId"
    }
}

/**
 * Matter command to execute on a device.
 */
sealed class MatterCommand {
    data class OnOff(val on: Boolean) : MatterCommand()
    data class SetLevel(val level: Int) : MatterCommand() // 0-254
    data class SetColor(val hue: Int, val saturation: Int) : MatterCommand()
    data class SetTemperature(val tempCelsius: Float, val mode: String = "heat") : MatterCommand()
    data class Lock(val lock: Boolean, val pin: String? = null) : MatterCommand()
    
    fun toJson(): JSONObject = JSONObject().apply {
        when (val cmd = this@MatterCommand) {
            is OnOff -> {
                put("cluster", "on_off")
                put("command", if (cmd.on) "on" else "off")
            }
            is SetLevel -> {
                put("cluster", "level_control")
                put("command", "move_to_level")
                put("level", cmd.level)
            }
            is SetColor -> {
                put("cluster", "color_control")
                put("command", "move_to_hue_and_saturation")
                put("hue", cmd.hue)
                put("saturation", cmd.saturation)
            }
            is SetTemperature -> {
                put("cluster", "thermostat")
                put("command", "set_temperature")
                put("temperature", cmd.tempCelsius)
                put("mode", cmd.mode)
            }
            is Lock -> {
                put("cluster", "door_lock")
                put("command", if (cmd.lock) "lock" else "unlock")
                cmd.pin?.let { put("pin", it) }
            }
        }
    }
}

/**
 * Matter transport configuration.
 */
data class MatterConfig(
    val enabled: Boolean = true,
    val bridgeUrl: String = "ws://localhost:11452",  // Matter bridge WebSocket
    val autoCommission: Boolean = false,
    val fabricId: Long = 1
)

/**
 * Matter transport for smart home device control.
 * 
 * Connects to a Matter bridge server (Python/Node.js) that handles
 * the actual Matter protocol. This is because Matter SDK requires
 * native code that's complex to integrate directly on Android.
 * 
 * The bridge exposes Matter devices as capabilities in the mesh.
 */
class MatterTransport(
    private val context: Context,
    private val config: MatterConfig = MatterConfig()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    
    // State
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    
    private val _devices = MutableStateFlow<List<MatterDevice>>(emptyList())
    val devices: StateFlow<List<MatterDevice>> = _devices.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Pending command callbacks
    private val pendingCommands = mutableMapOf<String, CompletableDeferred<Boolean>>()
    
    // HTTP client for bridge API
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    
    /**
     * Connect to Matter bridge.
     */
    suspend fun connect(): Boolean {
        if (_connected.value) return true
        
        return try {
            val request = Request.Builder()
                .url(config.bridgeUrl)
                .build()
            
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "Connected to Matter bridge")
                    _connected.value = true
                    _error.value = null
                    
                    // Request device list
                    ws.send(JSONObject().apply {
                        put("type", "list_devices")
                    }.toString())
                }
                
                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }
                
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Bridge closing: $reason")
                }
                
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Bridge closed: $reason")
                    _connected.value = false
                    scheduleReconnect()
                }
                
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Bridge error: ${t.message}")
                    _connected.value = false
                    _error.value = t.message
                    scheduleReconnect()
                }
            })
            
            // Wait for connection
            withTimeout(10_000) {
                _connected.first { it }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            _error.value = e.message
            false
        }
    }
    
    /**
     * Disconnect from Matter bridge.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Closing")
        webSocket = null
        _connected.value = false
    }
    
    /**
     * Execute command on a Matter device.
     */
    suspend fun executeCommand(
        nodeId: Long,
        endpoint: Int = 1,
        command: MatterCommand
    ): Boolean {
        if (!_connected.value) {
            Log.w(TAG, "Not connected to bridge")
            return false
        }
        
        val commandId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingCommands[commandId] = deferred
        
        try {
            val message = JSONObject().apply {
                put("type", "command")
                put("id", commandId)
                put("node_id", nodeId)
                put("endpoint", endpoint)
                put("command", command.toJson())
            }
            
            webSocket?.send(message.toString())
            
            return withTimeout(10_000) {
                deferred.await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            return false
        } finally {
            pendingCommands.remove(commandId)
        }
    }
    
    /**
     * Commission a new Matter device.
     */
    suspend fun commission(
        setupCode: String,
        discriminator: Int? = null
    ): MatterDevice? {
        if (!_connected.value) return null
        
        val commandId = java.util.UUID.randomUUID().toString()
        
        try {
            val message = JSONObject().apply {
                put("type", "commission")
                put("id", commandId)
                put("setup_code", setupCode)
                discriminator?.let { put("discriminator", it) }
            }
            
            webSocket?.send(message.toString())
            
            // Wait for device to appear
            return withTimeout(60_000) {
                _devices.first { devices ->
                    devices.any { it.lastSeen > System.currentTimeMillis() - 5000 }
                }.lastOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Commission failed: ${e.message}")
            return null
        }
    }
    
    /**
     * Get device state.
     */
    suspend fun getDeviceState(nodeId: Long): JSONObject? {
        if (!_connected.value) return null
        
        val commandId = java.util.UUID.randomUUID().toString()
        
        try {
            val message = JSONObject().apply {
                put("type", "get_state")
                put("id", commandId)
                put("node_id", nodeId)
            }
            
            webSocket?.send(message.toString())
            
            // TODO: Wait for response
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Get state failed: ${e.message}")
            return null
        }
    }
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            
            when (type) {
                "devices" -> {
                    val devicesArray = json.getJSONArray("devices")
                    val devices = mutableListOf<MatterDevice>()
                    
                    for (i in 0 until devicesArray.length()) {
                        val d = devicesArray.getJSONObject(i)
                        devices.add(parseDevice(d))
                    }
                    
                    _devices.value = devices
                    Log.i(TAG, "Got ${devices.size} Matter devices")
                }
                
                "device_update" -> {
                    val device = parseDevice(json.getJSONObject("device"))
                    _devices.value = _devices.value.map {
                        if (it.nodeId == device.nodeId) device else it
                    }
                }
                
                "command_result" -> {
                    val id = json.optString("id")
                    val success = json.optBoolean("success", false)
                    pendingCommands[id]?.complete(success)
                }
                
                "error" -> {
                    val error = json.optString("message", "Unknown error")
                    Log.e(TAG, "Bridge error: $error")
                    _error.value = error
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        }
    }
    
    private fun parseDevice(json: JSONObject): MatterDevice {
        val nodeId = json.getLong("node_id")
        val typeId = json.optInt("device_type", -1)
        
        val type = MatterDeviceType.values().find { it.clusterId == typeId }
            ?: MatterDeviceType.UNKNOWN
        
        val endpoints = mutableListOf<Int>()
        json.optJSONArray("endpoints")?.let { arr ->
            for (i in 0 until arr.length()) {
                endpoints.add(arr.getInt(i))
            }
        }
        
        return MatterDevice(
            nodeId = nodeId,
            name = json.optString("name", "Device $nodeId"),
            type = type,
            vendor = json.optString("vendor", null),
            product = json.optString("product", null),
            endpoints = endpoints,
            online = json.optBoolean("online", true)
        )
    }
    
    private fun scheduleReconnect() {
        if (!config.enabled) return
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000) // Wait 5 seconds before reconnecting
            if (!_connected.value) {
                Log.i(TAG, "Reconnecting to Matter bridge...")
                connect()
            }
        }
    }
    
    /**
     * Convert Matter devices to Atmosphere capabilities.
     */
    fun devicesToCapabilities(): List<MatterCapability> {
        return _devices.value.map { device ->
            MatterCapability(
                id = device.toCapabilityId(),
                name = device.name,
                device = device,
                actions = getActionsForDevice(device.type)
            )
        }
    }
    
    private fun getActionsForDevice(type: MatterDeviceType): List<String> {
        return when (type) {
            MatterDeviceType.LIGHT -> listOf("turn_on", "turn_off", "toggle")
            MatterDeviceType.DIMMER -> listOf("turn_on", "turn_off", "set_brightness")
            MatterDeviceType.COLOR_LIGHT -> listOf("turn_on", "turn_off", "set_color", "set_brightness")
            MatterDeviceType.THERMOSTAT -> listOf("set_temperature", "set_mode", "get_temperature")
            MatterDeviceType.LOCK -> listOf("lock", "unlock", "get_status")
            MatterDeviceType.SENSOR -> listOf("get_reading")
            MatterDeviceType.SWITCH -> listOf("press", "get_state")
            MatterDeviceType.UNKNOWN -> listOf("raw_command")
        }
    }
    
    companion object {
        /**
         * Check if Matter is available (bridge reachable).
         */
        suspend fun isAvailable(bridgeUrl: String = "ws://localhost:11452"): Boolean {
            return try {
                // Quick HTTP check to bridge health endpoint
                val client = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .build()
                
                val httpUrl = bridgeUrl
                    .replace("ws://", "http://")
                    .replace("wss://", "https://")
                    .removeSuffix("/") + "/health"
                
                val request = Request.Builder()
                    .url(httpUrl)
                    .get()
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        response.isSuccessful
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Matter capability exposed to the mesh.
 */
data class MatterCapability(
    val id: String,
    val name: String,
    val device: MatterDevice,
    val actions: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", "matter")
        put("device_type", device.type.name)
        put("actions", JSONArray(actions))
        put("online", device.online)
    }
}
