package com.llamafarm.atmosphere.llamafarm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.llamafarm.atmosphere.inference.LlamaCppEngine
import com.llamafarm.atmosphere.inference.ModelManager
import com.llamafarm.atmosphere.rag.LocalRagStore
import com.llamafarm.atmosphere.vision.VisionCapability
import com.llamafarm.atmosphere.vision.DetectionResult
import com.llamafarm.atmosphere.capabilities.CameraCapability
import com.llamafarm.atmosphere.capabilities.CameraFacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * LlamaFarm Lite - Unified on-device AI layer for Atmosphere.
 * 
 * Integrates:
 * - LLM inference (LlamaCppEngine with ARM AiChat)
 * - Prompt management (PromptManager)
 * - RAG (LocalRagStore)
 * - Vision (VisionCapability with TFLite)
 * 
 * Exposes all capabilities through the Atmosphere SDK AIDL interface.
 * 
 * Usage:
 * ```kotlin
 * val llamaFarm = LlamaFarmLite.getInstance(context, cameraCapability)
 * 
 * // Load LLM
 * llamaFarm.loadLlm("granite-3.1-1b-q4km")
 * 
 * // Chat completion
 * llamaFarm.chat("Hello!").collect { token -> print(token) }
 * 
 * // RAG-enhanced chat
 * llamaFarm.createRagIndex("docs", listOf("doc1" to "content..."))
 * llamaFarm.chatWithRag("docs", "What is...?").collect { token -> print(token) }
 * 
 * // Vision detection
 * val detection = llamaFarm.detectObjects(imageBytes)
 * ```
 */
class LlamaFarmLite private constructor(
    private val context: Context,
    private val cameraCapability: CameraCapability
) {
    
    companion object {
        private const val TAG = "LlamaFarmLite"
        
        @Volatile
        private var instance: LlamaFarmLite? = null
        
        /**
         * Get the singleton instance.
         */
        fun getInstance(context: Context, cameraCapability: CameraCapability): LlamaFarmLite {
            return instance ?: synchronized(this) {
                instance ?: LlamaFarmLite(context.applicationContext, cameraCapability).also {
                    instance = it
                }
            }
        }
    }
    
    // Core components
    private val llmEngine = LlamaCppEngine.getInstance(context)
    private val modelManager = ModelManager(context)
    private val promptManager = PromptManager()
    private val ragStore = LocalRagStore()
    private val visionCapability = VisionCapability(context, "local", cameraCapability)
    
    // State
    private var currentModelId: String? = null
    private var currentPersona: String = "assistant"
    
    // ========================== LLM API ==========================
    
    /**
     * Check if LLM is ready.
     */
    fun isLlmReady(): Boolean {
        return llmEngine.state.value == LlamaCppEngine.State.ModelReady
    }
    
    /**
     * Load an LLM model.
     */
    suspend fun loadLlm(modelId: String, contextSize: Int = 4096): Result<String> {
        val modelPath = modelManager.getModelPath(modelId)
            ?: return Result.failure(Exception("Model not found: $modelId. Download it first."))
        
        val result = llmEngine.loadModel(modelPath, contextSize)
        if (result.isSuccess) {
            currentModelId = modelId
            Log.i(TAG, "LLM loaded: $modelId")
        }
        
        return result.map { it.path }
    }
    
    /**
     * Set system prompt / persona.
     */
    suspend fun setPersona(persona: String) {
        currentPersona = persona
        val systemPrompt = promptManager.getPersona(persona) 
            ?: "You are a helpful AI assistant."
        
        llmEngine.setSystemPrompt(systemPrompt)
        Log.i(TAG, "Persona set: $persona")
    }
    
    /**
     * Chat completion (streaming).
     */
    fun chat(
        userMessage: String,
        maxTokens: Int = 512
    ): Flow<String> {
        val modelId = currentModelId ?: "default"
        val systemPrompt = promptManager.getPersona(currentPersona)
        
        val formattedPrompt = promptManager.formatChat(
            modelId = modelId,
            user = userMessage,
            system = systemPrompt
        )
        
        return llmEngine.generate(
            formattedPrompt,
            LlamaCppEngine.GenerationParams(maxTokens = maxTokens)
        )
    }
    
    /**
     * Chat with RAG context injection.
     */
    fun chatWithRag(
        indexId: String,
        userMessage: String,
        topK: Int = 3,
        maxTokens: Int = 512
    ): Flow<String> = flow {
        val modelId = currentModelId ?: "default"
        
        // Retrieve relevant context
        val context = ragStore.queryForContext(indexId, userMessage, topK)
        
        // Format with RAG template
        val formattedPrompt = promptManager.formatRagChat(
            modelId = modelId,
            user = userMessage,
            context = context,
            system = promptManager.getPersona(currentPersona)
        )
        
        // Stream response
        llmEngine.generate(
            formattedPrompt,
            LlamaCppEngine.GenerationParams(maxTokens = maxTokens)
        ).collect { token ->
            emit(token)
        }
    }
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        llmEngine.cancelGeneration()
    }
    
    /**
     * Unload current LLM.
     */
    fun unloadLlm() {
        llmEngine.unloadModel()
        currentModelId = null
        Log.i(TAG, "LLM unloaded")
    }
    
    // ========================== RAG API ==========================
    
    /**
     * Create a RAG index from documents.
     */
    suspend fun createRagIndex(
        indexId: String,
        documents: List<Pair<String, String>>  // (id, content)
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val index = ragStore.createIndex(indexId, documents)
            Log.i(TAG, "RAG index created: $indexId (${index.documents.size} docs)")
            Result.success(indexId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create RAG index", e)
            Result.failure(e)
        }
    }
    
    /**
     * Create RAG index from JSON.
     */
    suspend fun createRagIndexFromJson(indexId: String, documentsJson: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val index = ragStore.createIndexFromJson(indexId, documentsJson)
                Log.i(TAG, "RAG index created from JSON: $indexId (${index.documents.size} docs)")
                Result.success(indexId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create RAG index from JSON", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Query RAG index (without LLM).
     */
    suspend fun queryRag(
        indexId: String,
        query: String,
        topK: Int = 3
    ): List<LocalRagStore.QueryResult> {
        return ragStore.query(indexId, query, topK)
    }
    
    /**
     * Delete RAG index.
     */
    fun deleteRagIndex(indexId: String): Boolean {
        return ragStore.deleteIndex(indexId)
    }
    
    /**
     * List all RAG indexes.
     */
    fun listRagIndexes(): List<LocalRagStore.RagIndex> {
        return ragStore.listIndexes()
    }
    
    // ========================== Vision API ==========================
    
    /**
     * Check if vision is ready.
     */
    fun isVisionReady(): Boolean {
        return visionCapability.isReady.value
    }
    
    /**
     * Detect objects in an image.
     */
    suspend fun detectObjects(imageBytes: ByteArray, sourceId: String = "api"): DetectionResult? {
        return visionCapability.detect(imageBytes, sourceId)
    }
    
    /**
     * Capture from camera and detect.
     */
    suspend fun captureAndDetect(facing: CameraFacing = CameraFacing.BACK): DetectionResult? {
        return visionCapability.captureAndDetect(facing)
    }
    
    /**
     * Set vision confidence threshold for escalation.
     */
    fun setVisionConfidenceThreshold(threshold: Float) {
        visionCapability.setConfidenceThreshold(threshold)
    }
    
    /**
     * Switch vision model.
     */
    suspend fun switchVisionModel(modelId: String, version: String): Boolean {
        return visionCapability.switchModel(modelId, version)
    }
    
    /**
     * Train a custom vision model on a dataset.
     * 
     * @param datasetPath URI or path to the dataset folder
     * @param modelName Name for the new model
     * @return Result with model ID or error
     */
    suspend fun trainVisionModel(datasetPath: String, modelName: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: Implement actual training via LlamaFarm API
                // POST /v1/vision/train with dataset_path, model_name, epochs, etc.
                // For now, return a mock success
                Log.i(TAG, "Training model $modelName with dataset at $datasetPath")
                
                // Simulate training delay
                kotlinx.coroutines.delay(5000)
                
                Result.success(modelName)
            } catch (e: Exception) {
                Log.e(TAG, "Training failed", e)
                Result.failure(e)
            }
        }
    }
    
    // ========================== Prompt API ==========================
    
    /**
     * Get all available prompt templates.
     */
    fun getPromptTemplates(): List<PromptManager.PromptTemplate> {
        return promptManager.getAllTemplates()
    }
    
    /**
     * Register a custom prompt template.
     */
    fun registerPromptTemplate(template: PromptManager.PromptTemplate) {
        promptManager.registerTemplate(template)
    }
    
    /**
     * Associate a template with a model.
     */
    fun setModelPromptTemplate(modelId: String, templateName: String) {
        promptManager.setModelTemplate(modelId, templateName)
    }
    
    /**
     * Get all available personas.
     */
    fun getPersonas(): Map<String, String> {
        return promptManager.getAllPersonas()
    }
    
    // ========================== Model Management ==========================
    
    /**
     * Get available LLM models.
     */
    fun getAvailableModels(): List<ModelManager.ModelInfo> {
        return modelManager.getModels()
    }
    
    /**
     * Download an LLM model.
     */
    suspend fun downloadModel(modelId: String): Result<String> {
        val config = ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
            ?: return Result.failure(Exception("Model not found: $modelId"))
        
        return modelManager.downloadModel(config)
    }
    
    /**
     * Check if a model is downloaded.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        return modelManager.getModelById(modelId)?.isDownloaded ?: false
    }
    
    /**
     * Delete a model.
     */
    fun deleteModel(modelId: String): Boolean {
        return modelManager.deleteModel(modelId)
    }
    
    // ========================== Capability Info (for SDK/AIDL) ==========================
    
    /**
     * Get capability info as JSON for SDK exposure.
     */
    fun getCapabilityInfo(): JSONObject {
        val capabilities = JSONObject()
        
        // LLM capability
        capabilities.put("llm", JSONObject().apply {
            put("available", isLlmReady())
            put("model", currentModelId ?: "none")
            put("persona", currentPersona)
            put("engine", if (llmEngine.isUsingArmFallback()) "arm_aichat" else "direct_jni")
            put("supported_architectures", JSONArray().apply {
                put("Granite")
                put("Llama")
                put("Mistral")
                put("Phi")
                put("Gemma")
            })
        })
        
        // RAG capability
        capabilities.put("rag", JSONObject().apply {
            put("available", true)
            put("indexes", ragStore.listIndexes().size)
            put("algorithm", "BM25")
        })
        
        // Vision capability
        capabilities.put("vision", visionCapability.getCapabilityJson())
        
        // Prompt management
        capabilities.put("prompts", JSONObject().apply {
            put("templates", promptManager.getAllTemplates().size)
            put("personas", promptManager.getAllPersonas().size)
        })
        
        return capabilities
    }
    
    /**
     * Export configuration to JSON.
     */
    fun exportConfig(): JSONObject {
        val config = JSONObject()
        config.put("version", "1.0")
        config.put("model_id", currentModelId)
        config.put("persona", currentPersona)
        config.put("prompts", promptManager.toJson())
        config.put("rag_indexes", JSONArray().apply {
            ragStore.listIndexes().forEach { index ->
                put(JSONObject().apply {
                    put("id", index.id)
                    put("document_count", index.documents.size)
                    put("created_at", index.createdAt)
                })
            }
        })
        return config
    }
    
    /**
     * Import configuration from JSON.
     */
    fun importConfig(json: JSONObject) {
        val modelId = json.optString("model_id", null)
        if (modelId != null) {
            currentModelId = modelId
        }
        
        val persona = json.optString("persona", "assistant")
        currentPersona = persona
        
        val promptsJson = json.optJSONObject("prompts")
        if (promptsJson != null) {
            promptManager.fromJson(promptsJson)
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        llmEngine.destroy()
        visionCapability.destroy()
        Log.i(TAG, "LlamaFarmLite destroyed")
    }
}
