package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel
import java.util.UUID

@Composable
fun ModelsScreenNew(viewModel: MeshDebugViewModel) {
    val capabilities by viewModel.capabilities.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val health by viewModel.health.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Filter capabilities to only show models
    val modelCaps = capabilities.filter { cap ->
        cap.type.contains("llm", ignoreCase = true) || 
        cap.type.contains("vision", ignoreCase = true) ||
        cap.type.contains("model", ignoreCase = true) ||
        cap.model != null
    }
    
    // Separate local vs mesh based on peer_id
    val localPeerId = health?.peerId ?: ""
    val localModels = modelCaps.filter { it.nodeId == localPeerId }
    val meshModels = modelCaps.filter { it.nodeId != localPeerId }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tab selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton("On Device (${localModels.size})", selectedTab == 0) { selectedTab = 0 }
                TabButton("Mesh (${meshModels.size})", selectedTab == 1) { selectedTab = 1 }
                TabButton("Transfers (${transfers.size})", selectedTab == 2) { selectedTab = 2 }
            }
        }
        
        when (selectedTab) {
            0 -> {
                // Local models
                item {
                    Text(
                        "Models available on this device",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                
                if (localModels.isEmpty()) {
                    item { EmptyState("No local models found") }
                } else {
                    items(localModels, key = { it.id }) { model ->
                        LocalModelCard(model, viewModel)
                    }
                }
            }
            1 -> {
                // Mesh models
                item {
                    Text(
                        "Models discovered from ${meshModels.map { it.nodeId }.distinct().size} peers",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                
                if (meshModels.isEmpty()) {
                    item { EmptyState("No mesh models discovered yet") }
                } else {
                    items(meshModels, key = { it.id }) { model ->
                        MeshModelCard(model, viewModel)
                    }
                }
            }
            2 -> {
                // Active transfers
                item {
                    Text(
                        "Model transfers in progress",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                
                if (transfers.isEmpty()) {
                    item { EmptyState("No active transfers") }
                } else {
                    items(transfers, key = { it.id }) { transfer ->
                        TransferCard(transfer, viewModel)
                    }
                }
            }
        }
        
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) AccentBlueDim else CardBackground,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) AccentBlue else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun LocalModelCard(
    model: com.llamafarm.atmosphere.network.MeshCapabilityInfo,
    viewModel: MeshDebugViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Model name + status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = StatusGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    model.model ?: model.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            GreenBadge("LOCAL")
        }
        
        Spacer(Modifier.height(8.dp))
        
        // Details
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            model.tier?.let { BlueBadge(it) }
            model.paramsB?.let { GrayBadge("${it}B") }
            GrayBadge(model.type)
        }
        
        Spacer(Modifier.height(8.dp))
        
        // Capabilities
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureBadge("Vision", model.hasVision)
            FeatureBadge("Tools", model.hasTools)
            FeatureBadge("RAG", model.hasRag)
        }
        
        // Status
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model.available) {
                StatusDot("connected")
                Spacer(Modifier.width(6.dp))
                Text("Available", color = StatusGreen, fontSize = 11.sp)
            } else {
                StatusDot("offline")
                Spacer(Modifier.width(6.dp))
                Text("Unavailable", color = StatusGray, fontSize = 11.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                "Load: ${(model.load * 100).toInt()}%",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            model.avgInferenceMs?.let {
                Spacer(Modifier.width(8.dp))
                Text("~${it.toInt()}ms", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun MeshModelCard(
    model: com.llamafarm.atmosphere.network.MeshCapabilityInfo,
    viewModel: MeshDebugViewModel
) {
    var showDownloadDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Model name + peer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.model ?: model.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "from ${model.nodeName}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            
            // Download button
            IconButton(
                onClick = { showDownloadDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    tint = AccentBlue
                )
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        // Details
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            model.tier?.let { PurpleBadge(it) }
            model.paramsB?.let { GrayBadge("${it}B") }
            GrayBadge(model.type)
        }
        
        Spacer(Modifier.height(8.dp))
        
        // Capabilities
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureBadge("Vision", model.hasVision)
            FeatureBadge("Tools", model.hasTools)
            FeatureBadge("RAG", model.hasRag)
        }
        
        // Peer ID (small, monospace)
        Spacer(Modifier.height(6.dp))
        Text(
            model.nodeId.take(16) + "...",
            color = TextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
    
    // Download confirmation dialog
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Download Model?") },
            text = {
                Column {
                    Text("Model: ${model.model ?: model.name}")
                    Text("From: ${model.nodeName}", fontSize = 13.sp, color = TextMuted)
                    Text("Type: ${model.type}", fontSize = 13.sp, color = TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This will initiate a transfer from ${model.nodeName} to this device via the mesh CRDT.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.initiateTransfer(
                            modelId = model.model ?: model.id,
                            fromPeer = model.nodeId,
                            modelName = model.name
                        )
                        showDownloadDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonGreen)
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TransferCard(
    transfer: com.llamafarm.atmosphere.network.TransferInfo,
    viewModel: MeshDebugViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Transfer header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (transfer.status) {
                        "active", "transferring" -> Icons.Default.Sync
                        "completed" -> Icons.Default.CheckCircle
                        "failed", "error" -> Icons.Default.Error
                        else -> Icons.Default.Schedule
                    },
                    contentDescription = null,
                    tint = when (transfer.status) {
                        "active", "transferring" -> AccentBlue
                        "completed" -> StatusGreen
                        "failed", "error" -> StatusRed
                        else -> StatusYellow
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    transfer.modelName ?: transfer.modelId,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            
            // Status badge
            when (transfer.status) {
                "pending" -> GrayBadge("PENDING")
                "active", "transferring" -> BlueBadge("ACTIVE")
                "completed" -> GreenBadge("DONE")
                "failed", "error" -> RedBadge("FAILED")
                else -> GrayBadge(transfer.status.uppercase())
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        // From/To
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text("From:", color = TextMuted, fontSize = 10.sp)
                Text(
                    transfer.fromPeerName ?: transfer.fromPeer.take(12),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column {
                Text("To:", color = TextMuted, fontSize = 10.sp)
                Text(
                    transfer.toPeerName ?: transfer.toPeer.take(12),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        
        // Progress bar (if active)
        if (transfer.status == "active" || transfer.status == "transferring") {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = AccentBlue,
                trackColor = BorderColor,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${(transfer.progress * 100).toInt()}%",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                transfer.bytesTransferred?.let { bytes ->
                    val mb = bytes / (1024 * 1024)
                    Text(
                        "${mb}MB",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        
        // Cancel button (if pending/active)
        if (transfer.status == "pending" || transfer.status == "active" || transfer.status == "transferring") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.cancelTransfer(transfer.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = StatusRed
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FeatureBadge(label: String, enabled: Boolean) {
    Text(
        "$label: ${if (enabled) "✓" else "—"}",
        color = if (enabled) StatusGreen else TextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}
