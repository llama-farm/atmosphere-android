package com.llamafarm.atmosphere.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    data class ChatMessage(
        val content: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(content: String) {
        viewModelScope.launch {
            // Add user message
            _messages.value = _messages.value + ChatMessage(content, isUser = true)
            
            // Show loading
            _isLoading.value = true
            
            try {
                // Call native inference
                val response = runInference(content)
                _messages.value = _messages.value + ChatMessage(response, isUser = false)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    "Error: ${e.message}",
                    isUser = false
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun runInference(prompt: String): String {
        // Placeholder - will be replaced with actual native call
        // For now, simulate a response
        delay(1000)
        return try {
            nativeInference(prompt)
        } catch (e: UnsatisfiedLinkError) {
            "Native library not loaded. Build with Rust support to enable inference."
        }
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
