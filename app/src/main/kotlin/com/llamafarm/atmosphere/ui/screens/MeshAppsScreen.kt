package com.llamafarm.atmosphere.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import com.llamafarm.atmosphere.apps.AppCapability
import com.llamafarm.atmosphere.apps.AppEndpoint
import com.llamafarm.atmosphere.apps.AppRegistry
import com.llamafarm.atmosphere.apps.PushEvent
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen showing mesh-discovered apps and their capabilities.
 * Allows interacting with app endpoints and viewing push events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshAppsScreen(viewModel: AtmosphereViewModel) {
    val appRegistry = remember { AppRegistry.getInstance() }
    val appCapabilities by appRegistry.appCapabilities.collectAsState()
    val pushEvents by appRegistry.pushEvents.collectAsState()
    val isConnected by viewModel.isConnectedToMesh.collectAsState()
    
    // Periodically clean up expired capabilities
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            appRegistry.cleanupExpired()
        }
    }

    // Group capabilities by app name
    val appGroups = remember(appCapabilities) {
        appCapabilities.groupBy { it.appName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Mesh Apps",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Discovered via mesh gossip protocol",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryCard(Modifier.weight(1f), "${appGroups.size}", "Apps", Icons.Default.Apps)
            SummaryCard(Modifier.weight(1f), "${appCapabilities.size}", "Capabilities", Icons.Default.Extension)
            SummaryCard(Modifier.weight(1f), "${pushEvents.size}", "Events", Icons.Default.Notifications)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Not connected to mesh. Join a mesh to discover apps.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (appGroups.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No Apps Discovered", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Apps like HORIZON will appear here when they register on the mesh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Render each app group
            appGroups.forEach { (appName, caps) ->
                AppCard(appName = appName, capabilities = caps, viewModel = viewModel)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Push Events section
        if (pushEvents.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "📨 Push Events",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            pushEvents.take(20).forEach { event ->
                PushEventRow(event)
            }
        }
    }
}

@Composable
private fun SummaryCard(modifier: Modifier, value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppCard(appName: String, capabilities: List<AppCapability>, viewModel: AtmosphereViewModel) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3).copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Widgets, contentDescription = null, tint = Color(0xFF2196F3), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${capabilities.size} capabilities • ${capabilities.firstOrNull()?.nodeName ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(shape = MaterialTheme.shapes.small, color = capTypeColor(capabilities.first().type)) {
                    Text(
                        text = capabilities.first().type.removePrefix("app/"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "toggle",
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    capabilities.forEach { cap ->
                        Spacer(Modifier.height(8.dp))
                        CapabilitySection(cap, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitySection(cap: AppCapability, viewModel: AtmosphereViewModel) {
    if (cap.description.isNotEmpty()) {
        Text(
            text = cap.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp)
        )
    }
    
    if (cap.keywords.isNotEmpty()) {
        Row(modifier = Modifier.padding(start = 36.dp, top = 4.dp)) {
            cap.keywords.take(5).forEach { kw ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = kw,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    // Endpoints
    cap.endpoints.forEach { entry ->
        EndpointRow(capabilityId = cap.id, endpoint = entry.value, viewModel = viewModel)
    }
}

@Composable
private fun EndpointRow(capabilityId: String, endpoint: AppEndpoint, viewModel: AtmosphereViewModel) {
    var showResponse by remember { mutableStateOf(false) }
    var responseText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(start = 36.dp, top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!isLoading) {
                        isLoading = true
                        showResponse = true
                        responseText = "Loading..."
                        viewModel.sendAppRequest(capabilityId, endpoint.name) { response ->
                            responseText = response.toString(2)
                            isLoading = false
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = methodColor(endpoint.method)) {
                Text(
                    text = endpoint.method,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = endpoint.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                if (endpoint.description.isNotEmpty()) {
                    Text(text = endpoint.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = "invoke", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }

        AnimatedVisibility(visible = showResponse) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = responseText,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 20
                )
            }
        }
    }
}

@Composable
private fun PushEventRow(event: PushEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeFormat.format(Date(event.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = "${event.capabilityId}: ${event.eventType}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private fun capTypeColor(type: String): Color = when (type) {
    "app/query" -> Color(0xFF2196F3)
    "app/action" -> Color(0xFFFF5722)
    "app/stream" -> Color(0xFF4CAF50)
    "app/chat" -> Color(0xFF9C27B0)
    else -> Color(0xFF607D8B)
}

private fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> Color(0xFF4CAF50)
    "POST" -> Color(0xFF2196F3)
    "PUT" -> Color(0xFFFFA726)
    "DELETE" -> Color(0xFFEF5350)
    else -> Color(0xFF607D8B)
}
