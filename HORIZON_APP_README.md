# HORIZON Mobile App - Atmosphere Integration

**Status**: Framework complete, mesh integration pending

This is the mobile interface for HORIZON (Disconnected Ops Intelligence) built on top of the Atmosphere mesh. The app communicates with HORIZON exclusively through Atmosphere mesh routing — no direct HTTP calls.

## Architecture

```
┌─────────────────────────────────────────┐
│  Android App (Atmosphere HORIZON)      │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │  HorizonViewModel                 │  │
│  │  - Mission summary                │  │
│  │  - Anomalies                      │  │
│  │  - Agent actions                  │  │
│  │  - Intel chat                     │  │
│  └───────────────┬───────────────────┘  │
│                  │                       │
│                  ▼                       │
│  ┌───────────────────────────────────┐  │
│  │  Mesh Connection                  │  │
│  │  (WebSocket to Atmosphere node)   │  │
│  └───────────────┬───────────────────┘  │
└──────────────────┼──────────────────────┘
                   │
                   │ app_request / push_event
                   ▼
┌─────────────────────────────────────────┐
│  Local Atmosphere Node                  │
│  - Capability discovery via gossip      │
│  - Semantic routing                     │
│  - App request routing                  │
└──────────────────┬──────────────────────┘
                   │
                   │ proxied HTTP
                   ▼
┌─────────────────────────────────────────┐
│  HORIZON Backend (FastAPI)              │
│  - Registered via atmosphere_bridge.py  │
│  - 5 capabilities exposed               │
└─────────────────────────────────────────┘
```

## Screens

### 1. Dashboard (`HorizonDashboardScreen.kt`)
- Mission summary (callsign, phase, route, connectivity)
- Quick stats (anomaly count, pending actions)
- Quick actions to other screens
- **Data source**: `app/horizon/mission` → `summary` endpoint

### 2. Anomalies (`HorizonAnomaliesScreen.kt`)
- Active anomalies grouped by severity (CRITICAL, WARNING, CAUTION, INFO)
- Expandable cards with full details
- Acknowledge and Resolve actions
- **Data source**: `app/horizon/anomaly` → `list_active` endpoint
- **Push events**: `anomaly.new`, `anomaly.critical`, `anomaly.resolved`

### 3. Agent Actions (`HorizonAgentActionsScreen.kt`)
- Pending agent decisions needing human approval
- Shows original message, drafted response, and reasoning
- Approve/Reject buttons
- Priority badges (CRITICAL, HIGH, MEDIUM, LOW)
- **Data source**: `app/horizon/agent` → `needs_input` endpoint
- **Push events**: `action.needs_approval`

### 4. Intel Chat (`HorizonIntelChatScreen.kt`)
- Natural language interface to HORIZON's knowledge brain
- Chat-style UI (user/assistant bubbles)
- Suggested questions
- **Data source**: `app/horizon/knowledge` → `query` endpoint

## Design Language

**Military Aesthetic**:
- Dark theme (background: `#0A0A0A`, cards: `#1A1A1A`)
- Amber accents (`#FFB74D`) for primary actions and highlights
- Green (`#4CAF50`) for success/good states
- Red (`#FF5252`) for critical alerts
- Clean, professional, no-nonsense

**Typography**:
- Material3 typography scale
- Bold headers for emphasis
- UPPERCASE labels for military feel (e.g., "CRITICAL", "APPROVED")

## ViewModel (`HorizonViewModel.kt`)

The ViewModel manages all state and mesh communication:

```kotlin
class HorizonViewModel : ViewModel() {
    val missionSummary: StateFlow<MissionSummary>
    val anomalies: StateFlow<List<Anomaly>>
    val agentActions: StateFlow<List<AgentAction>>
    val chatMessages: StateFlow<List<ChatMessage>>
    
    // Mesh connection (set by service)
    var meshConnection: MeshConnection?
    
    // Data loading
    fun loadMissionSummary()
    fun loadAnomalies()
    fun loadAgentActions()
    
    // Actions
    fun acknowledgeAnomaly(id: String)
    fun resolveAnomaly(id: String)
    fun approveAction(id: String)
    fun rejectAction(id: String)
    fun sendIntelQuery(question: String)
    
    // Push event handling
    fun onPushEvent(event: String, data: Map<String, Any>)
}
```

**Mesh communication flow**:
1. ViewModel calls `sendAppRequest(capabilityId, endpoint, params)`
2. Request is serialized to JSON: `{"type": "app_request", ...}`
3. Sent via `meshConnection.sendMessage()`
4. Atmosphere node routes to HORIZON via SDK
5. Response flows back through mesh
6. ViewModel updates StateFlow → UI re-renders

## Data Models (`HorizonModels.kt`)

All models use `@Serializable` for JSON parsing:
- `MissionSummary` - Overview data for dashboard
- `Anomaly` - Anomaly details with severity/category
- `AgentAction` - Pending approval with HIL priority
- `IntelBrief` - AI-generated intelligence briefing
- `AppRequest` / `AppResponse` - Mesh protocol wrappers

## Integration Status

### ✅ Completed
- [x] All UI screens built with Material3 Compose
- [x] ViewModel with state management
- [x] Data models with serialization
- [x] Military-themed design system
- [x] Placeholder mesh communication layer

### 🚧 Pending
- [ ] Wire up `HorizonViewModel.meshConnection` to actual Atmosphere WebSocket
- [ ] Implement `MeshConnection` interface (send/receive)
- [ ] Add push event subscription on screen load
- [ ] Handle mesh disconnection/reconnection
- [ ] Add local caching for offline mode
- [ ] Add notification support for push events
- [ ] Test with live HORIZON backend

## Integration Steps

1. **Add to MainActivity navigation**:
```kotlin
composable("horizon_dashboard") { HorizonDashboardScreen(...) }
composable("horizon_anomalies") { HorizonAnomaliesScreen(...) }
composable("horizon_agent_actions") { HorizonAgentActionsScreen(...) }
composable("horizon_intel_chat") { HorizonIntelChatScreen(...) }
```

2. **Wire up MeshConnection**:
```kotlin
// In MeshService or MainActivity
val horizonViewModel = viewModel<HorizonViewModel>()
horizonViewModel.meshConnection = object : HorizonViewModel.MeshConnection {
    override suspend fun sendMessage(message: JsonObject) {
        atmosphereWebSocket.send(message.toString())
    }
    override suspend fun awaitResponse(requestId: String, timeout: Long): AppResponse {
        // Wait for response with matching request_id
    }
}
```

3. **Subscribe to push events**:
```kotlin
// On mesh connect
atmosphereWebSocket.send("""
{
    "type": "subscribe_events",
    "patterns": ["anomaly.*", "action.needs_approval", "osint.*"]
}
""")
```

4. **Handle push deliveries**:
```kotlin
when (message.type) {
    "push_delivery" -> {
        horizonViewModel.onPushEvent(message.event, message.data)
    }
}
```

## Testing Without Mesh

For UI testing before mesh integration:
```kotlin
// Mock data injection
viewModel._missionSummary.value = MissionSummary(
    callsign = "REACH 421",
    phase = "CRUISE",
    route = "KDOV → OKBK",
    connectivity = "connected",
    anomalyCount = 2,
    pendingActions = 1
)
```

## File Structure

```
app/src/main/kotlin/com/llamafarm/atmosphere/
├── horizon/
│   ├── HorizonModels.kt       # Data models
│   └── HorizonViewModel.kt    # State + mesh logic
└── ui/screens/
    ├── HorizonDashboardScreen.kt
    ├── HorizonAnomaliesScreen.kt
    ├── HorizonAgentActionsScreen.kt
    └── HorizonIntelChatScreen.kt
```

## Next Steps

1. Complete `MeshConnection` implementation in existing Atmosphere WebSocket handler
2. Add HORIZON section to main navigation drawer
3. Test end-to-end with HORIZON bridge running
4. Add notification channel for push events
5. Implement offline caching (Room database)
6. Add mission selection if supporting multiple concurrent missions

---

**Demo-ready**: The UI is fully functional and can be demonstrated with mock data. Mesh integration is the final step.
