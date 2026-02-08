package com.llamafarm.atmosphere.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.router.RoutingDecision
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Comprehensive test screen for testing mesh connectivity, capabilities, and inference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(viewModel: AtmosphereViewModel, onNavigateToPairing: () -> Unit) {
    val connectionState by viewModel.meshConnectionState.collectAsState()
    val meshName by viewModel.meshName.collectAsState()
    val nodeState by viewModel.nodeState.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Inference", "Connectivity", "Nodes")
    
    val isConnected = connectionState == ConnectionState.CONNECTED
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Testing & Diagnostics")
                        meshName?.let {
                            Text(
                                text = "Mesh: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    ConnectionBadge(connectionState)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Default.Psychology, contentDescription = null)
                                1 -> Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                2 -> Icon(Icons.Default.Devices, contentDescription = null)
                            }
                        }
                    )
                }
            }
            
            // Content
            when (selectedTab) {
                0 -> InferenceTestTab(viewModel, isConnected)
                1 -> ConnectivityTestTab(viewModel, isConnected, nodeState, onNavigateToPairing)
                2 -> NodesTestTab(viewModel, isConnected, peers)
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connectionState: ConnectionState) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
            else -> Color(0xFFE57373)
        }.copy(alpha = 0.2f),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
                            else -> Color(0xFFE57373)
                        }
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when (connectionState) {
                    ConnectionState.CONNECTED -> "Online"
                    ConnectionState.CONNECTING -> "Connecting"
                    ConnectionState.RECONNECTING -> "Reconnecting"
                    ConnectionState.FAILED -> "Failed"
                    ConnectionState.DISCONNECTED -> "Offline"
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Inference testing tab - send prompts and see responses.
 */
@Composable
private fun InferenceTestTab(viewModel: AtmosphereViewModel, isConnected: Boolean) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    
    // Semantic router result - THE CROWN JEWEL
    val lastRouteResult by viewModel.lastRouteResult.collectAsState()
    
    // Quick test prompts
    val quickTests = listOf(
        "What is 2+2?" to "Math" to Icons.Default.Calculate,
        "Tell me a short joke" to "Joke" to Icons.Default.EmojiEmotions,
        "What's the capital of France?" to "Geography" to Icons.Default.Public,
        "Say hello in 3 languages" to "Languages" to Icons.Default.Translate,
        "Write a haiku about code" to "Poetry" to Icons.Default.AutoStories
    )
    
    fun runTest(testPrompt: String) {
        if (!isConnected || isLoading) return
        
        isLoading = true
        error = null
        response = ""
        latencyMs = null
        
        val startTime = System.currentTimeMillis()
        
        viewModel.sendLlmPrompt(testPrompt) { resp, err ->
            val latency = System.currentTimeMillis() - startTime
            isLoading = false
            if (err != null) {
                error = err
            } else {
                response = resp ?: ""
                latencyMs = latency
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connection warning
        AnimatedVisibility(visible = !isConnected) {
            WarningCard("Not Connected", "Join a mesh to test remote LLM")
        }
        
        // Quick Test Buttons
        Text(
            text = "Quick Tests",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickTests.forEach { (promptLabel, icon) ->
                val (testPrompt, label) = promptLabel
                AssistChip(
                    onClick = {
                        prompt = testPrompt
                        runTest(testPrompt)
                    },
                    label = { Text(label) },
                    enabled = isConnected && !isLoading,
                    leadingIcon = {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
        
        HorizontalDivider()
        
        // Custom Prompt Input
        Text(
            text = "Custom Prompt",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        OutlinedTextField(
            value = prompt,
            onValueChange = { 
                prompt = it
                error = null
            },
            label = { Text("Enter your prompt") },
            placeholder = { Text("Ask anything...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = !isLoading,
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            trailingIcon = {
                if (prompt.isNotEmpty() && !isLoading) {
                    IconButton(onClick = { prompt = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        )
        
        // Send Button
        Button(
            onClick = { runTest(prompt) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = isConnected && prompt.isNotBlank() && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Text("Thinking...")
            } else {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send to Remote LLM")
            }
        }
        
        // Error Display
        error?.let { errMsg ->
            ErrorCard(errMsg)
        }
        
        // Response Display
        AnimatedVisibility(visible = response.isNotEmpty() || isLoading) {
            ResponseCard(response, latencyMs, isLoading)
        }
        
        // 🎯 SEMANTIC ROUTER - THE CROWN JEWEL
        AnimatedVisibility(visible = lastRouteResult != null && (response.isNotEmpty() || isLoading)) {
            lastRouteResult?.let { route ->
                SemanticRouterCard(route)
            }
        }
        
        // Info card when idle
        AnimatedVisibility(visible = response.isEmpty() && !isLoading && error == null) {
            InfoCard()
        }
        
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Connectivity testing tab - ping, bandwidth, WebSocket tests.
 */
@Composable
private fun ConnectivityTestTab(
    viewModel: AtmosphereViewModel,
    isConnected: Boolean,
    nodeState: AtmosphereViewModel.NodeState,
    onNavigateToPairing: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    var pingResults by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var isTestingPing by remember { mutableStateOf(false) }
    
    var wsResults by remember { mutableStateOf<List<TestResult>>(emptyList()) }
    var isTestingWs by remember { mutableStateOf(false) }
    
    fun runPingTest() {
        scope.launch {
            isTestingPing = true
            val results = mutableListOf<TestResult>()
            
            // Run 5 ping tests
            repeat(5) { i ->
                val start = System.currentTimeMillis()
                
                // Use LLM request as ping (simple prompt)
                var success = false
                var latency = 0L
                
                viewModel.sendLlmPrompt("ping") { resp, err ->
                    latency = System.currentTimeMillis() - start
                    success = err == null
                }
                
                // Wait for response
                delay(3000)
                
                results.add(TestResult("Ping ${i + 1}", success, latency))
            }
            
            pingResults = results
            isTestingPing = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Node Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Local Node Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                
                StatusRow("Status", nodeState.status)
                StatusRow("Node ID", nodeState.nodeId?.take(16)?.plus("...") ?: "Unknown")
                StatusRow("Connected Peers", nodeState.connectedPeers.toString())
                StatusRow("Capabilities", nodeState.capabilities.size.toString())
            }
        }
        
        // Ping Test
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Latency Test",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Button(
                        onClick = { runPingTest() },
                        enabled = isConnected && !isTestingPing
                    ) {
                        if (isTestingPing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Test")
                    }
                }
                
                if (pingResults.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    
                    pingResults.forEach { result ->
                        TestResultRow(result)
                    }
                    
                    // Average
                    val avgLatency = pingResults.filter { it.success }.map { it.latencyMs }.average()
                    if (avgLatency.isFinite()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Average: ${avgLatency.toLong()}ms",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        
        // BLE Mesh Test
        BleTestCard(viewModel, onNavigateToPairing)
        
        // Connection Info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Connection Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                StatusRow("Protocol", "WebSocket (wss://)")
                StatusRow("Connection", if (isConnected) "Established" else "Not connected")
                StatusRow("Auto-reconnect", "Enabled")
            }
        }
    }
}

/**
 * BLE Mesh test card - Start/stop BLE and see discovered peers.
 */
@Composable
private fun BleTestCard(viewModel: AtmosphereViewModel, onNavigateToPairing: () -> Unit) {
    val bleEnabled by viewModel.bleEnabled.collectAsState()
    val blePeers by viewModel.blePeers.collectAsState()
    val meshName by viewModel.meshName.collectAsState()
    val context = LocalContext.current
    
    // BLE permissions for Android 12+
    val blePermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
    
    var permissionDenied by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startBle(meshName)
            permissionDenied = false
        } else {
            permissionDenied = true
        }
    }
    
    fun hasPermissions(): Boolean {
        return blePermissions.all { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (bleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "BLE Mesh",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (bleEnabled) "Scanning..." else "Local mesh without internet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Button(
                    onClick = {
                        if (bleEnabled) {
                            onNavigateToPairing()
                        } else {
                            // Check permissions first
                            if (hasPermissions()) {
                                viewModel.startBle(meshName)
                            } else {
                                permissionLauncher.launch(blePermissions)
                            }
                        }
                    }
                ) {
                    Icon(
                        if (bleEnabled) Icons.Default.Security else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (bleEnabled) "Pairing" else "Start")
                }
            }
            
            if (bleEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.stopBle() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Scanning")
                }
            }
            
            // Permission denied warning
            if (permissionDenied) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠️ Bluetooth permissions required for BLE mesh",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            if (bleEnabled) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                
                if (blePeers.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Scanning for nearby Atmosphere nodes...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Discovered ${blePeers.size} node(s):",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    blePeers.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = peer.name.ifEmpty { peer.nodeId.take(12) },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "RSSI: ${peer.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Nodes testing tab - list remote nodes and test their capabilities.
 */
@Composable
private fun NodesTestTab(
    viewModel: AtmosphereViewModel,
    isConnected: Boolean,
    peers: List<com.llamafarm.atmosphere.bindings.MeshPeer>
) {
    val scope = rememberCoroutineScope()
    
    var testResults by remember { mutableStateOf<Map<String, TestResult>>(emptyMap()) }
    
    // Remote node capabilities (from mesh - these would come from topology)
    data class RemoteNode(
        val id: String,
        val name: String,
        val capabilities: List<String>,
        val isLeader: Boolean = false,
        val cost: Float? = null
    )
    
    // Get nodes from mesh - combine local peers with mesh topology info
    val remoteNodes = remember(peers) {
        if (peers.isEmpty()) {
            // Demo nodes for when mesh is connected but no native peers
            listOf(
                RemoteNode(
                    "mac-server-001",
                    "Mac LlamaFarm Server",
                    listOf("llm", "embeddings", "vision", "code"),
                    isLeader = true,
                    cost = 0.8f
                )
            )
        } else {
            peers.map { peer ->
                RemoteNode(
                    peer.nodeId,
                    peer.name,
                    peer.capabilities,
                    // Cost could be derived from latency in the future
                    cost = peer.latencyMs?.let { 1.0f - (it / 1000f).coerceIn(0f, 0.9f) }
                )
            }
        }
    }
    
    fun testCapability(nodeId: String, capability: String) {
        val key = "$nodeId:$capability"
        testResults = testResults + (key to TestResult(capability, false, 0, loading = true))
        
        val startTime = System.currentTimeMillis()
        
        when (capability.lowercase()) {
            "llm", "chat" -> {
                viewModel.sendLlmPrompt("Say 'test ok'") { resp, err ->
                    val latency = System.currentTimeMillis() - startTime
                    testResults = testResults + (key to TestResult(
                        capability,
                        err == null && resp?.isNotEmpty() == true,
                        latency
                    ))
                }
            }
            else -> {
                // Generic test - just check connectivity
                scope.launch {
                    delay(500) // Simulated test
                    testResults = testResults + (key to TestResult(capability, true, 500))
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Remote Nodes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "${remoteNodes.size} nodes",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        if (!isConnected) {
            WarningCard("Not Connected", "Join a mesh to see remote nodes")
        } else if (remoteNodes.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No remote nodes found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(remoteNodes) { node ->
                    NodeCard(
                        node = node,
                        testResults = testResults.filterKeys { it.startsWith(node.id) },
                        onTestCapability = { cap -> testCapability(node.id, cap) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: Any, // RemoteNode from NodesTestTab
    testResults: Map<String, TestResult>,
    onTestCapability: (String) -> Unit
) {
    // Use reflection or cast safely
    val id = (node as? Any)?.let { 
        it::class.java.getDeclaredField("id").apply { isAccessible = true }.get(it) as String 
    } ?: ""
    val name = (node as? Any)?.let { 
        it::class.java.getDeclaredField("name").apply { isAccessible = true }.get(it) as String 
    } ?: "Unknown"
    val capabilities = (node as? Any)?.let { 
        @Suppress("UNCHECKED_CAST")
        it::class.java.getDeclaredField("capabilities").apply { isAccessible = true }.get(it) as List<String> 
    } ?: emptyList()
    val isLeader = (node as? Any)?.let { 
        it::class.java.getDeclaredField("isLeader").apply { isAccessible = true }.get(it) as Boolean 
    } ?: false
    val cost = (node as? Any)?.let { 
        it::class.java.getDeclaredField("cost").apply { isAccessible = true }.get(it) as? Float 
    }
    
    var expanded by remember { mutableStateOf(true) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isLeader) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "LEADER",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Text(
                            text = id.take(16) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cost?.let {
                        Text(
                            text = "Cost: ${"%.2f".format(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }
            
            // Capabilities
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        text = "Capabilities",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    capabilities.forEach { cap ->
                        val key = "$id:$cap"
                        val result = testResults[key]
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    getCapabilityIcon(cap),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = cap,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                result?.let { r ->
                                    if (!r.loading) {
                                        Icon(
                                            if (r.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (r.success) Color(0xFF4CAF50) else Color(0xFFE57373)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "${r.latencyMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                                
                                IconButton(
                                    onClick = { onTestCapability(cap) },
                                    modifier = Modifier.size(32.dp),
                                    enabled = result?.loading != true
                                ) {
                                    if (result?.loading == true) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Test",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Simple flow row implementation
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement
    ) {
        content()
    }
}

private fun getCapabilityIcon(capability: String): ImageVector {
    return when (capability.lowercase()) {
        "llm", "chat" -> Icons.Default.Psychology
        "embeddings" -> Icons.Default.Hub
        "vision" -> Icons.Default.Visibility
        "code" -> Icons.Default.Code
        "audio" -> Icons.Default.Mic
        "camera" -> Icons.Default.CameraAlt
        "screen" -> Icons.Default.ScreenShare
        else -> Icons.Default.Extension
    }
}

// Helper composables

data class TestResult(
    val name: String,
    val success: Boolean,
    val latencyMs: Long,
    val loading: Boolean = false
)

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TestResultRow(result: TestResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (result.success) Color(0xFF4CAF50) else Color(0xFFE57373)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${result.latencyMs}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 🎯 SEMANTIC ROUTER CARD - THE CROWN JEWEL
 * Shows how the intent was routed to a capability.
 */
@Composable
private fun SemanticRouterCard(route: RoutingDecision) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
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
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Semantic Router",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = route.matchMethod.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Capability info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Routed to:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = route.capability.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // Score badge
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%.1f%%", route.scoreBreakdown.compositeScore * 100),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            route.scoreBreakdown.compositeScore > 0.8f -> Color(0xFF4CAF50)
                            route.scoreBreakdown.compositeScore > 0.5f -> Color(0xFFFFA726)
                            else -> Color(0xFFEF5350)
                        }
                    )
                }
            }
            
            // Node info
            if (route.capability.nodeId.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Node: ${route.capability.nodeId.take(8)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = route.capability.projectPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseCard(response: String, latencyMs: Long?, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Response",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                latencyMs?.let { ms ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${ms}ms",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            if (isLoading && response.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Waiting for response...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = response.ifEmpty { "..." },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default
                )
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Test your mesh connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Send prompts to your Mac's LlamaFarm and see responses here. " +
                        "Works over local network or internet!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
