package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private enum class AgentTab(val label: String) {
    HIL("HIL Queue"), HANDLED("Auto-Handled")
}

@Composable
fun AgentScreen(
    hilItems: List<HilItem>,
    handledItems: List<HandledItem>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    var tab by remember { mutableStateOf(AgentTab.HIL) }

    Column(Modifier.fillMaxSize()) {
        // Alert banner if HIL items pending
        if (hilItems.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(HorizonColors.AgentAmber.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = HorizonColors.AgentAmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${hilItems.size} ACTION${if (hilItems.size != 1) "S" else ""} REQUIRE APPROVAL",
                    color = HorizonColors.AgentAmber,
                    fontSize = 11.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold
                )
            }
        }

        // Tabs
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = HorizonColors.Surface,
            contentColor = HorizonColors.AgentAmber
        ) {
            AgentTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(
                            t.label,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                            color = if (tab == t) HorizonColors.AgentAmber else HorizonColors.TextMuted
                        )
                    }
                )
            }
        }

        when (tab) {
            AgentTab.HIL -> HilList(hilItems, onApprove, onReject)
            AgentTab.HANDLED -> HandledList(handledItems)
        }
    }
}

@Composable
private fun HilList(items: List<HilItem>, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, null, tint = HorizonColors.VoiceGreen, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(6.dp))
                Text("No Pending Actions", color = HorizonColors.TextSecondary, fontSize = 14.sp)
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                HilCard(item, onApprove, onReject)
            }
        }
    }
}

@Composable
private fun HilCard(item: HilItem, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    val urgencyColor = when (item.urgency) {
        "high" -> HorizonColors.AnomalyRed
        "medium" -> HorizonColors.AgentAmber
        else -> HorizonColors.Accent
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HorizonColors.CardBg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(urgencyColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(item.title, color = HorizonColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (item.source.isNotBlank()) {
                Text(item.source, color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(item.description, color = HorizonColors.TextSecondary, fontSize = 12.sp)

        if (item.proposedAction.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("PROPOSED ACTION", color = HorizonColors.AgentAmber, fontSize = 10.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            Text(item.proposedAction, color = HorizonColors.TextPrimary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onApprove(item.id) },
                colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.VoiceGreen),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("APPROVE", fontSize = 12.sp, fontFamily = MonoFont)
            }
            OutlinedButton(
                onClick = { onReject(item.id) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HorizonColors.AnomalyRed),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("REJECT", fontSize = 12.sp, fontFamily = MonoFont)
            }
        }
    }
}

@Composable
private fun HandledList(items: List<HandledItem>) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No auto-handled items", color = HorizonColors.TextMuted)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(HorizonColors.CardBg)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = HorizonColors.AgentAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = HorizonColors.TextPrimary, fontSize = 13.sp)
                        Text(item.action, color = HorizonColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
