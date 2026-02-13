# Multi-Vendor Android AI Detection

## Overview

The Atmosphere Android app now features **universal AI capability detection** that works across all major Android device manufacturers using runtime feature detection, not hardcoded device model checks.

## Supported Platforms

### 1. **Google Pixel** (7, 8, 9, 10 series)
**Runtime Detection:** ML Kit GenAI API class check
```kotlin
Class.forName("com.google.mlkit.genai.prompt.Generation")
```

**Available Models:**
- **Gemini Nano v2/v3** - 2.7-4.0B parameters
  - Prompt API (multimodal LLM)
  - Summarization
  - Proofreading
  - Rewriting
  - Image Description

**Capability IDs:**
- `google:gemini-nano:prompt`
- `google:gemini-nano:summarization`
- `google:gemini-nano:proofreading`
- `google:gemini-nano:rewriting`
- `google:gemini-nano:image_description`

---

### 2. **Samsung Galaxy** (S23, S24, S25 series)
**Runtime Detection:** Samsung Neural SDK library check
```kotlin
checkLibraryExists("libsamneuralnetworks.so")
|| checkPackageExists("com.samsung.android.sdk.ai")
|| checkClassExists("com.samsung.android.sdk.neural.NeuralNetwork")
```

**Available Models:**
- **Samsung ONE** (On-device Neural Engine) - NPU accelerator
- **Galaxy AI** - Text processing (S24+, Android 14+)
  - Translation
  - Summarization  
  - Writing assist

**Capability IDs:**
- `samsung:neural:one-engine`
- `samsung:galaxy-ai:text`

---

### 3. **Qualcomm Snapdragon** (OnePlus, Xiaomi, Motorola, etc.)
**Runtime Detection:** QNN SDK library check
```kotlin
checkLibraryExists("libQnnHtp.so")      // Hexagon Tensor Processor
|| checkLibraryExists("libQnnHtpV75.so") // V75 (8 Gen 3)
|| checkLibraryExists("libQnnHtpV73.so") // V73 (8 Gen 2)
|| checkClassExists("com.qualcomm.qti.snpe.NeuralNetwork") // SNPE
```

**Available Hardware:**
- **Snapdragon 8 Gen 2** (kalama) - Hexagon NPU
- **Snapdragon 8 Gen 3** (pineapple) - Hexagon NPU
- **Snapdragon 8 Elite** (sun) - Hexagon NPU

**Models:**
- QNN accelerator (ONNX Runtime, LiteRT support)
- On-device LLM (OEM-dependent, usually disabled)

**Capability IDs:**
- `qualcomm:qnn:htp`
- `qualcomm:snapdragon:llm` (availability: false by default)

---

### 4. **MediaTek Dimensity** (Some Xiaomi, Oppo, Vivo)
**Runtime Detection:** NeuroPilot SDK library check
```kotlin
checkLibraryExists("libneuronusdk_adapter.so")
|| checkLibraryExists("libneuron_runtime.so")
|| checkLibraryExists("libAPUSys.so")
|| checkClassExists("com.mediatek.neuropilot.NeuroPilotSDK")
```

**Available Hardware:**
- **APU** (AI Processing Unit) - MediaTek's NPU

**Capability IDs:**
- `mediatek:neuropilot:apu`

---

### 5. **Universal** (All Android Devices)
**Runtime Detection:** API level & class checks

**Available Accelerators:**
- **NNAPI** (Android 8.1+) - vendor-agnostic NPU/GPU/DSP
  ```kotlin
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
  ```

- **LiteRT / TensorFlow Lite** - GPU delegates
  ```kotlin
  Class.forName("org.tensorflow.lite.Interpreter")
  ```

- **ONNX Runtime** - cross-platform inference
  ```kotlin
  Class.forName("ai.onnxruntime.OrtEnvironment")
  ```

**Capability IDs:**
- `universal:nnapi`
- `universal:litert`
- `universal:onnx-runtime`

---

## Detection Strategy

### Principles
1. ✅ **Runtime feature detection** - check for libraries, classes, APIs
2. ❌ **No hardcoded device checks** - avoid `Build.MODEL` comparisons
3. ✅ **Graceful fallback** - if nothing detected, use bundled Llama 3.2 GGUF
4. ✅ **Vendor-agnostic** - works on any Android device

### Detection Flow
```kotlin
suspend fun detectAll(context: Context): List<AICapability> {
    1. detectGoogleAICore()        // ML Kit GenAI class check
    2. detectSamsungNeural()        // libsamneuralnetworks.so check
    3. detectQualcommQNN()          // libQnnHtp*.so check
    4. detectMediaTekNeuroPilot()   // libneuron*.so check
    5. detectUniversalAccelerators() // NNAPI/LiteRT/ONNX class checks
    
    return all detected capabilities
}
```

### Library Checking
```kotlin
private fun checkLibraryExists(libName: String): Boolean {
    return try {
        System.loadLibrary(libName.removeSuffix(".so"))
        true
    } catch (e: UnsatisfiedLinkError) {
        // Check filesystem as fallback
        possiblePaths.any { File(it).exists() }
    }
}
```

Common library paths:
- `/vendor/lib64/`
- `/system/lib64/`
- `/system/vendor/lib64/`

---

## CRDT Capability Format

Each detected capability is registered as a CRDT document in the `_capabilities` collection:

```json
{
  "_id": "google:gemini-nano:prompt",
  "peer_id": "android-pixel9-abc123",
  "peer_name": "Pixel 9 Pro",
  "capability_type": "llm",
  "name": "Gemini Nano (Prompt)",
  "vendor": "google",
  "runtime": "aicore",
  "available": true,
  "version": "nano-v2",
  "device_info": {
    "cpu_cores": 8,
    "memory_gb": 12.0,
    "battery_level": 85,
    "is_charging": false
  },
  "model_info": {
    "context_length": 4000,
    "supports_vision": true,
    "supports_tools": false,
    "model_tier": "small"
  },
  "cost": {
    "local": true,
    "estimated_cost": 0.0,
    "battery_impact": 0.4
  },
  "status": {
    "available": true,
    "load": 0.0,
    "last_seen": 1707857234
  },
  "hops": 0
}
```

---

## Example Detection Results

### Pixel 9 Pro
```
google: 5 (llm, vision)
universal: 3 (accelerator)
Total: 8 capabilities
```

### Samsung Galaxy S24 Ultra
```
samsung: 2 (accelerator, llm)
qualcomm: 1 (accelerator)  # Snapdragon variant
universal: 3 (accelerator)
Total: 6 capabilities
```

### OnePlus 12 (Snapdragon 8 Gen 3)
```
qualcomm: 1 (accelerator)
universal: 3 (accelerator)
Total: 4 capabilities
```

### Xiaomi 14 (MediaTek Dimensity)
```
mediatek: 1 (accelerator)
universal: 3 (accelerator)
Total: 4 capabilities
```

### Generic Android (No Vendor SDK)
```
universal: 3 (accelerator)
bundled: 2 (llm, embedding)  # Llama 3.2 + all-MiniLM
Total: 5 capabilities
```

---

## Mesh Propagation

All detected capabilities automatically sync to other peers via CRDT:

```bash
# On Mac peer - query Android capabilities
curl http://localhost:14345/api/crdt/_capabilities | \
  jq '.[] | select(.vendor=="samsung" or .vendor=="qualcomm")'
```

This enables:
- **Cross-platform discovery** - Mac sees Android's Gemini Nano
- **Smart routing** - Route requests to best available model
- **Heterogeneous mesh** - Mix Pixel, Samsung, OnePlus devices

---

## Adding New Vendor Support

To add support for a new vendor (e.g., Huawei, Oppo):

1. **Research** their on-device AI SDK:
   - Library names (`.so` files)
   - Package identifiers
   - Class names

2. **Add detection method**:
   ```kotlin
   private fun detectVendorXYZ(context: Context): List<AICapability> {
       val hasSDK = checkLibraryExists("libvendor_ai.so") ||
                    checkClassExists("com.vendor.ai.SDK")
       
       if (hasSDK) {
           return listOf(AICapability(...))
       }
       return emptyList()
   }
   ```

3. **Register in detectAll()**:
   ```kotlin
   capabilities.addAll(detectVendorXYZ(context))
   ```

4. **Test on real device** - verify library detection works

---

## Troubleshooting

**No capabilities detected:**
- Check logcat: `adb logcat -s UniversalAIDetector`
- Verify dependencies in `build.gradle`:
  ```gradle
  implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")  // Gemini Nano
  implementation("org.tensorflow:tensorflow-lite:2.14.0")      // LiteRT
  ```

**Library not found:**
- Libraries may be vendor-locked (e.g., Samsung libs only on Galaxy devices)
- Check filesystem: `adb shell ls -la /vendor/lib64/lib*.so | grep -i neural`

**Capability shows available=false:**
- Model not downloaded (Gemini Nano)
- OEM disabled feature (Qualcomm LLM)
- Insufficient hardware (older chipset)

---

## Performance Impact

Runtime detection is fast (~50-200ms) and runs once on app start:
- Class.forName(): ~5ms per check
- File existence: ~1ms per path
- Library loading attempt: ~10-50ms

All detection happens in background coroutine, doesn't block UI.
