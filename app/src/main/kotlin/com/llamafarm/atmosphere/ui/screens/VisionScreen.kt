package com.llamafarm.atmosphere.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.llamafarm.atmosphere.vision.DetectionResult
import com.llamafarm.atmosphere.vision.VisionCapability
import com.llamafarm.atmosphere.vision.VisionModelManager
import com.llamafarm.atmosphere.vision.VisionModelMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "VisionScreen"

// Hardcoded mesh peer for now
private const val MESH_PEER_BASE_URL = "http://192.168.86.237:11451"

data class MeshModelInfo(
    val modelId: String,
    val name: String,
    val description: String,
    val classes: List<String>,
    val numClasses: Int,
    val inputSize: Int,
    val version: String,
    val fileSizeMb: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionScreen(
    visionCapability: VisionCapability,
    modelManager: VisionModelManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // Separate scope for analyzer coroutines (survives recomposition)
    val analyzerScope = remember { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    // Permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Detection state
    var isDetecting by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var detections by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var fps by remember { mutableStateOf(0f) }
    var showModelsSheet by remember { mutableStateOf(false) }

    val isReady by visionCapability.isReady.collectAsState()
    val activeModel by modelManager.activeModel.collectAsState()
    val installedModels by modelManager.installedModels.collectAsState()
    val downloadProgress by modelManager.downloadProgress.collectAsState()

    // Camera provider
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // FPS tracking
    var frameCount by remember { mutableIntStateOf(0) }
    var lastFpsTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    // Throttle: only process one frame at a time
    val isProcessingFrame = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            analyzerScope.cancel()
            analysisExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vision AI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showModelsSheet = !showModelsSheet }) {
                        Icon(Icons.Default.Settings, "Models")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasCameraPermission) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Camera, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("Camera permission required")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
            return@Scaffold
        }

        if (showModelsSheet) {
            // Models management screen
            ModelsTab(
                installedModels = installedModels,
                activeModel = activeModel,
                modelManager = modelManager,
                visionCapability = visionCapability,
                scope = scope,
                downloadProgress = downloadProgress,
                onBack = { showModelsSheet = false }
            )
            return@Scaffold
        }

        Box(
            Modifier.fillMaxSize().padding(padding)
        ) {
            // Camera preview with state tracking - use remember without mutableStateOf
            val previewView = remember { PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                Log.i(TAG, "📷 PreviewView created")
            } }
            
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            
            // Camera setup with analyzer - runs once when ready
            LaunchedEffect(useFrontCamera, hasCameraPermission, isReady) {
                if (!hasCameraPermission) {
                    Log.i(TAG, "⏸️ Camera setup skipped: no permission")
                    return@LaunchedEffect
                }
                
                if (!isReady) {
                    Log.i(TAG, "⏸️ Camera setup skipped: model not ready")
                    return@LaunchedEffect
                }
                
                Log.i(TAG, "📷 Setting up camera (front=$useFrontCamera, ready=$isReady)")
                
                try {
                    val provider = withContext(Dispatchers.IO) {
                        cameraProviderFuture.get()
                    }
                    
                    withContext(Dispatchers.Main) {
                        // Unbind all previous use cases
                        provider.unbindAll()
                        
                        val cameraSelector = if (useFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        
                        // Create preview use case
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        
                        // Create image analysis use case with analyzer
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        
                        // Set analyzer that checks isDetecting state
                        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            // Only process if detection is enabled
                            if (!isDetecting) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            
                            // Only process one frame at a time
                            if (!isProcessingFrame.compareAndSet(false, true)) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            
                            try {
                                val bytes = imageProxyToJpeg(imageProxy)
                                if (bytes != null) {
                                    try {
                                        val startTime = System.currentTimeMillis()
                                        val results = kotlinx.coroutines.runBlocking {
                                            visionCapability.detectAll(bytes, "camera_live")
                                        }
                                        val elapsed = System.currentTimeMillis() - startTime
                                        
                                        if (results.isNotEmpty()) {
                                            val best = results.maxByOrNull { it.confidence }!!
                                            Log.i(TAG, "📸 Detected ${results.size} objects: ${best.className} ${(best.confidence * 100).toInt()}% (${elapsed}ms)")
                                            // Update UI on main thread
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                detections = results
                                                frameCount++
                                                val now = System.currentTimeMillis()
                                                val e = now - lastFpsTime
                                                if (e >= 1000) {
                                                    fps = frameCount * 1000f / e
                                                    frameCount = 0
                                                    lastFpsTime = now
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Detection error: ${e.javaClass.simpleName}: ${e.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Analysis error", e)
                            } finally {
                                imageProxy.close()
                                isProcessingFrame.set(false)
                            }
                        }
                        
                        try {
                            // Bind both use cases to lifecycle
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            Log.i(TAG, "✅ Camera bound (${if (useFrontCamera) "FRONT" else "BACK"}) with analyzer attached")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Camera bind failed", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to get camera provider", e)
                }
            }

            // Bounding box overlay
            if (detections.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasW = size.width
                    val canvasH = size.height

                    for (det in detections) {
                        val bbox = det.bbox
                        // Bbox coords are normalized [0, 1] — scale directly to canvas
                        val scaleX = canvasW
                        val scaleY = canvasH

                        val left = bbox.x1 * scaleX
                        val top = bbox.y1 * scaleY
                        val right = bbox.x2 * scaleX
                        val bottom = bbox.y2 * scaleY

                        val color = when {
                            det.confidence > 0.7f -> Color.Green
                            det.confidence > 0.4f -> Color.Yellow
                            else -> Color.Red
                        }

                        // Bounding box
                        drawRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 4f)
                        )

                        // Label background
                        val labelText = "${det.className} ${(det.confidence * 100).toInt()}%"
                        drawRect(
                            color = color.copy(alpha = 0.7f),
                            topLeft = Offset(left, top - 48f),
                            size = Size((labelText.length * 20f).coerceAtMost(right - left + 40f), 48f)
                        )

                        // Label text
                        drawContext.canvas.nativeCanvas.drawText(
                            labelText,
                            left + 4f,
                            top - 14f,
                            android.graphics.Paint().apply {
                                this.color = android.graphics.Color.WHITE
                                textSize = 32f
                                isFakeBoldText = true
                                isAntiAlias = true
                            }
                        )
                    }
                }
            }

            // Info overlay (top)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = activeModel?.let { "${it.modelId} v${it.version}" } ?: "No model",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    text = if (isDetecting) "FPS: ${String.format("%.1f", fps)}" else "Stopped",
                    color = if (isDetecting) Color.Green else Color.Gray,
                    fontSize = 12.sp
                )
                if (detections.isNotEmpty()) {
                    val summary = detections.sortedByDescending { it.confidence }
                        .take(5)
                        .joinToString(" | ") { "${it.className} ${(it.confidence * 100).toInt()}%" }
                    Text(
                        text = "$summary (${detections.first().inferenceTimeMs.toInt()}ms)",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
                if (!isReady) {
                    Text("Model not loaded", color = Color.Red, fontSize = 12.sp)
                }
            }

            // Download progress overlay
            downloadProgress?.let { progress ->
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Downloading ${progress.modelId}", fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { progress.percentComplete() / 100f },
                            modifier = Modifier.width(200.dp).padding(top = 4.dp)
                        )
                    }
                }
            }

            // Bottom controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera flip
                IconButton(onClick = { useFrontCamera = !useFrontCamera }) {
                    Icon(
                        if (useFrontCamera) Icons.Default.CameraFront else Icons.Default.Camera,
                        "Flip Camera",
                        tint = Color.White
                    )
                }

                // Start/Stop detection
                FloatingActionButton(
                    onClick = {
                        isDetecting = !isDetecting
                        if (!isDetecting) {
                            detections = emptyList()
                            fps = 0f
                        }
                    },
                    containerColor = if (isDetecting) Color.Red else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (isDetecting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        if (isDetecting) "Stop" else "Start"
                    )
                }

                // Model selector
                IconButton(onClick = { showModelsSheet = true }) {
                    Icon(Icons.Default.Hub, "Models", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Convert ImageProxy (YUV_420_888) to JPEG byte array.
 */
private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
    try {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        return out.toByteArray()
    } catch (e: Exception) {
        Log.e(TAG, "YUV to JPEG failed", e)
        return null
    }
}

// ─── Models Management ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsTab(
    installedModels: List<VisionModelMetadata>,
    activeModel: VisionModelMetadata?,
    modelManager: VisionModelManager,
    visionCapability: VisionCapability,
    scope: kotlinx.coroutines.CoroutineScope,
    downloadProgress: com.llamafarm.atmosphere.vision.DownloadProgress?,
    onBack: () -> Unit
) {
    var meshModels by remember { mutableStateOf<List<MeshModelInfo>>(emptyList()) }
    var meshError by remember { mutableStateOf<String?>(null) }
    var isLoadingMesh by remember { mutableStateOf(false) }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoadingMesh = true
        try {
            val models = withContext(Dispatchers.IO) {
                val json = URL("$MESH_PEER_BASE_URL/api/vision/models").readText()
                val root = JSONObject(json)
                val arr = root.getJSONArray("models")
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    val classesArr = m.getJSONArray("classes")
                    val classes = (0 until classesArr.length()).map { classesArr.getString(it) }
                    val inputSizeArr = m.getJSONArray("input_size")
                    MeshModelInfo(
                        modelId = m.getString("model_id"),
                        name = m.getString("name"),
                        description = m.getString("description"),
                        classes = classes,
                        numClasses = m.getInt("num_classes"),
                        inputSize = inputSizeArr.getInt(0),
                        version = m.getString("version"),
                        fileSizeMb = m.getDouble("file_size_mb")
                    )
                }
            }
            meshModels = models
        } catch (e: Exception) {
            meshError = e.message
        }
        isLoadingMesh = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Download progress
            downloadProgress?.let { progress ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Downloading ${progress.modelId} v${progress.version}")
                            LinearProgressIndicator(
                                progress = { progress.percentComplete() / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text("${progress.status}", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Installed
            item {
                Text("Installed Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (installedModels.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No models installed")
                            Text("Download from mesh below", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(installedModels) { model ->
                    val isActive = model == activeModel
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors()
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.modelId, fontWeight = FontWeight.Bold)
                                        if (isActive) {
                                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                Text("ACTIVE", Modifier.padding(horizontal = 6.dp))
                                            }
                                        }
                                    }
                                    Text("v${model.version} · ${model.classMap.size} classes · ${model.inputSize}×${model.inputSize}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!isActive) {
                                    Button(onClick = {
                                        scope.launch {
                                            visionCapability.switchModel(model.modelId, model.version)
                                        }
                                    }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                        Text("Activate")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Mesh models
            item {
                Spacer(Modifier.height(16.dp))
                Text("Available from Mesh", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (isLoadingMesh) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (meshError != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Mesh unavailable", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Text(meshError ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(meshModels) { meshModel ->
                    val isInstalled = installedModels.any { it.modelId == meshModel.modelId && it.version == meshModel.version }
                    val isDownloading = downloadingModelId == meshModel.modelId

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(meshModel.name, fontWeight = FontWeight.Bold)
                                    Text(meshModel.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${meshModel.numClasses} classes · ${meshModel.inputSize}×${meshModel.inputSize} · v${meshModel.version}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isInstalled) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("INSTALLED", Modifier.padding(horizontal = 6.dp))
                                    }
                                } else if (isDownloading) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    Button(onClick = {
                                        downloadingModelId = meshModel.modelId
                                        scope.launch {
                                            val classMap = meshModel.classes.mapIndexed { i, n -> i to n }.toMap()
                                            val metadata = VisionModelMetadata(
                                                modelId = meshModel.modelId,
                                                version = meshModel.version,
                                                baseModel = "yolov8n",
                                                classMap = classMap,
                                                inputSize = meshModel.inputSize,
                                                quantized = false,
                                                fileSize = (meshModel.fileSizeMb * 1024 * 1024).toLong(),
                                                downloadUrl = "$MESH_PEER_BASE_URL/api/vision/models/${meshModel.modelId}/download",
                                                sourceNode = "mesh_peer",
                                                trainingDate = System.currentTimeMillis(),
                                                description = meshModel.description
                                            )
                                            val result = modelManager.downloadAndInstallModel(
                                                "$MESH_PEER_BASE_URL/api/vision/models/${meshModel.modelId}/download",
                                                metadata
                                            )
                                            if (result.isSuccess) {
                                                // Auto-activate and reinitialize
                                                visionCapability.switchModel(meshModel.modelId, meshModel.version)
                                            }
                                            downloadingModelId = null
                                        }
                                    }) {
                                        Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("${String.format("%.1f", meshModel.fileSizeMb)} MB")
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
