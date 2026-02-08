package com.llamafarm.atmosphere.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "ModelRegistry"

/**
 * Persona/System Prompt configuration.
 */
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val iconEmoji: String = "🤖",
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("system_prompt", systemPrompt)
        put("icon_emoji", iconEmoji)
        put("is_built_in", isBuiltIn)
        put("created_at", createdAt)
    }
    
    companion object {
        fun fromJson(json: JSONObject): Persona = Persona(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Unknown"),
            description = json.optString("description", ""),
            systemPrompt = json.optString("system_prompt", ""),
            iconEmoji = json.optString("icon_emoji", "🤖"),
            isBuiltIn = json.optBoolean("is_built_in", false),
            createdAt = json.optLong("created_at", System.currentTimeMillis())
        )
        
        // Built-in personas
        val DEFAULT = Persona(
            id = "default",
            name = "Default",
            description = "General-purpose assistant",
            systemPrompt = "You are a helpful, harmless, and honest AI assistant.",
            iconEmoji = "🤖",
            isBuiltIn = true
        )
        
        val CONCISE = Persona(
            id = "concise",
            name = "Concise",
            description = "Brief, to-the-point responses",
            systemPrompt = "You are a helpful assistant. Be concise and direct in your responses. Avoid unnecessary elaboration.",
            iconEmoji = "⚡",
            isBuiltIn = true
        )
        
        val CODER = Persona(
            id = "coder",
            name = "Coder",
            description = "Programming assistant",
            systemPrompt = "You are an expert programmer. Provide clean, well-commented code examples. Explain technical concepts clearly.",
            iconEmoji = "💻",
            isBuiltIn = true
        )
        
        val CREATIVE = Persona(
            id = "creative",
            name = "Creative",
            description = "Creative writing assistant",
            systemPrompt = "You are a creative writing assistant. Be imaginative, descriptive, and engaging. Help with stories, poems, and creative projects.",
            iconEmoji = "🎨",
            isBuiltIn = true
        )
        
        val TEACHER = Persona(
            id = "teacher",
            name = "Teacher",
            description = "Patient, educational assistant",
            systemPrompt = "You are a patient and knowledgeable teacher. Explain concepts step by step. Use analogies and examples to make learning easy.",
            iconEmoji = "📚",
            isBuiltIn = true
        )
        
        val BUILTINS = listOf(DEFAULT, CONCISE, CODER, CREATIVE, TEACHER)
    }
}

/**
 * Custom model configuration (for user-added models).
 */
data class CustomModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val huggingFaceRepo: String = "",
    val fileName: String,
    val sizeBytes: Long = 0,
    val quantization: String = "Q4_K_M",
    val contextLength: Int = 4096,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val downloadUrl: String
        get() = if (huggingFaceRepo.isNotEmpty()) {
            "https://huggingface.co/$huggingFaceRepo/resolve/main/$fileName"
        } else ""
    
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("huggingface_repo", huggingFaceRepo)
        put("file_name", fileName)
        put("size_bytes", sizeBytes)
        put("quantization", quantization)
        put("context_length", contextLength)
        put("is_downloaded", isDownloaded)
        put("local_path", localPath ?: "")
        put("created_at", createdAt)
    }
    
    companion object {
        fun fromJson(json: JSONObject): CustomModel = CustomModel(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Unknown"),
            description = json.optString("description", ""),
            huggingFaceRepo = json.optString("huggingface_repo", ""),
            fileName = json.optString("file_name", ""),
            sizeBytes = json.optLong("size_bytes", 0),
            quantization = json.optString("quantization", "Q4_K_M"),
            contextLength = json.optInt("context_length", 4096),
            isDownloaded = json.optBoolean("is_downloaded", false),
            localPath = json.optString("local_path", "").takeIf { it.isNotEmpty() },
            createdAt = json.optLong("created_at", System.currentTimeMillis())
        )
        
        /**
         * Parse HuggingFace URL to extract repo and filename.
         * Supports formats like:
         * - https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf
         * - unsloth/Qwen3-1.7B-GGUF:Qwen3-1.7B-Q4_K_M.gguf
         */
        fun fromHuggingFaceUrl(url: String): CustomModel? {
            return try {
                if (url.contains("huggingface.co")) {
                    // Full URL format
                    val regex = Regex("""huggingface\.co/([^/]+/[^/]+)/resolve/main/(.+\.gguf)""")
                    val match = regex.find(url) ?: return null
                    val (repo, filename) = match.destructured
                    
                    val name = filename.removeSuffix(".gguf")
                    CustomModel(
                        name = name,
                        huggingFaceRepo = repo,
                        fileName = filename
                    )
                } else if (url.contains(":")) {
                    // Short format: repo:filename
                    val parts = url.split(":")
                    if (parts.size != 2) return null
                    val repo = parts[0]
                    val filename = parts[1]
                    
                    CustomModel(
                        name = filename.removeSuffix(".gguf"),
                        huggingFaceRepo = repo,
                        fileName = filename
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HuggingFace URL: $url", e)
                null
            }
        }
    }
}

/**
 * Combined model entry (built-in or custom).
 */
sealed class ModelEntry {
    abstract val id: String
    abstract val name: String
    abstract val description: String
    abstract val isDownloaded: Boolean
    abstract val localPath: String?
    
    data class BuiltIn(val config: ModelManager.ModelConfig, val info: ModelManager.ModelInfo) : ModelEntry() {
        override val id: String get() = config.id
        override val name: String get() = config.name
        override val description: String get() = config.description
        override val isDownloaded: Boolean get() = info.isDownloaded
        override val localPath: String? get() = if (info.isDownloaded) info.localPath else null
    }
    
    data class Custom(val model: CustomModel) : ModelEntry() {
        override val id: String get() = model.id
        override val name: String get() = model.name
        override val description: String get() = model.description
        override val isDownloaded: Boolean get() = model.isDownloaded
        override val localPath: String? get() = model.localPath
    }
}

/**
 * Model Registry - manages all models and personas.
 * Persists custom models and personas to disk.
 */
class ModelRegistry(private val context: Context) {
    
    private val modelsDir = File(context.filesDir, "models")
    private val configFile = File(context.filesDir, "model_registry.json")
    
    private val modelManager = ModelManager(context)
    
    // State
    private val _customModels = MutableStateFlow<List<CustomModel>>(emptyList())
    val customModels: StateFlow<List<CustomModel>> = _customModels.asStateFlow()
    
    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()
    
    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()
    
    private val _selectedPersonaId = MutableStateFlow<String>("default")
    val selectedPersonaId: StateFlow<String> = _selectedPersonaId.asStateFlow()
    
    // Expose ModelManager states
    val downloadState = modelManager.downloadState
    val currentDownloadModel = modelManager.currentDownloadModel
    
    init {
        modelsDir.mkdirs()
        loadConfig()
    }
    
    /**
     * Get all available models (built-in + custom).
     */
    fun getAllModels(): List<ModelEntry> {
        val builtIn = modelManager.getModels().map { info ->
            val config = ModelManager.AVAILABLE_MODELS.find { it.id == info.config.id }
                ?: info.config
            ModelEntry.BuiltIn(config, info)
        }
        
        val custom = _customModels.value.map { ModelEntry.Custom(it) }
        
        return builtIn + custom
    }
    
    /**
     * Get all personas (built-in + custom).
     */
    fun getAllPersonas(): List<Persona> {
        return Persona.BUILTINS + _personas.value.filter { !it.isBuiltIn }
    }
    
    /**
     * Get currently selected model.
     */
    fun getSelectedModel(): ModelEntry? {
        val id = _selectedModelId.value ?: return null
        return getAllModels().find { it.id == id }
    }
    
    /**
     * Get currently selected persona.
     */
    fun getSelectedPersona(): Persona {
        val id = _selectedPersonaId.value
        return getAllPersonas().find { it.id == id } ?: Persona.DEFAULT
    }
    
    /**
     * Select a model by ID.
     */
    fun selectModel(modelId: String) {
        _selectedModelId.value = modelId
        saveConfig()
        Log.i(TAG, "Selected model: $modelId")
    }
    
    /**
     * Select a persona by ID.
     */
    fun selectPersona(personaId: String) {
        _selectedPersonaId.value = personaId
        saveConfig()
        Log.i(TAG, "Selected persona: $personaId")
    }
    
    /**
     * Add a custom model from HuggingFace URL.
     */
    fun addModelFromUrl(url: String): CustomModel? {
        val model = CustomModel.fromHuggingFaceUrl(url) ?: return null
        
        // Check if already exists
        if (_customModels.value.any { it.huggingFaceRepo == model.huggingFaceRepo && it.fileName == model.fileName }) {
            Log.w(TAG, "Model already exists: ${model.name}")
            return null
        }
        
        _customModels.value = _customModels.value + model
        saveConfig()
        Log.i(TAG, "Added custom model: ${model.name}")
        return model
    }
    
    /**
     * Add a custom model.
     */
    fun addCustomModel(model: CustomModel): Boolean {
        if (_customModels.value.any { it.id == model.id }) {
            Log.w(TAG, "Model already exists: ${model.id}")
            return false
        }
        
        _customModels.value = _customModels.value + model
        saveConfig()
        Log.i(TAG, "Added custom model: ${model.name}")
        return true
    }
    
    /**
     * Remove a custom model.
     */
    fun removeCustomModel(modelId: String): Boolean {
        val model = _customModels.value.find { it.id == modelId } ?: return false
        
        // Delete downloaded file if exists
        model.localPath?.let { path ->
            File(path).delete()
        }
        
        _customModels.value = _customModels.value.filter { it.id != modelId }
        
        if (_selectedModelId.value == modelId) {
            _selectedModelId.value = null
        }
        
        saveConfig()
        Log.i(TAG, "Removed custom model: ${model.name}")
        return true
    }
    
    /**
     * Add a custom persona.
     */
    fun addPersona(persona: Persona): Boolean {
        if (persona.isBuiltIn) {
            Log.w(TAG, "Cannot add built-in persona")
            return false
        }
        
        if (_personas.value.any { it.id == persona.id }) {
            Log.w(TAG, "Persona already exists: ${persona.id}")
            return false
        }
        
        _personas.value = _personas.value + persona
        saveConfig()
        Log.i(TAG, "Added persona: ${persona.name}")
        return true
    }
    
    /**
     * Update a custom persona.
     */
    fun updatePersona(persona: Persona): Boolean {
        if (persona.isBuiltIn) {
            Log.w(TAG, "Cannot update built-in persona")
            return false
        }
        
        _personas.value = _personas.value.map { 
            if (it.id == persona.id) persona else it 
        }
        saveConfig()
        Log.i(TAG, "Updated persona: ${persona.name}")
        return true
    }
    
    /**
     * Remove a custom persona.
     */
    fun removePersona(personaId: String): Boolean {
        val persona = _personas.value.find { it.id == personaId }
        
        if (persona?.isBuiltIn == true) {
            Log.w(TAG, "Cannot remove built-in persona")
            return false
        }
        
        _personas.value = _personas.value.filter { it.id != personaId }
        
        if (_selectedPersonaId.value == personaId) {
            _selectedPersonaId.value = "default"
        }
        
        saveConfig()
        Log.i(TAG, "Removed persona: $personaId")
        return true
    }
    
    /**
     * Download a built-in model.
     */
    suspend fun downloadBuiltInModel(modelId: String): Result<String> {
        val config = ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
            ?: return Result.failure(Exception("Model not found: $modelId"))
        
        return modelManager.downloadModel(config)
    }
    
    /**
     * Download a custom model.
     */
    suspend fun downloadCustomModel(modelId: String): Result<String> {
        val model = _customModels.value.find { it.id == modelId }
            ?: return Result.failure(Exception("Model not found: $modelId"))
        
        if (model.downloadUrl.isEmpty()) {
            return Result.failure(Exception("No download URL for model"))
        }
        
        // Use ModelManager's download with a temporary config
        val tempConfig = ModelManager.ModelConfig(
            id = model.id,
            name = model.name,
            huggingFaceRepo = model.huggingFaceRepo,
            fileName = model.fileName,
            sizeBytes = model.sizeBytes,
            description = model.description
        )
        
        val result = modelManager.downloadModel(tempConfig)
        
        if (result.isSuccess) {
            // Update custom model with local path
            val updatedModel = model.copy(
                isDownloaded = true,
                localPath = result.getOrNull()
            )
            _customModels.value = _customModels.value.map {
                if (it.id == modelId) updatedModel else it
            }
            saveConfig()
        }
        
        return result
    }
    
    /**
     * Cancel current download.
     */
    fun cancelDownload() {
        modelManager.cancelDownload()
    }
    
    /**
     * Delete a downloaded model file.
     */
    fun deleteModelFile(modelId: String): Boolean {
        // Check built-in models
        val builtIn = ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
        if (builtIn != null) {
            return modelManager.deleteModel(modelId)
        }
        
        // Check custom models
        val custom = _customModels.value.find { it.id == modelId } ?: return false
        custom.localPath?.let { path ->
            val file = File(path)
            if (file.delete()) {
                _customModels.value = _customModels.value.map {
                    if (it.id == modelId) it.copy(isDownloaded = false, localPath = null) else it
                }
                saveConfig()
                return true
            }
        }
        return false
    }
    
    /**
     * Get model path for inference.
     */
    fun getModelPath(modelId: String): String? {
        // Check built-in
        val builtInPath = modelManager.getModelPath(modelId)
        if (builtInPath != null) return builtInPath
        
        // Check custom
        val custom = _customModels.value.find { it.id == modelId }
        return custom?.localPath
    }
    
    /**
     * Check if model is ready for inference.
     */
    fun isModelReady(modelId: String): Boolean {
        return getModelPath(modelId) != null
    }
    
    /**
     * Get storage usage info.
     */
    fun getStorageInfo(): StorageInfo {
        val used = modelManager.getTotalStorageUsed()
        val available = modelManager.getAvailableStorage()
        return StorageInfo(used, available)
    }
    
    data class StorageInfo(val usedBytes: Long, val availableBytes: Long)
    
    // ========================================================================
    // Persistence
    // ========================================================================
    
    private fun loadConfig() {
        try {
            if (!configFile.exists()) {
                Log.i(TAG, "No config file found, using defaults")
                return
            }
            
            val json = JSONObject(configFile.readText())
            
            // Load custom models
            val modelsArray = json.optJSONArray("custom_models")
            if (modelsArray != null) {
                val models = mutableListOf<CustomModel>()
                for (i in 0 until modelsArray.length()) {
                    models.add(CustomModel.fromJson(modelsArray.getJSONObject(i)))
                }
                _customModels.value = models
            }
            
            // Load custom personas
            val personasArray = json.optJSONArray("custom_personas")
            if (personasArray != null) {
                val personas = mutableListOf<Persona>()
                for (i in 0 until personasArray.length()) {
                    personas.add(Persona.fromJson(personasArray.getJSONObject(i)))
                }
                _personas.value = personas
            }
            
            // Load selections
            _selectedModelId.value = json.optString("selected_model_id", "").takeIf { it.isNotEmpty() }
            _selectedPersonaId.value = json.optString("selected_persona_id", "default")
            
            Log.i(TAG, "Loaded config: ${_customModels.value.size} custom models, ${_personas.value.size} custom personas")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
        }
    }
    
    private fun saveConfig() {
        try {
            val json = JSONObject().apply {
                // Custom models
                put("custom_models", JSONArray().apply {
                    _customModels.value.forEach { put(it.toJson()) }
                })
                
                // Custom personas
                put("custom_personas", JSONArray().apply {
                    _personas.value.filter { !it.isBuiltIn }.forEach { put(it.toJson()) }
                })
                
                // Selections
                _selectedModelId.value?.let { put("selected_model_id", it) }
                put("selected_persona_id", _selectedPersonaId.value)
            }
            
            configFile.writeText(json.toString(2))
            Log.d(TAG, "Saved config")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }
}
