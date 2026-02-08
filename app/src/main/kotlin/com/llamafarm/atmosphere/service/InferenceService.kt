package com.llamafarm.atmosphere.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.llamafarm.atmosphere.AtmosphereApplication
import com.llamafarm.atmosphere.R
import com.llamafarm.atmosphere.inference.LlamaCppEngine
import com.llamafarm.atmosphere.inference.LocalInferenceEngine
import com.llamafarm.atmosphere.inference.ModelManager
import com.llamafarm.atmosphere.inference.UniversalRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for running local LLM inference.
 * 
 * This service manages the LocalInferenceEngine and UniversalRuntime,
 * keeping the model loaded in memory while the service is running.
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        const val NOTIFICATION_ID = 2
        
        const val ACTION_START = "com.llamafarm.atmosphere.START_INFERENCE"
        const val ACTION_STOP = "com.llamafarm.atmosphere.STOP_INFERENCE"
        const val ACTION_LOAD_MODEL = "com.llamafarm.atmosphere.LOAD_MODEL"
        const val ACTION_UNLOAD_MODEL = "com.llamafarm.atmosphere.UNLOAD_MODEL"
        
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_PERSONA = "persona"
        
        /**
         * Start the inference service.
         */
        fun start(context: Context, modelId: String? = null, persona: String? = null) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_START
                modelId?.let { putExtra(EXTRA_MODEL_ID, it) }
                persona?.let { putExtra(EXTRA_PERSONA, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the inference service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, InferenceService::class.java))
        }
        
        /**
         * Load a model in the running service.
         */
        fun loadModel(context: Context, modelId: String, persona: String? = null) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_LOAD_MODEL
                putExtra(EXTRA_MODEL_ID, modelId)
                persona?.let { putExtra(EXTRA_PERSONA, it) }
            }
            context.startService(intent)
        }
        
        /**
         * Unload the current model.
         */
        fun unloadModel(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_UNLOAD_MODEL
            }
            context.startService(intent)
        }
    }
    
    /**
     * Service state.
     */
    sealed class ServiceState {
        object Stopped : ServiceState()
        object Starting : ServiceState()
        object Running : ServiceState()
        object ModelLoaded : ServiceState()
        data class Error(val message: String) : ServiceState()
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Components
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceEngine: LocalInferenceEngine
    private lateinit var runtime: UniversalRuntime
    
    // State
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InferenceService created")
        
        // Initialize components
        modelManager = ModelManager(this)
        inferenceEngine = LocalInferenceEngine.getInstance(this)
        // Use UniversalRuntime.create() to get LlamaCppEngine-backed runtime
        // This supports Qwen3 and other architectures not in the ARM whitelist
        runtime = UniversalRuntime.create(this, modelManager)
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                val persona = intent.getStringExtra(EXTRA_PERSONA)
                if (modelId != null) {
                    loadModelAsync(modelId, persona)
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_LOAD_MODEL -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                val persona = intent.getStringExtra(EXTRA_PERSONA)
                if (modelId != null) {
                    loadModelAsync(modelId, persona)
                }
            }
            ACTION_UNLOAD_MODEL -> unloadModelAsync()
        }
        return START_STICKY
    }
    
    private fun startForegroundService() {
        _serviceState.value = ServiceState.Starting
        
        val notification = createNotification("Initializing...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        _serviceState.value = ServiceState.Running
        Log.i(TAG, "InferenceService started in foreground")
    }
    
    private fun loadModelAsync(modelId: String, personaName: String?) {
        serviceScope.launch {
            try {
                _serviceState.value = ServiceState.Starting
                updateNotification("Loading model...")
                
                val persona = personaName?.let { name ->
                    UniversalRuntime.Persona.entries.find { it.name == name }
                } ?: UniversalRuntime.Persona.ASSISTANT
                
                val config = UniversalRuntime.Config(
                    modelId = modelId,
                    persona = persona
                )
                
                val result = runtime.initialize(config)
                
                if (result.isSuccess) {
                    _isModelLoaded.value = true
                    _currentModelId.value = modelId
                    _serviceState.value = ServiceState.ModelLoaded
                    
                    val modelName = modelManager.getModelById(modelId)?.config?.name ?: modelId
                    updateNotification("$modelName ready")
                    Log.i(TAG, "Model loaded: $modelId with persona: $persona")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    _serviceState.value = ServiceState.Error(error)
                    updateNotification("Error: $error")
                    Log.e(TAG, "Failed to load model: $error")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                _serviceState.value = ServiceState.Error(e.message ?: "Unknown error")
                updateNotification("Error: ${e.message}")
            }
        }
    }
    
    private fun unloadModelAsync() {
        serviceScope.launch {
            try {
                runtime.shutdown()
                _isModelLoaded.value = false
                _currentModelId.value = null
                _serviceState.value = ServiceState.Running
                updateNotification("Ready")
                Log.i(TAG, "Model unloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model", e)
            }
        }
    }
    
    /**
     * Get the model manager instance.
     */
    fun getModelManager(): ModelManager = modelManager
    
    /**
     * Get the runtime instance.
     */
    fun getRuntime(): UniversalRuntime = runtime
    
    /**
     * Get the inference engine instance.
     */
    fun getInferenceEngine(): LocalInferenceEngine = inferenceEngine
    
    /**
     * Check if native inference is available.
     */
    fun isNativeAvailable(): Boolean = LlamaCppEngine.isNativeAvailable()
    
    /**
     * Send a chat message and get streaming response.
     */
    fun chat(message: String): Flow<String>? {
        return if (_isModelLoaded.value) {
            runtime.chat(message)
        } else {
            null
        }
    }
    
    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        runtime.cancelGeneration()
    }
    
    /**
     * Clear chat history.
     */
    fun clearHistory() {
        runtime.clearHistory()
    }
    
    /**
     * Change persona.
     */
    suspend fun setPersona(persona: UniversalRuntime.Persona): Result<Unit> {
        return runtime.setPersona(persona)
    }
    
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, AtmosphereApplication.NOTIFICATION_CHANNEL_INFERENCE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        Log.i(TAG, "InferenceService destroying")
        
        serviceScope.launch {
            try {
                runtime.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error during shutdown", e)
            }
        }
        
        serviceScope.cancel()
        _serviceState.value = ServiceState.Stopped
        _isModelLoaded.value = false
        _currentModelId.value = null
        
        super.onDestroy()
    }
}
