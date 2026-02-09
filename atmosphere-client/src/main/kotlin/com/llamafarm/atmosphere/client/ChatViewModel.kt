package com.llamafarm.atmosphere.client

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.sdk.AtmosphereClient
import com.llamafarm.atmosphere.sdk.AtmosphereNotInstalledException
import com.llamafarm.atmosphere.sdk.AtmosphereNotConnectedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ChatViewModel"

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    private var atmosphereClient: AtmosphereClient? = null
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow("Connecting...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()
    
    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()
    
    init {
        connectToAtmosphere()
    }
    
    /**
     * Connect to the local Atmosphere service.
     */
    private fun connectToAtmosphere() {
        viewModelScope.launch {
            try {
                Log.i(TAG, "🔌 Checking if Atmosphere is installed...")
                
                val context = getApplication<Application>()
                if (!AtmosphereClient.isInstalled(context)) {
                    _connectionStatus.value = "Atmosphere app not installed"
                    Log.e(TAG, "❌ Atmosphere app not installed on device")
                    addSystemMessage("Error: Atmosphere app not installed. Please install the Atmosphere app first.")
                    return@launch
                }
                
                Log.i(TAG, "✅ Atmosphere app found, connecting to service...")
                _connectionStatus.value = "Connecting to Atmosphere..."
                
                atmosphereClient = AtmosphereClient.connect(context)
                
                Log.i(TAG, "✅ Connected to Atmosphere service!")
                _isConnected.value = true
                _connectionStatus.value = "Connected"
                
                // Add welcome message
                addSystemMessage("Connected to Atmosphere! Your messages will be routed to the best available model.")
                
                // Get mesh status
                try {
                    val status = atmosphereClient?.meshStatus()
                    Log.i(TAG, "📱 Mesh Status: nodeId=${status?.nodeId}, connected=${status?.connected}, peers=${status?.peerCount}")
                    status?.nodeId?.let { nodeId ->
                        addSystemMessage("Atmosphere Node: ${nodeId.take(8)}... (${status.peerCount} peers)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get mesh status", e)
                }
                
                // Get available models (capabilities)
                try {
                    val capabilities = atmosphereClient?.capabilities() ?: emptyList()
                    val models = capabilities
                        .filter { it.type == "llm" || it.type == "model" }
                        .map { ModelInfo(id = it.id, name = it.name) }
                    _availableModels.value = models
                    Log.i(TAG, "📋 Found ${models.size} available models")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get models", e)
                }
                
            } catch (e: AtmosphereNotInstalledException) {
                Log.e(TAG, "❌ Atmosphere not installed", e)
                _connectionStatus.value = "Not installed"
                addSystemMessage("Error: Atmosphere app not installed.")
            } catch (e: AtmosphereNotConnectedException) {
                Log.e(TAG, "❌ Connection failed", e)
                _connectionStatus.value = "Connection failed"
                addSystemMessage("Error: Could not connect to Atmosphere. Make sure the app is running.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection error", e)
                _connectionStatus.value = "Error: ${e.message}"
                addSystemMessage("Connection error: ${e.message}")
            }
        }
    }
    
    /**
     * Send a chat message through Atmosphere.
     */
    suspend fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || !_isConnected.value) return
        
        val client = atmosphereClient
        if (client == null) {
            addMessage(ChatMessage(role = "error", content = "Not connected to Atmosphere"))
            return
        }
        
        // Add user message to UI
        addMessage(ChatMessage(role = "user", content = text))
        _inputText.value = ""
        _isSending.value = true
        
        try {
            Log.i(TAG, "📤 Sending prompt: $text")
            
            // Build conversation history using SDK ChatMessage type
            val conversationMessages = _messages.value
                .filter { it.role in listOf("user", "assistant") }
                .takeLast(10)  // Last 10 messages for context
                .map { com.llamafarm.atmosphere.sdk.ChatMessage(role = it.role, content = it.content) }
            
            Log.d(TAG, "📝 Conversation history: ${conversationMessages.size} messages")
            
            // Send chat request using selected model (null = semantic routing)
            val startTime = System.currentTimeMillis()
            val result = client.chat(conversationMessages, model = _selectedModel.value)
            val latency = System.currentTimeMillis() - startTime
            
            Log.i(TAG, "📥 Got response in ${latency}ms")
            
            if (result.success) {
                val content = result.content ?: "No response"
                val model = result.model ?: "unknown"
                
                Log.d(TAG, "✅ Response using $model: ${content.take(50)}...")
                
                // Add assistant message with metadata
                val metadata = mutableMapOf(
                    "model" to model,
                    "latency" to latency.toString()
                )
                result.usage?.totalTokens?.let { metadata["tokens"] = it.toString() }
                
                // Try to extract node info from raw response or use mesh status
                try {
                    val rawJson = org.json.JSONObject(result.raw ?: "{}")
                    val nodeId = rawJson.optString("nodeId")?.takeIf { it.isNotEmpty() }
                        ?: rawJson.optString("node_id")?.takeIf { it.isNotEmpty() }
                    
                    nodeId?.let { metadata["node"] = it.take(8) }
                    
                    // Add routing info if available
                    rawJson.optString("capability")?.takeIf { it.isNotEmpty() }?.let { cap ->
                        metadata["capability"] = cap
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse node info from response", e)
                }
                
                addMessage(ChatMessage(
                    role = "assistant",
                    content = content,
                    metadata = metadata
                ))
            } else {
                Log.e(TAG, "❌ Chat failed: ${result.error}")
                addMessage(ChatMessage(
                    role = "error",
                    content = "Error: ${result.error}"
                ))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send error", e)
            addMessage(ChatMessage(
                role = "error",
                content = "Error: ${e.message}"
            ))
        } finally {
            _isSending.value = false
        }
    }
    
    /**
     * Update input text.
     */
    fun updateInput(text: String) {
        _inputText.value = text
    }
    
    /**
     * Select a specific model (null for auto-route).
     */
    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
        val modelName = if (modelId == null) {
            "Auto-Route"
        } else {
            _availableModels.value.find { it.id == modelId }?.name ?: modelId
        }
        addSystemMessage("Switched to: $modelName")
    }
    
    /**
     * Refresh available capabilities from mesh.
     */
    fun refreshCapabilities() {
        viewModelScope.launch {
            try {
                val capabilities = atmosphereClient?.capabilities() ?: emptyList()
                val models = capabilities
                    .filter { it.type == "llm" || it.type == "model" }
                    .map { ModelInfo(id = it.id, name = it.name) }
                _availableModels.value = models
                addSystemMessage("Refreshed: ${models.size} models available")
                Log.i(TAG, "🔄 Refreshed capabilities: ${models.size} models")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh capabilities", e)
                addMessage(ChatMessage(role = "error", content = "Failed to refresh: ${e.message}"))
            }
        }
    }
    
    /**
     * Add a message to the conversation.
     */
    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
    
    /**
     * Add a system message (info/error).
     */
    private fun addSystemMessage(text: String) {
        addMessage(ChatMessage(role = "system", content = text))
    }
    
    override fun onCleared() {
        super.onCleared()
        atmosphereClient?.disconnect()
    }
}

data class ModelInfo(
    val id: String,
    val name: String
)
