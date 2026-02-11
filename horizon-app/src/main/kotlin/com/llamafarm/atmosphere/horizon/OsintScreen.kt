package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

private enum class OsintTab(val label: String) { BRIEF("Intel Brief"), RAW("Raw Intel") }
private val CATEGORIES = listOf("ALL", "weather", "notam", "threat", "news", "airfield")

@Composable
fun OsintScreen(
    brief: IntelBrief?,
    intelItems: List<IntelItem>,
    generating: Boolean,
    onGenerateBrief: () -> Unit,
    onLoadBrief: () -> Unit,
    onSearch: (String, String) -> Unit
) {
    var tab by remember { mutableStateOf(OsintTab.BRIEF) }

    LaunchedEffect(Unit) { onLoadBrief() }

    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = HorizonColors.Surface,
            contentColor = HorizonColors.OsintCyan
        ) {
            OsintTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(t.label, fontFamily = MonoFont, fontSize = 12.sp,
                            color = if (tab == t) HorizonColors.OsintCyan else HorizonColors.TextMuted)
                    }
                )
            }
        }

        when (tab) {
            OsintTab.BRIEF -> BriefView(brief, generating, onGenerateBrief)
            OsintTab.RAW -> RawIntelView(intelItems, onSearch)
        }
    }
}

@Composable
private fun BriefView(brief: IntelBrief?, generating: Boolean, onGenerate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Button(
            onClick = onGenerate,
            enabled = !generating,
            colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.OsintCyan),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (generating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HorizonColors.Background)
            else Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Generate Intel Brief", fontFamily = MonoFont)
        }

        Spacer(Modifier.height(12.dp))

        if (brief == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text("No brief available", color = HorizonColors.TextMuted)
            }
        } else {
            BriefSection("THREAT ASSESSMENT", brief.threatAssessment, HorizonColors.AnomalyRed)
            BriefSection("WEATHER", brief.weather, HorizonColors.OsintCyan)
            BriefSection("NOTAMs", brief.notams, HorizonColors.AgentAmber)
            BriefSection("RECOMMENDATIONS", brief.recommendations, HorizonColors.VoiceGreen)
        }
    }
}

@Composable
private fun BriefSection(title: String, content: String, accent: androidx.compose.ui.graphics.Color) {
    if (content.isBlank()) return
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(HorizonColors.CardBg)
            .padding(12.dp)
    ) {
        Text(title, color = accent, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(content, color = HorizonColors.TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun RawIntelView(items: List<IntelItem>, onSearch: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("ALL") }

    Column(Modifier.fillMaxSize()) {
        // Search
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it, if (selectedCat == "ALL") "" else selectedCat) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Search intel…", color = HorizonColors.TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = HorizonColors.OsintCyan) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HorizonColors.OsintCyan,
                unfocusedBorderColor = HorizonColors.SurfaceVariant,
                focusedTextColor = HorizonColors.TextPrimary,
                unfocusedTextColor = HorizonColors.TextPrimary
            )
        )

        // Category chips
        LazyRow(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(CATEGORIES) { cat ->
                FilterChip(
                    selected = cat == selectedCat,
                    onClick = {
                        selectedCat = cat
                        onSearch(query, if (cat == "ALL") "" else cat)
                    },
                    label = { Text(cat.uppercase(), fontSize = 10.sp, fontFamily = MonoFont) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HorizonColors.OsintCyan.copy(alpha = 0.2f),
                        selectedLabelColor = HorizonColors.OsintCyan
                    )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(items, key = { it.id }) { item ->
                IntelCard(item)
            }
        }
    }
}

@Composable
private fun IntelCard(item: IntelItem) {
    val catColor = when (item.category.lowercase()) {
        "threat" -> HorizonColors.AnomalyRed
        "weather" -> HorizonColors.OsintCyan
        "notam" -> HorizonColors.AgentAmber
        "news" -> HorizonColors.KnowledgePurple
        "airfield" -> HorizonColors.VoiceGreen
        else -> HorizonColors.TextMuted
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(HorizonColors.CardBg)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.category.uppercase(),
                color = catColor,
                fontSize = 9.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(3.dp))
                    .background(catColor.copy(alpha = 0.12f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(item.title, color = HorizonColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        Text(item.summary, color = HorizonColors.TextSecondary, fontSize = 12.sp, maxLines = 3)
        if (item.source.isNotBlank()) {
            Text(item.source, color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont)
        }
    }
}
