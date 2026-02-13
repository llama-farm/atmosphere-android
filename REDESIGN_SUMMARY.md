# Atmosphere Android App Redesign Summary

## What Changed
The app has been redesigned from a chat-focused 4-tab app into a **native mesh debugger / control plane** matching the web dashboard at `daemon/dashboard.html`.

## New Navigation
**5 bottom tabs** + gear icon for settings:
1. **Dashboard** — Node status, peer/cap counts, uptime, transports (LAN/BLE/WS/P2P), device info
2. **Mesh** — Peer list with expandable cards (latency, transport, platform, ping), gradient table visualization
3. **Caps** — Full capability browser with search, type filters, grouped by node, load bars, feature flags
4. **Routing** — Interactive routing test console with score breakdown, routing history
5. **Logs** — Real-time log viewer with level filtering (all/info/warn/error), pause/clear, monospace

## Files Created / Rewritten

### New Files
| File | Purpose |
|------|---------|
| `network/MeshApiClient.kt` | HTTP client for all dashboard API endpoints (health, peers, capabilities, gradient-table, requests, stats, device-metrics, logs SSE, routing test, ping) |
| `viewmodel/MeshDebugViewModel.kt` | ViewModel wrapping MeshApiClient, manages routing test state, log filtering |
| `ui/screens/DashboardScreen.kt` | Overview dashboard with node status, topology, device info, peer summary |
| `ui/screens/MeshPeersScreen.kt` | Peer list with expandable cards + gradient table cards |
| `ui/screens/RoutingScreen.kt` | Interactive routing test console with score breakdown bars |
| `ui/screens/LogsScreen.kt` | Real-time scrolling log view with level filter chips |
| `ui/screens/SettingsScreenNew.kt` | Connection/node info settings |
| `ui/components/DebugComponents.kt` | Reusable: DashCard, StatRow, StatusBadge, TransportDot, LoadBar, StatusDot, FilterChipRow, MonoText, EmptyState |

### Rewritten Files
| File | Changes |
|------|---------|
| `MainActivity.kt` | New 5-tab navigation, top app bar with settings gear, dark debugger chrome |
| `ui/theme/Color.kt` | Dashboard color palette (#0d1117 bg, #161b22 cards, #30363d borders, status colors) |
| `ui/theme/Theme.kt` | Dark-only theme, no dynamic color, debugger aesthetic |
| `ui/theme/Type.kt` | Cleaned up typography |
| `ui/screens/CapabilitiesScreen.kt` | Complete rewrite: searchable, filterable, grouped by node, full capability details |

### Unchanged (still needed)
- `viewmodel/AtmosphereViewModel.kt` — Still used for deep link mesh joining & service management
- `viewmodel/JniMeshState.kt` — JNI data classes still valid
- `data/MeshRepository.kt` — Still used by AtmosphereViewModel (HTTP fallback)
- `core/AtmosphereNative.kt` — JNI bridge unchanged
- All service files (`service/`, `network/`, `core/`)

## Files Safe to Delete
These are no longer referenced from the new navigation:

| File | Reason |
|------|--------|
| `ui/screens/HomeScreen.kt` | Replaced by DashboardScreen |
| `ui/screens/HomeScreenNew.kt` | Replaced by DashboardScreen |
| `ui/screens/InferenceScreen.kt` | Chat removed — routing test replaces it |
| `ui/screens/MeshScreen.kt` | Replaced by MeshPeersScreen |
| `ui/screens/MeshScreenNew.kt` | Replaced by MeshPeersScreen |
| `ui/screens/MeshManagementScreen.kt` | Merged into MeshPeersScreen |
| `ui/screens/TestScreen.kt` | Replaced by RoutingScreen |
| `ui/screens/LogScreen.kt` | Replaced by LogsScreen |
| `ui/screens/ConnectedAppsScreen.kt` | No longer in nav |
| `ui/screens/MeshAppsScreen.kt` | No longer in nav |
| `ui/screens/VisionScreen.kt` | No longer in nav |
| `ui/screens/VisionTestScreen.kt` | No longer in nav |
| `ui/screens/VisionTestScreenEnhanced.kt` | No longer in nav |
| `ui/screens/RagScreen.kt` | No longer in nav |
| `ui/screens/PairingScreen.kt` | No longer in nav |
| `ui/screens/JoinMeshScreen.kt` | Deep link join handled by ViewModel |
| `ui/screens/TransportSettingsScreen.kt` | Merged into settings |
| `ui/screens/ModelsScreen.kt` | No longer in nav |
| `ui/screens/MainScreen.kt` | No longer used |
| `ui/components/StatusCard.kt` | Replaced by DebugComponents |
| `viewmodel/ChatViewModel.kt` | Chat removed |
| `viewmodel/InferenceViewModel.kt` | Chat removed |
| `viewmodel/ModelsViewModel.kt` | Models screen removed |

## Design Language
- **Background**: #0D1117 (GitHub dark)
- **Cards**: #161B22 with #30363D borders
- **Monospace** for all data: peer IDs, latencies, scores, timestamps
- **Status dots**: green (connected), yellow (degraded), red (error), gray (offline)
- **Transport bar**: green dot = active, gray = off
- **Real-time**: API polled every 3 seconds, SSE for logs

## Data Flow
```
HTTP API (localhost:11462) → MeshApiClient → MeshDebugViewModel → Compose Screens
                                                    ↓
                                            SSE /api/logs/stream → LogsScreen
```

All endpoints match the web dashboard's JavaScript fetch calls exactly.
