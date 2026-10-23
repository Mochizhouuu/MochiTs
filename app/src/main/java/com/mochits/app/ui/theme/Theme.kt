package com.mochits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// MochiTs Blue Palette - selaras logo biru + putih
// ═══════════════════════════════════════════════════════════════

// Primary
val BluePrimary = Color(0xFF0A6EF0)
val BlueOnPrimary = Color(0xFFFFFFFF)
val BluePrimaryContainer = Color(0xFFD9E7FF)
val BlueOnPrimaryContainer = Color(0xFF0B2B54)

// Secondary
val BlueSecondary = Color(0xFF3F6EA8)
val BlueOnSecondary = Color(0xFFFFFFFF)
val BlueSecondaryContainer = Color(0xFFD7E3FF)
val BlueOnSecondaryContainer = Color(0xFF0D2A4A)

// Tertiary
val BlueTertiary = Color(0xFF0077B6)
val BlueOnTertiary = Color(0xFFFFFFFF)
val BlueTertiaryContainer = Color(0xFFD0ECFF)
val BlueOnTertiaryContainer = Color(0xFF00344F)

// Error
val BlueError = Color(0xFFBA1A1A)
val BlueOnError = Color(0xFFFFFFFF)
val BlueErrorContainer = Color(0xFFFFDAD6)
val BlueOnErrorContainer = Color(0xFF410E0B)

// Surface (Light)
val BlueBackground = Color(0xFFF7F9FF)
val BlueSurface = Color(0xFFFFFFFF)
val BlueSurfaceVariant = Color(0xFFEEF2F9)
val BlueSurfaceContainer = Color(0xFFF1F5FC)
val BlueSurfaceContainerHigh = Color(0xFFE9F0FA)
val BlueSurfaceContainerHighest = Color(0xFFE1EAF7)
val BlueOnSurface = Color(0xFF111827)
val BlueOnSurfaceVariant = Color(0xFF465366)
val BlueOutline = Color(0xFF6F7B8F)
val BlueOutlineVariant = Color(0xFFC6D0DF)
val BlueInverseSurface = Color(0xFF28313D)
val BlueInverseOnSurface = Color(0xFFEDF2FA)
val BlueInversePrimary = Color(0xFFA9C7FF)

// Dark Theme Colors
val BlueDarkPrimary = Color(0xFFA9C7FF)
val BlueDarkOnPrimary = Color(0xFF002E63)
val BlueDarkPrimaryContainer = Color(0xFF0B4A8F)
val BlueDarkOnPrimaryContainer = Color(0xFFD9E7FF)
val BlueDarkSecondary = Color(0xFFB4C7E7)
val BlueDarkOnSecondary = Color(0xFF1F3A5F)
val BlueDarkSecondaryContainer = Color(0xFF2A4468)
val BlueDarkOnSecondaryContainer = Color(0xFFD7E3FF)
val BlueDarkTertiary = Color(0xFF80D4F5)
val BlueDarkOnTertiary = Color(0xFF003544)
val BlueDarkTertiaryContainer = Color(0xFF004D63)
val BlueDarkOnTertiaryContainer = Color(0xFFD0ECFF)
val BlueDarkError = Color(0xFFFFB4AB)
val BlueDarkOnError = Color(0xFF690005)
val BlueDarkErrorContainer = Color(0xFF93000A)
val BlueDarkOnErrorContainer = Color(0xFFFFDAD6)

// Surface (Dark)
val BlueDarkBackground = Color(0xFF0F141D)
val BlueDarkSurface = Color(0xFF151C27)
val BlueDarkSurfaceVariant = Color(0xFF263243)
val BlueDarkSurfaceContainer = Color(0xFF1C2532)
val BlueDarkSurfaceContainerHigh = Color(0xFF243042)
val BlueDarkSurfaceContainerHighest = Color(0xFF2C3A4E)
val BlueDarkOnSurface = Color(0xFFE5ECF6)
val BlueDarkOnSurfaceVariant = Color(0xFFBDC9DA)
val BlueDarkOutline = Color(0xFF8796AA)
val BlueDarkOutlineVariant = Color(0xFF3C4A5D)
val BlueDarkInverseSurface = Color(0xFFE5ECF6)
val BlueDarkInverseOnSurface = Color(0xFF28313D)
val BlueDarkInversePrimary = Color(0xFF0A6EF0)

// ═══════════════════════════════════════════════════════════════
// Color Schemes
// ═══════════════════════════════════════════════════════════════

val LightBlueColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = BlueSecondary,
    onSecondary = BlueOnSecondary,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,
    tertiary = BlueTertiary,
    onTertiary = BlueOnTertiary,
    tertiaryContainer = BlueTertiaryContainer,
    onTertiaryContainer = BlueOnTertiaryContainer,
    error = BlueError,
    onError = BlueOnError,
    errorContainer = BlueErrorContainer,
    onErrorContainer = BlueOnErrorContainer,
    background = BlueBackground,
    surface = BlueSurface,
    onSurface = BlueOnSurface,
    surfaceVariant = BlueSurfaceVariant,
    onSurfaceVariant = BlueOnSurfaceVariant,
    surfaceContainer = BlueSurfaceContainer,
    surfaceContainerHigh = BlueSurfaceContainerHigh,
    surfaceContainerHighest = BlueSurfaceContainerHighest,
    outline = BlueOutline,
    outlineVariant = BlueOutlineVariant,
    inverseSurface = BlueInverseSurface,
    inverseOnSurface = BlueInverseOnSurface,
    inversePrimary = BlueInversePrimary
)

val DarkBlueColorScheme = darkColorScheme(
    primary = BlueDarkPrimary,
    onPrimary = BlueDarkOnPrimary,
    primaryContainer = BlueDarkPrimaryContainer,
    onPrimaryContainer = BlueDarkOnPrimaryContainer,
    secondary = BlueDarkSecondary,
    onSecondary = BlueDarkOnSecondary,
    secondaryContainer = BlueDarkSecondaryContainer,
    onSecondaryContainer = BlueDarkOnSecondaryContainer,
    tertiary = BlueDarkTertiary,
    onTertiary = BlueDarkOnTertiary,
    tertiaryContainer = BlueDarkTertiaryContainer,
    onTertiaryContainer = BlueDarkOnTertiaryContainer,
    error = BlueDarkError,
    onError = BlueDarkOnError,
    errorContainer = BlueDarkErrorContainer,
    onErrorContainer = BlueDarkOnErrorContainer,
    background = BlueDarkBackground,
    surface = BlueDarkSurface,
    onSurface = BlueDarkOnSurface,
    surfaceVariant = BlueDarkSurfaceVariant,
    onSurfaceVariant = BlueDarkOnSurfaceVariant,
    surfaceContainer = BlueDarkSurfaceContainer,
    surfaceContainerHigh = BlueDarkSurfaceContainerHigh,
    surfaceContainerHighest = BlueDarkSurfaceContainerHighest,
    outline = BlueDarkOutline,
    outlineVariant = BlueDarkOutlineVariant,
    inverseSurface = BlueDarkInverseSurface,
    inverseOnSurface = BlueDarkInverseOnSurface,
    inversePrimary = BlueDarkInversePrimary
)

// ═══════════════════════════════════════════════════════════════
// Theme Mode & Composable
// ═══════════════════════════════════════════════════════════════

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun MochiTsTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkBlueColorScheme else LightBlueColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
