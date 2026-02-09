package com.llamafarm.atmosphere.horizon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** HORIZON color palette — dark military theme */
object HorizonColors {
    val Background = Color(0xFF0A0E14)
    val Surface = Color(0xFF111820)
    val SurfaceVariant = Color(0xFF1A2230)
    val CardBg = Color(0xFF141C28)

    val Accent = Color(0xFF3B82F6)       // Primary blue
    val AccentDim = Color(0xFF1E3A5F)

    // Pillar accents
    val AnomalyRed = Color(0xFFEF4444)
    val AnomalyOrange = Color(0xFFF97316)
    val AnomalyYellow = Color(0xFFEAB308)
    val AnomalyBlue = Color(0xFF3B82F6)

    val KnowledgePurple = Color(0xFFA855F7)
    val VoiceGreen = Color(0xFF22C55E)
    val AgentAmber = Color(0xFFF59E0B)
    val OsintCyan = Color(0xFF06B6D4)

    // Severity → color
    fun severityColor(s: AnomalySeverity): Color = when (s) {
        AnomalySeverity.CRITICAL -> AnomalyRed
        AnomalySeverity.WARNING -> AnomalyOrange
        AnomalySeverity.CAUTION -> AnomalyYellow
        AnomalySeverity.INFO -> AnomalyBlue
    }

    // Connectivity
    val Connected = Color(0xFF22C55E)
    val Degraded = Color(0xFFF59E0B)
    val Denied = Color(0xFFEF4444)
    val Offline = Color(0xFF6B7280)

    fun connectivityColor(c: HorizonConnectivity): Color = when (c) {
        HorizonConnectivity.CONNECTED -> Connected
        HorizonConnectivity.DEGRADED -> Degraded
        HorizonConnectivity.DENIED -> Denied
        HorizonConnectivity.OFFLINE -> Offline
    }

    val TextPrimary = Color(0xFFE5E7EB)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextMuted = Color(0xFF6B7280)
}

val MonoFont = FontFamily.Monospace
