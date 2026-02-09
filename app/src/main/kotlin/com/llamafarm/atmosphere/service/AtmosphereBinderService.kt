package com.llamafarm.atmosphere.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.llamafarm.atmosphere.IAtmosphereCallback
import com.llamafarm.atmosphere.IAtmosphereService
import com.llamafarm.atmosphere.AtmosphereCapability
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.rag.LocalRagStore
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.llamafarm.LlamaFarmLite
import com.llamafarm.atmosphere.vision.DetectionResult
import com.llamafarm.atmosphere.vision.BoundingBox
import com.llamafarm.atmosphere.vision.EscalationEnvelope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data class tracking a connected SDK client app.
 */
data class ConnectedClient(
    val uid: Int,
    val packageName: String,
    val connectedAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    var requestCount: Int = 0,
    var tokensGenerated: Long = 0,
    var ragQueriesCount: Int = 0,
    var capabilitiesUsed: MutableSet<String> = mutableSetOf()
)

/**
 * AIDL Binder Service - Exposes Atmosphere mesh to other Android apps.
 * 
 * Other apps can bind to this service using:
 *   Intent().setAction("com.llamafarm.atmosphere.BIND")
 *           .setPackage("com.llamafarm.atmosphere")
 */
class AtmosphereBinderService : Service() {
    
    companion object {
        private const val TAG = "AtmosphereBinderService"
        const val ACTION_BIND = "com.llamafarm.atmosphere.BIND"
        const val VERSION = "1.0.0"
        
        // Static list of connected clients for UI access
        private val _connectedClients = mutableMapOf<Int, ConnectedClient>()
        val connectedClients: Map<Int, ConnectedClient> get() = _connectedClients.toMap()
        
        fun getConnectedClientsList(): List<ConnectedClient> = _connectedClients.values.toList()
    }
    
    private val callbacks = RemoteCallbackList<IAtmosphereCallback>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track third-party registered capabilities
    private val thirdPartyCapabilities = mutableMapOf<String, AtmosphereCapability>()
    private var capabilityIdCounter = 0
    
    // RAG store for on-device knowledge retrieval
    private val ragStore = LocalRagStore()
    
    private val binder = object : IAtmosphereService.Stub() {
        
        override fun getVersion(): String = VERSION
        
        override fun route(intent: String?, payload: String?): String {
            Log.d(TAG, "route() called: intent=$intent")
            recordClientActivity(capability = "route")
            
            if (intent.isNullOrBlank()) {
                return errorJson("Intent cannot be empty")
            }
            
            return try {
                val startTime = System.currentTimeMillis()
                
                // Get the router from application
                val app = applicationContext as? AtmosphereApplication
                val router = app?.semanticRouter
                
                if (router != null) {
                    // Use the actual semantic router
                    val result = runBlocking {
                        router.route(intent)
                    }
                    
                    val latencyMs = System.currentTimeMillis() - startTime
                    
                    JSONObject().apply {
                        put("status", "success")
                        put("intent", intent)
                        put("capability", result?.capability?.capabilityId ?: "unknown")
                        put("nodeId", result?.capability?.nodeId ?: "local")
                        put("explanation", result?.explanation ?: "No routing decision")
                        put("latencyMs", latencyMs)
                    }.toString()
                } else {
                    // Fallback: routing not connected
                    JSONObject().apply {
                        put("status", "success")
                        put("intent", intent)
                        put("capability", "local-fallback")
                        put("response", "Router not initialized")
                        put("latencyMs", 0)
                    }.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "route() error", e)
                errorJson("Route failed: ${e.message}")
            }
        }
        
        override fun chatCompletion(messagesJson: String?, model: String?): String {
            Log.d(TAG, "chatCompletion() called: model=$model")
            recordClientActivity(capability = "chat")
            
            if (messagesJson.isNullOrBlank()) {
                return errorJson("Messages cannot be empty")
            }
            
            return try {
                // Parse messages
                val messages = JSONArray(messagesJson)
                val app = applicationContext as? AtmosphereApplication
                
                // Check if connected to mesh - prefer mesh routing for better models
                val meshConnection = app?.meshConnection
                val connectionState = meshConnection?.connectionState?.value
                val isConnectedToMesh = connectionState?.name == "CONNECTED"
                
                // Get the real service for mesh routing
                val connector = ServiceManager.getConnector()
                val atmosphereService = connector.getService()
                val meshConnected = atmosphereService?.isConnected() == true
                
                if (meshConnected && atmosphereService != null) {
                    Log.d(TAG, "Routing chat through mesh via AtmosphereService...")
                    
                    // Extract the last user message as prompt
                    var prompt = ""
                    for (i in messages.length() - 1 downTo 0) {
                        val msg = messages.getJSONObject(i)
                        if (msg.optString("role") == "user") {
                            prompt = msg.optString("content", "")
                            break
                        }
                    }
                    
                    if (prompt.isEmpty()) {
                        return errorJson("No user message found")
                    }
                    
                    // Use blocking coroutine to send through mesh
                    val result = runBlocking {
                        var response: String? = null
                        var error: String? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        
                        atmosphereService.sendLlmRequest(prompt, model) { resp, err ->
                            response = resp
                            error = err
                            latch.countDown()
                        }
                        
                        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        
                        if (!error.isNullOrEmpty()) {
                            errorJson("Mesh error: $error")
                        } else if (!response.isNullOrEmpty()) {
                            JSONObject().apply {
                                put("choices", JSONArray().put(JSONObject().apply {
                                    put("message", JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", response)
                                    })
                                    put("finish_reason", "stop")
                                }))
                                put("model", model ?: "mesh-routed")
                            }.toString()
                        } else {
                            errorJson("No response from mesh (timeout)")
                        }
                    }
                    
                    result
                } else {
                    // Use local inference
                    Log.d(TAG, "Using local inference (not connected to mesh)")
                    executeLocalInference(app, messages, model)
                }
            } catch (e: Exception) {
                Log.e(TAG, "chatCompletion() error", e)
                errorJson("Chat completion failed: ${e.message}")
            }
        }
        
        override fun chatCompletionStream(
            requestId: String?,
            messagesJson: String?,
            model: String?,
            callback: IAtmosphereCallback?
        ) {
            Log.d(TAG, "chatCompletionStream() called: requestId=$requestId, model=$model")
            recordClientActivity(capability = "chat_stream")
            
            if (requestId.isNullOrBlank() || messagesJson.isNullOrBlank() || callback == null) {
                callback?.onStreamChunk(requestId ?: "unknown", errorJson("Invalid parameters"), true)
                return
            }
            
            // Launch streaming in background
            serviceScope.launch {
                try {
                    // TODO: Implement actual streaming from mesh
                    // For now, send a single chunk with error message
                    callback.onStreamChunk(requestId, errorJson("Streaming not yet implemented"), true)
                } catch (e: Exception) {
                    Log.e(TAG, "chatCompletionStream() error", e)
                    try {
                        callback.onStreamChunk(requestId, errorJson("Stream failed: ${e.message}"), true)
                    } catch (cbError: Exception) {
                        Log.e(TAG, "Failed to send error chunk", cbError)
                    }
                }
            }
        }
        
        private fun executeLocalInference(
            app: AtmosphereApplication?,
            messages: JSONArray,
            model: String?
        ): String {
            val inferenceEngine = LocalInferenceEngine.getInstance(applicationContext)
            
            return if (LocalInferenceEngine.isNativeAvailable() && inferenceEngine.state.value.isModelLoaded) {
                // Build prompt from messages
                val prompt = buildPromptFromMessages(messages)
                
                // Collect all tokens from the flow
                val responseBuilder = StringBuilder()
                var tokenCount = 0
                
                runBlocking {
                    try {
                        inferenceEngine.generate(prompt).collect { token ->
                            responseBuilder.append(token)
                            tokenCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Local inference error", e)
                        responseBuilder.append("[Error: ${e.message}]")
                    }
                }
                
                val modelInfo = inferenceEngine.getModelInfo()
                
                JSONObject().apply {
                    put("id", "chat-local-${System.currentTimeMillis()}")
                    put("object", "chat.completion")
                    put("model", model ?: modelInfo?.path?.substringAfterLast('/') ?: "local")
                    put("routed_via", "local")
                    put("choices", JSONArray().apply {
                        put(JSONObject().apply {
                            put("index", 0)
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", responseBuilder.toString())
                            })
                            put("finish_reason", "stop")
                        })
                    })
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", prompt.length / 4) // Estimate
                        put("completion_tokens", tokenCount)
                        put("total_tokens", (prompt.length / 4) + tokenCount)
                    })
                }.toString()
            } else {
                // No inference available
                JSONObject().apply {
                    put("id", "chat-${System.currentTimeMillis()}")
                    put("object", "chat.completion")
                    put("model", model ?: "none")
                    put("choices", JSONArray().apply {
                        put(JSONObject().apply {
                            put("index", 0)
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", "No inference available. Connect to mesh or load a local model.")
                            })
                            put("finish_reason", "stop")
                        })
                    })
                }.toString()
            }
        }
        
        override fun getCapabilities(): List<AtmosphereCapability> {
            Log.d(TAG, "getCapabilities() called")
            
            val capabilities = mutableListOf<AtmosphereCapability>()
            
            // TODO: Get capabilities from GossipManager/SemanticRouter with new API
            // For now, return third-party and default capabilities
            
            // Add third-party registered capabilities
            capabilities.addAll(thirdPartyCapabilities.values)
            
            // If no capabilities, return defaults
            if (capabilities.isEmpty()) {
                return getDefaultCapabilities()
            }
            
            return capabilities
        }
        
        override fun getCapability(capabilityId: String?): AtmosphereCapability? {
            if (capabilityId == null) return null
            
            // Check third-party first
            thirdPartyCapabilities[capabilityId]?.let { return it }
            
            // TODO: Check GossipManager for mesh capabilities with new API
            return null
        }
        
        override fun invokeCapability(capabilityId: String?, payload: String?): String {
            Log.d(TAG, "invokeCapability() called: id=$capabilityId")
            
            if (capabilityId.isNullOrBlank()) {
                return errorJson("Capability ID required")
            }
            
            return try {
                // TODO: Implement capability invocation with new mesh API
                errorJson("Capability invocation not yet implemented")
            } catch (e: Exception) {
                Log.e(TAG, "invokeCapability() error", e)
                errorJson("Invoke failed: ${e.message}")
            }
        }
        
        override fun getMeshStatus(): String {
            Log.d(TAG, "getMeshStatus() called")
            
            // Query the real AtmosphereService via ServiceManager
            val connector = ServiceManager.getConnector()
            val service = connector.getService()
            
            return if (service != null) {
                val gossipManager = com.llamafarm.atmosphere.core.GossipManager.getInstance(applicationContext)
                val stats = gossipManager.getStats()
                val totalCaps = (stats["total_capabilities"] as? Number)?.toInt() ?: 0
                val isConnected = service.isConnected()
                val nodeId = service.getNodeId() ?: "unknown"
                
                JSONObject().apply {
                    put("connected", isConnected)
                    put("nodeId", nodeId)
                    put("meshId", service.getMeshId() ?: "")
                    put("peerCount", service.getRelayPeerCount())
                    put("capabilities", totalCaps)
                    put("relayConnected", isConnected)
                }.toString()
            } else {
                JSONObject().apply {
                    put("connected", false)
                    put("nodeId", "unknown")
                    put("meshId", "")
                    put("peerCount", 0)
                    put("capabilities", 0)
                    put("relayConnected", false)
                }.toString()
            }
        }
        
        override fun getCostMetrics(): String {
            Log.d(TAG, "getCostMetrics() called")
            
            val app = applicationContext as? AtmosphereApplication
            val costCollector = app?.costCollector
            
            return if (costCollector != null) {
                val metrics = costCollector.currentMetrics
                JSONObject().apply {
                    put("battery", metrics.battery)
                    put("cpu", metrics.cpu)
                    put("memory", metrics.memory)
                    put("network", metrics.networkType)
                    put("thermal", metrics.thermalState)
                    put("overall", metrics.overallCost)
                }.toString()
            } else {
                // Default values
                JSONObject().apply {
                    put("battery", 0.85)
                    put("cpu", 0.15)
                    put("memory", 0.40)
                    put("network", "wifi")
                    put("thermal", "nominal")
                    put("overall", 0.25)
                }.toString()
            }
        }
        
        override fun joinMesh(meshId: String?, credentialsJson: String?): String {
            Log.d(TAG, "joinMesh() called: meshId=$meshId")
            
            return try {
                // TODO: Implement mesh join with new API
                errorJson("Mesh join not yet implemented")
            } catch (e: Exception) {
                Log.e(TAG, "joinMesh() error", e)
                errorJson("Join failed: ${e.message}")
            }
        }
        
        override fun leaveMesh(): String {
            Log.d(TAG, "leaveMesh() called")
            
            return try {
                // TODO: Implement mesh leave with new API
                JSONObject().apply {
                    put("success", true)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "leaveMesh() error", e)
                errorJson("Leave failed: ${e.message}")
            }
        }
        
        override fun registerCapability(capabilityJson: String?): String {
            Log.d(TAG, "registerCapability() called")
            
            if (capabilityJson.isNullOrBlank()) {
                return errorJson("Capability JSON required")
            }
            
            return try {
                val json = JSONObject(capabilityJson)
                val id = "ext-${++capabilityIdCounter}-${System.currentTimeMillis()}"
                
                val capability = AtmosphereCapability(
                    id = id,
                    name = json.optString("name", "Unknown"),
                    type = json.optString("type", "custom"),
                    nodeId = "external-app",
                    cost = json.optDouble("cost", 0.5).toFloat(),
                    available = true,
                    metadata = json.optString("metadata", "{}")
                )
                
                thirdPartyCapabilities[id] = capability
                
                // Notify mesh about new capability
                broadcastCapabilityEvent(id, JSONObject().apply {
                    put("event", "registered")
                    put("capability", capabilityJson)
                }.toString())
                
                JSONObject().apply {
                    put("success", true)
                    put("capabilityId", id)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "registerCapability() error", e)
                errorJson("Register failed: ${e.message}")
            }
        }
        
        override fun unregisterCapability(capabilityId: String?) {
            Log.d(TAG, "unregisterCapability() called: id=$capabilityId")
            
            capabilityId ?: return
            thirdPartyCapabilities.remove(capabilityId)
            
            // Notify mesh
            broadcastCapabilityEvent(capabilityId, JSONObject().apply {
                put("event", "unregistered")
            }.toString())
        }
        
        override fun registerCallback(callback: IAtmosphereCallback?) {
            Log.d(TAG, "registerCallback() called")
            callback?.let { callbacks.register(it) }
        }
        
        override fun unregisterCallback(callback: IAtmosphereCallback?) {
            Log.d(TAG, "unregisterCallback() called")
            callback?.let { callbacks.unregister(it) }
        }
        
        // ========================== RAG API Implementation ==========================
        
        override fun createRagIndex(indexId: String?, documentsJson: String?): String {
            Log.d(TAG, "createRagIndex() called: indexId=$indexId")
            
            if (indexId.isNullOrBlank()) {
                return errorJson("Index ID required")
            }
            if (documentsJson.isNullOrBlank()) {
                return errorJson("Documents JSON required")
            }
            
            return try {
                val index = runBlocking {
                    ragStore.createIndexFromJson(indexId, documentsJson)
                }
                
                JSONObject().apply {
                    put("success", true)
                    put("indexId", indexId)
                    put("documentCount", index.documents.size)
                    put("uniqueTerms", index.documentFrequencies.size)
                    put("avgDocLength", index.avgDocLength)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "createRagIndex() error", e)
                errorJson("Failed to create index: ${e.message}")
            }
        }
        
        override fun queryRag(indexId: String?, query: String?, generateAnswer: Boolean): String {
            Log.d(TAG, "queryRag() called: indexId=$indexId, query=$query, generateAnswer=$generateAnswer")
            recordClientActivity(capability = "rag", ragQuery = true)
            
            if (indexId.isNullOrBlank()) {
                return errorJson("Index ID required")
            }
            if (query.isNullOrBlank()) {
                return errorJson("Query required")
            }
            
            return try {
                // Retrieve relevant documents
                val results = runBlocking {
                    ragStore.query(indexId, query, topK = 3)
                }
                
                val retrievedDocs = JSONArray().apply {
                    results.forEach { result ->
                        put(JSONObject().apply {
                            put("id", result.document.id)
                            put("content", result.document.content)
                            put("score", result.score)
                            put("matchedTerms", JSONArray(result.matchedTerms))
                        })
                    }
                }
                
                // Build context from retrieved docs
                val context = results.joinToString("\n\n") { it.document.content }
                
                // Optionally generate answer using LLM
                val answer = if (generateAnswer && context.isNotBlank()) {
                    val inferenceEngine = LocalInferenceEngine.getInstance(applicationContext)
                    
                    if (LocalInferenceEngine.isNativeAvailable() && inferenceEngine.state.value.isModelLoaded) {
                        // Build RAG prompt
                        val ragPrompt = buildRagPrompt(query, context)
                        
                        runBlocking {
                            val responseBuilder = StringBuilder()
                            try {
                                inferenceEngine.generate(ragPrompt).collect { token ->
                                    responseBuilder.append(token)
                                }
                            } catch (e: Exception) {
                                responseBuilder.append("[Error: ${e.message}]")
                            }
                            responseBuilder.toString()
                        }
                    } else {
                        // No inference engine - just return context
                        "Based on the documents:\n$context"
                    }
                } else {
                    null
                }
                
                JSONObject().apply {
                    put("success", true)
                    put("query", query)
                    put("indexId", indexId)
                    put("documents", retrievedDocs)
                    put("documentCount", results.size)
                    if (answer != null) {
                        put("answer", answer)
                    }
                    put("context", context)
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "queryRag() error", e)
                errorJson("Query failed: ${e.message}")
            }
        }
        
        override fun deleteRagIndex(indexId: String?): String {
            Log.d(TAG, "deleteRagIndex() called: indexId=$indexId")
            
            if (indexId.isNullOrBlank()) {
                return errorJson("Index ID required")
            }
            
            return try {
                val deleted = ragStore.deleteIndex(indexId)
                
                JSONObject().apply {
                    put("success", deleted)
                    if (!deleted) {
                        put("message", "Index not found: $indexId")
                    }
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "deleteRagIndex() error", e)
                errorJson("Delete failed: ${e.message}")
            }
        }
        
        override fun listRagIndexes(): String {
            Log.d(TAG, "listRagIndexes() called")
            
            return try {
                val indexes = ragStore.listIndexes()
                
                JSONObject().apply {
                    put("success", true)
                    put("indexes", JSONArray().apply {
                        indexes.forEach { index ->
                            put(JSONObject().apply {
                                put("id", index.id)
                                put("documentCount", index.documents.size)
                                put("uniqueTerms", index.documentFrequencies.size)
                                put("avgDocLength", index.avgDocLength)
                                put("createdAt", index.createdAt)
                            })
                        }
                    })
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "listRagIndexes() error", e)
                errorJson("List failed: ${e.message}")
            }
        }
        
        // ========================== Vision and LlamaFarm API Implementation ==========================
        
        override fun addRagDocument(namespace: String?, docId: String?, content: String?, metadata: String?): Boolean {
            Log.d(TAG, "addRagDocument() called: namespace=$namespace, docId=$docId")
            recordClientActivity(capability = "rag", ragQuery = false)
            
            if (namespace.isNullOrBlank() || docId.isNullOrBlank() || content.isNullOrBlank()) {
                Log.w(TAG, "Invalid parameters for addRagDocument")
                return false
            }
            
            return try {
                // Create or update index with single document
                runBlocking {
                    val existingIndex = ragStore.getIndex(namespace)
                    if (existingIndex != null) {
                        // Add to existing index
                        ragStore.createIndex(
                            namespace,
                            existingIndex.documents.map { it.id to it.content } + (docId to content)
                        )
                    } else {
                        // Create new index
                        ragStore.createIndex(namespace, listOf(docId to content))
                    }
                }
                Log.i(TAG, "Added RAG document: $namespace/$docId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add RAG document", e)
                false
            }
        }
        
        override fun detectObjects(imageBase64: String?, sourceId: String?): String {
            Log.d(TAG, "detectObjects() called: sourceId=$sourceId")
            recordClientActivity(capability = "vision")
            
            if (imageBase64.isNullOrBlank()) {
                return errorJson("Image data required")
            }
            
            return try {
                // Decode base64 image
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                
                // Get LlamaFarmLite instance
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability
                
                if (cameraCapability == null) {
                    return errorJson("Camera capability not available")
                }
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                
                // Run detection
                val result = runBlocking {
                    llamaFarm.detectObjects(imageBytes, sourceId ?: "api")
                }
                
                if (result != null) {
                    // Return in format SDK expects: { detections: [...], model, inference_time_ms }
                    val detectionsArray = org.json.JSONArray()
                    detectionsArray.put(JSONObject().apply {
                        put("class_name", result.className)
                        put("confidence", result.confidence)
                        put("bbox", org.json.JSONArray().apply {
                            put(result.bbox.x1.toDouble())
                            put(result.bbox.y1.toDouble())
                            put(result.bbox.x2.toDouble())
                            put(result.bbox.y2.toDouble())
                        })
                        put("inference_time_ms", result.inferenceTimeMs)
                    })
                    JSONObject().apply {
                        put("success", true)
                        put("detections", detectionsArray)
                        put("model", "general_coco")
                        put("inference_time_ms", result.inferenceTimeMs)
                        put("escalated", false)
                        put("node_id", "local")
                    }.toString()
                } else {
                    errorJson("Detection failed - vision not ready or no objects detected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "detectObjects() error", e)
                errorJson("Detection failed: ${e.message}")
            }
        }
        
        override fun captureAndDetect(facing: String?): String {
            Log.d(TAG, "captureAndDetect() called: facing=$facing")
            recordClientActivity(capability = "vision")
            
            return try {
                // Get LlamaFarmLite instance
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability
                
                if (cameraCapability == null) {
                    return errorJson("Camera capability not available")
                }
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                
                // Parse camera facing
                val cameraFacing = when (facing?.lowercase()) {
                    "front" -> com.llamafarm.atmosphere.capabilities.CameraFacing.FRONT
                    else -> com.llamafarm.atmosphere.capabilities.CameraFacing.BACK
                }
                
                // Capture and detect
                val result = runBlocking {
                    llamaFarm.captureAndDetect(cameraFacing)
                }
                
                if (result != null) {
                    JSONObject().apply {
                        put("success", true)
                        put("className", result.className)
                        put("confidence", result.confidence)
                        put("bbox", JSONObject().apply {
                            put("x1", result.bbox.x1)
                            put("y1", result.bbox.y1)
                            put("x2", result.bbox.x2)
                            put("y2", result.bbox.y2)
                        })
                        put("inferenceTimeMs", result.inferenceTimeMs)
                        put("facing", facing ?: "back")
                    }.toString()
                } else {
                    errorJson("Camera capture or detection failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureAndDetect() error", e)
                errorJson("Capture and detect failed: ${e.message}")
            }
        }
        
        override fun getVisionCapability(): String {
            Log.d(TAG, "getVisionCapability() called")
            
            return try {
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability
                
                if (cameraCapability == null) {
                    return JSONObject().apply {
                        put("available", false)
                        put("message", "Camera capability not initialized")
                    }.toString()
                }
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                val visionManager = com.llamafarm.atmosphere.vision.VisionModelManager(applicationContext)
                
                val installedModels = visionManager.installedModels.value
                val activeModel = visionManager.activeModel.value
                
                JSONObject().apply {
                    put("available", llamaFarm.isVisionReady())
                    put("ready", llamaFarm.isVisionReady())
                    put("installedModels", JSONArray().apply {
                        installedModels.forEach { model ->
                            put(JSONObject().apply {
                                put("modelId", model.modelId)
                                put("version", model.version)
                                put("baseModel", model.baseModel)
                                put("inputSize", model.inputSize)
                                put("numClasses", model.classMap.size)
                            })
                        }
                    })
                    put("activeModel", if (activeModel != null) {
                        JSONObject().apply {
                            put("modelId", activeModel.modelId)
                            put("version", activeModel.version)
                            put("baseModel", activeModel.baseModel)
                            put("inputSize", activeModel.inputSize)
                            put("numClasses", activeModel.classMap.size)
                        }
                    } else {
                        null
                    })
                    put("supportedFormats", JSONArray().apply {
                        put("jpeg")
                        put("png")
                    })
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getVisionCapability() error", e)
                errorJson("Failed to get vision capability: ${e.message}")
            }
        }
        
        override fun setVisionConfidenceThreshold(threshold: Float) {
            Log.d(TAG, "setVisionConfidenceThreshold() called: threshold=$threshold")
            
            try {
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability
                
                if (cameraCapability != null) {
                    val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                    llamaFarm.setVisionConfidenceThreshold(threshold)
                    Log.i(TAG, "Vision confidence threshold set to: $threshold")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set confidence threshold", e)
            }
        }
        
        override fun sendVisionFeedback(feedbackJson: String?): String {
            Log.d(TAG, "sendVisionFeedback() called: feedbackJson=$feedbackJson")
            
            if (feedbackJson == null) {
                return errorJson("Feedback JSON cannot be null")
            }
            
            return try {
                val feedback = JSONObject(feedbackJson)
                
                // Log the feedback for now - could extend VisionCapability to support training
                Log.i(TAG, "📝 Vision feedback received: $feedbackJson")
                
                // Store feedback in app state for later batch training
                // For now, just return success
                JSONObject().apply {
                    put("success", true)
                    put("message", "Feedback received")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "sendVisionFeedback() error", e)
                errorJson("Failed to process vision feedback: ${e.message}")
            }
        }
        
        override fun getLlamaFarmCapabilities(): String {
            Log.d(TAG, "getLlamaFarmCapabilities() called")
            
            return try {
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability
                
                if (cameraCapability == null) {
                    return JSONObject().apply {
                        put("llm", JSONObject().apply { put("available", false) })
                        put("rag", JSONObject().apply { put("available", true) })
                        put("vision", JSONObject().apply { put("available", false) })
                    }.toString()
                }
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                val capabilities = llamaFarm.getCapabilityInfo()
                
                capabilities.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getLlamaFarmCapabilities() error", e)
                errorJson("Failed to get capabilities: ${e.message}")
            }
        }
        
        override fun isLlmReady(): Boolean {
            return try {
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability ?: return false
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                llamaFarm.isLlmReady()
            } catch (e: Exception) {
                Log.e(TAG, "isLlmReady() error", e)
                false
            }
        }
        
        override fun isVisionReady(): Boolean {
            return try {
                val app = applicationContext as? AtmosphereApplication
                val cameraCapability = app?.cameraCapability ?: return false
                
                val llamaFarm = LlamaFarmLite.getInstance(applicationContext, cameraCapability)
                llamaFarm.isVisionReady()
            } catch (e: Exception) {
                Log.e(TAG, "isVisionReady() error", e)
                false
            }
        }
    }
    
    /**
     * Build a RAG prompt for the LLM.
     */
    private fun buildRagPrompt(query: String, context: String): String {
        return """<|system|>
You are a helpful assistant that answers questions based on the provided context. 
Only use information from the context to answer. If the answer is not in the context, say so.
Be concise and direct.
<|user|>
Context:
$context

Question: $query
<|assistant|>
"""
    }
    
    private fun buildPromptFromMessages(messages: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")
            
            when (role) {
                "system" -> sb.append("<|system|>\n$content\n")
                "user" -> sb.append("<|user|>\n$content\n")
                "assistant" -> sb.append("<|assistant|>\n$content\n")
            }
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }
    
    private fun getDefaultCapabilities(): List<AtmosphereCapability> {
        return listOf(
            AtmosphereCapability(
                id = "llm-local",
                name = "Local LLM",
                type = "llm",
                nodeId = "this-device",
                cost = 0.1f,
                available = true,
                metadata = """{"model": "qwen3-1.7b"}"""
            ),
            AtmosphereCapability(
                id = "camera-back",
                name = "Back Camera",
                type = "camera",
                nodeId = "this-device",
                cost = 0.05f,
                available = true,
                metadata = """{"facing": "back", "resolution": "1080p"}"""
            ),
            AtmosphereCapability(
                id = "voice-stt",
                name = "Speech to Text",
                type = "voice",
                nodeId = "this-device",
                cost = 0.2f,
                available = true,
                metadata = """{"engine": "whisper"}"""
            )
        )
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind() action=${intent?.action}")
        
        // Track the connecting client
        val callingUid = android.os.Binder.getCallingUid()
        val packageName = packageManager.getNameForUid(callingUid) ?: "unknown"
        
        if (callingUid != android.os.Process.myUid()) {
            trackClient(callingUid, packageName)
            Log.i(TAG, "Client connected: $packageName (uid=$callingUid)")
        }
        
        return when (intent?.action) {
            ACTION_BIND -> binder
            else -> null
        }
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        val callingUid = android.os.Binder.getCallingUid()
        Log.i(TAG, "Client unbound: uid=$callingUid")
        // Note: We keep the client in the list for stats, marked as disconnected
        return super.onUnbind(intent)
    }
    
    /**
     * Track a connected client.
     */
    private fun trackClient(uid: Int, packageName: String) {
        _connectedClients.getOrPut(uid) {
            ConnectedClient(uid = uid, packageName = packageName)
        }
    }
    
    /**
     * Record activity from a client.
     */
    private fun recordClientActivity(capability: String? = null, tokensGenerated: Long = 0, ragQuery: Boolean = false) {
        val callingUid = android.os.Binder.getCallingUid()
        _connectedClients[callingUid]?.let { client ->
            client.lastActivityAt = System.currentTimeMillis()
            client.requestCount++
            client.tokensGenerated += tokensGenerated
            if (ragQuery) client.ragQueriesCount++
            capability?.let { client.capabilitiesUsed.add(it) }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        serviceScope.cancel()
        callbacks.kill()
        thirdPartyCapabilities.clear()
        _connectedClients.clear()
    }
    
    /**
     * Broadcast a capability event to all registered callbacks.
     */
    fun broadcastCapabilityEvent(capabilityId: String, eventJson: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onCapabilityEvent(capabilityId, eventJson)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
    }
    
    /**
     * Broadcast mesh status update to all registered callbacks.
     */
    fun broadcastMeshUpdate(statusJson: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onMeshUpdate(statusJson)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
    }
    
    /**
     * Broadcast cost update to all registered callbacks.
     */
    fun broadcastCostUpdate(costsJson: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onCostUpdate(costsJson)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
    }
    
    /**
     * Broadcast a streaming chunk.
     */
    fun broadcastStreamChunk(requestId: String, chunkJson: String, isFinal: Boolean) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onStreamChunk(requestId, chunkJson, isFinal)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast stream chunk to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
    }
    
    /**
     * Broadcast an error.
     */
    fun broadcastError(errorCode: String, errorMessage: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onError(errorCode, errorMessage)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast error to callback $i", e)
            }
        }
        callbacks.finishBroadcast()
    }
    
    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("error", true)
            put("message", message)
        }.toString()
    }
}
