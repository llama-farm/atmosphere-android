# Atmosphere Android

**CRDT mesh client with local AI inference for Android.**

Atmosphere Android is the mobile client for the [Atmosphere](https://github.com/llama-farm/atmosphere-core) mesh network. It connects to nearby peers over multiple transports and synchronizes state using CRDTs, with on-device inference powered by Llama 3.2 1B.

## Features

- **Mesh Networking** — automatic peer discovery and CRDT sync
- **Multiple Transports** — BLE, Wi-Fi Aware, LAN (mDNS), WebSocket relay
- **JNI Bridge** — calls into the Rust core (`atmo-jni`) for all mesh logic
- **Local Inference** — Llama 3.2 1B via llama.cpp AAR, runs entirely on-device

## Architecture

```
┌─────────────────────────────────────────────┐
│  Third-party apps (Drone, etc.)             │
│    └── atmosphere-sdk (AIDL IPC)            │
├─────────────────────────────────────────────┤
│  Atmosphere App                             │
│    ├── Jetpack Compose UI                   │
│    ├── AtmosphereService (foreground svc)   │
│    └── AtmosphereBinderService (AIDL host)  │
├─────────────────────────────────────────────┤
│  atmo-jni (JNI bridge)                      │
├─────────────────────────────────────────────┤
│  atmosphere-core (Rust mesh engine)         │
│    ├── CRDT sync, semantic routing          │
│    ├── BLE, Wi-Fi Aware, LAN, WebSocket     │
│    └── Streaming protocol                   │
└─────────────────────────────────────────────┘
```

## Modules

| Module | Description |
|--------|-------------|
| **app** | Main Atmosphere app — UI, service, transport managers |
| **atmosphere-sdk** | Thin SDK for third-party apps to connect via AIDL |
| **atmosphere-client** | Lightweight HTTP client (for apps that prefer REST over AIDL) |
| **horizon-app** | HORIZON integration demo app |

## SDK — Third-Party App Integration

The `atmosphere-sdk` module lets any Android app use the mesh. Add it as a dependency and bind to the running Atmosphere daemon:

```kotlin
// Check if Atmosphere is installed
if (AtmosphereClient.isInstalled(context)) {
    val atmo = AtmosphereClient.connect(context)

    // Route to best capability in mesh
    val result = atmo.route("detect objects in this image", payload)

    // Chat completion (routes to best LLM — local or remote)
    val chat = atmo.chat(listOf(ChatMessage.user("What do you see?")))

    // Binary streaming (video, telemetry, model transfer)
    val stream = atmo.openStream(macPeerId, "video:front")

    // Vision — on-device or mesh-escalated
    val detections = atmo.detectObjects(cameraBitmap)

    // CRDT data sync across mesh
    atmo.crdtInsert("telemetry", JSONObject().put("lat", 35.0).put("alt", 100))
    atmo.crdtSubscribe("telemetry") { docId, kind, json ->
        updateMap(json)
    }

    // Mesh app tools (generic OpenAPI proxy)
    val apps = atmo.getApps()
    val result = atmo.callTool("horizon", "get_mission_summary", mapOf("id" to "m1"))

    // Reactive mesh status
    atmo.meshStatusFlow().collect { status ->
        updateConnectionUI(status.peerCount, status.relayConnected)
    }

    atmo.disconnect()
}
```

### Full SDK API

| Category | Methods |
|----------|---------|
| **Routing** | `route()`, `chat()`, `invoke()` |
| **Capabilities** | `capabilities()`, `capability()`, `registerCapability()`, `unregisterCapability()` |
| **Mesh** | `meshStatus()`, `meshStatusFlow()`, `joinMesh()`, `leaveMesh()` |
| **Streaming** | `openStream()`, `closeStream()`, `listStreams()`, `getStreamPort()` |
| **CRDT** | `crdtInsert()`, `crdtQuery()`, `crdtGet()`, `crdtSubscribe()`, `crdtPeers()`, `crdtInfo()` |
| **Vision** | `detectObjects()`, `captureAndDetect()`, `visionStatus()`, `sendVisionFeedback()` |
| **RAG** | `createRagIndex()`, `queryRag()`, `deleteRagIndex()`, `listRagIndexes()` |
| **Apps** | `getApps()`, `getAppTools()`, `callTool()` |
| **Cost** | `costs()`, `costMetricsFlow()` |
| **Events** | `onCapabilityEvent()`, `onMeshUpdate()`, `onError()` |

### Integration Pattern: Drone App

```
Drone App (Android) ──AIDL──► Atmosphere App ◄──BLE/LAN/Relay──► Mac Daemon
  openStream("video:front")          │                                │
  detectObjects(frame)               │                      ◄──HTTP── Drone Web Dashboard
  crdtInsert("telemetry", ...)       │                        (TypeScript SDK)
```

The phone runs the Atmosphere daemon as a foreground service. The drone app binds to it via the SDK. The daemon handles all mesh routing — BLE to nearby devices, LAN to the Mac, relay for NAT traversal.

## Building

```bash
./gradlew assembleDebug
```

### JNI Shared Library

The cross-compiled `libatmo_jni.so` lives in:

```
app/src/main/jniLibs/arm64-v8a/libatmo_jni.so
```

Build from [atmosphere-core](https://github.com/llama-farm/atmosphere-core):

```bash
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.1.12297006
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android34-clang

cargo build -p atmo-jni --target aarch64-linux-android --release -j4
llvm-strip target/aarch64-linux-android/release/libatmo_jni.so
cp target/aarch64-linux-android/release/libatmo_jni.so \
   ../atmosphere-android/app/src/main/jniLibs/arm64-v8a/
```

## Related Projects

- [atmosphere-core](https://github.com/llama-farm/atmosphere-core) — Rust mesh engine + TypeScript client SDK
- [atmosphere](https://github.com/llama-farm/atmosphere) — Python SDK (legacy)

## License

Apache-2.0
