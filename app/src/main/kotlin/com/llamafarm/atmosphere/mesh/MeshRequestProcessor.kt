package com.llamafarm.atmosphere.mesh

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.core.AtmosphereNative
import com.llamafarm.atmosphere.inference.LlamaCppEngine
import com.llamafarm.atmosphere.inference.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Processes incoming mesh inference requests targeting this phone's local Llama 3.2 model.
 * Polls the _requests CRDT collection and runs on-device inference for matching requests.
 */
class MeshRequestProcessor(
    private val atmosphereHandle: Long,
    private val context: Context
) {
    companion object {
        private const val TAG = "MeshRequestProcessor"
        private const val POLL_INTERVAL_MS = 3000L
        private const val TARGET_CAPABILITY = "local:llama-3.2-1b:default"
        private const val MODEL_FILENAME = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        private const val MODEL_NAME = "Llama-3.2-1B-Instruct-Q4_K_M"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedRequests = HashSet<String>()
    private var pollingJob: Job? = null
    private var isProcessing = false
    private var modelLoaded = false
    private var myPeerId: String? = null

    fun start() {
        if (pollingJob != null) {
            Log.w(TAG, "Already started")
            return
        }
        Log.i(TAG, "Starting mesh request processor")
        myPeerId = resolveMyPeerId()
        Log.i(TAG, "My peer_id: $myPeerId")
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollForRequests()
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling requests", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping mesh request processor")
        pollingJob?.cancel()
        pollingJob = null
        scope.cancel()
    }

    private fun resolveMyPeerId(): String? {
        return try {
            val healthJson = AtmosphereNative.health(atmosphereHandle)
            val health = JSONObject(healthJson)
            health.optString("peer_id", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get peer_id from health: ${e.message}")
            null
        }
    }

    private suspend fun pollForRequests() {
        if (isProcessing) {
            Log.d(TAG, "Skipping poll — inference in progress")
            return
        }

        val requestsJson = try {
            AtmosphereNative.query(atmosphereHandle, "_requests")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query _requests: ${e.message}")
            return
        }

        val requests = try {
            JSONArray(requestsJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse _requests JSON: ${e.message}")
            return
        }

        for (i in 0 until requests.length()) {
            val doc = requests.optJSONObject(i) ?: continue
            val requestId = doc.optString("_id", "").ifEmpty {
                doc.optString("request_id", "")
            }
            if (requestId.isEmpty()) continue
            if (processedRequests.contains(requestId)) continue

            val status = doc.optString("status", "")
            if (status != "pending") continue

            val target = doc.optString("target", "")
            val targetCapability = doc.optString("target_capability", "")
            if (target != TARGET_CAPABILITY && targetCapability != TARGET_CAPABILITY) continue

            Log.i(TAG, "📥 Found matching request: $requestId")
            processedRequests.add(requestId)
            processRequest(requestId, doc)
            // Process one at a time (model is single-threaded)
            break
        }
    }

    private suspend fun processRequest(requestId: String, doc: JSONObject) {
        isProcessing = true
        try {
            // Mark as processing
            updateRequestStatus(requestId, doc, "processing")

            // Extract prompt
            val prompt = extractPrompt(doc)
            if (prompt.isNullOrBlank()) {
                Log.e(TAG, "No prompt found in request $requestId")
                writeErrorResponse(requestId, "No prompt found in request")
                updateRequestStatus(requestId, doc, "error")
                return
            }
            Log.i(TAG, "Prompt (${prompt.length} chars): ${prompt.take(100)}...")

            // Ensure model is loaded
            if (!ensureModelLoaded()) {
                Log.e(TAG, "Failed to load model for request $requestId")
                writeErrorResponse(requestId, "Failed to load model")
                updateRequestStatus(requestId, doc, "error")
                return
            }

            // Run inference
            val engine = LlamaCppEngine.getInstance(context)
            val response = StringBuilder()
            val startTime = System.currentTimeMillis()

            withContext(Dispatchers.Default) {
                engine.generate(prompt).collect { token ->
                    response.append(token)
                }
            }

            val inferenceMs = System.currentTimeMillis() - startTime
            val content = response.toString()
            Log.i(TAG, "✅ Inference complete: ${content.length} chars in ${inferenceMs}ms")

            // Write response
            writeSuccessResponse(requestId, content, inferenceMs)
            updateRequestStatus(requestId, doc, "completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing request $requestId", e)
            writeErrorResponse(requestId, "Inference error: ${e.message}")
            try { updateRequestStatus(requestId, doc, "error") } catch (_: Exception) {}
        } finally {
            isProcessing = false
        }
    }

    private fun extractPrompt(doc: JSONObject): String? {
        // Try "prompt" field first
        val prompt = doc.optString("prompt", "")
        if (prompt.isNotBlank()) return prompt

        // Try "messages" array (chat format)
        val messages = doc.optJSONArray("messages") ?: return null
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user")
            val content = msg.optString("content", "")
            sb.appendLine("$role: $content")
        }
        return sb.toString().ifBlank { null }
    }

    private suspend fun ensureModelLoaded(): Boolean {
        if (modelLoaded) {
            val engine = LlamaCppEngine.getInstance(context)
            if (engine.state.value is LlamaCppEngine.State.ModelReady) return true
        }

        Log.i(TAG, "Loading model for first mesh request...")

        // Ensure model file exists in internal storage
        val modelManager = ModelManager(context)
        var modelPath = modelManager.getDefaultModelPath()
        if (modelPath == null) {
            Log.i(TAG, "Extracting bundled model from assets...")
            val result = modelManager.extractBundledModel()
            if (result.isFailure) {
                Log.e(TAG, "Failed to extract model: ${result.exceptionOrNull()?.message}")
                return false
            }
            modelPath = result.getOrNull()
        }
        if (modelPath == null) return false

        val engine = LlamaCppEngine.getInstance(context)
        if (!LlamaCppEngine.isNativeAvailable()) {
            Log.e(TAG, "Native library not available")
            return false
        }

        val loadResult = engine.loadModel(modelPath)
        if (loadResult.isFailure) {
            Log.e(TAG, "Failed to load model: ${loadResult.exceptionOrNull()?.message}")
            return false
        }

        modelLoaded = true
        Log.i(TAG, "✅ Model loaded: $modelPath")
        return true
    }

    private fun updateRequestStatus(requestId: String, originalDoc: JSONObject, status: String) {
        try {
            // Clone and update status
            val updated = JSONObject(originalDoc.toString())
            updated.put("status", status)
            AtmosphereNative.insert(atmosphereHandle, "_requests", requestId, updated.toString())
            Log.d(TAG, "Updated request $requestId status → $status")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update request status: ${e.message}")
        }
    }

    private fun writeSuccessResponse(requestId: String, content: String, inferenceMs: Long) {
        try {
            val responseDoc = JSONObject().apply {
                put("_id", requestId)
                put("request_id", requestId)
                put("peer_id", myPeerId ?: "unknown")
                put("content", content)
                put("model", MODEL_NAME)
                put("inference_ms", inferenceMs)
                put("timestamp", System.currentTimeMillis() / 1000)
                put("status", "completed")
            }
            AtmosphereNative.insert(atmosphereHandle, "_responses", requestId, responseDoc.toString())
            Log.i(TAG, "📤 Response written for $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write response: ${e.message}")
        }
    }

    private fun writeErrorResponse(requestId: String, error: String) {
        try {
            val responseDoc = JSONObject().apply {
                put("_id", requestId)
                put("request_id", requestId)
                put("peer_id", myPeerId ?: "unknown")
                put("content", error)
                put("model", MODEL_NAME)
                put("inference_ms", 0)
                put("timestamp", System.currentTimeMillis() / 1000)
                put("status", "error")
            }
            AtmosphereNative.insert(atmosphereHandle, "_responses", requestId, responseDoc.toString())
            Log.w(TAG, "📤 Error response written for $requestId: $error")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error response: ${e.message}")
        }
    }
}
