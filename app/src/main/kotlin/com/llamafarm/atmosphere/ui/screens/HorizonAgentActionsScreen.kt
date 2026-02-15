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
import com.llamafarm.atmosphere.horizon.AgentAction
import com.llamafarm.atmosphere.horizon.HorizonViewModel

/**
 * HORIZON Agent Actions Screen - Approve/reject agent decisions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorizonAgentActionsScreen(
    viewModel: HorizonViewModel,
    onNavigateBack: () -> Unit
) {
    val actions by viewModel.agentActions.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.loadAgentActions()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Actions") },
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
                onClick = { viewModel.loadAgentActions() },
                containerColor = Color(0xFFFFB74D)
            ) {
                Icon(Icons.Default.Refresh, "Refresh", tint = Color.Black)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AgentStatusCard(
                    monitoring = agentStatus.monitoring,
                    needsInput = agentStatus.needsInputCount,
                    hilCritical = agentStatus.hilCriticalCount
                )
            }
            
            if (actions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1A1A1A)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No Pending Actions",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            } else {
                items(actions.sortedByDescending { it.hilPriority == "critical" || it.hilPriority == "high" }) { action ->
                    AgentActionCard(action, viewModel)
                }
            }
        }
    }
}

@Composable
fun AgentStatusCard(monitoring: Boolean, needsInput: Int, hilCritical: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = if (monitoring) Color(0xFF4CAF50) else Color(0xFF666666),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ) {
                        Spacer(modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    if (monitoring) "MONITORING" else "OFFLINE",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        hilCritical.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hilCritical > 0) Color(0xFFFF5252) else Color(0xFF808080),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "CRITICAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF808080)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        needsInput.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (needsInput > 0) Color(0xFFFFB74D) else Color(0xFF808080),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "PENDING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF808080)
                    )
                }
            }
        }
    }
}

@Composable
fun AgentActionCard(action: AgentAction, viewModel: HorizonViewModel) {
    val priorityColor = when (action.hilPriority) {
        "critical" -> Color(0xFFFF5252)
        "high" -> Color(0xFFFFB74D)
        else -> Color(0xFF2196F3)
    }
    
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
                    action.channel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold
                )
                
                Surface(
                    color = priorityColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        action.hilPriority.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = priorityColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "From: ${action.sender}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF808080)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = Color(0xFF2A2A2A))
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Original Message:",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF808080)
            )
            Text(
                action.content,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE0E0E0)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                "Agent Response:",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF808080)
            )
            Surface(
                color = Color(0xFF0F0F0F),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    action.draftedResponse.ifEmpty { "No response drafted" },
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0)
                )
            }
            
            if (action.reasoning.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Reasoning: ${action.reasoning}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF808080),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.rejectAction(action.id) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF5252)
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REJECT")
                }
                
                Button(
                    onClick = { viewModel.approveAction(action.id) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("APPROVE")
                }
            }
        }
    }
}
