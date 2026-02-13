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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import com.llamafarm.atmosphere.viewmodel.JniCapability
import com.llamafarm.atmosphere.viewmodel.JniPeer

/**
 * NEW MeshScreen - Uses JNI state directly.
 * No "Saved Meshes", no HTTP polling.
 * Shows real mesh state from AtmosphereNative.* calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreenNew(
    viewModel: AtmosphereViewModel,
    onJoinMeshClick: () -> Unit = {}
) {
    // JNI state - PRIMARY source of truth
    val jniHealth by viewModel.jniHealth.collectAsState()
    val jniPeers by viewModel.jniPeers.collectAsState()
    val jniCapabilities by viewModel.jniCapabilities.collectAsState()
    
    val isRunning = jniHealth?.status == "running"

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
                            !isRunning -> "Atmosphere not running"
                            jniPeers.isEmpty() -> "No peers discovered yet"
                            else -> "${jniPeers.size} peers • ${jniCapabilities.size} capabilities"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Add Peer / Invite buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { /* TODO: Add peer manually */ }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Peer")
                    }
                    IconButton(onClick = { /* TODO: Generate invite QR */ }) {
                        Icon(Icons.Default.QrCode, contentDescription = "Invite")
                    }
                }
            }
        }

        // Connected Peers Section
        item {
            Text(
                text = "Connected Peers (${jniPeers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (jniPeers.isEmpty() && isRunning) {
            // Empty state - searching
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Searching for peers on WiFi...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Make sure devices are on the same network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        } else if (!isRunning) {
            // Atmosphere not running
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Atmosphere is not running",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Peer list
            items(jniPeers, key = { it.peerId }) { peer ->
                PeerCardNew(peer = peer)
            }
        }

        // Capabilities Section
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Capabilities (${jniCapabilities.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (jniCapabilities.isEmpty() && isRunning) {
            item {
                Text(
                    text = "No capabilities discovered yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        } else {
            // Group capabilities by peer
            val capsByPeer = jniCapabilities.groupBy { it.peerId }
            capsByPeer.forEach { (peerId, caps) ->
                item(key = "peer-$peerId") {
                    CapabilityGroupCard(
                        peerName = caps.firstOrNull()?.peerName ?: peerId.take(8),
                        capabilities = caps
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerCardNew(peer: JniPeer) {
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
                color = if (peer.state == "connected") StatusOnline else StatusOffline,
                modifier = Modifier.size(12.dp)
            ) {}
            
            Spacer(Modifier.width(12.dp))

            // Peer info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name ?: peer.peerId.take(8),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = peer.peerId.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    peer.ip?.let { ip ->
                        TransportBadge(text = ip, icon = Icons.Default.Wifi)
                    }
                    TransportBadge(text = peer.transport.uppercase(), icon = null)
                }
            }

            // Latency
            peer.latency?.let { latency ->
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "${latency}ms",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            latency < 50 -> StatusOnline
                            latency < 200 -> Color(0xFFFFA726)
                            else -> Color(0xFFEF5350)
                        }
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

@Composable
private fun TransportBadge(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun CapabilityGroupCard(
    peerName: String,
    capabilities: List<JniCapability>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.tertiary
                ) {
                    Text(
                        text = "${capabilities.size}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiary
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            capabilities.forEach { cap ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cap.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            cap.model?.let { model ->
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            cap.projectPath?.let { path ->
                                Text(
                                    text = "• $path",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
