package com.llamafarm.atmosphere.horizon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HorizonScreen(
    atmosphereViewModel: AtmosphereViewModel,
    horizonViewModel: HorizonViewModel = viewModel()
) {
    // Initialize once
    LaunchedEffect(atmosphereViewModel) {
        horizonViewModel.initialize(atmosphereViewModel)
    }

    val selectedTab by horizonViewModel.selectedTab.collectAsState()
    val connectivity by horizonViewModel.connectivity.collectAsState()
    val mission by horizonViewModel.mission.collectAsState()
    val isStale by horizonViewModel.isStale.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(HorizonColors.Background)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(HorizonColors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand
            Text("HORIZON", color = HorizonColors.Accent, fontSize = 16.sp, fontFamily = MonoFont, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))

            // Callsign + aircraft
            Column(Modifier.weight(1f)) {
                Text(mission.callsign, color = HorizonColors.TextPrimary, fontSize = 13.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                Text("${mission.aircraft} · ${mission.route}", color = HorizonColors.TextSecondary, fontSize = 10.sp, fontFamily = MonoFont)
            }

            // Connectivity dot
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(HorizonColors.connectivityColor(connectivity))
            )
            Spacer(Modifier.width(4.dp))
            Text(
                connectivity.name,
                color = HorizonColors.connectivityColor(connectivity),
                fontSize = 9.sp, fontFamily = MonoFont
            )

            // Stale indicator
            if (isStale) {
                Spacer(Modifier.width(6.dp))
                Text("STALE", color = HorizonColors.AgentAmber, fontSize = 9.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            }
        }

        // ── Mission bar ──────────────────────────────────────────────────────
        MissionBar(mission, Modifier.background(HorizonColors.Surface.copy(alpha = 0.7f)))

        // ── Tab row ──────────────────────────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = HorizonColors.Surface,
            contentColor = HorizonColors.Accent,
            edgePadding = 0.dp,
            divider = {}
        ) {
            HorizonTab.entries.forEach { tab ->
                val accent = tabAccent(tab)
                Tab(
                    selected = selectedTab == tab,
                    onClick = { horizonViewModel.selectTab(tab) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                tabIcon(tab), null,
                                tint = if (selectedTab == tab) accent else HorizonColors.TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                tab.label.uppercase(),
                                fontFamily = MonoFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == tab) accent else HorizonColors.TextMuted
                            )
                        }
                    }
                )
            }
        }

        // ── Content ──────────────────────────────────────────────────────────
        Box(Modifier.fillMaxSize()) {
            when (selectedTab) {
                HorizonTab.ANOMALY -> {
                    val anomalies by horizonViewModel.anomalies.collectAsState()
                    val scanning by horizonViewModel.scanning.collectAsState()
                    AnomalyScreen(anomalies, scanning,
                        onRunScan = horizonViewModel::runScan,
                        onAcknowledge = horizonViewModel::acknowledgeAnomaly,
                        onResolve = horizonViewModel::resolveAnomaly
                    )
                }
                HorizonTab.KNOWLEDGE -> {
                    val result by horizonViewModel.knowledgeResult.collectAsState()
                    val suggestions by horizonViewModel.suggestions.collectAsState()
                    val loading by horizonViewModel.queryLoading.collectAsState()
                    KnowledgeScreen(result, suggestions, loading,
                        onQuery = horizonViewModel::queryKnowledge,
                        onLoadSuggestions = horizonViewModel::loadSuggestions
                    )
                }
                HorizonTab.VOICE -> {
                    val transcripts by horizonViewModel.transcripts.collectAsState()
                    val monitoring by horizonViewModel.voiceMonitoring.collectAsState()
                    VoiceScreen(transcripts, monitoring,
                        onToggleMonitoring = horizonViewModel::toggleVoiceMonitoring
                    )
                }
                HorizonTab.AGENT -> {
                    val hilItems by horizonViewModel.hilItems.collectAsState()
                    val handledItems by horizonViewModel.handledItems.collectAsState()
                    AgentScreen(hilItems, handledItems,
                        onApprove = horizonViewModel::approveHil,
                        onReject = horizonViewModel::rejectHil
                    )
                }
                HorizonTab.OSINT -> {
                    val brief by horizonViewModel.latestBrief.collectAsState()
                    val intelItems by horizonViewModel.intelItems.collectAsState()
                    val generating by horizonViewModel.briefGenerating.collectAsState()
                    OsintScreen(brief, intelItems, generating,
                        onGenerateBrief = horizonViewModel::generateBrief,
                        onLoadBrief = horizonViewModel::loadLatestBrief,
                        onSearch = horizonViewModel::searchIntel
                    )
                }
            }
        }
    }
}

private fun tabIcon(tab: HorizonTab): ImageVector = when (tab) {
    HorizonTab.ANOMALY -> Icons.Default.Warning
    HorizonTab.KNOWLEDGE -> Icons.Default.Psychology
    HorizonTab.VOICE -> Icons.Default.GraphicEq
    HorizonTab.AGENT -> Icons.Default.SmartToy
    HorizonTab.OSINT -> Icons.Default.Language
}

private fun tabAccent(tab: HorizonTab) = when (tab) {
    HorizonTab.ANOMALY -> HorizonColors.AnomalyRed
    HorizonTab.KNOWLEDGE -> HorizonColors.KnowledgePurple
    HorizonTab.VOICE -> HorizonColors.VoiceGreen
    HorizonTab.AGENT -> HorizonColors.AgentAmber
    HorizonTab.OSINT -> HorizonColors.OsintCyan
}
