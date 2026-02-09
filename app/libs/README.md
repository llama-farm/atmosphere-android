# Native Libraries for Local Inference

This directory contains native libraries for on-device LLM inference.

## llama.cpp Integration

The app uses llama.cpp for local LLM inference. To enable native inference, you need to build and include the llama.cpp Android library.

### Option 1: Build from Source (Recommended)

1. Clone llama.cpp:
   ```bash
   git clone https://github.com/ggml-org/llama.cpp.git
   cd llama.cpp
   ```

2. Build the Android library:
   ```bash
   cd examples/llama.android
   ./gradlew :lib:assembleRelease
   ```

3. Copy the AAR to this directory:
   ```bash
   cp lib/build/outputs/aar/lib-release.aar /path/to/atmosphere-android/app/libs/llama-android.aar
   ```

4. Rebuild the Atmosphere app:
   ```bash
   cd /path/to/atmosphere-android
   ./gradlew assembleDebug
   ```

### Option 2: Pre-built AAR

If available, download a pre-built AAR from the llama.cpp releases and place it here as `llama-android.aar`.

## Native Library Structure

The AAR should contain native libraries for these ABIs:
- `arm64-v8a` (64-bit ARM - most modern Android devices)
- `x86_64` (Android emulators on x86 machines)

Optional ABIs (for broader compatibility):
- `armeabi-v7a` (32-bit ARM - older devices)
- `x86` (older Android emulators)

## Without Native Library

The app will compile and run without the native library, but local inference will be disabled. The app will still function for:
- Model downloading and management
- Mesh connectivity (remote inference)
- All other features

When native library is not available:
- `LocalInferenceEngine.isNativeAvailable()` returns `false`
- UI shows a warning that native inference is unavailable
- Users can still use remote mesh inference

## Minimum Requirements

- Android SDK 24+ (Android 7.0)
- NDK r29+ (for building from source)
- CMake 3.31.6+ (for building from source)
- ~1.1GB storage for the default Qwen3-1.7B model

## Supported Models

Models are downloaded from HuggingFace in GGUF format:

| Model | Size | Use Case |
|-------|------|----------|
| Qwen3-0.6B-Q4_K_M | ~450MB | Ultra-light, basic tasks |
| Qwen3-1.7B-Q4_K_M | ~1.1GB | Default, balanced |
| Qwen3-4B-Q4_K_M | ~2.6GB | Higher quality reasoning |

## Performance Notes

On a typical modern Android device (e.g., Snapdragon 8 Gen 2):
- Model loading: 2-5 seconds
- Prompt processing: 50-100 tokens/second
- Generation: 10-30 tokens/second

Performance varies significantly based on:
- Device CPU capabilities (SME, AMX instructions)
- Available RAM
- Model quantization level
- Context window size
