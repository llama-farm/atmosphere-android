package com.llamafarm.atmosphere.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.llamafarm.atmosphere.capabilities.CameraFacing
import com.llamafarm.atmosphere.llamafarm.LlamaFarmLite
import com.llamafarm.atmosphere.vision.DetectionResult
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Test screen for vision capabilities.
 * 
 * Features:
 * - Take photo with camera
 * - Select image from gallery/files
 * - Run vision inference
 * - Display results with bounding boxes
 * - Show model info
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionTestScreen(
    llamaFarmLite: LlamaFarmLite
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var visionReady by remember { mutableStateOf(llamaFarmLite.isVisionReady()) }
    
    // Camera facing
    var cameraFacing by remember { mutableStateOf(CameraFacing.BACK) }
    
    // Training state
    var showTrainingDialog by remember { mutableStateOf(false) }
    var trainingProgress by remember { mutableStateOf<Float?>(null) }
    var trainingMessage by remember { mutableStateOf<String?>(null) }
    
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
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = "Failed to load image: ${e.message}"
            }
        }
    }
    
    // Dataset folder picker for training
    val datasetPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { datasetUri ->
            scope.launch {
                try {
                    trainingProgress = 0f
                    trainingMessage = "Preparing dataset..."
                    
                    // Count images in dataset folder
                    val rootDir = DocumentFile.fromTreeUri(context, datasetUri)
                    var imageCount = 0
                    rootDir?.listFiles()?.forEach { classDir ->
                        if (classDir.isDirectory) {
                            imageCount += classDir.listFiles().count { 
                                it.name?.endsWith(".jpg") == true || 
                                it.name?.endsWith(".png") == true ||
                                it.name?.endsWith(".jpeg") == true
                            }
                        }
                    }
                    
                    trainingMessage = "Found $imageCount images. Starting training..."
                    trainingProgress = 0.1f
                    
                    // Call LlamaFarm training API
                    // POST /v1/vision/train with dataset_path
                    val result = llamaFarmLite.trainVisionModel(datasetUri.toString(), "custom_model_v1")
                    
                    trainingProgress = 1f
                    trainingMessage = "Training complete! New model available."
                    
                    // Show notification
                    showNotification(context, "Training Complete", "Your custom vision model is ready!")
                    
                } catch (e: Exception) {
                    trainingMessage = "Training failed: ${e.message}"
                    errorMessage = "Training failed: ${e.message}"
                } finally {
                    kotlinx.coroutines.delay(3000)
                    trainingProgress = null
                    trainingMessage = null
                    showTrainingDialog = false
                }
            }
        }
    }
    
    // Update vision ready state
    LaunchedEffect(Unit) {
        visionReady = llamaFarmLite.isVisionReady()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vision Test") },
                actions = {
                    IconButton(onClick = { showTrainingDialog = true }) {
                        Icon(Icons.Default.School, contentDescription = "Train Model")
                    }
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
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Vision Capability",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (visionReady) "Ready" else "Not Ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (visionReady) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (visionReady) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (visionReady) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxSize()
                                ) {}
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (visionReady) "Active" else "Inactive",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            
            // Error display
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { errorMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Camera capture
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            errorMessage = null
                            try {
                                val result = llamaFarmLite.captureAndDetect(cameraFacing)
                                if (result != null) {
                                    detectionResult = result
                                    // Note: We don't have the bitmap from camera capture
                                    // In a real implementation, CameraCapability should return it
                                } else {
                                    errorMessage = "Camera capture failed"
                                }
                            } catch (e: Exception) {
                                errorMessage = "Camera error: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = visionReady && !isProcessing
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Camera")
                }
                
                // Camera facing toggle
                IconButton(
                    onClick = {
                        cameraFacing = if (cameraFacing == CameraFacing.BACK) 
                            CameraFacing.FRONT 
                        else 
                            CameraFacing.BACK
                    }
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera")
                }
                
                // File picker
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Gallery")
                }
            }
            
            // Image preview
            selectedBitmap?.let { bitmap ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Selected image",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                        
                        // Detection overlay (if any)
                        // TODO: Draw bounding boxes when we have proper detection results
                    }
                }
                
                // Inference button
                if (detectionResult == null) {
                    Button(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                errorMessage = null
                                try {
                                    // Convert bitmap to bytes
                                    val stream = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                                    val imageBytes = stream.toByteArray()
                                    
                                    val result = llamaFarmLite.detectObjects(imageBytes, "gallery")
                                    if (result != null) {
                                        detectionResult = result
                                    } else {
                                        errorMessage = "Detection failed"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Inference error: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = visionReady && !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Processing...")
                        } else {
                            Icon(Icons.Default.Psychology, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Run Detection")
                        }
                    }
                }
            }
            
            // Detection results
            detectionResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
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
                                "Detection Result",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    detectionResult = null
                                    selectedBitmap = null
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                        
                        HorizontalDivider()
                        
                        ResultRow("Class", result.className)
                        ResultRow(
                            "Confidence", 
                            "${(result.confidence * 100).toInt()}%"
                        )
                        ResultRow(
                            "Bounding Box",
                            "x1: ${String.format("%.2f", result.bbox.x1)}, " +
                            "y1: ${String.format("%.2f", result.bbox.y1)}, " +
                            "x2: ${String.format("%.2f", result.bbox.x2)}, " +
                            "y2: ${String.format("%.2f", result.bbox.y2)}"
                        )
                        ResultRow(
                            "Inference Time",
                            "${String.format("%.0f", result.inferenceTimeMs)} ms"
                        )
                        
                        // Confidence indicator
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { result.confidence },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Confidence: ${(result.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
            
            // Placeholder when no image
            if (selectedBitmap == null && detectionResult == null && !isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Text(
                            "No image selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Take a photo or select from gallery",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    // Training dialog
    if (showTrainingDialog) {
        TrainingDialog(
            onDismiss = { showTrainingDialog = false },
            onSelectDataset = { datasetPickerLauncher.launch(null) },
            trainingProgress = trainingProgress,
            trainingMessage = trainingMessage
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainingDialog(
    onDismiss: () -> Unit,
    onSelectDataset: () -> Unit,
    trainingProgress: Float?,
    trainingMessage: String?
) {
    AlertDialog(
        onDismissRequest = { if (trainingProgress == null) onDismiss() },
        icon = { Icon(Icons.Default.School, contentDescription = null) },
        title = { Text("Train Custom Vision Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (trainingProgress == null) {
                    Text("Select a dataset folder to train a custom vision model.")
                    
                    Text(
                        "Dataset structure:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "dataset/\n" +
                        "  class1/\n" +
                        "    img1.jpg\n" +
                        "    img2.jpg\n" +
                        "  class2/\n" +
                        "    img1.jpg\n" +
                        "    img2.jpg",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { trainingProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    trainingMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (trainingProgress == null) {
                Button(onClick = onSelectDataset) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select Dataset")
                }
            }
        },
        dismissButton = {
            if (trainingProgress == null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun showNotification(context: android.content.Context, title: String, message: String) {
    // Simple notification implementation
    // In a real app, would use NotificationManager
    android.widget.Toast.makeText(context, "$title: $message", android.widget.Toast.LENGTH_LONG).show()
}
