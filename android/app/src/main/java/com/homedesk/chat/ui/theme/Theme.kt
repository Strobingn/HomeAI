package com.homedesk.chat.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Greyscale chrome from Wildlife FieldOps: near-black pages, lifted cards,
 * light-gray primary in the dark theme, dark-gray primary in the light theme.
 * Status is conveyed with labels and icons; every surface stays grayscale.
 */
data class DeskChrome(
    val navBar: Color,
    val navIndicator: Color,
    val navSelected: Color,
    val navUnselected: Color,
    val success: Color,
)

private val DarkChrome = DeskChrome(
    navBar = Color(0xFF171717),
    navIndicator = Color(0xFF4A4A4A),
    navSelected = Color(0xFFF5F5F5),
    navUnselected = Color(0xFFBDBDBD),
    success = Color(0xFF999999),
)

private val LightChrome = DeskChrome(
    navBar = Color(0xFFEEEEEE),
    navIndicator = Color(0xFFE0E0E0),
    navSelected = Color(0xFF1C1C1C),
    navUnselected = Color(0xFF494949),
    success = Color(0xFF333333),
)

val LocalChrome = staticCompositionLocalOf { DarkChrome }

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0D0D0),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF333333),
    onPrimaryContainer = Color(0xFFE8E8E8),
    secondary = Color(0xFFE8E8E8),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFFB8B8B8),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFFE0E0E0),
    onTertiary = Color(0xFF111111),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF2C2C2C),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceBright = Color(0xFF404040),
    surfaceDim = Color(0xFF2A2A2A),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF2C2C2C),
    surfaceContainerHigh = Color(0xFF333333),
    surfaceContainerHighest = Color(0xFF404040),
    error = Color(0xFFCDCDCD),
    onError = Color(0xFF141414),
    errorContainer = Color(0xFF303030),
    onErrorContainer = Color(0xFFE5E5E5),
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF666666),
    scrim = Color(0xCC000000),
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF3A3A3A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A3A3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF2E2E2E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF494949),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEEEEEE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEEEEE),
    surfaceContainer = Color(0xFFE8E8E8),
    surfaceContainerHigh = Color(0xFFE3E3E3),
    surfaceContainerHighest = Color(0xFFDCDCDC),
    error = Color(0xFF464646),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE5E5E5),
    onErrorContainer = Color(0xFF161616),
    outline = Color(0xFF797979),
    outlineVariant = Color(0xFFC4C4C4),
    scrim = Color(0x66000000),
    inverseSurface = Color(0xFF313131),
    inverseOnSurface = Color(0xFFF6F6F6),
    inversePrimary = Color(0xFFD0D0D0),
)

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun DeskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalChrome provides if (darkTheme) DarkChrome else LightChrome) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

