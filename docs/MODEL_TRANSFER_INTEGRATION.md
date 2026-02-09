# Model Transfer Integration Guide

## Overview

This guide explains how to integrate the Model Transfer system into Atmosphere. The system enables peer-to-peer discovery and transfer of ML models across the mesh, replacing direct HuggingFace downloads with mesh-based model sharing.

## Components

### 1. Protocol (`MODEL_TRANSFER_PROTOCOL.md`)

Defines the gossip messages and transfer protocol:
- `model_catalog` - Periodic broadcast of available models
- `model_request` / `model_transfer_*` - Download protocol
- Supports both HTTP (LAN) and WebSocket (relay) transports

### 2. Android Components

#### ModelCatalog.kt
**Location:** `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelCatalog.kt`

**Purpose:** Maintains merged catalog of models available across all mesh peers.

**Key features:**
- Merges catalogs from multiple peers
- Tracks which peers have which models
- Selects best peer for download (prefers HTTP endpoints, low latency, high reliability)
- Auto-expires stale entries (5 minute TTL)
- Exposes StateFlow for UI: `availableModels`

**Usage:**
```kotlin
val catalog = ModelCatalog()

// Process incoming gossip
catalog.processCatalogMessage(nodeId, nodeName, catalogJson)

// Get available models
val visionModels = catalog.getModelsByType(ModelType.VISION)

// Get best peer for download
val model = catalog.getModel("yolov8n-coco")
val bestPeer = model?.getBestPeer()
```

#### ModelTransferService.kt
**Location:** `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelTransferService.kt`

**Purpose:** Handles model downloads from mesh peers.

**Key features:**
- Listens for `model_catalog` gossip messages
- Downloads models via HTTP (preferred) or WebSocket
- Chunked transfer with progress tracking
- SHA-256 verification
- Resume capability for interrupted transfers
- Stores models in app storage
- Updates peer reliability scores

**Usage:**
```kotlin
val transferService = ModelTransferService(context, meshConnection, modelManager)

// Download a model
val requestId = transferService.downloadModel(
    modelId = "yolov8n-coco",
    preferredNodeId = "llamafarm-mac-12345"
)

// Observe download progress
transferService.downloadStates.collect { states ->
    states.forEach { (modelId, state) ->
        when (state) {
            is DownloadState.Downloading -> {
                val progress = state.progress
                println("${progress.progressPercent}% (${progress.transferRateMbps} Mbps)")
            }
            is DownloadState.Completed -> {
                println("Downloaded to: ${state.storagePath}")
            }
            is DownloadState.Failed -> {
                println("Failed: ${state.reason}")
            }
        }
    }
}

// Cancel download
transferService.cancelDownload(requestId)
```

### 3. Python Bridge (`atmosphere/model_bridge.py`)

**Location:** `/Users/robthelen/clawd/projects/atmosphere/atmosphere/model_bridge.py`

**Purpose:** Serves models from LlamaFarm to the mesh.

**Key features:**
- Scans LlamaFarm model storage (`~/.llamafarm/models/vision/`)
- Scans HuggingFace cache (`~/.cache/huggingface/hub/`)
- Computes SHA-256 hashes for verification
- Serves models via HTTP with Range request support (for resume)
- Gossips model catalog every 5 minutes
- Auto-detects model types (vision, LLM) from file extensions and metadata

**Usage:**
```python
from atmosphere.model_bridge import ModelBridge

# Initialize
bridge = ModelBridge(
    llamafarm_models_dir=Path.home() / ".llamafarm" / "models",
    huggingface_cache_dir=Path.home() / ".cache" / "huggingface" / "hub",
    http_port=14345,
    gossip_interval=300
)

# Start (scans models, starts HTTP server, begins gossip)
await bridge.start()

# Connect to mesh (optional - for relay-based gossip)
await bridge.connect_to_mesh("ws://relay.atmosphere.io/mesh")

# Get stats
stats = bridge.get_stats()
print(f"Serving {stats['total_models']} models ({stats['total_size_gb']:.2f} GB)")
```

**HTTP Endpoints:**
- `GET /v1/models` - List all models
- `GET /v1/models/{model_id}` - Get model info
- `GET /v1/models/download/{model_id}` - Download model (supports Range requests)
- `GET /health` - Health check

## Integration Steps

### Step 1: Wire ModelTransferService into MainActivity

**File:** `app/src/main/kotlin/com/llamafarm/atmosphere/MainActivity.kt`

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var meshConnection: MeshConnection
    private lateinit var modelManager: ModelManager
    private lateinit var modelTransferService: ModelTransferService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize existing components
        modelManager = ModelManager(this)
        meshConnection = MeshConnection(this, relayUrl, relayToken)
        
        // NEW: Initialize model transfer service
        modelTransferService = ModelTransferService(this, meshConnection, modelManager)
        
        // Connect to mesh
        meshConnection.connect()
        
        setContent {
            AtmosphereApp(
                meshConnection = meshConnection,
                modelManager = modelManager,
                modelTransferService = modelTransferService  // Pass to UI
            )
        }
    }
}
```

### Step 2: Add Model Browser UI

**File:** `app/src/main/kotlin/com/llamafarm/atmosphere/ui/screens/ModelBrowserScreen.kt` (new)

```kotlin
@Composable
fun ModelBrowserScreen(
    transferService: ModelTransferService
) {
    val availableModels by transferService.modelCatalog.availableModels.collectAsState()
    val downloadStates by transferService.downloadStates.collectAsState()
    
    LazyColumn {
        items(availableModels) { model ->
            ModelCard(
                model = model,
                downloadState = downloadStates[model.modelId],
                onDownload = { transferService.downloadModel(model.modelId) },
                onCancel = { transferService.cancelDownloadByModelId(model.modelId) }
            )
        }
    }
}

@Composable
fun ModelCard(
    model: ModelCatalogEntry,
    downloadState: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = model.name, style = MaterialTheme.typography.titleMedium)
            Text(text = "${model.sizeBytes / 1_000_000} MB • ${model.type.name}")
            Text(text = "Available from: ${model.availableOnPeers.joinToString { it.nodeName }}")
            
            when (downloadState) {
                is DownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = downloadState.progress.progressPercent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${downloadState.progress.progressPercent.toInt()}% (${downloadState.progress.transferRateMbps} Mbps)")
                    Button(onClick = onCancel) { Text("Cancel") }
                }
                is DownloadState.Completed -> {
                    Text("✓ Downloaded", color = Color.Green)
                }
                is DownloadState.Failed -> {
                    Text("✗ Failed: ${downloadState.reason}", color = Color.Red)
                    Button(onClick = onDownload) { Text("Retry") }
                }
                else -> {
                    Button(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}
```

### Step 3: Integrate with Existing ModelManager

**File:** `app/src/main/kotlin/com/llamafarm/atmosphere/inference/ModelManager.kt`

**Modifications needed:**

1. Add method to register models downloaded from mesh:
```kotlin
fun registerMeshModel(modelId: String, filePath: String, metadata: JSONObject) {
    // Parse metadata and create ModelConfig
    val config = ModelConfig(
        id = modelId,
        name = metadata.getString("name"),
        huggingFaceRepo = metadata.optString("source_ref", "mesh"),
        fileName = File(filePath).name,
        sizeBytes = File(filePath).length(),
        description = metadata.optString("description", "")
    )
    
    // Add to available models
    AVAILABLE_MODELS.add(config)
    
    Log.i(TAG, "Registered mesh model: $modelId")
}
```

2. Update `getModelPath()` to check mesh-downloaded models:
```kotlin
fun getModelPath(modelId: String): String? {
    // Check local models first
    val localPath = /* existing logic */
    if (localPath != null) return localPath
    
    // Check mesh-downloaded models
    val meshModelsDir = File(context.filesDir, "models")
    val meshModel = meshModelsDir.listFiles()?.find { 
        it.nameWithoutExtension == modelId 
    }
    return meshModel?.absolutePath
}
```

### Step 4: Start Python Model Bridge on LlamaFarm

**Option A: Standalone Script**

Create `scripts/start_model_bridge.py`:
```python
#!/usr/bin/env python3
import asyncio
from atmosphere.model_bridge import ModelBridge

async def main():
    bridge = ModelBridge(
        http_port=14345,
        gossip_interval=300
    )
    
    await bridge.start()
    
    print(f"ModelBridge running on port {bridge.http_port}")
    print(f"Models available: {bridge.get_stats()['total_models']}")
    
    # Keep running
    try:
        while True:
            await asyncio.sleep(60)
    except KeyboardInterrupt:
        await bridge.stop()

if __name__ == "__main__":
    asyncio.run(main())
```

Run: `python scripts/start_model_bridge.py`

**Option B: Integrate into LlamaFarm Server**

**File:** `server/main.py`

```python
from atmosphere.model_bridge import ModelBridge

# In startup event
@app.on_event("startup")
async def startup():
    # ... existing startup code ...
    
    # Start model bridge
    model_bridge = ModelBridge()
    await model_bridge.start()
    
    # Store reference
    app.state.model_bridge = model_bridge

@app.on_event("shutdown")
async def shutdown():
    if hasattr(app.state, "model_bridge"):
        await app.state.model_bridge.stop()
```

### Step 5: Connect Mesh and LlamaFarm

**On Android:**
1. Connect to relay: `meshConnection.connect()`
2. GossipManager starts listening for `model_catalog` messages
3. ModelTransferService processes catalogs, populates `ModelCatalog`

**On LlamaFarm:**
1. ModelBridge scans local models
2. Starts HTTP server on port 14345
3. Every 5 minutes: gossips `model_catalog` to mesh
4. Android receives catalog, shows models in UI

**LAN Direct Transfer (Fast Path):**
- ModelBridge advertises `http://192.168.1.100:14345` in catalog
- Android sees HTTP endpoint, uses direct HTTP download
- No relay overhead, full speed transfer

**Relay Transfer (Fallback):**
- If no HTTP endpoint (behind NAT), use WebSocket transfer
- Chunked transfer with flow control via relay
- Slower but works anywhere

## Testing

### 1. Test Protocol Parsing

```kotlin
// Test catalog parsing
val catalogJson = JSONObject("""
{
  "type": "model_catalog",
  "node_id": "test-node",
  "node_name": "Test Node",
  "timestamp": 1707398765000,
  "models": [
    {
      "model_id": "yolov8n-test",
      "name": "Test Model",
      "type": "vision",
      "format": "pt",
      "size_bytes": 6234567,
      "sha256": "abc123",
      "version": "1.0.0",
      "capabilities": ["object_detection"],
      "source": "huggingface",
      "source_ref": "ultralytics/yolov8n"
    }
  ],
  "transfer_endpoints": {
    "http": "http://localhost:14345",
    "websocket": true
  }
}
""")

val catalog = ModelCatalog()
catalog.processCatalogMessage("test-node", "Test Node", catalogJson)

val models = catalog.getAllModels()
assertEquals(1, models.size)
assertEquals("yolov8n-test", models[0].modelId)
```

### 2. Test Python Bridge

```bash
# Start bridge
cd /Users/robthelen/clawd/projects/atmosphere
python -m atmosphere.model_bridge

# In another terminal, test endpoints
curl http://localhost:14345/v1/models | jq
curl http://localhost:14345/health

# Test download with range request
curl -H "Range: bytes=0-1023" http://localhost:14345/v1/models/download/yolov8n-coco
```

### 3. End-to-End Test

1. Start LlamaFarm with model bridge
2. Start Android app, connect to relay
3. Wait for gossip (or trigger manual refresh)
4. Check `ModelBrowserScreen` - should show models from LlamaFarm
5. Tap download - should start HTTP transfer
6. Verify SHA-256 on completion
7. Model should appear in inference model selector

## Configuration

### Android

**File:** `app/src/main/res/values/config.xml`

```xml
<resources>
    <string name="relay_url">ws://relay.atmosphere.io/mesh</string>
    <integer name="model_catalog_ttl_seconds">300</integer>
    <integer name="download_chunk_size">65536</integer>
</resources>
```

### Python

**Environment Variables:**
- `ATMOSPHERE_NODE_ID` - Node identifier for gossip
- `ATMOSPHERE_NODE_NAME` - Human-readable node name
- `MODEL_BRIDGE_HTTP_PORT` - HTTP server port (default: 14345)
- `MODEL_BRIDGE_GOSSIP_INTERVAL` - Gossip interval in seconds (default: 300)

## Security Considerations

### Current Implementation
- SHA-256 verification prevents corrupted transfers
- Models stored in app-private directory
- No additional privileges for mesh-downloaded models

### Future Enhancements
1. **Signed Catalogs**
   - GPG signatures on model metadata
   - Verify signatures before download
   - Trust model: only accept from verified nodes

2. **Sandboxed Inference**
   - Run mesh-downloaded models in isolated process
   - Limit resource usage (CPU, memory, network)

3. **Model審計**
   - Log all model downloads (source, hash, timestamp)
   - Allow inspection before loading
   - User consent for first-time downloads

## Performance Tuning

### Bandwidth Management

**File:** `ModelTransferService.kt`

Add throttling:
```kotlin
private fun calculateThrottleRate(modelSizeBytes: Long): Float {
    return when {
        modelSizeBytes < 10_000_000 -> Float.MAX_VALUE  // No throttle for < 10MB
        modelSizeBytes < 500_000_000 -> 5_000_000f      // 5 Mbps for 10-500MB
        else -> 2_000_000f                              // 2 Mbps for > 500MB
    }
}
```

### Parallel Downloads

Future enhancement: download different chunks from different peers simultaneously (BitTorrent-style).

### Caching

- Cache catalog entries locally (SQLite)
- Pre-fetch popular models in background
- Smart eviction (LRU, weighted by size and usage)

## Troubleshooting

### Models not appearing in catalog

**Check:**
1. LlamaFarm model bridge is running: `curl http://localhost:14345/health`
2. Gossip messages are being sent: check bridge logs
3. Android is connected to mesh: check `MeshConnection.connectionState`
4. Catalog is being processed: check `ModelCatalog` logs

**Debug:**
```kotlin
Log.i(TAG, "Catalog size: ${modelCatalog.getAllModels().size}")
Log.i(TAG, "Stats: ${modelCatalog.getStats()}")
```

### Download fails with hash mismatch

**Causes:**
- Corrupted transfer (retry should work)
- Wrong model file served (check bridge catalog)
- Hash computed incorrectly (check bridge logs)

**Debug:**
```kotlin
Log.i(TAG, "Expected hash: ${catalogEntry.sha256}")
Log.i(TAG, "Computed hash: $computedHash")
```

### Download stalls

**Causes:**
- Network issue (timeout should trigger retry)
- Peer went offline (switch to different peer)
- Relay congestion (prefer HTTP direct transfer)

**Debug:**
```kotlin
downloadStates.collect { states ->
    states.values.filterIsInstance<DownloadState.Downloading>().forEach { state ->
        if (state.progress.transferRateMbps < 0.1) {
            Log.w(TAG, "Slow transfer: ${state.progress.transferRateMbps} Mbps")
        }
    }
}
```

## Next Steps

1. **UI Polish**
   - Add model browser tab to main navigation
   - Show transfer progress in notification
   - Add filters (type, size, source)

2. **Smart Prefetching**
   - Detect common inference patterns
   - Pre-download likely-needed models in background
   - E.g., if user runs bird detection, prefetch bird classifier

3. **Differential Updates**
   - For trained models that are derivatives of base models
   - Transfer only weight deltas (1GB → 50MB)
   - Requires model lineage tracking

4. **Multi-Peer Downloads**
   - Download different chunks from different peers
   - BitTorrent-style reassembly
   - Significantly faster for large models

5. **Model Versioning**
   - Track model versions (semantic or timestamp-based)
   - Auto-update on new version
   - Keep old version as rollback option

6. **Feedback Loop**
   - Report transfer success/failure to gossip
   - Peers adjust reliability scores
   - Poor performers deprioritized

## References

- Protocol spec: `docs/MODEL_TRANSFER_PROTOCOL.md`
- Android ModelCatalog: `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelCatalog.kt`
- Android ModelTransferService: `app/src/main/kotlin/com/llamafarm/atmosphere/mesh/ModelTransferService.kt`
- Python ModelBridge: `atmosphere/atmosphere/model_bridge.py`
- Vision training plan: `llamafarm-core/plan-training.md` (Phase 6)
