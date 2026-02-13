# CRITICAL CORRECTIONS — READ BEFORE ANY MORE CHANGES

## From Rob (angry):

### 1. NO ADB REVERSE / USB TUNNELING
- **REMOVE** any `adb reverse` commands
- **REMOVE** any "Daemon URL" settings
- **REMOVE** any localhost proxy connections
- The phone discovers the Mac via **UDP broadcast over WiFi** (port 11452)
- The phone syncs with the Mac via **TCP over LAN**
- Zero USB tunneling. The mesh works over the network.

### 2. NO "DAEMON" LANGUAGE
- The app IS Atmosphere. It's not "connecting to a daemon."
- Remove ALL references to "daemon" in the UI
- "Atmosphere is running" not "Connected to Daemon"

### 3. NO "SAVED MESHES"
- The app joins ONE mesh (configured by mesh_id)
- Peers are discovered automatically via UDP broadcast
- There is nothing to "save" — discovery is automatic

### 4. MESH PAGE PURPOSE
- Shows **live mesh state**: discovered peers with IPs, ports, latency, capabilities
- CRDT sync status (collections, doc counts, last sync time)
- Transport status (LAN: active, BLE: off, WiFi Direct: off)
- It's a **mesh debugger/dashboard**, not a settings page

### 5. THE APP IS THE MESH NODE
- The Rust core runs inside the app
- It broadcasts its presence, discovers peers, syncs CRDTs
- Other apps on the phone connect to IT via AIDL
- The Mac's Atmosphere binary is just another peer on the mesh

### 6. TEST ALL TRANSPORTS — THE FULL RAINBOW
Not just LAN. Test everything:
- **LAN TCP** — UDP broadcast discovery + TCP sync (baseline)
- **BLE** — Bluetooth Low Energy. Use Android's native BLE APIs. Rust crate: `atmo-transport/src/ble.rs`
- **WiFi Direct** — P2P WiFi without a router. Use `WifiP2pManager`. Rust crate: `atmo-transport/src/p2p_wifi.rs`
- **WebSocket (BigLlama)** — Cloud relay. Rust crate: `atmo-transport/src/websocket.rs`

MeshScreen must show transport status for ALL:
```
LAN:         ✅ Active (3 peers)
BLE:         ✅ Scanning (1 peer nearby)
WiFi Direct: ⚪ Off
BigLlama:    ⚪ Not configured
```

Each transport toggleable in Settings. Multiplexer picks the fastest automatically (LAN > WiFi Direct > BLE > WebSocket).

### 7. DITTO-STYLE PEER ONBOARDING
Make adding peers DEAD SIMPLE:

1. **Automatic discovery** — Peers on same WiFi just find each other (UDP broadcast)
2. **QR Code joining** — Scan QR to join mesh. QR contains: `{"mesh_id":"...","shared_secret":"...","relay_url":"wss://..."}`
3. **Share/Invite** — Generate QR code or invite link to let others join YOUR mesh
4. **Manual peer add** — Type IP:port for direct connection (debugging)

UI exposure:
- HomeScreen: "Invite to Mesh" button (generates QR)
- HomeScreen: "Join Mesh" button (scans QR)
- MeshScreen: Peer list with "Add Peer Manually"
- Settings: Transport toggles, mesh config

Match Ditto's onboarding — one tap, scan QR, done.
