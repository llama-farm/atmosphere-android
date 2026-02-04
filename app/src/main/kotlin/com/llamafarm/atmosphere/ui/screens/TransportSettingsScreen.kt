package com.llamafarm.atmosphere.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import com.llamafarm.atmosphere.data.AtmospherePreferences
import com.llamafarm.atmosphere.network.TransportType
import kotlinx.coroutines.launch

/**
 * Status of a transport connection.
 */
enum class TransportStatus {
    CONNECTED,
    DISCONNECTED,
    UNAVAILABLE,
    CONNECTING
}

/**
 * Data class representing a transport with its configuration and state.
 */
data class TransportInfo(
    val type: TransportType,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val status: TransportStatus = TransportStatus.DISCONNECTED,
    val latencyMs: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AtmospherePreferences(context) }
    
    // Collect transport states
    val lanEnabled by preferences.transportLanEnabled.collectAsState(initial = true)
    val wifiDirectEnabled by preferences.transportWifiDirectEnabled.collectAsState(initial = true)
    val bleMeshEnabled by preferences.transportBleMeshEnabled.collectAsState(initial = true)
    val matterEnabled by preferences.transportMatterEnabled.collectAsState(initial = true)
    val relayEnabled by preferences.transportRelayEnabled.collectAsState(initial = true)
    val preferLocalOnly by preferences.transportPreferLocalOnly.collectAsState(initial = false)
    
    // Check device capabilities
    val hasBluetooth = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    val hasWifiDirect = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }
    val bluetoothEnabled = remember {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
    }
    
    // Build transport info list with current states
    val transports = remember(lanEnabled, wifiDirectEnabled, bleMeshEnabled, matterEnabled, relayEnabled, hasBluetooth, hasWifiDirect, bluetoothEnabled) {
        listOf(
            TransportInfo(
                type = TransportType.LAN,
                displayName = "LAN WebSocket",
                description = "Local network connection via WiFi router",
                icon = Icons.Default.Wifi,
                enabled = lanEnabled,
                status = if (lanEnabled) TransportStatus.DISCONNECTED else TransportStatus.UNAVAILABLE,
                latencyMs = null
            ),
            TransportInfo(
                type = TransportType.WIFI_DIRECT,
                displayName = "WiFi Direct",
                description = "Peer-to-peer WiFi without router",
                icon = Icons.Default.WifiTethering,
                enabled = wifiDirectEnabled,
                status = when {
                    !hasWifiDirect -> TransportStatus.UNAVAILABLE
                    !wifiDirectEnabled -> TransportStatus.UNAVAILABLE
                    else -> TransportStatus.DISCONNECTED
                },
                latencyMs = null
            ),
            TransportInfo(
                type = TransportType.BLE_MESH,
                displayName = "BLE Mesh",
                description = "Bluetooth Low Energy mesh network",
                icon = Icons.Default.Bluetooth,
                enabled = bleMeshEnabled,
                status = when {
                    !hasBluetooth -> TransportStatus.UNAVAILABLE
                    !bluetoothEnabled -> TransportStatus.DISCONNECTED // BT off but available
                    !bleMeshEnabled -> TransportStatus.UNAVAILABLE
                    else -> TransportStatus.DISCONNECTED
                },
                latencyMs = null
            ),
            TransportInfo(
                type = TransportType.MATTER,
                displayName = "Matter",
                description = "Smart home device protocol",
                icon = Icons.Default.Home,
                enabled = matterEnabled,
                // Matter requires Google Home services - check basic availability
                status = if (matterEnabled) TransportStatus.DISCONNECTED else TransportStatus.UNAVAILABLE,
                latencyMs = null
            ),
            TransportInfo(
                type = TransportType.RELAY,
                displayName = "Relay Server",
                description = "Cloud fallback for NAT traversal",
                icon = Icons.Default.Cloud,
                enabled = relayEnabled && !preferLocalOnly,
                status = if (relayEnabled && !preferLocalOnly) TransportStatus.DISCONNECTED else TransportStatus.UNAVAILABLE,
                latencyMs = null
            )
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transport Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            
            // Info card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Configure how your node connects to the mesh. Multiple transports can be active simultaneously for redundancy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            // Prefer local only toggle
            item {
                Text(
                    text = "Connection Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            item {
                Card {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    scope.launch { 
                                        preferences.setTransportPreferLocalOnly(!preferLocalOnly) 
                                    }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prefer Local Only",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (preferLocalOnly) 
                                        "Only using local network connections" 
                                    else 
                                        "Using all available transports",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preferLocalOnly,
                                onCheckedChange = { enabled ->
                                    scope.launch { preferences.setTransportPreferLocalOnly(enabled) }
                                }
                            )
                        }
                        
                        AnimatedVisibility(visible = preferLocalOnly) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Cloud relay is disabled. You may not be reachable outside your local network.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
            
            // Transport list header
            item {
                Text(
                    text = "Available Transports",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            // Transport cards
            transports.forEach { transport ->
                item(key = transport.type.name) {
                    TransportCard(
                        transport = transport,
                        onToggle = { enabled ->
                            scope.launch {
                                when (transport.type) {
                                    TransportType.LAN -> preferences.setTransportLanEnabled(enabled)
                                    TransportType.WIFI_DIRECT -> preferences.setTransportWifiDirectEnabled(enabled)
                                    TransportType.BLE_MESH -> preferences.setTransportBleMeshEnabled(enabled)
                                    TransportType.MATTER -> preferences.setTransportMatterEnabled(enabled)
                                    TransportType.RELAY -> preferences.setTransportRelayEnabled(enabled)
                                }
                            }
                        },
                        isDisabledByLocalOnly = transport.type == TransportType.RELAY && preferLocalOnly
                    )
                }
            }
            
            // Status legend
            item {
                Text(
                    text = "Status Legend",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusLegendItem(
                            color = Color(0xFF4CAF50),
                            label = "Connected",
                            description = "Transport is active and working"
                        )
                        StatusLegendItem(
                            color = Color(0xFFFFC107),
                            label = "Connecting",
                            description = "Establishing connection..."
                        )
                        StatusLegendItem(
                            color = Color(0xFF9E9E9E),
                            label = "Disconnected",
                            description = "Enabled but not currently connected"
                        )
                        StatusLegendItem(
                            color = Color(0xFFE57373),
                            label = "Unavailable",
                            description = "Disabled or not supported on this device"
                        )
                    }
                }
            }
            
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TransportCard(
    transport: TransportInfo,
    onToggle: (Boolean) -> Unit,
    isDisabledByLocalOnly: Boolean = false
) {
    val effectiveEnabled = transport.enabled && !isDisabledByLocalOnly
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isDisabledByLocalOnly) { onToggle(!transport.enabled) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !effectiveEnabled -> MaterialTheme.colorScheme.surfaceVariant
                                transport.status == TransportStatus.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                transport.status == TransportStatus.CONNECTING -> Color(0xFFFFC107).copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        transport.icon,
                        contentDescription = null,
                        tint = when {
                            !effectiveEnabled -> MaterialTheme.colorScheme.outline
                            transport.status == TransportStatus.CONNECTED -> Color(0xFF4CAF50)
                            transport.status == TransportStatus.CONNECTING -> Color(0xFFFFC107)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = transport.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (effectiveEnabled) 
                                MaterialTheme.colorScheme.onSurface 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(
                            status = if (!effectiveEnabled) TransportStatus.UNAVAILABLE else transport.status
                        )
                    }
                    
                    Spacer(Modifier.height(2.dp))
                    
                    Text(
                        text = transport.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Show latency if connected
                    transport.latencyMs?.let { latency ->
                        Spacer(Modifier.height(4.dp))
                        LatencyIndicator(latencyMs = latency)
                    }
                }
                
                Switch(
                    checked = transport.enabled,
                    onCheckedChange = onToggle,
                    enabled = !isDisabledByLocalOnly
                )
            }
            
            // Show warning if disabled by local only
            AnimatedVisibility(visible = isDisabledByLocalOnly) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Disabled by \"Prefer Local Only\" setting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TransportStatus) {
    val (color, text) = when (status) {
        TransportStatus.CONNECTED -> Color(0xFF4CAF50) to "Connected"
        TransportStatus.CONNECTING -> Color(0xFFFFC107) to "Connecting"
        TransportStatus.DISCONNECTED -> Color(0xFF9E9E9E) to "Disconnected"
        TransportStatus.UNAVAILABLE -> Color(0xFFE57373) to "Unavailable"
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LatencyIndicator(latencyMs: Int) {
    val color = when {
        latencyMs < 50 -> Color(0xFF4CAF50)
        latencyMs < 150 -> Color(0xFFFFC107)
        else -> Color(0xFFE57373)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${latencyMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusLegendItem(
    color: Color,
    label: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = "— $description",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
