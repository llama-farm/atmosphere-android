package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import java.text.SimpleDateFormat
import java.util.*

private val CHANNELS = listOf("ALL", "HF", "UHF", "SATCOM", "INTERCOM")

@Composable
fun VoiceScreen(
    transcripts: List<VoiceTranscript>,
    monitoring: Boolean,
    onToggleMonitoring: () -> Unit
) {
    var selectedChannel by remember { mutableStateOf("ALL") }

    val filtered = remember(transcripts, selectedChannel) {
        if (selectedChannel == "ALL") transcripts
        else transcripts.filter { it.channel.equals(selectedChannel, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        // Monitoring toggle + channel chips
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (monitoring) Icons.Default.GraphicEq else Icons.Default.MicOff,
                null,
                tint = if (monitoring) HorizonColors.VoiceGreen else HorizonColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (monitoring) "LIVE" else "STOPPED",
                color = if (monitoring) HorizonColors.VoiceGreen else HorizonColors.TextMuted,
                fontSize = 11.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = monitoring,
                onCheckedChange = { onToggleMonitoring() },
                colors = SwitchDefaults.colors(checkedThumbColor = HorizonColors.VoiceGreen)
            )
        }

        // Channel filter
        LazyRow(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(CHANNELS) { ch ->
                FilterChip(
                    selected = ch == selectedChannel,
                    onClick = { selectedChannel = ch },
                    label = { Text(ch, fontSize = 11.sp, fontFamily = MonoFont) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HorizonColors.VoiceGreen.copy(alpha = 0.2f),
                        selectedLabelColor = HorizonColors.VoiceGreen
                    )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Transcript list
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(filtered, key = { it.id }) { tx ->
                TranscriptRow(tx)
            }
        }
    }
}

@Composable
private fun TranscriptRow(tx: VoiceTranscript) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val isPriority = tx.priority != "normal"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPriority) HorizonColors.AnomalyRed.copy(alpha = 0.08f) else HorizonColors.CardBg)
            .padding(8.dp)
    ) {
        // Time + channel
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(60.dp)) {
            Text(fmt.format(Date(tx.timestamp)), color = HorizonColors.TextMuted, fontSize = 10.sp, fontFamily = MonoFont)
            Text(tx.channel, color = HorizonColors.VoiceGreen, fontSize = 9.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tx.speaker, color = HorizonColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (isPriority) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tx.priority.uppercase(),
                        color = HorizonColors.AnomalyRed,
                        fontSize = 9.sp,
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(HorizonColors.AnomalyRed.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(tx.text, color = HorizonColors.TextSecondary, fontSize = 12.sp)
            if (tx.keywords.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tx.keywords.take(4).forEach { kw ->
                        Text(
                            kw,
                            color = HorizonColors.OsintCyan,
                            fontSize = 9.sp,
                            fontFamily = MonoFont,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(HorizonColors.OsintCyan.copy(alpha = 0.12f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
