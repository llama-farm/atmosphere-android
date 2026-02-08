package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamafarm.atmosphere.bindings.MeshPeer
import com.llamafarm.atmosphere.data.SavedMesh
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.network.RelayPeer
import com.llamafarm.atmosphere.network.TransportStatus
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline
import com.llamafarm.atmosphere.ui.theme.StatusConnecting
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreen(
    viewModel: AtmosphereViewModel = viewModel(),
    onJoinMeshClick: () -> Unit = {}
) {
    // Observe state from ViewModel
    val nodeState by viewModel.nodeState.collectAsState()
    val nativePeers by viewModel.peers.collectAsState()
    val relayPeers by viewModel.relayPeers.collectAsState()
    val isConnectedToMesh by viewModel.isConnectedToMesh.collectAsState()
    val meshName by viewModel.meshName.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // NEW: Saved meshes
    val savedMeshes by viewModel.savedMeshes.collectAsState()
    val currentMeshId by viewModel.currentMeshId.collectAsState()
    val connectionState by viewModel.relayConnectionState.collectAsState()
    val transportStatuses by viewModel.transportStatuses.collectAsState()
    val activeTransportType by viewModel.activeTransportType.collectAsState()
    
    // Combine native and relay peers for display
    val allPeers = remember(nativePeers, relayPeers) {
        nativePeers + relayPeers.map { relayPeer ->
            MeshPeer(
                nodeId = relayPeer.nodeId,
                name = relayPeer.name,
                address = "relay",
                connected = relayPeer.connected,
                latencyMs = null,
                capabilities = relayPeer.capabilities
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Mesh Network",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            isConnectedToMesh -> "${allPeers.size} peers • ${meshName ?: "Connected"}"
                            nodeState.isRunning -> "Node running, not connected to mesh"
                            else -> "Node offline"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                FilledTonalButton(
                    onClick = {
                        if (isConnectedToMesh) {
                            isScanning = true
                            viewModel.discoverPeers()
                            scope.launch {
                                delay(3000)
                                isScanning = false
                            }
                        } else {
                            onJoinMeshClick()
                        }
                    }
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (isConnectedToMesh) Icons.Default.Refresh else Icons.Default.Add, 
                            contentDescription = null
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnectedToMesh) {
                            if (isScanning) "Scanning..." else "Scan"
                        } else {
                            "Join Mesh"
                        }
                    )
                }
            }
        }

        // Connection Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isConnectedToMesh -> MaterialTheme.colorScheme.primaryContainer
                        nodeState.isRunning -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            isConnectedToMesh -> StatusOnline
                            nodeState.isRunning -> StatusConnecting
                            else -> StatusOffline
                        },
                        modifier = Modifier.size(12.dp)
                    ) {}
                    
                    Spacer(Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isConnectedToMesh -> "Connected to Mesh"
                                nodeState.isRunning -> "Node Running"
                                else -> "Offline"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (meshName != null && isConnectedToMesh) {
                            Text(
                                text = meshName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Show active transport
                        if (isConnectedToMesh && activeTransportType != null) {
                            Text(
                                text = "via ${activeTransportType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    if (isConnectedToMesh) {
                        TextButton(onClick = { viewModel.disconnectMesh(clearSaved = false) }) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        // Transport Status (when connected)
        if (isConnectedToMesh && transportStatuses.isNotEmpty()) {
            item {
                TransportStatusCard(
                    statuses = transportStatuses,
                    activeType = activeTransportType
                )
            }
        }
        
        // 📦 MY MESHES SECTION (NEW)
        if (savedMeshes.isNotEmpty()) {
            item {
                Text(
                    text = "My Meshes (${savedMeshes.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            items(savedMeshes, key = { it.meshId }) { mesh ->
                SavedMeshCard(
                    mesh = mesh,
                    isConnected = currentMeshId == mesh.meshId && isConnectedToMesh,
                    isConnecting = currentMeshId == mesh.meshId && connectionState == ConnectionState.CONNECTING,
                    onReconnect = { viewModel.reconnectToMesh(mesh.meshId) },
                    onForget = { viewModel.removeSavedMesh(mesh.meshId) },
                    onToggleAutoReconnect = { enabled ->
                        viewModel.setMeshAutoReconnect(mesh.meshId, enabled)
                    }
                )
            }
        }

        // Peer section
        if (allPeers.isEmpty() && savedMeshes.isEmpty()) {
            // Empty state
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (isConnectedToMesh) "No peers found" else "Not connected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isConnectedToMesh) 
                                "Waiting for peers to join the mesh..." 
                            else 
                                "Scan a QR code to join a mesh",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        
                        if (!isConnectedToMesh) {
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = onJoinMeshClick) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Join Mesh")
                            }
                        }
                    }
                }
            }
        } else if (allPeers.isNotEmpty()) {
            // Peer list
            item {
                Text(
                    text = "Connected Peers (${allPeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            items(allPeers, key = { it.nodeId }) { peer ->
                PeerCard(peer = peer)
            }
        }
    }
}

/**
 * Card showing transport status indicators.
 */
@Composable
private fun TransportStatusCard(
    statuses: Map<String, TransportStatus>,
    activeType: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CellTower,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Transport Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statuses.forEach { (type, status) ->
                    TransportChip(
                        type = type,
                        status = status,
                        isActive = type == activeType
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportChip(
    type: String,
    status: TransportStatus,
    isActive: Boolean
) {
    val (icon, label) = when (type) {
        "ble" -> Icons.Default.Bluetooth to "BLE"
        "lan", "local" -> Icons.Default.Wifi to "LAN"
        "relay" -> Icons.Default.Cloud to "Relay"
        "public" -> Icons.Default.Public to "Public"
        else -> Icons.Default.QuestionMark to type
    }
    
    val color = when (status.state) {
        TransportStatus.TransportState.CONNECTED -> Color(0xFF4CAF50)
        TransportStatus.TransportState.AVAILABLE -> Color(0xFF8BC34A)
        TransportStatus.TransportState.PROBING -> Color(0xFFFFC107)
        TransportStatus.TransportState.FAILED -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.outline
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isActive) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        border = if (isActive) null else ButtonDefaults.outlinedButtonBorder
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = color,
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(Modifier.width(4.dp))
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isActive) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
            status.latencyMs?.let { latency ->
                Text(
                    text = " ${latency}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * Card for a saved mesh in "My Meshes" section.
 */
@Composable
private fun SavedMeshCard(
    mesh: SavedMesh,
    isConnected: Boolean,
    isConnecting: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                isConnecting -> Color(0xFFFFC107).copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        isConnected -> StatusOnline
                        isConnecting -> StatusConnecting
                        else -> StatusOffline
                    },
                    modifier = Modifier.size(12.dp)
                ) {}
                
                Spacer(Modifier.width(12.dp))
                
                // Mesh info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mesh.meshName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (mesh.founderName.isNotEmpty() && mesh.founderName != "Unknown") {
                        Text(
                            text = "Founded by ${mesh.founderName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Joined ${dateFormat.format(Date(mesh.joinedAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                // Status badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        isConnected -> Color(0xFF4CAF50)
                        isConnecting -> Color(0xFFFFC107)
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    Text(
                        text = when {
                            isConnected -> "🟢"
                            isConnecting -> "🔄"
                            else -> "🔴"
                        },
                        modifier = Modifier.padding(4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            // Endpoint chips
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                mesh.endpoints.forEach { endpoint ->
                    AssistChip(
                        onClick = {},
                        label = { 
                            Text(
                                endpoint.type.uppercase(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp),
                        leadingIcon = {
                            val icon = when (endpoint.type) {
                                "ble" -> Icons.Default.Bluetooth
                                "lan", "local" -> Icons.Default.Wifi
                                "relay" -> Icons.Default.Cloud
                                else -> Icons.Default.Link
                            }
                            Icon(icon, null, modifier = Modifier.size(14.dp))
                        }
                    )
                }
            }
            
            // Actions
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto-reconnect toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Auto-reconnect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = mesh.autoReconnect,
                        onCheckedChange = onToggleAutoReconnect,
                        modifier = Modifier.height(20.dp)
                    )
                }
                
                // Forget button
                IconButton(
                    onClick = onForget,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Forget",
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                // Reconnect button
                if (!isConnected) {
                    Button(
                        onClick = onReconnect,
                        enabled = !isConnecting,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isConnecting) "..." else "Connect",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: MeshPeer) {
    val isRelayPeer = peer.address == "relay"
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (peer.connected) StatusOnline else StatusOffline,
                modifier = Modifier.size(8.dp)
            ) {}
            
            Spacer(Modifier.width(12.dp))

            // Peer info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isRelayPeer) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "via relay",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = peer.nodeId.take(16) + if (peer.nodeId.length > 16) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isRelayPeer && peer.address.isNotEmpty()) {
                    Text(
                        text = peer.address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (peer.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        peer.capabilities.take(4).forEach { cap ->
                            AssistChip(
                                onClick = {},
                                label = { Text(cap, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        if (peer.capabilities.size > 4) {
                            AssistChip(
                                onClick = {},
                                label = { Text("+${peer.capabilities.size - 4}", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }

            // Latency or connection type
            Column(
                horizontalAlignment = Alignment.End
            ) {
                peer.latencyMs?.let { latency ->
                    Text(
                        text = "${latency}ms",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "latency",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } ?: run {
                    Icon(
                        if (isRelayPeer) Icons.Default.Cloud else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
