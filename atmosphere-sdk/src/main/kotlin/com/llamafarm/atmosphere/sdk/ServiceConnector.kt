package com.llamafarm.atmosphere.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.llamafarm.atmosphere.IAtmosphereService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages the connection to the Atmosphere AIDL service.
 * Handles binding, reconnection, and lifecycle management.
 */
class ServiceConnector(private val context: Context) {
    
    companion object {
        private const val TAG = "AtmosphereConnector"
        private const val ATMOSPHERE_PACKAGE = "com.llamafarm.atmosphere"
        private const val ATMOSPHERE_DEBUG_PACKAGE = "com.llamafarm.atmosphere.debug"
        private const val BIND_ACTION = "com.llamafarm.atmosphere.BIND"
        private const val DEFAULT_TIMEOUT_MS = 5000L
    }
    
    private var service: IAtmosphereService? = null
    private var isConnected = false
    private val mutex = Mutex()
    private var serviceConnection: ServiceConnection? = null
    private var installedPackage: String? = null
    
    /**
     * Check if Atmosphere app is installed (release or debug).
     */
    fun isAtmosphereInstalled(): Boolean {
        val pm = context.packageManager
        
        // Try release package first
        return try {
            pm.getPackageInfo(ATMOSPHERE_PACKAGE, 0)
            installedPackage = ATMOSPHERE_PACKAGE
            Log.d(TAG, "Found Atmosphere release package")
            true
        } catch (e: Exception) {
            // Try debug package
            try {
                pm.getPackageInfo(ATMOSPHERE_DEBUG_PACKAGE, 0)
                installedPackage = ATMOSPHERE_DEBUG_PACKAGE
                Log.d(TAG, "Found Atmosphere debug package")
                true
            } catch (e2: Exception) {
                Log.w(TAG, "Atmosphere not installed (tried both release and debug packages)")
                false
            }
        }
    }
    
    /**
     * Get the service, connecting if necessary.
     * @param timeoutMs Timeout for connection attempt
     * @return The service, or null if connection failed
     */
    suspend fun getService(timeoutMs: Long = DEFAULT_TIMEOUT_MS): IAtmosphereService? {
        if (isConnected && service != null) {
            return service
        }
        
        return mutex.withLock {
            // Double-check after acquiring lock
            if (isConnected && service != null) {
                return@withLock service
            }
            
            withTimeoutOrNull(timeoutMs) {
                connectToService()
            }
        }
    }
    
    private suspend fun connectToService(): IAtmosphereService? = 
        suspendCancellableCoroutine { continuation ->
            
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    Log.d(TAG, "Service connected")
                    service = IAtmosphereService.Stub.asInterface(binder)
                    isConnected = true
                    if (continuation.isActive) {
                        continuation.resume(service)
                    }
                }
                
                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d(TAG, "Service disconnected")
                    service = null
                    isConnected = false
                }
                
                override fun onBindingDied(name: ComponentName?) {
                    Log.w(TAG, "Binding died")
                    service = null
                    isConnected = false
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
                
                override fun onNullBinding(name: ComponentName?) {
                    Log.w(TAG, "Null binding returned")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
            
            serviceConnection = connection
            
            // Determine which package to use
            val packageToUse = installedPackage ?: run {
                // If not cached, check which one is installed
                if (isAtmosphereInstalled()) {
                    installedPackage
                } else {
                    null
                }
            }
            
            if (packageToUse == null) {
                Log.w(TAG, "No Atmosphere package found - cannot bind service")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
                return@suspendCancellableCoroutine
            }
            
            val intent = Intent(BIND_ACTION).apply {
                setPackage(packageToUse)
            }
            
            Log.d(TAG, "Attempting to bind to package: $packageToUse")
            
            val bound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service", e)
                false
            }
            
            if (!bound) {
                Log.w(TAG, "bindService returned false - is Atmosphere installed?")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
            
            continuation.invokeOnCancellation {
                disconnect()
            }
        }
    
    /**
     * Disconnect from the service.
     */
    fun disconnect() {
        serviceConnection?.let { conn ->
            try {
                context.unbindService(conn)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
        }
        serviceConnection = null
        service = null
        isConnected = false
    }
}
