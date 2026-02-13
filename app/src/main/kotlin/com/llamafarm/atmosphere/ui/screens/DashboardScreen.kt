package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

@Composable
fun DashboardScreen(viewModel: MeshDebugViewModel) {
    val health by viewModel.health.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val deviceMetrics by viewModel.deviceMetrics.collectAsState()
    val connected by viewModel.isConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header bar with node info
        DashCard(title = "Node Status", emoji = "🌐") {
            val h = health
            StatRow("Status", if (connected) "● Running" else "○ Offline")
            StatRow("Peer ID", h?.peerId?.take(12) ?: "—")
            StatRow("Node Name", h?.nodeName ?: "—")
            StatRow("Version", h?.version ?: "—")

            val uptime = h?.uptimeSeconds ?: stats?.uptimeSeconds ?: 0L
            val hours = uptime / 3600
            val mins = (uptime % 3600) / 60
            StatRow("Uptime", "${hours}h ${mins}m")

            // Transport bar
            Spacer(Modifier.height(8.dp))
            Text("TRANSPORTS", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val transports = h?.transports ?: emptyMap()
                TransportDot(transports["lan"] == true || connected, "LAN")
                TransportDot(transports["ble"] == true, "BLE")
                TransportDot(transports["websocket"] == true || transports["ws"] == true, "WS")
                TransportDot(transports["wifi_direct"] == true || transports["p2p"] == true, "P2P")
            }
        }

        // Mesh Topology
        DashCard(title = "Mesh Topology", emoji = "📊") {
            StatRow("Connected Peers", "${peers.size}")
            StatRow("Total Capabilities", "${capabilities.size}")
            val avgLat = stats?.avgLatencyMs ?: 0f
            StatRow("Avg Latency", "${String.format("%.1f", avgLat)}ms")
            StatRow("Total Requests", "${stats?.totalRequests ?: 0}")
            StatRow("Errors", "${stats?.errorCount ?: 0}")
        }

        // Device Info
        DashCard(title = "Device Info", emoji = "💻") {
            val dm = deviceMetrics
            // Also pull from first capability's device field via health
            val h = health
            StatRow("Platform", dm?.platform ?: h?.raw?.optString("platform", "Android") ?: "Android")
            StatRow("GPU", dm?.gpu ?: "—")
            StatRow("RAM", dm?.ramGb?.let { "${it} GB" } ?: "—")
            StatRow("CPU Cores", dm?.cpuCores?.toString() ?: "—")
            dm?.batteryLevel?.let { StatRow("Battery", "${it}% ${if (dm.isCharging == true) "⚡" else ""}") }
        }

        // Quick Peer Summary
        if (peers.isNotEmpty()) {
            DashCard(title = "Peers", emoji = "👥") {
                peers.take(5).forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(peer.status)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text(
                                peer.peerId.take(12),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                        peer.latencyMs?.let {
                            Text(
                                "${it}ms",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                }
                if (peers.size > 5) {
                    Text(
                        "+${peers.size - 5} more",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
