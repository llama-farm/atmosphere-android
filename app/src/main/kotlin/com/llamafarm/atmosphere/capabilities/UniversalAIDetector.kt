package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Universal Android AI capability detector.
 * 
 * Detects available on-device AI accelerators and models across all major vendors
 * using runtime feature detection (not device model checks).
 * 
 * Supports:
 * - Google Pixel: Gemini Nano via ML Kit GenAI / AI Core
 * - Samsung Galaxy: Neural SDK / ONE engine  
 * - Qualcomm: QNN SDK (Snapdragon devices)
 * - MediaTek: NeuroPilot (Dimensity devices)
 * - Universal: NNAPI, LiteRT/TFLite, ONNX Runtime
 */
object UniversalAIDetector {
    private const val TAG = "UniversalAIDetector"
    
    data class AICapability(
        val id: String,
        val name: String,
        val vendor: String, // google, samsung, qualcomm, mediatek, universal
        val type: String, // llm, vision, embedding, accelerator
        val runtime: String, // aicore, neural-sdk, qnn, neuropilot, nnapi, litert, onnx
        val available: Boolean,
        val version: String? = null,
        val modelInfo: Map<String, Any> = emptyMap()
    )
    
    /**
     * Detect all available AI capabilities on this device.
     * Uses runtime feature detection (library checks, API availability).
     */
    suspend fun detectAll(context: Context): List<AICapability> = withContext(Dispatchers.IO) {
        val capabilities = mutableListOf<AICapability>()
        
        // 1. Google AI Core / Gemini Nano (Pixel 9+, expanding to more devices)
        capabilities.addAll(detectGoogleAICore(context))
        
        // 2. Samsung Neural SDK (Galaxy S23+, S24+, S25+)
        capabilities.addAll(detectSamsungNeural(context))
        
        // 3. Qualcomm QNN (Snapdragon 8 Gen2+, most OnePlus/Xiaomi flagships)
        capabilities.addAll(detectQualcommQNN(context))
        
        // 4. MediaTek NeuroPilot (Dimensity series, some Xiaomi/Oppo)
        capabilities.addAll(detectMediaTekNeuroPilot(context))
        
        // 5. Universal accelerators (all devices)
        capabilities.addAll(detectUniversalAccelerators(context))
        
        Log.i(TAG, "Detected ${capabilities.size} AI capabilities: ${capabilities.map { it.id }}")
        capabilities
    }
    
    // ========================================================================
    // Google AI Core / Gemini Nano
    // ========================================================================
    
    private fun detectGoogleAICore(context: Context): List<AICapability> {
        val capabilities = mutableListOf<AICapability>()
        
        // Check if ML Kit GenAI is available (class check, not device model)
        val hasGenAI = try {
            Class.forName("com.google.mlkit.genai.prompt.Generation")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        
        if (hasGenAI) {
            // ML Kit GenAI detected - Gemini Nano available or downloadable
            Log.i(TAG, "ML Kit GenAI API detected (Gemini Nano)")
            
            // Gemini Nano Prompt API
            capabilities.add(AICapability(
                id = "google:gemini-nano:prompt",
                name = "Gemini Nano (Prompt)",
                vendor = "google",
                type = "llm",
                runtime = "aicore",
                available = true,
                modelInfo = mapOf(
                    "context_length" to 4000,
                    "supports_vision" to true,
                    "supports_tools" to false,
                    "model_tier" to "small"
                )
            ))
            
            // ML Kit feature-specific APIs
            val features = listOf(
                Triple("summarization", "Summarization", "Summarize articles or chat conversations"),
                Triple("proofreading", "Proofreading", "Polish content, fix grammar and spelling"),
                Triple("rewriting", "Rewriting", "Rewrite messages in different tones"),
                Triple("image_description", "Image Description", "Generate image descriptions")
            )
            
            for ((id, name, desc) in features) {
                capabilities.add(AICapability(
                    id = "google:gemini-nano:$id",
                    name = "Gemini Nano ($name)",
                    vendor = "google",
                    type = if (id == "image_description") "vision" else "llm",
                    runtime = "aicore",
                    available = true,
                    modelInfo = mapOf("description" to desc)
                ))
            }
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Samsung Neural SDK
    // ========================================================================
    
    private fun detectSamsungNeural(context: Context): List<AICapability> {
        val capabilities = mutableListOf<AICapability>()
        
        // Check if Samsung Neural SDK is available
        val hasSamsungNeural = checkLibraryExists("libsamneuralnetworks.so") ||
                               checkPackageExists(context, "com.samsung.android.sdk.ai") ||
                               checkClassExists("com.samsung.android.sdk.neural.NeuralNetwork")
        
        if (hasSamsungNeural) {
            Log.i(TAG, "Samsung Neural SDK detected")
            
            // Samsung ONE (On-device Neural Engine) accelerator
            capabilities.add(AICapability(
                id = "samsung:neural:one-engine",
                name = "Samsung ONE Neural Engine",
                vendor = "samsung",
                type = "accelerator",
                runtime = "neural-sdk",
                available = true,
                modelInfo = mapOf(
                    "supports_nnapi" to true,
                    "supports_litert" to true,
                    "hardware" to "NPU"
                )
            ))
            
            // Check for Samsung Galaxy AI features (S24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                // Samsung's on-device models (existence depends on device model)
                capabilities.add(AICapability(
                    id = "samsung:galaxy-ai:text",
                    name = "Samsung Galaxy AI (Text)",
                    vendor = "samsung",
                    type = "llm",
                    runtime = "neural-sdk",
                    available = true,
                    modelInfo = mapOf(
                        "features" to listOf("translation", "summarization", "writing_assist"),
                        "model_tier" to "small"
                    )
                ))
            }
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Qualcomm QNN SDK
    // ========================================================================
    
    private fun detectQualcommQNN(context: Context): List<AICapability> {
        val capabilities = mutableListOf<AICapability>()
        
        // Check if Qualcomm AI libraries are present
        val hasQNN = checkLibraryExists("libQnnHtp.so") ||      // Hexagon tensor processor
                     checkLibraryExists("libQnnHtpV75.so") ||   // V75 (8 Gen 3)
                     checkLibraryExists("libQnnHtpV73.so") ||   // V73 (8 Gen 2)
                     checkLibraryExists("libQnnCpu.so") ||
                     checkLibraryExists("libQnnGpu.so") ||
                     checkClassExists("com.qualcomm.qti.snpe.NeuralNetwork") // SNPE (older)
        
        if (hasQNN) {
            val socInfo = getQualcommSoCInfo()
            Log.i(TAG, "Qualcomm AI Engine detected: $socInfo")
            
            // QNN accelerator
            capabilities.add(AICapability(
                id = "qualcomm:qnn:htp",
                name = "Qualcomm AI Engine (HTP)",
                vendor = "qualcomm",
                type = "accelerator",
                runtime = "qnn",
                available = true,
                version = socInfo,
                modelInfo = mapOf(
                    "hardware" to "Hexagon NPU",
                    "supports_nnapi" to true,
                    "supports_litert" to true,
                    "supports_onnx" to true
                )
            ))
            
            // Check for Snapdragon-optimized models
            // Note: Actual LLM availability depends on OEM integration
            if (socInfo.contains("8 Gen 2") || socInfo.contains("8 Gen 3") || 
                socInfo.contains("8 Elite") || socInfo.contains("8 Gen5")) {
                capabilities.add(AICapability(
                    id = "qualcomm:snapdragon:llm",
                    name = "Snapdragon On-Device LLM",
                    vendor = "qualcomm",
                    type = "llm",
                    runtime = "qnn",
                    available = false, // Usually OEM-dependent
                    modelInfo = mapOf(
                        "note" to "Available if OEM enabled",
                        "model_tier" to "small"
                    )
                ))
            }
        }
        
        return capabilities
    }
    
    // ========================================================================
    // MediaTek NeuroPilot
    // ========================================================================
    
    private fun detectMediaTekNeuroPilot(context: Context): List<AICapability> {
        val capabilities = mutableListOf<AICapability>()
        
        // Check if MediaTek NeuroPilot is available
        val hasNeuroPilot = checkLibraryExists("libneuronusdk_adapter.so") ||
                           checkLibraryExists("libneuron_runtime.so") ||
                           checkLibraryExists("libAPUSys.so") ||
                           checkClassExists("com.mediatek.neuropilot.NeuroPilotSDK")
        
        if (hasNeuroPilot) {
            Log.i(TAG, "MediaTek NeuroPilot detected")
            
            // NeuroPilot APU accelerator
            capabilities.add(AICapability(
                id = "mediatek:neuropilot:apu",
                name = "MediaTek NeuroPilot APU",
                vendor = "mediatek",
                type = "accelerator",
                runtime = "neuropilot",
                available = true,
                modelInfo = mapOf(
                    "hardware" to "APU (AI Processing Unit)",
                    "supports_litert" to true,
                    "supports_nnapi" to true
                )
            ))
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Universal Accelerators (all Android devices)
    // ========================================================================
    
    private fun detectUniversalAccelerators(context: Context): List<AICapability> {
        val capabilities = mutableListOf<AICapability>()
        
        // 1. NNAPI (Android Neural Networks API) - available on Android 8.1+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // 27+
            val nnapiVersion = getNNAPIVersion()
            capabilities.add(AICapability(
                id = "universal:nnapi",
                name = "Android Neural Networks API (NNAPI)",
                vendor = "universal",
                type = "accelerator",
                runtime = "nnapi",
                available = true,
                version = nnapiVersion,
                modelInfo = mapOf(
                    "api_level" to Build.VERSION.SDK_INT,
                    "hardware" to "Vendor NPU/GPU/DSP"
                )
            ))
        }
        
        // 2. LiteRT / TensorFlow Lite
        val hasLiteRT = checkClassExists("org.tensorflow.lite.Interpreter") ||
                       checkClassExists("com.google.android.gms.tflite.client.TfLiteClient")
        
        if (hasLiteRT) {
            capabilities.add(AICapability(
                id = "universal:litert",
                name = "LiteRT (TensorFlow Lite)",
                vendor = "universal",
                type = "accelerator",
                runtime = "litert",
                available = true,
                modelInfo = mapOf(
                    "gpu_delegate" to "available",
                    "nnapi_delegate" to "available"
                )
            ))
        }
        
        // 3. ONNX Runtime
        val hasONNX = checkClassExists("ai.onnxruntime.OrtEnvironment")
        
        if (hasONNX) {
            capabilities.add(AICapability(
                id = "universal:onnx-runtime",
                name = "ONNX Runtime",
                vendor = "universal",
                type = "accelerator",
                runtime = "onnx",
                available = true,
                modelInfo = mapOf(
                    "nnapi_provider" to "available",
                    "cpu_provider" to "available"
                )
            ))
        }
        
        return capabilities
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private fun checkLibraryExists(libName: String): Boolean {
        return try {
            System.loadLibrary(libName.removeSuffix(".so"))
            true
        } catch (e: UnsatisfiedLinkError) {
            // Library might exist but not be loadable - check filesystem
            val possiblePaths = listOf(
                "/vendor/lib64/$libName",
                "/vendor/lib/$libName",
                "/system/lib64/$libName",
                "/system/lib/$libName",
                "/system/vendor/lib64/$libName",
                "/system/vendor/lib/$libName"
            )
            possiblePaths.any { File(it).exists() }
        }
    }
    
    private fun checkPackageExists(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    private fun checkClassExists(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    private fun getQualcommSoCInfo(): String {
        return try {
            val socModel = Build.HARDWARE ?: ""
            val board = Build.BOARD ?: ""
            
            // Common Snapdragon identifiers
            when {
                board.contains("kalama", ignoreCase = true) -> "Snapdragon 8 Gen 2"
                board.contains("pineapple", ignoreCase = true) -> "Snapdragon 8 Gen 3"
                board.contains("sun", ignoreCase = true) -> "Snapdragon 8 Elite"
                board.contains("taro", ignoreCase = true) -> "Snapdragon 8 Gen 1"
                socModel.contains("qualcomm", ignoreCase = true) -> "Snapdragon"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun getNNAPIVersion(): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "1.3" // Android 14
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "1.3" // Android 13
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "1.3" // Android 12
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "1.3" // Android 11
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "1.2" // Android 10
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> "1.1" // Android 9
            else -> "1.0" // Android 8.1
        }
    }
}
