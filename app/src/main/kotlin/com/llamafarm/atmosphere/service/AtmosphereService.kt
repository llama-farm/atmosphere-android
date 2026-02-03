package com.llamafarm.atmosphere.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.MainActivity
import com.llamafarm.atmosphere.R
import com.llamafarm.atmosphere.bindings.AtmosphereNode
import com.llamafarm.atmosphere.bindings.AtmosphereException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps the Atmosphere node running.
 * Handles mesh network operations and maintains connectivity.
 */
class AtmosphereService : Service() {

    companion object {
        private const val TAG = "AtmosphereService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "Atmosphere:ServiceWakeLock"

        /**
         * Start the Atmosphere service.
         */
        fun start(context: Context) {
            val intent = Intent(context, AtmosphereService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /**
         * Stop the Atmosphere service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, AtmosphereService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        // Service actions
        const val ACTION_START = "com.llamafarm.atmosphere.action.START"
        const val ACTION_STOP = "com.llamafarm.atmosphere.action.STOP"
    }

    // Service state
    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    // Binder for local clients
    inner class LocalBinder : Binder() {
        fun getService(): AtmosphereService = this@AtmosphereService
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Native Atmosphere node
    private var nativeNode: AtmosphereNode? = null

    // Observable state
    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _connectedPeers = MutableStateFlow(0)
    val connectedPeers: StateFlow<Int> = _connectedPeers.asStateFlow()

    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNode()
            ACTION_STOP -> stopNode()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNode()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun startNode() {
        if (_state.value == ServiceState.RUNNING || _state.value == ServiceState.STARTING) {
            Log.d(TAG, "Node already running or starting")
            return
        }

        _state.value = ServiceState.STARTING
        Log.i(TAG, "Starting Atmosphere node...")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Acquire wake lock
        acquireWakeLock()

        // Initialize node in background
        serviceScope.launch {
            try {
                // TODO: Initialize native node
                // This is where we'd call into the Rust library
                initializeNode()

                _state.value = ServiceState.RUNNING
                updateNotification("Connected • 0 peers")
                Log.i(TAG, "Node started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start node", e)
                _state.value = ServiceState.STOPPED
                stopSelf()
            }
        }
    }

    private fun stopNode() {
        if (_state.value == ServiceState.STOPPED || _state.value == ServiceState.STOPPING) {
            Log.d(TAG, "Node already stopped or stopping")
            return
        }

        _state.value = ServiceState.STOPPING
        Log.i(TAG, "Stopping Atmosphere node...")

        serviceScope.launch {
            try {
                // TODO: Gracefully shutdown native node
                shutdownNode()
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown", e)
            } finally {
                releaseWakeLock()
                _state.value = ServiceState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Node stopped")
            }
        }
    }

    private suspend fun initializeNode() {
        try {
            // Check if native library is available
            if (AtmosphereApplication.isNativeLoaded()) {
                Log.i(TAG, "Native library available - initializing real node")
                
                // Generate node ID from native code
                val nodeId = AtmosphereNode.generateNodeId()
                _nodeId.value = nodeId
                
                // Get data directory
                val dataDir = applicationContext.filesDir.absolutePath + "/atmosphere"
                java.io.File(dataDir).mkdirs()
                
                // Create native node
                nativeNode = AtmosphereNode.create(nodeId, dataDir)
                nativeNode?.start()
                
                Log.i(TAG, "Native node started: $nodeId")
                
                // Register default capabilities
                registerDefaultCapabilities()
            } else {
                Log.w(TAG, "Native library not available - running in mock mode")
                _nodeId.value = "mock_" + (1..16).map { ('a'..'z').random() }.joinToString("")
            }
        } catch (e: AtmosphereException) {
            Log.e(TAG, "Failed to initialize native node", e)
            throw e
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
            // Fall back to mock mode
            _nodeId.value = "mock_" + (1..16).map { ('a'..'z').random() }.joinToString("")
        }
    }
    
    private fun registerDefaultCapabilities() {
        try {
            // Register camera capability
            nativeNode?.registerCapability("""
                {"name": "camera", "description": "Take photos with device camera"}
            """.trimIndent())
            
            // Register location capability
            nativeNode?.registerCapability("""
                {"name": "location", "description": "Get device GPS location"}
            """.trimIndent())
            
            // Register microphone capability
            nativeNode?.registerCapability("""
                {"name": "microphone", "description": "Record audio from device mic"}
            """.trimIndent())
            
            Log.i(TAG, "Registered default capabilities")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register capabilities", e)
        }
    }

    private suspend fun shutdownNode() {
        try {
            nativeNode?.let { node ->
                Log.i(TAG, "Stopping native node...")
                node.stop()
                node.destroy()
                Log.i(TAG, "Native node stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native node", e)
        } finally {
            nativeNode = null
            _nodeId.value = null
            _connectedPeers.value = 0
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AtmosphereService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AtmosphereApplication.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("Atmosphere")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Get current service statistics.
     */
    fun getStats(): ServiceStats {
        return ServiceStats(
            state = _state.value,
            nodeId = _nodeId.value,
            connectedPeers = _connectedPeers.value,
            uptime = 0L // TODO: Track actual uptime
        )
    }
    
    /**
     * Get native node status as JSON.
     */
    fun getNativeStatus(): String? {
        return try {
            nativeNode?.statusJson()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get native status", e)
            null
        }
    }
    
    /**
     * Check if native node is running.
     */
    fun isNativeRunning(): Boolean {
        return try {
            nativeNode?.isRunning() ?: false
        } catch (e: Exception) {
            false
        }
    }

    data class ServiceStats(
        val state: ServiceState,
        val nodeId: String?,
        val connectedPeers: Int,
        val uptime: Long
    )
}
