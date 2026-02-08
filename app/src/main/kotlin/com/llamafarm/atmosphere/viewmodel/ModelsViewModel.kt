package com.llamafarm.atmosphere.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.inference.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ModelsViewModel"

/**
 * ViewModel for the Models screen.
 * Manages model registry operations and UI state.
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val registry = ModelRegistry(application)
    
    // Expose registry state
    val customModels = registry.customModels
    val personas = registry.personas
    val selectedModelId = registry.selectedModelId
    val selectedPersonaId = registry.selectedPersonaId
    val downloadState = registry.downloadState
    
    // Derived state
    private val _allModels = MutableStateFlow<List<ModelEntry>>(emptyList())
    val allModels: StateFlow<List<ModelEntry>> = _allModels.asStateFlow()
    
    private val _allPersonas = MutableStateFlow<List<Persona>>(emptyList())
    val allPersonas: StateFlow<List<Persona>> = _allPersonas.asStateFlow()
    
    private val _storageInfo = MutableStateFlow<ModelRegistry.StorageInfo?>(null)
    val storageInfo: StateFlow<ModelRegistry.StorageInfo?> = _storageInfo.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    init {
        // Refresh models and personas whenever custom ones change
        viewModelScope.launch {
            customModels.collect {
                refreshModels()
            }
        }
        
        viewModelScope.launch {
            personas.collect {
                refreshPersonas()
            }
        }
        
        // Initial refresh
        refreshModels()
        refreshPersonas()
        refreshStorageInfo()
    }
    
    private fun refreshModels() {
        _allModels.value = registry.getAllModels()
    }
    
    private fun refreshPersonas() {
        _allPersonas.value = registry.getAllPersonas()
    }
    
    private fun refreshStorageInfo() {
        _storageInfo.value = registry.getStorageInfo()
    }
    
    // ========================================================================
    // Model Operations
    // ========================================================================
    
    /**
     * Select a model by ID.
     */
    fun selectModel(modelId: String) {
        if (!registry.isModelReady(modelId)) {
            _error.value = "Model not downloaded"
            return
        }
        registry.selectModel(modelId)
        Log.i(TAG, "Selected model: $modelId")
    }
    
    /**
     * Add a model from HuggingFace URL.
     */
    fun addModelFromUrl(url: String): Boolean {
        val model = registry.addModelFromUrl(url)
        if (model == null) {
            _error.value = "Failed to parse model URL"
            return false
        }
        refreshModels()
        return true
    }
    
    /**
     * Download a model (built-in or custom).
     */
    suspend fun downloadModel(modelId: String) {
        Log.i(TAG, "Starting download: $modelId")
        
        val result = if (ModelManager.AVAILABLE_MODELS.any { it.id == modelId }) {
            registry.downloadBuiltInModel(modelId)
        } else {
            registry.downloadCustomModel(modelId)
        }
        
        result.onSuccess {
            Log.i(TAG, "Download complete: $modelId -> $it")
            refreshModels()
            refreshStorageInfo()
        }.onFailure { e ->
            Log.e(TAG, "Download failed: $modelId", e)
            _error.value = "Download failed: ${e.message}"
        }
    }
    
    /**
     * Cancel current download.
     */
    fun cancelDownload() {
        registry.cancelDownload()
    }
    
    /**
     * Delete a downloaded model file.
     */
    fun deleteModel(modelId: String) {
        // For custom models, remove entirely
        val customModel = customModels.value.find { it.id == modelId }
        if (customModel != null) {
            registry.removeCustomModel(modelId)
        } else {
            // For built-in, just delete the file
            registry.deleteModelFile(modelId)
        }
        refreshModels()
        refreshStorageInfo()
    }
    
    // ========================================================================
    // Persona Operations
    // ========================================================================
    
    /**
     * Select a persona by ID.
     */
    fun selectPersona(personaId: String) {
        registry.selectPersona(personaId)
        Log.i(TAG, "Selected persona: $personaId")
    }
    
    /**
     * Add a new persona.
     */
    fun addPersona(persona: Persona): Boolean {
        val result = registry.addPersona(persona)
        if (result) {
            refreshPersonas()
        } else {
            _error.value = "Failed to add persona"
        }
        return result
    }
    
    /**
     * Update an existing persona.
     */
    fun updatePersona(persona: Persona): Boolean {
        val result = registry.updatePersona(persona)
        if (result) {
            refreshPersonas()
        }
        return result
    }
    
    /**
     * Delete a persona.
     */
    fun deletePersona(personaId: String): Boolean {
        val result = registry.removePersona(personaId)
        if (result) {
            refreshPersonas()
        } else {
            _error.value = "Cannot delete built-in persona"
        }
        return result
    }
    
    // ========================================================================
    // Query Methods (for other ViewModels/Services)
    // ========================================================================
    
    /**
     * Get the currently selected model path for inference.
     */
    fun getSelectedModelPath(): String? {
        val modelId = selectedModelId.value ?: return null
        return registry.getModelPath(modelId)
    }
    
    /**
     * Get the currently selected persona's system prompt.
     */
    fun getSelectedSystemPrompt(): String {
        return registry.getSelectedPersona().systemPrompt
    }
    
    /**
     * Check if any model is ready for inference.
     */
    fun hasReadyModel(): Boolean {
        return _allModels.value.any { it.isDownloaded }
    }
    
    /**
     * Clear error state.
     */
    fun clearError() {
        _error.value = null
    }
}
