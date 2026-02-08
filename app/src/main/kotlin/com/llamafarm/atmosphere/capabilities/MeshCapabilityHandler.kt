package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.network.MeshConnection
// import com.llamafarm.atmosphere.network.MeshMessage // TODO: Update to new message format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MeshCapabilityHandler"

// TODO: Replace with proper message format from new API
data class MeshMessage(
    val payload: ByteArray,
    val sourceNodeId: String?
)

/**
 * Handles capability registration and invocation over the mesh network.
 * 
 * Exposes local capabilities (voice, camera, etc.) to remote mesh peers
 * and handles incoming capability requests.
 */
class MeshCapabilityHandler(
    private val context: Context,
    private val nodeId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Local capabilities
    private var voiceCapability: VoiceCapability? = null
    private var cameraCapability: CameraCapability? = null
    
    // Mesh connection for sending/receiving
    private var meshConnection: MeshConnection? = null
    
    // Registered capabilities
    private val localCapabilities = mutableMapOf<String, CapabilityInfo>()
    
    data class CapabilityInfo(
        val name: String,
        val description: String,
        val version: String = "1.0",
        val params: Map<String, String> = emptyMap(),
        val requiresApproval: Boolean = false
    )
    
    init {
        // Initialize capabilities
        voiceCapability = VoiceCapability(context)
        cameraCapability = CameraCapability(context)
        
        // Register local capabilities
        registerLocalCapabilities()
    }
    
    /**
     * Register all local capabilities.
     */
    private fun registerLocalCapabilities() {
        // Voice: Speech-to-Text
        if (voiceCapability?.sttAvailable?.value == true) {
            localCapabilities["speech_to_text"] = CapabilityInfo(
                name = "speech_to_text",
                description = "Convert speech to text using device microphone",
                params = mapOf(
                    "locale" to "Language locale (e.g., en-US)",
                    "max_duration_ms" to "Maximum recording duration"
                ),
                requiresApproval = true
            )
            Log.i(TAG, "Registered capability: speech_to_text")
        }
        
        // Voice: Text-to-Speech
        if (voiceCapability?.ttsAvailable?.value == true) {
            localCapabilities["text_to_speech"] = CapabilityInfo(
                name = "text_to_speech",
                description = "Convert text to speech using device speaker",
                params = mapOf(
                    "text" to "Text to speak",
                    "locale" to "Language locale",
                    "pitch" to "Voice pitch (0.5-2.0)",
                    "speech_rate" to "Speaking rate (0.5-2.0)"
                ),
                requiresApproval = false
            )
            Log.i(TAG, "Registered capability: text_to_speech")
        }
        
        // Camera
        if (cameraCapability?.isAvailable?.value == true) {
            localCapabilities["camera_capture"] = CapabilityInfo(
                name = "camera_capture",
                description = "Capture photo with device camera",
                params = mapOf(
                    "facing" to "Camera facing (front/back)",
                    "quality" to "Image quality (0-100)"
                ),
                requiresApproval = true
            )
            Log.i(TAG, "Registered capability: camera_capture")
        }
        
        Log.i(TAG, "Registered ${localCapabilities.size} local capabilities")
    }
    
    /**
     * Set the mesh connection for sending capability announcements.
     */
    fun setMeshConnection(connection: MeshConnection) {
        meshConnection = connection
        
        // Announce capabilities to mesh
        announceCapabilities()
    }
    
    /**
     * Clear the mesh connection.
     */
    fun clearMeshConnection() {
        meshConnection = null
    }
    
    /**
     * Announce local capabilities to the mesh.
     */
    private fun announceCapabilities() {
        val connection = meshConnection ?: return
        
        val announcement = JSONObject().apply {
            put("type", "capability_announce")
            put("node_id", nodeId)
            put("capabilities", JSONArray().apply {
                localCapabilities.values.forEach { cap ->
                    put(JSONObject().apply {
                        put("name", cap.name)
                        put("description", cap.description)
                        put("version", cap.version)
                        put("requires_approval", cap.requiresApproval)
                        put("params", JSONObject(cap.params))
                    })
                }
            })
        }
        
        scope.launch {
            try {
                connection.sendMessage(announcement)
                Log.i(TAG, "Announced ${localCapabilities.size} capabilities to mesh")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to announce capabilities", e)
            }
        }
    }
    
    /**
     * Handle incoming capability request from mesh.
     */
    suspend fun handleCapabilityRequest(message: MeshMessage): JSONObject {
        val payload = try {
            JSONObject(String(message.payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return errorResponse("Invalid request payload")
        }
        
        val capabilityName = payload.optString("capability", "")
        val requestId = payload.optString("request_id", java.util.UUID.randomUUID().toString())
        val params = payload.optJSONObject("params") ?: JSONObject()
        val requesterId = message.sourceNodeId ?: "unknown"
        
        Log.i(TAG, "Handling capability request: $capabilityName from $requesterId")
        
        return when (capabilityName) {
            "speech_to_text" -> handleSttRequest(requestId, params, requesterId)
            "text_to_speech" -> handleTtsRequest(requestId, params)
            "camera_capture" -> handleCameraRequest(requestId, params, requesterId)
            "voice" -> {
                // Combined voice capability - determine STT or TTS from params
                if (params.has("text")) {
                    handleTtsRequest(requestId, params)
                } else {
                    handleSttRequest(requestId, params, requesterId)
                }
            }
            else -> errorResponse("Unknown capability: $capabilityName", requestId)
        }
    }
    
    /**
     * Handle Speech-to-Text request.
     */
    private suspend fun handleSttRequest(
        requestId: String,
        params: JSONObject,
        requesterId: String
    ): JSONObject {
        val voice = voiceCapability ?: return errorResponse("STT not available", requestId)
        
        if (voice.sttAvailable.value != true) {
            return errorResponse("STT not available on this device", requestId)
        }
        
        return voice.handleSttMeshRequest(params.apply {
            put("request_id", requestId)
        }, requesterId)
    }
    
    /**
     * Handle Text-to-Speech request.
     */
    private suspend fun handleTtsRequest(
        requestId: String,
        params: JSONObject
    ): JSONObject {
        val voice = voiceCapability ?: return errorResponse("TTS not available", requestId)
        
        if (voice.ttsAvailable.value != true) {
            return errorResponse("TTS not available on this device", requestId)
        }
        
        return voice.handleTtsMeshRequest(params.apply {
            put("request_id", requestId)
        })
    }
    
    /**
     * Handle Camera capture request.
     */
    private suspend fun handleCameraRequest(
        requestId: String,
        params: JSONObject,
        requesterId: String
    ): JSONObject {
        val camera = cameraCapability ?: return errorResponse("Camera not available", requestId)
        
        if (camera.isAvailable.value != true) {
            return errorResponse("Camera not available on this device", requestId)
        }
        
        // TODO: Implement camera capture with approval
        return errorResponse("Camera capture not yet implemented", requestId)
    }
    
    /**
     * Create error response JSON.
     */
    private fun errorResponse(message: String, requestId: String? = null): JSONObject {
        return JSONObject().apply {
            requestId?.let { put("request_id", it) }
            put("error", message)
            put("status", "error")
        }
    }
    
    /**
     * Get list of local capability names.
     */
    fun getCapabilityNames(): List<String> = localCapabilities.keys.toList()
    
    /**
     * Get capability info as JSON for mesh registration.
     */
    fun getCapabilitiesJson(): JSONArray {
        return JSONArray().apply {
            localCapabilities.values.forEach { cap ->
                put(JSONObject().apply {
                    put("name", cap.name)
                    put("description", cap.description)
                    put("version", cap.version)
                    put("requires_approval", cap.requiresApproval)
                })
            }
        }
    }
    
    /**
     * Set approval callback for voice STT requests.
     */
    fun setVoiceApprovalCallback(callback: (requestId: String, requester: String) -> Boolean) {
        voiceCapability?.setMicApprovalCallback(callback)
    }
    
    /**
     * Handle approval response from UI.
     */
    fun handleVoiceApproval(requestId: String, approved: Boolean) {
        voiceCapability?.handleApprovalResponse(requestId, approved)
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        voiceCapability?.destroy()
        cameraCapability?.destroy()
        meshConnection = null
        localCapabilities.clear()
    }
}
