package com.llamafarm.atmosphere.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.R

class InferenceService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.llamafarm.atmosphere.START_INFERENCE"
        const val ACTION_STOP = "com.llamafarm.atmosphere.STOP_INFERENCE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInference()
            ACTION_STOP -> stopInference()
        }
        return START_STICKY
    }

    private fun startInference() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopInference() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AtmosphereApplication.NOTIFICATION_CHANNEL_INFERENCE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.inference_running))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
