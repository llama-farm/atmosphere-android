package com.llamafarm.atmosphere.photo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val TAG = "PhotoDemo"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF81C784),
                    primaryContainer = Color(0xFF2E7D32),
                    secondary = Color(0xFF64B5F6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    surfaceVariant = Color(0xFF2A2A2A)
                )
            ) {
                PhotoScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(viewModel: PhotoViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val isConnected by viewModel.isConnected.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val currentDetection by viewModel.currentDetection.collectAsState()
    val detectionHistory by viewModel.detectionHistory.collectAsState()
    val isDetecting by viewModel.isDetecting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
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
    
    var showHistory by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    
    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    
                    if (bitmap != null) {
                        viewModel.detectObjects(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load image from gallery", e)
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Atmosphere Vision", fontSize = 18.sp)
                        Text(
                            connectionStatus,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (isConnected) 
                                MaterialTheme.colorScheme.primary
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                },
                actions = {
                    if (detectionHistory.isNotEmpty()) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text("${detectionHistory.size}")
                                }
                            }
                        ) {
                            IconButton(onClick = { showHistory = !showHistory }) {
                                Icon(Icons.Default.History, "History")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            if (!hasCameraPermission) {
                // Camera permission required
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Camera,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Camera permission required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            } else if (currentDetection != null) {
                // Detection result view
                DetectionResultView(
                    detection = currentDetection!!,
                    onClose = { viewModel.clearCurrentDetection() }
                )
            } else if (showHistory) {
                // History view
                HistoryView(
                    history = detectionHistory,
                    onItemClick = { viewModel.viewHistoryItem(it) },
                    onClose = { showHistory = false }
                )
            } else {
                // Camera preview
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                previewView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Setup camera
                    LaunchedEffect(previewView, hasCameraPermission) {
                        val view = previewView ?: return@LaunchedEffect
                        if (!hasCameraPermission) return@LaunchedEffect
                        
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        val provider = withContext(Dispatchers.IO) {
                            cameraProviderFuture.get()
                        }
                        
                        withContext(Dispatchers.Main) {
                            provider.unbindAll()
                            
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(view.surfaceProvider)
                            }
                            
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCapture = capture
                            
                            try {
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                                Log.i(TAG, "✅ Camera bound")
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera bind failed", e)
                            }
                        }
                    }
                    
                    // Bottom controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Gallery button
                        FloatingActionButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, "Gallery")
                        }
                        
                        // Capture button
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConnected && !isDetecting)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = isConnected && !isDetecting) {
                                    val capture = imageCapture ?: return@clickable
                                    val executor = Executors.newSingleThreadExecutor()
                                    
                                    capture.takePicture(
                                        executor,
                                        object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(image: ImageProxy) {
                                                scope.launch {
                                                    try {
                                                        val bitmap = imageProxyToBitmap(image)
                                                        if (bitmap != null) {
                                                            viewModel.detectObjects(bitmap)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Capture failed", e)
                                                    } finally {
                                                        image.close()
                                                    }
                                                }
                                            }
                                            
                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e(TAG, "Capture error", exception)
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isDetecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    Icons.Default.Camera,
                                    "Capture",
                                    modifier = Modifier.size(36.dp),
                                    tint = if (isConnected) 
                                        MaterialTheme.colorScheme.onPrimary
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            // Error snackbar
            errorMessage?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
fun DetectionResultView(
    detection: Detection,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Image with bounding boxes
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Decode and display image
                val imageBytes = Base64.decode(detection.imageBase64, Base64.NO_WRAP)
                val bitmap = remember(detection.id) {
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
                
                if (bitmap != null) {
                    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val containerWidth = constraints.maxWidth.toFloat()
                        val containerHeight = constraints.maxHeight.toFloat()
                        val containerAspectRatio = containerWidth / containerHeight
                        
                        val (displayWidth, displayHeight) = if (imageAspectRatio > containerAspectRatio) {
                            // Image is wider
                            containerWidth to (containerWidth / imageAspectRatio)
                        } else {
                            // Image is taller
                            (containerHeight * imageAspectRatio) to containerHeight
                        }
                        
                        val offsetX = (containerWidth - displayWidth) / 2f
                        val offsetY = (containerHeight - displayHeight) / 2f
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(
                                    x = with(LocalDensity.current) { offsetX.toDp() },
                                    y = with(LocalDensity.current) { offsetY.toDp() }
                                )
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(
                                    width = with(LocalDensity.current) { displayWidth.toDp() },
                                    height = with(LocalDensity.current) { displayHeight.toDp() }
                                )
                            )
                            
                            // Bounding boxes overlay
                            Canvas(
                                modifier = Modifier.size(
                                    width = with(LocalDensity.current) { displayWidth.toDp() },
                                    height = with(LocalDensity.current) { displayHeight.toDp() }
                                )
                            ) {
                                val scaleX = displayWidth / bitmap.width
                                val scaleY = displayHeight / bitmap.height
                                
                                for (det in detection.detections) {
                                    val bbox = det.bbox
                                    val left = bbox.x1 * scaleX
                                    val top = bbox.y1 * scaleY
                                    val right = bbox.x2 * scaleX
                                    val bottom = bbox.y2 * scaleY
                                    
                                    val color = when {
                                        det.confidence > 0.7f -> Color(0xFF4CAF50)
                                        det.confidence > 0.4f -> Color(0xFFFFC107)
                                        else -> Color(0xFFF44336)
                                    }
                                    
                                    // Box
                                    drawRect(
                                        color = color,
                                        topLeft = Offset(left, top),
                                        size = Size(right - left, bottom - top),
                                        style = Stroke(width = 4f)
                                    )
                                    
                                    // Label background
                                    val labelText = "${det.className} ${(det.confidence * 100).toInt()}%"
                                    drawRect(
                                        color = color.copy(alpha = 0.85f),
                                        topLeft = Offset(left, max(top - 48f, 0f)),
                                        size = Size(min((labelText.length * 18f), right - left), 48f)
                                    )
                                    
                                    // Label text
                                    drawContext.canvas.nativeCanvas.drawText(
                                        labelText,
                                        left + 8f,
                                        max(top - 12f, 36f),
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
                    }
                }
            }
            
            // Detection info panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Detections (${detection.detections.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Metadata
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip(label = "${detection.latency}ms")
                        if (detection.escalated) {
                            Chip(label = "Mesh", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Chip(label = "Local", color = Color(0xFF4CAF50))
                        }
                        detection.nodeId?.let {
                            Chip(label = it.take(8))
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // Detection list
                    detection.detections.forEach { det ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                det.className,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${(det.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryView(
    history: List<Detection>,
    onItemClick: (Detection) -> Unit,
    onClose: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "History (${history.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            }
            
            // List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history) { detection ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onItemClick(detection) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Thumbnail
                            val imageBytes = Base64.decode(detection.imageBase64, Base64.NO_WRAP)
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(imageBytes)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            
                            // Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${detection.detections.size} objects",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    detection.detections.joinToString(", ") { it.className },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Chip(label = "${detection.latency}ms", small = true)
                                    if (detection.escalated) {
                                        Chip(label = "Mesh", color = MaterialTheme.colorScheme.primary, small = true)
                                    }
                                }
                            }
                            
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(
    label: String,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    small: Boolean = false
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(if (small) 8.dp else 12.dp),
        tonalElevation = 0.dp
    ) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (small) 6.dp else 8.dp,
                vertical = if (small) 2.dp else 4.dp
            ),
            fontSize = if (small) 10.sp else 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Convert ImageProxy to Bitmap.
 */
private suspend fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? = withContext(Dispatchers.Default) {
    try {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        
        // Rotate if needed
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != 0 && bitmap != null) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
        null
    }
}
