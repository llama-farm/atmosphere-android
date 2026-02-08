package com.llamafarm.atmosphere.cost

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "CostCollector"

/**
 * Cost factors collected from the device.
 */
data class CostFactors(
    val batteryLevel: Int,           // 0-100
    val isCharging: Boolean,
    val cpuUsage: Float,             // 0.0-1.0
    val memoryPressure: Float,       // 0.0-1.0 (higher = more pressure)
    val thermalState: ThermalState,
    val networkType: NetworkType,
    val signalStrength: Int,         // 0-4 bars equivalent
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Calculate the combined cost multiplier.
     * Lower = cheaper to use this node.
     */
    fun calculateCost(baseCost: Float = 1.0f): Float {
        val batteryMult = calculateBatteryMultiplier()
        val loadMult = calculateLoadMultiplier()
        val networkMult = calculateNetworkMultiplier()
        val thermalMult = calculateThermalMultiplier()
        
        return baseCost * batteryMult * loadMult * networkMult * thermalMult
    }
    
    private fun calculateBatteryMultiplier(): Float {
        // Charging: no penalty
        if (isCharging) return 1.0f
        
        // Battery level penalty curve
        return when {
            batteryLevel >= 80 -> 1.0f
            batteryLevel >= 50 -> 1.2f
            batteryLevel >= 30 -> 1.5f
            batteryLevel >= 15 -> 2.5f
            else -> 5.0f  // Critical battery - strongly discourage use
        }
    }
    
    private fun calculateLoadMultiplier(): Float {
        // CPU + memory combined load factor
        val combinedLoad = (cpuUsage + memoryPressure) / 2
        return when {
            combinedLoad < 0.3f -> 1.0f
            combinedLoad < 0.5f -> 1.2f
            combinedLoad < 0.7f -> 1.5f
            combinedLoad < 0.9f -> 2.0f
            else -> 3.0f  // System under heavy load
        }
    }
    
    private fun calculateNetworkMultiplier(): Float {
        // Base network type cost
        val typeMult = when (networkType) {
            NetworkType.WIFI -> 1.0f
            NetworkType.ETHERNET -> 0.8f  // Wired is best
            NetworkType.CELLULAR_5G -> 1.3f
            NetworkType.CELLULAR_4G -> 1.5f
            NetworkType.CELLULAR_3G -> 2.5f
            NetworkType.CELLULAR_2G -> 5.0f
            NetworkType.UNKNOWN -> 2.0f
            NetworkType.NONE -> 10.0f  // No network - very high cost
        }
        
        // Signal strength modifier (only for cellular)
        val signalMod = if (networkType.isCellular()) {
            when (signalStrength) {
                4 -> 1.0f
                3 -> 1.1f
                2 -> 1.3f
                1 -> 1.6f
                else -> 2.0f
            }
        } else 1.0f
        
        return typeMult * signalMod
    }
    
    private fun calculateThermalMultiplier(): Float {
        return when (thermalState) {
            ThermalState.NONE -> 1.0f
            ThermalState.LIGHT -> 1.2f
            ThermalState.MODERATE -> 1.8f
            ThermalState.SEVERE -> 3.0f
            ThermalState.CRITICAL -> 10.0f
            ThermalState.EMERGENCY -> 100.0f  // Device should cool down
            ThermalState.SHUTDOWN -> Float.MAX_VALUE  // Do not use
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("battery_level", batteryLevel)
            put("is_charging", isCharging)
            put("cpu_usage", cpuUsage)
            put("memory_pressure", memoryPressure)
            put("thermal_state", thermalState.name.lowercase())
            put("network_type", networkType.name.lowercase())
            put("signal_strength", signalStrength)
            put("timestamp", timestamp)
            put("cost", calculateCost())
        }
    }
}

/**
 * Thermal throttling states.
 */
enum class ThermalState {
    NONE,       // No throttling
    LIGHT,      // Light throttling
    MODERATE,   // Moderate throttling
    SEVERE,     // Severe throttling
    CRITICAL,   // Critical - major features disabled
    EMERGENCY,  // Emergency - device must cool down
    SHUTDOWN    // Imminent shutdown
}

/**
 * Network connection types.
 */
enum class NetworkType {
    NONE,
    WIFI,
    ETHERNET,
    CELLULAR_2G,
    CELLULAR_3G,
    CELLULAR_4G,
    CELLULAR_5G,
    UNKNOWN;
    
    fun isCellular(): Boolean = this in listOf(
        CELLULAR_2G, CELLULAR_3G, CELLULAR_4G, CELLULAR_5G
    )
}

/**
 * Collects device cost factors for mesh routing decisions.
 * 
 * Monitors:
 * - Battery level and charging state
 * - CPU usage estimation
 * - Memory pressure
 * - Thermal state (throttling)
 * - Network type and signal strength
 */
class CostCollector(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _costFactors = MutableStateFlow<CostFactors?>(null)
    val costFactors: StateFlow<CostFactors?> = _costFactors.asStateFlow()
    
    private var isCollecting = false
    private var collectionJob: Job? = null
    
    // CPU tracking
    private var lastCpuIdle: Long = 0
    private var lastCpuTotal: Long = 0
    
    /**
     * Start continuous cost collection.
     * 
     * @param intervalMs How often to collect metrics (default 30 seconds)
     */
    fun startCollecting(intervalMs: Long = 30_000L) {
        if (isCollecting) {
            Log.w(TAG, "Already collecting cost factors")
            return
        }
        
        isCollecting = true
        Log.i(TAG, "Starting cost collection (interval: ${intervalMs}ms)")
        
        collectionJob = scope.launch {
            while (isActive && isCollecting) {
                try {
                    val factors = collectFactors()
                    _costFactors.value = factors
                    Log.d(TAG, "Collected cost factors: cost=${factors.calculateCost()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting cost factors", e)
                }
                delay(intervalMs)
            }
        }
    }
    
    /**
     * Stop continuous cost collection.
     */
    fun stopCollecting() {
        isCollecting = false
        collectionJob?.cancel()
        collectionJob = null
        Log.i(TAG, "Stopped cost collection")
    }
    
    /**
     * Collect current cost factors (one-shot).
     */
    fun collectFactors(): CostFactors {
        return CostFactors(
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            cpuUsage = getCpuUsage(),
            memoryPressure = getMemoryPressure(),
            thermalState = getThermalState(),
            networkType = getNetworkType(),
            signalStrength = getSignalStrength()
        )
    }
    
    /**
     * Get current battery level (0-100).
     */
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    /**
     * Check if the device is currently charging.
     */
    private fun isCharging(): Boolean {
        val batteryIntent = context.registerReceiver(
            null, 
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }
    
    /**
     * Estimate CPU usage from /proc/stat.
     * Returns 0.0-1.0 representing percentage.
     */
    private fun getCpuUsage(): Float {
        return try {
            val statFile = File("/proc/stat")
            val lines = statFile.readLines()
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") } ?: return 0f
            
            val parts = cpuLine.split("\\s+".toRegex())
            if (parts.size < 8) return 0f
            
            // CPU times: user, nice, system, idle, iowait, irq, softirq
            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts[5].toLongOrNull() ?: 0L
            val irq = parts[6].toLongOrNull() ?: 0L
            val softirq = parts[7].toLongOrNull() ?: 0L
            
            val currentIdle = idle + iowait
            val currentTotal = user + nice + system + idle + iowait + irq + softirq
            
            val idleDelta = currentIdle - lastCpuIdle
            val totalDelta = currentTotal - lastCpuTotal
            
            lastCpuIdle = currentIdle
            lastCpuTotal = currentTotal
            
            if (totalDelta == 0L) return 0f
            
            val usage = 1.0f - (idleDelta.toFloat() / totalDelta.toFloat())
            usage.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read CPU usage", e)
            0f
        }
    }
    
    /**
     * Get memory pressure (0.0-1.0).
     * Higher values indicate more memory pressure.
     */
    private fun getMemoryPressure(): Float {
        return try {
            val memInfo = android.app.ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(memInfo)
            
            val usedMemory = memInfo.totalMem - memInfo.availMem
            val pressure = usedMemory.toFloat() / memInfo.totalMem.toFloat()
            
            // Also check if system is in low memory state
            if (memInfo.lowMemory) {
                pressure.coerceAtLeast(0.9f)
            } else {
                pressure.coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read memory pressure", e)
            0.5f  // Assume moderate pressure on error
        }
    }
    
    /**
     * Get thermal state from PowerManager.
     * Requires API 29+.
     */
    private fun getThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalState.NONE
        }
        
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalState.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
                else -> ThermalState.NONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read thermal state", e)
            ThermalState.NONE
        }
    }
    
    /**
     * Get current network connection type.
     */
    private fun getNetworkType(): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularGeneration()
            else -> NetworkType.UNKNOWN
        }
    }
    
    /**
     * Determine cellular network generation.
     */
    private fun getCellularGeneration(): NetworkType {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.CELLULAR_5G
                TelephonyManager.NETWORK_TYPE_LTE -> NetworkType.CELLULAR_4G
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> NetworkType.CELLULAR_3G
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT -> NetworkType.CELLULAR_2G
                else -> NetworkType.UNKNOWN
            }
        } catch (e: SecurityException) {
            // Missing READ_PHONE_STATE permission
            Log.w(TAG, "No permission to read network type")
            NetworkType.UNKNOWN
        }
    }
    
    /**
     * Get signal strength (0-4 bars equivalent).
     */
    private fun getSignalStrength(): Int {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telephonyManager.signalStrength?.level ?: 2
            } else {
                2  // Default to middle value for older APIs
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read signal strength", e)
            2
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        stopCollecting()
        scope.cancel()
    }
    
    /**
     * SDK-compatible cost metrics view.
     */
    data class SdkCostMetrics(
        val battery: Float,
        val cpu: Float,
        val memory: Float,
        val networkType: String,
        val thermalState: String,
        val overallCost: Float
    )
    
    /**
     * Get current metrics in SDK-compatible format.
     */
    val currentMetrics: SdkCostMetrics
        get() {
            val factors = costFactors.value ?: collectFactors()
            return SdkCostMetrics(
                battery = factors.batteryLevel / 100f,
                cpu = factors.cpuUsage,
                memory = factors.memoryPressure,
                networkType = factors.networkType.name.lowercase(),
                thermalState = factors.thermalState.name.lowercase(),
                overallCost = factors.calculateCost()
            )
        }
}
