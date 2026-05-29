package com.vtbatch.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Theme — defines the color palette and text styles for the entire app.
// Material3 is Google's design system (pre-built components + theming rules).
// isSystemInDarkTheme() automatically detects if Windows is in dark mode.

// Color definitions — Color(0xFF_RR_GG_BB) format
// The "FF" is alpha (fully opaque)
object AppColors {
    // Primary brand
    val Blue600 = Color(0xFF1E88E5)
    val Blue700 = Color(0xFF1976D2)
    val Blue200 = Color(0xFF90CAF9)

    // Status colors
    val CleanGreen = Color(0xFF4CAF50)
    val SuspiciousOrange = Color(0xFFFF9800)
    val MaliciousRed = Color(0xFFF44336)
    val ErrorRed = Color(0xFFB71C1C)
    val NeutralGray = Color(0xFF78909C)

    // Dark theme surfaces
    val DarkSurface = Color(0xFF1E1E2E)
    val DarkSurfaceVariant = Color(0xFF2A2A3C)
    val DarkBackground = Color(0xFF181825)

    // Light theme surfaces
    val LightSurface = Color(0xFFFFFBFE)
    val LightSurfaceVariant = Color(0xFFF5F5F5)
    val LightBackground = Color(0xFFF0F0F0)
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Blue200,
    secondary = AppColors.CleanGreen,
    surface = AppColors.DarkSurface,
    surfaceVariant = AppColors.DarkSurfaceVariant,
    background = AppColors.DarkBackground,
    error = AppColors.ErrorRed,
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFBDBDBD),
    onBackground = Color(0xFFE0E0E0),
)

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Blue600,
    secondary = AppColors.CleanGreen,
    surface = AppColors.LightSurface,
    surfaceVariant = AppColors.LightSurfaceVariant,
    background = AppColors.LightBackground,
    error = AppColors.ErrorRed,
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F),
    onBackground = Color(0xFF1C1B1F),
)

@Composable
fun VTBatchTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        // typography uses Material3 defaults — will customize later if needed
        content = content
    )
}
