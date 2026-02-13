# Transport Implementation Plan - Full Rainbow

## Rob's Requirement (2026-02-13 09:04)

Test ALL transport methods simultaneously:

1. **LAN TCP** - UDP broadcast (11452) + TCP sync (baseline)
2. **BLE** - Bluetooth Low Energy discovery + data transfer
3. **WiFi Direct** - P2P WiFi without router (WifiP2pManager)
4. **WebSocket** - BigLlama cloud relay

**Preference order:** LAN > WiFi Direct > BLE > WebSocket (multiplexer handles this)

---

## Current Status in Rust Core

### ✅ Already Implemented in `atmo-transport/`

All transports exist and are feature-complete:

- **`lan.rs`** - UDP broadcast discovery on 11452, TCP sync
- **`ble.rs`** - BLE peripheral/central with GATT characteristics (behind `ble` feature flag)
- **`p2p_wifi.rs`** - mDNS-based P2P WiFi discovery + TCP
- **`websocket.rs`** - WebSocket client for cloud relay
- **`multiplexer.rs`** - Manages all transports, prefers fastest

### ✅ Configuration Already Exists

```rust
pub struct TransportConfig {
    // LAN
    pub lan_enabled: bool,
    pub lan_tcp_port: u16,
    
    // TCP (base layer)
    pub tcp_enabled: bool,
    pub tcp_listen_port: u16,
    
    // WebSocket (BigLlama)
    pub websocket_enabled: bool,
    pub relay_url: Option<String>,
    
    // BLE
    pub ble_enabled: bool,
    pub ble_service_uuid: String,
    pub ble_max_connections: usize,
    
    // P2P WiFi
    pub p2p_wifi_enabled: bool,
    pub p2p_wifi_service_type: String,
    pub p2p_wifi_tcp_port: u16,
}
```

---

## Android Integration Plan

### Phase 1: Update JNI Bridge (Rust)

**Add to `crates/atmo-jni/src/lib.rs`:**

```rust
/// Configure transports
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_core_AtmosphereNative_configureTransports(
    mut env: JNIEnv,
    _class: JClass,
    handle_id: jlong,
    config_json: JString,
) {
    let handles = HANDLES.lock().unwrap();
    let handle = match handles.get(&handle_id) {
        Some(h) => h.clone(),
        None => {
            error!("Invalid handle ID: {}", handle_id);
            return;
        }
    };
    drop(handles);

    let config_str: String = env.get_string(&config_json).unwrap().into();
    
    RUNTIME.block_on(handle.configure_transports(&config_str));
}

/// Get transport status
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_core_AtmosphereNative_transportStatus(
    mut env: JNIEnv,
    _class: JClass,
    handle_id: jlong,
) -> jstring {
    let handles = HANDLES.lock().unwrap();
    let handle = match handles.get(&handle_id) {
        Some(h) => h.clone(),
        None => {
            error!("Invalid handle ID: {}", handle_id);
            return env.new_string("{}").unwrap().into_raw();
        }
    };
    drop(handles);

    match RUNTIME.block_on(handle.transport_status()) {
        Ok(json) => env.new_string(json).unwrap().into_raw(),
        Err(e) => {
            error!("Failed to get transport status: {}", e);
            env.new_string("{}").unwrap().into_raw()
        }
    }
}
```

**Add to `crates/atmo-jni/src/atmosphere.rs`:**

```rust
impl AtmosphereHandle {
    /// Configure transports
    pub async fn configure_transports(&self, config_json: &str) -> Result<(), Box<dyn std::error::Error>> {
        let mesh_lock = self.mesh.read().await;
        let mesh = mesh_lock.as_ref().ok_or("Mesh not started")?;
        
        // Parse config JSON
        let config: serde_json::Value = serde_json::from_str(config_json)?;
        
        // Update transport config (this requires extending MeshManager API)
        // For now, log the config change
        info!("Transport config update requested: {}", config_json);
        
        Ok(())
    }
    
    /// Get transport status
    pub async fn transport_status(&self) -> Result<String, Box<dyn std::error::Error>> {
        let mesh_lock = self.mesh.read().await;
        let mesh = mesh_lock.as_ref().ok_or("Mesh not started")?;
        
        // Get stats from multiplexer
        let stats = mesh.transport_stats().await;
        
        let status = json!({
            "transports": stats.iter().map(|(name, stat)| {
                json!({
                    "name": name,
                    "active": stat.active,
                    "peers": stat.connected_peers,
                    "bytes_sent": stat.bytes_sent,
                    "bytes_received": stat.bytes_received,
                })
            }).collect::<Vec<_>>(),
        });
        
        Ok(serde_json::to_string(&status)?)
    }
}
```

---

### Phase 2: Update Kotlin JNI Wrapper

**Add to `AtmosphereNative.kt`:**

```kotlin
object AtmosphereNative {
    // ... existing functions ...
    
    /**
     * Configure transports (enable/disable BLE, WiFi Direct, etc.)
     * 
     * @param handle Handle from init()
     * @param configJson Transport config as JSON string
     */
    external fun configureTransports(handle: Long, configJson: String)
    
    /**
     * Get transport status (which transports are active, peer counts, etc.)
     * 
     * @param handle Handle from init()
     * @return JSON string with transport status
     */
    external fun transportStatus(handle: Long): String
}
```

---

### Phase 3: Update AtmosphereService

**Add transport configuration:**

```kotlin
class AtmosphereService : Service() {
    
    data class TransportConfig(
        val lanEnabled: Boolean = true,
        val bleEnabled: Boolean = false,
        val wifiDirectEnabled: Boolean = false,
        val websocketEnabled: Boolean = false,
        val relayUrl: String? = null
    )
    
    data class TransportStatus(
        val name: String,
        val active: Boolean,
        val peers: Int,
        val bytesSent: Long,
        val bytesReceived: Long
    )
    
    private val _transportStatus = MutableStateFlow<List<TransportStatus>>(emptyList())
    val transportStatus: StateFlow<List<TransportStatus>> = _transportStatus.asStateFlow()
    
    fun configureTransports(config: TransportConfig) {
        if (atmosphereHandle == 0L) return
        
        val configJson = JSONObject().apply {
            put("lan_enabled", config.lanEnabled)
            put("ble_enabled", config.bleEnabled)
            put("p2p_wifi_enabled", config.wifiDirectEnabled)
            put("websocket_enabled", config.websocketEnabled)
            if (config.relayUrl != null) {
                put("relay_url", config.relayUrl)
            }
        }.toString()
        
        AtmosphereNative.configureTransports(atmosphereHandle, configJson)
    }
    
    private suspend fun updateTransportStatus() {
        if (atmosphereHandle == 0L) return
        
        val statusJson = AtmosphereNative.transportStatus(atmosphereHandle)
        val statusObj = JSONObject(statusJson)
        val transportsArray = statusObj.getJSONArray("transports")
        
        val statuses = (0 until transportsArray.length()).map { i ->
            val t = transportsArray.getJSONObject(i)
            TransportStatus(
                name = t.getString("name"),
                active = t.getBoolean("active"),
                peers = t.getInt("peers"),
                bytesSent = t.getLong("bytes_sent"),
                bytesReceived = t.getLong("bytes_received")
            )
        }
        
        _transportStatus.value = statuses
    }
}
```

**Update periodic sync loop to include transport status:**

```kotlin
serviceScope.launch {
    while (true) {
        delay(3000)
        if (atmosphereHandle == 0L) break
        
        // Update peer info
        updatePeerInfo()
        
        // Update transport status
        updateTransportStatus()
    }
}
```

---

### Phase 4: Settings UI with Transport Toggles

**SettingsScreen.kt:**

```kotlin
@Composable
fun SettingsScreen(viewModel: AtmosphereViewModel) {
    val service = viewModel.getService()
    
    LazyColumn {
        item {
            Text("Node Configuration", style = MaterialTheme.typography.titleLarge)
        }
        
        item {
            TextField(
                value = nodeName,
                onValueChange = { /* update */ },
                label = { Text("Node Name") }
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Mesh Configuration", style = MaterialTheme.typography.titleLarge)
        }
        
        item {
            TextField(
                value = "atmosphere-playground-mesh-v1",
                onValueChange = { /* read-only for now */ },
                label = { Text("Mesh ID") },
                readOnly = true
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Transports", style = MaterialTheme.typography.titleLarge)
        }
        
        // LAN Toggle
        item {
            TransportToggle(
                name = "LAN Discovery (WiFi)",
                description = "UDP broadcast + TCP sync on local network",
                enabled = lanEnabled,
                onToggle = { enabled ->
                    service?.configureTransports(
                        TransportConfig(lanEnabled = enabled, ...)
                    )
                }
            )
        }
        
        // BLE Toggle
        item {
            TransportToggle(
                name = "Bluetooth Low Energy",
                description = "Discover peers via BLE when WiFi unavailable",
                enabled = bleEnabled,
                onToggle = { enabled ->
                    service?.configureTransports(
                        TransportConfig(bleEnabled = enabled, ...)
                    )
                }
            )
        }
        
        // WiFi Direct Toggle
        item {
            TransportToggle(
                name = "WiFi Direct (P2P WiFi)",
                description = "Direct peer-to-peer WiFi without router",
                enabled = wifiDirectEnabled,
                onToggle = { enabled ->
                    service?.configureTransports(
                        TransportConfig(wifiDirectEnabled = enabled, ...)
                    )
                }
            )
        }
        
        // WebSocket Toggle + URL
        item {
            TransportToggle(
                name = "BigLlama Cloud Relay",
                description = "Connect via WebSocket relay for internet-wide mesh",
                enabled = websocketEnabled,
                onToggle = { enabled ->
                    service?.configureTransports(
                        TransportConfig(websocketEnabled = enabled, relayUrl = relayUrl)
                    )
                }
            )
        }
        
        if (websocketEnabled) {
            item {
                TextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text("Relay URL") },
                    placeholder = { Text("wss://relay.bigllama.ai") }
                )
            }
        }
    }
}

@Composable
fun TransportToggle(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}
```

---

### Phase 5: MeshScreen with Transport Status

**MeshScreen.kt:**

```kotlin
@Composable
fun MeshScreen(service: AtmosphereService?) {
    val peers by service?.crdtPeers?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val transports by service?.transportStatus?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    
    LazyColumn {
        // Discovered Peers
        item {
            Text("Discovered Peers", style = MaterialTheme.typography.titleLarge)
        }
        
        if (peers.isEmpty()) {
            item {
                Text("No peers discovered yet. Broadcasting UDP on 11452...")
            }
        }
        
        items(peers) { peer ->
            PeerCard(peer)
        }
        
        // Transport Status
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Transport Status", style = MaterialTheme.typography.titleLarge)
        }
        
        items(transports) { transport ->
            TransportStatusCard(transport)
        }
        
        // CRDT Collections
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("CRDT Collections", style = MaterialTheme.typography.titleLarge)
        }
        
        item {
            CrdtCollectionsBrowser(service)
        }
    }
}

@Composable
fun TransportStatusCard(transport: TransportStatus) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (transport.active) Color(0xFF1B5E20) else Color.DarkGray
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when {
                        transport.active -> "✅"
                        else -> "⚪"
                    }
                    Text("$icon ${transport.name}", fontWeight = FontWeight.Bold)
                }
                
                if (transport.active) {
                    Text("${transport.peers} peers", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "↑ ${formatBytes(transport.bytesSent)} ↓ ${formatBytes(transport.bytesReceived)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("Off", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
```

**Example display:**

```
Transport Status
┌─────────────────────────────────────┐
│ ✅ LAN                              │
│ 3 peers                             │
│ ↑ 2.3 MB ↓ 5.1 MB                   │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ ✅ BLE                              │
│ 1 peer                              │
│ ↑ 45 KB ↓ 120 KB                    │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ ⚪ WiFi Direct                      │
│ Off                                 │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ ⚪ BigLlama                         │
│ Not configured                      │
└─────────────────────────────────────┘
```

---

## Android-Specific Transport Notes

### BLE Integration

The Rust `btleplug` crate works on Android, but requires:
1. Enable `ble` feature flag in `atmo-transport` Cargo.toml
2. Android permissions: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
3. Compile with BLE support:
   ```bash
   cargo ndk -t arm64-v8a --features ble build --release -p atmo-jni
   ```

**Alternative:** Use Android's native BLE APIs in Kotlin and bridge to Rust via JNI.

### WiFi Direct Integration

Requires Android `WifiP2pManager` APIs:
1. Permissions: `ACCESS_FINE_LOCATION`, `CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`
2. WifiP2pManager for peer discovery
3. Socket connections between peers
4. Bridge discovered peers to Rust multiplexer

The Rust `p2p_wifi.rs` uses mDNS which works on Android with mdns-sd crate.

### WebSocket Integration

Already works! Just need:
1. Relay URL setting in UI
2. Enable websocket transport in config
3. Rust core handles the rest

---

## Implementation Steps

### Step 1: Extend JNI Bridge
- Add `configureTransports()` and `transportStatus()` JNI functions
- Implement in `atmosphere.rs`

### Step 2: Update Kotlin Service
- Add transport config data classes
- Add transport status polling
- Expose `configureTransports()` method

### Step 3: Build Settings UI
- Add transport toggles
- Add relay URL input
- Wire up to service

### Step 4: Update MeshScreen
- Display transport status cards
- Show per-transport peer counts and traffic

### Step 5: Enable BLE & WiFi Direct
- Add Android permissions to manifest
- Request runtime permissions
- Enable feature flags in Rust build

### Step 6: Test Full Rainbow
- Start app on Android
- Start Mac daemon on WiFi
- Enable all transports
- Verify discovery on all channels
- Check multiplexer prefers LAN > WiFi Direct > BLE > WebSocket

---

## Testing Checklist

- [ ] **LAN** - Mac and Android on same WiFi, auto-discover via UDP
- [ ] **BLE** - Turn off WiFi, enable BLE, peers discover via Bluetooth
- [ ] **WiFi Direct** - Two Android devices, WiFi Direct discovery
- [ ] **WebSocket** - Configure relay URL, connect to BigLlama cloud
- [ ] **Multiplexer** - All transports enabled, verify LAN is preferred
- [ ] **Failover** - Disable LAN, verify failover to BLE/WiFi Direct
- [ ] **UI** - MeshScreen shows all transport statuses correctly

---

## Critical Rules (Reminder)

1. **No ADB reverse / USB tunneling** - All transports are network-based
2. **No daemon URLs** - App IS Atmosphere
3. **MeshScreen = live debugger** - Shows all transports and their status
4. **Settings = transport toggles** - Enable/disable transports
5. **Multiplexer preference** - LAN > WiFi Direct > BLE > WebSocket

---

*Transport implementation plan: 2026-02-13 09:10 CST*
