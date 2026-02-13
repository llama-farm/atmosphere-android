package com.llamafarm.atmosphere.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.network.LogEntry
import com.llamafarm.atmosphere.ui.components.*
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsScreen(viewModel: MeshDebugViewModel) {
    val logs by viewModel.logs.collectAsState()
    val filter by viewModel.logFilter.collectAsState()
    val paused by viewModel.logPaused.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filtered = if (filter == "all") logs else logs.filter { it.level == filter }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBackground)
    ) {
        // Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChipRow(
                options = listOf("all", "info", "warn", "error"),
                selected = filter,
                onSelect = { viewModel.setLogFilter(it) },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = { viewModel.toggleLogPause() },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(if (paused) "Resume" else "Pause", fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.clearLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        // Log count
        Text(
            "${filtered.size} entries",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Log view
        if (filtered.isEmpty()) {
            EmptyState("No logs")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                reverseLayout = false
            ) {
                items(filtered, key = { "${it.timestamp}:${it.message.hashCode()}" }) { entry ->
                    LogEntryRow(entry)
                }
            }

            // Auto-scroll to bottom
            LaunchedEffect(filtered.size) {
                if (!paused && filtered.isNotEmpty()) {
                    scope.launch {
                        listState.animateScrollToItem(filtered.size - 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val levelColor = when (entry.level) {
        "info" -> AccentBlue
        "warn" -> StatusYellow
        "error" -> StatusRed
        "debug" -> TextMuted
        else -> TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Timestamp
        Text(
            fmt.format(Date(entry.timestamp)),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextMuted
        )

        // Level
        Text(
            entry.level.uppercase().padEnd(5),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = levelColor,
            fontWeight = FontWeight.SemiBold
        )

        // Message
        Text(
            "[${entry.source}] ${entry.message}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 3
        )
    }
}
