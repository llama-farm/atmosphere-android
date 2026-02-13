package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * LogScreen - Live event log from mesh
 * Shows mesh events with filtering by type
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: AtmosphereViewModel
) {
    val meshEvents by viewModel.meshEvents.collectAsState()
    var filterType by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new events arrive
    LaunchedEffect(meshEvents.size) {
        if (meshEvents.isNotEmpty()) {
            listState.animateScrollToItem(meshEvents.size - 1)
        }
    }
    
    // Filter events by type
    val filteredEvents = remember(meshEvents, filterType) {
        if (filterType == null) {
            meshEvents
        } else {
            meshEvents.filter { it.type == filterType }
        }
    }
    
    // Get unique event types for filters
    val eventTypes = remember(meshEvents) {
        meshEvents.map { it.type }.distinct().sorted()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Mesh Events",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${filteredEvents.size} events" + if (filterType != null) " (filtered)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Filter chips
        if (eventTypes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // All filter
                FilterChip(
                    selected = filterType == null,
                    onClick = { filterType = null },
                    label = { Text("All") }
                )
                
                // Event type filters
                eventTypes.take(6).forEach { type ->
                    FilterChip(
                        selected = filterType == type,
                        onClick = { filterType = if (filterType == type) null else type },
                        label = { Text(type.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }
        
        // Event list
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = "No events yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Connect to mesh to see activity",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredEvents, key = { "${it.timestamp}-${it.title}" }) { event ->
                        LogEventItem(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEventItem(event: AtmosphereViewModel.MeshEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(event.timestamp))
    
    val (icon, color) = when (event.type) {
        "connected" -> Icons.Default.Link to Color(0xFF4CAF50)
        "joined" -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        "peer_joined", "peer" -> Icons.Default.PersonAdd to Color(0xFF2196F3)
        "peer_left" -> Icons.Default.PersonRemove to Color(0xFFFF9800)
        "cost" -> Icons.Default.AttachMoney to Color(0xFF9C27B0)
        "chat" -> Icons.Default.Chat to Color(0xFF00BCD4)
        "route" -> Icons.Default.AltRoute to Color(0xFF3F51B5)
        "error" -> Icons.Default.Error to Color(0xFFEF5350)
        "gossip", "capability" -> Icons.Default.Campaign to Color(0xFFFF5722)
        "lan" -> Icons.Default.Wifi to Color(0xFF009688)
        "inference" -> Icons.Default.Psychology to Color(0xFF673AB7)
        "llm" -> Icons.Default.SmartToy to Color(0xFF00BCD4)
        else -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        
        // Icon
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        
        Spacer(Modifier.width(8.dp))
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            event.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        
        // Node ID badge
        event.nodeId?.let { nodeId ->
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = color.copy(alpha = 0.1f)
            ) {
                Text(
                    text = nodeId.take(6),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}
