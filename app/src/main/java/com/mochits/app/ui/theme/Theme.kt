package com.mochits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// Lavender Color Palette — Complete Material 3 Schema
// ═══════════════════════════════════════════════════════════════

// Primary
val LavenderPrimary = Color(0xFF7E57C2)
val LavenderOnPrimary = Color(0xFFFFFFFF)
val LavenderPrimaryContainer = Color(0xFFEDE7F6)
val LavenderOnPrimaryContainer = Color(0xFF321959)

// Secondary
val LavenderSecondary = Color(0xFF9575CD)
val LavenderOnSecondary = Color(0xFFFFFFFF)
val LavenderSecondaryContainer = Color(0xFFD1C4E9)
val LavenderOnSecondaryContainer = Color(0xFF261247)

// Tertiary
val LavenderTertiary = Color(0xFFAB47BC)
val LavenderOnTertiary = Color(0xFFFFFFFF)
val LavenderTertiaryContainer = Color(0xFFF3E5F5)
val LavenderOnTertiaryContainer = Color(0xFF4A148C)

// Error
val LavenderError = Color(0xFFBA1A1A)
val LavenderOnError = Color(0xFFFFFFFF)
val LavenderErrorContainer = Color(0xFFFFDAD6)
val LavenderOnErrorContainer = Color(0xFF410E0B)

// Surface (Light)
val LavenderBackground = Color(0xFFF9F8FD)
val LavenderSurface = Color(0xFFFFFFFF)
val LavenderSurfaceVariant = Color(0xFFF3EFEF)
val LavenderSurfaceContainer = Color(0xFFF5F2FF)
val LavenderSurfaceContainerHigh = Color(0xFFEFE9FF)
val LavenderSurfaceContainerHighest = Color(0xFFE9E0FF)
val LavenderOnSurface = Color(0xFF1D1B20)
val LavenderOnSurfaceVariant = Color(0xFF49454F)
val LavenderOutline = Color(0xFF79747E)
val LavenderOutlineVariant = Color(0xFFCAC4D0)
val LavenderInverseSurface = Color(0xFF322935)
val LavenderInverseOnSurface = Color(0xFFF5EFF7)
val LavenderInversePrimary = Color(0xFFD0BCFF)

// Dark Theme Colors — Defined BEFORE scheme to avoid forward reference
val LavenderDarkPrimary = Color(0xFFD0BCFF)
val LavenderDarkOnPrimary = Color(0xFF381E72)
val LavenderDarkPrimaryContainer = Color(0xFF4F378B)
val LavenderDarkOnPrimaryContainer = Color(0xFFEDE7F6)
val LavenderDarkSecondary = Color(0xFFCCC2DC)
val LavenderDarkOnSecondary = Color(0xFF332D41)
val LavenderDarkSecondaryContainer = Color(0xFF4A4058)
val LavenderDarkOnSecondaryContainer = Color(0xFFE8DEF8)
val LavenderDarkTertiary = Color(0xFFEFB8C8)
val LavenderDarkOnTertiary = Color(0xFF492532)
val LavenderDarkTertiaryContainer = Color(0xFF633B48)
val LavenderDarkOnTertiaryContainer = Color(0xFFF9D8E4)
val LavenderDarkError = Color(0xFFFFB4AB)
val LavenderDarkOnError = Color(0xFF690005)
val LavenderDarkErrorContainer = Color(0xFF93000A)
val LavenderDarkOnErrorContainer = Color(0xFFFFDAD6)

// Surface (Dark)
val LavenderDarkBackground = Color(0xFF141218)
val LavenderDarkSurface = Color(0xFF1D1B24)
val LavenderDarkSurfaceVariant = Color(0xFF2D2938)
val LavenderDarkSurfaceContainer = Color(0xFF25232D)
val LavenderDarkSurfaceContainerHigh = Color(0xFF2F2C39)
val LavenderDarkSurfaceContainerHighest = Color(0xFF3A3744)
val LavenderDarkOnSurface = Color(0xFFE6E0E9)
val LavenderDarkOnSurfaceVariant = Color(0xFFCAC4D0)
val LavenderDarkOutline = Color(0xFF938F99)
val LavenderDarkOutlineVariant = Color(0xFF49454F)
val LavenderDarkInverseSurface = Color(0xFFE6E0E9)
val LavenderDarkInverseOnSurface = Color(0xFF322935)
val LavenderDarkInversePrimary = Color(0xFF7E57C2)

// ═══════════════════════════════════════════════════════════════
// Color Schemes
// ═══════════════════════════════════════════════════════════════

val LightLavenderColorScheme = lightColorScheme(
    primary = LavenderPrimary,
    onPrimary = LavenderOnPrimary,
    primaryContainer = LavenderPrimaryContainer,
    onPrimaryContainer = LavenderOnPrimaryContainer,
    secondary = LavenderSecondary,
    onSecondary = LavenderOnSecondary,
    secondaryContainer = LavenderSecondaryContainer,
    onSecondaryContainer = LavenderOnSecondaryContainer,
    tertiary = LavenderTertiary,
    onTertiary = LavenderOnTertiary,
    tertiaryContainer = LavenderTertiaryContainer,
    onTertiaryContainer = LavenderOnTertiaryContainer,
    error = LavenderError,
    onError = LavenderOnError,
    errorContainer = LavenderErrorContainer,
    onErrorContainer = LavenderOnErrorContainer,
    background = LavenderBackground,
    surface = LavenderSurface,
    onSurface = LavenderOnSurface,
    surfaceVariant = LavenderSurfaceVariant,
    onSurfaceVariant = LavenderOnSurfaceVariant,
    surfaceContainer = LavenderSurfaceContainer,
    surfaceContainerHigh = LavenderSurfaceContainerHigh,
    surfaceContainerHighest = LavenderSurfaceContainerHighest,
    outline = LavenderOutline,
    outlineVariant = LavenderOutlineVariant,
    inverseSurface = LavenderInverseSurface,
    inverseOnSurface = LavenderInverseOnSurface,
    inversePrimary = LavenderInversePrimary
)

val DarkLavenderColorScheme = darkColorScheme(
    primary = LavenderDarkPrimary,
    onPrimary = LavenderDarkOnPrimary,
    primaryContainer = LavenderDarkPrimaryContainer,
    onPrimaryContainer = LavenderDarkOnPrimaryContainer,
    secondary = LavenderDarkSecondary,
    onSecondary = LavenderDarkOnSecondary,
    secondaryContainer = LavenderDarkSecondaryContainer,
    onSecondaryContainer = LavenderDarkOnSecondaryContainer,
    tertiary = LavenderDarkTertiary,
    onTertiary = LavenderDarkOnTertiary,
    tertiaryContainer = LavenderDarkTertiaryContainer,
    onTertiaryContainer = LavenderDarkOnTertiaryContainer,
    error = LavenderDarkError,
    onError = LavenderDarkOnError,
    errorContainer = LavenderDarkErrorContainer,
    onErrorContainer = LavenderDarkOnErrorContainer,
    background = LavenderDarkBackground,
    surface = LavenderDarkSurface,
    onSurface = LavenderDarkOnSurface,
    surfaceVariant = LavenderDarkSurfaceVariant,
    onSurfaceVariant = LavenderDarkOnSurfaceVariant,
    surfaceContainer = LavenderDarkSurfaceContainer,
    surfaceContainerHigh = LavenderDarkSurfaceContainerHigh,
    surfaceContainerHighest = LavenderDarkSurfaceContainerHighest,
    outline = LavenderDarkOutline,
    outlineVariant = LavenderDarkOutlineVariant,
    inverseSurface = LavenderDarkInverseSurface,
    inverseOnSurface = LavenderDarkInverseOnSurface,
    inversePrimary = LavenderDarkInversePrimary
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

    val colorScheme = if (darkTheme) DarkLavenderColorScheme else LightLavenderColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
