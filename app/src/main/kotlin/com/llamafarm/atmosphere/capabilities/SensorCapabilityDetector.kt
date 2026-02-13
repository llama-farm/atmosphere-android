package com.llamafarm.atmosphere.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Detects all available phone sensors and exposes them as mesh capabilities.
 * 
 * Transforms the phone into a rich sensor node - not just compute, but data collection.
 * Peers can request sensor data via CRDT _requests/_responses pattern.
 * 
 * Sensor types:
 * - Location: GPS, network location, speed, heading
 * - Motion: Accelerometer, gyroscope, step counter, activity
 * - Environment: Temperature, humidity, pressure, light
 * - Camera: Front/back vision capture
 * - Microphone: Audio capture, STT
 * - Battery: Level, charging, temperature
 * - Network: WiFi/cellular signal strength
 * - Proximity: Proximity sensor
 * - Magnetometer: Compass heading
 */
object SensorCapabilityDetector {
    private const val TAG = "SensorCapability"
    
    data class SensorCapability(
        val id: String,
        val name: String,
        val type: String, // sensor:gps, sensor:accelerometer, etc.
        val category: String, // location, motion, environment, vision, audio, battery, network
        val available: Boolean,
        val requiresPermission: String? = null,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Detect all available sensors on this device.
     */
    fun detectAll(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        
        // 1. Location sensors
        capabilities.addAll(detectLocationSensors(context))
        
        // 2. Motion sensors
        capabilities.addAll(detectMotionSensors(context))
        
        // 3. Environment sensors
        capabilities.addAll(detectEnvironmentSensors(context))
        
        // 4. Camera (vision)
        capabilities.addAll(detectCameraSensors(context))
        
        // 5. Microphone (audio)
        capabilities.addAll(detectAudioSensors(context))
        
        // 6. Battery
        capabilities.addAll(detectBatterySensors(context))
        
        // 7. Network
        capabilities.addAll(detectNetworkSensors(context))
        
        // 8. Other sensors (proximity, magnetometer, etc.)
        capabilities.addAll(detectOtherSensors(context))
        
        Log.i(TAG, "Detected ${capabilities.size} sensor capabilities")
        return capabilities
    }
    
    // ========================================================================
    // Location Sensors
    // ========================================================================
    
    private fun detectLocationSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return capabilities
        
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        // GPS location
        val hasGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        capabilities.add(SensorCapability(
            id = "sensor:location:gps",
            name = "GPS Location",
            type = "sensor:location",
            category = "location",
            available = hasGPS,
            requiresPermission = Manifest.permission.ACCESS_FINE_LOCATION,
            metadata = mapOf(
                "provider" to "gps",
                "outputs" to listOf("latitude", "longitude", "altitude", "accuracy", "speed", "bearing"),
                "units" to mapOf(
                    "latitude" to "degrees",
                    "longitude" to "degrees",
                    "altitude" to "meters",
                    "accuracy" to "meters",
                    "speed" to "meters/second",
                    "bearing" to "degrees"
                ),
                "typical_accuracy" to "5-20 meters",
                "refresh_rate" to "1-10 Hz",
                "permission_granted" to hasLocationPermission
            )
        ))
        
        // Network location (cell tower + WiFi triangulation)
        val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        capabilities.add(SensorCapability(
            id = "sensor:location:network",
            name = "Network Location",
            type = "sensor:location",
            category = "location",
            available = hasNetwork,
            requiresPermission = Manifest.permission.ACCESS_COARSE_LOCATION,
            metadata = mapOf(
                "provider" to "network",
                "outputs" to listOf("latitude", "longitude", "accuracy"),
                "typical_accuracy" to "100-500 meters",
                "refresh_rate" to "variable",
                "permission_granted" to (hasLocationPermission || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
            )
        ))
        
        return capabilities
    }
    
    // ========================================================================
    // Motion Sensors
    // ========================================================================
    
    private fun detectMotionSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return capabilities
        
        // Accelerometer
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:accelerometer",
                name = "Accelerometer",
                type = "sensor:motion",
                category = "motion",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "accelerometer",
                    "outputs" to listOf("x", "y", "z"),
                    "units" to "m/s²",
                    "range" to "${sensor.maximumRange} m/s²",
                    "resolution" to "${sensor.resolution} m/s²",
                    "power" to "${sensor.power} mA",
                    "refresh_rate" to "up to ${sensor.maxDelay / 1000000}Hz",
                    "vendor" to sensor.vendor
                )
            ))
        }
        
        // Gyroscope
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:gyroscope",
                name = "Gyroscope",
                type = "sensor:motion",
                category = "motion",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "gyroscope",
                    "outputs" to listOf("x_rotation", "y_rotation", "z_rotation"),
                    "units" to "rad/s",
                    "range" to "${sensor.maximumRange} rad/s",
                    "resolution" to "${sensor.resolution} rad/s",
                    "power" to "${sensor.power} mA"
                )
            ))
        }
        
        // Step counter
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:step_counter",
                name = "Step Counter",
                type = "sensor:motion",
                category = "motion",
                available = true,
                requiresPermission = Manifest.permission.ACTIVITY_RECOGNITION,
                metadata = mapOf(
                    "sensor_type" to "step_counter",
                    "outputs" to listOf("step_count"),
                    "units" to "steps",
                    "power" to "${sensor.power} mA"
                )
            ))
        }
        
        // Step detector
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:step_detector",
                name = "Step Detector",
                type = "sensor:motion",
                category = "motion",
                available = true,
                requiresPermission = Manifest.permission.ACTIVITY_RECOGNITION,
                metadata = mapOf(
                    "sensor_type" to "step_detector",
                    "outputs" to listOf("step_event"),
                    "description" to "Triggers event for each step taken"
                )
            ))
        }
        
        // Linear acceleration (gravity removed)
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:linear_acceleration",
                name = "Linear Acceleration",
                type = "sensor:motion",
                category = "motion",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "linear_acceleration",
                    "outputs" to listOf("x", "y", "z"),
                    "units" to "m/s²",
                    "description" to "Acceleration excluding gravity"
                )
            ))
        }
        
        // Gravity
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:motion:gravity",
                name = "Gravity",
                type = "sensor:motion",
                category = "motion",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "gravity",
                    "outputs" to listOf("x", "y", "z"),
                    "units" to "m/s²"
                )
            ))
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Environment Sensors
    // ========================================================================
    
    private fun detectEnvironmentSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return capabilities
        
        // Temperature
        sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:environment:temperature",
                name = "Ambient Temperature",
                type = "sensor:environment",
                category = "environment",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "temperature",
                    "outputs" to listOf("temperature"),
                    "units" to "°C",
                    "range" to "${sensor.maximumRange} °C",
                    "resolution" to "${sensor.resolution} °C"
                )
            ))
        }
        
        // Humidity
        sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:environment:humidity",
                name = "Relative Humidity",
                type = "sensor:environment",
                category = "environment",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "humidity",
                    "outputs" to listOf("humidity"),
                    "units" to "%",
                    "range" to "${sensor.maximumRange} %",
                    "resolution" to "${sensor.resolution} %"
                )
            ))
        }
        
        // Barometric pressure
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:environment:pressure",
                name = "Barometric Pressure",
                type = "sensor:environment",
                category = "environment",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "pressure",
                    "outputs" to listOf("pressure", "altitude_estimate"),
                    "units" to "hPa (millibars)",
                    "range" to "${sensor.maximumRange} hPa",
                    "resolution" to "${sensor.resolution} hPa",
                    "description" to "Can estimate altitude from pressure"
                )
            ))
        }
        
        // Ambient light
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:environment:light",
                name = "Ambient Light",
                type = "sensor:environment",
                category = "environment",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "light",
                    "outputs" to listOf("illuminance"),
                    "units" to "lux",
                    "range" to "${sensor.maximumRange} lux",
                    "resolution" to "${sensor.resolution} lux"
                )
            ))
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Camera Sensors
    // ========================================================================
    
    private fun detectCameraSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return capabilities
        
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        try {
            val cameraIds = cameraManager.cameraIdList
            
            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                
                val facingName = when (facing) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "back"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                
                capabilities.add(SensorCapability(
                    id = "sensor:camera:$facingName",
                    name = "Camera ($facingName)",
                    type = "sensor:vision",
                    category = "vision",
                    available = true,
                    requiresPermission = Manifest.permission.CAMERA,
                    metadata = mapOf(
                        "facing" to facingName,
                        "camera_id" to cameraId,
                        "outputs" to listOf("image_frame", "video_stream"),
                        "formats" to listOf("jpeg", "raw", "yuv"),
                        "permission_granted" to hasCameraPermission
                    )
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate cameras: ${e.message}")
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Audio Sensors
    // ========================================================================
    
    private fun detectAudioSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        
        val hasMic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasMic) {
            capabilities.add(SensorCapability(
                id = "sensor:audio:microphone",
                name = "Microphone",
                type = "sensor:audio",
                category = "audio",
                available = true,
                requiresPermission = Manifest.permission.RECORD_AUDIO,
                metadata = mapOf(
                    "outputs" to listOf("audio_stream", "audio_level", "speech_text"),
                    "formats" to listOf("pcm", "aac", "opus"),
                    "sample_rates" to listOf(8000, 16000, 44100, 48000),
                    "channels" to listOf("mono", "stereo"),
                    "permission_granted" to hasRecordPermission,
                    "features" to listOf("speech_recognition", "noise_cancellation")
                )
            ))
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Battery Sensors
    // ========================================================================
    
    private fun detectBatterySensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        
        // Battery level and charging state (always available)
        capabilities.add(SensorCapability(
            id = "sensor:battery:status",
            name = "Battery Status",
            type = "sensor:battery",
            category = "battery",
            available = true,
            metadata = mapOf(
                "outputs" to listOf("level", "is_charging", "charge_type", "health", "temperature"),
                "units" to mapOf(
                    "level" to "percentage",
                    "temperature" to "°C"
                ),
                "charge_types" to listOf("none", "usb", "ac", "wireless"),
                "health_states" to listOf("good", "overheat", "dead", "over_voltage", "cold")
            )
        ))
        
        return capabilities
    }
    
    // ========================================================================
    // Network Sensors
    // ========================================================================
    
    private fun detectNetworkSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        
        // Network connectivity status
        capabilities.add(SensorCapability(
            id = "sensor:network:connectivity",
            name = "Network Connectivity",
            type = "sensor:network",
            category = "network",
            available = true,
            metadata = mapOf(
                "outputs" to listOf("connection_type", "wifi_ssid", "wifi_signal_strength", "cellular_signal_strength"),
                "connection_types" to listOf("wifi", "cellular", "ethernet", "vpn", "none"),
                "signal_units" to "dBm",
                "requires_permission" to Manifest.permission.ACCESS_WIFI_STATE
            )
        ))
        
        return capabilities
    }
    
    // ========================================================================
    // Other Sensors
    // ========================================================================
    
    private fun detectOtherSensors(context: Context): List<SensorCapability> {
        val capabilities = mutableListOf<SensorCapability>()
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return capabilities
        
        // Proximity sensor
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:proximity",
                name = "Proximity Sensor",
                type = "sensor:proximity",
                category = "proximity",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "proximity",
                    "outputs" to listOf("distance"),
                    "units" to "cm",
                    "range" to "${sensor.maximumRange} cm",
                    "description" to "Detects nearby objects (e.g., ear during call)"
                )
            ))
        }
        
        // Magnetometer / Compass
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:magnetometer",
                name = "Magnetometer",
                type = "sensor:magnetometer",
                category = "orientation",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "magnetometer",
                    "outputs" to listOf("x", "y", "z", "compass_heading"),
                    "units" to "μT (microtesla)",
                    "range" to "${sensor.maximumRange} μT",
                    "description" to "Magnetic field detector, used for compass"
                )
            ))
        }
        
        // Rotation vector (orientation)
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            capabilities.add(SensorCapability(
                id = "sensor:orientation:rotation_vector",
                name = "Rotation Vector",
                type = "sensor:orientation",
                category = "orientation",
                available = true,
                metadata = mapOf(
                    "sensor_type" to "rotation_vector",
                    "outputs" to listOf("x", "y", "z", "scalar"),
                    "description" to "Device orientation in 3D space"
                )
            ))
        }
        
        return capabilities
    }
}
