package com.llamafarm.atmosphere.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and storing GGUF models from HuggingFace.
 */
class ModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val CHUNK_SIZE = 8192
        
        // Default model: IBM Granite (supported by ARM AiChat/llama.cpp)
        val DEFAULT_MODEL = ModelConfig(
            id = "granite-3.1-1b-q4km",
            name = "Granite 3.1 1B Instruct Q4_K_M",
            huggingFaceRepo = "ibm-granite/granite-3.1-1b-a400m-instruct-GGUF",
            fileName = "granite-3.1-1b-a400m-instruct.Q4_K_M.gguf",
            sizeBytes = 300_000_000L, // ~300MB
            description = "IBM Granite 3.1 1B - tiny, fast, supported by ARM AiChat"
        )
        
        // Available models (all supported by ARM AiChat/llama.cpp)
        val AVAILABLE_MODELS = listOf(
            DEFAULT_MODEL,
            ModelConfig(
                id = "granite-3.1-2b-q4km",
                name = "Granite 3.1 2B Instruct Q4_K_M",
                huggingFaceRepo = "ibm-granite/granite-3.1-2b-instruct-GGUF",
                fileName = "granite-3.1-2b-instruct.Q4_K_M.gguf",
                sizeBytes = 1_300_000_000L, // ~1.3GB
                description = "IBM Granite 3.1 2B - balanced quality and speed"
            ),
            ModelConfig(
                id = "llama-3.2-1b-q4km",
                name = "Llama 3.2 1B Instruct Q4_K_M",
                huggingFaceRepo = "lmstudio-community/Llama-3.2-1B-Instruct-GGUF",
                fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                sizeBytes = 800_000_000L, // ~800MB
                description = "Meta's Llama 3.2 1B instruction-tuned model"
            ),
            ModelConfig(
                id = "llama-3.2-3b-q4km",
                name = "Llama 3.2 3B Instruct Q4_K_M",
                huggingFaceRepo = "lmstudio-community/Llama-3.2-3B-Instruct-GGUF",
                fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                sizeBytes = 2_000_000_000L, // ~2GB
                description = "Meta's Llama 3.2 3B instruction-tuned model"
            )
        )
    }
    
    data class ModelConfig(
        val id: String,
        val name: String,
        val huggingFaceRepo: String,
        val fileName: String,
        val sizeBytes: Long,
        val description: String
    ) {
        val downloadUrl: String
            get() = "https://huggingface.co/$huggingFaceRepo/resolve/main/$fileName"
    }
    
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
        object Cancelled : DownloadState()
    }
    
    data class ModelInfo(
        val config: ModelConfig,
        val localPath: String,
        val isDownloaded: Boolean,
        val isBundled: Boolean,
        val fileSizeBytes: Long
    )
    
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }
    
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    
    // Asset path for bundled models
    private val BUNDLED_MODELS_PATH = "models"
    
    /**
     * Check if a model is bundled in app assets.
     */
    private fun isBundledModel(config: ModelConfig): Boolean {
        return try {
            val assets = context.assets.list(BUNDLED_MODELS_PATH) ?: emptyArray()
            assets.contains(config.fileName)
        } catch (e: Exception) {
            Log.d(TAG, "No bundled models found: ${e.message}")
            false
        }
    }
    
    /**
     * Extract bundled model from assets to internal storage.
     * This is done on first launch if model is bundled.
     */
    suspend fun extractBundledModel(config: ModelConfig = DEFAULT_MODEL): Result<String> = withContext(Dispatchers.IO) {
        val targetFile = File(modelsDir, config.fileName)
        
        // Already extracted
        if (targetFile.exists() && targetFile.length() > config.sizeBytes * 0.9) {
            Log.i(TAG, "Bundled model already extracted: ${targetFile.absolutePath}")
            return@withContext Result.success(targetFile.absolutePath)
        }
        
        // Check if bundled
        if (!isBundledModel(config)) {
            return@withContext Result.failure(Exception("Model not bundled in APK"))
        }
        
        Log.i(TAG, "Extracting bundled model: ${config.fileName}")
        _downloadState.value = DownloadState.Downloading(0f, 0, config.sizeBytes)
        _currentDownloadModel.value = config
        
        try {
            context.assets.open("$BUNDLED_MODELS_PATH/${config.fileName}").use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        val progress = totalBytesRead.toFloat() / config.sizeBytes
                        _downloadState.value = DownloadState.Downloading(progress, totalBytesRead, config.sizeBytes)
                    }
                }
            }
            
            Log.i(TAG, "Bundled model extracted: ${targetFile.absolutePath}")
            _downloadState.value = DownloadState.Completed
            _currentDownloadModel.value = null
            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bundled model", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Extraction failed")
            _currentDownloadModel.value = null
            targetFile.delete()
            Result.failure(e)
        }
    }
    
    /**
     * Initialize model - extract bundled if available, otherwise needs download.
     * Call this on app startup.
     */
    suspend fun initializeDefaultModel(): Result<String> = withContext(Dispatchers.IO) {
        // Check if already ready
        val existingPath = getDefaultModelPath()
        if (existingPath != null) {
            Log.i(TAG, "Default model already available: $existingPath")
            return@withContext Result.success(existingPath)
        }
        
        // Try to extract bundled
        if (isBundledModel(DEFAULT_MODEL)) {
            return@withContext extractBundledModel(DEFAULT_MODEL)
        }
        
        // Not bundled and not downloaded - needs manual download
        Result.failure(Exception("Model not available - download required"))
    }
    
    /**
     * Check if bundled model is available for faster startup.
     */
    fun hasBundledDefaultModel(): Boolean = isBundledModel(DEFAULT_MODEL)
    
    private val _currentDownloadModel = MutableStateFlow<ModelConfig?>(null)
    val currentDownloadModel: StateFlow<ModelConfig?> = _currentDownloadModel.asStateFlow()
    
    @Volatile
    private var cancelDownload = false
    
    /**
     * Get list of all models with their download status.
     */
    fun getModels(): List<ModelInfo> {
        return AVAILABLE_MODELS.map { config ->
            val localFile = File(modelsDir, config.fileName)
            val bundled = isBundledModel(config)
            ModelInfo(
                config = config,
                localPath = localFile.absolutePath,
                isDownloaded = localFile.exists() && localFile.length() > 0,
                isBundled = bundled,
                fileSizeBytes = if (localFile.exists()) localFile.length() else 0
            )
        }
    }
    
    /**
     * Get model info by ID.
     */
    fun getModelById(id: String): ModelInfo? {
        val config = AVAILABLE_MODELS.find { it.id == id } ?: return null
        val localFile = File(modelsDir, config.fileName)
        return ModelInfo(
            config = config,
            localPath = localFile.absolutePath,
            isDownloaded = localFile.exists() && localFile.length() > 0,
            isBundled = isBundledModel(config),
            fileSizeBytes = if (localFile.exists()) localFile.length() else 0
        )
    }
    
    /**
     * Check if default model is downloaded and ready.
     */
    fun isDefaultModelReady(): Boolean {
        val modelFile = File(modelsDir, DEFAULT_MODEL.fileName)
        return modelFile.exists() && modelFile.length() > DEFAULT_MODEL.sizeBytes * 0.9
    }
    
    /**
     * Get the path to the default model (or null if not downloaded).
     */
    fun getDefaultModelPath(): String? {
        val modelFile = File(modelsDir, DEFAULT_MODEL.fileName)
        return if (modelFile.exists() && modelFile.length() > 0) {
            modelFile.absolutePath
        } else null
    }
    
    /**
     * Get path to a specific model by ID.
     */
    fun getModelPath(modelId: String): String? {
        val config = AVAILABLE_MODELS.find { it.id == modelId } ?: return null
        val modelFile = File(modelsDir, config.fileName)
        return if (modelFile.exists() && modelFile.length() > 0) {
            modelFile.absolutePath
        } else null
    }
    
    /**
     * Download a model from HuggingFace.
     */
    suspend fun downloadModel(config: ModelConfig = DEFAULT_MODEL): Result<String> = withContext(Dispatchers.IO) {
        if (_downloadState.value is DownloadState.Downloading) {
            return@withContext Result.failure(IllegalStateException("Download already in progress"))
        }
        
        cancelDownload = false
        _currentDownloadModel.value = config
        _downloadState.value = DownloadState.Downloading(0f, 0, config.sizeBytes)
        
        val destFile = File(modelsDir, config.fileName)
        val tempFile = File(modelsDir, "${config.fileName}.tmp")
        
        try {
            Log.i(TAG, "Starting download: ${config.downloadUrl}")
            
            val url = URL(config.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Atmosphere-Android/1.0")
            
            // Support resuming downloads
            var downloadedBytes = 0L
            if (tempFile.exists()) {
                downloadedBytes = tempFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                Log.i(TAG, "Resuming download from byte $downloadedBytes")
            }
            
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw Exception("Server returned HTTP $responseCode")
            }
            
            val totalBytes = connection.contentLengthLong + downloadedBytes
            Log.i(TAG, "Total size: $totalBytes bytes")
            
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
            
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var totalDownloaded = downloadedBytes
            var lastProgressUpdate = System.currentTimeMillis()
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (cancelDownload) {
                    inputStream.close()
                    outputStream.close()
                    _downloadState.value = DownloadState.Cancelled
                    _currentDownloadModel.value = null
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                
                outputStream.write(buffer, 0, bytesRead)
                totalDownloaded += bytesRead
                
                // Update progress at most every 100ms
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100) {
                    val progress = totalDownloaded.toFloat() / totalBytes.toFloat()
                    _downloadState.value = DownloadState.Downloading(progress, totalDownloaded, totalBytes)
                    lastProgressUpdate = now
                }
            }
            
            inputStream.close()
            outputStream.close()
            connection.disconnect()
            
            // Rename temp file to final
            if (destFile.exists()) {
                destFile.delete()
            }
            tempFile.renameTo(destFile)
            
            Log.i(TAG, "Download complete: ${destFile.absolutePath}")
            _downloadState.value = DownloadState.Completed
            _currentDownloadModel.value = null
            
            Result.success(destFile.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
            _currentDownloadModel.value = null
            Result.failure(e)
        }
    }
    
    /**
     * Cancel ongoing download.
     */
    fun cancelDownload() {
        cancelDownload = true
    }
    
    /**
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String): Boolean {
        val config = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val modelFile = File(modelsDir, config.fileName)
        val tempFile = File(modelsDir, "${config.fileName}.tmp")
        
        var deleted = false
        if (modelFile.exists()) {
            deleted = modelFile.delete()
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        return deleted
    }
    
    /**
     * Get total storage used by models.
     */
    fun getTotalStorageUsed(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0
    }
    
    /**
     * Get available storage space.
     */
    fun getAvailableStorage(): Long {
        return modelsDir.freeSpace
    }
    
    /**
     * Reset download state to idle.
     */
    fun resetDownloadState() {
        if (_downloadState.value !is DownloadState.Downloading) {
            _downloadState.value = DownloadState.Idle
            _currentDownloadModel.value = null
        }
    }
}
