package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.service.AtmosphereService
import com.llamafarm.atmosphere.ui.components.StatusCard
import com.llamafarm.atmosphere.ui.theme.StatusConnecting
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }
    var nodeStatus by remember { mutableStateOf("Offline") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Atmosphere",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Mesh Node Control",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status Card
        StatusCard(
            title = "Node Status",
            status = nodeStatus,
            statusColor = when (nodeStatus) {
                "Online" -> StatusOnline
                "Connecting" -> StatusConnecting
                else -> StatusOffline
            },
            icon = Icons.Default.Hub
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Peers",
                value = "0",
                icon = Icons.Default.People
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Capabilities",
                value = "0",
                icon = Icons.Default.Memory
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Service Control
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Service Control",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            AtmosphereService.start(context)
                            isServiceRunning = true
                            nodeStatus = "Connecting"
                        },
                        enabled = !isServiceRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start")
                    }

                    OutlinedButton(
                        onClick = {
                            AtmosphereService.stop(context)
                            isServiceRunning = false
                            nodeStatus = "Offline"
                        },
                        enabled = isServiceRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Native Library Status
        val nativeStatus = if (AtmosphereApplication.isNativeLoaded()) "Loaded" else "Not Available"
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
                        text = "Native Library",
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
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
