package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RoutingScreen(viewModel: MeshDebugViewModel) {
    val history by viewModel.routingHistory.collectAsState()
    val isLoading by viewModel.isRoutingLoading.collectAsState()
    var query by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Routing console
        item {
            DashCard(title = "Routing Test Console", emoji = "🧭") {
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (query.isNotBlank()) viewModel.testRoute(query)
                        },
                        enabled = query.isNotBlank() && !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonGreen)
                    ) {
                        Text("Route", fontWeight = FontWeight.SemiBold)
                    }

                    if (isLoading) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentBlue
                        )
                    }
                }

                // Show latest result
                history.firstOrNull()?.let { latest ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(12.dp))

                    if (latest.error != null) {
                        Text("Error: ${latest.error}", color = StatusRed, fontSize = 13.sp)
                    } else {
                        latest.result?.let { r ->
                            Text("ROUTING RESULT", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
