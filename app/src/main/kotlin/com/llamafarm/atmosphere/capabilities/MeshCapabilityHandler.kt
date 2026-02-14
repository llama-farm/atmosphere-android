package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.mesh.ModelCatalog
import com.llamafarm.atmosphere.core.GossipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MeshCapabilityHandler"

data class MeshMessage(
    val payload: ByteArray,
    val sourceNodeId: String?
)

/**
 * Handles capability registration and invocation over the mesh network.
 */
class MeshCapabilityHandler(
    private val context: Context,
    private val nodeId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var voiceCapability: VoiceCapability? = null
    private var cameraCapability: CameraCapability? = null
    
    private val modelCatalog = ModelCatalog()
    private var gossipManager: GossipManager? = null
    
    private val localCapabilities = mutableMapOf<String, CapabilityInfo>()
    
    data class CapabilityInfo(
        val name: String,
        val description: String,
        val version: String = "1.0",
        val params: Map<String, String> = emptyMap(),
        val requiresApproval: Boolean = false
    )
    
    init {
        voiceCapability = VoiceCapability(context)
        cameraCapability = CameraCapability(context)
        registerLocalCapabilities()
    }
    
    private fun registerLocalCapabilities() {
        if (voiceCapability?.sttAvailable?.value == true) {
            localCapabilities["speech_to_text"] = CapabilityInfo(
                name = "speech_to_text",
                description = "Convert speech to text using device microphone",
                params = mapOf("locale" to "Language locale", "max_duration_ms" to "Maximum recording duration"),
                requiresApproval = true
            )
        }
        
        if (voiceCapability?.ttsAvailable?.value == true) {
            localCapabilities["text_to_speech"] = CapabilityInfo(
                name = "text_to_speech",
                description = "Convert text to speech using device speaker",
                params = mapOf("text" to "Text to speak", "locale" to "Language locale"),
                requiresApproval = false
            )
        }
        
        if (cameraCapability?.isAvailable?.value == true) {
            localCapabilities["camera_capture"] = CapabilityInfo(
                name = "camera_capture",
                description = "Capture photo with device camera",
                params = mapOf("facing" to "Camera facing (front/back)", "quality" to "Image quality (0-100)"),
                requiresApproval = true
            )
        }
        
        Log.i(TAG, "Registered ${localCapabilities.size} local capabilities")
    }
    
    fun setMeshConnection(connection: Any) {}
    
    fun setGossipManager(manager: GossipManager) {
        gossipManager = manager
        scope.launch {
            manager.modelCatalogUpdates.collect { (catalogJson, sourceNodeId) ->
                val nodeName = catalogJson.optString("node_name", sourceNodeId)
                modelCatalog.processCatalogMessage(sourceNodeId, nodeName, catalogJson)
            }
        }
    }
    
    fun getModelCatalog(): ModelCatalog = modelCatalog
    fun clearMeshConnection() {}
    
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
        
        return when (capabilityName) {
            "speech_to_text" -> handleSttRequest(requestId, params, requesterId)
            "text_to_speech" -> handleTtsRequest(requestId, params)
            "camera_capture" -> errorResponse("Camera capture not yet implemented", requestId)
            "voice" -> {
                if (params.has("text")) handleTtsRequest(requestId, params)
                else handleSttRequest(requestId, params, requesterId)
            }
            else -> errorResponse("Unknown capability: $capabilityName", requestId)
        }
    }
    
    private suspend fun handleSttRequest(requestId: String, params: JSONObject, requesterId: String): JSONObject {
        val voice = voiceCapability ?: return errorResponse("STT not available", requestId)
        if (voice.sttAvailable.value != true) return errorResponse("STT not available", requestId)
        return voice.handleSttMeshRequest(params.apply { put("request_id", requestId) }, requesterId)
    }
    
    private suspend fun handleTtsRequest(requestId: String, params: JSONObject): JSONObject {
        val voice = voiceCapability ?: return errorResponse("TTS not available", requestId)
        if (voice.ttsAvailable.value != true) return errorResponse("TTS not available", requestId)
        return voice.handleTtsMeshRequest(params.apply { put("request_id", requestId) })
    }
    
    private fun errorResponse(message: String, requestId: String? = null): JSONObject {
        return JSONObject().apply {
            requestId?.let { put("request_id", it) }
            put("error", message)
            put("status", "error")
        }
    }
    
    fun getCapabilityNames(): List<String> = localCapabilities.keys.toList()
    
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
    
    fun setVoiceApprovalCallback(callback: (requestId: String, requester: String) -> Boolean) {
        voiceCapability?.setMicApprovalCallback(callback)
    }
    
    fun handleVoiceApproval(requestId: String, approved: Boolean) {
        voiceCapability?.handleApprovalResponse(requestId, approved)
    }
    
    fun destroy() {
        voiceCapability?.destroy()
        cameraCapability?.destroy()
        localCapabilities.clear()
    }
}
