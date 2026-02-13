package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.mesh.ModelCatalog
import com.llamafarm.atmosphere.vision.VisionModelManager
import com.llamafarm.atmosphere.core.GossipManager
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
    private var visionCapability: com.llamafarm.atmosphere.vision.VisionCapability? = null
    
    // Model catalog and transfer
    private val modelCatalog = ModelCatalog()
    private var visionModelManager: VisionModelManager? = null
    private var gossipManager: GossipManager? = null
    
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
        
        // Initialize vision capability (requires camera)
        visionCapability = com.llamafarm.atmosphere.vision.VisionCapability(
            context = context,
            nodeId = nodeId,
            cameraCapability = cameraCapability!!
        )
        
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
        
        // Vision Detection
        if (visionCapability?.isReady?.value == true) {
            localCapabilities["vision_detect"] = CapabilityInfo(
                name = "vision_detect",
                description = "Object detection with on-device AI",
                params = mapOf(
                    "image_base64" to "Base64-encoded image",
                    "confidence_threshold" to "Minimum confidence (0.0-1.0)"
                ),
                requiresApproval = false
            )
            Log.i(TAG, "Registered capability: vision_detect")
        }
        
        // Vision Classification
        if (visionCapability?.isReady?.value == true) {
            localCapabilities["vision_classify"] = CapabilityInfo(
                name = "vision_classify",
                description = "Image classification with on-device AI",
                params = mapOf(
                    "image_base64" to "Base64-encoded image"
                ),
                requiresApproval = false
            )
            Log.i(TAG, "Registered capability: vision_classify")
        }
        
        Log.i(TAG, "Registered ${localCapabilities.size} local capabilities")
    }
    
    /**
     * LEGACY - Stub for backwards compatibility. Capabilities now in CRDT _capabilities collection.
     */
    fun setMeshConnection(connection: Any) {
        // No-op: Capabilities managed via CRDT mesh, not relay
        Log.d(TAG, "setMeshConnection called but ignored (CRDT mesh handles capabilities)")
    }
    
    /**
     * Set the GossipManager for listening to model catalog updates.
     */
    fun setGossipManager(manager: GossipManager) {
        gossipManager = manager
        
        // Listen for model catalog updates
        scope.launch {
            manager.modelCatalogUpdates.collect { (catalogJson, sourceNodeId) ->
                val nodeName = catalogJson.optString("node_name", sourceNodeId)
                modelCatalog.processCatalogMessage(sourceNodeId, nodeName, catalogJson)
                
                // Check for new model versions
                checkForModelUpdates()
            }
        }
    }
    
    /**
     * Set the VisionModelManager for auto-updating models.
     */
    fun setVisionModelManager(manager: VisionModelManager) {
        visionModelManager = manager
        
        // Register vision model capabilities when models are loaded
        // (will update announcements when new models are available)
    }
    
    /**
     * Check for model updates and trigger downloads if needed.
     */
    private fun checkForModelUpdates() {
        val manager = visionModelManager ?: return
        
        scope.launch {
            try {
                // Get vision models from catalog
                val visionModels = modelCatalog.getModelsByType(com.llamafarm.atmosphere.mesh.ModelType.VISION)
                
                for (model in visionModels) {
                    // Check if we have this model locally
                    val localMetadata = manager.getModelMetadata(model.modelId, model.version)
                    val localVersion = localMetadata?.version
                    
                    if (localVersion == null || isNewerVersion(model.version, localVersion)) {
                        Log.i(TAG, "New model version available: ${model.modelId} v${model.version} (local: $localVersion)")
                        
                        // Auto-queue download
                        val bestPeer = model.getBestPeer()
                        if (bestPeer != null) {
                            Log.i(TAG, "Queueing download from ${bestPeer.nodeName} via ${bestPeer.httpEndpoint ?: "websocket"}")
                            // TODO: Trigger download via ModelTransferService
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for model updates: ${e.message}", e)
            }
        }
    }
    
    /**
     * Compare version strings (simple semantic versioning).
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrNull(i) ?: 0
                val currentPart = currentParts.getOrNull(i) ?: 0
                
                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
            
            return false
        } catch (e: Exception) {
            // Fall back to string comparison
            return newVersion > currentVersion
        }
    }
    
    /**
     * Get the model catalog.
     */
    fun getModelCatalog(): ModelCatalog = modelCatalog
    
    /**
     * LEGACY - Stub for backwards compatibility. Capabilities now in CRDT _capabilities collection.
     */
    fun clearMeshConnection() {
        // No-op: Capabilities managed via CRDT mesh, not relay
    }
    
    /**
     * LEGACY - Capabilities now announced via CRDT _capabilities collection.
     */
    private fun announceCapabilities() {
        // No-op: Capabilities managed via CRDT mesh, not relay
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
            "vision_detect", "vision_classify" -> handleVisionRequest(requestId, params)
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
     * Handle Vision detection/classification request.
     */
    private suspend fun handleVisionRequest(
        requestId: String,
        params: JSONObject
    ): JSONObject {
        val vision = visionCapability ?: return errorResponse("Vision not available", requestId)
        
        if (vision.isReady.value != true) {
            return errorResponse("Vision model not loaded", requestId)
        }
        
        return vision.handleVisionRequest(params.apply {
            put("request_id", requestId)
        })
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
        visionCapability?.destroy()
        localCapabilities.clear()
    }
}
