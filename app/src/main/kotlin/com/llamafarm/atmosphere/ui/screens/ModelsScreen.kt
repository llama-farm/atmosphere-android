package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.inference.*
import com.llamafarm.atmosphere.viewmodel.ModelsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel) {
    val scope = rememberCoroutineScope()
    
    // Collect state
    val models by viewModel.allModels.collectAsState()
    val personas by viewModel.allPersonas.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedPersonaId by viewModel.selectedPersonaId.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    
    // UI state
    var showAddModelDialog by remember { mutableStateOf(false) }
    var showAddPersonaDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "🦙 LlamaFarm Mobile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Models & Personas",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Storage info
        StorageInfoCard(storageInfo)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Tab row
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Models") },
                icon = { Icon(Icons.Default.Memory, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Personas") },
                icon = { Icon(Icons.Default.Person, contentDescription = null) }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        when (selectedTab) {
            0 -> ModelsTab(
                models = models,
                selectedModelId = selectedModelId,
                downloadState = downloadState,
                onSelectModel = { viewModel.selectModel(it) },
                onDownloadModel = { scope.launch { viewModel.downloadModel(it) } },
                onDeleteModel = { viewModel.deleteModel(it) },
                onCancelDownload = { viewModel.cancelDownload() },
                onAddModel = { showAddModelDialog = true }
            )
            1 -> PersonasTab(
                personas = personas,
                selectedPersonaId = selectedPersonaId,
                onSelectPersona = { viewModel.selectPersona(it) },
                onDeletePersona = { viewModel.deletePersona(it) },
                onAddPersona = { showAddPersonaDialog = true }
            )
        }
    }
    
    // Add Model Dialog
    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onAddModel = { url ->
                viewModel.addModelFromUrl(url)
                showAddModelDialog = false
            }
        )
    }
    
    // Add Persona Dialog
    if (showAddPersonaDialog) {
        AddPersonaDialog(
            onDismiss = { showAddPersonaDialog = false },
            onAddPersona = { persona ->
                viewModel.addPersona(persona)
                showAddPersonaDialog = false
            }
        )
    }
}

@Composable
private fun StorageInfoCard(storageInfo: ModelRegistry.StorageInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Storage", style = MaterialTheme.typography.titleSmall)
            }
            
            if (storageInfo != null) {
                val usedMB = storageInfo.usedBytes / (1024 * 1024)
                val availableGB = storageInfo.availableBytes / (1024 * 1024 * 1024)
                Text(
                    text = "${usedMB} MB used • ${availableGB} GB free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Calculating...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelsTab(
    models: List<ModelEntry>,
    selectedModelId: String?,
    downloadState: ModelManager.DownloadState,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onAddModel: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Download progress indicator
        if (downloadState is ModelManager.DownloadState.Downloading) {
            item {
                DownloadProgressCard(downloadState, onCancelDownload)
            }
        }
        
        // Add model button
        item {
            OutlinedButton(
                onClick = onAddModel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Model from HuggingFace")
            }
        }
        
        // Model list
        items(models, key = { it.id }) { model ->
            ModelCard(
                model = model,
                isSelected = model.id == selectedModelId,
                isDownloading = downloadState is ModelManager.DownloadState.Downloading,
                onSelect = { onSelectModel(model.id) },
                onDownload = { onDownloadModel(model.id) },
                onDelete = { onDeleteModel(model.id) }
            )
        }
    }
}

@Composable
private fun DownloadProgressCard(
    state: ModelManager.DownloadState.Downloading,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Downloading...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(Modifier.height(4.dp))
            
            val downloadedMB = state.bytesDownloaded / (1024 * 1024)
            val totalMB = state.totalBytes / (1024 * 1024)
            Text(
                text = "$downloadedMB / $totalMB MB (${(state.progress * 100).toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelEntry,
    isSelected: Boolean,
    isDownloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = model.isDownloaded) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = when {
                    model.isDownloaded -> Icons.Default.CheckCircle
                    model is ModelEntry.Custom -> Icons.Default.CloudDownload
                    else -> Icons.Default.CloudQueue
                },
                contentDescription = null,
                tint = when {
                    model.isDownloaded -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Model info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "ACTIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Type badge
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (model is ModelEntry.Custom) "Custom" else "Built-in",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            // Action buttons
            if (!model.isDownloaded && !isDownloading) {
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (model.isDownloaded) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonasTab(
    personas: List<Persona>,
    selectedPersonaId: String,
    onSelectPersona: (String) -> Unit,
    onDeletePersona: (String) -> Unit,
    onAddPersona: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Add persona button
        item {
            OutlinedButton(
                onClick = onAddPersona,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create New Persona")
            }
        }
        
        // Persona list
        items(personas, key = { it.id }) { persona ->
            PersonaCard(
                persona = persona,
                isSelected = persona.id == selectedPersonaId,
                onSelect = { onSelectPersona(persona.id) },
                onDelete = { onDeletePersona(persona.id) }
            )
        }
    }
}

@Composable
private fun PersonaCard(
    persona: Persona,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon
            Text(
                text = persona.iconEmoji,
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Persona info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "ACTIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                
                Text(
                    text = persona.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // System prompt preview
                Spacer(Modifier.height(4.dp))
                Text(
                    text = persona.systemPrompt.take(100) + if (persona.systemPrompt.length > 100) "..." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2
                )
            }
            
            // Delete button (only for non-built-in)
            if (!persona.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onAddModel: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
        title = { Text("Add Model from HuggingFace") },
        text = {
            Column {
                Text(
                    text = "Enter a HuggingFace URL or repo:filename",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        error = null
                    },
                    label = { Text("Model URL") },
                    placeholder = { Text("unsloth/Qwen3-1.7B-GGUF:Qwen3-1.7B-Q4_K_M.gguf") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(8.dp))
                
                Text(
                    text = "Formats:\n• Full URL: https://huggingface.co/repo/resolve/main/file.gguf\n• Short: repo:filename.gguf",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (url.isBlank()) {
                        error = "Please enter a URL"
                    } else if (!url.contains("huggingface") && !url.contains(":")) {
                        error = "Invalid format"
                    } else {
                        onAddModel(url)
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPersonaDialog(
    onDismiss: () -> Unit,
    onAddPersona: (Persona) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🤖") }
    var error by remember { mutableStateOf<String?>(null) }
    
    val emojiOptions = listOf("🤖", "👨‍💻", "🎨", "📚", "🔬", "💼", "🎮", "🌍", "🧙‍♂️", "👽")
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, contentDescription = null) },
        title = { Text("Create New Persona") },
        text = {
            Column {
                // Emoji selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojiOptions.forEach { option ->
                        Surface(
                            modifier = Modifier.clickable { emoji = option },
                            shape = MaterialTheme.shapes.small,
                            color = if (emoji == option) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = option,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it; error = null },
                    label = { Text("System Prompt") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        name.isBlank() -> error = "Name is required"
                        systemPrompt.isBlank() -> error = "System prompt is required"
                        else -> {
                            onAddPersona(
                                Persona(
                                    name = name,
                                    description = description,
                                    systemPrompt = systemPrompt,
                                    iconEmoji = emoji
                                )
                            )
                        }
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
