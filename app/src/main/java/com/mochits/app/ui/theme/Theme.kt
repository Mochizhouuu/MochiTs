package com.mochits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Lavender Color Palette
val LavenderPrimary = Color(0xFF7E57C2) // Deep Lavender / Medium Purple
val LavenderOnPrimary = Color(0xFFFFFFFF)
val LavenderPrimaryContainer = Color(0xFFEDE7F6)
val LavenderOnPrimaryContainer = Color(0xFF321959)

val LavenderSecondary = Color(0xFF9575CD)
val LavenderOnSecondary = Color(0xFFFFFFFF)
val LavenderSecondaryContainer = Color(0xFFD1C4E9)
val LavenderOnSecondaryContainer = Color(0xFF261247)

val LavenderTertiary = Color(0xFFAB47BC)
val LavenderOnTertiary = Color(0xFFFFFFFF)

// Dark Theme Lavender Palette
val LavenderDarkPrimary = Color(0xFFB39DDB)
val LavenderDarkOnPrimary = Color(0xFF321461)
val LavenderDarkPrimaryContainer = Color(0xFF5E35B1)
val LavenderDarkOnPrimaryContainer = Color(0xFFEDE7F6)

val LavenderDarkSecondary = Color(0xFFD1C4E9)
val LavenderDarkOnSecondary = Color(0xFF381E6D)
val LavenderDarkSecondaryContainer = Color(0xFF512DA8)
val LavenderDarkOnSecondaryContainer = Color(0xFFEDE7F6)

val LavenderDarkBackground = Color(0xFF141218)
val LavenderDarkSurface = Color(0xFF1D1B24)
val LavenderDarkSurfaceVariant = Color(0xFF2D2938)

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
    background = Color(0xFFF9F8FD),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF3EFEF),
    onSurfaceVariant = Color(0xFF49454F)
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
    background = LavenderDarkBackground,
    surface = LavenderDarkSurface,
    surfaceVariant = LavenderDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCAC4D0)
)

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
