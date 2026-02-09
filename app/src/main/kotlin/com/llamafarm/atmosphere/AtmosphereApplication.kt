package com.llamafarm.atmosphere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.llamafarm.atmosphere.router.SemanticRouter
import com.llamafarm.atmosphere.router.DefaultCapabilities
import com.llamafarm.atmosphere.cost.CostCollector
import com.llamafarm.atmosphere.network.MeshConnection
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.inference.ModelManager
import com.llamafarm.atmosphere.auth.IdentityManager
import com.llamafarm.atmosphere.service.ServiceManager
import com.llamafarm.atmosphere.service.AtmosphereService
import com.llamafarm.atmosphere.capabilities.CameraCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                System.loadLibrary("atmosphere_android")
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
                System.loadLibrary("atmosphere_android")
                nativeLoaded = true
                Log.i(TAG, "Native library loaded successfully on retry")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library still not available: ${e.message}")
                false
            }
        }
    }
    
    // Public services for SDK binder access
    lateinit var semanticRouter: SemanticRouter
        private set

    lateinit var identityManager: IdentityManager
        private set
    
    var meshConnection: MeshConnection? = null
        private set
    
    var costCollector: CostCollector? = null
        private set
    
    var localInferenceEngine: LocalInferenceEngine? = null
        private set
    
    var modelManager: ModelManager? = null
        private set
    
    var cameraCapability: CameraCapability? = null
        private set
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        // Initialize identity
        identityManager = IdentityManager(this)
        identityManager.loadOrCreateIdentity()

        // Create notification channels
        createNotificationChannels()
        
        // Initialize services
        initializeServices()
        
        // Initialize ServiceManager (but don't start service from Application.onCreate())
        // Service will be started from MainActivity to avoid ForegroundServiceStartNotAllowedException on Android 12+
        ServiceManager.initialize(this)
        
        Log.i(TAG, "Atmosphere application initialized (node: ${identityManager.nodeId})")
    }
    
    private fun initializeServices() {
        // Initialize semantic router with default capabilities
        semanticRouter = SemanticRouter.getInstance(this)
        DefaultCapabilities.registerAll(semanticRouter)
        Log.d(TAG, "Semantic router initialized")
        
        // Cost collector - will be started when service starts
        try {
            costCollector = CostCollector(this)
            Log.d(TAG, "Cost collector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize cost collector: ${e.message}")
        }
        
        // Mesh connection - initialized lazily when user joins mesh
        // meshConnection will be set when connectToMesh() is called
        meshConnection = null
        
        // Local inference engine - initialized lazily
        try {
            localInferenceEngine = LocalInferenceEngine.getInstance(this)
            Log.d(TAG, "Local inference engine initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize local inference engine: ${e.message}")
        }
        
        // Model manager - extract bundled model on first launch
        try {
            modelManager = ModelManager(this)
            
            // Check if bundled model exists and extract it in background
            if (modelManager!!.hasBundledDefaultModel() && !modelManager!!.isDefaultModelReady()) {
                Log.i(TAG, "Bundled model detected, extracting in background...")
                applicationScope.launch {
                    val result = modelManager!!.initializeDefaultModel()
                    if (result.isSuccess) {
                        Log.i(TAG, "Bundled model extracted successfully: ${result.getOrNull()}")
                    } else {
                        Log.w(TAG, "Failed to extract bundled model: ${result.exceptionOrNull()?.message}")
                    }
                }
            } else if (modelManager!!.isDefaultModelReady()) {
                Log.d(TAG, "Default model already ready")
            } else {
                Log.d(TAG, "No bundled model, user will need to download")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize model manager: ${e.message}")
        }
        
        // Camera capability for vision tasks
        try {
            cameraCapability = CameraCapability(this)
            Log.d(TAG, "Camera capability initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize camera capability: ${e.message}")
        }
    }
    
    /**
     * Connect to a mesh network.
     */
    fun connectToMesh(endpoint: String, token: String): MeshConnection {
        // Disconnect existing connection if any
        meshConnection?.disconnect()
        
        val connection = MeshConnection(this, endpoint)
        meshConnection = connection
        return connection
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
