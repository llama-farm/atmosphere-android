# Atmosphere SDK - Vision API

The Atmosphere SDK provides convenient vision capabilities that automatically escalate to the mesh when local confidence is low.

## Quick Start

```kotlin
// Connect to Atmosphere
val atmosphere = AtmosphereClient.connect(context)

// Detect objects from a Bitmap
val bitmap = getBitmapFromCamera()
val result = atmosphere.detectObjects(bitmap, sourceId = "camera")

if (result.success) {
    result.detections.forEach { detection ->
        println("${detection.className}: ${detection.confidence * 100}%")
        // Access bounding box
        val bbox = detection.bbox
        println("  Box: (${bbox.x1}, ${bbox.y1}) to (${bbox.x2}, ${bbox.y2})")
    }
    
    // Check if result escalated to mesh
    if (result.escalated) {
        println("Detection handled by mesh node: ${result.nodeId}")
    }
}
```

## Vision Methods

### Detection

**`detectObjects(bitmap: Bitmap, sourceId: String = "sdk"): VisionResult`**

Detect objects in a bitmap. Automatically converts to JPEG and sends to Atmosphere.

```kotlin
val result = atmosphere.detectObjects(bitmap)
val bestDetection = result.bestDetection()  // Highest confidence
val highConfidence = result.withMinConfidence(0.7f)  // Filter by threshold
```

**`detectObjectsBase64(imageBase64: String, sourceId: String = "sdk"): VisionResult`**

Detect objects from a base64-encoded image string.

```kotlin
val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
val result = atmosphere.detectObjectsBase64(base64)
```

**`captureAndDetect(facing: String = "back"): VisionResult`**

Trigger camera capture on the Atmosphere service and detect objects.

```kotlin
// Capture from back camera
val result = atmosphere.captureAndDetect("back")

// Capture from front camera
val result = atmosphere.captureAndDetect("front")
```

### Status & Configuration

**`visionStatus(): VisionStatus`**

Get current vision capability status.

```kotlin
val status = atmosphere.visionStatus()
if (status.ready) {
    println("Model: ${status.modelId} v${status.modelVersion}")
    println("Classes: ${status.numClasses}")
    println("Confidence threshold: ${status.confidenceThreshold}")
}
```

**`setVisionConfidenceThreshold(threshold: Float)`**

Set the confidence threshold for mesh escalation. When local detection confidence is below this value, the request automatically escalates to the mesh for higher-quality inference.

```kotlin
// Escalate when local confidence < 70%
atmosphere.setVisionConfidenceThreshold(0.7f)
```

### Feedback & Training

**`sendVisionFeedback(detectionId: String?, correct: Boolean, correctedLabel: String?, imageBase64: String?): Boolean`**

Send feedback on detection results to improve the model over time.

```kotlin
// Mark detection as correct
atmosphere.sendVisionFeedback(
    detectionId = null,
    correct = true,
    correctedLabel = null,
    imageBase64 = null
)

// Provide correction
atmosphere.sendVisionFeedback(
    detectionId = null,
    correct = false,
    correctedLabel = "dog",  // Should have been "dog", not "cat"
    imageBase64 = imageBase64String  // Optional: include image for retraining
)
```

## Data Classes

### VisionResult

```kotlin
data class VisionResult(
    val success: Boolean,
    val detections: List<Detection> = emptyList(),
    val model: String? = null,
    val inferenceTimeMs: Float = 0f,
    val escalated: Boolean = false,
    val nodeId: String? = null,
    val error: String? = null
)

// Helper methods
result.bestDetection()                    // Detection with highest confidence
result.withMinConfidence(0.7f)           // Filter detections >= threshold
```

### Detection

```kotlin
data class Detection(
    val className: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val inferenceTimeMs: Float = 0f
)

detection.format()  // "dog 85%"
```

### BoundingBox

```kotlin
data class BoundingBox(
    val x1: Float, y1: Float,
    val x2: Float, y2: Float
)

// Helper properties
bbox.width          // x2 - x1
bbox.height         // y2 - y1
bbox.centerX        // (x1 + x2) / 2
bbox.centerY        // (y1 + y2) / 2
bbox.area           // width * height
```

### VisionStatus

```kotlin
data class VisionStatus(
    val ready: Boolean,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val numClasses: Int = 0,
    val inputSize: Int = 0,
    val confidenceThreshold: Float = 0.7f
)
```

## How Escalation Works

1. **Local First**: Detection runs on-device using ONNX Runtime Mobile
2. **Confidence Check**: If max confidence < threshold, prepare for escalation
3. **Mesh Query**: Atmosphere finds a capable mesh peer with higher-quality models
4. **Remote Inference**: Image sent to mesh peer for re-detection
5. **Result Merge**: Best result returned with `escalated=true` flag

Benefits:
- **Fast**: Most detections complete locally in <50ms
- **Accurate**: Low-confidence cases get mesh-level quality
- **Resilient**: Works offline (local-only) or on-mesh (escalation available)
- **Efficient**: Bandwidth used only when needed

## Example: Real-Time Camera Detection

```kotlin
class VisionViewModel(application: Application) : AndroidViewModel(application) {
    private val atmosphere = AtmosphereClient.connect(application)
    
    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()
    
    suspend fun analyzeFrame(bitmap: Bitmap) {
        val result = atmosphere.detectObjects(bitmap, sourceId = "camera_live")
        if (result.success) {
            _detections.value = result.detections
            Log.i("Vision", "Found ${result.detections.size} objects (escalated: ${result.escalated})")
        }
    }
}
```

## Example: Gallery Photo Analysis

```kotlin
// Pick image from gallery
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    scope.launch {
        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
        val result = atmosphere.detectObjects(bitmap, sourceId = "gallery")
        
        result.detections.forEach { detection ->
            println("${detection.format()} at (${detection.bbox.centerX}, ${detection.bbox.centerY})")
        }
    }
}

Button(onClick = { launcher.launch("image/*") }) {
    Text("Analyze Photo")
}
```

## Error Handling

```kotlin
val result = atmosphere.detectObjects(bitmap)

when {
    !result.success -> {
        Log.e("Vision", "Detection failed: ${result.error}")
    }
    result.detections.isEmpty() -> {
        Log.i("Vision", "No objects detected")
    }
    else -> {
        result.detections.forEach { detection ->
            // Process detection
        }
    }
}
```

## Performance Tips

1. **Batch Processing**: Throttle camera frames to ~5-10 FPS for real-time detection
2. **Confidence Tuning**: Start with threshold 0.7, adjust based on your use case
3. **Source IDs**: Use descriptive sourceId for better debugging ("camera_live", "gallery", "scan")
4. **Feedback Loop**: Send corrections to improve model over time

## See Also

- [AtmosphereClient API](API.md)
- [Mesh Architecture](../docs/MESH.md)
- [Example Apps](../atmosphere-photo/)
