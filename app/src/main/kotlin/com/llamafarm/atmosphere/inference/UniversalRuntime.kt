package com.llamafarm.atmosphere.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Universal Runtime for managing LLM inference with:
 * - System prompt management
 * - Project/persona routing
 * - Context window management
 * - Chat history tracking
 * 
 * Now supports both LocalInferenceEngine (ARM AiChat) and LlamaCppEngine (direct llama.cpp).
 * LlamaCppEngine is preferred as it supports Qwen3 and other architectures.
 */
class UniversalRuntime private constructor(
    private val context: Context,
    private val modelManager: ModelManager,
    private val llamaCppEngine: LlamaCppEngine
) {
    // Secondary constructor for backward compatibility
    constructor(
        context: Context,
        modelManager: ModelManager,
        @Suppress("UNUSED_PARAMETER") inferenceEngine: LocalInferenceEngine
    ) : this(context, modelManager, LlamaCppEngine.getInstance(context))
    companion object {
        private const val TAG = "UniversalRuntime"
        private const val MAX_CONTEXT_MESSAGES = 20
        private const val MAX_CONTEXT_TOKENS_ESTIMATE = 3000 // Leave room for generation
        
        /**
         * Create a new UniversalRuntime using LlamaCppEngine.
         * This is the preferred method for Qwen3 and other modern architectures.
         */
        fun create(context: Context, modelManager: ModelManager): UniversalRuntime {
            return UniversalRuntime(
                context = context,
                modelManager = modelManager,
                llamaCppEngine = LlamaCppEngine.getInstance(context)
            )
        }
        
        // Default system prompts for different personas
        val DEFAULT_SYSTEM_PROMPT = """
You are a helpful AI assistant running locally on an Android device. You are part of the Atmosphere mesh network.

Key traits:
- Concise and direct in responses
- Helpful and friendly
- Honest about limitations
- Focus on practical solutions

Keep responses brief unless asked for detail.
        """.trimIndent()
        
        val CODER_SYSTEM_PROMPT = """
You are a coding assistant running locally on Android. You help with:
- Code review and debugging
- Algorithm explanations
- Best practices
- Quick code snippets

Be concise. Use code blocks with language tags. Focus on practical, working solutions.
        """.trimIndent()
        
        val CREATIVE_SYSTEM_PROMPT = """
You are a creative writing assistant. You help with:
- Story ideas and outlines
- Character development
- Dialogue writing
- Creative brainstorming

Be imaginative but keep responses focused. Quality over quantity.
        """.trimIndent()
        
        val ANALYST_SYSTEM_PROMPT = """
You are a data analyst assistant. You help with:
- Data interpretation
- Statistical concepts
- Visualization suggestions
- Report writing

Be precise and analytical. Use structured formats when helpful.
        """.trimIndent()
    }
    
    /**
     * Predefined personas/projects with their system prompts.
     */
    enum class Persona(val displayName: String, val systemPrompt: String) {
        ASSISTANT("Assistant", DEFAULT_SYSTEM_PROMPT),
        CODER("Coder", CODER_SYSTEM_PROMPT),
        CREATIVE("Creative", CREATIVE_SYSTEM_PROMPT),
        ANALYST("Analyst", ANALYST_SYSTEM_PROMPT),
        CUSTOM("Custom", "")
    }
    
    /**
     * A message in the chat history.
     */
    data class Message(
        val role: Role,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Role { USER, ASSISTANT, SYSTEM }
        
        // Rough token count estimate (4 chars per token average)
        val estimatedTokens: Int get() = content.length / 4 + 1
    }
    
    /**
     * Runtime state.
     */
    sealed class RuntimeState {
        object Idle : RuntimeState()
        object LoadingModel : RuntimeState()
        object Ready : RuntimeState()
        object Generating : RuntimeState()
        data class Error(val message: String) : RuntimeState()
    }
    
    /**
     * Configuration for the runtime.
     */
    data class Config(
        val modelId: String = ModelManager.DEFAULT_MODEL.id,
        val persona: Persona = Persona.ASSISTANT,
        val customSystemPrompt: String? = null,
        val maxContextMessages: Int = MAX_CONTEXT_MESSAGES,
        val generationParams: LlamaCppEngine.GenerationParams = LlamaCppEngine.GenerationParams()
    )
    
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()
    
    private val _currentPersona = MutableStateFlow(Persona.ASSISTANT)
    val currentPersona: StateFlow<Persona> = _currentPersona.asStateFlow()
    
    private val _chatHistory = MutableStateFlow<List<Message>>(emptyList())
    val chatHistory: StateFlow<List<Message>> = _chatHistory.asStateFlow()
    
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()
    
    private var systemPromptSet = false
    
    // RAG Data
    private val ragEntries = mutableListOf<org.json.JSONObject>()

    init {
        // Load RAG data from assets
        try {
            val inputStream = context.assets.open("rag/indo_pacific.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                ragEntries.add(jsonArray.getJSONObject(i))
            }
            Log.i(TAG, "Loaded ${ragEntries.size} RAG entries from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load RAG data", e)
        }
    }
    
    /**
     * Get the effective system prompt based on persona and custom settings.
     */
    fun getEffectiveSystemPrompt(): String {
        val cfg = _config.value
        return when {
            cfg.persona == Persona.CUSTOM && !cfg.customSystemPrompt.isNullOrBlank() -> cfg.customSystemPrompt
            else -> cfg.persona.systemPrompt
        }
    }
    
    /**
     * Initialize the runtime with a specific configuration.
     */
    suspend fun initialize(config: Config = Config()): Result<Unit> {
        _config.value = config
        _currentPersona.value = config.persona
        
        // Check if model is downloaded
        val modelPath = modelManager.getModelPath(config.modelId)
        if (modelPath == null) {
            val error = "Model not downloaded: ${config.modelId}"
            Log.e(TAG, error)
            _state.value = RuntimeState.Error(error)
            return Result.failure(IllegalStateException(error))
        }
        
        // Load the model
        _state.value = RuntimeState.LoadingModel
        
        val loadResult = llamaCppEngine.loadModel(modelPath)
        if (loadResult.isFailure) {
            val error = "Failed to load model: ${loadResult.exceptionOrNull()?.message}"
            Log.e(TAG, error)
            _state.value = RuntimeState.Error(error)
            return Result.failure(loadResult.exceptionOrNull() ?: Exception(error))
        }
        
        // Set system prompt
        val systemPrompt = getEffectiveSystemPrompt()
        val promptResult = llamaCppEngine.setSystemPrompt(systemPrompt)
        if (promptResult.isFailure) {
            Log.w(TAG, "Failed to set system prompt: ${promptResult.exceptionOrNull()?.message}")
            // Continue anyway - some models work without explicit system prompt
        }
        systemPromptSet = promptResult.isSuccess
        
        _state.value = RuntimeState.Ready
        _chatHistory.value = emptyList()
        
        Log.i(TAG, "Runtime initialized with persona: ${config.persona.displayName}")
        return Result.success(Unit)
    }
    
    /**
     * Change the persona/project (requires model reload to apply new system prompt).
     */
    suspend fun setPersona(persona: Persona, customPrompt: String? = null): Result<Unit> {
        val newConfig = _config.value.copy(
            persona = persona,
            customSystemPrompt = if (persona == Persona.CUSTOM) customPrompt else null
        )
        return initialize(newConfig)
    }
    
    /**
     * Update generation parameters without reloading the model.
     */
    fun updateGenerationParams(params: LlamaCppEngine.GenerationParams) {
        _config.value = _config.value.copy(generationParams = params)
    }
    
    /**
     * Send a message and get a streaming response.
     */
    fun chat(userMessage: String): Flow<String> {
        if (_state.value !is RuntimeState.Ready) {
            return flow { throw IllegalStateException("Runtime not ready: ${_state.value}") }
        }
        
        // Add user message to history
        val userMsg = Message(Message.Role.USER, userMessage)
        addToHistory(userMsg)
        
        // Build the prompt with context
        val prompt = buildPromptWithContext(userMessage)
        
        // Track assistant response
        val responseBuilder = StringBuilder()
        
        return llamaCppEngine.generate(prompt, _config.value.generationParams)
            .onStart {
                _state.value = RuntimeState.Generating
                Log.d(TAG, "Starting generation for: ${userMessage.take(50)}...")
            }
            .map { token ->
                responseBuilder.append(token)
                token
            }
            .onCompletion { error ->
                if (error == null) {
                    // Add assistant response to history
                    val assistantMsg = Message(Message.Role.ASSISTANT, responseBuilder.toString())
                    addToHistory(assistantMsg)
                    _state.value = RuntimeState.Ready
                } else {
                    Log.e(TAG, "Generation error", error)
                    _state.value = RuntimeState.Error(error.message ?: "Generation failed")
                }
            }
    }
    
    /**
     * Simple single-shot completion (non-streaming).
     */
    suspend fun complete(prompt: String): Result<String> {
        if (_state.value !is RuntimeState.Ready) {
            return Result.failure(IllegalStateException("Runtime not ready: ${_state.value}"))
        }
        
        val response = StringBuilder()
        
        try {
            _state.value = RuntimeState.Generating
            
            llamaCppEngine.generate(prompt, _config.value.generationParams)
                .collect { token ->
                    response.append(token)
                }
            
            _state.value = RuntimeState.Ready
            return Result.success(response.toString())
            
        } catch (e: Exception) {
            _state.value = RuntimeState.Error(e.message ?: "Completion failed")
            return Result.failure(e)
        }
    }
    
    /**
     * Build a prompt including chat history context.
     */
    private fun buildPromptWithContext(userMessage: String): String {
        // RAG Injection
        val ragContext = findRagMatches(userMessage)
        val augmentedUserMessage = if (ragContext.isNotEmpty()) {
            "Use the following context to answer the user's question:\n$ragContext\n\nUser Question: $userMessage"
        } else {
            userMessage
        }

        val history = _chatHistory.value
        if (history.size <= 1) {
            // No history yet (or just the current user message)
            return formatUserMessage(augmentedUserMessage)
        }
        
        // Build context from recent messages (excluding the last one which is the current message)
        val contextMessages = history.dropLast(1).takeLast(_config.value.maxContextMessages - 1)
        
        // Estimate tokens and trim if needed
        var totalTokens = augmentedUserMessage.length / 4
        val includedMessages = mutableListOf<Message>()
        
        for (msg in contextMessages.reversed()) {
            val msgTokens = msg.estimatedTokens
            if (totalTokens + msgTokens > MAX_CONTEXT_TOKENS_ESTIMATE) {
                break
            }
            totalTokens += msgTokens
            includedMessages.add(0, msg)
        }
        
        // Format the prompt
        // Using a simple chat format that works with most models
        val sb = StringBuilder()
        
        for (msg in includedMessages) {
            when (msg.role) {
                Message.Role.USER -> sb.append("<|user|>\n${msg.content}\n")
                Message.Role.ASSISTANT -> sb.append("<|assistant|>\n${msg.content}\n")
                Message.Role.SYSTEM -> {} // System prompt handled separately
            }
        }
        
        sb.append("<|user|>\n$augmentedUserMessage\n<|assistant|>\n")
        
        return sb.toString()
    }

    private fun findRagMatches(query: String): String {
        val keywords = query.lowercase().split(Regex("\\s+"))
            .filter { it.length > 3 } // Filter short words
        
        if (keywords.isEmpty()) return ""

        val matches = ragEntries.filter { entry ->
            val text = "${entry.optString("name")} ${entry.optString("description")} ${entry.optString("category")}".lowercase()
            keywords.any { text.contains(it) }
        }

        if (matches.isEmpty()) return ""

        return matches.joinToString("\n\n") { entry ->
            """
            [Relevant Info]
            Name: ${entry.optString("name")}
            Category: ${entry.optString("category")}
            Description: ${entry.optString("description")}
            Symptoms: ${entry.optString("symptoms")}
            Treatment: ${entry.optString("treatment")}
            """.trimIndent()
        }
    }
    
    /**
     * Format a simple user message.
     */
    private fun formatUserMessage(message: String): String {
        // Qwen3 chat format
        return "<|user|>\n$message\n<|assistant|>\n"
    }
    
    /**
     * Add a message to history, trimming old messages if needed.
     */
    private fun addToHistory(message: Message) {
        val current = _chatHistory.value.toMutableList()
        current.add(message)
        
        // Trim to max context messages
        while (current.size > _config.value.maxContextMessages) {
            current.removeAt(0)
        }
        
        _chatHistory.value = current
    }
    
    /**
     * Clear chat history (keeps system prompt).
     */
    fun clearHistory() {
        _chatHistory.value = emptyList()
        Log.d(TAG, "Chat history cleared")
    }
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        llamaCppEngine.cancelGeneration()
        _state.value = RuntimeState.Ready
    }
    
    /**
     * Check if runtime is ready for inference.
     */
    fun isReady(): Boolean = _state.value is RuntimeState.Ready
    
    /**
     * Check if native inference is available.
     */
    fun isNativeAvailable(): Boolean = LlamaCppEngine.isNativeAvailable()
    
    /**
     * Get current model info.
     */
    fun getModelInfo(): LlamaCppEngine.ModelInfo? = llamaCppEngine.modelInfo.value
    
    /**
     * Shutdown the runtime and release resources.
     */
    fun shutdown() {
        llamaCppEngine.unloadModel()
        _chatHistory.value = emptyList()
        _state.value = RuntimeState.Idle
        systemPromptSet = false
        Log.i(TAG, "Runtime shutdown")
    }
    
    /**
     * Destroy the runtime completely.
     */
    fun destroy() {
        shutdown()
        llamaCppEngine.destroy()
    }
}
