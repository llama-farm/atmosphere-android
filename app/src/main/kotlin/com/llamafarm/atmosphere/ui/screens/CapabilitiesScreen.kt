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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Represents a capability that can be exposed by this node.
 */
data class Capability(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isEnabled: Boolean,
    val requiresPermission: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen() {
    // Predefined capabilities this node can expose
    var capabilities by remember {
        mutableStateOf(
            listOf(
                Capability(
                    id = "camera",
                    name = "Camera",
                    description = "Share camera access with the mesh",
                    icon = Icons.Default.CameraAlt,
                    isEnabled = false,
                    requiresPermission = "android.permission.CAMERA"
                ),
                Capability(
                    id = "microphone",
                    name = "Microphone",
                    description = "Share microphone access with the mesh",
                    icon = Icons.Default.Mic,
                    isEnabled = false,
                    requiresPermission = "android.permission.RECORD_AUDIO"
                ),
                Capability(
                    id = "location",
                    name = "Location",
                    description = "Share location data with the mesh",
                    icon = Icons.Default.LocationOn,
                    isEnabled = false,
                    requiresPermission = "android.permission.ACCESS_FINE_LOCATION"
                ),
                Capability(
                    id = "storage",
                    name = "Storage",
                    description = "Provide distributed storage to the mesh",
                    icon = Icons.Default.Storage,
                    isEnabled = false
                ),
                Capability(
                    id = "compute",
                    name = "Compute",
                    description = "Offer compute resources to the mesh",
                    icon = Icons.Default.Memory,
                    isEnabled = false
                ),
                Capability(
                    id = "notifications",
                    name = "Notifications",
                    description = "Receive mesh notifications",
                    icon = Icons.Default.Notifications,
                    isEnabled = true
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Capabilities",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure what this node exposes to the mesh",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Summary card
        val enabledCount = capabilities.count { it.isEnabled }
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "$enabledCount of ${capabilities.size} capabilities enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Capabilities list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(capabilities) { capability ->
                CapabilityCard(
                    capability = capability,
                    onToggle = { enabled ->
                        capabilities = capabilities.map {
                            if (it.id == capability.id) it.copy(isEnabled = enabled)
                            else it
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    capability: Capability,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (capability.isEnabled) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        capability.icon,
                        contentDescription = null,
                        tint = if (capability.isEnabled)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = capability.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = capability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                capability.requiresPermission?.let {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Requires permission",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Toggle
            Switch(
                checked = capability.isEnabled,
                onCheckedChange = { enabled ->
                    // TODO: Check/request permission if needed
                    onToggle(enabled)
                }
            )
        }
    }
}
