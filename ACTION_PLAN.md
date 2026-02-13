# ACTION PLAN: Remove SavedMesh & Simplify Architecture

## Rob's Requirements (2026-02-13 08:59)

### What's WRONG:
1. ❌ SavedMesh / SavedMeshRepository - No saved meshes, app joins ONE mesh
2. ❌ "Connect to mesh" dialogs - No connection dialogs, auto-discovery only
3. ❌ Daemon URL settings - No daemon, app IS Atmosphere
4. ❌ ADB reverse / USB tunneling - WiFi mesh discovery only

### What's CORRECT:
1. ✅ App starts → Rust core starts → UDP broadcast begins → peers discovered → TCP connections established
2. ✅ One mesh ID (configured in settings): `"atmosphere-playground-mesh-v1"`
3. ✅ MeshScreen shows LIVE peer state (IPs, ports, latency, capabilities)
4. ✅ No "saved meshes", no connection management, pure auto-discovery

---

## Files That Need Major Changes

### 1. **AtmosphereViewModel.kt** (30+ SavedMesh references)

**Remove:**
- `SavedMesh` and `SavedMeshRepository` imports
- `meshRepository` property
- `_savedMeshes` / `savedMeshes` flows
- `_hasSavedMesh` / `hasSavedMesh` flows
- `initializeSavedMeshes()`
- `refreshSavedMeshes()`
- `reconnectToMeshLegacy()`
- `reloadSavedMeshes()`
- `forgetSavedMesh()`
- `connectToSavedMesh()`
- `reconnectToMesh()`
- Any logic checking `hasSavedMesh`

**Keep:**
- Peer info display (but use `SimplePeerInfo` not old `PeerInfo`)
- Capability display
- Inference routing
- Device metrics

### 2. **MeshManagement.kt** (6 errors)

**Remove:**
- All `getAtmosphereCore()` calls
- References to `meshId` and `sharedSecret` properties (these don't exist on handle)

**Replace with:**
- `getAtmosphereHandle()` to get JNI handle
- Query mesh info via JNI if needed, OR hardcode mesh ID for now

**Example:**
```kotlin
fun getMeshInfo(): MeshInfo {
    val handle = service.getAtmosphereHandle()
    if (handle == 0L) return MeshInfo.default()
    
    // For now, hardcode mesh ID (later query from Rust if needed)
    return MeshInfo(
        meshId = "atmosphere-playground-mesh-v1",
        appId = "atmosphere",
        sharedSecret = "..." // Or query via JNI
    )
}
```

### 3. **ServiceConnection.kt** (1 error)

**Fix return type:**
```kotlin
// OLD:
fun getCrdtPeers(): StateFlow<List<PeerInfo>>?

// NEW:
fun getCrdtPeers(): StateFlow<List<SimplePeerInfo>>?
```

### 4. **InferenceScreen.kt** (5 errors)

**Remove:**
- Direct CRDT operations: `getAtmosphereCore()`, `.insert()`, `.syncNow()`, `.query()`

**Replace with:**
- Service methods that call JNI internally

**Example:**
```kotlin
// OLD:
val core = service.getAtmosphereCore()
core?.insert("_test", testDoc)
core?.syncNow()
val docs = core?.query("_test")

// NEW:
service.insertCrdtDocument("_test", docId, testDoc)
val docs = service.queryCrdtCollection("_test")
```

Add helper methods to AtmosphereService:
```kotlin
fun insertCrdtDocument(collection: String, docId: String, data: Map<String, Any>) {
    if (atmosphereHandle == 0L) return
    val json = JSONObject(data).toString()
    AtmosphereNative.insert(atmosphereHandle, collection, docId, json)
}

fun queryCrdtCollection(collection: String): List<Map<String, Any>> {
    if (atmosphereHandle == 0L) return emptyList()
    val json = AtmosphereNative.query(atmosphereHandle, collection)
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        obj.keys().asSequence().associateWith { obj.get(it) }
    }
}
```

### 5. **UI Screens That Reference SavedMesh**

Search for screens that show:
- "Saved Meshes" list
- "Connect to Mesh" buttons
- "Daemon URL" input fields

**Remove these UI elements entirely.**

**Replace with:**
- MeshScreen: Live peer discovery display
- SettingsScreen: Mesh ID input (single field, no saved meshes)

---

## Step-by-Step Implementation

### Step 1: Delete SavedMesh Files
```bash
rm app/src/main/kotlin/com/llamafarm/atmosphere/data/SavedMesh.kt
rm app/src/main/kotlin/com/llamafarm/atmosphere/data/SavedMeshRepository.kt
```

### Step 2: Clean Up AtmosphereService.kt
- ✅ Already removed `meshRepository`, `connectToMesh`, `disconnectMesh`
- ✅ Already simplified LAN discovery
- Add helper methods: `insertCrdtDocument()`, `queryCrdtCollection()`

### Step 3: Clean Up AtmosphereViewModel.kt
- Remove all SavedMesh references (30+ lines)
- Remove `initializeSavedMeshes()`, `refreshSavedMeshes()`, etc.
- Keep only peer display and capability logic

### Step 4: Fix Compilation Errors
- MeshManagement.kt: Replace `getAtmosphereCore()` with `getAtmosphereHandle()`
- ServiceConnection.kt: Change return type to `SimplePeerInfo`
- InferenceScreen.kt: Use service helper methods instead of direct CRDT calls
- AtmosphereViewModel.kt: Use `SimplePeerInfo` instead of old `PeerInfo`

### Step 5: Update UI
- Remove any "Saved Meshes" screens/dialogs
- Remove "Connect to Mesh" buttons
- Remove "Daemon URL" settings
- Update MeshScreen to show live peer list (IPs, ports, state, transports)
- Update SettingsScreen to have single "Mesh ID" field

### Step 6: Test
```bash
./gradlew assembleDebug
adb -s 4B041FDAP0033Q install -r app/build/outputs/apk/debug/app-debug.apk
```

**On Mac:**
```bash
atmosphere --http-port 11462
```

**On Android:**
- Open app
- Should auto-discover Mac peer via UDP
- Mesh screen should show: "robs-mac (192.168.1.100:11462) - Connected"

---

## Simplified Architecture

```
┌─────────────────────────────────────────┐
│           Android App Startup           │
└────────────────┬────────────────────────┘
                 ▼
┌─────────────────────────────────────────┐
│  AtmosphereService.startNode()          │
│  ├─ AtmosphereNative.init()             │
│  └─ AtmosphereNative.startMesh()        │
└────────────────┬────────────────────────┘
                 ▼
┌─────────────────────────────────────────┐
│  Rust Core (libatmo_jni.so)            │
│  ├─ UDP broadcast: "ATMO" + peer_info   │
│  ├─ Listen for UDP from other peers     │
│  └─ TCP connect to discovered peers     │
└────────────────┬────────────────────────┘
                 ▼
┌─────────────────────────────────────────┐
│  Mac discovers Android peer              │
│  ├─ Receives UDP: Android peer info      │
│  ├─ Connects via TCP to Android port     │
│  └─ CRDT sync begins                     │
└──────────────────────────────────────────┘
                 ▼
┌─────────────────────────────────────────┐
│  UI polls AtmosphereNative.peers()      │
│  ├─ Displays: "robs-mac (192.168.1.100)"│
│  ├─ Shows: latency, state, transports    │
│  └─ User can route inference through Mac │
└──────────────────────────────────────────┘
```

**No saved meshes. No connection dialogs. No daemon URLs. Pure auto-discovery.**

---

## Critical Rules (Final Reminder)

1. **No ADB reverse / USB tunneling** - WiFi mesh only
2. **No daemon URL settings** - App IS Atmosphere
3. **Mesh page = live debugger** - Show discovered peers with IPs/ports
4. **No saved meshes** - Join one mesh, auto-discover peers
5. **Atmosphere IS the app** - Not a client

---

*Action plan created: 2026-02-13 09:05 CST*
