package com.llamafarm.atmosphere.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Handles sensor data requests from mesh peers.
 * 
 * Polls _requests collection for sensor requests and responds via _responses.
 * Request format:
 * {
 *   "request_id": "uuid",
 *   "capability_id": "sensor:location:gps",
 *   "action": "read",
 *   "params": { "timeout_ms": 5000 }
 * }
 * 
 * Response format:
 * {
 *   "request_id": "uuid",
 *   "status": "success",
 *   "data": { "latitude": 37.7749, "longitude": -122.4194, ... },
 *   "timestamp": 1707857234
 * }
 */
class SensorRequestHandler(private val context: Context) {
    companion object {
        private const val TAG = "SensorRequestHandler"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val MAX_SENSOR_WAIT_MS = 10000L
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    
    /**
     * Handle a sensor data request.
     * Returns response data as JSONObject or throws exception on error.
     */
    suspend fun handleRequest(requestId: String, capabilityId: String, params: Map<String, Any>): JSONObject {
        Log.i(TAG, "Handling sensor request: $capabilityId (request=$requestId)")
        
        val timeoutMs = (params["timeout_ms"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_MS
        
        return withTimeout(timeoutMs) {
            when {
                capabilityId.startsWith("sensor:location:") -> handleLocationRequest(capabilityId, params)
                capabilityId.startsWith("sensor:motion:") -> handleMotionRequest(capabilityId, params)
                capabilityId.startsWith("sensor:environment:") -> handleEnvironmentRequest(capabilityId, params)
                capabilityId.startsWith("sensor:camera:") -> handleCameraRequest(capabilityId, params)
                capabilityId.startsWith("sensor:audio:") -> handleAudioRequest(capabilityId, params)
                capabilityId.startsWith("sensor:battery:") -> handleBatteryRequest(capabilityId, params)
                capabilityId.startsWith("sensor:network:") -> handleNetworkRequest(capabilityId, params)
                capabilityId == "sensor:proximity" -> handleProximityRequest(params)
                capabilityId == "sensor:magnetometer" -> handleMagnetometerRequest(params)
                capabilityId.startsWith("sensor:orientation:") -> handleOrientationRequest(capabilityId, params)
                else -> throw IllegalArgumentException("Unknown sensor capability: $capabilityId")
            }
        }
    }
    
    // ========================================================================
    // Location Requests
    // ========================================================================
    
    private suspend fun handleLocationRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        // Check permission
        val permission = when {
            capabilityId.contains("gps") -> Manifest.permission.ACCESS_FINE_LOCATION
            else -> Manifest.permission.ACCESS_COARSE_LOCATION
        }
        
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Location permission not granted")
        }
        
        val provider = when {
            capabilityId.contains("gps") -> LocationManager.GPS_PROVIDER
            capabilityId.contains("network") -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        
        locationManager?.let { lm ->
            // Try to get last known location first (fast)
            val lastLocation = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
            
            if (lastLocation != null && !params.containsKey("require_fresh")) {
                return locationToJson(lastLocation)
            }
            
            // Request fresh location
            return suspendCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        continuation.resume(locationToJson(location))
                    }
                    
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        lm.removeUpdates(this)
                        continuation.resumeWithException(IllegalStateException("Location provider disabled"))
                    }
                }
                
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener)
                    
                    // Timeout fallback
                    GlobalScope.launch {
                        delay(MAX_SENSOR_WAIT_MS)
                        lm.removeUpdates(listener)
                        // If still waiting, use last known or fail
                        lastLocation?.let {
                            continuation.resume(locationToJson(it))
                        } ?: continuation.resumeWithException(TimeoutCancellationException("Location timeout"))
                    }
                } catch (e: SecurityException) {
                    continuation.resumeWithException(e)
                }
            }
        } ?: throw IllegalStateException("LocationManager not available")
    }
    
    private fun locationToJson(location: Location): JSONObject {
        return JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", location.altitude)
            put("accuracy", location.accuracy)
            put("speed", location.speed)
            put("bearing", location.bearing)
            put("timestamp", location.time)
            put("provider", location.provider)
        }
    }
    
    // ========================================================================
    // Motion Requests
    // ========================================================================
    
    private suspend fun handleMotionRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        sensorManager?.let { sm ->
            val sensorType = when {
                capabilityId.contains("accelerometer") -> Sensor.TYPE_ACCELEROMETER
                capabilityId.contains("gyroscope") -> Sensor.TYPE_GYROSCOPE
                capabilityId.contains("step_counter") -> Sensor.TYPE_STEP_COUNTER
                capabilityId.contains("step_detector") -> Sensor.TYPE_STEP_DETECTOR
                capabilityId.contains("linear_acceleration") -> Sensor.TYPE_LINEAR_ACCELERATION
                capabilityId.contains("gravity") -> Sensor.TYPE_GRAVITY
                else -> throw IllegalArgumentException("Unknown motion sensor: $capabilityId")
            }
            
            val sensor = sm.getDefaultSensor(sensorType)
                ?: throw IllegalStateException("Sensor not available: $capabilityId")
            
            return suspendCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        continuation.resume(sensorEventToJson(event))
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                // Timeout
                GlobalScope.launch {
                    delay(MAX_SENSOR_WAIT_MS)
                    sm.unregisterListener(listener)
                    continuation.resumeWithException(TimeoutCancellationException("Sensor timeout"))
                }
            }
        } ?: throw IllegalStateException("SensorManager not available")
    }
    
    private fun sensorEventToJson(event: SensorEvent): JSONObject {
        return JSONObject().apply {
            put("sensor_type", event.sensor.type)
            put("sensor_name", event.sensor.name)
            put("timestamp", event.timestamp)
            put("accuracy", event.accuracy)
            
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    put("step_count", event.values[0].toInt())
                }
                else -> {
                    put("x", event.values.getOrNull(0) ?: 0f)
                    put("y", event.values.getOrNull(1) ?: 0f)
                    put("z", event.values.getOrNull(2) ?: 0f)
                }
            }
        }
    }
    
    // ========================================================================
    // Environment Requests
    // ========================================================================
    
    private suspend fun handleEnvironmentRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        sensorManager?.let { sm ->
            val sensorType = when {
                capabilityId.contains("temperature") -> Sensor.TYPE_AMBIENT_TEMPERATURE
                capabilityId.contains("humidity") -> Sensor.TYPE_RELATIVE_HUMIDITY
                capabilityId.contains("pressure") -> Sensor.TYPE_PRESSURE
                capabilityId.contains("light") -> Sensor.TYPE_LIGHT
                else -> throw IllegalArgumentException("Unknown environment sensor: $capabilityId")
            }
            
            val sensor = sm.getDefaultSensor(sensorType)
                ?: throw IllegalStateException("Sensor not available: $capabilityId")
            
            return suspendCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        val result = JSONObject().apply {
                            put("timestamp", event.timestamp)
                            put("accuracy", event.accuracy)
                            
                            when (sensorType) {
                                Sensor.TYPE_AMBIENT_TEMPERATURE -> put("temperature", event.values[0])
                                Sensor.TYPE_RELATIVE_HUMIDITY -> put("humidity", event.values[0])
                                Sensor.TYPE_PRESSURE -> {
                                    put("pressure", event.values[0])
                                    // Estimate altitude from pressure (standard atmosphere)
                                    val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
                                    put("altitude_estimate", altitude)
                                }
                                Sensor.TYPE_LIGHT -> put("illuminance", event.values[0])
                            }
                        }
                        continuation.resume(result)
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                // Timeout
                GlobalScope.launch {
                    delay(MAX_SENSOR_WAIT_MS)
                    sm.unregisterListener(listener)
                    continuation.resumeWithException(TimeoutCancellationException("Sensor timeout"))
                }
            }
        } ?: throw IllegalStateException("SensorManager not available")
    }
    
    // ========================================================================
    // Camera Requests
    // ========================================================================
    
    private suspend fun handleCameraRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        // TODO: Implement camera frame capture
        // For now, return capability info
        return JSONObject().apply {
            put("status", "not_implemented")
            put("message", "Camera capture requires CameraCapability integration")
            put("capability_id", capabilityId)
        }
    }
    
    // ========================================================================
    // Audio Requests
    // ========================================================================
    
    private suspend fun handleAudioRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        // TODO: Implement audio capture / STT
        // For now, return capability info
        return JSONObject().apply {
            put("status", "not_implemented")
            put("message", "Audio capture requires VoiceCapability integration")
            put("capability_id", capabilityId)
        }
    }
    
    // ========================================================================
    // Battery Requests
    // ========================================================================
    
    private suspend fun handleBatteryRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: throw IllegalStateException("BatteryManager not available")
        
        return JSONObject().apply {
            put("level", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("is_charging", batteryManager.isCharging)
            put("charge_type", when {
                !batteryManager.isCharging -> "none"
                else -> {
                    // Get charging type from BatteryManager
                    val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        BatteryManager.BATTERY_STATUS_FULL -> "full"
                        else -> "unknown"
                    }
                }
            })
            
            // Temperature (in tenths of degrees Celsius)
            val temp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10.0
            put("temperature", temp)
            
            put("timestamp", System.currentTimeMillis())
        }
    }
    
    // ========================================================================
    // Network Requests
    // ========================================================================
    
    private suspend fun handleNetworkRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("ConnectivityManager not available")
        
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        
        val result = JSONObject().apply {
            put("is_connected", activeNetwork != null)
            
            capabilities?.let { caps ->
                put("connection_type", when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "unknown"
                })
                
                // WiFi signal strength
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifiManager?.connectionInfo?.let { info ->
                        put("wifi_ssid", info.ssid.removeSurrounding("\""))
                        put("wifi_signal_strength", info.rssi) // dBm
                        put("wifi_link_speed", info.linkSpeed) // Mbps
                    }
                }
                
                put("download_bandwidth_kbps", caps.linkDownstreamBandwidthKbps)
                put("upload_bandwidth_kbps", caps.linkUpstreamBandwidthKbps)
            }
            
            put("timestamp", System.currentTimeMillis())
        }
        
        return result
    }
    
    // ========================================================================
    // Proximity Requests
    // ========================================================================
    
    private suspend fun handleProximityRequest(params: Map<String, Any>): JSONObject {
        sensorManager?.let { sm ->
            val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                ?: throw IllegalStateException("Proximity sensor not available")
            
            return suspendCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        continuation.resume(JSONObject().apply {
                            put("distance", event.values[0])
                            put("max_range", sensor.maximumRange)
                            put("timestamp", event.timestamp)
                        })
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                GlobalScope.launch {
                    delay(MAX_SENSOR_WAIT_MS)
                    sm.unregisterListener(listener)
                    continuation.resumeWithException(TimeoutCancellationException("Sensor timeout"))
                }
            }
        } ?: throw IllegalStateException("SensorManager not available")
    }
    
    // ========================================================================
    // Magnetometer Requests
    // ========================================================================
    
    private suspend fun handleMagnetometerRequest(params: Map<String, Any>): JSONObject {
        sensorManager?.let { sm ->
            val sensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                ?: throw IllegalStateException("Magnetometer not available")
            
            return suspendCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        
                        // Calculate compass heading (azimuth)
                        val result = JSONObject().apply {
                            put("x", event.values[0])
                            put("y", event.values[1])
                            put("z", event.values[2])
                            put("timestamp", event.timestamp)
                            
                            // Compass heading requires both magnetic field and accelerometer
                            // For now, just return raw values
                            put("note", "Full compass heading requires accelerometer fusion")
                        }
                        continuation.resume(result)
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                GlobalScope.launch {
                    delay(MAX_SENSOR_WAIT_MS)
                    sm.unregisterListener(listener)
                    continuation.resumeWithException(TimeoutCancellationException("Sensor timeout"))
                }
            }
        } ?: throw IllegalStateException("SensorManager not available")
    }
    
    // ========================================================================
    // Orientation Requests
    // ========================================================================
    
    private suspend fun handleOrientationRequest(capabilityId: String, params: Map<String, Any>): JSONObject {
        sensorManager?.let { sm ->
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: throw IllegalStateException("Rotation vector sensor not available")
            
            return suspendCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        
                        // Convert rotation vector to orientation angles
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        
                        val result = JSONObject().apply {
                            put("azimuth", Math.toDegrees(orientation[0].toDouble())) // Z axis
                            put("pitch", Math.toDegrees(orientation[1].toDouble()))   // X axis
                            put("roll", Math.toDegrees(orientation[2].toDouble()))    // Y axis
                            put("timestamp", event.timestamp)
                        }
                        continuation.resume(result)
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
                }
                
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                GlobalScope.launch {
                    delay(MAX_SENSOR_WAIT_MS)
                    sm.unregisterListener(listener)
                    continuation.resumeWithException(TimeoutCancellationException("Sensor timeout"))
                }
            }
        } ?: throw IllegalStateException("SensorManager not available")
    }
}
