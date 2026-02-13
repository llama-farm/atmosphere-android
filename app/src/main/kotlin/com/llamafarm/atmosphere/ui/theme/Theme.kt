package com.llamafarm.atmosphere.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DebuggerColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextWhite,
    primaryContainer = AccentBlueDim,
    onPrimaryContainer = TextWhite,
    secondary = StatusPurple,
    onSecondary = TextWhite,
    tertiary = StatusGreen,
    onTertiary = TextWhite,
    background = DashboardBackground,
    onBackground = TextPrimary,
    surface = CardBackground,
    onSurface = TextPrimary,
    surfaceVariant = CardBackgroundHover,
    onSurfaceVariant = TextSecondary,
    outline = BorderColor,
    outlineVariant = BorderSubtle,
    error = StatusRed,
    onError = TextWhite,
)

@Composable
fun AtmosphereTheme(
    darkTheme: Boolean = true, // Always dark
    dynamicColor: Boolean = false, // Never dynamic — debugger aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = DebuggerColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DashboardBackground.toArgb()
            window.navigationBarColor = CardBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
