package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.horizon.Anomaly
import com.llamafarm.atmosphere.horizon.HorizonViewModel

/**
 * HORIZON Anomalies Screen - View and manage active anomalies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorizonAnomaliesScreen(
    viewModel: HorizonViewModel,
    onNavigateBack: () -> Unit
) {
    val anomalies by viewModel.anomalies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadAnomalies()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Anomalies") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFFFFB74D)
                )
            )
        },
        containerColor = Color(0xFF0A0A0A),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.loadAnomalies() },
                containerColor = Color(0xFFFFB74D)
            ) {
                Icon(Icons.Default.Refresh, "Refresh", tint = Color.Black)
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFFFB74D))
            }
        } else if (anomalies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "No anomalies",
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Active Anomalies",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Group by severity
                val critical = anomalies.filter { it.severity == "critical" }
                val warning = anomalies.filter { it.severity == "warning" }
                val caution = anomalies.filter { it.severity == "caution" }
                val info = anomalies.filter { it.severity == "info" }
                
                if (critical.isNotEmpty()) {
                    item {
                        SeverityHeader("CRITICAL", critical.size, Color(0xFFFF5252))
                    }
                    items(critical) { anomaly ->
                        AnomalyCard(anomaly, viewModel)
                    }
                }
                
                if (warning.isNotEmpty()) {
                    item {
                        SeverityHeader("WARNING", warning.size, Color(0xFFFFB74D))
                    }
                    items(warning) { anomaly ->
                        AnomalyCard(anomaly, viewModel)
                    }
                }
                
                if (caution.isNotEmpty()) {
                    item {
                        SeverityHeader("CAUTION", caution.size, Color(0xFFFFC107))
                    }
                    items(caution) { anomaly ->
                        AnomalyCard(anomaly, viewModel)
                    }
                }
                
                if (info.isNotEmpty()) {
                    item {
                        SeverityHeader("INFO", info.size, Color(0xFF2196F3))
                    }
                    items(info) { anomaly ->
                        AnomalyCard(anomaly, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun SeverityHeader(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = color.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                count.toString(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AnomalyCard(anomaly: Anomaly, viewModel: HorizonViewModel) {
    var expanded by remember { mutableStateOf(false) }
    
    val severityColor = when (anomaly.severity) {
        "critical" -> Color(0xFFFF5252)
        "warning" -> Color(0xFFFFB74D)
        "caution" -> Color(0xFFFFC107)
        else -> Color(0xFF2196F3)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(8.dp),
        onClick = { expanded = !expanded }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        anomaly.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        anomaly.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF808080)
                    )
                }
                
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = Color(0xFF808080)
                )
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFF2A2A2A))
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    anomaly.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!anomaly.acknowledged) {
                        OutlinedButton(
                            onClick = { viewModel.acknowledgeAnomaly(anomaly.id) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFFB74D)
                            )
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ACK")
                        }
                    }
                    
                    Button(
                        onClick = { viewModel.resolveAnomaly(anomaly.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESOLVE")
                    }
                }
            }
        }
    }
}
