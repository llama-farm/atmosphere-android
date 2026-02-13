package com.llamafarm.atmosphere.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.service.AtmosphereService
import com.llamafarm.atmosphere.service.*
import com.llamafarm.atmosphere.util.QRCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshManagementScreen(
    service: AtmosphereService?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var meshInfo by remember { mutableStateOf<MeshInfo?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var inviteQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var inviteToken by remember { mutableStateOf<String?>(null) }
    
    // Load mesh info
    LaunchedEffect(service) {
        meshInfo = service?.getMeshInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Management") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current Mesh Info Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Current Mesh",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (meshInfo != null) {
                        MeshInfoRow("Mesh ID", meshInfo!!.meshId.take(16) + "...")
                        MeshInfoRow("Mode", meshInfo!!.mode.uppercase())
                        MeshInfoRow("Connected Peers", meshInfo!!.peerCount.toString())
                        
                        // Show peer list
                        if (meshInfo!!.peers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Peers:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            meshInfo!!.peers.forEach { peer ->
                                Text(
                                    text = "• ${peer.peerId.take(12)}... (${peer.transports.firstOrNull() ?: "unknown"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Not connected to mesh",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Action Buttons
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create New Mesh")
            }
            
            OutlinedButton(
                onClick = { /* TODO: Open QR scanner */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Join Mesh (Scan QR)")
            }
            
            OutlinedButton(
                onClick = { 
                    scope.launch {
                        val token = service?.generateInvite()
                        if (token != null) {
                            inviteToken = token.toBase64()
                            // Generate QR code in background
                            withContext(Dispatchers.Default) {
                                inviteQrBitmap = QRCodeGenerator.generateQRCode(token.toBase64())
                            }
                            showInviteDialog = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = meshInfo != null
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Invite")
            }
            
            OutlinedButton(
                onClick = {
                    service?.leaveMesh()
                    meshInfo = service?.getMeshInfo()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = meshInfo != null,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Leave Mesh")
            }
        }
    }
    
    // Create Mesh Dialog
    if (showCreateDialog) {
        var bigLlamaUrl by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Mesh") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will create a new private mesh with unique credentials.")
                    OutlinedTextField(
                        value = bigLlamaUrl,
                        onValueChange = { bigLlamaUrl = it },
                        label = { Text("BigLlama URL (optional)") },
                        placeholder = { Text("wss://bigllama.example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val token = service?.createMesh(
                            bigLlamaUrl = bigLlamaUrl.takeIf { it.isNotBlank() }
                        )
                        if (token != null) {
                            inviteToken = token.toBase64()
                            withContext(Dispatchers.Default) {
                                inviteQrBitmap = QRCodeGenerator.generateQRCode(token.toBase64())
                            }
                            showCreateDialog = false
                            showInviteDialog = true
                            meshInfo = service?.getMeshInfo()
                        }
                    }
                }) {
                    Text("Create & Show Invite")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Share Invite Dialog
    if (showInviteDialog && inviteToken != null) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Mesh Invite") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Scan this QR code or share the token:",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    
                    // QR Code
                    if (inviteQrBitmap != null) {
                        Image(
                            bitmap = inviteQrBitmap!!.asImageBitmap(),
                            contentDescription = "Invite QR Code",
                            modifier = Modifier
                                .size(256.dp)
                                .padding(8.dp)
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    
                    // Token text (truncated)
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = inviteToken!!.take(32) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    // Share via Android share sheet
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Join my Atmosphere mesh:\n$inviteToken")
                        putExtra(Intent.EXTRA_SUBJECT, "Atmosphere Mesh Invite")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Invite"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInviteDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun MeshInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
