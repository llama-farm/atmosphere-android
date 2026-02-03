package com.llamafarm.atmosphere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Atmosphere Application class.
 * Handles app-level initialization including native library loading and notification channels.
 */
class AtmosphereApplication : Application() {

    companion object {
        private const val TAG = "AtmosphereApp"
        
        // Notification channel IDs
        const val NOTIFICATION_CHANNEL_SERVICE = "atmosphere_service"
        const val NOTIFICATION_CHANNEL_MESH = "atmosphere_mesh"
        const val NOTIFICATION_CHANNEL_INFERENCE = "inference_channel"
        
        private var nativeLoaded = false
        
        init {
            // Attempt to load native Rust library at class initialization
            try {
                System.loadLibrary("atmosphere_core")
                nativeLoaded = true
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not available: ${e.message}")
                nativeLoaded = false
            }
        }
        
        /**
         * Check if the native library was successfully loaded.
         */
        fun isNativeLoaded(): Boolean = nativeLoaded
        
        /**
         * Attempt to reload the native library if it wasn't loaded initially.
         * Returns true if successfully loaded, false otherwise.
         */
        fun loadNativeLibrary(): Boolean {
            if (nativeLoaded) return true
            
            return try {
                System.loadLibrary("atmosphere_core")
                nativeLoaded = true
                Log.i(TAG, "Native library loaded successfully on retry")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library still not available: ${e.message}")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Create notification channels
        createNotificationChannels()
        
        Log.i(TAG, "Atmosphere application initialized (native: $nativeLoaded)")
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Service notification channel (for foreground service)
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_SERVICE,
            "Atmosphere Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Atmosphere node is running"
            setShowBadge(false)
        }

        // Mesh events channel
        val meshChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_MESH,
            "Mesh Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about mesh network events"
        }

        // Inference channel (for LLM inference service)
        val inferenceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_INFERENCE,
            getString(R.string.notification_channel_inference),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_inference_description)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(
            listOf(serviceChannel, meshChannel, inferenceChannel)
        )
    }
}
