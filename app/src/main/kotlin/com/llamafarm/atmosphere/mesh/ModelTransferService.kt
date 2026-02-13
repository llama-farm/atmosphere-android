package com.llamafarm.atmosphere.mesh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.llamafarm.atmosphere.core.GossipManager
import com.llamafarm.atmosphere.inference.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "ModelTransferService"

/**
 * Download progress information.
 */
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val chunksReceived: Int,
    val totalChunks: Int,
    val transferRateMbps: Float,
    val etaSeconds: Int
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()) * 100f else 0f
}

/**
 * Download state.
 */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Preparing(val modelId: String) : DownloadState()
    data class Downloading(val progress: DownloadProgress) : DownloadState()
    data class Verifying(val modelId: String) : DownloadState()
    data class Completed(val modelId: String, val storagePath: String) : DownloadState()
    data class Failed(val modelId: String, val reason: String) : DownloadState()
    data class Cancelled(val modelId: String) : DownloadState()
}

/**
 * Manages model transfers over the mesh.
 * Listens for model_catalog gossip, handles downloads via HTTP.
 * WebSocket transfer removed - HTTP direct transfer only.
 */
class ModelTransferService(
    private val context: Context,
    private val modelManager: ModelManager
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gossipManager = GossipManager.getInstance(context)
    
    val modelCatalog = ModelCatalog()
    
    // Active downloads: requestId -> DownloadJob
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    
    // Download states: modelId -> DownloadState
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Models directory
    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    
    init {
        // Model catalog updates now come from CRDT _capabilities collection
        // Periodic cleanup of expired catalog entries
        scope.launch {
            while (isActive) {
                delay(60_000)  // Every minute
                modelCatalog.cleanupExpired()
            }
        }
    }
    
    /**
     * Download a model from the mesh.
     * Returns the request ID for tracking progress.
     */
    fun downloadModel(
        modelId: String,
        preferredNodeId: String? = null
    ): String? {
        // Check if already downloading
        if (_downloadStates.value[modelId] is DownloadState.Downloading) {
            Log.w(TAG, "Model $modelId is already being downloaded")
            return null
        }
        
        // Check if already have the model
        val existingPath = modelManager.getModelPath(modelId)
        if (existingPath != null) {
            Log.i(TAG, "Model $modelId already exists at $existingPath")
            return null
        }
        
        // Get model from catalog
        val catalogEntry = modelCatalog.getModel(modelId)
        if (catalogEntry == null) {
            Log.e(TAG, "Model $modelId not found in catalog")
            updateDownloadState(modelId, DownloadState.Failed(modelId, "Model not found in catalog"))
            return null
        }
        
        // Select peer
        val peer = if (preferredNodeId != null) {
            catalogEntry.availableOnPeers.find { it.nodeId == preferredNodeId }
        } else {
            catalogEntry.getBestPeer()
        }
        
        if (peer == null) {
            Log.e(TAG, "No available peer for model $modelId")
            updateDownloadState(modelId, DownloadState.Failed(modelId, "No available peers"))
            return null
        }
        
        val requestId = UUID.randomUUID().toString()
        
        updateDownloadState(modelId, DownloadState.Preparing(modelId))
        
        // Start download based on transport
        val job = if (peer.httpEndpoint != null) {
            // HTTP transfer (preferred)
            scope.launch {
                downloadViaHttp(requestId, catalogEntry, peer)
            }
        } else if (peer.websocketAvailable) {
            // WebSocket transfer
            scope.launch {
                downloadViaWebSocket(requestId, catalogEntry, peer)
            }
        } else {
            Log.e(TAG, "Peer has no available transfer method")
            updateDownloadState(modelId, DownloadState.Failed(modelId, "No transfer method available"))
            return null
        }
        
        activeDownloads[requestId] = job
        
        Log.i(TAG, "Started download of $modelId from ${peer.nodeName} (request: $requestId)")
        
        return requestId
    }
    
    /**
     * Download via direct HTTP (LAN peers).
     */
    private suspend fun downloadViaHttp(
        requestId: String,
        catalogEntry: ModelCatalogEntry,
        peer: PeerModelInfo
    ) = withContext(Dispatchers.IO) {
        val modelId = catalogEntry.modelId
        val httpEndpoint = peer.httpEndpoint ?: return@withContext
        
        val destFile = File(modelsDir, "${catalogEntry.modelId}.${catalogEntry.format}")
        val tempFile = File(modelsDir, "${catalogEntry.modelId}.${catalogEntry.format}.tmp")
        
        try {
            // Check for resume
            val resumeFromByte = if (tempFile.exists()) tempFile.length() else 0L
            
            val url = "$httpEndpoint/v1/models/download/${catalogEntry.modelId}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=$resumeFromByte-")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            
            val totalBytes = (response.body?.contentLength() ?: 0) + resumeFromByte
            val inputStream = response.body?.byteStream()
                ?: throw Exception("Empty response body")
            
            val outputStream = FileOutputStream(tempFile, resumeFromByte > 0)
            
            val buffer = ByteArray(65536)
            var bytesRead: Int
            var totalDownloaded = resumeFromByte
            var lastProgressUpdate = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isActive) {
                    inputStream.close()
                    outputStream.close()
                    updateDownloadState(modelId, DownloadState.Cancelled(modelId))
                    return@withContext
                }
                
                outputStream.write(buffer, 0, bytesRead)
                totalDownloaded += bytesRead
                
                // Update progress at most every 500ms
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 500) {
                    val elapsedSeconds = (now - startTime) / 1000f
                    val transferRateMbps = if (elapsedSeconds > 0) {
                        ((totalDownloaded - resumeFromByte) * 8f / 1_000_000f) / elapsedSeconds
                    } else 0f
                    
                    val remainingBytes = totalBytes - totalDownloaded
                    val etaSeconds = if (transferRateMbps > 0) {
                        (remainingBytes * 8f / 1_000_000f / transferRateMbps).toInt()
                    } else 0
                    
                    val progress = DownloadProgress(
                        modelId = modelId,
                        bytesDownloaded = totalDownloaded,
                        totalBytes = totalBytes,
                        chunksReceived = (totalDownloaded / 65536).toInt(),
                        totalChunks = (totalBytes / 65536).toInt() + 1,
                        transferRateMbps = transferRateMbps,
                        etaSeconds = etaSeconds
                    )
                    
                    updateDownloadState(modelId, DownloadState.Downloading(progress))
                    lastProgressUpdate = now
                }
            }
            
            inputStream.close()
            outputStream.close()
            response.close()
            
            // Verify SHA-256
            updateDownloadState(modelId, DownloadState.Verifying(modelId))
            
            val computedHash = computeSha256(tempFile)
            if (computedHash.lowercase() != catalogEntry.sha256.lowercase()) {
                tempFile.delete()
                throw Exception("SHA-256 verification failed: expected ${catalogEntry.sha256}, got $computedHash")
            }
            
            // Move to final location
            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)
            
            // Save metadata
            val metadataFile = File(modelsDir, "${catalogEntry.modelId}.json")
            val metadataJson = JSONObject().apply {
                put("model_id", catalogEntry.modelId)
                put("name", catalogEntry.name)
                put("type", catalogEntry.type.name.lowercase())
                put("format", catalogEntry.format)
                put("size_bytes", catalogEntry.sizeBytes)
                put("sha256", catalogEntry.sha256)
                put("version", catalogEntry.version)
                put("downloaded_from", peer.nodeName)
                put("downloaded_at", System.currentTimeMillis())
            }
            metadataFile.writeText(metadataJson.toString(2))
            
            Log.i(TAG, "Download complete: $modelId -> ${destFile.absolutePath}")
            
            updateDownloadState(modelId, DownloadState.Completed(modelId, destFile.absolutePath))
            
            // Update peer reliability (success)
            modelCatalog.updatePeerReliability(peer.nodeId, success = true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelId: ${e.message}", e)
            tempFile.delete()
            updateDownloadState(modelId, DownloadState.Failed(modelId, e.message ?: "Unknown error"))
            
            // Update peer reliability (failure)
            modelCatalog.updatePeerReliability(peer.nodeId, success = false)
        } finally {
            activeDownloads.remove(requestId)
        }
    }
    
    /**
     * LEGACY - WebSocket transfer removed. Use HTTP transfer only.
     */
    private suspend fun downloadViaWebSocket(
        requestId: String,
        catalogEntry: ModelCatalogEntry,
        peer: PeerModelInfo
    ) = withContext(Dispatchers.IO) {
        val modelId = catalogEntry.modelId
        Log.w(TAG, "WebSocket transfer removed - use HTTP transfer only")
        updateDownloadState(modelId, DownloadState.Failed(modelId, "WebSocket transfer removed"))
        activeDownloads.remove(requestId)
    }
    
    /**
     * Cancel an active download.
     */
    fun cancelDownload(requestId: String) {
        val job = activeDownloads[requestId]
        if (job != null) {
            job.cancel()
            activeDownloads.remove(requestId)
            Log.i(TAG, "Cancelled download: $requestId")
        }
    }
    
    /**
     * Cancel download by model ID.
     */
    fun cancelDownloadByModelId(modelId: String) {
        val requestId = activeDownloads.entries.find { (_, job) ->
            // This is simplified - in production, maintain a modelId -> requestId mapping
            job.isActive
        }?.key
        
        if (requestId != null) {
            cancelDownload(requestId)
        }
    }
    
    /**
     * Delete a downloaded model.
     */
    fun deleteModel(modelId: String): Boolean {
        val modelFile = modelsDir.listFiles()?.find { it.name.startsWith(modelId) }
        val metadataFile = File(modelsDir, "$modelId.json")
        
        var deleted = false
        if (modelFile != null && modelFile.exists()) {
            deleted = modelFile.delete()
        }
        if (metadataFile.exists()) {
            metadataFile.delete()
        }
        
        if (deleted) {
            Log.i(TAG, "Deleted model: $modelId")
        }
        
        return deleted
    }
    
    /**
     * Get list of downloaded models.
     */
    fun getDownloadedModels(): List<String> {
        return modelsDir.listFiles()
            ?.filter { it.extension in listOf("pt", "gguf", "tflite", "onnx") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
    
    /**
     * Compute SHA-256 hash of a file.
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Update download state and emit to StateFlow.
     */
    private fun updateDownloadState(modelId: String, state: DownloadState) {
        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[modelId] = state
        _downloadStates.value = currentStates
    }
    
    /**
     * Get statistics.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "active_downloads" to activeDownloads.size,
            "downloaded_models" to getDownloadedModels().size,
            "catalog_size" to modelCatalog.getAllModels().size
        ) + modelCatalog.getStats()
    }
}
