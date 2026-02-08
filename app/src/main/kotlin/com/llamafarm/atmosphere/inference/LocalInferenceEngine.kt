package com.llamafarm.atmosphere.inference

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine as AarInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.io.File

/**
 * Local inference engine wrapping the llama.cpp AAR for on-device LLM inference.
 * 
 * This provides a coroutine-friendly interface to the native llama.cpp library,
 * supporting model loading, system prompts, and streaming token generation.
 */
class LocalInferenceEngine private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocalInferenceEngine"
        private const val DEFAULT_PREDICT_LENGTH = 512
        
        @Volatile
        private var instance: LocalInferenceEngine? = null
        
        @Volatile
        private var nativeLoaded = false
        
        /**
         * Get or create the singleton instance.
         */
        fun getInstance(context: Context): LocalInferenceEngine {
            return instance ?: synchronized(this) {
                instance ?: LocalInferenceEngine(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Check if native library is available.
         */
        fun isNativeAvailable(): Boolean = nativeLoaded
    }
    
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
        val maxTokens: Int = DEFAULT_PREDICT_LENGTH,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val stopSequences: List<String> = emptyList()
    )
    
    // Internal state
    private val _state = MutableStateFlow<State>(State.Uninitialized)
    val state: StateFlow<State> = _state.asStateFlow()
    
    private val _modelInfo = MutableStateFlow<ModelInfo?>(null)
    val modelInfo: StateFlow<ModelInfo?> = _modelInfo.asStateFlow()
    
    private var currentModel: ModelInfo?
        get() = _modelInfo.value
        set(value) { _modelInfo.value = value }
    
    private var currentSystemPrompt: String? = null
    
    // AAR engine (lazily initialized)
    private var aarEngine: AarInferenceEngine? = null
    
    // Coroutine scope for engine operations
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Try to initialize the AAR engine
        try {
            aarEngine = AiChat.getInferenceEngine(context)
            nativeLoaded = true
            _state.value = State.Initialized
            Log.i(TAG, "AAR InferenceEngine initialized successfully")
            
            // Forward state changes from AAR engine
            engineScope.launch {
                aarEngine?.state?.collect { aarState ->
                    _state.value = mapAarState(aarState)
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available: ${e.message}")
            nativeLoaded = false
            _state.value = State.Initialized // Still mark as initialized, just without native
        } catch (e: NoSuchMethodError) {
            // Kotlinx-coroutines version mismatch with AAR
            Log.w(TAG, "AAR coroutines version mismatch: ${e.message}")
            nativeLoaded = false
            _state.value = State.Initialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AAR engine: ${e.message}", e)
            nativeLoaded = false
            _state.value = State.Initialized
        }
    }
    
    private fun mapAarState(aarState: AarInferenceEngine.State): State {
        return when (aarState) {
            is AarInferenceEngine.State.Uninitialized -> State.Uninitialized
            is AarInferenceEngine.State.Initializing -> State.Initializing
            is AarInferenceEngine.State.Initialized -> State.Initialized
            is AarInferenceEngine.State.LoadingModel -> State.LoadingModel
            is AarInferenceEngine.State.UnloadingModel -> State.UnloadingModel
            is AarInferenceEngine.State.ModelReady -> State.ModelReady
            is AarInferenceEngine.State.Benchmarking -> State.Generating
            is AarInferenceEngine.State.ProcessingSystemPrompt -> State.ProcessingSystemPrompt
            is AarInferenceEngine.State.ProcessingUserPrompt -> State.ProcessingUserPrompt
            is AarInferenceEngine.State.Generating -> State.Generating
            is AarInferenceEngine.State.Error -> State.Error(aarState.exception)
        }
    }
    
    /**
     * Load a model from the given path.
     */
    suspend fun loadModel(
        modelPath: String,
        contextSize: Int = 4096
    ): Result<ModelInfo> {
        if (!nativeLoaded) {
            return Result.failure(IllegalStateException("Native library not available"))
        }
        
        val engine = aarEngine ?: return Result.failure(IllegalStateException("Engine not initialized"))
        
        return try {
            _state.value = State.LoadingModel
            
            // Resolve model path
            val resolvedPath = resolveModelPath(modelPath)
            if (!File(resolvedPath).exists()) {
                return Result.failure(IllegalStateException("Model not found: $resolvedPath"))
            }
            
            Log.i(TAG, "Loading model: $resolvedPath")
            engine.loadModel(resolvedPath)
            
            currentModel = ModelInfo(
                path = resolvedPath,
                contextSize = contextSize
            )
            
            _state.value = State.ModelReady
            Log.i(TAG, "Model loaded successfully")
            
            Result.success(currentModel!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            _state.value = State.Error(e)
            Result.failure(e)
        }
    }
    
    private fun resolveModelPath(path: String): String {
        // Check if it's an absolute path
        if (File(path).exists()) return path
        
        // Check in app's files directory
        val filesPath = File(context.filesDir, path)
        if (filesPath.exists()) return filesPath.absolutePath
        
        // Check in models subdirectory
        val modelsPath = File(context.filesDir, "models/$path")
        if (modelsPath.exists()) return modelsPath.absolutePath
        
        // Return original path (will fail later if not found)
        return path
    }
    
    /**
     * Set system prompt (alias for processSystemPrompt).
     */
    suspend fun setSystemPrompt(prompt: String): Result<Unit> = processSystemPrompt(prompt)
    
    /**
     * Process a system prompt.
     */
    suspend fun processSystemPrompt(prompt: String): Result<Unit> {
        if (!nativeLoaded) {
            return Result.failure(IllegalStateException("Native library not available"))
        }
        
        val engine = aarEngine ?: return Result.failure(IllegalStateException("Engine not initialized"))
        
        return try {
            _state.value = State.ProcessingSystemPrompt
            engine.setSystemPrompt(prompt)
            currentSystemPrompt = prompt
            _state.value = State.ModelReady
            Log.i(TAG, "System prompt processed (${prompt.length} chars)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process system prompt", e)
            _state.value = State.Error(e)
            Result.failure(e)
        }
    }
    
    /**
     * Generate completion for user input, streaming tokens.
     */
    fun generate(
        userPrompt: String,
        params: GenerationParams = GenerationParams()
    ): Flow<String> {
        val engine = aarEngine ?: throw IllegalStateException("Engine not initialized")
        
        _state.value = State.ProcessingUserPrompt
        
        return engine.sendUserPrompt(userPrompt, params.maxTokens)
            .map { token ->
                _state.value = State.Generating
                token
            }
            .onCompletion {
                _state.value = State.ModelReady
            }
            .catch { e ->
                Log.e(TAG, "Generation error", e)
                _state.value = State.Error(e as Exception)
                throw e
            }
    }
    
    /**
     * Unload the current model.
     */
    fun unloadModel() {
        try {
            _state.value = State.UnloadingModel
            aarEngine?.cleanUp()
            currentModel = null
            currentSystemPrompt = null
            _state.value = State.Initialized
            Log.i(TAG, "Model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
            _state.value = State.Error(e)
        }
    }
    
    /**
     * Run benchmark.
     */
    suspend fun benchmark(pp: Int = 512, tg: Int = 128, pl: Int = 1, nr: Int = 1): String {
        val engine = aarEngine ?: return "Engine not initialized"
        return try {
            engine.bench(pp, tg, pl, nr)
        } catch (e: Exception) {
            "Benchmark failed: ${e.message}"
        }
    }
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        // The AAR doesn't expose a cancel method, so we just log
        Log.i(TAG, "Cancel generation requested (not supported by AAR)")
    }
    
    /**
     * Destroy the engine (alias for shutdown).
     */
    fun destroy() = shutdown()
    
    /**
     * Shutdown the engine.
     */
    fun shutdown() {
        try {
            engineScope.cancel()
            aarEngine?.destroy()
            aarEngine = null
            currentModel = null
            currentSystemPrompt = null
            _state.value = State.Uninitialized
            instance = null
            Log.i(TAG, "Engine shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
    
    /**
     * Get current model info.
     */
    fun getModelInfo(): ModelInfo? = currentModel
    
    /**
     * Get system info from native library.
     */
    fun getSystemInfo(): String {
        return if (nativeLoaded) {
            "AAR InferenceEngine - Native loaded"
        } else {
            "Native library not available"
        }
    }
}
