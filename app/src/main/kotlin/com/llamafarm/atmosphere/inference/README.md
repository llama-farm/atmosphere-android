# Inference Engine Architecture

This directory contains the local LLM inference components for Atmosphere.

## Components

### LlamaCppEngine (Preferred)
Direct llama.cpp bindings that bypass the ARM AiChat wrapper. This engine supports:
- Qwen3 and other architectures not in the ARM whitelist
- Direct control over llama.cpp parameters
- Better error reporting

### LocalInferenceEngine (Legacy)
Wrapper around the ARM AiChat library. Limited to architectures supported by the ARM wrapper.

### UniversalRuntime
High-level API that provides:
- System prompt management
- Persona/project routing
- Context window management
- Chat history tracking

Use `UniversalRuntime.create(context, modelManager)` to get a runtime backed by `LlamaCppEngine`.

## Usage

```kotlin
// Create runtime with LlamaCppEngine (supports Qwen3)
val runtime = UniversalRuntime.create(context, modelManager)

// Or for backward compatibility with LocalInferenceEngine
val runtime = UniversalRuntime(context, modelManager, localInferenceEngine)
```

## Building Direct JNI Bindings

If the ARM AiChat fallback doesn't support your model architecture, you can build direct JNI bindings:

### Prerequisites
1. Android NDK (installed via Android Studio SDK Manager)
2. llama.cpp source code

### Setup

1. Clone llama.cpp next to the atmosphere-android project:
   ```bash
   cd ~/clawd/projects
   git clone https://github.com/ggml-org/llama.cpp.git
   ```

2. Enable NDK in `app/build.gradle.kts`:
   ```kotlin
   android {
       ...
       externalNativeBuild {
           cmake {
               path = file("src/main/cpp/CMakeLists.txt")
               version = "3.22.1"
           }
       }
       ndkVersion = "26.1.10909125" // Or your installed version
   }
   ```

3. Build:
   ```bash
   ./gradlew :app:assembleDebug
   ```

### Native Library Dependencies

The JNI code (`llama_jni.cpp`) requires these libraries from the AAR:
- `libllama.so` - Core llama.cpp library
- `libggml.so` - GGML tensor library
- `libggml-base.so` - GGML base
- `libggml-cpu-*.so` - CPU backend variants

These are automatically included from `app/libs/llama-android.aar`.

## Troubleshooting

### UnsupportedArchitectureException
This error from the ARM AiChat wrapper means the model architecture isn't in its whitelist.
Solution: Use `LlamaCppEngine` with direct JNI bindings, or update to a newer version of the llama-android AAR.

### Model Load Failures
Check logs for specific error:
```
adb logcat -s LlamaCppEngine:* LlamaCppJNI:*
```

Common causes:
- Model file not found or inaccessible
- Insufficient memory for model
- llama.cpp version doesn't support the model format

### Native Library Not Found
Ensure the AAR is properly included in `app/build.gradle.kts`:
```kotlin
implementation(files("libs/llama-android.aar"))
```
