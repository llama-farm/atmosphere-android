package com.llamafarm.atmosphere.capabilities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VoiceCapability"

/**
 * Speech-to-text result.
 */
sealed class SpeechResult {
    data class Success(
        val requestId: String,
        val text: String,
        val confidence: Float,
        val alternatives: List<String> = emptyList(),
        val locale: String,
        val durationMs: Long
    ) : SpeechResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("text", text)
            put("confidence", confidence)
            put("alternatives", JSONArray(alternatives))
            put("locale", locale)
            put("duration_ms", durationMs)
        }
    }
    
    data class Error(
        val requestId: String,
        val error: String,
        val errorCode: Int = -1
    ) : SpeechResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("error", error)
            put("error_code", errorCode)
        }
    }
    
    data class Denied(
        val requestId: String,
        val reason: String = "User denied microphone access"
    ) : SpeechResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("error", reason)
            put("denied", true)
        }
    }
}

/**
 * Text-to-speech result.
 */
sealed class TtsResult {
    data class Success(
        val requestId: String,
        val text: String,
        val durationMs: Long
    ) : TtsResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("text", text)
            put("duration_ms", durationMs)
            put("status", "completed")
        }
    }
    
    data class Error(
        val requestId: String,
        val error: String
    ) : TtsResult() {
        fun toJson(): JSONObject = JSONObject().apply {
            put("request_id", requestId)
            put("error", error)
        }
    }
}

/**
 * STT request parameters.
 */
data class SttRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val locale: Locale = Locale.getDefault(),
    val maxDurationMs: Long = 30_000L,
    val partialResults: Boolean = false,
    val requireApproval: Boolean = true
)

/**
 * TTS request parameters.
 */
data class TtsRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val text: String,
    val locale: Locale = Locale.getDefault(),
    val pitch: Float = 1.0f,       // 0.5 - 2.0
    val speechRate: Float = 1.0f   // 0.5 - 2.0
)

/**
 * Approval callback for privacy-respecting microphone access.
 */
typealias MicApprovalCallback = (requestId: String, requester: String) -> Boolean

/**
 * Voice capability for mesh network.
 * 
 * Provides:
 * - Speech-to-text using Android SpeechRecognizer
 * - Text-to-speech using Android TTS engine
 * 
 * Privacy: STT requires explicit approval for each request.
 */
class VoiceCapability(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // TTS engine
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private val ttsLock = Object()
    
    // STT
    private var speechRecognizer: SpeechRecognizer? = null
    
    // Privacy approval
    private var micApprovalCallback: MicApprovalCallback? = null
    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()
    
    // State
    private val _sttAvailable = MutableStateFlow(false)
    val sttAvailable: StateFlow<Boolean> = _sttAvailable.asStateFlow()
    
    private val _ttsAvailable = MutableStateFlow(false)
    val ttsAvailable: StateFlow<Boolean> = _ttsAvailable.asStateFlow()
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    init {
        checkAvailability()
        initializeTts()
    }
    
    /**
     * Check availability of voice features.
     */
    private fun checkAvailability() {
        // Check STT availability
        _sttAvailable.value = SpeechRecognizer.isRecognitionAvailable(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == 
                PackageManager.PERMISSION_GRANTED
        
        Log.i(TAG, "STT available: ${_sttAvailable.value}")
    }
    
    /**
     * Initialize TTS engine.
     */
    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            synchronized(ttsLock) {
                ttsInitialized = status == TextToSpeech.SUCCESS
                _ttsAvailable.value = ttsInitialized
                Log.i(TAG, "TTS initialized: $ttsInitialized")
                
                if (ttsInitialized) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
    }
    
    /**
     * Set approval callback for microphone access.
     */
    fun setMicApprovalCallback(callback: MicApprovalCallback) {
        micApprovalCallback = callback
    }
    
    /**
     * Handle approval response from UI.
     */
    fun handleApprovalResponse(requestId: String, approved: Boolean) {
        pendingApprovals[requestId]?.complete(approved)
        pendingApprovals.remove(requestId)
    }
    
    // ========================================================================
    // Speech-to-Text
    // ========================================================================
    
    /**
     * Start speech recognition.
     * 
     * @param request STT request parameters
     * @param requester Node ID of the requester (for approval UI)
     * @return SpeechResult with transcription or error
     */
    suspend fun recognizeSpeech(
        request: SttRequest,
        requester: String = "unknown"
    ): SpeechResult {
        Log.i(TAG, "STT requested: ${request.requestId} from $requester")
        
        if (!_sttAvailable.value) {
            return SpeechResult.Error(
                request.requestId,
                "Speech recognition not available or permission denied"
            )
        }
        
        // Request approval if needed
        if (request.requireApproval) {
            val approval = micApprovalCallback?.invoke(request.requestId, requester)
            
            if (approval == null) {
                val deferred = CompletableDeferred<Boolean>()
                pendingApprovals[request.requestId] = deferred
                
                val approved = withTimeoutOrNull(30_000L) {
                    deferred.await()
                } ?: false
                
                if (!approved) {
                    return SpeechResult.Denied(request.requestId)
                }
            } else if (!approval) {
                return SpeechResult.Denied(request.requestId)
            }
        }
        
        return performSpeechRecognition(request)
    }
    
    /**
     * Perform actual speech recognition.
     */
    private suspend fun performSpeechRecognition(request: SttRequest): SpeechResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val startTime = System.currentTimeMillis()
                
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Ready for speech")
                        _isListening.value = true
                    }
                    
                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech started")
                    }
                    
                    override fun onRmsChanged(rmsdB: Float) {
                        // Audio level changed
                    }
                    
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "Speech ended")
                        _isListening.value = false
                    }
                    
                    override fun onError(error: Int) {
                        _isListening.value = false
                        val errorMsg = getErrorMessage(error)
                        Log.e(TAG, "STT error: $errorMsg ($error)")
                        
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        
                        if (continuation.isActive) {
                            continuation.resume(
                                SpeechResult.Error(request.requestId, errorMsg, error)
                            )
                        }
                    }
                    
                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val duration = System.currentTimeMillis() - startTime
                        
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        
                        if (matches.isNullOrEmpty()) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    SpeechResult.Error(request.requestId, "No speech detected")
                                )
                            }
                            return
                        }
                        
                        val text = matches.first()
                        val confidence = confidences?.firstOrNull() ?: 0.8f
                        val alternatives = if (matches.size > 1) matches.drop(1) else emptyList()
                        
                        if (continuation.isActive) {
                            continuation.resume(
                                SpeechResult.Success(
                                    requestId = request.requestId,
                                    text = text,
                                    confidence = confidence,
                                    alternatives = alternatives,
                                    locale = request.locale.toString(),
                                    durationMs = duration
                                )
                            )
                        }
                    }
                    
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (request.partialResults) {
                            val matches = partialResults?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            Log.d(TAG, "Partial: ${matches?.firstOrNull()}")
                        }
                    }
                    
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                
                // Start recognition
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                             RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.locale.toString())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }
                
                speechRecognizer?.startListening(intent)
                
                // Set up timeout
                scope.launch {
                    delay(request.maxDurationMs)
                    if (_isListening.value) {
                        speechRecognizer?.stopListening()
                    }
                }
                
                continuation.invokeOnCancellation {
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    _isListening.value = false
                }
            }
        }
    }
    
    /**
     * Stop current speech recognition.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }
    
    // ========================================================================
    // Text-to-Speech
    // ========================================================================
    
    /**
     * Speak text aloud.
     * 
     * @param request TTS request parameters
     * @return TtsResult indicating success or error
     */
    suspend fun speak(request: TtsRequest): TtsResult {
        Log.i(TAG, "TTS requested: ${request.requestId}")
        
        if (!_ttsAvailable.value) {
            return TtsResult.Error(request.requestId, "TTS not available")
        }
        
        return performTts(request)
    }
    
    /**
     * Perform text-to-speech.
     */
    private suspend fun performTts(request: TtsRequest): TtsResult {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val startTime = System.currentTimeMillis()
                
                synchronized(ttsLock) {
                    if (!ttsInitialized) {
                        if (continuation.isActive) {
                            continuation.resume(TtsResult.Error(request.requestId, "TTS not initialized"))
                        }
                        return@suspendCancellableCoroutine
                    }
                    
                    tts?.apply {
                        language = request.locale
                        setPitch(request.pitch.coerceIn(0.5f, 2.0f))
                        setSpeechRate(request.speechRate.coerceIn(0.5f, 2.0f))
                        
                        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                _isSpeaking.value = true
                                Log.d(TAG, "TTS started: $utteranceId")
                            }
                            
                            override fun onDone(utteranceId: String?) {
                                _isSpeaking.value = false
                                val duration = System.currentTimeMillis() - startTime
                                Log.d(TAG, "TTS completed: $utteranceId (${duration}ms)")
                                
                                if (continuation.isActive) {
                                    continuation.resume(
                                        TtsResult.Success(
                                            requestId = request.requestId,
                                            text = request.text,
                                            durationMs = duration
                                        )
                                    )
                                }
                            }
                            
                            @Deprecated("Deprecated in API")
                            override fun onError(utteranceId: String?) {
                                _isSpeaking.value = false
                                Log.e(TAG, "TTS error: $utteranceId")
                                
                                if (continuation.isActive) {
                                    continuation.resume(
                                        TtsResult.Error(request.requestId, "TTS playback failed")
                                    )
                                }
                            }
                            
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                _isSpeaking.value = false
                                Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                                
                                if (continuation.isActive) {
                                    continuation.resume(
                                        TtsResult.Error(request.requestId, "TTS error: $errorCode")
                                    )
                                }
                            }
                        })
                        
                        // Speak
                        speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.requestId)
                    }
                }
                
                continuation.invokeOnCancellation {
                    tts?.stop()
                    _isSpeaking.value = false
                }
            }
        }
    }
    
    /**
     * Stop current TTS playback.
     */
    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }
    
    // ========================================================================
    // Mesh Integration
    // ========================================================================
    
    /**
     * Get capability JSON for mesh registration.
     */
    fun getSttCapabilityJson(): JSONObject {
        return JSONObject().apply {
            put("name", "speech_to_text")
            put("description", "Convert speech to text using device microphone")
            put("version", "1.0")
            put("params", JSONObject().apply {
                put("locale", "language locale (e.g., en-US)")
                put("max_duration_ms", "max recording duration")
            })
            put("requires_approval", true)
            put("available", _sttAvailable.value)
        }
    }
    
    /**
     * Get TTS capability JSON for mesh registration.
     */
    fun getTtsCapabilityJson(): JSONObject {
        return JSONObject().apply {
            put("name", "text_to_speech")
            put("description", "Convert text to speech using device speaker")
            put("version", "1.0")
            put("params", JSONObject().apply {
                put("text", "text to speak")
                put("locale", "language locale")
                put("pitch", "voice pitch (0.5-2.0)")
                put("speech_rate", "speaking rate (0.5-2.0)")
            })
            put("requires_approval", false)
            put("available", _ttsAvailable.value)
        }
    }
    
    /**
     * Handle mesh STT request.
     */
    suspend fun handleSttMeshRequest(requestJson: JSONObject, requester: String): JSONObject {
        val requestId = requestJson.optString("request_id", UUID.randomUUID().toString())
        val localeStr = requestJson.optString("locale", Locale.getDefault().toString())
        
        val request = SttRequest(
            requestId = requestId,
            locale = Locale.forLanguageTag(localeStr.replace('_', '-')),
            maxDurationMs = requestJson.optLong("max_duration_ms", 30_000L),
            partialResults = requestJson.optBoolean("partial_results", false),
            requireApproval = requestJson.optBoolean("require_approval", true)
        )
        
        return when (val result = recognizeSpeech(request, requester)) {
            is SpeechResult.Success -> result.toJson()
            is SpeechResult.Error -> result.toJson()
            is SpeechResult.Denied -> result.toJson()
        }
    }
    
    /**
     * Handle mesh TTS request.
     */
    suspend fun handleTtsMeshRequest(requestJson: JSONObject): JSONObject {
        val requestId = requestJson.optString("request_id", UUID.randomUUID().toString())
        val text = requestJson.optString("text", "")
        
        if (text.isBlank()) {
            return JSONObject().apply {
                put("request_id", requestId)
                put("error", "No text provided")
            }
        }
        
        val localeStr = requestJson.optString("locale", Locale.getDefault().toString())
        
        val request = TtsRequest(
            requestId = requestId,
            text = text,
            locale = Locale.forLanguageTag(localeStr.replace('_', '-')),
            pitch = requestJson.optDouble("pitch", 1.0).toFloat(),
            speechRate = requestJson.optDouble("speech_rate", 1.0).toFloat()
        )
        
        return when (val result = speak(request)) {
            is TtsResult.Success -> result.toJson()
            is TtsResult.Error -> result.toJson()
        }
    }
    
    /**
     * Get STT error message from error code.
     */
    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            else -> "Unknown error"
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        tts?.shutdown()
        tts = null
        ttsInitialized = false
        
        scope.cancel()
    }
}
