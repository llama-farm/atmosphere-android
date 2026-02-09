package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.horizon.HorizonViewModel

/**
 * HORIZON Dashboard - Mission overview and status.
 * 
 * Military aesthetic: dark theme, amber/green accents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorizonDashboardScreen(
    viewModel: HorizonViewModel,
    onNavigateToAnomalies: () -> Unit,
    onNavigateToAgentActions: () -> Unit,
    onNavigateToIntelChat: () -> Unit,
    onNavigateToBrief: () -> Unit
) {
    val missionSummary by viewModel.missionSummary.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    // Load data on first composition
    LaunchedEffect(Unit) {
        viewModel.loadMissionSummary()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HORIZON", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFFFFB74D)  // Amber
                )
            )
        },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mission Status Card
            item {
                MissionStatusCard(
                    callsign = missionSummary.callsign,
                    phase = missionSummary.phase,
                    route = missionSummary.route,
                    connectivity = missionSummary.connectivity
                )
            }
            
            // Quick Stats
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Warning,
                        label = "Anomalies",
                        value = missionSummary.anomalyCount.toString(),
                        color = if (missionSummary.anomalyCount > 0) Color(0xFFFF5252) else Color(0xFF4CAF50),
                        onClick = onNavigateToAnomalies
                    )
                    
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.HourglassTop,
                        label = "Pending",
                        value = missionSummary.pendingActions.toString(),
                        color = if (missionSummary.pendingActions > 0) Color(0xFFFFB74D) else Color(0xFF4CAF50),
                        onClick = onNavigateToAgentActions
                    )
                }
            }
            
            // Quick Actions
            item {
                Text(
                    "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.ChatBubble,
                    title = "Intel Chat",
                    subtitle = "Query knowledge brain",
                    onClick = onNavigateToIntelChat
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.Description,
                    title = "Intelligence Brief",
                    subtitle = "View latest OSINT brief",
                    onClick = onNavigateToBrief
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.Warning,
                    title = "Active Anomalies",
                    subtitle = "Monitor and resolve issues",
                    onClick = onNavigateToAnomalies
                )
            }
            
            item {
                QuickActionCard(
                    icon = Icons.Default.CheckCircle,
                    title = "Agent Actions",
                    subtitle = "Approve/reject decisions",
                    onClick = onNavigateToAgentActions
                )
            }
        }
    }
}

@Composable
fun MissionStatusCard(
    callsign: String,
    phase: String,
    route: String,
    connectivity: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    callsign.ifEmpty { "NO MISSION" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB74D)  // Amber
                )
                
                ConnectivityBadge(connectivity)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            StatusRow(label = "Phase", value = phase.uppercase())
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow(label = "Route", value = route)
        }
    }
}

@Composable
fun ConnectivityBadge(status: String) {
    val (color, text) = when (status.lowercase()) {
        "connected" -> Color(0xFF4CAF50) to "CONNECTED"
        "degraded" -> Color(0xFFFFB74D) to "DEGRADED"
        "denied" -> Color(0xFFFF5252) to "DENIED"
        else -> Color(0xFF666666) to "UNKNOWN"
    }
    
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF808080)
        )
        Text(
            value.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE0E0E0),
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF808080)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE0E0E0),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF808080)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Navigate",
                tint = Color(0xFF606060)
            )
        }
    }
}
