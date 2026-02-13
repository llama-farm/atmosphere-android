# Testing Android Embedding + Capability Detection

## Build libatmo_jni.so

From `atmosphere-core`:
```bash
cargo build --target aarch64-linux-android --release -p atmo-jni
```

Copy to Android project:
```bash
mkdir -p ../atmosphere-android/app/src/main/jniLibs/arm64-v8a/
cp target/aarch64-linux-android/release/libatmo_jni.so \
   ../atmosphere-android/app/src/main/jniLibs/arm64-v8a/
```

## Deploy & Test

1. **Build & Install APK**:
   ```bash
   cd ../atmosphere-android
   ./gradlew installDebug
   ```

2. **Monitor Logs**:
   ```bash
   adb logcat -s Atmosphere AtmosphereService AtmosphereNative
   ```

3. **Check for**:
   - ✅ `Copying model.onnx from assets (~22MB)`
   - ✅ `Text embedder initialized successfully`
   - ✅ `Test embedding: 384 dimensions`
   - ✅ `Registered Llama 3.2 1B capability`
   - ✅ `Gemini Nano detected: ...`
   - ✅ `Registered text embedding capability`

4. **Verify CRDT Capabilities**:
   ```kotlin
   // In Android app or via AtmosphereService
   val caps = AtmosphereNative.query(handle, "_capabilities")
   Log.i("TEST", "Capabilities: $caps")
   ```

   Should show:
   - `local:llama-3.2-1b:default`
   - `local:gemini-nano:nano-v2` (on Pixel 9+)
   - `local:embedding:minilm-l6-v2`
   - ML Kit features (if Gemini available)

5. **Test Embedding**:
   ```kotlin
   val embedding = AtmosphereNative.nativeEmbed("Hello world")
   Log.i("TEST", "Embedding dim: ${embedding?.size}") // Should be 384
   
   val emb1 = AtmosphereNative.nativeEmbed("cat")!!
   val emb2 = AtmosphereNative.nativeEmbed("dog")!!
   val sim = AtmosphereNative.nativeCosineSimilarity(emb1, emb2)
   Log.i("TEST", "cat-dog similarity: $sim") // Should be > 0.5
   ```

## Verify Mesh Propagation

On Mac peer:
```bash
# Query CRDT to see Android capabilities
curl http://localhost:14345/api/crdt/_capabilities | jq '.[] | select(.peer_id | contains("android"))'
```

Should show all Android capabilities with metadata.

## Troubleshooting

**"Failed to initialize embedder"**:
- Check `app/src/main/assets/models/` has `model.onnx` and `tokenizer.json`
- Verify files copied to internal storage: `/data/data/com.llamafarm.atmosphere/files/models/`

**"UnsatisfiedLinkError"**:
- Ensure `libatmo_jni.so` is in `app/src/main/jniLibs/arm64-v8a/`
- Clean build: `./gradlew clean`

**Gemini Nano shows "UNAVAILABLE"**:
- Expected on non-Pixel or Pixel < 9
- ML Kit GenAI dependency not added (detection is placeholder)
- To enable: Add `implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")` to `build.gradle`

## Model Files

**Download model.onnx**:
```bash
cd app/src/main/assets/models/
# Copy from atmosphere-core:
cp ~/clawd/projects/atmosphere-core/models/model.onnx .
```

**Or download from HuggingFace**:
```bash
wget https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx
```

Size: ~22MB (acceptable for bundling in APK)
