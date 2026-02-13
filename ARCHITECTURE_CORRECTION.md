# ARCHITECTURE CORRECTION - CRITICAL

## Rob's Corrections (2026-02-13 08:59)

### ❌ WRONG ASSUMPTIONS (What I Built)

1. ❌ **ADB reverse / USB tunneling** - I assumed the phone connects to a Mac daemon via localhost
2. ❌ **"Daemon URL" settings** - I left settings for connecting to external daemons
3. ❌ **Mesh page as settings** - I thought it was for configuring connections
4. ❌ **"Saved Meshes" concept** - I kept the saved mesh repository

### ✅ CORRECT ARCHITECTURE (What It Should Be)

1. ✅ **WiFi mesh discovery** - Phone discovers Mac via UDP broadcast on same WiFi
2. ✅ **No daemon URL** - The app IS Atmosphere, no external connections
3. ✅ **Mesh page = live debugger** - Shows discovered peers, IPs, ports, latency, capabilities
4. ✅ **One mesh, auto-discovery** - App joins mesh_id, discovers peers automatically

---

## How It Actually Works

### Startup Flow

```
User opens app
  ↓
AtmosphereService starts
  ↓
AtmosphereNative.init() + startMesh()
  ↓
Rust core starts UDP broadcast: "ATMO" + peer_info
Rust core listens for UDP broadcasts from other peers
  ↓
Discovers peer on WiFi (e.g., Mac at 192.168.1.100:11460)
  ↓
Rust core connects via TCP, performs HMAC handshake
  ↓
CRDT sync begins (bidirectional diff exchange)
  ↓
UI polls peers() and displays: "robs-mac (192.168.1.100:11460) - 12ms - 47 capabilities"
```

### Discovery Protocol

**UDP Broadcast (port 11452):**
```
4-byte magic: "ATMO"
JSON: {"peer_id":"abc123","app_id":"atmosphere","tcp_port":11460}
```

**TCP Handshake (peer's tcp_port):**
```
→ hello {"peer_id":"abc123","mesh_id":"atmosphere-playground-mesh-v1","hmac":"..."}
← hello_ack {"peer_id":"xyz789","hmac":"..."}
→ sync_diff {"collection":"_capabilities","diffs":[...]}
← sync_diff {"collection":"_status","diffs":[...]}
→ sync_done
← sync_done
```

**No saved meshes. No connection dialogs. No daemon URLs.**

---

## What Needs To Be Deleted

### Files to Delete:
- `app/src/main/kotlin/com/llamafarm/atmosphere/data/SavedMesh.kt`
- `app/src/main/kotlin/com/llamafarm/atmosphere/data/SavedMeshRepository.kt`

### Code to Remove from AtmosphereService.kt:
- `private lateinit var meshRepository: SavedMeshRepository`
- `fun connectToMesh(meshId: String)`
- `fun connectToMesh(mesh: SavedMesh)`
- `fun disconnectMesh()`
- `private fun checkAutoConnect()`
- Any "ACTION_CONNECT_MESH" / "ACTION_DISCONNECT_MESH" handling

### Settings to Remove:
- Any "Daemon URL" input fields
- Any "Saved Meshes" list
- Any "Connect to Mesh" buttons

---

## What The UI Should Show

### HomeScreen - Mesh Status Dashboard
```
┌─────────────────────────────────┐
│ Atmosphere                      │
│                                 │
│ Running                         │
│ Peer: abc123def45678            │
│ Mesh: atmosphere-playground-v1  │
│ Port: 11460                     │
│                                 │
│ Connected Peers: 2              │
│ Capabilities: 47                │
│                                 │
│ Transports:                     │
│  LAN ✓ Active                   │
│  BLE ○ Off                      │
│  WiFi Direct ○ Off              │
│                                 │
│ Device:                         │
│  CPU: 12%  RAM: 2.3GB           │
│  Battery: 87%                   │
└─────────────────────────────────┘
```

### MeshScreen - Live Mesh Debugger
```
┌─────────────────────────────────┐
│ Discovered Peers                │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ robs-mac                    │ │
│ │ 192.168.1.100:11460         │ │
│ │ Latency: 12ms               │ │
│ │ State: Connected, Syncing   │ │
│ │ Transport: LAN              │ │
│ │ Capabilities: 47            │ │
│ │ Last Seen: 2s ago           │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ bigllama-relay              │ │
│ │ relay.bigllama.ai:443       │ │
│ │ Latency: 89ms               │ │
│ │ State: Discovered           │ │
│ │ Transport: WebSocket        │ │
│ │ Capabilities: 0             │ │
│ │ Last Seen: 1m ago           │ │
│ └─────────────────────────────┘ │
│                                 │
│ CRDT Collections:               │
│  _capabilities: 47 docs         │
│  _status: 2 docs                │
│  _requests: 0 docs              │
│  _responses: 3 docs             │
│                                 │
│ Transport Status:               │
│  LAN: Active (port 11460)       │
│  BLE: Disabled                  │
│  WiFi Direct: Disabled          │
│  BigLlama Relay: Connecting     │
└─────────────────────────────────┘
```

### SettingsScreen
```
┌─────────────────────────────────┐
│ Node Configuration              │
│                                 │
│ Node Name: [Pixel 8 Pro      ] │
│ Peer ID: abc123def45678 (copy) │
│                                 │
│ Mesh Configuration              │
│ Mesh ID: [atmosphere-playground]│
│ Auth Mode: HMAC-SHA256          │
│                                 │
│ Transports                      │
│ □ LAN Discovery (WiFi)          │
│ □ BLE Mesh                      │
│ □ WiFi Direct                   │
│ □ BigLlama Relay                │
│                                 │
│ Sub-Pages:                      │
│  › Test Console                 │
│  › RAG Configuration            │
│  › Vision Settings              │
│  › Connected Apps               │
└─────────────────────────────────┘
```

---

## Implementation Plan

### Step 1: Remove Saved Mesh Logic
```kotlin
// DELETE these from AtmosphereService.kt:
// - meshRepository initialization
// - connectToMesh() functions
// - disconnectMesh()
// - checkAutoConnect()
// - ACTION_CONNECT_MESH / ACTION_DISCONNECT_MESH handling

// KEEP only:
// - startNode() → initializes Rust core
// - stopNode() → stops Rust core
// - Periodic polling of peers() for UI
```

### Step 2: Simplify Service Startup
```kotlin
private fun startNode() {
    _state.value = ServiceState.STARTING
    startForeground(NOTIFICATION_ID, createNotification("Starting..."))
    acquireWakeLock()
    
    serviceScope.launch {
        try {
            initializeNode() // Just init Rust core, that's it
            _state.value = ServiceState.RUNNING
            updateNotification("Running • 0 peers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start node", e)
            _state.value = ServiceState.STOPPED
            stopSelf()
        }
    }
}

private suspend fun initializeNode() {
    // 1. Get identity
    val app = applicationContext as AtmosphereApplication
    val nodeId = app.identityManager.loadOrCreateIdentity()
    _nodeId.value = nodeId
    
    // 2. Start Rust core
    startCrdtMesh(nodeId)
    
    // 3. Register capabilities
    registerDefaultCapabilities()
    
    // That's it. No "connect to mesh", no saved meshes.
}
```

### Step 3: Update MeshScreen
```kotlin
@Composable
fun MeshScreen(service: AtmosphereService?) {
    val peers by service?.crdtPeers?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    
    LazyColumn {
        item {
            Text("Discovered Peers", style = MaterialTheme.typography.titleLarge)
        }
        
        if (peers.isEmpty()) {
            item {
                Text("No peers discovered yet. Waiting for UDP broadcasts...")
            }
        }
        
        items(peers) { peer ->
            PeerCard(peer) // Show IP, port, latency, state, transports
        }
        
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("CRDT Collections", style = MaterialTheme.typography.titleLarge)
        }
        
        item {
            CrdtCollectionsBrowser(service)
        }
        
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Transport Status", style = MaterialTheme.typography.titleLarge)
        }
        
        item {
            TransportStatusCard(service)
        }
    }
}

@Composable
fun PeerCard(peer: SimplePeerInfo) {
    Card {
        Column {
            Text(peer.peerId.take(16), fontWeight = FontWeight.Bold)
            Text("State: ${peer.state}")
            Text("Transports: ${peer.transports.joinToString(", ")}")
            // TODO: Show IP, port, latency when available from Rust core
        }
    }
}
```

### Step 4: Remove Settings for Daemon URLs
```kotlin
// DELETE any SettingsScreen sections for:
// - "Daemon URL"
// - "Connect to Mesh"
// - "Saved Meshes"

// KEEP only:
// - Node name
// - Peer ID (read-only)
// - Mesh ID
// - Transport toggles
```

---

## Critical Rules (Reinforced)

1. **No ADB reverse / USB tunneling**
2. **No daemon URL settings**
3. **Mesh page = live debugger** (discovered peers, IPs, ports, capabilities)
4. **No saved meshes** (app joins one mesh, auto-discovers peers)
5. **WiFi discovery only** (UDP broadcast on LAN)
6. **Atmosphere IS the app** (not a client that connects to something)

---

## Testing Flow

1. Mac runs: `atmosphere --http-port 11462` (on WiFi 192.168.1.100)
2. Android app starts (on same WiFi)
3. Rust core on Android broadcasts UDP: "ATMO" + peer info
4. Mac receives broadcast, connects via TCP to Android's port
5. Android receives Mac's broadcast, connects via TCP to Mac's port
6. CRDT sync begins (bidirectional)
7. Android UI shows: "robs-mac (192.168.1.100:11462) - Connected"
8. User taps "Test inference" → routes through mesh → gets response

**No USB. No localhost. No saved meshes. Pure WiFi mesh.**

---

*Correction applied: 2026-02-13 09:00 CST*
