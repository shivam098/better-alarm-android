package com.alarmy.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF6C5CE7)
private val IndigoLight = Color(0xFFB9AFFF)
private val Amber = Color(0xFFFFB74D)
private val DeepNight = Color(0xFF12101C)
private val NightSurface = Color(0xFF1D1A2B)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF1B1033),
    primaryContainer = Color(0xFF3B2E7A),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Amber,
    onSecondary = Color(0xFF3A2600),
    background = DeepNight,
    onBackground = Color(0xFFE9E6F5),
    surface = NightSurface,
    onSurface = Color(0xFFE9E6F5),
    surfaceVariant = Color(0xFF2A2640),
    onSurfaceVariant = Color(0xFFC6C0DC),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0A0A)
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF1B1033),
    secondary = Color(0xFFB35C00),
    background = Color(0xFFFBFAFF),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEAF6),
    error = Color(0xFFB3261E)
)

@Composable
fun BetterAlarmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

/**
 * The ringing screen is always dark regardless of the system setting.
 *
 * A white screen at 6am is physically painful and makes people look away from
 * the mission, which is the opposite of what this app is for.
 */
@Composable
fun RingingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

@Composable
fun currentActivity(): Activity? {
    var context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
