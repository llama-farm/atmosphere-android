package com.llamafarm.atmosphere.ui.screens

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
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline

/**
 * Represents a connected peer in the mesh network.
 */
data class MeshPeer(
    val nodeId: String,
    val displayName: String,
    val address: String,
    val isConnected: Boolean,
    val latencyMs: Int?,
    val capabilities: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreen() {
    // TODO: Connect to actual mesh state via ViewModel
    var peers by remember { mutableStateOf<List<MeshPeer>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
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
                    text = "${peers.size} peers discovered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            FilledTonalButton(
                onClick = {
                    isScanning = !isScanning
                    // TODO: Trigger peer discovery
                }
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isScanning) "Scanning..." else "Scan")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (peers.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                        text = "No peers found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Start the service and scan for nearby nodes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            // Peer list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(peers) { peer ->
                    PeerCard(peer = peer)
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: MeshPeer) {
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
                color = if (peer.isConnected) StatusOnline else StatusOffline,
                modifier = Modifier.size(8.dp)
            ) {}
            
            Spacer(Modifier.width(12.dp))

            // Peer info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = peer.nodeId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (peer.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        peer.capabilities.take(3).forEach { cap ->
                            AssistChip(
                                onClick = {},
                                label = { Text(cap, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }

            // Latency
            peer.latencyMs?.let { latency ->
                Column(
                    horizontalAlignment = Alignment.End
                ) {
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
                }
            }
        }
    }
}
