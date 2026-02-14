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
// Vision imports removed
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
    
    // CRDT observer tracking: collection -> list of observer IDs
    private val crdtObserverIds = mutableMapOf<String, MutableList<String>>()
    
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
                val messages = JSONArray(messagesJson)
                val app = applicationContext as? AtmosphereApplication
                val crdtCore = getCrdtCore()
                
                // Extract last user message for routing
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
                
                // Step 1: Semantic router decides WHERE to send
                val router = app?.semanticRouter
                val routeResult = if (router != null) {
                    runBlocking { router.route(prompt) }
                } else null
                
                val targetNodeId = routeResult?.capability?.nodeId
                val targetIsLocal = targetNodeId == null || targetNodeId == crdtCore?.peerId
                
                Log.d(TAG, "Router decision: target=$targetNodeId, local=$targetIsLocal, " +
                    "capability=${routeResult?.capability?.capabilityId}, " +
                    "score=${routeResult?.explanation}")
                
                if (targetIsLocal) {
                    // Router says run locally (on-device GGUF)
                    Log.d(TAG, "Semantic router → local inference")
                    executeLocalInference(app, messages, model)
                } else if (crdtCore != null) {
                    // Router says send to remote peer → route via CRDT mesh
                    Log.d(TAG, "Semantic router → CRDT mesh → $targetNodeId")
                    
                    // Extract project_path from the routing decision's capability
                    val projectPath = routeResult?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                    Log.d(TAG, "Routed to project: $projectPath (capability=${routeResult?.capability?.label})")
                    
                    val requestId = java.util.UUID.randomUUID().toString()
                    crdtCore.insert("_requests", mapOf<String, Any>(
                        "_id" to requestId,
                        "request_id" to requestId,
                        "prompt" to prompt,
                        "messages" to messagesJson,
                        "model" to (model ?: "auto"),
                        "target_peer" to (targetNodeId ?: ""),
                        "project_path" to projectPath,
                        "status" to "pending",
                        "timestamp" to System.currentTimeMillis(),
                        "source" to "android"
                    ))
                    
                    // Trigger immediate sync so the request reaches peers ASAP
                    crdtCore.syncNow()
                    
                    // Wait for response via CRDT _responses (30s timeout)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var responseContent: String? = null
                    var responseModel: String? = null
                    
                    val observerId = crdtCore.observe("_responses") { event ->
                        val doc = crdtCore.get("_responses", event.docId)
                        if (doc?.get("request_id") == requestId) {
                            responseContent = doc["content"]?.toString()
                            responseModel = doc["model"]?.toString()
                            latch.countDown()
                        }
                    }
                    
                    val answered = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    crdtCore.removeObserver(observerId)
                    
                    if (answered && !responseContent.isNullOrEmpty()) {
                        JSONObject().apply {
                            put("choices", JSONArray().put(JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", responseContent)
                                })
                                put("finish_reason", "stop")
                            }))
                            put("model", responseModel ?: model ?: "mesh-routed")
                            put("routed_via", "crdt-mesh")
                            put("target_peer", targetNodeId)
                        }.toString()
                    } else {
                        errorJson("No response from mesh peer $targetNodeId (30s timeout)")
                    }
                } else {
                    // No CRDT core available, local inference only option
                    Log.d(TAG, "No CRDT mesh available → local inference")
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
            
            // Read from CRDT _capabilities collection (merged view from all mesh peers)
            val crdtCore = getCrdtCore()
            if (crdtCore != null) {
                val crdtCaps = crdtCore.query("_capabilities")
                crdtCaps.forEach { doc ->
                    try {
                        capabilities.add(AtmosphereCapability(
                            id = doc["_id"]?.toString() ?: return@forEach,
                            name = doc["name"]?.toString() ?: "unknown",
                            type = doc["type"]?.toString() ?: "custom",
                            nodeId = doc["nodeId"]?.toString() ?: "unknown",
                            cost = (doc["cost"] as? Number)?.toFloat() ?: 0.5f,
                            available = doc["available"] as? Boolean ?: true,
                            metadata = doc["metadata"]?.toString() ?: "{}"
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CRDT capability", e)
                    }
                }
            }
            
            // Add third-party registered capabilities (not yet in CRDT)
            thirdPartyCapabilities.values.forEach { cap ->
                if (capabilities.none { it.id == cap.id }) {
                    capabilities.add(cap)
                }
            }
            
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
                    put("peerCount", service.getCrdtPeerCount())
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
                
                // Insert into CRDT _capabilities collection (syncs to all peers automatically)
                val crdtCore = getCrdtCore()
                if (crdtCore != null) {
                    val capData = mapOf<String, Any>(
                        "_id" to id,
                        "name" to capability.name,
                        "type" to capability.type,
                        "nodeId" to capability.nodeId,
                        "cost" to capability.cost,
                        "available" to capability.available,
                        "metadata" to capability.metadata,
                        "registered_at" to System.currentTimeMillis()
                    )
                    crdtCore.insert("_capabilities", capData)
                    Log.i(TAG, "Registered capability in CRDT mesh: $id")
                }
                
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
            return errorJson("Vision capability removed")
        }
        
        override fun captureAndDetect(facing: String?): String {
            return errorJson("Vision capability removed")
        }
        
        override fun getVisionCapability(): String {
            return JSONObject().apply {
                put("available", false)
                put("message", "Vision capability removed")
            }.toString()
        }
        
        override fun setVisionConfidenceThreshold(threshold: Float) {
            // Vision removed
        }
        
        override fun sendVisionFeedback(feedbackJson: String?): String {
            return errorJson("Vision capability removed")
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
        
        // ========================== Mesh App / Tool API Implementation ==========================
        
        override fun getApps(): String {
            Log.d(TAG, "getApps() called")
            
            return try {
                val registry = com.llamafarm.atmosphere.apps.AppRegistry.getInstance()
                val allCaps = registry.getAppCapabilities()
                
                // Group by app name
                val appMap = mutableMapOf<String, MutableList<com.llamafarm.atmosphere.apps.AppCapability>>()
                allCaps.forEach { cap ->
                    appMap.getOrPut(cap.appName) { mutableListOf() }.add(cap)
                }
                
                val appsArray = JSONArray()
                appMap.forEach { (appName, caps) ->
                    val toolCount = caps.sumOf { it.tools.size }
                    val desc = caps.firstOrNull()?.description ?: ""
                    appsArray.put(JSONObject().apply {
                        put("name", appName)
                        put("description", desc)
                        put("toolCount", toolCount)
                    })
                }
                
                appsArray.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getApps() error", e)
                JSONArray().toString()
            }
        }
        
        override fun getAppTools(appName: String?): String {
            Log.d(TAG, "getAppTools() called: appName=$appName")
            
            if (appName.isNullOrBlank()) {
                return JSONArray().toString()
            }
            
            return try {
                val registry = com.llamafarm.atmosphere.apps.AppRegistry.getInstance()
                val tools = registry.getToolsForApp(appName)
                
                val toolsArray = JSONArray()
                tools.forEach { (name, tool) ->
                    toolsArray.put(JSONObject().apply {
                        put("name", name)
                        put("description", tool.description)
                        put("method", tool.endpoint.method)
                        put("endpoint", tool.endpoint.path)
                        put("params", JSONArray().apply {
                            tool.parameters.forEach { p ->
                                put(JSONObject().apply {
                                    put("name", p.name)
                                    put("type", p.type)
                                    put("description", p.description)
                                    put("required", p.required)
                                })
                            }
                        })
                    })
                }
                
                toolsArray.toString()
            } catch (e: Exception) {
                Log.e(TAG, "getAppTools() error", e)
                JSONArray().toString()
            }
        }
        
        override fun callTool(appName: String?, toolName: String?, paramsJson: String?): String {
            Log.d(TAG, "callTool() called: app=$appName, tool=$toolName")
            recordClientActivity(capability = "tool_call")
            
            if (appName.isNullOrBlank() || toolName.isNullOrBlank()) {
                return errorJson("appName and toolName are required")
            }
            
            return try {
                val app = applicationContext as? AtmosphereApplication
                val crdtCore = getCrdtCore()
                
                // Semantic router finds which peer has this tool capability
                val router = app?.semanticRouter
                val routeResult = if (router != null) {
                    runBlocking { router.route("$appName.$toolName") }
                } else null
                
                val targetNodeId = routeResult?.capability?.nodeId
                
                Log.d(TAG, "Router decision for tool $appName.$toolName: target=$targetNodeId")
                
                if (crdtCore == null) {
                    return errorJson("CRDT mesh not available")
                }
                
                // Send tool request via CRDT mesh
                val requestId = java.util.UUID.randomUUID().toString()
                crdtCore.insert("_tool_requests", mapOf<String, Any>(
                    "_id" to requestId,
                    "request_id" to requestId,
                    "app" to appName,
                    "tool" to toolName,
                    "params" to paramsJson.orEmpty(),
                    "target_peer" to (targetNodeId ?: ""),
                    "status" to "pending",
                    "timestamp" to System.currentTimeMillis(),
                    "source" to "android"
                ))
                
                // Wait for response via CRDT _tool_responses (30s timeout)
                val latch = java.util.concurrent.CountDownLatch(1)
                var responseJson: String? = null
                
                val observerId = crdtCore.observe("_tool_responses") { event ->
                    val doc = crdtCore.get("_tool_responses", event.docId)
                    if (doc?.get("request_id") == requestId) {
                        responseJson = doc["result"]?.toString()
                        latch.countDown()
                    }
                }
                
                val answered = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                crdtCore.removeObserver(observerId)
                
                if (answered && !responseJson.isNullOrEmpty()) {
                    responseJson!!
                } else {
                    errorJson("No response for $appName.$toolName from mesh (30s timeout)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "callTool() error", e)
                errorJson("Tool call failed: ${e.message}")
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
        
        // ========================== CRDT Data Sync API Implementation ==========================
        
        override fun crdtInsert(collection: String?, docJson: String?): String? {
            Log.d(TAG, "crdtInsert() called: collection=$collection")
            if (collection.isNullOrBlank() || docJson.isNullOrBlank()) {
                return null
            }
            return try {
                val core = getCrdtCore() ?: return null
                val json = JSONObject(docJson)
                val data = mutableMapOf<String, Any>()
                json.keys().forEach { key -> data[key] = json.get(key) }
                core.insert(collection, data)
                docJson  // Return the inserted document
            } catch (e: Exception) {
                Log.e(TAG, "crdtInsert() error", e)
                null
            }
        }
        
        override fun crdtQuery(collection: String?): String? {
            if (collection.isNullOrBlank()) return "[]"
            return try {
                val core = getCrdtCore() ?: return "[]"
                val docs = core.query(collection)
                val arr = JSONArray()
                docs.forEach { doc -> arr.put(JSONObject(doc)) }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "crdtQuery() error", e)
                "[]"
            }
        }
        
        override fun crdtGet(collection: String?, docId: String?): String? {
            if (collection.isNullOrBlank() || docId.isNullOrBlank()) return null
            return try {
                val core = getCrdtCore() ?: return null
                val doc = core.get(collection, docId)
                doc?.let { JSONObject(it).toString() }
            } catch (e: Exception) {
                Log.e(TAG, "crdtGet() error", e)
                null
            }
        }
        
        override fun crdtSubscribe(collection: String?) {
            if (collection.isNullOrBlank()) return
            val core = getCrdtCore() ?: return
            val observerId = core.observe(collection) { event ->
                val doc = core.get(event.collection, event.docId)
                val docJson = doc?.let { JSONObject(it).toString() } ?: "{}"
                broadcastCrdtChange(event.collection, event.docId, event.kind, docJson)
            }
            crdtObserverIds.getOrPut(collection) { mutableListOf() }.add(observerId)
        }
        
        override fun crdtUnsubscribe(collection: String?) {
            if (collection.isNullOrBlank()) return
            val core = getCrdtCore() ?: return
            crdtObserverIds.remove(collection)?.forEach { id ->
                core.removeObserver(id)
            }
        }
        
        override fun crdtPeers(): String {
            return try {
                val core = getCrdtCore() ?: return "[]"
                val arr = JSONArray()
                core.connectedPeers().forEach { p ->
                    arr.put(JSONObject().apply {
                        put("peer_id", p.peerId)
                        put("transport", p.transport)
                        put("last_seen", p.lastSeen)
                    })
                }
                arr.toString()
            } catch (e: Exception) {
                Log.e(TAG, "crdtPeers() error", e)
                "[]"
            }
        }
        
        override fun crdtInfo(): String {
            return try {
                val core = getCrdtCore()
                JSONObject().apply {
                    put("peer_id", core?.peerId ?: "unknown")
                    put("app_id", core?.appId ?: "atmosphere")
                    put("mesh_port", core?.listenPort ?: 0)
                    put("peer_count", core?.connectedPeers()?.size ?: 0)
                    put("daemon_type", "android-bigllama")
                }.toString()
            } catch (e: Exception) {
                Log.e(TAG, "crdtInfo() error", e)
                "{}"
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
    
    /**
     * Get the Atmosphere handle from the running AtmosphereService.
     */
    private fun getAtmosphereHandle(): Long {
        val connector = ServiceManager.getConnector()
        return connector.getService()?.getAtmosphereHandle() ?: 0L
    }
    
    /**
     * Get the CRDT core wrapper from the running AtmosphereService.
     */
    private fun getCrdtCore(): com.llamafarm.atmosphere.core.CrdtCoreWrapper? {
        val connector = ServiceManager.getConnector()
        return connector.getService()?.getAtmosphereCore()
    }
    
    /**
     * Broadcast a CRDT change event to all registered callbacks.
     */
    private fun broadcastCrdtChange(collection: String, docId: String, kind: String, docJson: String) {
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).onCrdtChange(collection, docId, kind, docJson)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to broadcast CRDT change to callback $i", e)
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
