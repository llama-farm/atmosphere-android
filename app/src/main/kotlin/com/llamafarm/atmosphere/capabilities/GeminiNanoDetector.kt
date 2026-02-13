package com.llamafarm.atmosphere.capabilities

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Detector for Gemini Nano availability via ML Kit GenAI APIs.
 * 
 * Checks if Gemini Nano is available on the device and returns metadata.
 */
object GeminiNanoDetector {
    private const val TAG = "GeminiNanoDetector"
    
    data class GeminiNanoInfo(
        val available: Boolean,
        val version: String?, // nano-v2, nano-v3
        val status: String, // AVAILABLE, DOWNLOADING, DOWNLOADABLE, UNAVAILABLE
        val capabilities: List<String> // prompt, summarization, proofreading, etc.
    )
    
    /**
     * Check if Gemini Nano is available on this device.
     * Returns null if ML Kit GenAI APIs are not available.
     */
    suspend fun detect(context: Context): GeminiNanoInfo? {
        return try {
            // Check if ML Kit GenAI APIs are available
            val hasGenAi = checkMlKitGenAiAvailable()
            if (!hasGenAi) {
                Log.d(TAG, "ML Kit GenAI APIs not available")
                return null
            }
            
            // Try to check Prompt API status (Gemini Nano)
            val status = checkPromptApiStatus(context)
            
            GeminiNanoInfo(
                available = status == "AVAILABLE",
                version = getGeminiNanoVersion(),
                status = status,
                capabilities = getAvailableCapabilities(status)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect Gemini Nano: ${e.message}")
            null
        }
    }
    
    /**
     * Check if ML Kit GenAI library is available at runtime.
     */
    private fun checkMlKitGenAiAvailable(): Boolean {
        return try {
            Class.forName("com.google.mlkit.genai.prompt.Generation")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    /**
     * Check Prompt API status (requires ML Kit GenAI dependency).
     * Returns: AVAILABLE, DOWNLOADING, DOWNLOADABLE, or UNAVAILABLE
     */
    private suspend fun checkPromptApiStatus(context: Context): String {
        // This requires the ML Kit GenAI Prompt API dependency
        // For now, return a placeholder until we add the dependency
        return try {
            // TODO: Implement actual check using:
            // val generativeModel = Generation.getClient()
            // val status = generativeModel.checkStatus()
            
            // Placeholder: check if device is a Pixel 9+ or supported device
            val model = android.os.Build.MODEL ?: ""
            val isPixel9Plus = model.startsWith("Pixel 9") || model.startsWith("Pixel 10")
            
            if (isPixel9Plus) {
                "DOWNLOADABLE" // Likely available but may need download
            } else {
                "UNAVAILABLE"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Prompt API status: ${e.message}")
            "UNAVAILABLE"
        }
    }
    
    /**
     * Get Gemini Nano version if available (nano-v2, nano-v3).
     */
    private fun getGeminiNanoVersion(): String? {
        return try {
            val model = android.os.Build.MODEL ?: ""
            when {
                model.startsWith("Pixel 10") -> "nano-v3"
                model.startsWith("Pixel 9") -> "nano-v2"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get list of available ML Kit GenAI capabilities based on status.
     */
    private fun getAvailableCapabilities(status: String): List<String> {
        return if (status == "AVAILABLE" || status == "DOWNLOADABLE") {
            listOf(
                "prompt",
                "summarization",
                "proofreading",
                "rewriting",
                "image_description"
            )
        } else {
            emptyList()
        }
    }
}
