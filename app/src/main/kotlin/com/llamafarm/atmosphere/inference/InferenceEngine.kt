package com.llamafarm.atmosphere.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for inference engines.
 * 
 * This allows swapping between different inference backends:
 * - LocalInferenceEngine (uses ARM AiChat wrapper)
 * - LlamaCppEngine (direct llama.cpp JNI bindings)
 */
interface InferenceEngine {
    
    /**
     * State of the inference engine.
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()
        object LoadingModel : State()
        object ModelReady : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()
        object Generating : State()
        object UnloadingModel : State()
        data class Error(val exception: Exception) : State()
        
        val isModelLoaded: Boolean
            get() = this is ModelReady || this is ProcessingSystemPrompt || 
                    this is ProcessingUserPrompt || this is Generating
        
        val canAcceptPrompt: Boolean
            get() = this is ModelReady
    }
    
    /**
     * Model information after loading.
     */
    data class ModelInfo(
        val path: String,
        val contextSize: Int,
        val vocabSize: Int = 0,
        val nParams: Long = 0
    )
    
    /**
     * Generation parameters.
     */
    data class GenerationParams(
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val stopSequences: List<String> = emptyList()
    )
    
    /**
     * Current state of the engine.
     */
    val state: StateFlow<State>
    
    /**
     * Model info if a model is loaded.
     */
    val modelInfo: StateFlow<ModelInfo?>
    
    /**
     * Load a model from the given path.
     */
    suspend fun loadModel(
        modelPath: String,
        contextSize: Int = 4096
    ): Result<ModelInfo>
    
    /**
     * Set the system prompt.
     */
    suspend fun setSystemPrompt(prompt: String): Result<Unit>
    
    /**
     * Generate completion for user input, streaming tokens.
     */
    fun generate(
        userPrompt: String,
        params: GenerationParams = GenerationParams()
    ): Flow<String>
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration()
    
    /**
     * Unload the current model.
     */
    fun unloadModel()
    
    /**
     * Destroy the engine and release all resources.
     */
    fun destroy()
    
    /**
     * Get current model info.
     */
    fun getModelInfo(): ModelInfo?
    
    /**
     * Get system info from native library.
     */
    fun getSystemInfo(): String
    
    companion object {
        /**
         * Check if native inference is available.
         */
        fun isNativeAvailable(): Boolean = 
            LlamaCppEngine.isNativeAvailable() || LocalInferenceEngine.isNativeAvailable()
    }
}

/**
 * Wrapper to adapt LocalInferenceEngine to the InferenceEngine interface.
 */
class LocalInferenceEngineAdapter(
    private val engine: LocalInferenceEngine
) : InferenceEngine {
    
    override val state: StateFlow<InferenceEngine.State>
        get() = engine.state.let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow(mapState(flow.value)).also { mapped ->
                // Note: For proper implementation, we'd need to collect and map the flow
            }
        }
    
    override val modelInfo: StateFlow<InferenceEngine.ModelInfo?>
        get() = engine.modelInfo.let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow(flow.value?.let { mapModelInfo(it) })
        }
    
    private fun mapState(state: LocalInferenceEngine.State): InferenceEngine.State {
        return when (state) {
            is LocalInferenceEngine.State.Uninitialized -> InferenceEngine.State.Uninitialized
            is LocalInferenceEngine.State.Initializing -> InferenceEngine.State.Initializing
            is LocalInferenceEngine.State.Initialized -> InferenceEngine.State.Initialized
            is LocalInferenceEngine.State.LoadingModel -> InferenceEngine.State.LoadingModel
            is LocalInferenceEngine.State.ModelReady -> InferenceEngine.State.ModelReady
            is LocalInferenceEngine.State.ProcessingSystemPrompt -> InferenceEngine.State.ProcessingSystemPrompt
            is LocalInferenceEngine.State.ProcessingUserPrompt -> InferenceEngine.State.ProcessingUserPrompt
            is LocalInferenceEngine.State.Generating -> InferenceEngine.State.Generating
            is LocalInferenceEngine.State.UnloadingModel -> InferenceEngine.State.UnloadingModel
            is LocalInferenceEngine.State.Error -> InferenceEngine.State.Error(state.exception)
        }
    }
    
    private fun mapModelInfo(info: LocalInferenceEngine.ModelInfo): InferenceEngine.ModelInfo {
        return InferenceEngine.ModelInfo(
            path = info.path,
            contextSize = info.contextSize,
            vocabSize = info.vocabSize,
            nParams = info.nParams
        )
    }
    
    override suspend fun loadModel(modelPath: String, contextSize: Int): Result<InferenceEngine.ModelInfo> {
        return engine.loadModel(modelPath, contextSize).map { mapModelInfo(it) }
    }
    
    override suspend fun setSystemPrompt(prompt: String): Result<Unit> {
        return engine.setSystemPrompt(prompt)
    }
    
    override fun generate(userPrompt: String, params: InferenceEngine.GenerationParams): kotlinx.coroutines.flow.Flow<String> {
        val localParams = LocalInferenceEngine.GenerationParams(
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty,
            stopSequences = params.stopSequences
        )
        return engine.generate(userPrompt, localParams)
    }
    
    override fun cancelGeneration() = engine.cancelGeneration()
    override fun unloadModel() = engine.unloadModel()
    override fun destroy() = engine.destroy()
    override fun getModelInfo(): InferenceEngine.ModelInfo? = engine.getModelInfo()?.let { mapModelInfo(it) }
    override fun getSystemInfo(): String = engine.getSystemInfo()
}

/**
 * Wrapper to adapt LlamaCppEngine to the InferenceEngine interface.
 */
class LlamaCppEngineAdapter(
    private val engine: LlamaCppEngine
) : InferenceEngine {
    
    override val state: StateFlow<InferenceEngine.State>
        get() = engine.state.let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow(mapState(flow.value))
        }
    
    override val modelInfo: StateFlow<InferenceEngine.ModelInfo?>
        get() = engine.modelInfo.let { flow ->
            kotlinx.coroutines.flow.MutableStateFlow(flow.value?.let { mapModelInfo(it) })
        }
    
    private fun mapState(state: LlamaCppEngine.State): InferenceEngine.State {
        return when (state) {
            is LlamaCppEngine.State.Uninitialized -> InferenceEngine.State.Uninitialized
            is LlamaCppEngine.State.Initializing -> InferenceEngine.State.Initializing
            is LlamaCppEngine.State.Initialized -> InferenceEngine.State.Initialized
            is LlamaCppEngine.State.LoadingModel -> InferenceEngine.State.LoadingModel
            is LlamaCppEngine.State.ModelReady -> InferenceEngine.State.ModelReady
            is LlamaCppEngine.State.ProcessingSystemPrompt -> InferenceEngine.State.ProcessingSystemPrompt
            is LlamaCppEngine.State.ProcessingUserPrompt -> InferenceEngine.State.ProcessingUserPrompt
            is LlamaCppEngine.State.Generating -> InferenceEngine.State.Generating
            is LlamaCppEngine.State.UnloadingModel -> InferenceEngine.State.UnloadingModel
            is LlamaCppEngine.State.Error -> InferenceEngine.State.Error(state.exception)
        }
    }
    
    private fun mapModelInfo(info: LlamaCppEngine.ModelInfo): InferenceEngine.ModelInfo {
        return InferenceEngine.ModelInfo(
            path = info.path,
            contextSize = info.contextSize,
            vocabSize = info.vocabSize,
            nParams = info.nParams
        )
    }
    
    override suspend fun loadModel(modelPath: String, contextSize: Int): Result<InferenceEngine.ModelInfo> {
        return engine.loadModel(modelPath, contextSize).map { mapModelInfo(it) }
    }
    
    override suspend fun setSystemPrompt(prompt: String): Result<Unit> {
        return engine.setSystemPrompt(prompt)
    }
    
    override fun generate(userPrompt: String, params: InferenceEngine.GenerationParams): kotlinx.coroutines.flow.Flow<String> {
        val engineParams = LlamaCppEngine.GenerationParams(
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty,
            stopSequences = params.stopSequences
        )
        return engine.generate(userPrompt, engineParams)
    }
    
    override fun cancelGeneration() = engine.cancelGeneration()
    override fun unloadModel() = engine.unloadModel()
    override fun destroy() = engine.destroy()
    override fun getModelInfo(): InferenceEngine.ModelInfo? = engine.getModelInfo()?.let { mapModelInfo(it) }
    override fun getSystemInfo(): String = engine.getSystemInfo()
}
