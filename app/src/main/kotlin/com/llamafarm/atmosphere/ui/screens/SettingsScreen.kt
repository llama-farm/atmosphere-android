package com.llamafarm.atmosphere.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.BuildConfig
import com.llamafarm.atmosphere.data.AtmospherePreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToTransportSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AtmospherePreferences(context) }
    
    // Collect persisted states
    val nodeName by preferences.nodeName.collectAsState(initial = "My Android Node")
    val nodeId by preferences.nodeId.collectAsState(initial = null)
    val autoStart by preferences.autoStartOnBoot.collectAsState(initial = false)
    val autoReconnect by preferences.autoReconnectMesh.collectAsState(initial = false)
    val lastMeshName by preferences.lastMeshName.collectAsState(initial = null)
    
    var showNodeIdDialog by remember { mutableStateOf(false) }
    var editingNodeName by remember { mutableStateOf(false) }
    var tempNodeName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Node Configuration Section
        item {
            Text(
                text = "Node Configuration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                if (editingNodeName) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Badge,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(16.dp))
                        OutlinedTextField(
                            value = tempNodeName,
                            onValueChange = { tempNodeName = it },
                            label = { Text("Node Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            scope.launch {
                                preferences.setNodeName(tempNodeName)
                            }
                            editingNodeName = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                        IconButton(onClick = { editingNodeName = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                } else {
                    SettingsItem(
                        title = "Node Name",
                        subtitle = nodeName,
                        icon = Icons.Default.Badge,
                        onClick = {
                            tempNodeName = nodeName
                            editingNodeName = true
                        }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Node ID",
                    subtitle = nodeId?.take(24)?.let { "$it..." } ?: "Not generated",
                    icon = Icons.Default.Fingerprint,
                    onClick = { showNodeIdDialog = true }
                )
            }
        }

        // Behavior Section
        item {
            Text(
                text = "Behavior",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsSwitch(
                    title = "Auto-start on boot",
                    subtitle = "Start Atmosphere service when device boots",
                    icon = Icons.Default.PowerSettingsNew,
                    checked = autoStart,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoStartOnBoot(enabled) }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsSwitch(
                    title = "Auto-reconnect to mesh",
                    subtitle = lastMeshName?.let { "Last: $it" } ?: "Reconnect to last mesh on startup",
                    icon = Icons.Default.Hub,
                    checked = autoReconnect,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoReconnectMesh(enabled) }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Battery Optimization",
                    subtitle = "Tap to configure for reliable background operation",
                    icon = Icons.Default.BatteryStd,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }

        // Network Section
        item {
            Text(
                text = "Network",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsItem(
                    title = "Transport Settings",
                    subtitle = "Configure LAN, WiFi Direct, BLE, Matter, Relay",
                    icon = Icons.Default.SettingsEthernet,
                    onClick = { onNavigateToTransportSettings?.invoke() }
                )
            }
        }

        // Daemon Section
        item {
            Text(
                text = "Daemon (atmosphere-core)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsItem(
                    title = "Daemon URL",
                    subtitle = "http://127.0.0.1:11462 (via adb reverse)",
                    icon = Icons.Default.Link,
                    onClick = { /* Future: allow URL editing */ }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "BigLlama Config",
                    subtitle = "Configure Cloud/LAN inference routing",
                    icon = Icons.Default.CloudQueue,
                    onClick = { /* Future: BigLlama configuration */ }
                )
            }
        }
        
        // Permissions Section
        item {
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsItem(
                    title = "App Permissions",
                    subtitle = "Manage camera, microphone, location access",
                    icon = Icons.Default.Security,
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Notification Settings",
                    subtitle = "Configure notification preferences",
                    icon = Icons.Default.Notifications,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }
        
        // Data Section
        item {
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsItem(
                    title = "Clear Mesh History",
                    subtitle = "Remove saved mesh connection",
                    icon = Icons.Default.DeleteSweep,
                    onClick = {
                        scope.launch { preferences.clearMeshConnection() }
                    }
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Reset All Settings",
                    subtitle = "Clear all preferences and start fresh",
                    icon = Icons.Default.RestartAlt,
                    onClick = {
                        scope.launch { preferences.clearAll() }
                    },
                    dangerous = true
                )
            }
        }

        // About Section
        item {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsItem(
                    title = "Version",
                    subtitle = BuildConfig.VERSION_NAME,
                    icon = Icons.Default.Info,
                    onClick = {}
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Atmosphere Project",
                    subtitle = "github.com/llamafarm/atmosphere",
                    icon = Icons.Default.Code,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://github.com/llamafarm/atmosphere")
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }
    }

    // Node ID Dialog
    if (showNodeIdDialog) {
        AlertDialog(
            onDismissRequest = { showNodeIdDialog = false },
            title = { Text("Node ID") },
            text = {
                Column {
                    Text("Your unique node identifier:")
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = nodeId ?: "Not generated yet",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNodeIdDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    dangerous: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}
