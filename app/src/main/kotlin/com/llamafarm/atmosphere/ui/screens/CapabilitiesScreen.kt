package com.llamafarm.atmosphere.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.llamafarm.atmosphere.data.AtmospherePreferences
import kotlinx.coroutines.launch

/**
 * Represents a capability that can be exposed by this node.
 */
data class Capability(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val isEnabled: Boolean,
    val permission: String? = null,
    val permissionGranted: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AtmospherePreferences(context) }
    
    // Collect persisted states
    val cameraEnabled by preferences.cameraEnabled.collectAsState(initial = false)
    val micEnabled by preferences.micEnabled.collectAsState(initial = false)
    val locationEnabled by preferences.locationEnabled.collectAsState(initial = false)
    val storageEnabled by preferences.storageEnabled.collectAsState(initial = false)
    val computeEnabled by preferences.computeEnabled.collectAsState(initial = false)
    val notificationsEnabled by preferences.notificationsEnabled.collectAsState(initial = true)
    
    // Check current permission states
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Permission launchers
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            scope.launch { preferences.setCameraEnabled(true) }
        }
    }
    
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
        if (granted) {
            scope.launch { preferences.setMicEnabled(true) }
        }
    }
    
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationPermissionGranted = granted
        if (granted) {
            scope.launch { preferences.setLocationEnabled(true) }
        }
    }
    
    // Build capabilities list with current states
    val capabilities = listOf(
        Capability(
            id = "camera",
            name = "Camera",
            description = "Share camera access with the mesh",
            icon = Icons.Default.CameraAlt,
            isEnabled = cameraEnabled,
            permission = Manifest.permission.CAMERA,
            permissionGranted = cameraPermissionGranted
        ),
        Capability(
            id = "microphone",
            name = "Microphone",
            description = "Share microphone access with the mesh",
            icon = Icons.Default.Mic,
            isEnabled = micEnabled,
            permission = Manifest.permission.RECORD_AUDIO,
            permissionGranted = micPermissionGranted
        ),
        Capability(
            id = "location",
            name = "Location",
            description = "Share location data with the mesh",
            icon = Icons.Default.LocationOn,
            isEnabled = locationEnabled,
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            permissionGranted = locationPermissionGranted
        ),
        Capability(
            id = "storage",
            name = "Storage",
            description = "Provide distributed storage to the mesh",
            icon = Icons.Default.Storage,
            isEnabled = storageEnabled
        ),
        Capability(
            id = "compute",
            name = "Compute",
            description = "Offer compute resources to the mesh",
            icon = Icons.Default.Memory,
            isEnabled = computeEnabled
        ),
        Capability(
            id = "notifications",
            name = "Notifications",
            description = "Receive mesh notifications",
            icon = Icons.Default.Notifications,
            isEnabled = notificationsEnabled
        )
    )
    
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
                        scope.launch {
                            when (capability.id) {
                                "camera" -> {
                                    if (enabled && !cameraPermissionGranted) {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    } else {
                                        preferences.setCameraEnabled(enabled)
                                    }
                                }
                                "microphone" -> {
                                    if (enabled && !micPermissionGranted) {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        preferences.setMicEnabled(enabled)
                                    }
                                }
                                "location" -> {
                                    if (enabled && !locationPermissionGranted) {
                                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        preferences.setLocationEnabled(enabled)
                                    }
                                }
                                "storage" -> preferences.setStorageEnabled(enabled)
                                "compute" -> preferences.setComputeEnabled(enabled)
                                "notifications" -> preferences.setNotificationsEnabled(enabled)
                            }
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
                capability.permission?.let {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (capability.permissionGranted) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (capability.permissionGranted) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (capability.permissionGranted) "Permission granted" else "Requires permission",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (capability.permissionGranted) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Toggle
            Switch(
                checked = capability.isEnabled,
                onCheckedChange = { enabled ->
                    onToggle(enabled)
                }
            )
        }
    }
}
