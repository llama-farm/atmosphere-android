# Local LLM Inference Integration

This document describes the on-device LLM inference integration for Atmosphere Android using llama.cpp.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI Layer                                 │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ InferenceScreen │  │ InferenceViewModel│  │ ChatViewModel │  │
│  └────────┬────────┘  └────────┬─────────┘  └───────┬───────┘  │
└───────────┼────────────────────┼───────────────────┼───────────┘
            │                    │                   │
┌───────────▼────────────────────▼───────────────────▼───────────┐
│                       Service Layer                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   InferenceService                       │   │
│  │  - Foreground service for model persistence              │   │
│  │  - Hosts UniversalRuntime & LocalInferenceEngine         │   │
│  └────────┬─────────────────────────────────────────────────┘   │
└───────────┼─────────────────────────────────────────────────────┘
            │
┌───────────▼─────────────────────────────────────────────────────┐
│                      Inference Layer                             │
│  ┌─────────────────┐  ┌──────────────────────────────────────┐  │
│  │  ModelManager   │  │        UniversalRuntime               │  │
│  │  - Download     │  │  - System prompt management           │  │
│  │  - Storage      │  │  - Persona/project routing            │  │
│  │  - GGUF files   │  │  - Context window management          │  │
│  └─────────────────┘  │  - Chat history tracking              │  │
│                       └────────────────┬─────────────────────┘  │
│                                        │                        │
│  ┌─────────────────────────────────────▼─────────────────────┐  │
│  │               LocalInferenceEngine                         │  │
│  │  - Kotlin coroutine interface                              │  │
│  │  - State management                                        │  │
│  │  - Streaming token generation                              │  │
│  └────────────────────────────┬──────────────────────────────┘  │
└───────────────────────────────┼─────────────────────────────────┘
                                │ JNI
┌───────────────────────────────▼─────────────────────────────────┐
│                      Native Layer (llama.cpp)                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  libllama-android.so (from llama.cpp AAR)                │   │
│  │  - GGML backends (CPU, potentially GPU)                   │   │
│  │  - Model loading & inference                              │   │
│  │  - Hardware acceleration (SME2/AMX)                       │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### ModelManager (`inference/ModelManager.kt`)
- Downloads GGUF models from HuggingFace
- Resume support for interrupted downloads
- Progress tracking via StateFlow
- Model storage in app's private files directory
- Pre-configured model list:
  - **Qwen3-0.6B-Q4_K_M** (~450MB) - Ultra-light
  - **Qwen3-1.7B-Q4_K_M** (~1.1GB) - Default, balanced
  - **Qwen3-4B-Q4_K_M** (~2.6GB) - Higher quality

### LocalInferenceEngine (`inference/LocalInferenceEngine.kt`)
- Singleton wrapper for llama.cpp JNI
- Thread-safe via single-threaded dispatcher
- Graceful degradation when native library unavailable
- Key features:
  - Model loading/unloading
  - System prompt setting
  - Streaming token generation via Flow
  - Generation cancellation
  - Benchmarking

### UniversalRuntime (`inference/UniversalRuntime.kt`)
- High-level chat interface
- Pre-defined personas:
  - **Assistant** - General purpose helper
  - **Coder** - Programming assistance
  - **Creative** - Creative writing
  - **Analyst** - Data analysis
  - **Custom** - User-defined system prompt
- Context window management
- Chat history with token estimation
- Automatic context trimming

### InferenceService (`service/InferenceService.kt`)
- Foreground service for persistent inference
- Keeps model loaded across activity lifecycle
- Service binding for ViewModel access
- Notification with model status

### InferenceViewModel (`viewmodel/InferenceViewModel.kt`)
- UI state management
- Service binding/unbinding
- Model download orchestration
- Chat message handling

### InferenceScreen (`ui/screens/InferenceScreen.kt`)
- Model picker dialog
- Download progress UI
- Chat interface with streaming responses
- Persona selector
- Service status display

## Setup Instructions

### 1. Build llama.cpp AAR

```bash
# Clone llama.cpp
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp

# Build Android library
cd examples/llama.android
./gradlew :lib:assembleRelease

# Copy AAR to Atmosphere
cp lib/build/outputs/aar/lib-release.aar \
   /path/to/atmosphere-android/app/libs/llama-android.aar
```

### 2. Rebuild Atmosphere

```bash
cd atmosphere-android
./gradlew assembleDebug
```

### 3. Run on Device

The app will:
1. Check for native library availability
2. Show download options for models
3. Enable local inference when model is loaded

## API Usage

### From ViewModel
```kotlin
// Start service and load model
viewModel.startService(
    modelId = "qwen3-1.7b-q4km",
    persona = UniversalRuntime.Persona.ASSISTANT
)

// Chat with streaming response
viewModel.chat("Hello, how are you?").collect { token ->
    // Append token to UI
    response += token
}

// Cancel generation
viewModel.cancelGeneration()
```

### Direct Engine Access
```kotlin
val engine = LocalInferenceEngine.getInstance(context)

// Load model
engine.loadModel("/path/to/model.gguf", contextSize = 4096)

// Set system prompt (optional)
engine.setSystemPrompt("You are a helpful assistant.")

// Generate response
engine.generate("What is 2+2?", GenerationParams(
    maxTokens = 256,
    temperature = 0.7f
)).collect { token ->
    print(token)
}
```

## Mesh Integration

Local inference can be exposed as a mesh capability:

```kotlin
// Register LLM capability
val capability = JSONObject().apply {
    put("type", "llm")
    put("model", "qwen3-1.7b")
    put("quantization", "Q4_K_M")
    put("context_size", 4096)
    put("local", true)
}
atmosphereNode.registerCapability(capability.toString())
```

When connected to a mesh, other nodes can route LLM requests to this device.

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Model load | <5s | Depends on storage speed |
| First token | <500ms | After prompt processing |
| Generation | 10-30 tok/s | Device dependent |
| Memory | <2GB | For 1.7B model |

## Troubleshooting

### Native library not found
- Ensure `llama-android.aar` is in `app/libs/`
- Verify AAR contains `arm64-v8a` libraries
- Check logcat for `UnsatisfiedLinkError`

### Model download fails
- Check network connectivity
- Ensure sufficient storage space
- Try clearing partial download and retry

### Slow generation
- Reduce context size
- Use smaller quantization (Q4_K_M recommended)
- Close background apps
- Check device thermal state

## Files Created/Modified

### New Files
- `app/src/main/kotlin/com/llamafarm/atmosphere/inference/ModelManager.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/inference/LocalInferenceEngine.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/inference/UniversalRuntime.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/viewmodel/InferenceViewModel.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/ui/screens/InferenceScreen.kt`
- `app/libs/README.md`
- `docs/LOCAL_INFERENCE.md`

### Modified Files
- `app/build.gradle.kts` - Added AAR dependency handling
- `settings.gradle.kts` - Added flatDir repository
- `app/src/main/kotlin/com/llamafarm/atmosphere/service/InferenceService.kt` - Full rewrite
