package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamafarm.atmosphere.inference.ModelManager
import com.llamafarm.atmosphere.inference.UniversalRuntime
import com.llamafarm.atmosphere.viewmodel.InferenceViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceScreen(
    viewModel: InferenceViewModel = viewModel(),
    isMeshConnected: Boolean = false,
    onMeshInference: ((String, (String?, String?) -> Unit) -> Unit)? = null,
    atmosphereViewModel: com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel? = null
) {
    // Mesh state - always use mesh routing when connected
    val meshConnected by atmosphereViewModel?.meshConnected?.collectAsState() 
        ?: remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val canChat = uiState.isModelLoaded || isMeshConnected
    var showModelPicker by remember { mutableStateOf(false) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var chatInput by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // role, content
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    
    // Bind to service on first composition
    LaunchedEffect(Unit) {
        viewModel.bindService()
        viewModel.refreshModels()
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Inference") },
                actions = {
                    // Persona selector
                    IconButton(onClick = { showPersonaPicker = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Change Persona")
                    }
                    // Model selector
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.Psychology, contentDescription = "Manage Models")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status bar
            StatusBar(
                uiState = uiState,
                onStartService = { viewModel.startService() },
                onStopService = { viewModel.stopService() }
            )
            
            // Routing info (mesh is always used when available)
            if (meshConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mesh routing active",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Requests are routed through the mesh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiary
                        ) {
                            Text(
                                text = "✓",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiary
                            )
                        }
                    }
                }
            }
            
            // Error display
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
            
            // Download progress
            if (uiState.downloadState is ModelManager.DownloadState.Downloading) {
                val state = uiState.downloadState as ModelManager.DownloadState.Downloading
                DownloadProgressCard(
                    modelName = uiState.downloadingModelName ?: "Model",
                    progress = state.progress,
                    bytesDownloaded = state.bytesDownloaded,
                    totalBytes = state.totalBytes,
                    onCancel = { viewModel.cancelDownload() }
                )
            }
            
            // Chat area - show if local model loaded OR mesh connected
            if (canChat) {
                // Chat messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chatMessages) { (role, content) ->
                        ChatBubble(
                            isUser = role == "user",
                            content = content
                        )
                    }
                    
                    // Generation indicator
                    if (uiState.isGenerating) {
                        item {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Generating...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                
                // Input area
                ChatInputBar(
                    input = chatInput,
                    onInputChange = { chatInput = it },
                    onSend = {
                        if (chatInput.isNotBlank() && !uiState.isGenerating) {
                            val userMessage = chatInput
                            chatInput = ""
                            chatMessages = chatMessages + ("user" to userMessage)
                            
                            // Always use mesh routing when connected
                            if (meshConnected && atmosphereViewModel != null) {
                                // Route via mesh for LLM inference
                                chatMessages = chatMessages + ("assistant" to "🔧 Routing via mesh...")
                                scope.launch {
                                    try {
                                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val service = atmosphereViewModel?.serviceConnector?.getService()
                                            if (service != null && service.isNativeRunning()) {
                                                // Route via SemanticRouter to find best project
                                                val router = com.llamafarm.atmosphere.router.SemanticRouter.getInstance(context)
                                                val routeDecision = router.route(userMessage)
                                                val projectPath = routeDecision?.capability?.projectPath ?: "discoverable/atmosphere-universal"
                                                val routedLabel = routeDecision?.capability?.label ?: "atmosphere-universal"
                                                android.util.Log.i("InferenceScreen", "🎯 Routed '$userMessage' → $routedLabel ($projectPath)")

                                                val requestId = java.util.UUID.randomUUID().toString()
                                                val messagesJson = org.json.JSONArray().apply {
                                                    put(org.json.JSONObject().apply {
                                                        put("role", "user")
                                                        put("content", userMessage)
                                                    })
                                                }.toString()
                                                
                                                // Insert via JNI
                                                val handle = service.getAtmosphereHandle()
                                                val docJson = org.json.JSONObject().apply {
                                                    put("_id", requestId)
                                                    put("request_id", requestId)
                                                    put("prompt", userMessage)
                                                    put("messages", messagesJson)
                                                    put("model", "auto")
                                                    put("project_path", projectPath)
                                                    put("status", "pending")
                                                    put("timestamp", System.currentTimeMillis())
                                                    put("source", "android")
                                                }.toString()
                                                
                                                com.llamafarm.atmosphere.core.AtmosphereNative.insert(
                                                    handle, "_requests", requestId, docJson
                                                )
                                                android.util.Log.i("InferenceScreen", "📤 CRDT request inserted: $requestId")
                                                
                                                // Poll _responses for up to 30s
                                                var responseContent: String? = null
                                                val deadline = System.currentTimeMillis() + 30_000
                                                while (System.currentTimeMillis() < deadline && responseContent == null) {
                                                    Thread.sleep(500)
                                                    val responsesJson = com.llamafarm.atmosphere.core.AtmosphereNative.query(handle, "_responses")
                                                    val responses = org.json.JSONArray(responsesJson)
                                                    for (i in 0 until responses.length()) {
                                                        val doc = responses.getJSONObject(i)
                                                        if (doc.optString("request_id") == requestId) {
                                                            responseContent = doc.optString("content")
                                                            android.util.Log.i("InferenceScreen", "📥 Got response for $requestId")
                                                            break
                                                        }
                                                    }
                                                }
                                                responseContent ?: "❌ Timeout waiting for mesh response (30s)"
                                            } else {
                                                "❌ Mesh not available"
                                            }
                                        }
                                        chatMessages = chatMessages.dropLastWhile { it.first == "assistant" }
                                        chatMessages = chatMessages + ("assistant" to result)
                                    } catch (e: Exception) {
                                        chatMessages = chatMessages.dropLastWhile { it.first == "assistant" }
                                        chatMessages = chatMessages + ("assistant" to "❌ Mesh error: ${e.message}")
                                    }
                                }
                            } else if (uiState.isModelLoaded) {
                                // Local inference
                                scope.launch {
                                    val response = StringBuilder()
                                    try {
                                        viewModel.chat(userMessage).collect { token ->
                                            response.append(token)
                                            chatMessages = chatMessages.dropLast(1).let { msgs ->
                                                if (msgs.lastOrNull()?.first == "assistant") {
                                                    msgs.dropLast(1) + ("assistant" to response.toString())
                                                } else {
                                                    msgs + ("assistant" to response.toString())
                                                }
                                            }
                                        }
                                        chatMessages = chatMessages.dropLastWhile { it.first == "assistant" } + 
                                            ("assistant" to response.toString())
                                    } catch (e: Exception) {
                                        chatMessages = chatMessages + ("assistant" to "Error: ${e.message}")
                                    }
                                }
                            } else if (isMeshConnected && onMeshInference != null) {
                                // Mesh fallover inference
                                chatMessages = chatMessages + ("assistant" to "⏳ Routing to mesh...")
                                onMeshInference(userMessage) { response, error ->
                                    chatMessages = chatMessages.dropLastWhile { it.first == "assistant" }
                                    if (error != null) {
                                        chatMessages = chatMessages + ("assistant" to "❌ Mesh error: $error")
                                    } else {
                                        chatMessages = chatMessages + ("assistant" to (response ?: "No response"))
                                    }
                                }
                            }
                        }
                    },
                    onCancel = { viewModel.cancelGeneration() },
                    isGenerating = uiState.isGenerating,
                    enabled = canChat
                )
            } else {
                // No model loaded - show model selection
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Text(
                            if (isMeshConnected) "Mesh Available" else "No model loaded",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isMeshConnected) "No local model — connect to mesh for remote inference"
                            else "Download and load a model to start chatting",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { showModelPicker = true }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Manage Models")
                        }
                    }
                }
            }
        }
    }
    
    // Model picker dialog
    if (showModelPicker) {
        ModelPickerDialog(
            models = uiState.availableModels,
            currentModelId = uiState.currentModelId,
            onDismiss = { showModelPicker = false },
            onDownload = { modelId -> viewModel.downloadModel(modelId) },
            onLoad = { modelId -> 
                viewModel.loadModel(modelId)
                showModelPicker = false
            },
            onDelete = { modelId -> viewModel.deleteModel(modelId) }
        )
    }
    
    // Persona picker dialog
    if (showPersonaPicker) {
        PersonaPickerDialog(
            currentPersona = uiState.currentPersona,
            onDismiss = { showPersonaPicker = false },
            onSelect = { persona ->
                viewModel.setPersona(persona)
                chatMessages = emptyList() // Clear chat on persona change
                showPersonaPicker = false
            }
        )
    }
}

@Composable
private fun StatusBar(
    uiState: InferenceViewModel.InferenceUiState,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (uiState.isModelLoaded) 
                        uiState.currentModelName ?: "Model Loaded"
                    else if (uiState.isServiceBound)
                        "Service Ready"
                    else
                        "Service Stopped",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Persona: ${uiState.currentPersona.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!uiState.isNativeAvailable) {
                    Text(
                        "⚠️ Native library not available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Row {
                // Status indicator
                val statusColor = when {
                    uiState.isModelLoaded -> MaterialTheme.colorScheme.primary
                    uiState.isServiceBound -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(end = 4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = statusColor,
                                modifier = Modifier.fillMaxSize()
                            ) {}
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                uiState.isModelLoaded -> "Ready"
                                uiState.isServiceBound -> "Idle"
                                else -> "Off"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                
                // Service control
                IconButton(
                    onClick = if (uiState.isServiceBound) onStopService else onStartService
                ) {
                    Icon(
                        if (uiState.isServiceBound) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isServiceBound) "Stop" else "Start"
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    modelName: String,
    progress: Float,
    bytesDownloaded: Long,
    totalBytes: Long,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Downloading $modelName",
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)} (${(progress * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatBubble(
    isUser: Boolean,
    content: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean
) {
    Surface(
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = enabled && !isGenerating,
                maxLines = 3,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Stop, contentDescription = "Cancel")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank() && enabled
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<ModelManager.ModelInfo>,
    currentModelId: String?,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Models") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(models) { model ->
                    ModelItem(
                        model = model,
                        isLoaded = model.config.id == currentModelId,
                        onDownload = { onDownload(model.config.id) },
                        onLoad = { onLoad(model.config.id) },
                        onDelete = { onDelete(model.config.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ModelItem(
    model: ModelManager.ModelInfo,
    isLoaded: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.config.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        model.config.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Size: ${formatBytes(model.config.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                if (isLoaded) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "LOADED",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (model.isDownloaded) {
                    Button(
                        onClick = onLoad,
                        enabled = !isLoaded,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isLoaded) "Loaded" else "Load")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !isLoaded
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaPickerDialog(
    currentPersona: UniversalRuntime.Persona,
    onDismiss: () -> Unit,
    onSelect: (UniversalRuntime.Persona) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Persona") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UniversalRuntime.Persona.entries.filter { it != UniversalRuntime.Persona.CUSTOM }.forEach { persona ->
                    Surface(
                        onClick = { onSelect(persona) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (persona == currentPersona) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = persona == currentPersona,
                                onClick = { onSelect(persona) }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    persona.displayName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    persona.systemPrompt.take(80) + "...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
