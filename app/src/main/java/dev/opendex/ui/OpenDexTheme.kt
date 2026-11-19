package dev.opendex.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.opendex.data.ThemeMode

private val night = darkColorScheme(
    primary = Color(0xFFA7CCFF), onPrimary = Color(0xFF092748),
    secondaryContainer = Color(0xFF253A55), onSecondaryContainer = Color(0xFFDFEDFF),
    background = Color(0xFF101721), onBackground = Color(0xFFEEF3FB),
    surface = Color(0xFF151D29), surfaceContainer = Color(0xFF1B2533),
    surfaceContainerHigh = Color(0xFF222E40), onSurface = Color(0xFFEEF3FB),
    onSurfaceVariant = Color(0xFFB6C2D4), outline = Color(0xFF8092AB),
    outlineVariant = Color(0xFF3B4B61),
)
private val day = lightColorScheme(
    primary = Color(0xFF214E88), onPrimary = Color.White,
    secondaryContainer = Color(0xFFD6E5F9), onSecondaryContainer = Color(0xFF152D4C),
    background = Color(0xFFDDE9FA), onBackground = Color(0xFF111D2D),
    surface = Color(0xFFF9FBFF), surfaceContainer = Color(0xFFEDF3FC),
    surfaceContainerHigh = Color(0xFFE5EDFA), onSurface = Color(0xFF172333),
    onSurfaceVariant = Color(0xFF434F61), outline = Color(0xFF5E7189),
    outlineVariant = Color(0xFFBAC8DA),
)

@Composable
fun OpenDexTheme(mode: ThemeMode?, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(colorScheme = if (dark) night else day, content = content)
}
