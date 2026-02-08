package com.llamafarm.atmosphere.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.inference.LlamaCppEngine
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.inference.ModelManager
import com.llamafarm.atmosphere.inference.UniversalRuntime
import com.llamafarm.atmosphere.service.InferenceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

private const val TAG = "InferenceViewModel"

/**
 * ViewModel for managing local LLM inference.
 * 
 * Handles model downloading, service binding, and inference requests.
 * Can expose local inference as a mesh capability.
 */
class InferenceViewModel(application: Application) : AndroidViewModel(application) {
    
    /**
     * Combined state for UI display.
     */
    data class InferenceUiState(
        val isServiceBound: Boolean = false,
        val isModelLoaded: Boolean = false,
        val isNativeAvailable: Boolean = false,
        val currentModelId: String? = null,
        val currentModelName: String? = null,
        val currentPersona: UniversalRuntime.Persona = UniversalRuntime.Persona.ASSISTANT,
        val downloadState: ModelManager.DownloadState = ModelManager.DownloadState.Idle,
        val downloadingModelName: String? = null,
        val availableModels: List<ModelManager.ModelInfo> = emptyList(),
        val chatHistory: List<UniversalRuntime.Message> = emptyList(),
        val isGenerating: Boolean = false,
        val error: String? = null
    )
    
    private val _uiState = MutableStateFlow(InferenceUiState())
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()
    
    // Model manager (accessible without service)
    private val modelManager = ModelManager(application)
    
    // Service connection
    private var inferenceService: InferenceService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? InferenceService.LocalBinder)?.getService()
            inferenceService = service
            serviceBound = true
            
            Log.i(TAG, "Bound to InferenceService")
            
            // Update state from service
            service?.let { svc ->
                viewModelScope.launch {
                    svc.serviceState.collect { state ->
                        updateUiState {
                            copy(
                                isServiceBound = true,
                                isModelLoaded = state is InferenceService.ServiceState.ModelLoaded,
                                error = if (state is InferenceService.ServiceState.Error) state.message else null
                            )
                        }
                    }
                }
                
                viewModelScope.launch {
                    svc.currentModelId.collect { modelId ->
                        val modelName = modelId?.let { modelManager.getModelById(it)?.config?.name }
                        updateUiState {
                            copy(
                                currentModelId = modelId,
                                currentModelName = modelName
                            )
                        }
                    }
                }
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            inferenceService = null
            serviceBound = false
            updateUiState {
                copy(
                    isServiceBound = false,
                    isModelLoaded = false,
                    currentModelId = null,
                    currentModelName = null
                )
            }
            Log.i(TAG, "Disconnected from InferenceService")
        }
    }
    
    init {
        // Check native availability
        val nativeAvailable = LlamaCppEngine.isNativeAvailable()
        updateUiState { copy(isNativeAvailable = nativeAvailable) }
        
        // Load available models
        refreshModels()
        
        // Observe download state
        viewModelScope.launch {
            modelManager.downloadState.collect { state ->
                updateUiState { copy(downloadState = state) }
            }
        }
        
        viewModelScope.launch {
            modelManager.currentDownloadModel.collect { model ->
                updateUiState { copy(downloadingModelName = model?.name) }
            }
        }
    }
    
    /**
     * Bind to the inference service.
     */
    fun bindService() {
        if (serviceBound) return
        
        val context = getApplication<Application>()
        val intent = Intent(context, InferenceService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * Unbind from the service.
     */
    fun unbindService() {
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
            serviceBound = false
            inferenceService = null
        }
    }
    
    /**
     * Start the inference service and optionally load a model.
     */
    fun startService(modelId: String? = null, persona: UniversalRuntime.Persona? = null) {
        InferenceService.start(
            getApplication(),
            modelId = modelId ?: ModelManager.DEFAULT_MODEL.id,
            persona = persona?.name
        )
        bindService()
    }
    
    /**
     * Stop the inference service.
     */
    fun stopService() {
        unbindService()
        InferenceService.stop(getApplication())
    }
    
    /**
     * Refresh the list of available models.
     */
    fun refreshModels() {
        val models = modelManager.getModels()
        updateUiState { copy(availableModels = models) }
    }
    
    /**
     * Extract bundled model from assets (if available).
     */
    fun extractBundledModel(modelId: String? = null) {
        val config = if (modelId != null) {
            ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
        } else {
            ModelManager.DEFAULT_MODEL
        }
        
        if (config == null) {
            updateUiState { copy(error = "Model not found: $modelId") }
            return
        }
        
        viewModelScope.launch {
            val result = modelManager.extractBundledModel(config)
            if (result.isSuccess) {
                Log.i(TAG, "Bundled model extracted: ${result.getOrNull()}")
                refreshModels()
            } else {
                updateUiState { copy(error = result.exceptionOrNull()?.message) }
            }
        }
    }
    
    /**
     * Download a model from HuggingFace (or extract if bundled).
     */
    fun downloadModel(modelId: String? = null) {
        val config = if (modelId != null) {
            ModelManager.AVAILABLE_MODELS.find { it.id == modelId }
        } else {
            ModelManager.DEFAULT_MODEL
        }
        
        if (config == null) {
            updateUiState { copy(error = "Model not found: $modelId") }
            return
        }
        
        // Check if model is bundled first
        val modelInfo = modelManager.getModelById(config.id)
        if (modelInfo?.isBundled == true && !modelInfo.isDownloaded) {
            Log.i(TAG, "Model is bundled, extracting instead of downloading")
            extractBundledModel(modelId)
            return
        }
        
        viewModelScope.launch {
            val result = modelManager.downloadModel(config)
            if (result.isSuccess) {
                refreshModels()
            } else {
                updateUiState { copy(error = result.exceptionOrNull()?.message) }
            }
        }
    }
    
    /**
     * Cancel ongoing download.
     */
    fun cancelDownload() {
        modelManager.cancelDownload()
    }
    
    /**
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String) {
        modelManager.deleteModel(modelId)
        refreshModels()
    }
    
    /**
     * Load a model in the service.
     */
    fun loadModel(modelId: String, persona: UniversalRuntime.Persona = UniversalRuntime.Persona.ASSISTANT) {
        if (!serviceBound) {
            startService(modelId, persona)
        } else {
            InferenceService.loadModel(getApplication(), modelId, persona.name)
        }
        updateUiState { copy(currentPersona = persona) }
    }
    
    /**
     * Unload the current model.
     */
    fun unloadModel() {
        InferenceService.unloadModel(getApplication())
    }
    
    /**
     * Change persona.
     */
    fun setPersona(persona: UniversalRuntime.Persona) {
        viewModelScope.launch {
            inferenceService?.setPersona(persona)
            updateUiState { copy(currentPersona = persona) }
        }
    }
    
    /**
     * Send a chat message and get streaming response.
     */
    fun chat(message: String): Flow<String> {
        val service = inferenceService
        if (service == null || !_uiState.value.isModelLoaded) {
            return flow { throw IllegalStateException("Model not loaded") }
        }
        
        return service.chat(message) ?: flow { throw IllegalStateException("Chat not available") }
    }
    
    /**
     * Send a chat message and collect full response.
     */
    fun chatAsync(message: String, onComplete: (String?, String?) -> Unit) {
        viewModelScope.launch {
            updateUiState { copy(isGenerating = true) }
            
            try {
                val response = StringBuilder()
                chat(message).collect { token ->
                    response.append(token)
                }
                updateUiState { copy(isGenerating = false) }
                onComplete(response.toString(), null)
            } catch (e: Exception) {
                updateUiState { copy(isGenerating = false, error = e.message) }
                onComplete(null, e.message)
            }
        }
    }
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        inferenceService?.cancelGeneration()
        updateUiState { copy(isGenerating = false) }
    }
    
    /**
     * Clear chat history.
     */
    fun clearHistory() {
        inferenceService?.clearHistory()
        updateUiState { copy(chatHistory = emptyList()) }
    }
    
    /**
     * Check if default model is ready.
     */
    fun isDefaultModelReady(): Boolean = modelManager.isDefaultModelReady()
    
    /**
     * Get model path for mesh capability registration.
     */
    fun getModelPath(modelId: String? = null): String? {
        return if (modelId != null) {
            modelManager.getModelPath(modelId)
        } else {
            modelManager.getDefaultModelPath()
        }
    }
    
    /**
     * Check if native inference is available.
     */
    fun isNativeAvailable(): Boolean = LlamaCppEngine.isNativeAvailable()
    
    /**
     * Get storage info.
     */
    fun getStorageInfo(): Pair<Long, Long> {
        return Pair(modelManager.getTotalStorageUsed(), modelManager.getAvailableStorage())
    }
    
    /**
     * Reset download state.
     */
    fun resetDownloadState() {
        modelManager.resetDownloadState()
    }
    
    /**
     * Clear error.
     */
    fun clearError() {
        updateUiState { copy(error = null) }
    }
    
    private inline fun updateUiState(update: InferenceUiState.() -> InferenceUiState) {
        _uiState.value = _uiState.value.update()
    }
    
    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
