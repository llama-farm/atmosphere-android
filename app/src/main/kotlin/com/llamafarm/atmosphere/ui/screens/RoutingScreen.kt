package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RoutingScreen(viewModel: MeshDebugViewModel, atmosphereViewModel: AtmosphereViewModel? = null) {
    val history by viewModel.routingHistory.collectAsState()
    val isLoading by viewModel.isRoutingLoading.collectAsState()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0=Auto Route, 1=Direct Target
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var lastResponse by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    
    // Get available LLM capabilities from gossip (via AtmosphereViewModel)
    val capabilities = atmosphereViewModel?.llmCapabilities?.collectAsState()?.value ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mode selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    label = { Text("🧭 Auto Route") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBlue.copy(alpha = 0.2f),
                        selectedLabelColor = AccentBlue,
                        containerColor = CardBackground,
                        labelColor = TextSecondary
                    )
                )
                FilterChip(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    label = { Text("🎯 Direct Target") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentOrange.copy(alpha = 0.2f),
                        selectedLabelColor = AccentOrange,
                        containerColor = CardBackground,
                        labelColor = TextSecondary
                    )
                )
            }
        }

        // Target selector (only in Direct Target mode)
        if (mode == 1) {
            item {
                DashCard(title = "Select Target", emoji = "🎯") {
                    if (capabilities.isEmpty()) {
                        Text(
                            "No LLM capabilities found in mesh.\nWait for gossip propagation or check peer connections.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    } else {
                        capabilities.forEach { cap ->
                            val isSelected = selectedTarget == cap.capabilityId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) AccentBlue.copy(alpha = 0.1f) else CardBackground,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { selectedTarget = cap.capabilityId }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val icon = when {
                                    cap.hops == 0 -> "📱"
                                    else -> "🌐"
                                }
                                Text(icon, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        cap.label,
                                        color = if (isSelected) AccentBlue else TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "${cap.nodeName} • hops=${cap.hops} • ${cap.modelTier}",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                if (isSelected) {
                                    Text("✓", color = AccentBlue, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        // Query input + action buttons
        item {
            DashCard(
                title = if (mode == 0) "Routing Test Console" else "Direct Request",
                emoji = if (mode == 0) "🧭" else "🎯"
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Enter your query or prompt...", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = BorderColor,
                        cursorColor = AccentBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DashboardBackground,
                        unfocusedContainerColor = DashboardBackground,
                    ),
                    shape = RoundedCornerShape(6.dp),
                    minLines = 3,
                    maxLines = 6
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Route test (dry run) — always available
                    if (mode == 0) {
                        Button(
                            onClick = {
                                if (query.isNotBlank()) viewModel.testRoute(query)
                            },
                            enabled = query.isNotBlank() && !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonGreen)
                        ) {
                            Text("Route Test", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Execute button — actually sends the request
                    Button(
                        onClick = {
                            if (query.isNotBlank() && atmosphereViewModel != null) {
                                isExecuting = true
                                lastResponse = null
                                lastError = null
                                val targetId = if (mode == 1) selectedTarget else null
                                atmosphereViewModel.sendUserMessage(
                                    content = query,
                                    target = targetId
                                ) { response, error ->
                                    isExecuting = false
                                    lastResponse = response
                                    lastError = error
                                }
                            }
                        },
                        enabled = query.isNotBlank() && !isExecuting &&
                                atmosphereViewModel != null &&
                                (mode == 0 || selectedTarget != null),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == 1) AccentOrange else AccentBlue
                        )
                    ) {
                        Text(
                            if (mode == 0) "Route & Execute" else "Execute on Target",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isLoading || isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentBlue
                        )
                    }
                }

                // Show execution response
                if (lastResponse != null || lastError != null) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "EXECUTION RESULT",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    if (lastError != null) {
                        Text(
                            "Error: $lastError",
                            color = StatusRed,
                            fontSize = 13.sp
                        )
                    }

                    lastResponse?.let { resp ->
                        Text(
                            resp,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DashboardBackground, RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        )
                    }
                }

                // Show latest route test result
                history.firstOrNull()?.let { latest ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(12.dp))

                    if (latest.error != null) {
                        Text("Route Error: ${latest.error}", color = StatusRed, fontSize = 13.sp)
                    } else {
                        latest.result?.let { r ->
                            Text("ROUTE TEST RESULT", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))

                            r.target?.let { StatRow("Target", it) }
                            r.score?.let { StatRow("Score", String.format("%.4f", it)) }

                            if (r.breakdown.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("SCORE BREAKDOWN", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                r.breakdown.forEach { (k, v) ->
                                    ScoreBar(k, v)
                                }
                            }

                            // Raw JSON
                            Spacer(Modifier.height(12.dp))
                            Text(
                                r.raw.toString(2),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextMuted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DashboardBackground, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }

        // History
        item { SectionHeader("Routing History (${history.size})") }

        if (history.isEmpty()) {
            item { EmptyState("No routing decisions yet") }
        } else {
            items(history.drop(1), key = { it.timestamp }) { entry ->
                RoutingHistoryCard(entry)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ScoreBar(label: String, value: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.replaceFirstChar { it.uppercase() },
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(100.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .background(BorderSubtle, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .background(AccentBlue, RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.width(8.dp))
        MonoText(String.format("%.3f", value), color = TextPrimary, fontSize = 11)
    }
}

@Composable
private fun RoutingHistoryCard(entry: MeshDebugViewModel.RoutingTestResult) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(fmt.format(Date(entry.timestamp)), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            entry.query.take(80) + if (entry.query.length > 80) "..." else "",
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
        entry.result?.let { r ->
            Text(
                "→ ${r.target ?: "unknown"} (score: ${r.score?.let { String.format("%.3f", it) } ?: "?"})",
                color = AccentBlue,
                fontSize = 12.sp
            )
        }
        entry.error?.let {
            Text("Error: $it", color = StatusRed, fontSize = 12.sp)
        }
    }
}
