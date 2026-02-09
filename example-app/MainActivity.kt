package com.example.atmosphere_demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.llamafarm.atmosphere.sdk.*
import kotlinx.coroutines.launch

/**
 * Example Atmosphere SDK integration.
 * 
 * Shows:
 * - Connecting to Atmosphere
 * - Checking installation
 * - Sending chat requests
 * - Viewing capabilities
 * - Monitoring mesh status
 * - Cost metrics
 */
class MainActivity : ComponentActivity() {
    
    private lateinit var atmosphere: AtmosphereClient
    private var isInstalled by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if Atmosphere is installed
        isInstalled = AtmosphereClient.isInstalled(this)
        
        if (isInstalled) {
            // Connect to Atmosphere
            atmosphere = AtmosphereClient.connect(this)
        }
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isInstalled) {
                        AtmosphereDemoScreen(atmosphere)
                    } else {
                        NotInstalledScreen()
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::atmosphere.isInitialized) {
            atmosphere.disconnect()
        }
    }
}

@Composable
fun NotInstalledScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Atmosphere Not Installed",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "This app requires the Atmosphere app to be installed.",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = {
            // Open Play Store (or download APK)
            val url = AtmosphereClient.getInstallUrl()
            // TODO: Open browser to url
        }) {
            Text("Install Atmosphere")
        }
    }
}

@Composable
fun AtmosphereDemoScreen(atmosphere: AtmosphereClient) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Chat") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Capabilities") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Status") }
            )
        }
        
        when (selectedTab) {
            0 -> ChatScreen(atmosphere)
            1 -> CapabilitiesScreen(atmosphere)
            2 -> StatusScreen(atmosphere)
        }
    }
}

@Composable
fun ChatScreen(atmosphere: AtmosphereClient) {
    var message by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var isLoading by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Chat history
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { (role, content) ->
                ChatBubble(role, content)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask anything...") },
                enabled = !isLoading
            )
            
            Button(
                onClick = {
                    if (message.isNotBlank()) {
                        val userMessage = message
                        message = ""
                        
                        // Add user message
                        messages = messages + ("user" to userMessage)
                        
                        // Send to Atmosphere
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val result = atmosphere.chat(
                                    messages = listOf(
                                        ChatMessage.user(userMessage)
                                    )
                                )
                                
                                if (result.success) {
                                    messages = messages + ("assistant" to result.content!!)
                                } else {
                                    messages = messages + ("error" to result.error!!)
                                }
                            } catch (e: Exception) {
                                messages = messages + ("error" to e.message!!)
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = !isLoading && message.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(role: String, content: String) {
    val backgroundColor = when (role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "assistant" -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = role.uppercase(),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun CapabilitiesScreen(atmosphere: AtmosphereClient) {
    var capabilities by remember { mutableStateOf(listOf<Capability>()) }
    var isLoading by remember { mutableStateOf(true) }
    
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                capabilities = atmosphere.capabilities()
            } finally {
                isLoading = false
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Available Capabilities",
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(capabilities) { cap ->
                    CapabilityCard(cap)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun CapabilityCard(capability: Capability) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = capability.name,
                style = MaterialTheme.typography.titleMedium
            )
            
            Text(
                text = "Type: ${capability.type}",
                style = MaterialTheme.typography.bodySmall
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Cost: ${(capability.cost * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                
                Text(
                    text = if (capability.available) "Available" else "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (capability.available) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            Text(
                text = "Node: ${capability.nodeId.take(8)}...",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun StatusScreen(atmosphere: AtmosphereClient) {
    val meshStatus by atmosphere.meshStatusFlow().collectAsState(
        initial = MeshStatus(connected = false)
    )
    
    val costMetrics by atmosphere.costMetricsFlow().collectAsState(
        initial = CostMetrics()
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Mesh Status",
            style = MaterialTheme.typography.headlineSmall
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Mesh info
        StatusCard("Mesh Status") {
            StatusRow("Connected", meshStatus.connected.toString())
            StatusRow("Mesh ID", meshStatus.meshId ?: "N/A")
            StatusRow("Node ID", meshStatus.nodeId ?: "N/A")
            StatusRow("Peers", meshStatus.peerCount.toString())
            StatusRow("Capabilities", meshStatus.capabilities.toString())
            StatusRow("Relay", if (meshStatus.relayConnected) "Connected" else "Disconnected")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cost metrics
        StatusCard("Cost Metrics") {
            StatusRow("Battery", "${(costMetrics.battery * 100).toInt()}%")
            StatusRow("CPU", "${(costMetrics.cpu * 100).toInt()}%")
            StatusRow("Memory", "${(costMetrics.memory * 100).toInt()}%")
            StatusRow("Network", costMetrics.network)
            StatusRow("Thermal", costMetrics.thermal)
            StatusRow(
                "Overall Cost", 
                "${(costMetrics.overall * 100).toInt()}%",
                color = if (costMetrics.isAvailable()) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.error
            )
            
            if (costMetrics.isAvailable()) {
                Text(
                    text = "✓ Device available for work",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "⚠ Device cost is high",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun StatusCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun StatusRow(
    label: String, 
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}
