# Android UI Refactor Plan

## Phase 1: ViewModel - JNI State Management ✅ NEXT

### Current Problem
- ViewModel polls HTTP endpoint (localhost:11462) via `MeshRepository`
- Shows "daemon" language everywhere
- Manages "Saved Meshes" concept
- Disconnect between what Rust core is doing (via JNI) and what UI shows (via HTTP)

### Solution
Replace HTTP polling with direct JNI polling every 3 seconds:

```kotlin
// New data classes for JNI responses
data class JniPeer(
    val peerId: String,
    val name: String,
    val ip: String?,
    val transport: String,  // "lan", "ble", "wifi_direct", "biglama"
    val latency: Int?
)

data class JniCapability(
    val name: String,
    val model: String?,
    val projectPath: String?,
    val peerId: String
)

data class JniHealth(
    val status: String,  // "running", "starting", "stopped"
    val peerId: String,
    val nodeName: String,
    val meshPort: Int,
    val peerCount: Int,
    val capabilityCount: Int
)

// In ViewModel init:
private fun startJniPolling() {
    viewModelScope.launch {
        var handle = 0L
        while (true) {
            try {
                // Initialize if needed
                if (handle == 0L) {
                    val peerId = preferences.getOrCreateNodeId()
                    val nodeName = preferences.nodeName.first() ?: "Android Device"
                    handle = AtmosphereNative.init("atmosphere", peerId, nodeName)
                    if (handle != 0L) {
                        AtmosphereNative.startMesh(handle)
                    }
                }
                
                if (handle != 0L) {
                    // Poll health
                    val healthJson = AtmosphereNative.health(handle)
                    val health = parseHealth(healthJson)
                    _jniHealth.value = health
                    
                    // Poll peers
                    val peersJson = AtmosphereNative.peers(handle)
                    val peers = parsePeers(peersJson)
                    _jniPeers.value = peers.filter { it.peerId != health.peerId } // Filter self
                    
                    // Poll capabilities
                    val capsJson = AtmosphereNative.capabilities(handle)
                    val caps = parseCapabilities(capsJson)
                    _jniCapabilities.value = caps
                }
            } catch (e: Exception) {
                Log.e(TAG, "JNI polling error", e)
            }
            
            delay(3000)
        }
    }
}
```

### Files to modify:
1. `AtmosphereViewModel.kt` - Add JNI polling, remove HTTP polling
2. `MeshRepository.kt` - Keep file but don't use for primary state (mark deprecated)

---

## Phase 2: HomeScreen ✅

### Changes:
1. **Remove**: "Connected to Daemon", "Daemon URL"
2. **Show**: "Atmosphere Running" with green dot (from JNI health status)
3. **Display**:
   - This device: peer ID (truncated), node name, mesh port
   - Mesh stats: X peers connected, Y capabilities available
   - Transport indicators: LAN ✅ | BLE ⚪ | WiFi Direct ⚪ | BigLlama ⚪
4. **Data source**: `jniHealth`, `jniPeers`, `jniCapabilities` from ViewModel

### Files to modify:
1. `HomeScreen.kt` - Complete UI rewrite based on JNI state

---

## Phase 3: MeshScreen ✅

### Changes:
1. **Remove**: Entire "Saved Meshes" section
2. **Remove**: "Relay Peers" vs "Native Peers" distinction
3. **Show**:
   - Connected Peers section:
     - Each peer: name, peer ID (truncated), IP, transport type, latency
     - Filter out self
     - If 0 peers: "Searching for peers on WiFi..." with animation
   - Capabilities section:
     - Each capability: name, model, project_path, peer it lives on
     - Grouped by peer
4. **Add**:
   - "Add Peer Manually" button (IP:port entry)
   - "Invite to Mesh" button (generate QR code)

### Files to modify:
1. `MeshScreen.kt` - Complete UI rewrite

---

## Phase 4: InferenceScreen ✅

### Changes:
1. **Remove**: `useDaemon` / `useMeshRouting` toggle
2. **Show**: Routing info when sending message: "→ robs-mac via LAN (score: 0.85)"
3. **Use**: `AtmosphereNative.capabilities(handle)` for model picker

### Files to modify:
1. `InferenceScreen.kt` - Remove toggle, add routing display

---

## Phase 5: SettingsScreen ✅

### Changes:
1. **Remove**: "Daemon URL" setting
2. **Remove**: Any "adb reverse" instructions/help text
3. **Keep**:
   - Node Config: name, peer ID (read-only), mesh ID
   - Transport toggles: LAN, BLE, WiFi Direct, BigLlama URL
   - Sub-nav to Test Console, RAG, Vision

### Files to modify:
1. `SettingsScreen.kt` - Remove daemon-related settings

---

## Build & Deploy Commands

```bash
cd /Users/robthelen/clawd/projects/atmosphere-android
./gradlew assembleDebug
adb -s 4B041FDAP0033Q install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 4B041FDAP0033Q shell am force-stop com.llamafarm.atmosphere.debug
adb -s 4B041FDAP0033Q shell am start -n com.llamafarm.atmosphere.debug/com.llamafarm.atmosphere.MainActivity
```

## Verification Checklist

- [ ] App launches without crash
- [ ] HomeScreen shows "Atmosphere Running" (not "Connected to Daemon")
- [ ] MeshScreen shows real peers from JNI (or "Searching..." if none)
- [ ] No "daemon" text anywhere in UI
- [ ] `adb logcat | grep -i "atmos"` shows Rust core initializing
- [ ] Peers list does not include self
- [ ] Capabilities are visible when peers are present
