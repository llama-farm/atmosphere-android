package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.ui.components.StatusCard
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel

/**
 * NEW HomeScreen - Uses JNI state directly (no HTTP, no "daemon" language).
 * Shows what the Rust core is actually doing via AtmosphereNative.* calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenNew(viewModel: AtmosphereViewModel) {
    // JNI state - PRIMARY source of truth
    val jniHealth by viewModel.jniHealth.collectAsState()
    val jniPeers by viewModel.jniPeers.collectAsState()
    val jniCapabilities by viewModel.jniCapabilities.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Atmosphere",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Mesh Computing Platform",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status Card - from JNI health
        val isRunning = jniHealth?.status == "running"
        val statusText = when (jniHealth?.status) {
            "running" -> "Atmosphere Running"
            "starting" -> "Starting..."
            "stopped" -> "Stopped"
            null -> "Initializing..."
            else -> jniHealth?.status ?: "Unknown"
        }
        
        StatusCard(
            title = "Node Status",
            status = statusText,
            statusColor = if (isRunning) StatusOnline else StatusOffline,
            icon = Icons.Default.Hub
        )

        Spacer(modifier = Modifier.height(16.dp))

        // This Device Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "This Device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (jniHealth != null) {
                    DeviceInfoRow("Node Name", jniHealth!!.nodeName)
                    DeviceInfoRow("Peer ID", jniHealth!!.peerId.take(16) + "...")
                    if (jniHealth!!.meshPort > 0) {
                        DeviceInfoRow("Mesh Port", jniHealth!!.meshPort.toString())
                    }
                } else {
                    Text(
                        text = "Waiting for core to initialize...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mesh Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Peers",
                value = jniPeers.size.toString(),
                icon = Icons.Default.People,
                color = if (jniPeers.isNotEmpty()) StatusOnline else MaterialTheme.colorScheme.outline
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Capabilities",
                value = jniCapabilities.size.toString(),
                icon = Icons.Default.Memory,
                color = if (jniCapabilities.isNotEmpty()) StatusOnline else MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transport Status
        if (jniHealth != null && jniHealth!!.transports.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CellTower,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Transports",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransportIndicator("LAN", jniHealth!!.transports["lan"] ?: false)
                        TransportIndicator("BLE", jniHealth!!.transports["ble"] ?: false)
                        TransportIndicator("WiFi Direct", jniHealth!!.transports["wifi_direct"] ?: false)
                        TransportIndicator("BigLlama", jniHealth!!.transports["biglama"] ?: false)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Peer List (if any)
        if (jniPeers.isNotEmpty()) {
            Text(
                text = "Connected Peers (${jniPeers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(Modifier.height(8.dp))
            
            jniPeers.take(5).forEach { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status dot
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (peer.state == "connected") StatusOnline else StatusOffline,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        
                        Spacer(Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.name ?: peer.peerId.take(8),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                peer.ip?.let { ip ->
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "via ${peer.transport.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        peer.latency?.let { latency ->
                            Text(
                                text = "${latency}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            
            if (jniPeers.size > 5) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+ ${jniPeers.size - 5} more peers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        } else if (isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
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
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Searching for peers on WiFi...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Native Library Status
        val nativeStatus = if (AtmosphereApplication.isNativeLoaded()) "Loaded ✓" else "Not Available"
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Rust Core",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = nativeStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransportIndicator(name: String, enabled: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) {
            StatusOnline.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (enabled) StatusOnline else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
