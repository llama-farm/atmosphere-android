# Atmosphere Android - Rust Core Migration

## Goal
Replace the Kotlin `AtmosphereCore.kt` with the Rust core via JNI bindings.  
**Atmosphere IS the app. The phone is a full mesh peer.**

## Progress

### ✅ Phase 1: JNI Bridge (IN PROGRESS)

**Status:** Compiling release build for ARM64-v8a

#### Completed:
- ✅ Installed `cargo-ndk` for cross-compilation
- ✅ Located Android NDK v27.1.12297006
- ✅ Created `crates/atmo-jni/` with complete JNI implementation
  - `Cargo.toml` - All dependencies configured
  - `src/lib.rs` - JNI entry points (9 functions)
  - `src/atmosphere.rs` - Rust core wrapper (AtmosphereHandle)
- ✅ Added `atmo-jni` to workspace
- ✅ Created `AtmosphereNative.kt` - Kotlin JNI wrapper
- ✅ Fixed all compilation errors

#### JNI Functions:
```kotlin
// In AtmosphereNative.kt
external fun init(appId: String, peerId: String, name: String): Long
external fun startMesh(handle: Long): Int
external fun stop(handle: Long)
external fun insert(handle: Long, collection: String, docId: String, dataJson: String)
external fun query(handle: Long, collection: String): String
external fun get(handle: Long, collection: String, docId: String): String
external fun peers(handle: Long): String
external fun capabilities(handle: Long): String
external fun health(handle: Long): String
```

#### Currently:
🔄 **Building `libatmo_jni.so` for ARM64**
- Release build takes ~10-15 minutes
- Output: `app/src/main/jniLibs/arm64-v8a/libatmo_jni.so`

---

### ⏳ Phase 2: Replace Kotlin AtmosphereCore (NOT STARTED)

**Files to update:**

#### 1. `AtmosphereService.kt`
Replace `AtmosphereCore` with `AtmosphereNative`:

```kotlin
// OLD:
private var atmosphereCore: AtmosphereCore? = null

// NEW:
private var atmosphereHandle: Long = 0

// In onCreate():
atmosphereHandle = AtmosphereNative.init(
    appId = "atmosphere",
    peerId = preferences.peerId,
    deviceName = Build.MODEL
)
val meshPort = AtmosphereNative.startMesh(atmosphereHandle)

// In onDestroy():
AtmosphereNative.stop(atmosphereHandle)
```

#### 2. `AtmosphereBinderService.kt`
Wire AIDL methods to JNI:

```kotlin
// Chat completion
override fun chatCompletion(...): String {
    // Route via Rust core
    val result = atmosphere.route(query)
    
    // Insert request into CRDT
    AtmosphereNative.insert(
        handle,
        "_requests",
        requestId,
        requestJson
    )
    
    return result
}

// Capabilities
override fun getCapabilities(): List<Capability> {
    val json = AtmosphereNative.capabilities(handle)
    return parseCapabilities(json)
}

// CRDT operations
override fun crdtInsert(...) {
    AtmosphereNative.insert(handle, collection, docId, dataJson)
}

override fun crdtQuery(...): String {
    return AtmosphereNative.query(handle, collection)
}
```

#### 3. **DELETE** `AtmosphereCore.kt`
No more Kotlin CRDT, TCP, or UDP discovery. All mesh logic is in Rust.

---

### ⏳ Phase 3: Rebuild the UI (NOT STARTED)

Transform the app into an **Atmosphere mesh dashboard**.

#### HomeScreen → Mesh Status Dashboard
```kotlin
@Composable
fun HomeScreen() {
    val health = remember { AtmosphereNative.health(handle) }
    
    Column {
        Text("Atmosphere is running")
        Text("Peer ID: ${health.peer_id}")
        Text("Mesh Port: ${health.mesh_port}")
        Text("Connected Peers: ${health.peer_count}")
        
        // Network indicators
        Row {
            TransportBadge("LAN", active = true)
            TransportBadge("BLE", active = false)
            TransportBadge("WiFi Direct", active = false)
        }
        
        // Device metrics
        DeviceMetrics(cpu = "12%", ram = "2.3 GB", battery = "87%")
    }
}
```

#### InferenceScreen → Add routing info
```kotlin
// Show where the inference was routed
Text("Routed to robs-mac via LAN (score: 0.85, 252µs)")
```

#### MeshScreen → Topology visualizer
```kotlin
@Composable
fun MeshScreen() {
    val peers = remember { AtmosphereNative.peers(handle) }
    val capabilities = remember { AtmosphereNative.capabilities(handle) }
    
    LazyColumn {
        item { Text("Connected Peers") }
        items(peers) { peer ->
            PeerCard(peer)
        }
        
        item { Text("Gradient Table") }
        items(capabilities) { cap ->
            CapabilityRow(cap)
        }
        
        item { MeshTopologyGraph(peers) }
    }
}
```

#### SettingsScreen → Mesh config
```kotlin
- Node name
- Peer ID (read-only)
- Mesh ID
- Auth mode
- Transport toggles (LAN, BLE, WiFi Direct, BigLlama relay)
- Sub-nav: Test Console, RAG, Vision, Connected Apps
```

---

### ⏳ Phase 4: Build & Deploy (NOT STARTED)

```bash
# 1. Cross-compile Rust (already running)
cd /Users/robthelen/clawd/projects/atmosphere-core
cargo ndk -t arm64-v8a -o ../atmosphere-android/app/src/main/jniLibs build --release -p atmo-jni

# 2. Build APK
cd /Users/robthelen/clawd/projects/atmosphere-android
./gradlew assembleDebug

# 3. Install on device
adb -s 4B041FDAP0033Q install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Test mesh sync with Mac
# On Mac: atmosphere --http-port 11462
# On Android: Open app, should auto-discover via LAN
```

---

## Wire Protocol Compatibility

The Rust core MUST be compatible with the Mac `atmosphere` binary:

- **UDP Discovery:** 4-byte "ATMO" magic + JSON
  ```json
  {"peer_id":"abc123","app_id":"atmosphere","tcp_port":11460}
  ```

- **TCP Sync:** 4-byte big-endian length prefix + JSON frames
  - Handshake: `hello` → `hello_ack`
  - Sync: `sync_diff` → `sync_done`

- **Auth:** HMAC-SHA256 with shared secret
  - Secret: `SHA256("atmosphere-playground-v1")`
  - Mesh ID: `"atmosphere-playground-mesh-v1"`

---

## Critical Rules

- ❌ NO "daemon" language in the UI
- ✅ Atmosphere IS the app
- ✅ Kotlin only handles UI and Android lifecycle
- ✅ Rust core handles ALL mesh operations
- ✅ Keep the 4-tab bottom nav
- ✅ Build MUST pass
- ✅ Test on device after deploying

---

## Next Steps (Once Compilation Finishes)

1. Verify `libatmo_jni.so` exists in `app/src/main/jniLibs/arm64-v8a/`
2. Update `AtmosphereService.kt` to use `AtmosphereNative`
3. Delete `AtmosphereCore.kt`
4. Build APK: `./gradlew assembleDebug`
5. Deploy to device
6. Test mesh sync with Mac

---

## Device Info
- Device: `adb -s 4B041FDAP0033Q`
- Package: `com.llamafarm.atmosphere.debug`
