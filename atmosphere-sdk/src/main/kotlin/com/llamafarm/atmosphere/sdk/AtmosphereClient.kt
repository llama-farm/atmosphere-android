package com.llamafarm.atmosphere.sdk

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.llamafarm.atmosphere.IAtmosphereCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main entry point for using Atmosphere mesh from other Android apps.
 * 
 * Usage:
 * ```kotlin
 * // Connect to Atmosphere
 * val atmosphere = AtmosphereClient.connect(context)
 * 
 * // Route an intent to the best capability
 * val result = atmosphere.route("summarize this document", payload)
 * 
 * // Chat completion
 * val chat = atmosphere.chat(listOf(ChatMessage.user("Hello!")))
 * 
 * // Get capabilities
 * val caps = atmosphere.capabilities()
 * 
 * // Clean up
 * atmosphere.disconnect()
 * ```
 */
class AtmosphereClient private constructor(
    private val connector: ServiceConnector
) {
    
    companion object {
        private const val TAG = "AtmosphereClient"
        
        /** SDK version */
        const val VERSION = "1.0.0"
        
        /**
         * Connect to the Atmosphere mesh service.
         * @param context Android context
         * @return AtmosphereClient instance
         * @throws AtmosphereNotInstalledException if Atmosphere app is not installed
         */
        fun connect(context: Context): AtmosphereClient {
            val connector = ServiceConnector(context.applicationContext)
            if (!connector.isAtmosphereInstalled()) {
                throw AtmosphereNotInstalledException()
            }
            return AtmosphereClient(connector)
        }
        
        /**
         * Check if Atmosphere app is installed.
         */
        fun isInstalled(context: Context): Boolean {
            return ServiceConnector(context.applicationContext).isAtmosphereInstalled()
        }
        
        /**
         * Get the Play Store URL for installing Atmosphere.
         */
        fun getInstallUrl(): String {
            return "https://play.google.com/store/apps/details?id=com.llamafarm.atmosphere"
        }
    }
    
    private var capabilityCallbacks = mutableMapOf<String, (String) -> Unit>()
    private var meshUpdateCallback: ((MeshStatus) -> Unit)? = null
    private var costUpdateCallback: ((CostMetrics) -> Unit)? = null
    private var streamCallbacks = mutableMapOf<String, (StreamChunk) -> Unit>()
    private var errorCallback: ((String, String) -> Unit)? = null
    private var meshCallback: IAtmosphereCallback? = null
    
    /**
     * Route an intent to the best capability in the mesh.
     * 
     * @param intent Natural language description of what to do
     * @param payload Optional JSON payload with data
     * @return RouteResult with the response
     */
    suspend fun route(intent: String, payload: String = "{}"): RouteResult = withContext(Dispatchers.IO) {
        val service = connector.getService() 
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.route(intent, payload)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                RouteResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                RouteResult(
                    success = true,
                    response = json.optString("response"),
                    capability = json.optString("capability"),
                    nodeId = json.optString("nodeId"),
                    latencyMs = json.optLong("latencyMs", -1),
                    raw = responseJson
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in route()", e)
            RouteResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * OpenAI-compatible chat completion.
     * 
     * @param messages List of chat messages
     * @param model Optional model name (null for auto-select)
     * @return ChatResult with the response
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        model: String? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val messagesJson = JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            }.toString()
            
            val responseJson = service.chatCompletion(messagesJson, model)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                ChatResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                val choices = json.optJSONArray("choices")
                val firstChoice = choices?.optJSONObject(0)
                val message = firstChoice?.optJSONObject("message")
                
                ChatResult(
                    success = true,
                    content = message?.optString("content"),
                    model = json.optString("model"),
                    finishReason = firstChoice?.optString("finish_reason"),
                    usage = json.optJSONObject("usage")?.let { usage ->
                        TokenUsage(
                            promptTokens = usage.optInt("prompt_tokens", 0),
                            completionTokens = usage.optInt("completion_tokens", 0),
                            totalTokens = usage.optInt("total_tokens", 0)
                        )
                    },
                    raw = responseJson
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in chat()", e)
            ChatResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Get all available capabilities in the mesh.
     */
    suspend fun capabilities(): List<Capability> = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            service.getCapabilities().map { cap ->
                Capability(
                    id = cap.id,
                    name = cap.name,
                    type = cap.type,
                    nodeId = cap.nodeId,
                    cost = cap.cost,
                    available = cap.available,
                    metadata = cap.metadata
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in capabilities()", e)
            emptyList()
        }
    }
    
    /**
     * Get a specific capability by ID.
     */
    suspend fun capability(capabilityId: String): Capability? = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            service.getCapability(capabilityId)?.let { cap ->
                Capability(
                    id = cap.id,
                    name = cap.name,
                    type = cap.type,
                    nodeId = cap.nodeId,
                    cost = cap.cost,
                    available = cap.available,
                    metadata = cap.metadata
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in capability()", e)
            null
        }
    }
    
    /**
     * Invoke a specific capability directly, bypassing routing.
     */
    suspend fun invoke(capabilityId: String, payload: String = "{}"): RouteResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.invokeCapability(capabilityId, payload)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                RouteResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                RouteResult(
                    success = true,
                    response = json.optString("response"),
                    capability = capabilityId,
                    raw = responseJson
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in invoke()", e)
            RouteResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Get current mesh status.
     */
    suspend fun meshStatus(): MeshStatus = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val json = JSONObject(service.getMeshStatus())
            MeshStatus(
                connected = json.optBoolean("connected", false),
                nodeId = json.optString("nodeId"),
                meshId = json.optString("meshId"),
                peerCount = json.optInt("peerCount", 0),
                capabilities = json.optInt("capabilities", 0),
                relayConnected = json.optBoolean("relayConnected", false)
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in meshStatus()", e)
            MeshStatus(connected = false)
        }
    }
    
    /**
     * Get current cost metrics.
     */
    suspend fun costs(): CostMetrics = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val json = JSONObject(service.getCostMetrics())
            CostMetrics(
                battery = json.optDouble("battery", 1.0).toFloat(),
                cpu = json.optDouble("cpu", 0.0).toFloat(),
                memory = json.optDouble("memory", 0.0).toFloat(),
                network = json.optString("network", "unknown"),
                thermal = json.optString("thermal", "unknown"),
                overall = json.optDouble("overall", 0.5).toFloat()
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in costs()", e)
            CostMetrics()
        }
    }
    
    /**
     * Join a mesh network.
     * 
     * @param meshId The mesh to join (null for auto/default)
     * @param credentials Optional credentials
     * @return MeshJoinResult with success/error
     */
    suspend fun joinMesh(meshId: String? = null, credentials: String? = null): MeshJoinResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.joinMesh(meshId, credentials ?: "{}")
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                MeshJoinResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                MeshJoinResult(
                    success = true,
                    meshId = json.optString("meshId"),
                    nodeId = json.optString("nodeId")
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in joinMesh()", e)
            MeshJoinResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Leave the current mesh network.
     */
    suspend fun leaveMesh(): Boolean = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.leaveMesh()
            val json = JSONObject(responseJson)
            !json.optBoolean("error", false)
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in leaveMesh()", e)
            false
        }
    }
    
    /**
     * Register a capability from this app to contribute to the mesh.
     * 
     * @param capability The capability to register
     * @return The assigned capability ID, or null on failure
     */
    suspend fun registerCapability(capability: CapabilityRegistration): String? = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val capJson = JSONObject().apply {
                put("name", capability.name)
                put("type", capability.type)
                put("description", capability.description)
                put("metadata", capability.metadata)
            }.toString()
            
            val responseJson = service.registerCapability(capJson)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                null
            } else {
                json.optString("capabilityId")
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in registerCapability()", e)
            null
        }
    }
    
    /**
     * Unregister a previously registered capability.
     */
    suspend fun unregisterCapability(capabilityId: String): Unit = withContext(Dispatchers.IO) {
        val service = connector.getService() ?: return@withContext
        
        try {
            service.unregisterCapability(capabilityId)
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in unregisterCapability()", e)
        }
    }
    
    /**
     * Subscribe to capability events.
     * 
     * @param capabilityId The capability to listen to (or "*" for all)
     * @param callback Called when an event occurs
     */
    suspend fun onCapabilityEvent(capabilityId: String, callback: (String) -> Unit) {
        capabilityCallbacks[capabilityId] = callback
        ensureCallbackRegistered()
    }
    
    /**
     * Subscribe to mesh updates.
     */
    suspend fun onMeshUpdate(callback: (MeshStatus) -> Unit) {
        meshUpdateCallback = callback
        ensureCallbackRegistered()
    }
    
    /**
     * Subscribe to cost updates.
     */
    suspend fun onCostUpdate(callback: (CostMetrics) -> Unit) {
        costUpdateCallback = callback
        ensureCallbackRegistered()
    }
    
    /**
     * Subscribe to errors.
     */
    suspend fun onError(callback: (errorCode: String, message: String) -> Unit) {
        errorCallback = callback
        ensureCallbackRegistered()
    }
    
    /**
     * Get mesh status as a Flow for reactive consumption.
     */
    fun meshStatusFlow(): Flow<MeshStatus> = callbackFlow {
        onMeshUpdate { status ->
            trySend(status)
        }
        
        // Send initial status
        try {
            val initial = meshStatus()
            send(initial)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get initial mesh status", e)
        }
        
        awaitClose {
            meshUpdateCallback = null
        }
    }
    
    /**
     * Get cost metrics as a Flow for reactive consumption.
     */
    fun costMetricsFlow(): Flow<CostMetrics> = callbackFlow {
        onCostUpdate { costs ->
            trySend(costs)
        }
        
        // Send initial costs
        try {
            val initial = costs()
            send(initial)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get initial costs", e)
        }
        
        awaitClose {
            costUpdateCallback = null
        }
    }
    
    private suspend fun ensureCallbackRegistered() {
        if (meshCallback != null) return
        
        val service = connector.getService() ?: return
        
        meshCallback = object : IAtmosphereCallback.Stub() {
            override fun onCapabilityEvent(capabilityId: String?, eventJson: String?) {
                capabilityId ?: return
                eventJson ?: return
                
                // Check for specific capability callback
                capabilityCallbacks[capabilityId]?.invoke(eventJson)
                // Check for wildcard callback
                capabilityCallbacks["*"]?.invoke(eventJson)
            }
            
            override fun onMeshUpdate(statusJson: String?) {
                statusJson ?: return
                try {
                    val json = JSONObject(statusJson)
                    val status = MeshStatus(
                        connected = json.optBoolean("connected", false),
                        nodeId = json.optString("nodeId"),
                        meshId = json.optString("meshId"),
                        peerCount = json.optInt("peerCount", 0),
                        capabilities = json.optInt("capabilities", 0),
                        relayConnected = json.optBoolean("relayConnected", false)
                    )
                    meshUpdateCallback?.invoke(status)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse mesh update", e)
                }
            }
            
            override fun onCostUpdate(costsJson: String?) {
                costsJson ?: return
                try {
                    val json = JSONObject(costsJson)
                    val costs = CostMetrics(
                        battery = json.optDouble("battery", 1.0).toFloat(),
                        cpu = json.optDouble("cpu", 0.0).toFloat(),
                        memory = json.optDouble("memory", 0.0).toFloat(),
                        network = json.optString("network", "unknown"),
                        thermal = json.optString("thermal", "unknown"),
                        overall = json.optDouble("overall", 0.5).toFloat()
                    )
                    costUpdateCallback?.invoke(costs)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse cost update", e)
                }
            }
            
            override fun onStreamChunk(requestId: String?, chunkJson: String?, isFinal: Boolean) {
                requestId ?: return
                chunkJson ?: return
                
                val chunk = StreamChunk(
                    requestId = requestId,
                    data = chunkJson,
                    isFinal = isFinal
                )
                streamCallbacks[requestId]?.invoke(chunk)
                
                if (isFinal) {
                    streamCallbacks.remove(requestId)
                }
            }
            
            override fun onError(errorCode: String?, errorMessage: String?) {
                errorCallback?.invoke(
                    errorCode ?: "UNKNOWN",
                    errorMessage ?: "Unknown error"
                )
            }
        }
        
        try {
            service.registerCallback(meshCallback)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to register callback", e)
            meshCallback = null
        }
    }
    
    // ========================== RAG API ==========================
    
    /**
     * Create a RAG index from JSON documents.
     * 
     * @param indexId Unique ID for the index
     * @param documents List of documents (id, content pairs)
     * @return RagIndexResult with index info
     */
    suspend fun createRagIndex(
        indexId: String,
        documents: List<Pair<String, String>>  // (id, content)
    ): RagIndexResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            // Convert to JSON array
            val docsJson = JSONArray().apply {
                documents.forEach { (id, content) ->
                    put(JSONObject().apply {
                        put("id", id)
                        put("content", content)
                    })
                }
            }.toString()
            
            val responseJson = service.createRagIndex(indexId, docsJson)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                RagIndexResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                RagIndexResult(
                    success = true,
                    indexId = json.optString("indexId"),
                    documentCount = json.optInt("documentCount", 0),
                    uniqueTerms = json.optInt("uniqueTerms", 0)
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in createRagIndex()", e)
            RagIndexResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Create a RAG index from a JSON string.
     * The JSON should be an array of objects with "id" and "content" fields.
     */
    suspend fun createRagIndexFromJson(
        indexId: String,
        documentsJson: String
    ): RagIndexResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.createRagIndex(indexId, documentsJson)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                RagIndexResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                RagIndexResult(
                    success = true,
                    indexId = json.optString("indexId"),
                    documentCount = json.optInt("documentCount", 0),
                    uniqueTerms = json.optInt("uniqueTerms", 0)
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in createRagIndexFromJson()", e)
            RagIndexResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Query a RAG index with natural language.
     * 
     * @param indexId The index to query
     * @param query Natural language question
     * @param generateAnswer If true, use LLM to generate answer from retrieved context
     * @return RagQueryResult with documents and optional answer
     */
    suspend fun queryRag(
        indexId: String,
        query: String,
        generateAnswer: Boolean = true
    ): RagQueryResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.queryRag(indexId, query, generateAnswer)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                RagQueryResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                // Parse retrieved documents
                val docsArray = json.optJSONArray("documents")
                val documents = mutableListOf<RagDocument>()
                
                if (docsArray != null) {
                    for (i in 0 until docsArray.length()) {
                        val docJson = docsArray.getJSONObject(i)
                        documents.add(RagDocument(
                            id = docJson.optString("id"),
                            content = docJson.optString("content"),
                            score = docJson.optDouble("score", 0.0),
                            matchedTerms = docJson.optJSONArray("matchedTerms")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            } ?: emptyList()
                        ))
                    }
                }
                
                RagQueryResult(
                    success = true,
                    query = query,
                    documents = documents,
                    answer = json.optString("answer", null),
                    context = json.optString("context", null)
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in queryRag()", e)
            RagQueryResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Delete a RAG index.
     */
    suspend fun deleteRagIndex(indexId: String): Boolean = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.deleteRagIndex(indexId)
            val json = JSONObject(responseJson)
            json.optBoolean("success", false)
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in deleteRagIndex()", e)
            false
        }
    }
    
    /**
     * List all RAG indexes.
     */
    suspend fun listRagIndexes(): List<RagIndexInfo> = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.listRagIndexes()
            val json = JSONObject(responseJson)
            
            val indexes = mutableListOf<RagIndexInfo>()
            val indexesArray = json.optJSONArray("indexes")
            
            if (indexesArray != null) {
                for (i in 0 until indexesArray.length()) {
                    val indexJson = indexesArray.getJSONObject(i)
                    indexes.add(RagIndexInfo(
                        id = indexJson.optString("id"),
                        documentCount = indexJson.optInt("documentCount", 0),
                        uniqueTerms = indexJson.optInt("uniqueTerms", 0),
                        createdAt = indexJson.optLong("createdAt", 0)
                    ))
                }
            }
            
            indexes
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in listRagIndexes()", e)
            emptyList()
        }
    }
    
    // ========================== Vision API ==========================
    
    /**
     * Detect objects in a Bitmap.
     * Automatically converts to base64 and sends to Atmosphere vision capability.
     * 
     * @param bitmap The image to analyze
     * @param sourceId Optional source identifier (e.g., "camera", "gallery")
     * @return VisionResult with detections
     */
    suspend fun detectObjects(
        bitmap: android.graphics.Bitmap,
        sourceId: String = "sdk"
    ): VisionResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            // Convert bitmap to base64 JPEG
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            
            val responseJson = service.detectObjects(base64, sourceId)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                VisionResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                // Parse detections
                val detectionsArray = json.optJSONArray("detections") ?: JSONArray()
                val detections = mutableListOf<Detection>()
                
                for (i in 0 until detectionsArray.length()) {
                    val detJson = detectionsArray.getJSONObject(i)
                    val bboxArray = detJson.optJSONArray("bbox")
                    
                    val bbox = if (bboxArray != null && bboxArray.length() >= 4) {
                        BoundingBox(
                            x1 = bboxArray.getDouble(0).toFloat(),
                            y1 = bboxArray.getDouble(1).toFloat(),
                            x2 = bboxArray.getDouble(2).toFloat(),
                            y2 = bboxArray.getDouble(3).toFloat()
                        )
                    } else {
                        BoundingBox(0f, 0f, 0f, 0f)
                    }
                    
                    detections.add(Detection(
                        className = detJson.optString("class_name", "unknown"),
                        confidence = detJson.optDouble("confidence", 0.0).toFloat(),
                        bbox = bbox,
                        inferenceTimeMs = detJson.optDouble("inference_time_ms", 0.0).toFloat()
                    ))
                }
                
                VisionResult(
                    success = true,
                    detections = detections,
                    model = json.optString("model"),
                    inferenceTimeMs = json.optDouble("inference_time_ms", 0.0).toFloat(),
                    escalated = json.optBoolean("escalated", false),
                    nodeId = json.optString("node_id")
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in detectObjects()", e)
            VisionResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Detect objects from a base64-encoded image string.
     */
    suspend fun detectObjectsBase64(
        imageBase64: String,
        sourceId: String = "sdk"
    ): VisionResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.detectObjects(imageBase64, sourceId)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                VisionResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                val detectionsArray = json.optJSONArray("detections") ?: JSONArray()
                val detections = mutableListOf<Detection>()
                
                for (i in 0 until detectionsArray.length()) {
                    val detJson = detectionsArray.getJSONObject(i)
                    val bboxArray = detJson.optJSONArray("bbox")
                    
                    val bbox = if (bboxArray != null && bboxArray.length() >= 4) {
                        BoundingBox(
                            x1 = bboxArray.getDouble(0).toFloat(),
                            y1 = bboxArray.getDouble(1).toFloat(),
                            x2 = bboxArray.getDouble(2).toFloat(),
                            y2 = bboxArray.getDouble(3).toFloat()
                        )
                    } else {
                        BoundingBox(0f, 0f, 0f, 0f)
                    }
                    
                    detections.add(Detection(
                        className = detJson.optString("class_name", "unknown"),
                        confidence = detJson.optDouble("confidence", 0.0).toFloat(),
                        bbox = bbox,
                        inferenceTimeMs = detJson.optDouble("inference_time_ms", 0.0).toFloat()
                    ))
                }
                
                VisionResult(
                    success = true,
                    detections = detections,
                    model = json.optString("model"),
                    inferenceTimeMs = json.optDouble("inference_time_ms", 0.0).toFloat(),
                    escalated = json.optBoolean("escalated", false),
                    nodeId = json.optString("node_id")
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in detectObjectsBase64()", e)
            VisionResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Capture from device camera and detect objects.
     * 
     * @param facing "front" or "back"
     * @return VisionResult with detections
     */
    suspend fun captureAndDetect(facing: String = "back"): VisionResult = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.captureAndDetect(facing)
            val json = JSONObject(responseJson)
            
            if (json.optBoolean("error", false)) {
                VisionResult(
                    success = false,
                    error = json.optString("message", "Unknown error")
                )
            } else {
                val detectionsArray = json.optJSONArray("detections") ?: JSONArray()
                val detections = mutableListOf<Detection>()
                
                for (i in 0 until detectionsArray.length()) {
                    val detJson = detectionsArray.getJSONObject(i)
                    val bboxArray = detJson.optJSONArray("bbox")
                    
                    val bbox = if (bboxArray != null && bboxArray.length() >= 4) {
                        BoundingBox(
                            x1 = bboxArray.getDouble(0).toFloat(),
                            y1 = bboxArray.getDouble(1).toFloat(),
                            x2 = bboxArray.getDouble(2).toFloat(),
                            y2 = bboxArray.getDouble(3).toFloat()
                        )
                    } else {
                        BoundingBox(0f, 0f, 0f, 0f)
                    }
                    
                    detections.add(Detection(
                        className = detJson.optString("class_name", "unknown"),
                        confidence = detJson.optDouble("confidence", 0.0).toFloat(),
                        bbox = bbox,
                        inferenceTimeMs = detJson.optDouble("inference_time_ms", 0.0).toFloat()
                    ))
                }
                
                VisionResult(
                    success = true,
                    detections = detections,
                    model = json.optString("model"),
                    inferenceTimeMs = json.optDouble("inference_time_ms", 0.0).toFloat(),
                    escalated = json.optBoolean("escalated", false),
                    nodeId = json.optString("node_id")
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in captureAndDetect()", e)
            VisionResult(success = false, error = "Connection lost: ${e.message}")
        }
    }
    
    /**
     * Get vision capability status.
     */
    suspend fun visionStatus(): VisionStatus = withContext(Dispatchers.IO) {
        val service = connector.getService()
            ?: throw AtmosphereNotConnectedException()
        
        try {
            val responseJson = service.getVisionCapability()
            val json = JSONObject(responseJson)
            
            VisionStatus(
                ready = json.optBoolean("ready", false),
                modelId = json.optString("model_id"),
                modelVersion = json.optString("model_version"),
                numClasses = json.optInt("num_classes", 0),
                inputSize = json.optInt("input_size", 0),
                confidenceThreshold = json.optDouble("confidence_threshold", 0.7).toFloat()
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in visionStatus()", e)
            VisionStatus(ready = false)
        }
    }
    
    /**
     * Set confidence threshold for vision escalation.
     * When local detection confidence is below this threshold, request escalates to mesh.
     */
    suspend fun setVisionConfidenceThreshold(threshold: Float): Unit = withContext(Dispatchers.IO) {
        val service = connector.getService() ?: return@withContext
        
        try {
            service.setVisionConfidenceThreshold(threshold)
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote exception in setVisionConfidenceThreshold()", e)
        }
    }
    
    /**
     * Send feedback on a detection result for model training.
     * 
     * @param detectionId Optional detection ID (from metadata)
     * @param correct Whether the detection was correct
     * @param correctedLabel If incorrect, what it should have been
     * @param imageBase64 Optional image data for retraining
     */
    suspend fun sendVisionFeedback(
        detectionId: String? = null,
        correct: Boolean,
        correctedLabel: String? = null,
        imageBase64: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val service = connector.getService() ?: return@withContext false
        
        try {
            val feedbackJson = JSONObject().apply {
                detectionId?.let { put("detection_id", it) }
                put("correct", correct)
                correctedLabel?.let { put("corrected_label", it) }
                imageBase64?.let { put("image_base64", it) }
            }.toString()
            
            val responseJson = service.sendVisionFeedback(feedbackJson)
            val json = JSONObject(responseJson)
            val success = json.optBoolean("success", false)
            
            Log.i(TAG, "📝 Vision feedback sent: id=$detectionId, correct=$correct, label=$correctedLabel → $success")
            success
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to send vision feedback", e)
            false
        }
    }
    
    /**
     * Unregister all callbacks.
     */
    suspend fun unregisterCallbacks(): Unit = withContext(Dispatchers.IO) {
        meshCallback?.let { callback ->
            try {
                connector.getService()?.unregisterCallback(callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "Error unregistering callback", e)
            }
        }
        meshCallback = null
        capabilityCallbacks.clear()
        meshUpdateCallback = null
        costUpdateCallback = null
        streamCallbacks.clear()
        errorCallback = null
    }
    
    /**
     * Disconnect from the mesh.
     */
    fun disconnect() {
        meshCallback = null
        capabilityCallbacks.clear()
        meshUpdateCallback = null
        costUpdateCallback = null
        streamCallbacks.clear()
        errorCallback = null
        connector.disconnect()
    }
}

// ============================================================================
// Data Classes
// ============================================================================

data class ChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
        fun system(content: String) = ChatMessage("system", content)
    }
}

data class RouteResult(
    val success: Boolean,
    val response: String? = null,
    val capability: String? = null,
    val nodeId: String? = null,
    val latencyMs: Long = -1,
    val error: String? = null,
    val raw: String? = null
)

data class ChatResult(
    val success: Boolean,
    val content: String? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
    val error: String? = null,
    val raw: String? = null
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

data class Capability(
    val id: String,
    val name: String,
    val type: String,
    val nodeId: String,
    val cost: Float,
    val available: Boolean,
    val metadata: String
) {
    /** Parse metadata as JSON */
    fun metadataJson(): JSONObject = try {
        JSONObject(metadata)
    } catch (e: Exception) {
        JSONObject()
    }
}

data class CapabilityRegistration(
    val name: String,
    val type: String,
    val description: String = "",
    val metadata: String = "{}"
)

data class MeshStatus(
    val connected: Boolean,
    val nodeId: String? = null,
    val meshId: String? = null,
    val peerCount: Int = 0,
    val capabilities: Int = 0,
    val relayConnected: Boolean = false
)

data class MeshJoinResult(
    val success: Boolean,
    val meshId: String? = null,
    val nodeId: String? = null,
    val error: String? = null
)

data class CostMetrics(
    val battery: Float = 1.0f,
    val cpu: Float = 0.0f,
    val memory: Float = 0.0f,
    val network: String = "unknown",
    val thermal: String = "unknown",
    val overall: Float = 0.5f
) {
    /** Is this device currently a good candidate for work? */
    fun isAvailable(): Boolean = overall < 0.7f && battery > 0.2f
}

data class StreamChunk(
    val requestId: String,
    val data: String,
    val isFinal: Boolean
)

// ============================================================================
// Exceptions
// ============================================================================

// ============================================================================
// RAG Data Classes
// ============================================================================

data class RagIndexResult(
    val success: Boolean,
    val indexId: String? = null,
    val documentCount: Int = 0,
    val uniqueTerms: Int = 0,
    val error: String? = null
)

data class RagQueryResult(
    val success: Boolean,
    val query: String? = null,
    val documents: List<RagDocument> = emptyList(),
    val answer: String? = null,
    val context: String? = null,
    val error: String? = null
) {
    /** Get the answer or first document content as fallback */
    fun getResponseText(): String {
        return answer 
            ?: documents.firstOrNull()?.content 
            ?: error 
            ?: "No answer available"
    }
}

data class RagDocument(
    val id: String,
    val content: String,
    val score: Double = 0.0,
    val matchedTerms: List<String> = emptyList()
)

data class RagIndexInfo(
    val id: String,
    val documentCount: Int,
    val uniqueTerms: Int,
    val createdAt: Long
)

// ============================================================================
// Vision Data Classes
// ============================================================================

data class VisionResult(
    val success: Boolean,
    val detections: List<Detection> = emptyList(),
    val model: String? = null,
    val inferenceTimeMs: Float = 0f,
    val escalated: Boolean = false,
    val nodeId: String? = null,
    val error: String? = null
) {
    /** Get the best (highest confidence) detection */
    fun bestDetection(): Detection? = detections.maxByOrNull { it.confidence }
    
    /** Filter detections by minimum confidence */
    fun withMinConfidence(minConfidence: Float): List<Detection> =
        detections.filter { it.confidence >= minConfidence }
}

data class Detection(
    val className: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val inferenceTimeMs: Float = 0f
) {
    /** Format as human-readable string */
    fun format(): String = "$className ${(confidence * 100).toInt()}%"
}

data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
    val area: Float get() = width * height
}

data class VisionStatus(
    val ready: Boolean,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val numClasses: Int = 0,
    val inputSize: Int = 0,
    val confidenceThreshold: Float = 0.7f
)

// ============================================================================
// Exceptions
// ============================================================================

class AtmosphereNotInstalledException : Exception(
    "Atmosphere app is not installed. Please install from Play Store or download APK."
)

class AtmosphereNotConnectedException : Exception(
    "Could not connect to Atmosphere service. Is the app running?"
)
