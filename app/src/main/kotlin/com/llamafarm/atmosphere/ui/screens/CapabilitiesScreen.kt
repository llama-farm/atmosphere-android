package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.network.MeshCapabilityInfo
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

@Composable
fun CapabilitiesScreen(viewModel: MeshDebugViewModel) {
    val capabilities by viewModel.capabilities.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") }

    val filters = listOf("all", "llm", "vision", "tts", "tool", "api")

    val filtered = capabilities.filter { cap ->
        val matchesFilter = selectedFilter == "all" || cap.type == selectedFilter
        val matchesSearch = searchQuery.isEmpty() ||
            cap.name.contains(searchQuery, ignoreCase = true) ||
            cap.nodeName.contains(searchQuery, ignoreCase = true) ||
            cap.model?.contains(searchQuery, ignoreCase = true) == true
        matchesFilter && matchesSearch
    }

    // Group by node
    val grouped = filtered.groupBy { it.nodeId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search capabilities...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = BorderColor,
                    cursorColor = AccentBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                ),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
        }

        // Filters
        item {
            FilterChipRow(options = filters, selected = selectedFilter, onSelect = { selectedFilter = it })
        }

        // Count
        item {
            Text(
                "${filtered.size} capabilities across ${grouped.size} nodes",
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        if (filtered.isEmpty()) {
            item { EmptyState("No capabilities match filter") }
        }

        // Grouped by node
        grouped.forEach { (nodeId, caps) ->
            item {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot("connected")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        caps.firstOrNull()?.nodeName?.ifEmpty { nodeId.take(12) } ?: nodeId.take(12),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    MonoText(nodeId.take(12), color = TextMuted, fontSize = 11)
                }
            }

            items(caps, key = { it.id }) { cap ->
                CapabilityCard(cap)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun CapabilityCard(cap: MeshCapabilityInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Name + type badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cap.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
            BlueBadge(cap.type)
        }

        Spacer(Modifier.height(6.dp))

        // Model + tier
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cap.model?.let { MonoText(it, color = TextSecondary, fontSize = 12) }
            cap.tier?.let { PurpleBadge(it) }
            cap.paramsB?.let { GrayBadge("${it}B") }
        }

        Spacer(Modifier.height(8.dp))

        // Feature flags
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureFlag("RAG", cap.hasRag)
            FeatureFlag("Vision", cap.hasVision)
            FeatureFlag("Tools", cap.hasTools)
        }

        Spacer(Modifier.height(8.dp))

        // Load + latency + status
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadBar(cap.load, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("Q:${cap.queueDepth}", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            cap.avgInferenceMs?.let {
                MonoText("${it.toInt()}ms", color = TextSecondary, fontSize = 11)
            }
            Spacer(Modifier.width(8.dp))
            if (cap.available) GreenBadge("Available") else RedBadge("Unavailable")
        }

        // Semantic tags
        if (cap.semanticTags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                cap.semanticTags.take(5).forEach { tag ->
                    GrayBadge(tag)
                }
            }
        }
    }
}

@Composable
private fun FeatureFlag(label: String, enabled: Boolean) {
    Text(
        "$label: ${if (enabled) "✓" else "—"}",
        color = if (enabled) StatusGreen else TextMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}
