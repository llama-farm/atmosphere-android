package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

@Composable
fun SettingsScreenNew(viewModel: MeshDebugViewModel) {
    val health by viewModel.health.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DashCard(title = "Connection", emoji = "🔗") {
            StatRow("Data Source", "Local JNI (AtmosphereNative)")
            StatRow("Poll Interval", "3s")
            StatRow("Status", if (health != null) "Connected" else "Disconnected")
        }

        DashCard(title = "Node Info", emoji = "ℹ️") {
            StatRow("Peer ID", health?.peerId ?: "—")
            StatRow("Node Name", health?.nodeName ?: "—")
            StatRow("Mesh Port", health?.meshPort?.toString() ?: "—")
            StatRow("Version", health?.version ?: "—")
        }

        DashCard(title = "About", emoji = "🌐") {
            Text("Atmosphere Mesh Debugger", color = TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Control plane for the Atmosphere mesh network.", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("v1.0.0", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(8.dp))
    }
}
