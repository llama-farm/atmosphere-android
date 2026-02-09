package com.llamafarm.atmosphere.vision

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

private const val TAG = "VisionModelManager"

/**
 * Metadata for a vision model.
 */
data class VisionModelMetadata(
    val modelId: String,
    val version: String,
    val baseModel: String,              // "mobilenet_v3_small", "yolov8n"
    val classMap: Map<Int, String>,     // Label index -> class name
    val inputSize: Int = 224,           // Model input size (224, 320, 640, etc.)
    val quantized: Boolean = true,
    val fileSize: Long,
    val downloadUrl: String? = null,
    val sourceNode: String? = null,     // Where this model came from
    val trainingDate: Long,
    val accuracy: Float? = null,        // Validation accuracy if available
    val description: String = ""
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("model_id", modelId)
        json.put("version", version)
        json.put("base_model", baseModel)
        
        val classMapJson = JSONObject()
        classMap.forEach { (index, className) ->
            classMapJson.put(index.toString(), className)
        }
        json.put("class_map", classMapJson)
        
        json.put("input_size", inputSize)
        json.put("quantized", quantized)
        json.put("file_size", fileSize)
        downloadUrl?.let { json.put("download_url", it) }
        sourceNode?.let { json.put("source_node", it) }
        json.put("training_date", trainingDate)
        accuracy?.let { json.put("accuracy", it) }
        json.put("description", description)
        
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): VisionModelMetadata {
            val classMapJson = json.getJSONObject("class_map")
            val classMap = mutableMapOf<Int, String>()
            classMapJson.keys().forEach { key ->
                classMap[key.toInt()] = classMapJson.getString(key)
            }
            
            return VisionModelMetadata(
                modelId = json.getString("model_id"),
                version = json.getString("version"),
                baseModel = json.getString("base_model"),
                classMap = classMap,
                inputSize = json.optInt("input_size", 224),
                quantized = json.optBoolean("quantized", true),
                fileSize = json.getLong("file_size"),
                downloadUrl = json.optString("download_url", null),
                sourceNode = json.optString("source_node", null),
                trainingDate = json.getLong("training_date"),
                accuracy = if (json.has("accuracy")) json.getDouble("accuracy").toFloat() else null,
                description = json.optString("description", "")
            )
        }
    }
}

/**
 * Manages vision models: storage, versioning, downloading, hot-swapping.
 * 
 * Storage layout:
 * - {app_data}/vision_models/
 *   - mobilenet_v3_small_v1/
 *     - model.tflite
 *     - metadata.json
 *     - class_map.json
 *   - mobilenet_v3_small_v2/
 *     - model.tflite
 *     - metadata.json
 *     - class_map.json
 */
class VisionModelManager(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Base directory for vision models
    private val modelsDir = File(context.filesDir, "vision_models").apply {
        if (!exists()) mkdirs()
    }
    
    // Current active model
    private val _activeModel = MutableStateFlow<VisionModelMetadata?>(null)
    val activeModel: StateFlow<VisionModelMetadata?> = _activeModel.asStateFlow()
    
    // All installed models
    private val _installedModels = MutableStateFlow<List<VisionModelMetadata>>(emptyList())
    val installedModels: StateFlow<List<VisionModelMetadata>> = _installedModels.asStateFlow()
    
    // Download progress
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()
    
    init {
        loadInstalledModels()
    }
    
    /**
     * Scan models directory and load metadata.
     */
    private fun loadInstalledModels() {
        val models = mutableListOf<VisionModelMetadata>()
        
        modelsDir.listFiles()?.forEach { modelDir ->
            if (modelDir.isDirectory) {
                val metadataFile = File(modelDir, "metadata.json")
                if (metadataFile.exists()) {
                    try {
                        val json = JSONObject(metadataFile.readText())
                        val metadata = VisionModelMetadata.fromJson(json)
                        models.add(metadata)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load model metadata: ${modelDir.name}", e)
                    }
                }
            }
        }
        
        _installedModels.value = models
        Log.i(TAG, "Loaded ${models.size} installed models")
        
        // Set first model as active if none set
        if (_activeModel.value == null && models.isNotEmpty()) {
            _activeModel.value = models.first()
        }
    }
    
    /**
     * Get the path to the active model's TFLite file.
     */
    fun getActiveModelPath(): String? {
        val active = _activeModel.value ?: return null
        val modelFile = File(modelsDir, "${active.modelId}_${active.version}/model.tflite")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }
    
    /**
     * Get model file path by model ID and version.
     */
    fun getModelPath(modelId: String, version: String): String? {
        val modelFile = File(modelsDir, "${modelId}_${version}/model.tflite")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }
    
    /**
     * Hot-swap the active model.
     */
    fun setActiveModel(modelId: String, version: String): Boolean {
        val model = _installedModels.value.find { 
            it.modelId == modelId && it.version == version 
        }
        
        if (model == null) {
            Log.e(TAG, "Model not found: $modelId v$version")
            return false
        }
        
        val modelFile = File(modelsDir, "${modelId}_${version}/model.tflite")
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found: ${modelFile.path}")
            return false
        }
        
        _activeModel.value = model
        Log.i(TAG, "Switched active model to: $modelId v$version")
        return true
    }
    
    /**
     * Install a model from a local file path.
     */
    suspend fun installModel(
        modelFile: File,
        metadata: VisionModelMetadata
    ): Result<VisionModelMetadata> = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(modelsDir, "${metadata.modelId}_${metadata.version}")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            // Copy model file
            val destModelFile = File(modelDir, "model.tflite")
            modelFile.copyTo(destModelFile, overwrite = true)
            
            // Write metadata
            val metadataFile = File(modelDir, "metadata.json")
            metadataFile.writeText(metadata.toJson().toString(2))
            
            // Write class map
            val classMapFile = File(modelDir, "class_map.json")
            val classMapJson = JSONObject()
            metadata.classMap.forEach { (index, className) ->
                classMapJson.put(index.toString(), className)
            }
            classMapFile.writeText(classMapJson.toString(2))
            
            Log.i(TAG, "Installed model: ${metadata.modelId} v${metadata.version}")
            
            // Reload models
            loadInstalledModels()
            
            Result.success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install model", e)
            Result.failure(e)
        }
    }
    
    /**
     * Download and install a model from a URL.
     */
    suspend fun downloadAndInstallModel(
        downloadUrl: String,
        metadata: VisionModelMetadata
    ): Result<VisionModelMetadata> = withContext(Dispatchers.IO) {
        try {
            _downloadProgress.value = DownloadProgress(
                modelId = metadata.modelId,
                version = metadata.version,
                bytesDownloaded = 0,
                totalBytes = metadata.fileSize,
                status = "Downloading..."
            )
            
            val modelDir = File(modelsDir, "${metadata.modelId}_${metadata.version}")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            
            val destFile = File(modelDir, "model.tflite")
            
            // Download file with progress
            URL(downloadUrl).openStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        _downloadProgress.value = DownloadProgress(
                            modelId = metadata.modelId,
                            version = metadata.version,
                            bytesDownloaded = totalBytesRead,
                            totalBytes = metadata.fileSize,
                            status = "Downloading..."
                        )
                    }
                }
            }
            
            // Write metadata
            val metadataFile = File(modelDir, "metadata.json")
            metadataFile.writeText(metadata.toJson().toString(2))
            
            // Write class map
            val classMapFile = File(modelDir, "class_map.json")
            val classMapJson = JSONObject()
            metadata.classMap.forEach { (index, className) ->
                classMapJson.put(index.toString(), className)
            }
            classMapFile.writeText(classMapJson.toString(2))
            
            _downloadProgress.value = DownloadProgress(
                modelId = metadata.modelId,
                version = metadata.version,
                bytesDownloaded = metadata.fileSize,
                totalBytes = metadata.fileSize,
                status = "Complete"
            )
            
            Log.i(TAG, "Downloaded and installed model: ${metadata.modelId} v${metadata.version}")
            
            // Reload models
            loadInstalledModels()
            
            delay(1000)  // Brief delay to show completion
            _downloadProgress.value = null
            
            Result.success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model", e)
            _downloadProgress.value = DownloadProgress(
                modelId = metadata.modelId,
                version = metadata.version,
                bytesDownloaded = 0,
                totalBytes = metadata.fileSize,
                status = "Error: ${e.message}"
            )
            Result.failure(e)
        }
    }
    
    /**
     * Download and install a model package (tar.gz or zip) from LlamaFarm.
     */
    suspend fun downloadModelPackage(
        packageUrl: String,
        sourceNode: String
    ): Result<VisionModelMetadata> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Downloading model package from $packageUrl")
            
            val tempFile = File(context.cacheDir, "model_package_${System.currentTimeMillis()}.zip")
            
            // Download package
            URL(packageUrl).openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Extract package
            val extractedDir = File(context.cacheDir, "model_extract_${System.currentTimeMillis()}")
            extractedDir.mkdirs()
            
            ZipInputStream(tempFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(extractedDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            
            // Read metadata
            val metadataFile = File(extractedDir, "metadata.json")
            if (!metadataFile.exists()) {
                throw Exception("Package missing metadata.json")
            }
            
            val json = JSONObject(metadataFile.readText())
            val metadata = VisionModelMetadata.fromJson(json).copy(
                sourceNode = sourceNode,
                downloadUrl = packageUrl
            )
            
            // Install the model
            val modelFile = File(extractedDir, "model.tflite")
            if (!modelFile.exists()) {
                // Try model.pt (PyTorch - would need conversion)
                val ptFile = File(extractedDir, "model.pt")
                if (ptFile.exists()) {
                    throw Exception("PyTorch models not yet supported on Android (need TFLite conversion)")
                }
                throw Exception("Package missing model file")
            }
            
            val result = installModel(modelFile, metadata)
            
            // Cleanup
            tempFile.delete()
            extractedDir.deleteRecursively()
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model package", e)
            Result.failure(e)
        }
    }
    
    /**
     * Delete a model.
     */
    fun deleteModel(modelId: String, version: String): Boolean {
        val modelDir = File(modelsDir, "${modelId}_${version}")
        if (!modelDir.exists()) {
            Log.w(TAG, "Model directory not found: ${modelDir.path}")
            return false
        }
        
        // Don't delete if it's the active model
        val active = _activeModel.value
        if (active != null && active.modelId == modelId && active.version == version) {
            Log.w(TAG, "Cannot delete active model")
            return false
        }
        
        val success = modelDir.deleteRecursively()
        if (success) {
            Log.i(TAG, "Deleted model: $modelId v$version")
            loadInstalledModels()
        }
        
        return success
    }
    
    /**
     * Handle MODEL_AVAILABLE gossip message from mesh.
     */
    suspend fun handleModelAvailable(message: JSONObject) {
        try {
            val modelId = message.getString("model_id")
            val version = message.getString("version")
            val downloadUrl = message.getString("download_url")
            val sourceNode = message.getString("source_node")
            
            Log.i(TAG, "MODEL_AVAILABLE: $modelId v$version from $sourceNode")
            
            // Check if we already have this version
            val existing = _installedModels.value.find {
                it.modelId == modelId && it.version == version
            }
            
            if (existing != null) {
                Log.i(TAG, "Model already installed: $modelId v$version")
                return
            }
            
            // Optionally auto-download (could make this a setting)
            // For now, just log it
            Log.i(TAG, "New model available for download: $modelId v$version")
            
            // TODO: Show notification or UI update about new model
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle MODEL_AVAILABLE message", e)
        }
    }
    
    /**
     * Get model metadata by ID and version.
     */
    fun getModelMetadata(modelId: String, version: String): VisionModelMetadata? {
        return _installedModels.value.find {
            it.modelId == modelId && it.version == version
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        scope.cancel()
    }
}

/**
 * Download progress information.
 */
data class DownloadProgress(
    val modelId: String,
    val version: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: String
) {
    fun percentComplete(): Int {
        return if (totalBytes > 0) {
            ((bytesDownloaded * 100) / totalBytes).toInt()
        } else {
            0
        }
    }
}
