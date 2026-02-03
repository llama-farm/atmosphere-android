package com.llamafarm.atmosphere.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var nodeName by remember { mutableStateOf("My Android Node") }
    var autoStart by remember { mutableStateOf(false) }
    var showNodeIdDialog by remember { mutableStateOf(false) }
    
    // Placeholder node ID - would come from actual node initialization
    val nodeId = remember { "atm_" + (1..16).map { ('a'..'z').random() }.joinToString("") }

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
                SettingsTextField(
                    label = "Node Name",
                    value = nodeName,
                    onValueChange = { nodeName = it },
                    icon = Icons.Default.Badge
                )
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                SettingsItem(
                    title = "Node ID",
                    subtitle = nodeId.take(24) + "...",
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
                    onCheckedChange = { autoStart = it }
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
                        // TODO: Open GitHub page
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
                            text = nodeId,
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
    onClick: () -> Unit
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
        Icon(
            Icons.Default.ChevronRight,
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
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SelectionContainer(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}
