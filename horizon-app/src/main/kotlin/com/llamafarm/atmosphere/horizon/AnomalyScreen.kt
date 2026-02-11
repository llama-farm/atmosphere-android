package com.llamafarm.atmosphere.horizon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnomalyScreen(
    anomalies: List<Anomaly>,
    scanning: Boolean,
    onRunScan: () -> Unit,
    onAcknowledge: (String) -> Unit,
    onResolve: (String) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (anomalies.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = HorizonColors.VoiceGreen, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No Active Anomalies", color = HorizonColors.TextSecondary, fontSize = 16.sp)
            }
        } else {
            // Summary bar
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
            ) {
                // Counts row
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnomalySeverity.entries.forEach { sev ->
                            val count = anomalies.count { it.severity == sev }
                            if (count > 0) {
                                SeverityBadge(sev, count)
                            }
                        }
                    }
                }

                items(anomalies, key = { it.id }) { anomaly ->
                    AnomalyCard(anomaly, onAcknowledge, onResolve)
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = onRunScan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = HorizonColors.Accent
        ) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(24.dp), color = HorizonColors.TextPrimary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Radar, "Run Scan", tint = HorizonColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: AnomalySeverity, count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HorizonColors.severityColor(severity).copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(HorizonColors.severityColor(severity))
        )
        Text(
            "$count ${severity.name}",
            color = HorizonColors.severityColor(severity),
            fontSize = 11.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AnomalyCard(
    anomaly: Anomaly,
    onAcknowledge: (String) -> Unit,
    onResolve: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sevColor = HorizonColors.severityColor(anomaly.severity)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HorizonColors.CardBg)
            .clickable { expanded = !expanded }
    ) {
        // Severity stripe + title
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(4.dp).height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(sevColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(anomaly.title, color = HorizonColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(anomaly.description, color = HorizonColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = HorizonColors.TextMuted
            )
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 26.dp, end = 12.dp, bottom = 12.dp)) {
                if (anomaly.aiAnalysis.isNotBlank()) {
                    Text("AI ANALYSIS", color = sevColor, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(anomaly.aiAnalysis, color = HorizonColors.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (anomaly.recommendedAction.isNotBlank()) {
                    Text("RECOMMENDED", color = sevColor, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(anomaly.recommendedAction, color = HorizonColors.TextPrimary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!anomaly.acknowledged) {
                        OutlinedButton(
                            onClick = { onAcknowledge(anomaly.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HorizonColors.AgentAmber)
                        ) { Text("ACK", fontSize = 12.sp, fontFamily = MonoFont) }
                    }
                    if (!anomaly.resolved) {
                        Button(
                            onClick = { onResolve(anomaly.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.VoiceGreen)
                        ) { Text("RESOLVE", fontSize = 12.sp, fontFamily = MonoFont) }
                    }
                }
            }
        }
    }
}
