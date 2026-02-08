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
import com.llamafarm.atmosphere.data.AtmospherePreferences
import com.llamafarm.atmosphere.network.ConnectionState
import com.llamafarm.atmosphere.network.MeshConnection
import com.llamafarm.atmosphere.network.MeshMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Foreground service that maintains persistent mesh connectivity.
 * 
 * Simplified version for initial release.
 */
class MeshService : Service() {
    
    companion object {
        private const val TAG = "MeshService"
        private const val NOTIFICATION_ID = 1002
        private const val WAKELOCK_TAG = "Atmosphere:MeshWakeLock"
        
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60_000L
        
        const val ACTION_START = "com.llamafarm.atmosphere.service.START_MESH"
        const val ACTION_STOP = "com.llamafarm.atmosphere.service.STOP_MESH"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_MESH_ID = "mesh_id"
        const val EXTRA_TOKEN = "token"
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var meshConnection: MeshConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var preferences: AtmospherePreferences
    
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var reconnectJob: Job? = null
    
    sealed class ServiceState {
        object Disconnected : ServiceState()
        object Connecting : ServiceState()
        data class Connected(val meshName: String?, val peerCount: Int) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }
    
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Disconnected)
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    
    private val _meshName = MutableStateFlow<String?>(null)
    val meshName: StateFlow<String?> = _meshName.asStateFlow()
    
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    
    // Current saved connection info
    private var savedRelayUrl: String? = null
    private var savedMeshId: String? = null
    private var savedToken: String? = null
    
    // Binder for local binding
    inner class LocalBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }
    
    private val binder = LocalBinder()
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MeshService created")
        preferences = AtmospherePreferences(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                savedRelayUrl = intent.getStringExtra(EXTRA_RELAY_URL)
                savedMeshId = intent.getStringExtra(EXTRA_MESH_ID)
                savedToken = intent.getStringExtra(EXTRA_TOKEN)
                
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                connect()
            }
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        serviceScope.cancel()
        Log.i(TAG, "MeshService destroyed")
    }
    
    private fun connect() {
        val relayUrl = savedRelayUrl ?: run {
            _state.value = ServiceState.Error("No relay URL configured")
            return
        }
        
        _state.value = ServiceState.Connecting
        updateNotification("Connecting...")
        acquireWakeLock()
        
        Log.i(TAG, "Connecting to relay: $relayUrl")
        
        // Create simplified mesh connection
        meshConnection = MeshConnection(this, relayUrl)
        
        // Monitor connection state
        serviceScope.launch {
            meshConnection?.connectionState?.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        Log.i(TAG, "Connected to mesh")
                        _state.value = ServiceState.Connected(_meshName.value, _peerCount.value)
                        currentBackoffMs = INITIAL_BACKOFF_MS
                        releaseWakeLock()
                        updateNotification("Connected")
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (_state.value is ServiceState.Connected) {
                            Log.w(TAG, "Disconnected from mesh")
                            _state.value = ServiceState.Disconnected
                            scheduleReconnect()
                        }
                    }
                    ConnectionState.FAILED -> {
                        Log.e(TAG, "Connection failed")
                        _state.value = ServiceState.Error("Connection failed")
                        scheduleReconnect()
                    }
                    else -> { /* ignore intermediate states */ }
                }
            }
        }
        
        // Monitor messages
        serviceScope.launch {
            meshConnection?.messages?.collect { message ->
                handleMessage(message)
            }
        }
        
        // Actually connect
        meshConnection?.connect()
    }
    
    private fun handleMessage(message: MeshMessage) {
        when (message) {
            is MeshMessage.CapabilityAnnounce -> {
                Log.d(TAG, "Capability announcement from ${message.nodeId}")
            }
            is MeshMessage.InferenceRequest -> {
                Log.d(TAG, "Inference request: ${message.requestId}")
            }
            is MeshMessage.InferenceResponse -> {
                Log.d(TAG, "Inference response: ${message.requestId}")
            }
            is MeshMessage.Error -> {
                Log.e(TAG, "Mesh error: ${message.message}")
            }
            else -> {
                Log.d(TAG, "Other message: $message")
            }
        }
    }
    
    private fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        meshConnection?.disconnect()
        meshConnection = null
        _state.value = ServiceState.Disconnected
        releaseWakeLock()
    }
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            Log.i(TAG, "Reconnecting in ${currentBackoffMs}ms")
            delay(currentBackoffMs)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            connect()
        }
    }
    
    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, AtmosphereApplication.NOTIFICATION_CHANNEL_MESH)
            .setContentTitle("Atmosphere Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }
}
