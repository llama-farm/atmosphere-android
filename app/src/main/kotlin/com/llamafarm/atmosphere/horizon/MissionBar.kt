package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MissionBar(mission: MissionSummary, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("POSITION", mission.position)
        StatCard("ALT", mission.altitude)
        StatCard("FUEL", mission.fuel)
        StatCard("CARGO", mission.cargo)
        StatCard("PAX", mission.pax)
        StatCard("GS", mission.groundSpeed)
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HorizonColors.CardBg)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = HorizonColors.TextMuted,
            fontSize = 10.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = HorizonColors.TextPrimary,
            fontSize = 14.sp,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Bold
        )
    }
}
