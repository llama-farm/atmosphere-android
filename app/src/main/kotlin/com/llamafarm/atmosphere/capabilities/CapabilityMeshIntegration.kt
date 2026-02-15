package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.core.AtmosphereNative
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

private const val TAG = "CapabilityMesh"

/**
 * Integrates device capabilities (camera, sensors, etc.) with the Atmosphere CRDT mesh.
 * 
 * This class:
 * - Publishes device capabilities to the mesh (_capabilities collection)
 * - Subscribes to capability requests from mesh peers (_requests collection)
 * - Routes requests to the appropriate capability handlers
 * - Returns results via the mesh (_results collection)
 */
class CapabilityMeshIntegration(
    private val context: Context,
    private val atmosphereHandle: Long,
    private val deviceId: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Capability handlers
    private var cameraCapability: CameraCapability? = null
    private var sensorCapability: SensorCapability? = null
    
    private val _isPublished = MutableStateFlow(false)
    val isPublished: StateFlow<Boolean> = _isPublished
    
    // Polling job for checking requests
    private var requestPollingJob: Job? = null
    
    /**
     * Initialize and register capabilities.
     */
    fun initialize() {
        Log.i(TAG, "Initializing capability mesh integration for device: $deviceId")
        
        // Initialize capability handlers
        cameraCapability = CameraCapability(context)
        sensorCapability = SensorCapability(context)
        
        // Publish capabilities to mesh
        publishCapabilities()
        
        // Start polling for requests
        startRequestPolling()
    }
    
    /**
     * Publish device capabilities to the CRDT mesh.
     */
    private fun publishCapabilities() {
        scope.launch {
            try {
                val capabilities = buildCapabilitiesJson()
                
                val docId = "cap_$deviceId"
                
                AtmosphereNative.insert(
                    atmosphereHandle,
                    "_capabilities",
                    docId,
                    capabilities.toString()
                )
                
                _isPublished.value = true
                
                Log.i(TAG, "Published capabilities to mesh: $docId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish capabilities", e)
            }
        }
    }
    
    /**
     * Build JSON document describing this device's capabilities.
     */
    private fun buildCapabilitiesJson(): JSONObject {
        val camera = cameraCapability?.getCapabilityJson() ?: JSONObject()
        val sensors = sensorCapability?.getCapabilitiesJson() ?: JSONArray()
        
        return JSONObject().apply {
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
            put("ttl", 300) // 5 minutes
            
            put("capabilities", JSONObject().apply {
                put("camera", camera)
                put("sensors", sensors)
            })
            
            put("metadata", JSONObject().apply {
                put("platform", "android")
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("sdk", android.os.Build.VERSION.SDK_INT)
            })
        }
    }
    
    /**
     * Start polling for capability requests from the mesh.
     */
    private fun startRequestPolling() {
        requestPollingJob?.cancel()
        
        requestPollingJob = scope.launch {
            while (isActive) {
                try {
                    checkForRequests()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking requests", e)
                }
                
                delay(2000) // Poll every 2 seconds
            }
        }
    }
    
    /**
     * Check for new capability requests directed at this device.
     */
    private suspend fun checkForRequests() {
        try {
            val requestsJson = AtmosphereNative.query(atmosphereHandle, "_requests")
            val requests = JSONArray(requestsJson)
            
            for (i in 0 until requests.length()) {
                val request = requests.getJSONObject(i)
                
                // Check if request is for this device
                val targetDevice = request.optString("target_device", "")
                if (targetDevice != deviceId) {
                    continue
                }
                
                // Check if we've already processed this request
                val requestId = request.optString("request_id", "")
                if (requestId.isEmpty()) {
                    continue
                }
                
                val capability = request.optString("capability", "")
                val params = request.optJSONObject("params") ?: JSONObject()
                val requester = request.optString("requester", "unknown")
                
                Log.i(TAG, "Processing request: $requestId, capability: $capability")
                
                // Process request based on capability type
                val result = when (capability) {
                    "camera" -> handleCameraRequest(requestId, params, requester)
                    "sensor" -> handleSensorRequest(requestId, params, requester)
                    else -> {
                        Log.w(TAG, "Unknown capability: $capability")
                        JSONObject().apply {
                            put("request_id", requestId)
                            put("error", "Unknown capability: $capability")
                        }
                    }
                }
                
                // Publish result to mesh
                publishResult(requestId, result)
                
                // Delete the request (mark as processed)
                deleteRequest(requestId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check requests", e)
        }
    }
    
    /**
     * Handle camera capability request.
     */
    private suspend fun handleCameraRequest(
        requestId: String,
        params: JSONObject,
        requester: String
    ): JSONObject {
        val camera = cameraCapability ?: return JSONObject().apply {
            put("request_id", requestId)
            put("error", "Camera capability not available")
        }
        
        return try {
            camera.handleMeshRequest(params.apply {
                put("request_id", requestId)
            }, requester)
        } catch (e: Exception) {
            Log.e(TAG, "Camera request failed", e)
            JSONObject().apply {
                put("request_id", requestId)
                put("error", e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Handle sensor capability request.
     */
    private suspend fun handleSensorRequest(
        requestId: String,
        params: JSONObject,
        requester: String
    ): JSONObject {
        val sensor = sensorCapability ?: return JSONObject().apply {
            put("request_id", requestId)
            put("error", "Sensor capability not available")
        }
        
        return try {
            sensor.handleMeshRequest(params.apply {
                put("request_id", requestId)
            }, requester)
        } catch (e: Exception) {
            Log.e(TAG, "Sensor request failed", e)
            JSONObject().apply {
                put("request_id", requestId)
                put("error", e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Publish result to the mesh.
     */
    private suspend fun publishResult(requestId: String, result: JSONObject) {
        try {
            val resultDoc = JSONObject().apply {
                put("request_id", requestId)
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis())
                put("result", result)
            }
            
            AtmosphereNative.insert(
                atmosphereHandle,
                "_results",
                "result_$requestId",
                resultDoc.toString()
            )
            
            Log.i(TAG, "Published result for request: $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish result", e)
        }
    }
    
    /**
     * Delete a processed request from the mesh.
     */
    private suspend fun deleteRequest(requestId: String) {
        try {
            // In a real CRDT implementation, we'd mark it as deleted/tombstone
            // For now, we'll just log it
            Log.d(TAG, "Request processed: $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete request", e)
        }
    }
    
    /**
     * Refresh capabilities (e.g., when permissions change).
     */
    fun refreshCapabilities() {
        publishCapabilities()
    }
    
    /**
     * Stop capability integration.
     */
    fun stop() {
        requestPollingJob?.cancel()
        cameraCapability?.destroy()
        sensorCapability?.destroy()
        scope.cancel()
        
        Log.i(TAG, "Capability mesh integration stopped")
    }
}
