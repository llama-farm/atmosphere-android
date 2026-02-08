package com.llamafarm.atmosphere.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraCapability"

/**
 * Camera facing direction.
 */
enum class CameraFacing {
    FRONT,
    BACK
}

/**
 * Camera snapshot request.
 */
data class SnapshotRequest(
    val requestId: String,
    val facing: CameraFacing = CameraFacing.BACK,
    val quality: Int = 85,           // JPEG quality 0-100
    val maxWidth: Int = 1920,        // Max output width
    val maxHeight: Int = 1080,       // Max output height
    val requireApproval: Boolean = true
)

/**
 * Camera snapshot result.
 */
sealed class SnapshotResult {
    data class Success(
        val requestId: String,
        val imageData: ByteArray,      // JPEG bytes
        val width: Int,
        val height: Int,
        val facing: CameraFacing,
        val timestamp: Long = System.currentTimeMillis()
    ) : SnapshotResult() {
        fun toBase64(): String = Base64.encodeToString(imageData, Base64.NO_WRAP)
        
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("width", width)
            put("height", height)
            put("facing", facing.name.lowercase())
            put("timestamp", timestamp)
            put("image_base64", toBase64())
            put("mime_type", "image/jpeg")
            put("size_bytes", imageData.size)
        }
    }
    
    data class Error(
        val requestId: String,
        val error: String
    ) : SnapshotResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("error", error)
        }
    }
    
    data class PendingApproval(
        val requestId: String
    ) : SnapshotResult()
    
    data class Denied(
        val requestId: String,
        val reason: String = "User denied camera access"
    ) : SnapshotResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("error", reason)
            put("denied", true)
        }
    }
}

/**
 * Approval callback for privacy-respecting camera access.
 */
typealias ApprovalCallback = (requestId: String, requester: String) -> Boolean

/**
 * Camera capability for mesh network.
 * 
 * Exposes device camera as a mesh capability, allowing remote
 * nodes to request snapshots with privacy controls.
 * 
 * Features:
 * - Front/back camera selection
 * - Configurable JPEG quality and resolution
 * - Privacy: requires explicit user approval for each request
 * - Returns base64-encoded JPEG
 */
class CameraCapability(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraManager: CameraManager? = null
    
    private val cameraLock = Semaphore(1)
    
    // Privacy approval callback
    private var approvalCallback: ApprovalCallback? = null
    
    // Pending approvals
    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()
    
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    
    init {
        checkAvailability()
    }
    
    /**
     * Check if camera capability is available.
     */
    private fun checkAvailability() {
        val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val hasPermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        _isAvailable.value = hasCamera && hasPermission
        Log.i(TAG, "Camera capability available: ${_isAvailable.value}")
    }
    
    /**
     * Set the approval callback for privacy-respecting camera access.
     */
    fun setApprovalCallback(callback: ApprovalCallback) {
        approvalCallback = callback
    }
    
    /**
     * Handle approval response from UI.
     */
    fun handleApprovalResponse(requestId: String, approved: Boolean) {
        pendingApprovals[requestId]?.complete(approved)
        pendingApprovals.remove(requestId)
    }
    
    /**
     * Take a snapshot with the specified parameters.
     * 
     * @param request Snapshot request parameters
     * @param requester Node ID of the requester (for approval UI)
     * @return SnapshotResult with image data or error
     */
    suspend fun takeSnapshot(
        request: SnapshotRequest,
        requester: String = "unknown"
    ): SnapshotResult {
        Log.i(TAG, "Snapshot requested: ${request.requestId} from $requester")
        
        // Check availability
        if (!_isAvailable.value) {
            return SnapshotResult.Error(
                request.requestId,
                "Camera not available or permission denied"
            )
        }
        
        // Request approval if needed
        if (request.requireApproval) {
            val approval = approvalCallback?.invoke(request.requestId, requester)
            
            if (approval == null) {
                // Use deferred approval flow
                val deferred = CompletableDeferred<Boolean>()
                pendingApprovals[request.requestId] = deferred
                
                // Wait for approval with timeout
                val approved = withTimeoutOrNull(30_000L) {
                    deferred.await()
                } ?: false
                
                if (!approved) {
                    return SnapshotResult.Denied(request.requestId)
                }
            } else if (!approval) {
                return SnapshotResult.Denied(request.requestId)
            }
        }
        
        // Take the snapshot
        return try {
            captureImage(request)
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot failed", e)
            SnapshotResult.Error(request.requestId, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Capture an image using Camera2 API.
     */
    private suspend fun captureImage(request: SnapshotRequest): SnapshotResult {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                try {
                    // Initialize camera thread if needed
                    if (cameraThread == null) {
                        cameraThread = HandlerThread("CameraCapability").apply { start() }
                        cameraHandler = Handler(cameraThread!!.looper)
                    }
                    
                    cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    
                    // Find camera by facing direction
                    val cameraId = findCamera(request.facing)
                    if (cameraId == null) {
                        continuation.resume(
                            SnapshotResult.Error(request.requestId, "Camera not found for ${request.facing}")
                        )
                        return@suspendCancellableCoroutine
                    }
                    
                    // Get supported output size
                    val outputSize = getOutputSize(cameraId, request.maxWidth, request.maxHeight)
                    
                    // Create image reader
                    val imageReader = ImageReader.newInstance(
                        outputSize.width,
                        outputSize.height,
                        ImageFormat.JPEG,
                        1
                    )
                    
                    // Set up image available listener
                    var capturedImage: Image? = null
                    imageReader.setOnImageAvailableListener({ reader ->
                        capturedImage = reader.acquireLatestImage()
                    }, cameraHandler)
                    
                    // Open camera and capture
                    if (!cameraLock.tryAcquire(5, TimeUnit.SECONDS)) {
                        continuation.resume(
                            SnapshotResult.Error(request.requestId, "Camera busy")
                        )
                        return@suspendCancellableCoroutine
                    }
                    
                    try {
                        cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                try {
                                    // Create capture session
                                    val surfaces = listOf(imageReader.surface)
                                    
                                    @Suppress("DEPRECATION")
                                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {
                                            try {
                                                // Create capture request
                                                val captureBuilder = camera.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_STILL_CAPTURE
                                                )
                                                captureBuilder.addTarget(imageReader.surface)
                                                captureBuilder.set(
                                                    CaptureRequest.JPEG_QUALITY,
                                                    request.quality.toByte()
                                                )
                                                
                                                // Capture
                                                session.capture(
                                                    captureBuilder.build(),
                                                    object : CameraCaptureSession.CaptureCallback() {
                                                        override fun onCaptureCompleted(
                                                            session: CameraCaptureSession,
                                                            request: CaptureRequest,
                                                            result: TotalCaptureResult
                                                        ) {
                                                            // Process captured image
                                                            scope.launch {
                                                                delay(100) // Give time for image to be available
                                                                
                                                                val image = capturedImage
                                                                if (image != null) {
                                                                    val buffer = image.planes[0].buffer
                                                                    val bytes = ByteArray(buffer.remaining())
                                                                    buffer.get(bytes)
                                                                    image.close()
                                                                    
                                                                    val result = SnapshotResult.Success(
                                                                        requestId = request.hashCode().toString(),
                                                                        imageData = bytes,
                                                                        width = outputSize.width,
                                                                        height = outputSize.height,
                                                                        facing = CameraFacing.BACK
                                                                    )
                                                                    
                                                                    camera.close()
                                                                    imageReader.close()
                                                                    cameraLock.release()
                                                                    
                                                                    if (continuation.isActive) {
                                                                        continuation.resume(result)
                                                                    }
                                                                } else {
                                                                    camera.close()
                                                                    imageReader.close()
                                                                    cameraLock.release()
                                                                    
                                                                    if (continuation.isActive) {
                                                                        continuation.resume(
                                                                            SnapshotResult.Error(
                                                                                request.hashCode().toString(),
                                                                                "Failed to capture image"
                                                                            )
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        
                                                        override fun onCaptureFailed(
                                                            session: CameraCaptureSession,
                                                            request: CaptureRequest,
                                                            failure: CaptureFailure
                                                        ) {
                                                            camera.close()
                                                            imageReader.close()
                                                            cameraLock.release()
                                                            
                                                            if (continuation.isActive) {
                                                                continuation.resume(
                                                                    SnapshotResult.Error(
                                                                        request.hashCode().toString(),
                                                                        "Capture failed: ${failure.reason}"
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    },
                                                    cameraHandler
                                                )
                                            } catch (e: Exception) {
                                                camera.close()
                                                imageReader.close()
                                                cameraLock.release()
                                                
                                                if (continuation.isActive) {
                                                    continuation.resume(
                                                        SnapshotResult.Error(
                                                            request.requestId,
                                                            "Capture error: ${e.message}"
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        
                                        override fun onConfigureFailed(session: CameraCaptureSession) {
                                            camera.close()
                                            imageReader.close()
                                            cameraLock.release()
                                            
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    SnapshotResult.Error(
                                                        request.requestId,
                                                        "Camera configuration failed"
                                                    )
                                                )
                                            }
                                        }
                                    }, cameraHandler)
                                } catch (e: Exception) {
                                    camera.close()
                                    imageReader.close()
                                    cameraLock.release()
                                    
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            SnapshotResult.Error(request.requestId, "Session error: ${e.message}")
                                        )
                                    }
                                }
                            }
                            
                            override fun onDisconnected(camera: CameraDevice) {
                                camera.close()
                                cameraLock.release()
                            }
                            
                            override fun onError(camera: CameraDevice, error: Int) {
                                camera.close()
                                imageReader.close()
                                cameraLock.release()
                                
                                if (continuation.isActive) {
                                    continuation.resume(
                                        SnapshotResult.Error(request.requestId, "Camera error: $error")
                                    )
                                }
                            }
                        }, cameraHandler)
                    } catch (e: SecurityException) {
                        cameraLock.release()
                        continuation.resume(
                            SnapshotResult.Error(request.requestId, "Camera permission denied")
                        )
                    }
                    
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(
                            SnapshotResult.Error(request.requestId, "Camera error: ${e.message}")
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Find camera ID by facing direction.
     */
    private fun findCamera(facing: CameraFacing): String? {
        val manager = cameraManager ?: return null
        val targetLensFacing = when (facing) {
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }
        
        return manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == targetLensFacing
        }
    }
    
    /**
     * Get best output size within constraints.
     */
    private fun getOutputSize(cameraId: String, maxWidth: Int, maxHeight: Int): Size {
        val manager = cameraManager ?: return Size(maxWidth, maxHeight)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        val sizes = configs?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(maxWidth, maxHeight))
        
        // Find largest size within constraints
        return sizes
            .filter { it.width <= maxWidth && it.height <= maxHeight }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(maxWidth, maxHeight)
    }
    
    /**
     * Get capability JSON for mesh registration.
     */
    fun getCapabilityJson(): JSONObject {
        return JSONObject().apply {
            put("name", "camera")
            put("description", "Take photos with device camera")
            put("version", "1.0")
            put("params", JSONObject().apply {
                put("facing", "front|back")
                put("quality", "1-100 (JPEG quality)")
                put("max_width", "max output width")
                put("max_height", "max output height")
            })
            put("requires_approval", true)
            put("available", _isAvailable.value)
        }
    }
    
    /**
     * Handle mesh request.
     */
    suspend fun handleMeshRequest(requestJson: JSONObject, requester: String): JSONObject {
        val requestId = requestJson.optString("request_id", java.util.UUID.randomUUID().toString())
        
        val request = SnapshotRequest(
            requestId = requestId,
            facing = when (requestJson.optString("facing", "back").lowercase()) {
                "front" -> CameraFacing.FRONT
                else -> CameraFacing.BACK
            },
            quality = requestJson.optInt("quality", 85).coerceIn(1, 100),
            maxWidth = requestJson.optInt("max_width", 1920),
            maxHeight = requestJson.optInt("max_height", 1080),
            requireApproval = requestJson.optBoolean("require_approval", true)
        )
        
        return when (val result = takeSnapshot(request, requester)) {
            is SnapshotResult.Success -> result.toJson()
            is SnapshotResult.Error -> result.toJson()
            is SnapshotResult.Denied -> result.toJson()
            is SnapshotResult.PendingApproval -> JSONObject().apply {
                put("request_id", requestId)
                put("status", "pending_approval")
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        scope.cancel()
    }
}
