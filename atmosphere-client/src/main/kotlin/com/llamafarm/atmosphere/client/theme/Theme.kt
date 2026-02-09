package com.llamafarm.atmosphere.client.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Custom color palette
private val AtmosphereBlue = Color(0xFF2196F3)
private val AtmosphereCyan = Color(0xFF00BCD4)
private val AtmosphereDeepBlue = Color(0xFF0D47A1)
private val AtmosphereSkyBlue = Color(0xFFBBDEFB)

private val DarkColorScheme = darkColorScheme(
    primary = AtmosphereBlue,
    secondary = AtmosphereCyan,
    tertiary = AtmosphereSkyBlue,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

private val LightColorScheme = lightColorScheme(
    primary = AtmosphereBlue,
    secondary = AtmosphereCyan,
    tertiary = AtmosphereDeepBlue,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F0),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121)
)

@Composable
fun AtmosphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
