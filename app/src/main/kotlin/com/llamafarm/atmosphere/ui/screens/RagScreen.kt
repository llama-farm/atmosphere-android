package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.llamafarm.LlamaFarmLite
import com.llamafarm.atmosphere.rag.LocalRagStore
import kotlinx.coroutines.launch

/**
 * RAG (Retrieval-Augmented Generation) Screen
 * 
 * Features:
 * - List all RAG indices
 * - Create new index
 * - Add documents (text, file, URL)
 * - Query indices
 * - Per-app isolation via namespaces
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagScreen(
    llamaFarmLite: LlamaFarmLite,
    appId: String = "com.llamafarm.atmosphere"  // Current app's package name
) {
    val scope = rememberCoroutineScope()
    
    // State
    var indices by remember { mutableStateOf<List<LocalRagStore.RagIndex>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf<LocalRagStore.RagIndex?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddDocDialog by remember { mutableStateOf(false) }
    var showQueryDialog by remember { mutableStateOf(false) }
    var queryResults by remember { mutableStateOf<List<LocalRagStore.QueryResult>>(emptyList()) }
    var isQuerying by remember { mutableStateOf(false) }
    
    // Load indices on launch
    LaunchedEffect(Unit) {
        indices = llamaFarmLite.listRagIndexes().filter { 
            it.id.startsWith("$appId:")  // Filter by app namespace
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG Indices") },
                actions = {
                    IconButton(onClick = { 
                        indices = llamaFarmLite.listRagIndexes().filter {
                            it.id.startsWith("$appId:")
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create Index")
            }
        }
    ) { padding ->
        if (selectedIndex == null) {
            // Index list view
            IndicesListView(
                indices = indices,
                onSelectIndex = { selectedIndex = it },
                onDeleteIndex = { index ->
                    scope.launch {
                        llamaFarmLite.deleteRagIndex(index.id)
                        indices = llamaFarmLite.listRagIndexes().filter {
                            it.id.startsWith("$appId:")
                        }
                    }
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            // Index detail view
            IndexDetailView(
                index = selectedIndex!!,
                queryResults = queryResults,
                isQuerying = isQuerying,
                onBack = { selectedIndex = null },
                onAddDocument = { showAddDocDialog = true },
                onQuery = { showQueryDialog = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
    
    // Create Index Dialog
    if (showCreateDialog) {
        CreateIndexDialog(
            appId = appId,
            onDismiss = { showCreateDialog = false },
            onCreateIndex = { indexName, documents ->
                scope.launch {
                    val fullIndexId = "$appId:$indexName"
                    llamaFarmLite.createRagIndex(fullIndexId, documents)
                    indices = llamaFarmLite.listRagIndexes().filter {
                        it.id.startsWith("$appId:")
                    }
                    showCreateDialog = false
                }
            }
        )
    }
    
    // Add Document Dialog
    if (showAddDocDialog && selectedIndex != null) {
        AddDocumentDialog(
            indexId = selectedIndex!!.id,
            onDismiss = { showAddDocDialog = false },
            onAddDocument = { docId, content, metadata ->
                scope.launch {
                    // TODO: Add addDocument API to LlamaFarmLite
                    // For now, we'd need to recreate the index with the new document
                    showAddDocDialog = false
                }
            }
        )
    }
    
    // Query Dialog
    if (showQueryDialog && selectedIndex != null) {
        QueryDialog(
            indexId = selectedIndex!!.id,
            onDismiss = { showQueryDialog = false },
            onQuery = { query, topK ->
                scope.launch {
                    isQuerying = true
                    queryResults = llamaFarmLite.queryRag(selectedIndex!!.id, query, topK)
                    isQuerying = false
                    showQueryDialog = false
                }
            }
        )
    }
}

@Composable
private fun IndicesListView(
    indices: List<LocalRagStore.RagIndex>,
    onSelectIndex: (LocalRagStore.RagIndex) -> Unit,
    onDeleteIndex: (LocalRagStore.RagIndex) -> Unit,
    modifier: Modifier = Modifier
) {
    if (indices.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Text(
                    "No RAG indices yet",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Create an index to start using RAG",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(indices) { index ->
                IndexCard(
                    index = index,
                    onClick = { onSelectIndex(index) },
                    onDelete = { onDeleteIndex(index) }
                )
            }
        }
    }
}

@Composable
private fun IndexCard(
    index: LocalRagStore.RagIndex,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = index.id.substringAfter(":"),  // Remove app namespace prefix
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "${index.documents.size} documents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Created ${formatTimestamp(index.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
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

@Composable
private fun IndexDetailView(
    index: LocalRagStore.RagIndex,
    queryResults: List<LocalRagStore.QueryResult>,
    isQuerying: Boolean,
    onBack: () -> Unit,
    onAddDocument: () -> Unit,
    onQuery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = index.id.substringAfter(":"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${index.documents.size} documents",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Divider()
        
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onAddDocument,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add Document")
            }
            
            Button(
                onClick = onQuery,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Query")
            }
        }
        
        // Query results or document list
        if (queryResults.isNotEmpty() || isQuerying) {
            Text(
                "Query Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            if (isQuerying) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queryResults) { result ->
                        QueryResultCard(result)
                    }
                }
            }
        } else {
            Text(
                "Documents",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(index.documents) { doc ->
                    DocumentCard(doc)
                }
            }
        }
    }
}

@Composable
private fun QueryResultCard(result: LocalRagStore.QueryResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = result.document.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = String.format("%.2f", result.score),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Text(
                text = result.document.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DocumentCard(doc: LocalRagStore.Document) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = doc.id,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = doc.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateIndexDialog(
    appId: String,
    onDismiss: () -> Unit,
    onCreateIndex: (String, List<Pair<String, String>>) -> Unit
) {
    var indexName by remember { mutableStateOf("") }
    var documentText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        title = { Text("Create RAG Index") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = indexName,
                    onValueChange = { 
                        indexName = it
                        error = null
                    },
                    label = { Text("Index Name") },
                    placeholder = { Text("my_index") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = documentText,
                    onValueChange = { documentText = it },
                    label = { Text("Initial Document (optional)") },
                    placeholder = { Text("Paste text here...") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (indexName.isBlank()) {
                        error = "Index name is required"
                    } else {
                        val docs = if (documentText.isNotBlank()) {
                            listOf("doc1" to documentText)
                        } else {
                            emptyList()
                        }
                        onCreateIndex(indexName, docs)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDocumentDialog(
    indexId: String,
    onDismiss: () -> Unit,
    onAddDocument: (String, String, String?) -> Unit
) {
    var docId by remember { mutableStateOf("doc_${System.currentTimeMillis()}") }
    var content by remember { mutableStateOf("") }
    var metadata by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        title = { Text("Add Document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = docId,
                    onValueChange = { docId = it },
                    label = { Text("Document ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = content,
                    onValueChange = { 
                        content = it
                        error = null
                    },
                    label = { Text("Content") },
                    placeholder = { Text("Paste text, URL, or file content...") },
                    minLines = 4,
                    maxLines = 8,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = metadata,
                    onValueChange = { metadata = it },
                    label = { Text("Metadata (JSON, optional)") },
                    placeholder = { Text("{\"source\": \"web\"}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isBlank()) {
                        error = "Content is required"
                    } else {
                        onAddDocument(docId, content, metadata.ifBlank { null })
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
private fun QueryDialog(
    indexId: String,
    onDismiss: () -> Unit,
    onQuery: (String, Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var topK by remember { mutableStateOf("3") }
    var error by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Search, contentDescription = null) },
        title = { Text("Query Index") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { 
                        query = it
                        error = null
                    },
                    label = { Text("Query") },
                    placeholder = { Text("What are you looking for?") },
                    minLines = 2,
                    maxLines = 4,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Top K Results") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isBlank()) {
                        error = "Query is required"
                    } else {
                        val k = topK.toIntOrNull() ?: 3
                        onQuery(query, k)
                    }
                }
            ) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}
