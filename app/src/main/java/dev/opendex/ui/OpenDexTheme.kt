package dev.opendex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import dev.opendex.data.ThemeMode
import dev.opendex.data.Wallpaper

private val night = darkColorScheme(
    primary = Color(0xFF8CC2FF),
    onPrimary = Color(0xFF072546),
    primaryContainer = Color(0xFF1D3350),
    onPrimaryContainer = Color(0xFFE4F0FF),
    secondary = Color(0xFFA9C3E3),
    secondaryContainer = Color(0xFF233751),
    onSecondaryContainer = Color(0xFFDFEDFF),
    background = Color(0xFF0C121C),
    onBackground = Color(0xFFEEF3FB),
    surface = Color(0xFF141B26),
    surfaceContainerLowest = Color(0xFF0F151E),
    surfaceContainerLow = Color(0xFF17202D),
    surfaceContainer = Color(0xFF1C2636),
    surfaceContainerHigh = Color(0xFF232F42),
    surfaceContainerHighest = Color(0xFF2B394F),
    onSurface = Color(0xFFEEF3FB),
    onSurfaceVariant = Color(0xFF9EB0C8),
    outline = Color(0xFF6B809C),
    outlineVariant = Color(0xFF2D3B4F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3E0A0D),
    errorContainer = Color(0xFFB72B35),
    onErrorContainer = Color(0xFFFFFFFF),
)

private val day = lightColorScheme(
    primary = Color(0xFF1C549D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF203857),
    onPrimaryContainer = Color(0xFFE8F1FF),
    secondary = Color(0xFF47607E),
    secondaryContainer = Color(0xFFD6E5FA),
    onSecondaryContainer = Color(0xFF132C4B),
    background = Color(0xFFDDE7F5),
    onBackground = Color(0xFF111C2B),
    surface = Color(0xFFF8FAFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5FB),
    surfaceContainer = Color(0xFFE9EFF8),
    surfaceContainerHigh = Color(0xFFE1E9F5),
    surfaceContainerHighest = Color(0xFFD7E1F0),
    onSurface = Color(0xFF142030),
    onSurfaceVariant = Color(0xFF4F5E73),
    outline = Color(0xFF6B7E96),
    outlineVariant = Color(0xFFC2CFE0),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFD93025),
    onErrorContainer = Color.White,
)

@Composable
fun DesktopWallpaper(
    wallpaper: Wallpaper,
    dark: Boolean,
    customBitmap: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    if (wallpaper == Wallpaper.Custom && customBitmap != null) {
        Box(modifier.fillMaxSize()) {
            Image(
                bitmap = customBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    val effective = if (wallpaper == Wallpaper.Custom) Wallpaper.Mountain else wallpaper
    Canvas(modifier.fillMaxSize()) {
        drawPresetWallpaper(effective, dark)
    }
}

private fun DrawScope.drawPresetWallpaper(wallpaper: Wallpaper, dark: Boolean) {
    val w = size.width
    val h = size.height
    when (wallpaper) {
        Wallpaper.Mountain -> {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1022), Color(0xFF182A4D), Color(0xFF3A4E7A), Color(0xFF5D6A96)),
                ),
            )
            // Soft lunar glow
            val moonCenter = Offset(w * 0.76f, h * 0.24f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFFF4DC).copy(alpha = 0.34f), Color(0xFF93C5FD).copy(alpha = 0.10f), Color.Transparent),
                    center = moonCenter,
                    radius = minOf(w, h) * 0.40f,
                ),
                radius = minOf(w, h) * 0.40f,
                center = moonCenter,
            )
            drawCircle(Color(0xFFFFF7E6).copy(alpha = 0.88f), radius = minOf(w, h) * 0.036f, center = moonCenter)
            // Distant starlight speckles
            val stars = floatArrayOf(
                0.12f, 0.14f, 0.23f, 0.09f, 0.36f, 0.18f, 0.49f, 0.11f, 0.61f, 0.16f,
                0.86f, 0.12f, 0.92f, 0.26f, 0.18f, 0.28f, 0.43f, 0.25f, 0.57f, 0.07f,
            )
            for (i in stars.indices step 2) {
                drawCircle(
                    Color.White.copy(alpha = if (i % 4 == 0) 0.55f else 0.32f),
                    radius = (minOf(w, h) * 0.0022f).coerceAtLeast(1f),
                    center = Offset(w * stars[i], h * stars[i + 1]),
                )
            }
            // Layered mountain ridges
            drawMountainRidge(
                points = listOf(0f to 0.62f, 0.16f to 0.47f, 0.33f to 0.56f, 0.52f to 0.41f, 0.72f to 0.53f, 0.89f to 0.44f, 1f to 0.54f),
                color = Color(0xFF283A5E).copy(alpha = 0.88f),
            )
            drawMountainRidge(
                points = listOf(0f to 0.72f, 0.20f to 0.58f, 0.40f to 0.67f, 0.62f to 0.52f, 0.80f to 0.64f, 1f to 0.57f),
                color = Color(0xFF1A2844),
            )
            drawMountainRidge(
                points = listOf(0f to 0.82f, 0.14f to 0.73f, 0.34f to 0.79f, 0.55f to 0.69f, 0.76f to 0.78f, 1f to 0.70f),
                color = Color(0xFF0E1729),
            )
        }
        Wallpaper.Sunset -> {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFF1C1636), Color(0xFF5B2C52), Color(0xFFB84A5E), Color(0xFFF39C6B)),
                ),
            )
            val sunCenter = Offset(w * 0.50f, h * 0.56f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFFE4A3).copy(alpha = 0.65f), Color(0xFFFF8C69).copy(alpha = 0.25f), Color.Transparent),
                    center = sunCenter,
                    radius = minOf(w, h) * 0.48f,
                ),
                radius = minOf(w, h) * 0.48f,
                center = sunCenter,
            )
            drawCircle(Color(0xFFFFE9B8).copy(alpha = 0.90f), radius = minOf(w, h) * 0.08f, center = sunCenter)
            drawSmoothWave(0.60f, 0.06f, 0.18f, Color(0xFF542544).copy(alpha = 0.85f))
            drawSmoothWave(0.70f, 0.05f, 0.72f, Color(0xFF331832))
            drawSmoothWave(0.81f, 0.04f, 0.36f, Color(0xFF1C0E20))
        }
        Wallpaper.Aurora -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF070E1A), Color(0xFF0E1E36), Color(0xFF0C1829))))
            val r = maxOf(w, h) * 0.62f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF2DD4BF).copy(alpha = 0.32f), Color.Transparent), Offset(w * 0.76f, h * 0.22f), r),
                radius = r,
                center = Offset(w * 0.76f, h * 0.22f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF6366F1).copy(alpha = 0.28f), Color.Transparent), Offset(w * 0.24f, h * 0.34f), r * 0.9f),
                radius = r * 0.9f,
                center = Offset(w * 0.24f, h * 0.34f),
            )
            drawSmoothWave(0.74f, 0.05f, 0.25f, Color(0xFF0D1D30).copy(alpha = 0.85f))
            drawSmoothWave(0.84f, 0.04f, 0.70f, Color(0xFF08111E))
        }
        Wallpaper.Ocean -> {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF061524), Color(0xFF0B2C45), Color(0xFF124663)),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
            )
            val r = maxOf(w, h) * 0.58f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF38BDF8).copy(alpha = 0.30f), Color.Transparent), Offset(w * 0.22f, h * 0.20f), r),
                radius = r,
                center = Offset(w * 0.22f, h * 0.20f),
            )
            drawSmoothWave(0.62f, 0.06f, 0.30f, Color(0xFF0E3E5B).copy(alpha = 0.72f))
            drawSmoothWave(0.74f, 0.05f, 0.65f, Color(0xFF0A2E47).copy(alpha = 0.88f))
            drawSmoothWave(0.85f, 0.04f, 0.20f, Color(0xFF071F33))
        }
        Wallpaper.Forest -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1715), Color(0xFF122E27), Color(0xFF1F473A))))
            val r = maxOf(w, h) * 0.55f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF6EE7B7).copy(alpha = 0.24f), Color.Transparent), Offset(w * 0.75f, h * 0.24f), r),
                radius = r,
                center = Offset(w * 0.75f, h * 0.24f),
            )
            drawMountainRidge(
                points = listOf(0f to 0.65f, 0.22f to 0.52f, 0.45f to 0.61f, 0.68f to 0.48f, 0.88f to 0.58f, 1f to 0.53f),
                color = Color(0xFF15382E).copy(alpha = 0.90f),
            )
            drawMountainRidge(
                points = listOf(0f to 0.78f, 0.28f to 0.67f, 0.55f to 0.75f, 0.82f to 0.66f, 1f to 0.73f),
                color = Color(0xFF0B211B),
            )
        }
        Wallpaper.Dusk -> {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF151021), Color(0xFF2D1C42), Color(0xFF472544)),
                    start = Offset.Zero,
                    end = Offset(w, h),
                ),
            )
            val r = maxOf(w, h) * 0.60f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF472B6).copy(alpha = 0.24f), Color.Transparent), Offset(w * 0.80f, h * 0.22f), r),
                radius = r,
                center = Offset(w * 0.80f, h * 0.22f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF8B5CF6).copy(alpha = 0.24f), Color.Transparent), Offset(w * 0.20f, h * 0.78f), r * 0.85f),
                radius = r * 0.85f,
                center = Offset(w * 0.20f, h * 0.78f),
            )
        }
        Wallpaper.Solid, Wallpaper.Custom -> {
            drawRect(
                Brush.linearGradient(
                    if (dark) listOf(Color(0xFF111822), Color(0xFF16202E))
                    else listOf(Color(0xFFD6E2F2), Color(0xFFE6EEF8)),
                ),
            )
        }
    }
}

private fun DrawScope.drawMountainRidge(points: List<Pair<Float, Float>>, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0f, h)
        points.forEach { (px, py) -> lineTo(w * px, h * py) }
        lineTo(w, h)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawSmoothWave(baseY: Float, amp: Float, crestX: Float, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h * baseY)
        cubicTo(
            w * crestX, h * (baseY - amp),
            w * (1f - crestX * 0.65f), h * (baseY + amp),
            w, h * (baseY - amp * 0.35f),
        )
        lineTo(w, h)
        close()
    }
    drawPath(path, color)
}

@Composable
fun isThemeDark(mode: ThemeMode?): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.System -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun OpenDexTheme(mode: ThemeMode?, content: @Composable () -> Unit) {
    val dark = isThemeDark(mode)
    MaterialTheme(colorScheme = if (dark) night else day, content = content)
}
