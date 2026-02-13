package com.llamafarm.atmosphere.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamafarm.atmosphere.ui.theme.*

// ============================================================================
// Transport Status Dot
// ============================================================================
@Composable
fun TransportDot(active: Boolean, label: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) TransportActive else TransportOff)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

// ============================================================================
// Dashboard Card
// ============================================================================
@Composable
fun DashCard(
    title: String,
    emoji: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Text(emoji, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ============================================================================
// Stat Row (label — value)
// ============================================================================
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
}

// ============================================================================
// Badge
// ============================================================================
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun GreenBadge(text: String) = StatusBadge(text, StatusGreen, StatusGreenBg)

@Composable
fun RedBadge(text: String) = StatusBadge(text, StatusRed, StatusRedBg)

@Composable
fun YellowBadge(text: String) = StatusBadge(text, StatusYellow, StatusYellowBg)

@Composable
fun BlueBadge(text: String) = StatusBadge(text, AccentBlue, Color(0x201F6FEB))

@Composable
fun PurpleBadge(text: String) = StatusBadge(text, StatusPurple, StatusPurpleBg)

@Composable
fun GrayBadge(text: String) = StatusBadge(text, StatusGray, StatusGrayBg)

// ============================================================================
// Monospace Text
// ============================================================================
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: Int = 13
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// ============================================================================
// Load Bar
// ============================================================================
@Composable
fun LoadBar(load: Float, modifier: Modifier = Modifier) {
    val color = when {
        load < 0.5f -> StatusGreen
        load < 0.8f -> StatusYellow
        else -> StatusRed
    }
    val animatedColor by animateColorAsState(color, label = "loadColor")

    Box(
        modifier = modifier
            .width(80.dp)
            .height(6.dp)
            .background(BorderSubtle, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(load.coerceIn(0f, 1f))
                .background(animatedColor, RoundedCornerShape(3.dp))
        )
    }
}

// ============================================================================
// Status Dot (inline)
// ============================================================================
@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "available", "connected", "online", "running" -> StatusGreen
        "degraded", "connecting" -> StatusYellow
        "unreachable", "disconnected", "error" -> StatusRed
        else -> StatusGray
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

// ============================================================================
// Filter Chip Row
// ============================================================================
@Composable
fun FilterChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isActive = option == selected
            Text(
                option.replaceFirstChar { it.uppercase() },
                modifier = Modifier
                    .background(
                        if (isActive) AccentBlueDim else CardBackground,
                        RoundedCornerShape(16.dp)
                    )
                    .border(
                        1.dp,
                        if (isActive) AccentBlueDim else BorderColor,
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (isActive) TextWhite else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============================================================================
// Section Header
// ============================================================================
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )
}

// ============================================================================
// Empty State
// ============================================================================
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}
