# BLE Transport Initialization Fix

## Problem
The Android app had BLE transport implementation (`BleTransport.kt`) but it was never started or initialized. The Mac couldn't discover Android via BLE because:
- BLE endpoint was never added to saved meshes
- ConnectionTrain only probes transports that have endpoints
- No BLE advertising was occurring

## Root Cause
When meshes are saved via `SavedMesh.fromJoin()`, endpoints are created from the invite's endpoint map (typically relay, local, public). BLE is a local discovery mechanism that should always be available but was never added.

## Solution

### 1. **SavedMesh.kt** - Auto-add BLE Endpoint
Modified `SavedMesh.fromJoin()` to automatically add a BLE endpoint to every saved mesh:
```kotlin
// Always add BLE endpoint for local mesh discovery
if (!endpoints.any { it.type == "ble" }) {
    endpoints.add(Endpoint(
        type = "ble",
        address = "ble://$meshId",
        priority = 80  // High priority (between LAN and local)
    ))
}
```

### 2. **ConnectionTrain.kt** - BLE Transport Support
Added BLE transport initialization and management:
- Added `Context` parameter to constructor (needed for BLE)
- Added `bleTransport: BleTransport?` field
- Created `tryConnectBle()` method to start BLE advertising
- Updated `disconnect()` to stop BLE transport
- BLE transport now starts when ConnectionTrain selects BLE endpoint

Key changes:
```kotlin
class ConnectionTrain(
    private val context: Context,  // NEW: needed for BLE
    private val savedMesh: SavedMesh,
    // ...
) {
    private var bleTransport: BleTransport? = null  // NEW
    
    private suspend fun tryConnectBle(type: String, address: String): Boolean {
        // Extract mesh ID and start BLE advertising
        bleTransport = BleTransport(context, nodeName, capabilities, meshId)
        bleTransport?.start()
        // ...
    }
}
```

### 3. **AtmosphereService.kt** - Pass Context
Updated ConnectionTrain instantiation to pass application context:
```kotlin
connectionTrain = ConnectionTrain(
    context = applicationContext,  // NEW
    savedMesh = mesh,
    // ...
)
```

## How It Works Now

1. When a mesh is joined/saved, a BLE endpoint is automatically added
2. ConnectionTrain probes all endpoints including BLE
3. BLE is prioritized (priority 80, between LAN and relay)
4. When BLE endpoint is selected, BleTransport starts advertising
5. Mac can now discover Android via BLE mesh

## Verification

After installing the updated APK, check logcat:
```bash
adb logcat | grep -i "ble\|bluetooth\|advertis"
```

Expected logs:
- `✅ Added BLE endpoint for mesh <meshId>`
- `🔵 Starting BLE transport for mesh: <meshId>`
- `✅ BLE transport started and advertising`
- BLE peer discovery logs

## Testing

1. Rebuild and install:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Join or reconnect to a mesh
3. Check logs for BLE advertising
4. From Mac, run BLE discovery
5. Verify Mac can see Android device via BLE

## Files Modified
- `app/src/main/kotlin/com/llamafarm/atmosphere/data/SavedMesh.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/network/ConnectionTrain.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/service/AtmosphereService.kt`
