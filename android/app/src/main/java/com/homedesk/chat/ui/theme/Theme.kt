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
 * Desk lamp palette: deep navy pages, warm amber actions, blue for links.
 * Green, amber, and red are reserved for connection status.
 */
data class DeskChrome(
    val navBar: Color,
    val navIndicator: Color,
    val navSelected: Color,
    val navUnselected: Color,
    val success: Color,
    val warn: Color,
)

private val DarkChrome = DeskChrome(
    navBar = Color(0xFF12182A),
    navIndicator = Color(0xFF3D2C14),
    navSelected = Color(0xFFF2B15A),
    navUnselected = Color(0xFF9AA6BA),
    success = Color(0xFF3DDC97),
    warn = Color(0xFFF0C14A),
)

private val LightChrome = DeskChrome(
    navBar = Color(0xFFFFF9F1),
    navIndicator = Color(0xFFF8E2C0),
    navSelected = Color(0xFF8C5A16),
    navUnselected = Color(0xFF5C6573),
    success = Color(0xFF0E7A45),
    warn = Color(0xFF8A5A00),
)

val LocalChrome = staticCompositionLocalOf { DarkChrome }

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2B15A),
    onPrimary = Color(0xFF2A1C08),
    primaryContainer = Color(0xFF3D2C14),
    onPrimaryContainer = Color(0xFFF8E2C2),
    secondary = Color(0xFF8EB4FF),
    onSecondary = Color(0xFF0C1B33),
    secondaryContainer = Color(0xFF1C3054),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF3DDC97),
    onTertiary = Color(0xFF063222),
    background = Color(0xFF0C1222),
    onBackground = Color(0xFFF4F1EA),
    surface = Color(0xFF161E30),
    onSurface = Color(0xFFF4F1EA),
    surfaceVariant = Color(0xFF243044),
    onSurfaceVariant = Color(0xFFB7C0D0),
    surfaceBright = Color(0xFF2A3850),
    surfaceDim = Color(0xFF101726),
    surfaceContainerLowest = Color(0xFF0A101C),
    surfaceContainerLow = Color(0xFF1C2638),
    surfaceContainer = Color(0xFF1A2333),
    surfaceContainerHigh = Color(0xFF243044),
    surfaceContainerHighest = Color(0xFF2E3C52),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0002),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3D4A63),
    outlineVariant = Color(0xFF2C3850),
    scrim = Color(0xCC000000),
    inverseSurface = Color(0xFFF4F1EA),
    inverseOnSurface = Color(0xFF1C1915),
    inversePrimary = Color(0xFF8C5A16),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8C5A16),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8E2C0),
    onPrimaryContainer = Color(0xFF3A2508),
    secondary = Color(0xFF2F5DA8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF0C1B33),
    tertiary = Color(0xFF0E7A45),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F3EC),
    onBackground = Color(0xFF1C1915),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1915),
    surfaceVariant = Color(0xFFF1E7D6),
    onSurfaceVariant = Color(0xFF5C5348),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEFE6D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF9F1),
    surfaceContainer = Color(0xFFF8F1E6),
    surfaceContainerHigh = Color(0xFFF1E7D6),
    surfaceContainerHighest = Color(0xFFE8DCC8),
    error = Color(0xFF9B1B1B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF8A7B68),
    outlineVariant = Color(0xFFE0D3C0),
    scrim = Color(0x66000000),
    inverseSurface = Color(0xFF2C261F),
    inverseOnSurface = Color(0xFFF7F3EC),
    inversePrimary = Color(0xFFF2B15A),
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
