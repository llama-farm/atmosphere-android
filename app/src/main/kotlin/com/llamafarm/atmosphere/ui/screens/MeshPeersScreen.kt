package com.llamafarm.atmosphere.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.network.GradientTableEntry
import com.llamafarm.atmosphere.network.MeshPeerInfo
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

@Composable
fun MeshPeersScreen(viewModel: MeshDebugViewModel) {
    val peers by viewModel.peers.collectAsState()
    val gradientTable by viewModel.gradientTable.collectAsState()
    val connected by viewModel.isConnected.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection status
        if (!connected) {
            item {
                DashCard(title = "Status", emoji = "⚠️") {
                    Text("Not connected to mesh", color = StatusYellow, fontSize = 13.sp)
                }
            }
        }

        // Peer list
        item {
            SectionHeader("Peers (${peers.size})")
        }

        if (peers.isEmpty()) {
            item { EmptyState("No peers connected") }
        } else {
            items(peers, key = { it.peerId }) { peer ->
                PeerCard(peer, viewModel)
            }
        }

        // Gradient Table
        if (gradientTable.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Gradient Table (${gradientTable.size})") }
            items(gradientTable, key = { "${it.capability}:${it.node}" }) { entry ->
                GradientTableCard(entry)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PeerCard(peer: MeshPeerInfo, viewModel: MeshDebugViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(peer.status)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    GrayBadge(peer.transport)
                }
                MonoText(peer.peerId.take(16), color = TextMuted, fontSize = 11)
            }
            peer.latencyMs?.let {
                MonoText("${it}ms", color = if (it < 50) StatusGreen else if (it < 200) StatusYellow else StatusRed)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                val platformIcon = when (peer.platform) {
                    "darwin", "macos" -> "🖥️"
                    "android" -> "📱"
                    "linux" -> "🐧"
                    "windows" -> "🪟"
                    else -> "💻"
                }
                StatRow("Platform", "$platformIcon ${peer.platform}")
                StatRow("Transport", peer.transport)
                if (peer.lastSeen > 0) {
                    val ago = (System.currentTimeMillis() / 1000 - peer.lastSeen)
                    StatRow("Last Seen", if (ago < 60) "${ago}s ago" else "${ago / 60}m ago")
                }
                peer.metadata.forEach { (k, v) ->
                    if (v.isNotEmpty()) StatRow(k, v)
                }

                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            pingResult = "Pinging..."
                            viewModel.pingPeer(peer.peerId) { rtt ->
                                pingResult = if (rtt != null) "✓ ${rtt}ms" else "✗ Timeout"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Ping", fontSize = 12.sp)
                    }
                    pingResult?.let {
                        Spacer(Modifier.width(12.dp))
                        MonoText(it, color = if (it.startsWith("✓")) StatusGreen else StatusRed, fontSize = 12)
                    }
                }
            }
        }
    }
}

@Composable
private fun GradientTableCard(entry: GradientTableEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Row 1: Capability name + status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.capability, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                if (entry.available) "● ONLINE" else "○ OFFLINE",
                color = if (entry.available) StatusGreen else StatusRed,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(4.dp))
        
        // Row 2: Node + type + tier
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoText(entry.node.take(12), color = TextMuted, fontSize = 11)
            BlueBadge(entry.type)
            entry.model?.let { MonoText(it, color = TextSecondary, fontSize = 11) }
            entry.tier?.let { PurpleBadge(it) }
        }
        
        // Row 3: Device info (CPU, RAM, GPU)
        if (entry.cpuCores != null || entry.memoryGb != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.cpuCores?.let { MonoText("${it} cores", color = AccentCyan, fontSize = 11) }
                entry.memoryGb?.let { MonoText("${it}GB RAM", color = AccentCyan, fontSize = 11) }
                if (entry.gpuAvailable) MonoText("GPU ✓", color = StatusGreen, fontSize = 11)
                else MonoText("CPU only", color = TextMuted, fontSize = 11)
            }
        }
        
        // Row 4: Load + latency + cost
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadBar(entry.load, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            MonoText("Q:${entry.queueDepth}", color = TextMuted, fontSize = 11)
            entry.avgInferenceMs?.let {
                Spacer(Modifier.width(8.dp))
                MonoText("${it.toInt()}ms", color = TextSecondary, fontSize = 11)
            }
            entry.score?.let {
                Spacer(Modifier.width(8.dp))
                MonoText("cost:${String.format("%.2f", it)}", color = AccentOrange, fontSize = 11)
            }
        }
    }
}
