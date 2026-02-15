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
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "SensorCapability"

/**
 * Sensor capability for mesh network.
 * 
 * Exposes device sensors (accelerometer, gyroscope, GPS, etc.) as mesh capabilities.
 * Allows remote nodes to request sensor readings with privacy controls.
 */
class SensorCapability(private val context: Context) {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Get list of available sensors as JSON array.
     */
    fun getCapabilitiesJson(): JSONArray {
        val capabilities = JSONArray()
        
        // Accelerometer
        if (hasSensor(Sensor.TYPE_ACCELEROMETER)) {
            capabilities.put(JSONObject().apply {
                put("name", "accelerometer")
                put("type", "motion")
                put("description", "3-axis acceleration sensor")
            })
        }
        
        // Gyroscope
        if (hasSensor(Sensor.TYPE_GYROSCOPE)) {
            capabilities.put(JSONObject().apply {
                put("name", "gyroscope")
                put("type", "motion")
                put("description", "3-axis rotation sensor")
            })
        }
        
        // Magnetometer
        if (hasSensor(Sensor.TYPE_MAGNETIC_FIELD)) {
            capabilities.put(JSONObject().apply {
                put("name", "magnetometer")
                put("type", "position")
                put("description", "3-axis magnetic field sensor")
            })
        }
        
        // GPS
        if (hasLocationPermission()) {
            capabilities.put(JSONObject().apply {
                put("name", "gps")
                put("type", "position")
                put("description", "GPS location")
                put("requires_approval", true)
            })
        }
        
        // Ambient light
        if (hasSensor(Sensor.TYPE_LIGHT)) {
            capabilities.put(JSONObject().apply {
                put("name", "light")
                put("type", "environment")
                put("description", "Ambient light sensor")
            })
        }
        
        // Proximity
        if (hasSensor(Sensor.TYPE_PROXIMITY)) {
            capabilities.put(JSONObject().apply {
                put("name", "proximity")
                put("type", "environment")
                put("description", "Proximity sensor")
            })
        }
        
        // Battery (not a sensor but useful)
        capabilities.put(JSONObject().apply {
            put("name", "battery")
            put("type", "system")
            put("description", "Battery level and status")
        })
        
        return capabilities
    }
    
    /**
     * Check if a specific sensor type is available.
     */
    private fun hasSensor(sensorType: Int): Boolean {
        return sensorManager.getDefaultSensor(sensorType) != null
    }
    
    /**
     * Check if location permission is granted.
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Handle mesh request for sensor data.
     */
    suspend fun handleMeshRequest(request: JSONObject, requester: String): JSONObject {
        val requestId = request.optString("request_id", "")
        val sensorType = request.optString("sensor_type", "")
        
        Log.i(TAG, "Sensor request from $requester: type=$sensorType")
        
        return try {
            when (sensorType) {
                "accelerometer" -> readAccelerometer(requestId)
                "gyroscope" -> readGyroscope(requestId)
                "magnetometer" -> readMagnetometer(requestId)
                "gps" -> readGPS(requestId, requester)
                "light" -> readLight(requestId)
                "proximity" -> readProximity(requestId)
                "battery" -> readBattery(requestId)
                else -> JSONObject().apply {
                    put("request_id", requestId)
                    put("error", "Unknown sensor type: $sensorType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sensor read failed", e)
            JSONObject().apply {
                put("request_id", requestId)
                put("error", e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Read accelerometer data.
     */
    private suspend fun readAccelerometer(requestId: String): JSONObject {
        return readSensor(requestId, Sensor.TYPE_ACCELEROMETER, "accelerometer")
    }
    
    /**
     * Read gyroscope data.
     */
    private suspend fun readGyroscope(requestId: String): JSONObject {
        return readSensor(requestId, Sensor.TYPE_GYROSCOPE, "gyroscope")
    }
    
    /**
     * Read magnetometer data.
     */
    private suspend fun readMagnetometer(requestId: String): JSONObject {
        return readSensor(requestId, Sensor.TYPE_MAGNETIC_FIELD, "magnetometer")
    }
    
    /**
     * Read ambient light sensor.
     */
    private suspend fun readLight(requestId: String): JSONObject {
        return readSensor(requestId, Sensor.TYPE_LIGHT, "light")
    }
    
    /**
     * Read proximity sensor.
     */
    private suspend fun readProximity(requestId: String): JSONObject {
        return readSensor(requestId, Sensor.TYPE_PROXIMITY, "proximity")
    }
    
    /**
     * Generic sensor reading with timeout.
     */
    private suspend fun readSensor(
        requestId: String,
        sensorType: Int,
        sensorName: String
    ): JSONObject = suspendCoroutine { continuation ->
        
        val sensor = sensorManager.getDefaultSensor(sensorType)
        if (sensor == null) {
            continuation.resume(JSONObject().apply {
                put("request_id", requestId)
                put("error", "Sensor not available: $sensorName")
            })
            return@suspendCoroutine
        }
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Unregister immediately after first reading
                sensorManager.unregisterListener(this)
                
                val result = JSONObject().apply {
                    put("request_id", requestId)
                    put("sensor", sensorName)
                    put("timestamp", event.timestamp)
                    
                    val values = JSONArray()
                    for (value in event.values) {
                        values.put(value.toDouble())
                    }
                    put("values", values)
                    put("accuracy", event.accuracy)
                }
                
                if (continuation.context.isActive) {
                    continuation.resume(result)
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Ignore accuracy changes
            }
        }
        
        // Register listener
        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        if (!registered) {
            continuation.resume(JSONObject().apply {
                put("request_id", requestId)
                put("error", "Failed to register sensor listener")
            })
            return@suspendCoroutine
        }
        
        // Timeout after 5 seconds
        scope.launch {
            delay(5000)
            if (continuation.context.isActive) {
                sensorManager.unregisterListener(listener)
                continuation.resume(JSONObject().apply {
                    put("request_id", requestId)
                    put("error", "Sensor read timeout")
                })
            }
        }
    }
    
    /**
     * Read GPS location (requires approval).
     */
    private suspend fun readGPS(requestId: String, requester: String): JSONObject = suspendCoroutine { continuation ->
        
        if (!hasLocationPermission()) {
            continuation.resume(JSONObject().apply {
                put("request_id", requestId)
                put("error", "Location permission not granted")
            })
            return@suspendCoroutine
        }
        
        // TODO: Add user approval flow for privacy
        Log.w(TAG, "GPS requested by $requester - auto-approving for now")
        
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Unregister immediately after first location
                try {
                    locationManager.removeUpdates(this)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to remove location updates", e)
                }
                
                val result = JSONObject().apply {
                    put("request_id", requestId)
                    put("sensor", "gps")
                    put("timestamp", location.time)
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("altitude", location.altitude)
                    put("accuracy", location.accuracy)
                    put("speed", location.speed)
                    put("bearing", location.bearing)
                }
                
                if (continuation.context.isActive) {
                    continuation.resume(result)
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle) {
                // Ignore
            }
            
            override fun onProviderEnabled(provider: String) {
                // Ignore
            }
            
            override fun onProviderDisabled(provider: String) {
                // Ignore
            }
        }
        
        try {
            // Try GPS provider first
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener
            )
            
            // Timeout after 10 seconds
            scope.launch {
                delay(10_000)
                if (continuation.context.isActive) {
                    locationManager.removeUpdates(locationListener)
                    continuation.resume(JSONObject().apply {
                        put("request_id", requestId)
                        put("error", "GPS location timeout")
                    })
                }
            }
        } catch (e: SecurityException) {
            continuation.resume(JSONObject().apply {
                put("request_id", requestId)
                put("error", "Location permission denied")
            })
        } catch (e: Exception) {
            continuation.resume(JSONObject().apply {
                put("request_id", requestId)
                put("error", "GPS error: ${e.message}")
            })
        }
    }
    
    /**
     * Read battery status.
     */
    private suspend fun readBattery(requestId: String): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                
                val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = batteryManager.isCharging
                
                JSONObject().apply {
                    put("request_id", requestId)
                    put("sensor", "battery")
                    put("timestamp", System.currentTimeMillis())
                    put("level", level)
                    put("charging", isCharging)
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("request_id", requestId)
                    put("error", "Battery read error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        scope.cancel()
    }
}
