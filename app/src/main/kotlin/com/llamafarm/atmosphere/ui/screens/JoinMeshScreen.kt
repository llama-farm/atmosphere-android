package com.llamafarm.atmosphere.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "JoinMeshScreen"

/**
 * Data class representing parsed mesh join information.
 * Supports both legacy single-endpoint and new multi-endpoint formats.
 */
data class MeshJoinInfo(
    val endpoint: String,        // Primary/legacy endpoint
    val token: String,           // Legacy: string token. V2: JSON-encoded token object
    val meshName: String? = null,
    val endpoints: Map<String, String>? = null,  // Multi-path: local, public, relay
    val tokenObject: org.json.JSONObject? = null,  // V2: Full signed token object
    val meshPublicKey: String? = null  // V2: For signature verification
) {
    /**
     * Get MeshEndpoints for multi-path connection.
     */
    fun toMeshEndpoints(): com.llamafarm.atmosphere.network.MeshEndpoints {
        return if (endpoints != null) {
            com.llamafarm.atmosphere.network.MeshEndpoints(
                local = endpoints["local"],
                public = endpoints["public"],
                relay = endpoints["relay"]
            )
        } else {
            // Legacy single endpoint
            com.llamafarm.atmosphere.network.MeshEndpoints.fromSingle(endpoint)
        }
    }
    
    /**
     * Check if this is a v2 signed token.
     */
    val isSignedToken: Boolean
        get() = tokenObject != null
}

/**
 * Screen for joining a mesh network via QR code or manual entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinMeshScreen(
    onJoinMesh: (endpoint: String, token: String, tokenObject: org.json.JSONObject?, endpoints: Map<String, String>?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showScanner by remember { mutableStateOf(false) }
    var manualEndpoint by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isJoining by remember { mutableStateOf(false) }
    
    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showScanner = true
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join Mesh") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error message
            errorMessage?.let { error ->
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
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            if (showScanner && hasCameraPermission) {
                // QR Scanner View
                QRScannerView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    onQRCodeScanned = { qrContent ->
                        showScanner = false
                        parseQRCode(qrContent)?.let { joinInfo ->
                            manualEndpoint = joinInfo.endpoint
                            manualToken = joinInfo.token
                            // Auto-join with signed token and all endpoints for fallback
                            isJoining = true
                            onJoinMesh(joinInfo.endpoint, joinInfo.token, joinInfo.tokenObject, joinInfo.endpoints)
                        } ?: run {
                            errorMessage = "Invalid QR code format"
                        }
                    }
                )
                
                TextButton(
                    onClick = { showScanner = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Cancel Scan")
                }
            } else {
                // QR Scan Button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (hasCameraPermission) {
                            showScanner = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Scan QR Code",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Scan a mesh invitation QR code",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                // Divider with "OR"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = "  OR  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
                
                // Manual Entry
                Text(
                    text = "Manual Entry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                OutlinedTextField(
                    value = manualEndpoint,
                    onValueChange = { 
                        manualEndpoint = it
                        errorMessage = null
                    },
                    label = { Text("Endpoint URL") },
                    placeholder = { Text("ws://192.168.1.100:11451/api/ws") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Link, contentDescription = null)
                    }
                )
                
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { 
                        manualToken = it
                        errorMessage = null
                    },
                    label = { Text("Join Token") },
                    placeholder = { Text("Enter invitation token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (manualEndpoint.isNotBlank() && manualToken.isNotBlank()) {
                                isJoining = true
                                onJoinMesh(manualEndpoint, manualToken, null, null)  // Manual entry
                            }
                        }
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Key, contentDescription = null)
                    }
                )
                
                // Paste from clipboard hint
                Text(
                    text = "Paste an atmosphere:// link to auto-fill",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                
                Spacer(Modifier.weight(1f))
                
                // Join Button
                Button(
                    onClick = {
                        if (manualEndpoint.isBlank()) {
                            errorMessage = "Please enter an endpoint URL"
                            return@Button
                        }
                        if (manualToken.isBlank()) {
                            errorMessage = "Please enter a join token"
                            return@Button
                        }
                        isJoining = true
                        errorMessage = null
                        onJoinMesh(manualEndpoint, manualToken, null, null)  // Manual entry
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isJoining && manualEndpoint.isNotBlank() && manualToken.isNotBlank()
                ) {
                    if (isJoining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Icon(Icons.Default.Hub, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isJoining) "Joining..." else "Join Mesh")
                }
            }
        }
    }
}

/**
 * QR Code Scanner View using CameraX and ML Kit.
 */
@Composable
private fun QRScannerView(
    modifier: Modifier = Modifier,
    onQRCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scannedOnce by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    
                    val barcodeScanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                    )
                    
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(android.util.Size(1920, 1080)) // Higher res for dense QR
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                @androidx.camera.core.ExperimentalGetImage
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !scannedOnce) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            barcodes.firstOrNull()?.rawValue?.let { value ->
                                                if (!scannedOnce) {
                                                    scannedOnce = true
                                                    onQRCodeScanned(value)
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                    
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Scanner overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp)
                    )
            )
        }
        
        // Instruction text
        Text(
            text = "Point camera at QR code",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Data class for comprehensive invite (v1 format)
 */
data class ComprehensiveInvite(
    val version: Int,
    val token: String,
    val mesh: JoinMeshInfo,
    val endpoints: Map<String, String>,
    val capabilities: List<String>,
    val network: NetworkInfo,
    val expires: Long,
    val created: Long
)

data class JoinMeshInfo(
    val id: String,
    val name: String,
    val founder: String,
    val founderId: String
)

data class NetworkInfo(
    val localIp: String?,
    val publicIp: String?,
    val nat: Boolean
)

/**
 * Parse a QR code or atmosphere:// URL into MeshJoinInfo.
 * 
 * Supported formats:
 * - COMPREHENSIVE: atmosphere://join/{base64_encoded_json} (full invite with all info)
 * - Multi-path: atmosphere://join?token=XXX&mesh=YYY&endpoints={"local":"ws://...","public":"ws://..."}
 * - Legacy: atmosphere://join?endpoint=ws://...&token=XXX
 * - JSON: {"endpoint":"ws://...","token":"XXX","endpoints":{...}}
 * - Plain URL: ws://192.168.1.100:11451/api/ws?token=XXX
 */
private fun parseQRCode(content: String): MeshJoinInfo? {
    return try {
        when {
            // atmosphere:// URL scheme
            content.startsWith("atmosphere://") -> {
                val uri = Uri.parse(content)
                
                // NEW: Comprehensive base64-encoded format: atmosphere://join/{base64}
                val path = uri.path
                if (path != null && path.startsWith("/") && !path.contains("?")) {
                    val base64Data = path.removePrefix("/")
                    if (base64Data.isNotEmpty() && !base64Data.contains("=") || base64Data.endsWith("=")) {
                        try {
                            val jsonBytes = android.util.Base64.decode(base64Data, android.util.Base64.URL_SAFE)
                            val jsonStr = String(jsonBytes, Charsets.UTF_8)
                            val invite = org.json.JSONObject(jsonStr)
                            
                            val version = invite.optInt("v", 1)
                            Log.i(TAG, "Parsed comprehensive invite v$version")
                            
                            // V2 uses short keys for smaller QR codes
                            val meshObj = if (version >= 2) invite.getJSONObject("m") else invite.getJSONObject("mesh")
                            val meshName = if (version >= 2) meshObj.getString("n") else meshObj.getString("name")
                            val meshId = if (version >= 2) meshObj.getString("i") else meshObj.getString("id")
                            val founder = if (version >= 2) meshObj.optString("f", null) else meshObj.optString("founder", null)
                            val meshPublicKey = if (version >= 2) meshObj.optString("pk", null) else meshObj.optString("public_key", null)
                            
                            // V2 has token as object, V1 has token as string
                            val tokenObj: org.json.JSONObject?
                            val tokenStr: String
                            if (version >= 2) {
                                tokenObj = invite.getJSONObject("t")
                                tokenStr = tokenObj.toString()
                            } else {
                                tokenObj = null
                                tokenStr = invite.getString("token")
                            }
                            
                            val endpointsJson = if (version >= 2) invite.getJSONObject("e") else invite.getJSONObject("endpoints")
                            val endpointsMap = mutableMapOf<String, String>()
                            endpointsJson.keys().forEach { key ->
                                val value = endpointsJson.optString(key, null)
                                if (value != null && value.isNotEmpty()) {
                                    endpointsMap[key] = value
                                }
                            }
                            
                            val capabilities = mutableListOf<String>()
                            val capsKey = if (version >= 2) "c" else "capabilities"
                            invite.optJSONArray(capsKey)?.let { capsArray ->
                                for (i in 0 until capsArray.length()) {
                                    capabilities.add(capsArray.getString(i))
                                }
                            }
                            
                            // Prefer relay for mobile (local IPs often unreachable)
                            val primaryEndpoint = endpointsMap["relay"]
                                ?: endpointsMap["local"]
                                ?: endpointsMap["public"]
                                ?: return null
                            
                            Log.i(TAG, "Joining mesh '$meshName' (founded by $founder)")
                            Log.i(TAG, "Endpoints: $endpointsMap")
                            Log.i(TAG, "Token v$version: ${if (tokenObj != null) "signed" else "legacy"}")
                            
                            return MeshJoinInfo(
                                endpoint = primaryEndpoint,
                                token = tokenStr,
                                meshName = meshName,
                                endpoints = endpointsMap,
                                tokenObject = tokenObj,
                                meshPublicKey = meshPublicKey
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse base64 invite, trying query params", e)
                        }
                    }
                }
                
                // Multi-path format with query params
                val token = uri.getQueryParameter("token") ?: return null
                val meshName = uri.getQueryParameter("mesh") ?: uri.getQueryParameter("name")
                
                // Try to parse multi-path endpoints (new format)
                val endpointsParam = uri.getQueryParameter("endpoints")
                if (endpointsParam != null) {
                    try {
                        val endpointsJson = org.json.JSONObject(
                            java.net.URLDecoder.decode(endpointsParam, "UTF-8")
                        )
                        val endpointsMap = mutableMapOf<String, String>()
                        endpointsJson.keys().forEach { key ->
                            val value = endpointsJson.optString(key, null)
                            if (value != null && value.isNotEmpty()) {
                                endpointsMap[key] = value
                            }
                        }
                        
                        // Use local or public as primary endpoint for legacy support
                        val primaryEndpoint = endpointsMap["local"] 
                            ?: endpointsMap["public"] 
                            ?: return null
                        
                        Log.i(TAG, "Parsed multi-path endpoints: $endpointsMap")
                        return MeshJoinInfo(
                            endpoint = primaryEndpoint,
                            token = token,
                            meshName = meshName,
                            endpoints = endpointsMap
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse endpoints JSON, falling back to legacy", e)
                    }
                }
                
                // Legacy single endpoint format
                val endpoint = uri.getQueryParameter("endpoint") ?: return null
                MeshJoinInfo(endpoint, token, meshName)
            }
            
            // JSON format
            content.startsWith("{") -> {
                val json = org.json.JSONObject(content)
                val token = json.getString("token")
                val meshName = json.optString("mesh_name", null)
                
                // Check for multi-path endpoints
                val endpointsJson = json.optJSONObject("endpoints")
                if (endpointsJson != null) {
                    val endpointsMap = mutableMapOf<String, String>()
                    endpointsJson.keys().forEach { key ->
                        val value = endpointsJson.optString(key, null)
                        if (value != null && value.isNotEmpty()) {
                            endpointsMap[key] = value
                        }
                    }
                    val primaryEndpoint = endpointsMap["local"] 
                        ?: endpointsMap["public"] 
                        ?: json.getString("endpoint")
                    return MeshJoinInfo(primaryEndpoint, token, meshName, endpointsMap)
                }
                
                // Legacy format
                val endpoint = json.getString("endpoint")
                MeshJoinInfo(endpoint, token, meshName)
            }
            
            // Plain WebSocket URL with token
            content.startsWith("ws://") || content.startsWith("wss://") -> {
                val uri = Uri.parse(content)
                val token = uri.getQueryParameter("token") ?: return null
                val endpoint = content.substringBefore("?")
                MeshJoinInfo(endpoint, token)
            }
            
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse QR code: $content", e)
        null
    }
}
