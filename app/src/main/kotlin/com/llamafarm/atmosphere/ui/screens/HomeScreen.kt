package com.llamafarm.atmosphere.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.network.RoutingInfo
import com.llamafarm.atmosphere.service.AtmosphereService
import com.llamafarm.atmosphere.ui.components.StatusCard
import com.llamafarm.atmosphere.ui.theme.StatusConnecting
import com.llamafarm.atmosphere.ui.theme.StatusOffline
import com.llamafarm.atmosphere.ui.theme.StatusOnline
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel.MeshEvent
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AtmosphereViewModel) {
    val context = LocalContext.current
    
    // Cost tracking
    val localCost by viewModel.localCost.collectAsState()
    val peerCosts by viewModel.peerCosts.collectAsState()
    val isConnected by viewModel.isConnectedToMesh.collectAsState()
    val nodeState by viewModel.nodeState.collectAsState()
    
    // Mesh connection state
    val meshName by viewModel.meshName.collectAsState()
    val savedMeshName by viewModel.savedMeshName.collectAsState()
    val hasSavedMesh by viewModel.hasSavedMesh.collectAsState()
    val connectionState by viewModel.relayConnectionState.collectAsState()
    
    // Mesh events
    val meshEvents by viewModel.meshEvents.collectAsState()
    
    // 🎯 Routing decision from router (THE CROWN JEWEL!)
    val lastRoutingDecision by viewModel.lastRoutingDecision.collectAsState()
    
    // Gossip capabilities count
    val gossipStats by viewModel.gossipStats.collectAsState()
    
    // Relay peers
    val relayPeers by viewModel.relayPeers.collectAsState()
    
    // 📊 Debug: FORCE LOG ON EVERY RECOMPOSITION
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            android.util.Log.i("HomeScreen", """
                📊 PERIODIC UI CHECK (every 2s):
                - Mesh Events: ${meshEvents.size}
                - Relay Peers: ${relayPeers.size}
                - Connected Peers (nodeState): ${nodeState.connectedPeers}
                - Total Capabilities: ${gossipStats["total_capabilities"]}
                - Is Connected: $isConnected
                - Gossip Stats Keys: ${gossipStats.keys}
            """.trimIndent())
        }
    }
    
    // 📊 Debug: Log UI state to verify data flow
    LaunchedEffect(meshEvents.size, relayPeers.size, gossipStats, nodeState.connectedPeers) {
        android.util.Log.i("HomeScreen", """
            📊 UI State Update (RECOMPOSITION TRIGGERED):
            - Mesh Events: ${meshEvents.size}
            - Relay Peers: ${relayPeers.size}
            - Connected Peers (nodeState): ${nodeState.connectedPeers}
            - Total Capabilities: ${gossipStats["total_capabilities"]}
            - Is Connected: $isConnected
            - Gossip Stats Keys: ${gossipStats.keys}
        """.trimIndent())
        
        // Log first 3 events for debugging
        meshEvents.take(3).forEachIndexed { i, event ->
            android.util.Log.d("HomeScreen", "Event $i: [${event.type}] ${event.title}")
        }
        
        // Log peer details
        relayPeers.forEachIndexed { i, peer ->
            android.util.Log.d("HomeScreen", "Peer $i: ${peer.name} (${peer.nodeId.take(8)}...) - ${peer.capabilities.size} caps")
        }
    }

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
            text = "Mesh Node Control",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status Card - auto-updated from ViewModel
        StatusCard(
            title = "Node Status",
            status = nodeState.status,
            statusColor = when {
                isConnected -> StatusOnline
                nodeState.status.contains("Connect", ignoreCase = true) -> StatusConnecting
                else -> StatusOffline
            },
            icon = Icons.Default.Hub
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // 🔗 MESH CONNECTION CARD
        MeshConnectionCard(
            isConnected = isConnected,
            meshName = meshName,
            savedMeshName = savedMeshName,
            hasSavedMesh = hasSavedMesh,
            connectionState = connectionState,
            onReconnect = { viewModel.attemptReconnect() },
            onDisconnect = { viewModel.disconnectMesh(clearSaved = false) },
            onForget = { viewModel.forgetSavedMesh() }
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // 🎯 ROUTING DECISION CARD (THE CROWN JEWEL!)
        lastRoutingDecision?.let { decision ->
            RoutingDecisionCard(decision)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Quick Stats Row - Direct from state (simplified extraction)
        val peerCount = maxOf(relayPeers.size, nodeState.connectedPeers)
        
        // Simplified capability extraction - directly cast to Int
        val capabilityCount = (gossipStats["total_capabilities"] as? Int) ?: 0
        
        // Force log on every recomposition to debug
        Log.d("HomeScreen", "📊 StatCards render: peers=$peerCount, caps=$capabilityCount (relay=${relayPeers.size}, node=${nodeState.connectedPeers}, stats=${gossipStats["total_capabilities"]})")
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Peers",
                value = "$peerCount",
                icon = Icons.Default.People
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "Capabilities",
                value = "$capabilityCount",
                icon = Icons.Default.Memory
            )
        }
        
        // 🔧 DEBUG CARD - Shows raw state values for troubleshooting
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "🔧 DEBUG STATE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9800)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Events: ${meshEvents.size} | Relay Peers: ${relayPeers.size} | Node Peers: ${nodeState.connectedPeers}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Gossip keys: ${gossipStats.keys.joinToString()} | Connected: $isConnected",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Total caps: ${gossipStats["total_capabilities"]} | Computed: $capabilityCount",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Peer list (if any) - Simple direct rendering
        if (relayPeers.isNotEmpty()) {
            Log.d("HomeScreen", "📡 Rendering peer list: ${relayPeers.size} peers")
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "📡 ${relayPeers.size} Relay Peers",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                )
                relayPeers.forEach { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = peer.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = peer.nodeId.take(8) + "...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (peer.capabilities.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${peer.capabilities.size} caps",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9C27B0)
                            )
                        }
                    }
                }
            }
        } else {
            // Show debug info when no peers but we expect some
            if (isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🔍 Connected to mesh but no relay peers yet (expecting data...)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 💰 COST DASHBOARD - Shows routing costs
        CostDashboard(
            localCost = localCost,
            peerCosts = peerCosts,
            isConnected = isConnected
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // 📡 MESH EVENTS FEED - Shows gossip, peer events, routing
        MeshEventsFeed(
            events = meshEvents,
            modifier = Modifier
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Service Status (auto-managed, no manual control needed)
        // Service starts automatically on app launch

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

/**
 * 💰 Cost Dashboard - Shows local and peer routing costs
 */
@Composable
private fun CostDashboard(
    localCost: Float,
    peerCosts: Map<String, Float>,
    isConnected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.MonetizationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Routing Costs",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Local device cost
            CostRow(
                label = "This Device",
                cost = localCost,
                isLocal = true
            )
            
            // Peer costs
            if (peerCosts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Mesh Peers",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                peerCosts.forEach { (nodeId, cost) ->
                    CostRow(
                        label = nodeId.take(8) + "...",
                        cost = cost,
                        isLocal = false
                    )
                }
            } else if (isConnected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Waiting for peer cost updates...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CostRow(
    label: String,
    cost: Float,
    isLocal: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isLocal) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        // Cost badge with color coding
        Surface(
            shape = MaterialTheme.shapes.small,
            color = when {
                cost <= 1.0f -> Color(0xFF4CAF50).copy(alpha = 0.2f)  // Green - cheap
                cost <= 2.0f -> Color(0xFFFFA726).copy(alpha = 0.2f)  // Orange - moderate
                else -> Color(0xFFEF5350).copy(alpha = 0.2f)          // Red - expensive
            }
        ) {
            Text(
                text = String.format("%.2f", cost),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    cost <= 1.0f -> Color(0xFF4CAF50)
                    cost <= 2.0f -> Color(0xFFFFA726)
                    else -> Color(0xFFEF5350)
                }
            )
        }
    }
}

/**
 * 🎯 Server Routing Card - Shows the last routing decision from the server (THE CROWN JEWEL!)
 */
@Composable
private fun RoutingDecisionCard(decision: com.llamafarm.atmosphere.router.RoutingDecision) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF9C27B0).copy(alpha = 0.1f)  // Purple for routing
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    tint = Color(0xFF9C27B0),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last Routing Decision",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = decision.capability.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Model tier badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFF9C27B0)
                ) {
                    Text(
                        text = decision.capability.modelTier.value,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Details grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoutingDetail("Node", decision.capability.nodeName)
                RoutingDetail("Hops", decision.capability.hops.toString())
                RoutingDetail("Method", decision.matchMethod.name)
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Score bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Score",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(70.dp)
                )
                LinearProgressIndicator(
                    progress = { decision.scoreBreakdown.compositeScore },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = Color(0xFF9C27B0),
                    trackColor = Color(0xFF9C27B0).copy(alpha = 0.2f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(decision.scoreBreakdown.compositeScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Latency info
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Latency: ${decision.capability.estimatedLatencyMs.toInt()}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoutingDetail(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 📡 Mesh Events Feed - Shows real-time gossip, peer events, routing decisions.
 * Events are tappable to expand and show full metadata/detail.
 */
@Composable
private fun MeshEventsFeed(
    events: List<AtmosphereViewModel.MeshEvent>,
    modifier: Modifier = Modifier
) {
    // Force log to verify this composable is actually being called
    Log.d("HomeScreen", "📡 MeshEventsFeed rendered with ${events.size} events")
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Mesh Events",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${events.size} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (events.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No events yet. Connect to mesh to see activity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Log.d("HomeScreen", "📡 Rendering ${events.size} event rows")
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    events.take(50).forEach { event ->
                        MeshEventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshEventRow(event: AtmosphereViewModel.MeshEvent) {
    var expanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(event.timestamp))
    val hasDetail = event.metadata.isNotEmpty() || !event.detail.isNullOrEmpty()
    
    Log.d("HomeScreen", "📡 MeshEventRow: ${event.title} (type=${event.type}, hasDetail=$hasDetail)")
    
    val (icon, color) = when (event.type) {
        "connected" -> Icons.Default.Link to Color(0xFF4CAF50)
        "joined" -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        "peer_joined", "peer" -> Icons.Default.PersonAdd to Color(0xFF2196F3)
        "peer_left" -> Icons.Default.PersonRemove to Color(0xFFFF9800)
        "cost" -> Icons.Default.AttachMoney to Color(0xFF9C27B0)
        "chat" -> Icons.Default.Chat to Color(0xFF00BCD4)
        "route" -> Icons.Default.AltRoute to Color(0xFF3F51B5)
        "error" -> Icons.Default.Error to Color(0xFFEF5350)
        "gossip", "capability" -> Icons.Default.Campaign to Color(0xFFFF5722)
        "lan" -> Icons.Default.Wifi to Color(0xFF009688)
        "inference" -> Icons.Default.Psychology to Color(0xFF673AB7)
        "llm" -> Icons.Default.SmartToy to Color(0xFF00BCD4)
        else -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasDetail) Modifier.clickable { expanded = !expanded }
                else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1
                )
                if (!expanded) {
                    event.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            if (hasDetail) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            event.nodeId?.let { nodeId ->
                Spacer(Modifier.width(4.dp))
                Text(
                    text = nodeId.take(6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        
        // Expanded detail view
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            EventDetailPanel(event, color)
        }
    }
}

/**
 * Detail panel shown when an event is tapped.
 * Shows all metadata as key-value pairs, with special formatting for routing events.
 */
@Composable
private fun EventDetailPanel(
    event: AtmosphereViewModel.MeshEvent,
    accentColor: Color
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, top = 2.dp, bottom = 6.dp),
        shape = MaterialTheme.shapes.small,
        color = accentColor.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Show detail text if present
            event.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (event.metadata.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = accentColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))
                }
            }
            
            // Route events get special treatment
            if (event.type == "route" && event.metadata.isNotEmpty()) {
                RouteDetailView(event.metadata, accentColor)
            } else if (event.type == "gossip" || event.type == "capability") {
                GossipDetailView(event.metadata, accentColor)
            } else {
                // Generic metadata display
                event.metadata.forEach { (key, value) ->
                    MetadataRow(key, value)
                }
            }
        }
    }
}

/**
 * Special layout for routing decision details.
 */
@Composable
private fun RouteDetailView(metadata: Map<String, String>, accentColor: Color) {
    // Query
    metadata["query"]?.let { query ->
        Text(
            text = "\"$query\"",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
    }
    
    // Score breakdown
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ScorePill("Semantic", metadata["semanticScore"], accentColor)
        ScorePill("Latency", metadata["latencyScore"], accentColor)
        ScorePill("Hops", metadata["hopScore"], accentColor)
        ScorePill("Cost", metadata["costScore"], accentColor)
    }
    
    Spacer(Modifier.height(6.dp))
    
    // Model info
    Row {
        metadata["model"]?.let { model ->
            Text(
                text = model,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        metadata["modelTier"]?.let { tier ->
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = accentColor.copy(alpha = 0.2f)
            ) {
                Text(
                    text = tier.uppercase(),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
    
    // Method & hops
    Spacer(Modifier.height(4.dp))
    Row {
        metadata["method"]?.let { MetadataRow("Method", it) }
        Spacer(Modifier.width(12.dp))
        metadata["hops"]?.let { MetadataRow("Hops", it) }
        Spacer(Modifier.width(12.dp))
        metadata["latencyMs"]?.let { MetadataRow("Latency", "${it}ms") }
    }
    
    // Alternatives
    metadata["alternatives"]?.takeIf { it.isNotEmpty() }?.let { alts ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Alternatives: $alts",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    
    // Explanation
    metadata["explanation"]?.let { explanation ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = explanation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4
        )
    }
}

@Composable
private fun ScorePill(label: String, value: String?, accentColor: Color) {
    if (value == null) return
    val pct = (value.toFloatOrNull() ?: 0f) * 100
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${pct.toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Special layout for gossip/capability events — shows capability list.
 */
@Composable
private fun GossipDetailView(metadata: Map<String, String>, accentColor: Color) {
    metadata["node"]?.let { node ->
        MetadataRow("From Node", node)
        Spacer(Modifier.height(4.dp))
    }
    
    // Extract capability labels (cap_0, cap_1, ...)
    val caps = metadata.entries
        .filter { it.key.startsWith("cap_") }
        .sortedBy { it.key.removePrefix("cap_").toIntOrNull() ?: 0 }
        .map { it.value }
    
    if (caps.isNotEmpty()) {
        Text(
            text = "Capabilities:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        caps.forEach { cap ->
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = cap,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetadataRow(key: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$key: ",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 🔗 Mesh Connection Card - Shows mesh status with reconnect/forget options
 */
@Composable
private fun MeshConnectionCard(
    isConnected: Boolean,
    meshName: String?,
    savedMeshName: String?,
    hasSavedMesh: Boolean,
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                connectionState == ConnectionState.CONNECTING -> Color(0xFFFFA726).copy(alpha = 0.1f)
                hasSavedMesh -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when {
                        isConnected -> Icons.Default.Cloud
                        connectionState == ConnectionState.CONNECTING -> Icons.Default.CloudSync
                        hasSavedMesh -> Icons.Default.CloudOff
                        else -> Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    tint = when {
                        isConnected -> Color(0xFF4CAF50)
                        connectionState == ConnectionState.CONNECTING -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected -> "Connected to Mesh"
                            connectionState == ConnectionState.CONNECTING -> "Connecting..."
                            hasSavedMesh -> "Saved Mesh"
                            else -> "No Mesh Connection"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (meshName != null || savedMeshName != null) {
                        Text(
                            text = meshName ?: savedMeshName ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Status indicator
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        isConnected -> Color(0xFF4CAF50)
                        connectionState == ConnectionState.CONNECTING -> Color(0xFFFFA726)
                        connectionState == ConnectionState.FAILED -> Color(0xFFEF5350)
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    Text(
                        text = when {
                            isConnected -> "ONLINE"
                            connectionState == ConnectionState.CONNECTING -> "..."
                            connectionState == ConnectionState.FAILED -> "FAILED"
                            hasSavedMesh -> "OFFLINE"
                            else -> "—"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            // Action buttons
            if (hasSavedMesh || isConnected) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isConnected && hasSavedMesh) {
                        Button(
                            onClick = onReconnect,
                            modifier = Modifier.weight(1f),
                            enabled = connectionState != ConnectionState.CONNECTING
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reconnect")
                        }
                    }
                    if (isConnected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect")
                        }
                    }
                    if (hasSavedMesh && !isConnected) {
                        OutlinedButton(
                            onClick = onForget,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFEF5350)
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            
            // Hint for new users
            if (!hasSavedMesh && !isConnected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Scan a mesh QR code to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
