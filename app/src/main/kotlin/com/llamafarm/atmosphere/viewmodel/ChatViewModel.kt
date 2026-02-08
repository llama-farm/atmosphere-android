package com.llamafarm.atmosphere.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

class ChatViewModel : ViewModel() {

    data class ChatMessage(
        val content: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val isError: Boolean = false,
        val source: String? = null // "local", "mesh", "relay"
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Reference to AtmosphereViewModel for mesh routing
    private var atmosphereViewModel: AtmosphereViewModel? = null
    
    /**
     * Set the AtmosphereViewModel for mesh-based inference.
     */
    fun setAtmosphereViewModel(viewModel: AtmosphereViewModel) {
        atmosphereViewModel = viewModel
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            // Add user message
            _messages.value = _messages.value + ChatMessage(content, isUser = true)
            
            // Show loading
            _isLoading.value = true
            
            try {
                val (response, source) = runInference(content)
                _messages.value = _messages.value + ChatMessage(
                    content = response,
                    isUser = false,
                    source = source
                )
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                _messages.value = _messages.value + ChatMessage(
                    content = "Error: ${e.message}",
                    isUser = false,
                    isError = true
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun runInference(prompt: String): Pair<String, String> {
        // Try mesh routing first if connected
        val meshVm = atmosphereViewModel
        if (meshVm != null && meshVm.isConnectedToMesh.value) {
            Log.d(TAG, "Routing inference through mesh")
            return runMeshInference(meshVm, prompt)
        }
        
        // Fallback to local inference
        Log.d(TAG, "Using local inference (not connected to mesh)")
        return runLocalInference(prompt)
    }
    
    /**
     * Run inference through the mesh relay.
     */
    private suspend fun runMeshInference(viewModel: AtmosphereViewModel, prompt: String): Pair<String, String> {
        return suspendCancellableCoroutine { continuation ->
            viewModel.sendUserMessage(prompt) { response, error ->
                if (error != null) {
                    Log.e(TAG, "Mesh inference error: $error")
                    // If mesh fails, we could fallback to local, but for now return the error
                    if (continuation.isActive) {
                        continuation.resume("Mesh error: $error" to "mesh")
                    }
                } else if (response != null) {
                    Log.d(TAG, "Mesh inference response: ${response.take(100)}...")
                    if (continuation.isActive) {
                        continuation.resume(response to "mesh")
                    }
                } else {
                    if (continuation.isActive) {
                        continuation.resume("No response from mesh" to "mesh")
                    }
                }
            }
        }
    }
    
    /**
     * Run inference locally using native library.
     */
    private suspend fun runLocalInference(prompt: String): Pair<String, String> {
        delay(100) // Small delay for UI responsiveness
        return try {
            nativeInference(prompt) to "local"
        } catch (e: UnsatisfiedLinkError) {
            "No local model available. Connect to a mesh to use remote inference." to "local"
        }
    }
    
    /**
     * Clear chat history.
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    // Native method declaration - implemented in Rust
    private external fun nativeInference(prompt: String): String

    companion object {
        // Native methods for model management
        @JvmStatic
        external fun nativeLoadModel(modelPath: String): Boolean
        
        @JvmStatic
        external fun nativeUnloadModel()
        
        @JvmStatic
        external fun nativeIsModelLoaded(): Boolean
    }
}
