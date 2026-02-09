package com.llamafarm.atmosphere.photo

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.sdk.AtmosphereClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private const val TAG = "PhotoViewModel"

data class Detection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val imageBase64: String,
    val detections: List<DetectionObject>,
    val timestamp: Long = System.currentTimeMillis(),
    val latency: Long = 0,
    val escalated: Boolean = false,
    val nodeId: String? = null
)

data class DetectionObject(
    val className: String,
    val confidence: Float,
    val bbox: BBox
)

data class BBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    
    private var atmosphereClient: AtmosphereClient? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow("Connecting...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    private val _currentDetection = MutableStateFlow<Detection?>(null)
    val currentDetection: StateFlow<Detection?> = _currentDetection.asStateFlow()
    
    private val _detectionHistory = MutableStateFlow<List<Detection>>(emptyList())
    val detectionHistory: StateFlow<List<Detection>> = _detectionHistory.asStateFlow()
    
    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        connectToAtmosphere()
    }
    
    /**
     * Connect to the local Atmosphere service.
     */
    private fun connectToAtmosphere() {
        viewModelScope.launch {
            try {
                Log.i(TAG, "🔌 Connecting to Atmosphere...")
                
                val context = getApplication<Application>()
                if (!AtmosphereClient.isInstalled(context)) {
                    _connectionStatus.value = "Atmosphere not installed"
                    _errorMessage.value = "Please install the Atmosphere app first"
                    Log.e(TAG, "❌ Atmosphere app not installed")
                    return@launch
                }
                
                _connectionStatus.value = "Connecting..."
                atmosphereClient = AtmosphereClient.connect(context)
                
                Log.i(TAG, "✅ Connected to Atmosphere service!")
                _isConnected.value = true
                
                // Get mesh status
                try {
                    val status = atmosphereClient?.meshStatus()
                    Log.i(TAG, "📱 Mesh Status: nodeId=${status?.nodeId}, peers=${status?.peerCount}")
                    _connectionStatus.value = "Connected (${status?.peerCount ?: 0} peers)"
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get mesh status", e)
                    _connectionStatus.value = "Connected"
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection error", e)
                _connectionStatus.value = "Error: ${e.message}"
                _errorMessage.value = "Connection failed: ${e.message}"
            }
        }
    }
    
    /**
     * Detect objects in a photo using Atmosphere SDK.
     */
    suspend fun detectObjects(bitmap: Bitmap) {
        if (!_isConnected.value) {
            _errorMessage.value = "Not connected to Atmosphere"
            return
        }
        
        val client = atmosphereClient
        if (client == null) {
            _errorMessage.value = "Atmosphere client not initialized"
            return
        }
        
        _isDetecting.value = true
        _errorMessage.value = null
        
        try {
            Log.i(TAG, "📸 Starting detection on ${bitmap.width}x${bitmap.height} image...")
            
            // Convert bitmap to base64 for storage
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            
            Log.d(TAG, "📦 Image size: ${imageBytes.size / 1024}KB")
            
            // Call SDK detectObjects
            val startTime = System.currentTimeMillis()
            val result = client.detectObjects(bitmap, "photo_capture")
            val latency = System.currentTimeMillis() - startTime
            
            Log.i(TAG, "📥 Detection complete in ${latency}ms")
            
            if (result.success) {
                val detectionObjects = result.detections.map { det ->
                    DetectionObject(
                        className = det.className,
                        confidence = det.confidence,
                        bbox = BBox(
                            x1 = det.bbox.x1,
                            y1 = det.bbox.y1,
                            x2 = det.bbox.x2,
                            y2 = det.bbox.y2
                        )
                    )
                }
                
                Log.i(TAG, "✅ Found ${detectionObjects.size} objects (escalated=${result.escalated})")
                
                val detection = Detection(
                    imageBase64 = imageBase64,
                    detections = detectionObjects,
                    latency = latency,
                    escalated = result.escalated,
                    nodeId = result.nodeId?.takeIf { it.isNotEmpty() }
                )
                
                _currentDetection.value = detection
                _detectionHistory.value = listOf(detection) + _detectionHistory.value
                
            } else {
                Log.e(TAG, "❌ Detection failed: ${result.error}")
                _errorMessage.value = result.error
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Detection error", e)
            _errorMessage.value = "Detection failed: ${e.message}"
        } finally {
            _isDetecting.value = false
        }
    }
    
    /**
     * Clear the current detection.
     */
    fun clearCurrentDetection() {
        _currentDetection.value = null
    }
    
    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * View a historical detection.
     */
    fun viewHistoryItem(detection: Detection) {
        _currentDetection.value = detection
    }
    
    override fun onCleared() {
        super.onCleared()
        atmosphereClient?.disconnect()
    }
}
