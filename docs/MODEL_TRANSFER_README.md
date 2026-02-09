# Model Transfer Over Atmosphere Mesh

## Quick Overview

The Model Transfer system enables peer-to-peer discovery and transfer of ML models across the Atmosphere mesh. Instead of downloading models from HuggingFace, Android devices discover models from LlamaFarm instances running on mesh peers and transfer them directly.

**Key benefits:**
- ⚡ **Faster** - Direct LAN transfer instead of internet download
- 🔒 **Offline** - Works without internet connectivity
- 🌐 **Mesh-native** - Automatic discovery via gossip protocol
- 💾 **Efficient** - Resume capability for large models
- ✅ **Verified** - SHA-256 hash verification

## Architecture

```
┌─────────────────┐                    ┌─────────────────┐
│  Android Device │                    │   LlamaFarm     │
│                 │                    │   (Mac/Linux)   │
│  ┌───────────┐  │  ◄─── Gossip ───  │  ┌───────────┐  │
│  │ Model     │  │   model_catalog   │  │ Model     │  │
│  │ Catalog   │  │                    │  │ Bridge    │  │
│  └─────┬─────┘  │                    │  └─────┬─────┘  │
│        │        │                    │        │        │
│  ┌─────▼─────┐  │                    │  ┌─────▼─────┐  │
│  │ Transfer  │  │  ──── HTTP ───►   │  │   HTTP    │  │
│  │ Service   │  │   (LAN direct)    │  │  Server   │  │
│  └───────────┘  │                    │  └───────────┘  │
│                 │                    │                 │
│  ┌───────────┐  │                    │  ┌───────────┐  │
│  │ Model     │  │                    │  │ ~/.llamafarm│
│  │ Manager   │  │                    │  │ ~/.cache/hf │
│  └───────────┘  │                    │  └───────────┘  │
└─────────────────┘                    └─────────────────┘
```

## Components

### 1. Protocol (`MODEL_TRANSFER_PROTOCOL.md`)
Complete protocol specification for model discovery and transfer.

### 2. Android Components

**ModelCatalog.kt**
- Maintains catalog of available models from all peers
- Tracks which peers have which models
- Selects best peer for download

**ModelTransferService.kt**
- Downloads models via HTTP or WebSocket
- Progress tracking, resume support
- SHA-256 verification
- Stores models in app storage

### 3. Python Bridge (`atmosphere/model_bridge.py`)

**LlamaFarm side:**
- Scans local models and HuggingFace cache
- Serves models via HTTP
- Gossips catalog to mesh every 5 minutes

## Quick Start

### Android (Kotlin)

```kotlin
// Initialize
val transferService = ModelTransferService(context, meshConnection, modelManager)

// Browse available models
transferService.modelCatalog.availableModels.collect { models ->
    models.forEach { model ->
        println("${model.name} (${model.sizeBytes / 1_000_000} MB) from ${model.availableOnPeers.size} peers")
    }
}

// Download a model
val requestId = transferService.downloadModel("yolov8n-coco")

// Track progress
transferService.downloadStates.collect { states ->
    when (val state = states["yolov8n-coco"]) {
        is DownloadState.Downloading -> {
            println("${state.progress.progressPercent}%")
        }
        is DownloadState.Completed -> {
            println("Downloaded to: ${state.storagePath}")
        }
    }
}
```

### Python (LlamaFarm)

```python
from atmosphere.model_bridge import ModelBridge

# Start bridge
bridge = ModelBridge(http_port=14345, gossip_interval=300)
await bridge.start()

# Automatically scans:
# - ~/.llamafarm/models/vision/
# - ~/.cache/huggingface/hub/

# Serves models at:
# http://localhost:14345/v1/models/download/{model_id}

# Gossips catalog every 5 minutes
```

## Transfer Modes

### 1. Direct HTTP (LAN - Preferred)
- Both devices on same network
- Direct TCP connection
- Full speed transfer (100+ Mbps)
- HTTP Range requests for resume

### 2. WebSocket Chunked (Relay)
- Devices behind NAT / on different networks
- Routed through relay server
- Flow control (2 chunks in-flight max)
- Slower but works anywhere

**The system automatically chooses the best mode.**

## Model Catalog Format

Models are advertised via gossip with:
- `model_id` - Unique identifier
- `name` - Human-readable name
- `type` - "vision", "llm", "audio", "embedding"
- `format` - "pt", "gguf", "tflite", "onnx"
- `size_bytes` - Total size
- `sha256` - Hash for verification
- `capabilities` - What the model can do
- `classes` - For vision models: detected objects

**Example:**
```json
{
  "model_id": "yolov8n-coco",
  "name": "YOLOv8 Nano - COCO",
  "type": "vision",
  "format": "pt",
  "size_bytes": 6234567,
  "sha256": "a3f2e8...",
  "capabilities": ["object_detection"],
  "classes": ["person", "car", "bird", "..."]
}
```

## Testing

### Test Python Bridge
```bash
cd /Users/robthelen/clawd/projects/atmosphere
python scripts/test_model_bridge.py
```

Tests:
- ✓ Model scanning (LlamaFarm + HuggingFace cache)
- ✓ HTTP server endpoints
- ✓ Catalog message generation
- ✓ Resume capability (Range requests)

### Test Android (Manual)
1. Start LlamaFarm with model bridge
2. Start Android app, connect to mesh
3. Wait for gossip or trigger refresh
4. Browse models in Model Browser screen
5. Download a model
6. Verify progress tracking
7. Verify SHA-256 on completion

## Files Created

### Documentation
- `docs/MODEL_TRANSFER_PROTOCOL.md` - Complete protocol spec
- `docs/MODEL_TRANSFER_INTEGRATION.md` - Integration guide
- `docs/MODEL_TRANSFER_README.md` - This file

### Android (Kotlin)
- `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelCatalog.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelTransferService.kt`

### Python
- `atmosphere/atmosphere/model_bridge.py`
- `atmosphere/scripts/test_model_bridge.py`

## Next Steps

1. **Integration** - Wire into MainActivity and add UI
2. **Testing** - Run end-to-end tests with real devices
3. **Polish** - Add notification progress, filters, search
4. **Optimization** - Implement multi-peer downloads, differential updates

## References

- **Protocol Spec:** `MODEL_TRANSFER_PROTOCOL.md`
- **Integration Guide:** `MODEL_TRANSFER_INTEGRATION.md`
- **Vision Training Plan:** `/Users/robthelen/clawd/projects/llamafarm-core/plan-training.md` (Phase 6)

## Status

✅ **Protocol designed**  
✅ **Android ModelCatalog implemented**  
✅ **Android ModelTransferService implemented**  
✅ **Python ModelBridge implemented**  
✅ **Test suite created**  
⏳ **UI integration** (next)  
⏳ **End-to-end testing** (next)

---

**Last Updated:** 2026-02-08  
**Version:** 1.0
