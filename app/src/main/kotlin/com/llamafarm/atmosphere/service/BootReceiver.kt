package com.llamafarm.atmosphere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.llamafarm.atmosphere.data.AtmospherePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles device boot events to restore mesh connection.
 * 
 * When device boots:
 * 1. Check if auto-reconnect is enabled
 * 2. Check if we have saved mesh credentials
 * 3. Start AtmosphereService to reconnect
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        
        if (intent?.action in validActions) {
            Log.i(TAG, "Device booted, checking mesh auto-reconnect...")
            
            // Use goAsync() since we need to do async work
            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    checkAndReconnectMesh(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during boot mesh check", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
    
    private suspend fun checkAndReconnectMesh(context: Context) {
        val preferences = AtmospherePreferences(context)
        
        // Check if auto-reconnect is enabled
        val autoReconnect = preferences.autoReconnectMesh.first()
        if (!autoReconnect) {
            Log.d(TAG, "Auto-reconnect disabled, skipping")
            return
        }
        
        // Check if we have saved credentials
        val endpoint = preferences.lastMeshEndpoint.first()
        val token = preferences.lastMeshToken.first()
        
        if (endpoint != null && token != null) {
            Log.i(TAG, "Found saved mesh credentials, starting service...")
            
            // Start the AtmosphereService which will handle reconnection
            try {
                // TODO: Implement mesh service auto-start with new API
                Log.d(TAG, "Boot receiver - mesh service auto-start not yet implemented")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh service on boot", e)
            }
        } else {
            Log.d(TAG, "No saved mesh credentials")
        }
    }
}
