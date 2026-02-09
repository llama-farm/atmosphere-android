package com.llamafarm.atmosphere.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.*
import com.llamafarm.atmosphere.capabilities.CameraCapability
import com.llamafarm.atmosphere.capabilities.SnapshotRequest
import com.llamafarm.atmosphere.capabilities.SnapshotResult
import com.llamafarm.atmosphere.capabilities.CameraFacing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "VisionCapability"

/**
 * Detection result from on-device inference.
 */
data class DetectionResult(
    val className: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val inferenceTimeMs: Float
)

/**
 * Vision capability for Atmosphere mesh using ONNX Runtime Mobile.
 * 
 * Runs local on-device inference with YOLO models.
 * If confidence is below threshold, escalates to mesh peers for better detection.
 */
class VisionCapability(
    private val context: Context,
    private val nodeId: String,
    private val cameraCapability: CameraCapability,
    val modelManager: VisionModelManager? = null
) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Model manager — use injected or create own
    private val _modelManager = modelManager ?: VisionModelManager(context)
    
    // ONNX Runtime session
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var sessionLock = Any()
    
    // Model metadata
    private var inputSize = 320
    private var numClasses = 80
    private var classNames: List<String> = emptyList()
    
    // Confidence threshold for escalation
    private var confidenceThreshold = 0.7f
    private val nmsThreshold = 0.45f
    
    // Detection results flow
    private val _detections = MutableSharedFlow<DetectionResult>()
    val detections: SharedFlow<DetectionResult> = _detections.asSharedFlow()
    
    // Escalation events (for UI feedback)
    private val _escalations = MutableSharedFlow<EscalationEnvelope>()
    val escalations: SharedFlow<EscalationEnvelope> = _escalations.asSharedFlow()
    
    // Capability status
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    
    // Mesh sender callback
    private var meshSender: ((JSONObject) -> Unit)? = null
    
    init {
        Log.i(TAG, "🔧 VisionCapability init — nodeId=$nodeId")
        // Initialize ONNX Runtime environment
        ortEnv = OrtEnvironment.getEnvironment()
        Log.i(TAG, "🔧 ONNX env created, modelManager has ${_modelManager.installedModels.value.size} models, active=${_modelManager.activeModel.value?.modelId}")
        
        // Initialize model
        scope.launch {
            Log.i(TAG, "🔧 initializeModel starting...")
            initializeModel()
            Log.i(TAG, "🔧 initializeModel done, isReady=${_isReady.value}")
        }
    }
    
    /**
     * Initialize the vision model.
     */
    private suspend fun initializeModel() = withContext(Dispatchers.IO) {
        try {
            val modelPath = _modelManager.getActiveModelPath()
            val metadata = _modelManager.activeModel.value
            
            if (modelPath == null || metadata == null) {
                Log.w(TAG, "No active model available")
                _isReady.value = false
                return@withContext
            }
            
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: $modelPath")
                _isReady.value = false
                return@withContext
            }
            
            synchronized(sessionLock) {
                // Close existing session
                ortSession?.close()
                
                // Load ONNX model
                val env = ortEnv ?: throw Exception("ONNX Runtime not initialized")
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                
                ortSession = env.createSession(modelPath, sessionOptions)
                
                // Load metadata
                inputSize = metadata.inputSize
                numClasses = metadata.classMap.size
                classNames = (0 until numClasses).map { metadata.classMap[it] ?: "class_$it" }
                
                Log.i(TAG, "✅ ONNX model loaded: ${metadata.modelId} v${metadata.version}")
                Log.i(TAG, "   Input size: ${inputSize}x$inputSize")
                Log.i(TAG, "   Classes: $numClasses")
                Log.i(TAG, "   Model file: ${modelFile.length() / 1024}KB")
                
                _isReady.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model", e)
            _isReady.value = false
        }
    }
    
    /**
     * Detect objects in a frame. Returns the best detection.
     */
    suspend fun detect(imageBytes: ByteArray, sourceId: String = "camera"): DetectionResult? {
        return detectAll(imageBytes, sourceId).maxByOrNull { it.confidence }
    }
    
    /**
     * Detect ALL objects in a frame. Returns all detections after NMS.
     */
    suspend fun detectAll(imageBytes: ByteArray, sourceId: String = "camera"): List<DetectionResult> {
        if (!_isReady.value) {
            Log.w(TAG, "Vision capability not ready")
            return emptyList()
        }
        
        return withContext(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                
                // Decode image
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: throw Exception("Failed to decode image")
                
                // Run inference
                val results = runInference(bitmap)
                
                val inferenceTime = (System.currentTimeMillis() - startTime).toFloat()
                
                if (results.isNotEmpty()) {
                    val detections = results.map { it.copy(inferenceTimeMs = inferenceTime) }
                    
                    // Emit best detection
                    detections.maxByOrNull { it.confidence }?.let { _detections.emit(it) }
                    
                    // Check if best detection should escalate
                    val best = detections.maxByOrNull { it.confidence }
                    if (best != null && best.confidence < confidenceThreshold) {
                        escalateToMesh(imageBytes, sourceId, best)
                    }
                    
                    detections
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed", e)
                emptyList()
            }
        }
    }
    
    /**
     * Run YOLO inference using ONNX Runtime.
     * Returns ALL detections after NMS (not just the best one).
     */
    private fun runInference(bitmap: Bitmap): List<DetectionResult> {
        synchronized(sessionLock) {
            val session = ortSession ?: return emptyList()
            
            try {
                // Preprocess: resize and normalize
                val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
                val inputTensor = bitmapToFloatBuffer(resized)
                
                // Create input tensor
                val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
                val inputName = session.inputNames.iterator().next()
                val tensor = OnnxTensor.createTensor(ortEnv, inputTensor, shape)
                
                // Run inference
                val inputs = mapOf(inputName to tensor)
                val outputs = session.run(inputs)
                
                // Get output tensor info
                val outputTensor = outputs[0]
                val outputInfo = outputTensor.info as? ai.onnxruntime.TensorInfo
                val outputShape = outputInfo?.shape
                
                // Parse YOLO output — handle multiple formats
                val rawValue = outputTensor.value
                val detections = parseYoloOutputRobust(rawValue, outputShape, bitmap.width, bitmap.height)
                
                // Clean up
                tensor.close()
                outputs.close()
                
                return detections
                
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                return emptyList()
            }
        }
    }
    
    /**
     * Convert bitmap to float buffer for ONNX input (CHW format, normalized to [0,1]).
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        // Convert to CHW format: [C, H, W] with normalization [0, 1]
        for (c in 0..2) {
            for (h in 0 until inputSize) {
                for (w in 0 until inputSize) {
                    val pixel = pixels[h * inputSize + w]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f   // G
                        2 -> (pixel and 0xFF) / 255.0f           // B
                        else -> 0f
                    }
                    buffer.put(value)
                }
            }
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * Sigmoid function for raw logits.
     */
    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))
    
    /**
     * Robust YOLO output parser — handles multiple output formats:
     * - YOLOv8: [1, 84, 8400] (features × predictions)
     * - YOLOv5: [1, 25200, 85] (predictions × features, with objectness)
     * - Flat: [1, N] (need to reshape)
     * 
     * Also handles both sigmoid'd and raw logit outputs.
     */
    private fun parseYoloOutputRobust(
        rawValue: Any?,
        outputShape: LongArray?,
        imageWidth: Int, 
        imageHeight: Int
    ): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        
        try {
            Log.d(TAG, "🔍 Raw output type: ${rawValue?.javaClass?.name}, shape: ${outputShape?.toList()}")
            
            // Extract the 2D float array from the 3D output [1, X, Y] → [X, Y]
            val data2d: Array<FloatArray> = when (rawValue) {
                // [1, X, Y] → 3D float array
                is Array<*> -> {
                    val first = rawValue[0]
                    when (first) {
                        is Array<*> -> {
                            if (first.isNotEmpty() && first[0] is FloatArray) {
                                @Suppress("UNCHECKED_CAST")
                                first as Array<FloatArray>
                            } else {
                                Log.e(TAG, "🔍 3D array but inner not FloatArray: ${first[0]?.javaClass?.name}")
                                return detections
                            }
                        }
                        is FloatArray -> {
                            // [1, X] → 2D, unlikely for YOLO but handle it
                            @Suppress("UNCHECKED_CAST")
                            rawValue as Array<FloatArray>
                        }
                        else -> {
                            Log.e(TAG, "🔍 Unexpected inner type: ${first?.javaClass?.name}")
                            return detections
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "🔍 Unexpected raw value type: ${rawValue?.javaClass?.name}")
                    return detections
                }
            }
            
            val dim0 = data2d.size
            val dim1 = if (data2d.isNotEmpty()) data2d[0].size else 0
            
            // Determine format:
            // YOLOv8: dim0 = numClasses+4 (e.g. 84), dim1 = numPredictions (e.g. 8400)
            // YOLOv5: dim0 = numPredictions (e.g. 25200), dim1 = numClasses+5 (e.g. 85)
            val isV8Format = dim0 == (numClasses + 4) // 84 for COCO
            val isV5Format = dim1 == (numClasses + 5) // 85 for COCO
            
            val candidates = mutableListOf<DetectionResult>()
            
            if (isV8Format) {
                // YOLOv8: [numClasses+4, numPredictions] — transposed
                val numPreds = dim1
                Log.d(TAG, "🔍 YOLOv8: $numPreds predictions")
                
                for (i in 0 until numPreds) {
                    val xCenter = data2d[0][i]
                    val yCenter = data2d[1][i]
                    val w = data2d[2][i]
                    val h = data2d[3][i]
                    
                    // Find best class
                    var maxScore = -Float.MAX_VALUE
                    var maxClassIdx = 0
                    
                    for (c in 0 until numClasses) {
                        val raw = data2d[4 + c][i]
                        if (raw > maxScore) {
                            maxScore = raw
                            maxClassIdx = c
                        }
                    }
                    
                    // Apply sigmoid if scores look like logits
                    val confidence = if (maxScore < -0.5f || maxScore > 1.5f) sigmoid(maxScore) else maxScore
                    
                    if (confidence < 0.15f) continue  // Lower threshold for debugging
                    
                    // YOLOv8 outputs pixel coords relative to inputSize
                    val x1 = ((xCenter - w / 2) / inputSize).coerceIn(0f, 1f)
                    val y1 = ((yCenter - h / 2) / inputSize).coerceIn(0f, 1f)
                    val x2 = ((xCenter + w / 2) / inputSize).coerceIn(0f, 1f)
                    val y2 = ((yCenter + h / 2) / inputSize).coerceIn(0f, 1f)
                    
                    candidates.add(DetectionResult(
                        className = classNames.getOrNull(maxClassIdx) ?: "class_$maxClassIdx",
                        confidence = confidence,
                        bbox = BoundingBox(x1, y1, x2, y2),
                        inferenceTimeMs = 0f
                    ))
                }
            } else if (isV5Format) {
                // YOLOv5: [numPredictions, numClasses+5] — has objectness score
                val numPreds = dim0
                Log.d(TAG, "🔍 YOLOv5: $numPreds predictions")
                
                for (i in 0 until numPreds) {
                    val pred = data2d[i]
                    val xCenter = pred[0]
                    val yCenter = pred[1]
                    val w = pred[2]
                    val h = pred[3]
                    val objectness = sigmoid(pred[4])
                    
                    if (objectness < 0.15f) continue
                    
                    // Find best class
                    var maxScore = -Float.MAX_VALUE
                    var maxClassIdx = 0
                    for (c in 0 until numClasses) {
                        val raw = pred[5 + c]
                        if (raw > maxScore) {
                            maxScore = raw
                            maxClassIdx = c
                        }
                    }
                    
                    val classConf = sigmoid(maxScore)
                    val confidence = objectness * classConf
                    
                    if (confidence < 0.15f) continue
                    
                    // YOLOv5 coords relative to input size
                    val x1 = ((xCenter - w / 2) / inputSize).coerceIn(0f, 1f)
                    val y1 = ((yCenter - h / 2) / inputSize).coerceIn(0f, 1f)
                    val x2 = ((xCenter + w / 2) / inputSize).coerceIn(0f, 1f)
                    val y2 = ((yCenter + h / 2) / inputSize).coerceIn(0f, 1f)
                    
                    candidates.add(DetectionResult(
                        className = classNames.getOrNull(maxClassIdx) ?: "class_$maxClassIdx",
                        confidence = confidence,
                        bbox = BoundingBox(x1, y1, x2, y2),
                        inferenceTimeMs = 0f
                    ))
                }
            } else {
                // Unknown format — try to figure it out
                Log.w(TAG, "🔍 Unknown output format: ${dim0}x${dim1}. Trying heuristic...")
                
                // If dim1 > dim0 and dim0 > numClasses, might be transposed YOLOv8 with different class count
                // If dim0 > dim1, might be YOLOv5-style
                if (dim1 > dim0) {
                    // Assume YOLOv8-like: features × predictions
                    val numFeats = dim0
                    val numPreds = dim1
                    val inferredClasses = numFeats - 4
                    Log.w(TAG, "🔍 Heuristic: YOLOv8-like with $inferredClasses classes, $numPreds predictions")
                    
                    for (i in 0 until numPreds) {
                        val xCenter = data2d[0][i]
                        val yCenter = data2d[1][i]
                        val w = data2d[2][i]
                        val h = data2d[3][i]
                        
                        var maxScore = -Float.MAX_VALUE
                        var maxClassIdx = 0
                        for (c in 0 until inferredClasses) {
                            val raw = data2d[4 + c][i]
                            if (raw > maxScore) { maxScore = raw; maxClassIdx = c }
                        }
                        
                        val confidence = if (maxScore < -0.5f || maxScore > 1.5f) sigmoid(maxScore) else maxScore
                        if (confidence < 0.15f) continue
                        
                        val x1 = ((xCenter - w / 2) / inputSize).coerceIn(0f, 1f)
                        val y1 = ((yCenter - h / 2) / inputSize).coerceIn(0f, 1f)
                        val x2 = ((xCenter + w / 2) / inputSize).coerceIn(0f, 1f)
                        val y2 = ((yCenter + h / 2) / inputSize).coerceIn(0f, 1f)
                        
                        candidates.add(DetectionResult(
                            className = classNames.getOrNull(maxClassIdx) ?: "class_$maxClassIdx",
                            confidence = confidence,
                            bbox = BoundingBox(x1, y1, x2, y2),
                            inferenceTimeMs = 0f
                        ))
                    }
                }
            }
            
            // Apply NMS
            detections.addAll(applyNMS(candidates, nmsThreshold))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse YOLO output", e)
        }
        
        return detections
    }
    
    /**
     * Apply Non-Maximum Suppression to remove overlapping boxes.
     */
    private fun applyNMS(detections: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionResult>()
        
        for (detection in sorted) {
            var shouldAdd = true
            
            for (selected in selected) {
                if (calculateIoU(detection.bbox, selected.bbox) > iouThreshold) {
                    shouldAdd = false
                    break
                }
            }
            
            if (shouldAdd) {
                selected.add(detection)
            }
        }
        
        return selected
    }
    
    /**
     * Calculate Intersection over Union between two bounding boxes.
     */
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = max(box1.x1, box2.x1)
        val y1 = max(box1.y1, box2.y1)
        val x2 = min(box1.x2, box2.x2)
        val y2 = min(box1.y2, box2.y2)
        
        val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
        val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
        val unionArea = box1Area + box2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Escalate uncertain detection to mesh for better model.
     */
    private suspend fun escalateToMesh(
        imageBytes: ByteArray,
        sourceId: String,
        localDetection: DetectionResult
    ) {
        try {
            val metadata = _modelManager.activeModel.value ?: return
            
            val opinion = ModelOpinion(
                modelId = "${metadata.modelId}_${metadata.version}",
                nodeId = nodeId,
                className = localDetection.className,
                confidence = localDetection.confidence,
                bbox = localDetection.bbox,
                maskPolygon = null,
                inferenceTimeMs = localDetection.inferenceTimeMs,
                timestamp = System.currentTimeMillis()
            )
            
            val detection = DetectionWithMask(
                bbox = localDetection.bbox,
                cropBytes = null,
                maskPolygon = null,
                maskRle = null,
                className = localDetection.className,
                confidence = localDetection.confidence
            )
            
            val envelope = EscalationEnvelope.create(
                imageBytes = imageBytes,
                sourceId = sourceId,
                originNode = nodeId,
                firstOpinion = opinion,
                detections = listOf(detection)
            )
            
            _escalations.emit(envelope)
            
            val sender = meshSender
            if (sender != null) {
                val message = JSONObject().apply {
                    put("type", "vision.escalate")
                    put("envelope", envelope.toJson(includeImage = true))
                }
                sender(message)
                
                Log.i(TAG, "Escalated: ${localDetection.className} @ ${localDetection.confidence}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to escalate", e)
        }
    }
    
    fun setMeshSender(sender: (JSONObject) -> Unit) {
        meshSender = sender
    }
    
    suspend fun handleVisionRequest(request: JSONObject): JSONObject {
        try {
            val requestId = request.getString("request_id")
            val imageBase64 = request.getString("image_base64")
            val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
            
            val detection = detect(imageBytes, sourceId = "mesh_request")
            
            return if (detection != null) {
                JSONObject().apply {
                    put("request_id", requestId)
                    put("status", "success")
                    put("class_name", detection.className)
                    put("confidence", detection.confidence)
                    put("bbox", detection.bbox.toJson())
                    put("inference_time_ms", detection.inferenceTimeMs)
                }
            } else {
                JSONObject().apply {
                    put("request_id", requestId)
                    put("status", "error")
                    put("error", "Detection failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle vision request", e)
            return JSONObject().apply {
                put("status", "error")
                put("error", e.message)
            }
        }
    }
    
    suspend fun captureAndDetect(facing: CameraFacing = CameraFacing.BACK): DetectionResult? {
        val snapshot = cameraCapability.takeSnapshot(
            SnapshotRequest(
                requestId = "vision_${System.currentTimeMillis()}",
                facing = facing,
                quality = 85,
                maxWidth = 1280,
                maxHeight = 720,
                requireApproval = false
            ),
            requester = "vision_capability"
        )
        
        return when (snapshot) {
            is SnapshotResult.Success -> {
                detect(snapshot.imageData, sourceId = "camera_${facing.name.lowercase()}")
            }
            else -> {
                Log.w(TAG, "Camera snapshot failed: $snapshot")
                null
            }
        }
    }
    
    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0f, 1f)
        Log.i(TAG, "Confidence threshold: $confidenceThreshold")
    }
    
    suspend fun switchModel(modelId: String, version: String): Boolean {
        val success = _modelManager.setActiveModel(modelId, version)
        if (success) {
            initializeModel()
        }
        return success
    }
    
    fun getCapabilityJson(): JSONObject {
        val metadata = _modelManager.activeModel.value
        
        return JSONObject().apply {
            put("name", "vision.detect")
            put("description", "YOLO object detection with ONNX Runtime")
            put("version", "1.0")
            put("model", metadata?.modelId ?: "none")
            put("model_version", metadata?.version ?: "none")
            put("input_size", inputSize)
            put("num_classes", numClasses)
            put("confidence_threshold", confidenceThreshold)
            put("supports_escalation", true)
            put("ready", _isReady.value)
        }
    }
    
    fun destroy() {
        synchronized(sessionLock) {
            ortSession?.close()
            ortSession = null
        }
        ortEnv = null
        _modelManager.destroy()
        scope.cancel()
    }
}
