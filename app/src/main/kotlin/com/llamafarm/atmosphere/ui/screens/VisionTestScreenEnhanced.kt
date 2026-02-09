package com.llamafarm.atmosphere.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.llamafarm.atmosphere.capabilities.CameraFacing
import com.llamafarm.atmosphere.llamafarm.LlamaFarmLite
import com.llamafarm.atmosphere.vision.DetectionResult
import com.llamafarm.atmosphere.vision.VisionModelMetadata
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Enhanced Vision Test Screen with model selection and bounding box visualization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionTestScreenEnhanced(
    llamaFarmLite: LlamaFarmLite
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }
    var escalationResult by remember { mutableStateOf<DetectionResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var visionReady by remember { mutableStateOf(llamaFarmLite.isVisionReady()) }
    
    // Model selection
    var availableModels by remember { mutableStateOf<List<VisionModelMetadata>>(emptyList()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    
    // Camera facing
    var cameraFacing by remember { mutableStateOf(CameraFacing.BACK) }
    
    // Load available models
    LaunchedEffect(Unit) {
        visionReady = llamaFarmLite.isVisionReady()
        // TODO: Get list of available models from VisionCapability
        // availableModels = llamaFarmLite.getAvailableVisionModels()
    }
    
    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                selectedBitmap = bitmap
                detectionResult = null
                escalationResult = null
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = "Failed to load image: ${e.message}"
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vision Test") },
                actions = {
                    // Model selector
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.ModelTraining, contentDescription = "Select Model")
                    }
                    
                    // Refresh status
                    IconButton(onClick = { 
                        visionReady = llamaFarmLite.isVisionReady() 
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
            // Status and model info card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Vision Capability",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (visionReady) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (visionReady) Color.Green else Color.Red
                                        )
                                )
                                Text(
                                    if (visionReady) "Ready" else "Not Ready",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    
                    Divider()
                    
                    // Active model info
                    if (activeModelId != null) {
                        Text(
                            "Active Model: $activeModelId",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "No model selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                isProcessing = true
                                errorMessage = null
                                
                                // Capture from camera
                                // TODO: Implement vision capture method in LlamaFarmLite
                                // val result = llamaFarmLite.visionCaptureAndDetect(cameraFacing)
                                // if (result != null) {
                                //     detectionResult = result
                                // } else {
                                errorMessage = "Camera capture not yet implemented"
                                // }
                            } catch (e: Exception) {
                                errorMessage = "Camera error: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = visionReady && !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Camera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
                
                Button(
                    onClick = { filePickerLauncher.launch("image/*") },
                    enabled = visionReady && !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            }
            
            // Detect button
            if (selectedBitmap != null) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                isProcessing = true
                                errorMessage = null
                                escalationResult = null
                                
                                // Convert bitmap to bytes
                                val stream = ByteArrayOutputStream()
                                selectedBitmap?.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                                val imageBytes = stream.toByteArray()
                                
                                // Run detection
                                // TODO: Implement vision detect method in LlamaFarmLite
                                // val result = llamaFarmLite.visionDetect(imageBytes, "gallery")
                                // if (result != null) {
                                //     detectionResult = result
                                // } else {
                                errorMessage = "Detection not yet implemented - awaiting LlamaFarmLite integration"
                                // }
                            } catch (e: Exception) {
                                errorMessage = "Detection failed: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = visionReady && !isProcessing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isProcessing) "Detecting..." else "Detect Objects")
                }
            }
            
            // Escalate button (if confidence is low)
            if (detectionResult != null && detectionResult!!.confidence < 0.7f) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                // TODO: Manual escalation to mesh
                                errorMessage = "Escalation to mesh not yet implemented"
                            } catch (e: Exception) {
                                errorMessage = "Escalation failed: ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escalate to Mesh")
                }
            }
            
            // Error message
            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // Image with bounding boxes
            selectedBitmap?.let { bitmap ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Selected image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        
                        // Draw bounding boxes
                        detectionResult?.let { detection ->
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val bbox = detection.bbox
                                val scaleX = size.width / bitmap.width
                                val scaleY = size.height / bitmap.height
                                
                                // Draw rectangle
                                drawRect(
                                    color = Color.Green,
                                    topLeft = Offset(bbox.x1 * scaleX, bbox.y1 * scaleY),
                                    size = Size(
                                        (bbox.x2 - bbox.x1) * scaleX,
                                        (bbox.y2 - bbox.y1) * scaleY
                                    ),
                                    style = Stroke(width = 4f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Detection results card
            detectionResult?.let { detection ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Detection Result",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Divider()
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Class:")
                            Text(
                                detection.className,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Confidence:")
                            Text(
                                "${(detection.confidence * 100).toInt()}%",
                                color = if (detection.confidence >= 0.7f) 
                                    Color.Green else Color(0xFFFFA500),  // Orange
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Inference Time:")
                            Text("${detection.inferenceTimeMs.toInt()}ms")
                        }
                        
                        // Escalation status
                        if (escalationResult != null) {
                            Divider()
                            Text(
                                "Mesh Result",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Class:")
                                Text(
                                    escalationResult!!.className,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Confidence:")
                                Text(
                                    "${(escalationResult!!.confidence * 100).toInt()}%",
                                    color = Color.Green,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Model picker dialog
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("Select Vision Model") },
            text = {
                Column {
                    if (availableModels.isEmpty()) {
                        Text("No models available")
                    } else {
                        availableModels.forEach { model ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        // TODO: Switch to selected model
                                        // llamaFarmLite.switchVisionModel(model.modelId, model.version)
                                        activeModelId = model.modelId
                                        showModelPicker = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        model.modelId,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${model.classMap.size} classes · v${model.version}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text("Close")
                }
            }
        )
    }
}
