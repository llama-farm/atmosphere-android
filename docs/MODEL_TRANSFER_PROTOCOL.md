# Model Transfer Protocol

## Overview

The Model Transfer Protocol enables peer-to-peer discovery and transfer of ML models across the Atmosphere mesh. Instead of downloading models from HuggingFace, Android devices discover models on LlamaFarm instances running on mesh peers and transfer them directly.

**Key design principles:**
- **Gossip for discovery, direct transfer for data** - Lightweight catalogs gossip periodically, actual model transfers happen on-demand
- **Chunked transfer with resume** - Models can be 300MB-2GB; must support chunked transfer with resume capability
- **Dual transport** - Works over WebSocket (relay) AND direct HTTP (LAN peers)
- **Hash verification** - SHA-256 verification ensures integrity
- **Bandwidth management** - Flow control to avoid saturating mesh bandwidth

---

## Message Types

All messages are JSON, sent via WebSocket gossip or HTTP endpoints.

### 1. `model_catalog` - Periodic Broadcast

Announces available models to the mesh. Sent every 5 minutes.

```json
{
  "type": "model_catalog",
  "node_id": "llamafarm-mac-12345",
  "node_name": "Rob's Macbook",
  "timestamp": 1707398765000,
  "models": [
    {
      "model_id": "yolov8n-coco",
      "name": "YOLOv8 Nano - COCO",
      "type": "vision",
      "format": "pt",
      "size_bytes": 6234567,
      "sha256": "a3f2e8...",
      "version": "2024.02.08.001",
      "capabilities": ["object_detection", "segmentation"],
      "base_model": "yolov8n",
      "classes": ["person", "car", "bird", "..."],
      "class_count": 80,
      "source": "huggingface",
      "source_ref": "ultralytics/yolov8n",
      "metadata": {
        "trained_on": "COCO 2017",
        "mAP50": 0.52,
        "inference_ms": 5
      }
    },
    {
      "model_id": "qwen3-1.7b-q4km",
      "name": "Qwen3 1.7B Q4_K_M",
      "type": "llm",
      "format": "gguf",
      "size_bytes": 1100000000,
      "sha256": "7d3c91...",
      "version": "2024.02.01.003",
      "capabilities": ["text_generation", "chat"],
      "base_model": "Qwen3-1.7B",
      "context_length": 4096,
      "source": "huggingface",
      "source_ref": "unsloth/Qwen3-1.7B-GGUF"
    },
    {
      "model_id": "bird-detector_20260208_160000",
      "name": "Bird Detector (Custom Trained)",
      "type": "vision",
      "format": "pt",
      "size_bytes": 8123456,
      "sha256": "9f1a3e...",
      "version": "2026.02.08.002",
      "capabilities": ["object_detection"],
      "base_model": "yolov8n",
      "classes": ["person", "bird", "airplane"],
      "class_count": 3,
      "source": "llamafarm_training",
      "source_ref": "local",
      "metadata": {
        "trained_samples": 120,
        "validation_mAP50": 0.87,
        "parent_model": "yolov8n-coco"
      }
    }
  ],
  "transfer_endpoints": {
    "http": "http://192.168.1.100:14345",
    "websocket": true
  },
  "ttl_seconds": 300
}
```

**Fields:**
- `model_id` - Unique identifier (format: `{name}` or `{name}_{timestamp}` for trained models)
- `type` - `"vision"`, `"llm"`, `"audio"`, `"embedding"`
- `format` - File format: `"pt"` (PyTorch), `"gguf"`, `"tflite"`, `"onnx"`
- `size_bytes` - Total size in bytes
- `sha256` - Hash of model file for verification
- `version` - Semantic version or timestamp-based
- `capabilities` - List of what the model can do
- `classes` / `class_count` - For vision models: what it detects
- `source` - `"huggingface"`, `"llamafarm_training"`, `"imported"`
- `transfer_endpoints` - Where to get the model (HTTP preferred for LAN, WebSocket for relay)

**Gossip behavior:**
- Broadcasted every 5 minutes via `GossipManager`
- TTL: 300 seconds (expires if not refreshed)
- Merged with catalogs from other peers (de-duped by `model_id` + `node_id`)

---

### 2. `model_request` - Request a Model

Sent when a peer wants to download a model.

```json
{
  "type": "model_request",
  "request_id": "req-uuid-12345",
  "requester_node_id": "android-pixel-67890",
  "target_node_id": "llamafarm-mac-12345",
  "model_id": "yolov8n-coco",
  "resume_from_byte": 0,
  "transport": "http"
}
```

**Fields:**
- `request_id` - UUID for tracking
- `resume_from_byte` - For resuming interrupted transfers (0 = fresh download)
- `transport` - `"http"` (preferred for LAN) or `"websocket"` (for relay)

**Response:**
- HTTP: `200 OK` with `model_transfer_start` message
- WebSocket: `model_transfer_start` message broadcast back

---

### 3. `model_transfer_start` - Initiate Transfer

Response to `model_request`, sent before first chunk.

```json
{
  "type": "model_transfer_start",
  "request_id": "req-uuid-12345",
  "model_id": "yolov8n-coco",
  "total_bytes": 6234567,
  "total_chunks": 96,
  "chunk_size": 65536,
  "sha256": "a3f2e8...",
  "metadata": {
    "name": "YOLOv8 Nano - COCO",
    "type": "vision",
    "format": "pt",
    "classes": ["person", "car", "bird", "..."]
  },
  "transfer_method": "http_range"
}
```

**Fields:**
- `total_chunks` - Number of chunks (ceiling of `total_bytes / chunk_size`)
- `chunk_size` - Size of each chunk (default: 64KB)
- `transfer_method`:
  - `"http_range"` - Use HTTP Range requests (recommended for LAN)
  - `"websocket_chunked"` - Send chunks over WebSocket (for relay)

---

### 4. `model_transfer_chunk` - Individual Chunk (WebSocket Only)

For WebSocket-based transfers. HTTP transfers use standard Range requests.

```json
{
  "type": "model_transfer_chunk",
  "request_id": "req-uuid-12345",
  "model_id": "yolov8n-coco",
  "chunk_index": 0,
  "total_chunks": 96,
  "chunk_bytes": "base64-encoded-data...",
  "chunk_sha256": "partial-hash..."
}
```

**Fields:**
- `chunk_index` - 0-based index
- `chunk_bytes` - Base64-encoded binary data (max 64KB decoded)
- `chunk_sha256` - Hash of this chunk (optional, for verification)

**Flow control:**
- Receiver sends ACK after each chunk
- Sender waits for ACK before sending next chunk
- Max in-flight chunks: 2 (to prevent buffer overflow)

---

### 5. `model_transfer_chunk_ack` - Acknowledge Chunk (WebSocket Only)

```json
{
  "type": "model_transfer_chunk_ack",
  "request_id": "req-uuid-12345",
  "chunk_index": 0,
  "status": "received"
}
```

Sender waits for this before sending the next chunk.

---

### 6. `model_transfer_complete` - Transfer Done

Sent after all chunks delivered (WebSocket) or after HTTP download finishes.

```json
{
  "type": "model_transfer_complete",
  "request_id": "req-uuid-12345",
  "model_id": "yolov8n-coco",
  "total_bytes_received": 6234567,
  "sha256_computed": "a3f2e8...",
  "sha256_expected": "a3f2e8...",
  "verification_status": "success",
  "storage_path": "/data/user/0/com.llamafarm.atmosphere/files/models/yolov8n-coco.pt"
}
```

**Verification:**
- Receiver computes SHA-256 of reassembled file
- If mismatch: delete file, send `model_transfer_cancel` with reason
- If match: store model, integrate with `ModelManager`

---

### 7. `model_transfer_cancel` - Abort Transfer

Either side can cancel.

```json
{
  "type": "model_transfer_cancel",
  "request_id": "req-uuid-12345",
  "model_id": "yolov8n-coco",
  "reason": "hash_mismatch",
  "message": "SHA-256 verification failed",
  "cancelled_by": "android-pixel-67890"
}
```

**Reasons:**
- `"hash_mismatch"` - Verification failed
- `"timeout"` - Transfer took too long
- `"network_error"` - Connection lost
- `"user_cancelled"` - User stopped download
- `"storage_full"` - Not enough space

---

## Transfer Modes

### Mode 1: Direct HTTP Transfer (LAN Peers - Recommended)

When both peers are on the same LAN and can reach each other directly:

1. Receiver gets `model_catalog` gossip with `transfer_endpoints.http`
2. Receiver sends `model_request` with `transport: "http"`
3. Sender responds with `model_transfer_start`
4. Receiver makes HTTP Range requests to download chunks:
   ```
   GET /v1/models/download/{model_id}
   Range: bytes=0-65535
   ```
5. Sender responds with chunk data
6. Receiver repeats for all chunks, assembles file
7. Receiver verifies SHA-256
8. Receiver sends `model_transfer_complete` (or `cancel` if mismatch)

**Advantages:**
- Fast (direct TCP, no relay overhead)
- Standard HTTP Range requests (resumable)
- Can use HTTP/2 multiplexing

**Resume support:**
```
GET /v1/models/download/{model_id}
Range: bytes=3145728-
```
Receiver tracks last successfully received byte, resumes from there on reconnect.

---

### Mode 2: WebSocket Chunked Transfer (Relay)

When peers are NOT on the same LAN and must communicate via relay:

1. Receiver gets `model_catalog` via relay gossip
2. Receiver sends `model_request` with `transport: "websocket"`
3. Relay forwards request to sender
4. Sender sends `model_transfer_start`
5. For each chunk:
   - Sender sends `model_transfer_chunk` via relay
   - Relay forwards to receiver
   - Receiver sends `model_transfer_chunk_ack`
   - Relay forwards ACK to sender
6. After all chunks: receiver verifies SHA-256
7. Receiver sends `model_transfer_complete` (or `cancel`)

**Flow control:**
- Max 2 chunks in-flight (sender waits for ACK before sending chunk N+2)
- If no ACK within 30 seconds: retry chunk
- After 3 retries: send `model_transfer_cancel`

**Resume support:**
- Receiver tracks chunks received in bitmap
- On reconnect: sends `model_request` with `resume_from_byte`
- Sender calculates chunk index from byte offset, resumes

---

## Bandwidth Management

### Rate Limiting

To avoid saturating the mesh:

- **Small models (< 10MB)**: no throttling (vision TFLite, embeddings)
- **Medium models (10-500MB)**: max 5 Mbps (YOLOv8, small LLMs)
- **Large models (> 500MB)**: max 2 Mbps (large GGUF LLMs)

**Adaptive throttling:**
- Monitor mesh latency during transfer
- If ping to relay increases > 100ms: reduce rate by 50%
- If ping returns to normal: gradually increase

### Priority

Models have transfer priority:

1. **Critical** - Models needed for active inference (e.g., cascade escalation waiting on model)
2. **High** - User-requested downloads
3. **Normal** - Background syncing
4. **Low** - Opportunistic prefetch

Only one transfer per priority level can be active at a time.

---

## Progress Tracking

### Download Progress Event

Emitted periodically during transfer:

```json
{
  "type": "model_transfer_progress",
  "request_id": "req-uuid-12345",
  "model_id": "yolov8n-coco",
  "bytes_downloaded": 3145728,
  "total_bytes": 6234567,
  "progress_percent": 50.4,
  "transfer_rate_mbps": 4.2,
  "eta_seconds": 147,
  "chunks_received": 48,
  "total_chunks": 96
}
```

**UI integration:**
- Show in notification bar during download
- Display in model list with progress bar
- Allow pause/resume/cancel

---

## Catalog Management

### ModelCatalog (Android)

Maintains merged catalog from all peers:

```kotlin
data class ModelCatalogEntry(
    val modelId: String,
    val name: String,
    val type: ModelType,
    val format: String,
    val sizeBytes: Long,
    val sha256: String,
    val version: String,
    val capabilities: List<String>,
    val classes: List<String>?,
    val classCount: Int?,
    val source: String,
    val sourceRef: String,
    val metadata: Map<String, Any>,
    
    // Mesh availability
    val availableOnPeers: List<PeerModelInfo>,
    val lastSeen: Long,
    val ttl: Long
)

data class PeerModelInfo(
    val nodeId: String,
    val nodeName: String,
    val httpEndpoint: String?,
    val websocketAvailable: Boolean,
    val latencyMs: Float?,
    val reliability: Float  // 0.0-1.0, based on past transfer success
)
```

**Catalog merge logic:**
- Same `model_id` from multiple peers → merge into one entry with multiple `availableOnPeers`
- Prefer peers with:
  1. Direct HTTP endpoint (LAN)
  2. Lower latency
  3. Higher reliability score
- Expire entries after TTL (5 minutes default)

**StateFlow for UI:**
```kotlin
val availableModels: StateFlow<List<ModelCatalogEntry>>
val downloadingModels: StateFlow<Map<String, DownloadProgress>>
```

---

## Storage Integration

### Android

Downloaded models stored at:
```
/data/data/com.llamafarm.atmosphere/files/models/{model_id}.{format}
```

Metadata stored alongside:
```
/data/data/com.llamafarm.atmosphere/files/models/{model_id}.json
```

### LlamaFarm (Python)

Models served from:
```
~/.llamafarm/models/vision/{model_name}_{timestamp}/model.pt
~/.cache/huggingface/hub/models--{repo}/snapshots/{hash}/{file}
```

Model catalog built by scanning both directories.

---

## Security Considerations

### Hash Verification

**Always verify SHA-256 after transfer:**
- Prevents corrupted transfers
- Prevents malicious model injection (if hashes are signed/verified)

Future: GPG signatures on model metadata, signed by trusted peers.

### Sandboxing

Models loaded from mesh should run in same security context as user-downloaded models (no additional privileges).

### Storage Limits

Enforce max total model storage (e.g., 5GB on Android). Auto-delete least-recently-used models if limit exceeded.

---

## Example Flow: Android Downloads YOLOv8n from LlamaFarm

1. **Discovery (Gossip)**
   - LlamaFarm broadcasts `model_catalog` every 5 minutes
   - Android receives via `MeshConnection`, passes to `ModelTransferService`
   - `ModelCatalog` merges into available models list
   - UI shows: "YOLOv8 Nano - COCO (6.2 MB) - Available from Rob's Macbook"

2. **Request (User taps download)**
   - `ModelTransferService.downloadModel("yolov8n-coco", preferredNodeId = "llamafarm-mac-12345")`
   - Check `transfer_endpoints`: sees `http://192.168.1.100:14345`
   - Sends `model_request` with `transport: "http"`

3. **Transfer (Direct HTTP)**
   - LlamaFarm responds with `model_transfer_start`
   - Android makes range requests:
     ```
     GET http://192.168.1.100:14345/v1/models/download/yolov8n-coco
     Range: bytes=0-65535
     ```
   - Repeat for all chunks, write to temp file
   - Progress updates emitted to UI

4. **Verification**
   - Compute SHA-256 of downloaded file
   - Compare to `sha256` from catalog
   - If match: rename temp to final path, send `model_transfer_complete`
   - If mismatch: delete temp, send `model_transfer_cancel`, show error

5. **Integration**
   - `ModelTransferService` notifies `ModelManager`
   - `ModelManager` adds model to available list
   - Model appears in inference model selector
   - User can now use for local inference

---

## Future Enhancements

### Differential Model Updates

For trained models that are derivatives of base models:
- Transfer only the weight deltas, not entire model
- Receiver applies delta to base model
- Could reduce transfer size from 1GB → 50MB

### Peer Selection Heuristics

Choose best peer for transfer based on:
- Latency (ping time)
- Reliability (past success rate)
- Load (number of active transfers)
- Bandwidth (advertised in catalog)

### Multi-Peer Parallel Download

Download different chunks from different peers simultaneously:
- Chunks 0-31 from Peer A
- Chunks 32-63 from Peer B
- Chunks 64-95 from Peer C
- BitTorrent-style reassembly

### Model Versioning & Updates

When a new version is trained:
- Broadcast `model_catalog_update` with new version
- Peers can auto-update or prompt user
- Keep old version as rollback option

---

## Protocol Version

**Version:** `1.0`  
**Date:** 2026-02-08

Future protocol changes MUST include `protocol_version` field in all messages for backward compatibility.
