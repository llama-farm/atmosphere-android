# Atmosphere Android - Testing Checklist

Quick reference for verifying each screen after deployment.

## Pre-Flight Setup

```bash
# Deploy to device
cd /Users/robthelen/clawd/projects/atmosphere-android
./gradlew assembleDebug
adb -s 4B041FDAP0033Q install -r app/build/outputs/apk/debug/app-debug.apk

# Set up daemon connection (if using Mac daemon)
adb reverse tcp:11462 tcp:11462

# Launch app
adb shell am start -n com.llamafarm.atmosphere/.MainActivity
```

## Screen-by-Screen Testing

### 1. Home Screen (Dashboard)
**Navigation:** Bottom tab → Home

**Verify:**
- [ ] Connection status badge shows correct state (online/offline)
- [ ] Peer count accurate (matches actual connected peers)
- [ ] Capability count from daemon displays
- [ ] Mesh events feed populates in real-time
- [ ] Cost dashboard shows battery/cpu/network metrics
- [ ] Daemon status card shows connection to Mac (if applicable)
- [ ] Routing decision card appears after inference
- [ ] Debug card shows correct internal state

**Quick Test:**
```
1. Connect to mesh via QR code
2. Watch peer count increment
3. Send a chat message
4. Verify routing decision appears on home screen
```

---

### 2. Chat Screen (Inference)
**Navigation:** Bottom tab → Chat

**Verify:**
- [ ] Model status shows "Ready" when loaded
- [ ] Persona selector opens and switches correctly
- [ ] Chat input accepts text
- [ ] Send button triggers inference
- [ ] Response appears in chat bubbles
- [ ] Daemon routing toggle visible when daemon connected
- [ ] Daemon routing actually sends to mesh
- [ ] Local inference works when toggle off
- [ ] Error messages display properly

**Quick Test:**
```
1. Type "Hello, how are you?"
2. Send
3. Verify response appears
4. Toggle daemon routing ON
5. Send another message
6. Verify it routes via mesh
```

---

### 3. Mesh Screen (Network)
**Navigation:** Bottom tab → Mesh

**Verify:**
- [ ] "My Meshes" section shows saved meshes
- [ ] CRDT Peers section populates when connected
- [ ] Transport status indicators accurate (BLE/LAN/Relay)
- [ ] Capability list shows daemon capabilities
- [ ] Mesh apps appear when discovered (e.g., HORIZON)
- [ ] Scan button triggers peer discovery
- [ ] Reconnect button works for saved meshes
- [ ] Forget mesh button removes saved mesh
- [ ] Auto-reconnect toggle persists

**Quick Test:**
```
1. Join a mesh
2. Verify it appears in "My Meshes"
3. Check CRDT Peers populate
4. Open a mesh app (if available)
5. Disconnect
6. Verify "Reconnect" button appears
```

---

### 4. Settings Screen
**Navigation:** Bottom tab → Settings

**Verify:**
- [ ] Node name editable and persists
- [ ] Node ID displays correctly (full ID in dialog)
- [ ] Auto-start on boot toggle works
- [ ] Auto-reconnect to mesh toggle works
- [ ] Battery optimization link opens system settings
- [ ] All navigation links work:
  - [ ] Test Console
  - [ ] RAG
  - [ ] Vision
  - [ ] Connected Apps
  - [ ] Transport Settings
- [ ] Clear mesh history works
- [ ] Version displays correctly

**Quick Test:**
```
1. Change node name
2. Reopen Settings
3. Verify name persisted
4. Navigate to Test Console
5. Navigate back
```

---

### 5. Test Console
**Navigation:** Settings → Test Console

**Verify:**
- [ ] Three tabs render: Inference, Connectivity, Nodes
- [ ] **Inference Tab:**
  - [ ] Quick test buttons work
  - [ ] Custom prompt input works
  - [ ] Response displays
  - [ ] Routing decision shows
  - [ ] Latency measured
- [ ] **Connectivity Tab:**
  - [ ] Mesh status accurate
  - [ ] Transport status shows
  - [ ] Ping test works
- [ ] **Nodes Tab:**
  - [ ] Peer list populates
  - [ ] Node details accurate

**Quick Test:**
```
1. Click "Math" quick test
2. Verify "What is 2+2?" response
3. Check routing decision
4. Switch to Connectivity tab
5. Verify mesh status
```

---

### 6. Capabilities Screen
**Navigation:** Via navigation (not in main tabs)

**Verify:**
- [ ] Daemon capabilities section populates
- [ ] Local capabilities show (Camera, Mic, Location, Storage, Compute)
- [ ] Permission toggles work
- [ ] Permission dialogs appear when toggling on
- [ ] Capability cards show status correctly

**Quick Test:**
```
1. Toggle Camera capability ON
2. Grant permission
3. Verify capability shows as enabled
4. Check daemon capabilities section for gradient table entries
```

---

### 7. Connected Apps Screen
**Navigation:** Settings → Connected Apps

**Verify:**
- [ ] Connected SDK apps appear
- [ ] Package names correct
- [ ] Request count increments
- [ ] Token count increments
- [ ] Timestamps update (Connected At, Last Active)
- [ ] Capabilities used list populates
- [ ] Stats update every 2 seconds

**Quick Test:**
```
1. Open a demo app that uses SDK (e.g., atmosphere-client)
2. Send a chat request from demo app
3. Refresh Connected Apps screen
4. Verify app appears with stats
```

---

### 8. Mesh Apps Screen
**Navigation:** Discovered via Mesh screen

**Verify:**
- [ ] Apps discovered from mesh appear (e.g., HORIZON)
- [ ] App cards expand/collapse
- [ ] Tool list displays
- [ ] Tool parameters editable
- [ ] Tool invocation works (Play button)
- [ ] Response displays below tool
- [ ] Push events section shows events from mesh apps

**Quick Test:**
```
1. Connect to mesh with HORIZON node
2. Wait for app to appear
3. Expand HORIZON card
4. Find "get_mission_summary" tool
5. Click Play button
6. Verify response appears
```

---

### 9. RAG Screen
**Navigation:** Settings → RAG

**Verify:**
- [ ] RAG indices list loads
- [ ] Create index button works
- [ ] Add document dialog works
- [ ] Query interface works
- [ ] Search results display
- [ ] Generated answer shows (if enabled)
- [ ] Delete index works

**Quick Test:**
```
1. Click "+" to create index
2. Name it "test-index"
3. Add a document with sample text
4. Query "what is in the document?"
5. Verify results appear
```

---

### 10. Vision Screen
**Navigation:** Settings → Vision

**Verify:**
- [ ] Camera permission requested
- [ ] Camera preview appears
- [ ] Object detection runs
- [ ] Bounding boxes drawn on preview
- [ ] Detection results list shows class/confidence
- [ ] Model selector works
- [ ] Front/back camera toggle works
- [ ] Mesh escalation triggers on low confidence
- [ ] Inference time measured

**Quick Test:**
```
1. Grant camera permission
2. Point at an object (phone, cup, person)
3. Verify bounding box appears
4. Check detection result (class, confidence %)
5. Toggle to front camera
6. Verify it switches
```

---

### 11. Transport Settings
**Navigation:** Settings → Transport Settings

**Verify:**
- [ ] BLE toggle works
- [ ] WiFi Direct toggle works
- [ ] LAN discovery toggle works
- [ ] Relay settings configurable
- [ ] Settings persist after restart

**Quick Test:**
```
1. Toggle BLE OFF
2. Verify mesh still works via LAN
3. Toggle BLE back ON
4. Verify setting persisted
```

---

### 12. Join Mesh Screen
**Navigation:** Mesh → Join Mesh button

**Verify:**
- [ ] QR code scanner opens
- [ ] Scans mesh invite QR codes
- [ ] Connects to mesh after scan
- [ ] Error messages display on failure
- [ ] Manual token input works

**Quick Test:**
```
1. Generate mesh QR code on Mac daemon
2. Click "Join Mesh"
3. Scan QR code
4. Verify connection succeeds
5. Check Home screen for mesh name
```

---

## End-to-End Flows

### Flow 1: First Launch → Join Mesh → Chat
```
1. Install app
2. Grant camera permission
3. Navigate to Mesh tab
4. Click "Join Mesh"
5. Scan QR code
6. Navigate to Chat tab
7. Send "Hello from Android!"
8. Verify response from mesh
9. Check Home screen for routing decision
```

### Flow 2: SDK Integration Test
```
1. Install atmosphere-client demo app
2. Open demo app
3. Send chat request from demo app
4. Open Atmosphere app
5. Navigate to Connected Apps
6. Verify demo app appears with stats
```

### Flow 3: Vision → Escalation
```
1. Navigate to Vision screen
2. Point at object
3. Verify local detection runs
4. Point at complex/unknown object
5. Verify mesh escalation triggers
6. Check response from mesh peer
```

---

## Performance Checks

**Memory:**
```bash
adb shell dumpsys meminfo com.llamafarm.atmosphere
```
Expected: < 300 MB

**CPU:**
```bash
adb shell top | grep atmosphere
```
Expected: < 10% when idle, < 50% during inference

**Battery:**
```bash
adb shell dumpsys batterystats --charged com.llamafarm.atmosphere
```
Expected: Background service should be efficient

**Network:**
```bash
adb shell dumpsys netstats | grep atmosphere
```
Expected: Minimal when idle, bursts during sync

---

## Log Monitoring

**Filter Atmosphere logs:**
```bash
adb logcat -v time | grep -E "(Atmosphere|CRDT|SemanticRouter|LlamaFarm)"
```

**Key events to watch:**
- `📡 CRDT request inserted`
- `📥 Got response for [requestId]`
- `🎯 Routed 'query' → capability`
- `📊 UI State Update`
- `🔧 Routing via CRDT mesh...`

---

## Success Criteria

✅ All screens render without crashes  
✅ Navigation flows work smoothly  
✅ Data displays correctly throughout  
✅ Mesh connectivity established and stable  
✅ Local inference works  
✅ Daemon routing works via CRDT  
✅ SDK apps can connect and use API  
✅ Events flow correctly (mesh updates, cost updates, CRDT changes)  
✅ No memory leaks during 10-minute session  
✅ Build = SUCCESS, Deployment = SUCCESS, Launch = SUCCESS

---

## Known Limitations

1. **Device not connected** - Testing deferred until device reconnects
2. **Hardcoded peer URLs** - VisionScreen has hardcoded mesh peer IP (acceptable for dev/demo)
3. **Production logging** - Debug logs are verbose (can be reduced in release build)

---

## Deployment Command Reference

```bash
# Full deployment cycle
cd /Users/robthelen/clawd/projects/atmosphere-android

# Build
./gradlew assembleDebug

# Install
adb -s 4B041FDAP0033Q install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.llamafarm.atmosphere/.MainActivity

# View logs
adb logcat -v time | grep Atmosphere

# Clear data (fresh start)
adb shell pm clear com.llamafarm.atmosphere
```

---

**Ready to test when device reconnects!** 📱✅
