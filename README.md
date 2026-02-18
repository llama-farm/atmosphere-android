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
┌─────────────────┐
│  Android UI      │  Jetpack Compose
├─────────────────┤
│  Atmosphere SDK  │  Kotlin SDK layer
├─────────────────┤
│  atmo-jni        │  JNI bridge (Rust → Kotlin)
├─────────────────┤
│  atmosphere-core │  Rust mesh engine
└─────────────────┘
```

## Building

```bash
./gradlew assembleDebug
```

### JNI Shared Library

The cross-compiled `libatmo_jni.so` files live in:

```
app/libs/arm64-v8a/libatmo_jni.so
app/libs/armeabi-v7a/libatmo_jni.so
```

Build them from [atmosphere-core](https://github.com/llama-farm/atmosphere-core):

```bash
cargo build --target aarch64-linux-android --release -p atmo-jni
cargo build --target armv7-linux-androideabi --release -p atmo-jni
```

## Documentation

See [docs/](docs/) for additional design and architecture documents.

## License

Apache-2.0
