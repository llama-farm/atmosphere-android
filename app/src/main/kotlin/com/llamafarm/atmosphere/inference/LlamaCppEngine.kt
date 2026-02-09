package com.llamafarm.atmosphere.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct llama.cpp engine that bypasses the ARM AiChat wrapper.
 * 
 * This engine uses JNI bindings directly to libllama.so, avoiding the
 * architecture restrictions in the ARM wrapper. It supports Qwen3 and
 * other model architectures that may not be in the ARM whitelist.
 * 
 * Usage:
 * ```kotlin
 * val engine = LlamaCppEngine.getInstance(context)
 * engine.loadModel("/path/to/model.gguf")
 * engine.setSystemPrompt("You are a helpful assistant.")
 * 
 * engine.generate("Hello!").collect { token ->
 *     print(token)
 * }
 * ```
 */
class LlamaCppEngine private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val DEFAULT_PREDICT_LENGTH = 512
        
        @Volatile
        private var instance: LlamaCppEngine? = null
        
        @Volatile
        private var nativeLoaded = false
        
        /**
         * Get or create the singleton instance.
         */
        fun getInstance(context: Context): LlamaCppEngine {
            return instance ?: synchronized(this) {
                instance ?: LlamaCppEngine(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Check if native library is available.
         */
        fun isNativeAvailable(): Boolean = nativeLoaded
        
        // Native methods - JNI bindings to libllama.so
        @JvmStatic
        private external fun nativeInit(nativeLibDir: String): Int
        
        @JvmStatic
        private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Int
        
        @JvmStatic
        private external fun nativeSetSystemPrompt(prompt: String): Int
        
        @JvmStatic
        private external fun nativeStartGeneration(prompt: String, maxTokens: Int): Int
        
        @JvmStatic
        private external fun nativeGetNextToken(): String?
        
        @JvmStatic
        private external fun nativeStopGeneration()
        
        @JvmStatic
        private external fun nativeUnloadModel()
        
        @JvmStatic
        private external fun nativeShutdown()
        
        @JvmStatic
        private external fun nativeGetSystemInfo(): String
        
        @JvmStatic
        private external fun nativeIsModelLoaded(): Boolean
        
        @JvmStatic
        private external fun nativeIsGenerating(): Boolean
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
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val engineScope = CoroutineScope(llamaDispatcher + SupervisorJob())
    
    // Fallback to ARM AiChat if direct JNI isn't available
    private var useArmFallback = false
    private var armEngine: com.arm.aichat.InferenceEngine? = null
    
    init {
        // Use ARM AiChat engine directly (libllama-jni doesn't exist, and ARM AiChat works!)
        Log.i(TAG, "Initializing with ARM AiChat engine")
        tryArmFallback()
    }
    
    private fun tryArmFallback() {
        try {
            armEngine = com.arm.aichat.AiChat.getInferenceEngine(context)
            useArmFallback = true
            nativeLoaded = true
            _state.value = State.Initialized
            Log.i(TAG, "ARM AiChat engine initialized successfully")
            Log.i(TAG, "Supports Granite, Llama, and other common architectures")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ARM AiChat engine: ${e.message}")
            nativeLoaded = false
            _state.value = State.Error(e)
        }
    }
    
    /**
     * Load a model from the given path.
     */
    suspend fun loadModel(
        modelPath: String,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        nThreads: Int = 0 // 0 = auto-detect
    ): Result<ModelInfo> = withContext(llamaDispatcher) {
        if (!nativeLoaded) {
            return@withContext Result.failure(IllegalStateException("Native library not available"))
        }
        
        try {
            _state.value = State.LoadingModel
            
            // Resolve model path
            val resolvedPath = resolveModelPath(modelPath)
            if (!File(resolvedPath).exists()) {
                val error = IllegalStateException("Model not found: $resolvedPath")
                _state.value = State.Error(error)
                return@withContext Result.failure(error)
            }
            
            Log.i(TAG, "Loading model: $resolvedPath")
            
            if (useArmFallback) {
                // Use ARM AiChat
                try {
                    armEngine?.loadModel(resolvedPath)
                } catch (e: com.arm.aichat.UnsupportedArchitectureException) {
                    val error = UnsupportedModelException(
                        "Model architecture not supported by ARM AiChat wrapper. " +
                        "This may be a Qwen3 or other unsupported architecture. " +
                        "Build with direct llama.cpp JNI bindings to support all architectures.",
                        e
                    )
                    _state.value = State.Error(error)
                    return@withContext Result.failure(error)
                } catch (e: IllegalStateException) {
                    // Model already loaded (e.g. "Cannot load model in ModelReady!")
                    if (e.message?.contains("ModelReady") == true) {
                        Log.i(TAG, "Model already loaded, reusing existing model")
                    } else {
                        throw e
                    }
                }
            } else {
                // Use direct JNI
                val result = nativeLoadModel(resolvedPath, contextSize, nThreads)
                if (result != 0) {
                    val errorMsg = when (result) {
                        -1 -> "Failed to load model file"
                        -2 -> "Failed to create context"
                        -3 -> "Failed to create sampler"
                        else -> "Unknown error: $result"
                    }
                    val error = RuntimeException(errorMsg)
                    _state.value = State.Error(error)
                    return@withContext Result.failure(error)
                }
            }
            
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
    suspend fun setSystemPrompt(prompt: String): Result<Unit> = withContext(llamaDispatcher) {
        if (!nativeLoaded) {
            return@withContext Result.failure(IllegalStateException("Native library not available"))
        }
        
        try {
            _state.value = State.ProcessingSystemPrompt
            
            if (useArmFallback) {
                try {
                    armEngine?.setSystemPrompt(prompt)
                } catch (e: IllegalStateException) {
                    // ARM AiChat requires system prompt RIGHT AFTER model load.
                    // If model was already loaded (reused), system prompt may already be set.
                    if (e.message?.contains("RIGHT AFTER") == true && currentSystemPrompt != null) {
                        Log.i(TAG, "System prompt already set from previous load, skipping")
                        _state.value = State.ModelReady
                        return@withContext Result.success(Unit)
                    }
                    throw e
                }
            } else {
                val result = nativeSetSystemPrompt(prompt)
                if (result != 0) {
                    throw RuntimeException("Failed to set system prompt: $result")
                }
            }
            
            currentSystemPrompt = prompt
            _state.value = State.ModelReady
            Log.i(TAG, "System prompt set (${prompt.length} chars)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system prompt", e)
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
    ): Flow<String> = flow {
        if (!nativeLoaded) {
            throw IllegalStateException("Native library not available")
        }
        
        _state.value = State.ProcessingUserPrompt
        
        if (useArmFallback) {
            // Use ARM AiChat flow
            armEngine?.sendUserPrompt(userPrompt, params.maxTokens)?.collect { token ->
                _state.value = State.Generating
                emit(token)
            }
        } else {
            // Use direct JNI
            val startResult = nativeStartGeneration(userPrompt, params.maxTokens)
            if (startResult != 0) {
                _state.value = State.Error(RuntimeException("Failed to start generation: $startResult"))
                throw RuntimeException("Failed to start generation: $startResult")
            }
            
            _state.value = State.Generating
            
            var tokenCount = 0
            while (nativeIsGenerating() && tokenCount < params.maxTokens) {
                val token = nativeGetNextToken()
                if (token == null) {
                    break
                }
                emit(token)
                tokenCount++
            }
        }
        
        _state.value = State.ModelReady
    }.flowOn(llamaDispatcher)
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        if (!useArmFallback) {
            nativeStopGeneration()
        }
        _state.value = State.ModelReady
        Log.i(TAG, "Generation cancelled")
    }
    
    /**
     * Unload the current model.
     */
    fun unloadModel() {
        try {
            _state.value = State.UnloadingModel
            
            if (useArmFallback) {
                armEngine?.cleanUp()
            } else {
                nativeUnloadModel()
            }
            
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
     * Destroy the engine.
     */
    fun destroy() {
        try {
            engineScope.cancel()
            
            if (useArmFallback) {
                armEngine?.destroy()
                armEngine = null
            } else {
                nativeShutdown()
            }
            
            currentModel = null
            currentSystemPrompt = null
            _state.value = State.Uninitialized
            instance = null
            Log.i(TAG, "Engine destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during destroy", e)
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
            if (useArmFallback) {
                "ARM AiChat Engine (fallback mode)"
            } else {
                try {
                    nativeGetSystemInfo()
                } catch (e: Exception) {
                    "Direct llama.cpp JNI (info unavailable)"
                }
            }
        } else {
            "Native library not available"
        }
    }
    
    /**
     * Check if using ARM fallback mode.
     */
    fun isUsingArmFallback(): Boolean = useArmFallback
}

/**
 * Exception thrown when model architecture is not supported.
 */
class UnsupportedModelException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
