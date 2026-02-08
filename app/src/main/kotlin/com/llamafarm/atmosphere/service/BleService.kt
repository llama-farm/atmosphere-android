package com.llamafarm.atmosphere.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llamafarm.atmosphere.MainActivity
import com.llamafarm.atmosphere.R
import com.llamafarm.atmosphere.transport.BleMessage
import com.llamafarm.atmosphere.transport.BleTransport
import com.llamafarm.atmosphere.transport.MessageType
import com.llamafarm.atmosphere.transport.NodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BleService"
private const val CHANNEL_ID = "ble_mesh_channel"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service for BLE mesh networking.
 * 
 * Handles:
 * - BLE advertising (peripheral mode)
 * - BLE scanning (central mode)
 * - Message relay between peers
 */
class BleService : Service() {
    
    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var bleTransport: BleTransport? = null
    
    // Service state
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()
    
    private val _peers = MutableStateFlow<List<NodeInfo>>(emptyList())
    val peers: StateFlow<List<NodeInfo>> = _peers.asStateFlow()
    
    // Callbacks
    var onMessage: ((BleMessage) -> Unit)? = null
    var onPeerDiscovered: ((NodeInfo) -> Unit)? = null
    var onPeerLost: ((String) -> Unit)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BLE Service created")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BLE Service starting")
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Initialize and start BLE transport
        startBleTransport()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.i(TAG, "BLE Service destroying")
        stopBleTransport()
        scope.cancel()
        super.onDestroy()
    }
    
    private fun startBleTransport() {
        if (_isRunning.value) return
        
        try {
            bleTransport = BleTransport(
                context = this,
                nodeName = "Atmosphere-${Build.MODEL.take(10)}",
                capabilities = listOf("relay", "android")
            ).apply {
                this.onMessage = { message ->
                    Log.d(TAG, "Received message: ${message.header.msgType}")
                    this@BleService.onMessage?.invoke(message)
                }
                
                this.onPeerDiscovered = { info ->
                    Log.i(TAG, "Peer discovered: ${info.name}")
                    _peerCount.value = getPeerCount()
                    updateNotification()
                    this@BleService.onPeerDiscovered?.invoke(info)
                }
                
                this.onPeerLost = { peerId ->
                    Log.i(TAG, "Peer lost: $peerId")
                    _peerCount.value = getPeerCount()
                    updateNotification()
                    this@BleService.onPeerLost?.invoke(peerId)
                }
                
                start()
            }
            
            _isRunning.value = true
            
            // Collect peers state
            scope.launch {
                bleTransport?.peers?.collect { peerList ->
                    _peers.value = peerList
                    _peerCount.value = peerList.size
                }
            }
            
            Log.i(TAG, "BLE transport started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE transport: ${e.message}", e)
            _isRunning.value = false
        }
    }
    
    private fun stopBleTransport() {
        bleTransport?.stop()
        bleTransport = null
        _isRunning.value = false
        _peerCount.value = 0
        _peers.value = emptyList()
    }
    
    /**
     * Send a message via BLE mesh.
     */
    fun sendMessage(
        payload: ByteArray,
        msgType: MessageType = MessageType.DATA,
        ttl: Int = 5,
        target: String? = null
    ): Boolean {
        return bleTransport?.send(payload, msgType, ttl, target) ?: false
    }
    
    /**
     * Broadcast a hello message to discover peers.
     */
    fun broadcastHello() {
        bleTransport?.broadcastHello()
    }
    
    /**
     * Get current peer count.
     */
    fun getPeerCount(): Int = bleTransport?.getPeerCount() ?: 0
    
    /**
     * Get list of connected peers.
     */
    fun getPeers(): List<NodeInfo> = _peers.value
    
    // ========================================================================
    // Notification Management
    // ========================================================================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Atmosphere BLE mesh networking"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val peerText = when (val count = _peerCount.value) {
            0 -> "Searching for peers..."
            1 -> "Connected to 1 peer"
            else -> "Connected to $count peers"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Atmosphere BLE Mesh")
            .setContentText(peerText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
