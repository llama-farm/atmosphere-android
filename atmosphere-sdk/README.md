# Atmosphere SDK for Android

The Atmosphere SDK allows Android apps to use the Atmosphere mesh for AI inference, semantic routing, vision detection, and RAG queries.

## Installation

### Gradle (Module-level)

```gradle
dependencies {
    implementation project(':atmosphere-sdk')
}
```

### Settings.gradle

```gradle
include ':atmosphere-sdk'
```

## Quick Start

### 1. Connect to Atmosphere

```kotlin
import com.llamafarm.atmosphere.sdk.AtmosphereClient

class MyViewModel(application: Application) : AndroidViewModel(application) {
    private var atmosphere: AtmosphereClient? = null
    
    init {
        viewModelScope.launch {
            if (AtmosphereClient.isInstalled(application)) {
                atmosphere = AtmosphereClient.connect(application)
            }
        }
    }
}
```

### 2. Chat Completion (OpenAI-compatible)

```kotlin
val messages = listOf(
    ChatMessage.user("What's the weather like?"),
)

val result = atmosphere.chat(messages)

if (result.success) {
    println("Response: ${result.content}")
    println("Model: ${result.model}")
    println("Tokens: ${result.usage?.totalTokens}")
}
```

### 3. Semantic Routing

```kotlin
val result = atmosphere.route(
    intent = "Summarize this document",
    payload = """{"text": "Long document..."}"""
)

if (result.success) {
    println("Capability: ${result.capability}")
    println("Response: ${result.response}")
    println("Node: ${result.nodeId}")
}
```

### 4. Vision Detection

```kotlin
// From Bitmap
val bitmap = BitmapFactory.decodeResource(resources, R.drawable.photo)
val result = atmosphere.detectObjects(bitmap)

if (result.success) {
    for (detection in result.detections) {
        println("${detection.className}: ${detection.confidence}")
        println("Box: ${detection.bbox}")
    }
    println("Escalated to mesh: ${result.escalated}")
}

// From Camera
val result = atmosphere.captureAndDetect(facing = "back")

// From Base64
val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
val result = atmosphere.detectObjectsBase64(base64)
```

### 5. RAG (Retrieval-Augmented Generation)

```kotlin
// Create index
val documents = listOf(
    "doc1" to "The capital of France is Paris.",
    "doc2" to "The Eiffel Tower is in Paris.",
    "doc3" to "Paris is known for art and culture."
)

val indexResult = atmosphere.createRagIndex("my_index", documents)

// Query with generated answer
val queryResult = atmosphere.queryRag(
    indexId = "my_index",
    query = "What is Paris known for?",
    generateAnswer = true
)

if (queryResult.success) {
    println("Answer: ${queryResult.answer}")
    println("Sources:")
    for (doc in queryResult.documents) {
        println("  - ${doc.content} (score: ${doc.score})")
    }
}
```

## API Reference

### Connection

#### `AtmosphereClient.isInstalled(context: Context): Boolean`
Check if the Atmosphere app is installed.

#### `AtmosphereClient.connect(context: Context): AtmosphereClient`
Connect to the Atmosphere service.

**Throws:** `AtmosphereNotInstalledException`, `AtmosphereNotConnectedException`

#### `disconnect()`
Disconnect from the Atmosphere service.

---

### Chat

#### `chat(messages: List<ChatMessage>, model: String? = null): ChatResult`
OpenAI-compatible chat completion. If `model` is null, uses semantic routing to pick the best model.

**Parameters:**
- `messages` - List of chat messages (user, assistant, system)
- `model` - Optional model name (null for auto-select)

**Returns:** `ChatResult` with `content`, `model`, `usage`, etc.

**Example:**
```kotlin
val messages = listOf(
    ChatMessage.system("You are a helpful assistant."),
    ChatMessage.user("Hello!"),
)
val result = atmosphere.chat(messages)
```

---

### Routing

#### `route(intent: String, payload: String = "{}"): RouteResult`
Route an intent to the best capability in the mesh.

**Parameters:**
- `intent` - Natural language description of task
- `payload` - JSON payload with data

**Returns:** `RouteResult` with `response`, `capability`, `nodeId`, etc.

---

### Vision

#### `detectObjects(bitmap: Bitmap, sourceId: String = "sdk"): VisionResult`
Detect objects in a Bitmap. Automatically converts to JPEG and sends to Atmosphere.

**Returns:** `VisionResult` with `detections`, `model`, `escalated`, etc.

#### `detectObjectsBase64(imageBase64: String, sourceId: String = "sdk"): VisionResult`
Detect objects from a base64-encoded image string.

#### `captureAndDetect(facing: String = "back"): VisionResult`
Capture from device camera and detect objects.

**Parameters:**
- `facing` - "front" or "back"

#### `visionStatus(): VisionStatus`
Get vision capability status (ready, model info, confidence threshold).

**Returns:** `VisionStatus` with `ready`, `modelId`, `modelVersion`, etc.

#### `setVisionConfidenceThreshold(threshold: Float)`
Set confidence threshold for mesh escalation (0.0 to 1.0).

When local detection confidence is below this threshold, request escalates to mesh peers with better models.

#### `sendVisionFeedback(detectionId: String?, correct: Boolean, correctedLabel: String?, imageBase64: String?): Boolean`
Send feedback on a detection result for model training.

**Parameters:**
- `detectionId` - Optional detection ID from metadata
- `correct` - Whether the detection was correct
- `correctedLabel` - If incorrect, what it should have been
- `imageBase64` - Optional image data for retraining

**Example:**
```kotlin
// Detection was wrong
atmosphere.sendVisionFeedback(
    detectionId = null,
    correct = false,
    correctedLabel = "cat",
    imageBase64 = imageBase64
)
```

---

### RAG

#### `createRagIndex(indexId: String, documents: List<Pair<String, String>>): RagIndexResult`
Create a RAG index from documents (id, content pairs).

#### `createRagIndexFromJson(indexId: String, documentsJson: String): RagIndexResult`
Create a RAG index from a JSON array:
```json
[
  {"id": "doc1", "content": "..."},
  {"id": "doc2", "content": "..."}
]
```

#### `queryRag(indexId: String, query: String, generateAnswer: Boolean = true): RagQueryResult`
Query a RAG index with natural language.

**Parameters:**
- `indexId` - The index to query
- `query` - Natural language question
- `generateAnswer` - If true, use LLM to generate answer from retrieved context

**Returns:** `RagQueryResult` with `documents`, `answer`, `context`

#### `deleteRagIndex(indexId: String): Boolean`
Delete a RAG index.

#### `listRagIndexes(): List<RagIndexInfo>`
List all RAG indexes with metadata.

---

### Capabilities

#### `capabilities(): List<Capability>`
Get all available capabilities in the mesh.

**Returns:** List of `Capability` with `id`, `name`, `type`, `nodeId`, `cost`, etc.

#### `capability(capabilityId: String): Capability?`
Get a specific capability by ID.

#### `invoke(capabilityId: String, payload: String = "{}"): RouteResult`
Invoke a specific capability directly (bypass routing).

---

### Mesh

#### `meshStatus(): MeshStatus`
Get current mesh status.

**Returns:** `MeshStatus` with `connected`, `nodeId`, `peerCount`, etc.

#### `joinMesh(meshId: String? = null, credentials: String? = null): MeshJoinResult`
Join a mesh network.

#### `leaveMesh(): Boolean`
Leave the current mesh network.

---

### Observability

#### `meshStatusFlow(): Flow<MeshStatus>`
Observe mesh status changes as a Flow.

```kotlin
viewModelScope.launch {
    atmosphere.meshStatusFlow().collect { status ->
        println("Peers: ${status.peerCount}")
    }
}
```

#### `costMetricsFlow(): Flow<CostMetrics>`
Observe device cost metrics as a Flow.

#### `onMeshUpdate(callback: (MeshStatus) -> Unit)`
Subscribe to mesh updates.

#### `onCostUpdate(callback: (CostMetrics) -> Unit)`
Subscribe to cost updates.

#### `onError(callback: (errorCode: String, message: String) -> Unit)`
Subscribe to errors.

---

## Data Classes

### Vision

```kotlin
data class VisionResult(
    val success: Boolean,
    val detections: List<Detection>,
    val model: String?,
    val inferenceTimeMs: Float,
    val escalated: Boolean,
    val nodeId: String?,
    val error: String?
) {
    fun bestDetection(): Detection?
    fun withMinConfidence(minConfidence: Float): List<Detection>
}

data class Detection(
    val className: String,
    val confidence: Float,
    val bbox: BoundingBox,
    val inferenceTimeMs: Float
) {
    fun format(): String
}

data class BoundingBox(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float
) {
    val width: Float
    val height: Float
    val centerX: Float
    val centerY: Float
    val area: Float
}

data class VisionStatus(
    val ready: Boolean,
    val modelId: String?,
    val modelVersion: String?,
    val numClasses: Int,
    val inputSize: Int,
    val confidenceThreshold: Float
)
```

### Chat

```kotlin
data class ChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        fun user(content: String): ChatMessage
        fun assistant(content: String): ChatMessage
        fun system(content: String): ChatMessage
    }
}

data class ChatResult(
    val success: Boolean,
    val content: String?,
    val model: String?,
    val finishReason: String?,
    val usage: TokenUsage?,
    val error: String?,
    val raw: String?
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
```

### RAG

```kotlin
data class RagQueryResult(
    val success: Boolean,
    val query: String?,
    val documents: List<RagDocument>,
    val answer: String?,
    val context: String?,
    val error: String?
) {
    fun getResponseText(): String
}

data class RagDocument(
    val id: String,
    val content: String,
    val score: Double,
    val matchedTerms: List<String>
)
```

### Mesh

```kotlin
data class MeshStatus(
    val connected: Boolean,
    val nodeId: String?,
    val meshId: String?,
    val peerCount: Int,
    val capabilities: Int,
    val relayConnected: Boolean
)

data class Capability(
    val id: String,
    val name: String,
    val type: String,
    val nodeId: String,
    val cost: Float,
    val available: Boolean,
    val metadata: String
) {
    fun metadataJson(): JSONObject
}

data class CostMetrics(
    val battery: Float,
    val cpu: Float,
    val memory: Float,
    val network: String,
    val thermal: String,
    val overall: Float
) {
    fun isAvailable(): Boolean
}
```

---

## Permissions

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Allow visibility of Atmosphere app (Android 11+) -->
<queries>
    <package android:name="com.llamafarm.atmosphere" />
</queries>
```

---

## Error Handling

### Exceptions

```kotlin
class AtmosphereNotInstalledException : Exception
class AtmosphereNotConnectedException : Exception
```

### Best Practices

```kotlin
try {
    if (!AtmosphereClient.isInstalled(context)) {
        // Show install prompt
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
            AtmosphereClient.getInstallUrl()
        )))
        return
    }
    
    val atmosphere = AtmosphereClient.connect(context)
    
    val result = atmosphere.chat(messages)
    if (result.success) {
        // Handle success
    } else {
        // Handle error: result.error
    }
    
} catch (e: AtmosphereNotInstalledException) {
    // App not installed
} catch (e: AtmosphereNotConnectedException) {
    // Could not connect to service
}
```

---

## Architecture

```
┌─────────────────┐
│   Your App      │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│ Atmosphere SDK  │  (This library)
│ AtmosphereClient│
└────────┬────────┘
         │ AIDL
         ↓
┌─────────────────┐
│ Atmosphere App  │  (Must be installed)
│ Service         │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ↓         ↓
┌──────┐  ┌──────┐
│Local │  │ Mesh │
│Models│  │Peers │
└──────┘  └──────┘
```

---

## Sample Apps

### Chat Demo (`atmosphere-client`)
Simple chat interface demonstrating:
- Chat completion with semantic routing
- Model selector (Auto-Route vs specific models)
- Metadata display (model, latency, node)

### Photo Recognition Demo (`atmosphere-photo`)
Camera + gallery photo recognition demonstrating:
- Vision detection with bounding boxes
- Local vs mesh escalation
- Detection history
- Feedback mechanism

**Location:** `/atmosphere-client` and `/atmosphere-photo` in this repo

---

## License

MIT (or your license here)

## Support

- GitHub: https://github.com/llama-farm/atmosphere-android
- Discord: https://discord.com/invite/clawd
- Docs: https://docs.openclaw.ai
