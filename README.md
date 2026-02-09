# 🌐 Atmosphere Android

**Mesh-native AI on Android.** Semantic routing, gossip protocol, on-device inference, and multi-transport networking.

Atmosphere connects devices into an intelligent mesh where AI capabilities are discovered, shared, and routed automatically. No cloud required.

## ✨ What It Does

### 🧠 On-Device AI Inference
- **ONNX Runtime** — YOLOv8 object detection running locally at 40-60ms per frame
- **llama.cpp** — GGUF language model inference on-device (via bundled AAR)
- **Google ML Kit** — Barcode scanning, extensible to face/text detection
- **Model management** — Download, install, and hot-swap models from mesh peers

### 👁️ Real-Time Vision
- **Live camera detection** — CameraX + ONNX pipeline with bounding boxes, confidence scores, FPS counter
- **80 COCO classes** — Person, car, dog, chair, laptop, etc. at 320×320 input
- **Confidence-based escalation** — Low-confidence detections automatically route to more powerful models on mesh peers
- **Color-coded overlays** — Green (>70%), Yellow (>40%), Red (<40%)

### 🔀 Semantic Routing
- **Hash-first cascade** — 64-bit SimHash → keyword overlap → fuzzy matching
- **Gossip protocol** — Capabilities propagate across the mesh via periodic announcements
- **Gradient table** — All known capabilities ranked by semantic match, latency, hops, cost
- **Best-effort routing** — Always finds the best available capability, even with partial matches

### 📡 Multi-Transport Mesh
- **LAN Discovery** — mDNS/NSD auto-discovery of peers on local network
- **BLE Mesh** — Bluetooth Low Energy for proximity-based communication
- **WebSocket Relay** — Internet-connected relay for WAN mesh connectivity
- **Automatic failover** — Seamlessly switches between transports

### 🤝 Gossip Protocol
- **Capability announcements** — Devices broadcast what they can do (models, tools, sensors)
- **Gradient tables** — Distributed knowledge of mesh topology and capabilities
- **TTL-based expiry** — Stale capabilities automatically pruned
- **Hop counting** — Route cost increases with distance

### 🔌 SDK for Third-Party Apps
- **AIDL Service** — Any Android app can bind to Atmosphere for mesh AI
- **AtmosphereClient** — Simple SDK: `connect()`, `chat()`, `detectObjects()`, `meshStatus()`
- **Two demo apps included:**
  - **Atmosphere Chat** — Material3 chat UI with model selector and routing metadata
  - **Atmosphere Photo** — Camera capture with bounding box overlay and detection history

## 📱 Three Apps

| App | Package | Description |
|-----|---------|-------------|
| **Atmosphere** | `com.llamafarm.atmosphere` | Main mesh service + Vision + Chat + Dashboard |
| **Atmosphere Chat** | `com.llamafarm.atmosphere.client` | Lightweight chat demo using SDK |
| **Atmosphere Photo** | `com.llamafarm.atmosphere.photo` | Vision demo with camera + detection |

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                    ATMOSPHERE APP                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │  Vision   │ │   Chat   │ │   Mesh   │ │  Home   │ │
│  │  Screen   │ │  Screen  │ │  Screen  │ │ Screen  │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬────┘ │
│       │             │            │             │       │
│  ┌────┴─────────────┴────────────┴─────────────┴────┐ │
│  │              ATMOSPHERE SERVICE                    │ │
│  │  ┌──────────┐ ┌───────────┐ ┌──────────────────┐ │ │
│  │  │  Vision  │ │ Semantic  │ │     Gossip       │ │ │
│  │  │Capability│ │  Router   │ │    Manager       │ │ │
│  │  │ (ONNX)   │ │(SimHash)  │ │(Gradient Table)  │ │ │
│  │  └──────────┘ └───────────┘ └──────────────────┘ │ │
│  │  ┌──────────┐ ┌───────────┐ ┌──────────────────┐ │ │
│  │  │ LlamaCpp │ │   Model   │ │    Transport     │ │ │
│  │  │  Engine  │ │  Manager  │ │  (LAN/BLE/Relay) │ │ │
│  │  └──────────┘ └───────────┘ └──────────────────┘ │ │
│  └──────────────────────────────────────────────────┘ │
│       │              AIDL                              │
├───────┴────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐                     │
│  │ Chat Client │  │ Photo Client│  Third-party apps   │
│  │   (SDK)     │  │   (SDK)     │                     │
│  └─────────────┘  └─────────────┘                     │
└────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites
- Android Studio Hedgehog+
- Android SDK 33+ (Pixel recommended)
- A mesh peer running [Atmosphere](https://github.com/llama-farm/atmosphere) (optional)

### Build

```bash
git clone https://github.com/llama-farm/atmosphere-android.git
cd atmosphere-android

# Build all three apps
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r atmosphere-client/build/outputs/apk/debug/atmosphere-client-debug.apk
adb install -r atmosphere-photo/build/outputs/apk/debug/atmosphere-photo-debug.apk
```

### Connect to Mesh

1. Open Atmosphere app
2. Go to **Mesh** tab → **Join Mesh**
3. Enter your relay endpoint or scan QR code
4. Peers auto-discover via LAN/BLE

### Test Vision

1. Go to **Vision** tab
2. Tap ▶️ **Start**
3. Point camera at objects — see live bounding boxes
4. Low-confidence detections escalate to mesh peers automatically

## 🧬 Mesh Protocol

Atmosphere uses a gossip-based protocol for capability discovery:

```
Phone                          Mac (LlamaFarm)
  │                                │
  ├─── ANNOUNCE (capabilities) ───→│
  │    camera, gps, onnx:yolov8    │
  │                                │
  │←── ANNOUNCE (capabilities) ────┤
  │    llm:qwen3, llm:travel,     │
  │    rag, vision:clip            │
  │                                │
  ├─── QUERY (chat message) ──────→│
  │    "What do llamas eat?"       │
  │                                │
  │←── ROUTE (semantic match) ─────┤
  │    → llm:travel-guide (0.85)   │
  │                                │
  ├─── DETECT (vision trigger) ───→│
  │    cow 87%, image, gps         │
  │                                │
  │←── ESCALATE (better model) ────┤
  │    cow 95% + breed: Highland   │
```

## 📦 Project Structure

```
atmosphere-android/
├── app/                          # Main Atmosphere app
│   └── src/main/kotlin/
│       ├── core/                 # GossipManager, CapabilityAnnouncement
│       ├── inference/            # LlamaCppEngine, ModelManager
│       ├── mesh/                 # ModelCatalog, ModelTransferService
│       ├── router/               # SemanticRouter, HashMatcher, SimHash
│       ├── service/              # AtmosphereBinderService (AIDL)
│       ├── transport/            # LAN, BLE, Relay transports
│       ├── ui/screens/           # Home, Vision, Mesh, Chat, Settings
│       ├── viewmodel/            # ViewModels for each screen
│       └── vision/               # VisionCapability, VisionModelManager
├── atmosphere-sdk/               # SDK for third-party apps
│   └── AtmosphereClient.kt      # connect(), chat(), detectObjects()
├── atmosphere-client/            # Demo chat app
├── atmosphere-photo/             # Demo vision app
└── llama.cpp/                    # Submodule for on-device LLM
```

## 🔗 Related Projects

| Project | Description |
|---------|-------------|
| [Atmosphere](https://github.com/llama-farm/atmosphere) | Python mesh server + WebUI (Mac/Linux) |
| [LlamaFarm](https://github.com/llama-farm/llamafarm) | Local AI infrastructure and universal runtime |
| [OpenHoof](https://github.com/llama-farm/openhoof) | Event-driven agent framework for mesh triggers |

## 📄 License

MIT
