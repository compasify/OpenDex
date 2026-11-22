package dev.opendex.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opendex.R
import dev.opendex.desktop.AppWindowState
import dev.opendex.desktop.DesktopWindow
import dev.opendex.desktop.decodeWindows
import dev.opendex.desktop.encodeAppWindows
import dev.opendex.desktop.encodeWindows
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val Context.workspaceData by preferencesDataStore(name = "workspace")

enum class Wallpaper(@get:StringRes val label: Int) {
    Mountain(R.string.wallpaper_mountain),
    Sunset(R.string.wallpaper_sunset),
    Aurora(R.string.wallpaper_aurora),
    Ocean(R.string.wallpaper_ocean),
    Forest(R.string.wallpaper_forest),
    Dusk(R.string.wallpaper_dusk),
    Solid(R.string.wallpaper_solid),
    Custom(R.string.wallpaper_custom),
}

enum class ThemeMode(@get:StringRes val label: Int) {
    Dark(R.string.theme_dark),
    Light(R.string.theme_light),
    System(R.string.theme_system),
}

enum class DockStyle(@get:StringRes val label: Int) {
    Floating(R.string.dock_floating),
    Panel(R.string.dock_panel),
}

const val MIN_DOCK_HEIGHT_DP = 32
const val MAX_DOCK_HEIGHT_DP = 68
const val DEFAULT_LOCAL_DOCK_HEIGHT_DP = 52
const val DEFAULT_EXTERNAL_DOCK_HEIGHT_DP = 40

const val MIN_OVERSCAN_PERCENT = 0
const val MAX_OVERSCAN_PERCENT = 12
const val DEFAULT_EXTERNAL_OVERSCAN_PERCENT = 0

internal fun computeOverscanInsetPx(
    physicalWidthPx: Int,
    physicalHeightPx: Int,
    percent: Int,
): Pair<Int, Int> {
    val clamped = percent.coerceIn(MIN_OVERSCAN_PERCENT, MAX_OVERSCAN_PERCENT)
    if (clamped <= 0 || physicalWidthPx <= 0 || physicalHeightPx <= 0) return 0 to 0
    val padX = (physicalWidthPx * clamped / 100f).roundToInt().coerceAtMost(physicalWidthPx / 4)
    val padY = (physicalHeightPx * clamped / 100f).roundToInt().coerceAtMost(physicalHeightPx / 4)
    return padX to padY
}

internal fun defaultDockHeightDp(external: Boolean): Int =
    if (external) DEFAULT_EXTERNAL_DOCK_HEIGHT_DP else DEFAULT_LOCAL_DOCK_HEIGHT_DP

data class DockMetrics(
    val heightDp: Int,
    val buttonDp: Int,
    val iconDp: Int,
    val toolIconDp: Int,
    val trayIconDp: Int,
    val floatingCornerDp: Int,
    val floatingVerticalPaddingDp: Int,
    val compactClock: Boolean,
)

/**
 * Computes proportional button, icon, corner, and padding sizes for a dock height in dp.
 */
internal fun computeDockMetrics(heightDp: Int): DockMetrics {
    val clamped = heightDp.coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP)
    val buttonDp = (clamped * 0.80f).roundToInt().coerceIn(26, 56)
    val iconDp = (buttonDp * 0.66f).roundToInt().coerceIn(18, 36)
    val toolIconDp = (buttonDp * 0.52f).roundToInt().coerceIn(15, 24)
    val trayIconDp = (buttonDp * 0.42f).roundToInt().coerceIn(13, 18)
    val floatingCornerDp = (clamped * 0.30f).roundToInt().coerceIn(10, 18)
    val floatingVerticalPaddingDp = when {
        clamped <= 40 -> 4
        clamped <= 48 -> 5
        else -> 6
    }
    return DockMetrics(
        heightDp = clamped,
        buttonDp = buttonDp,
        iconDp = iconDp,
        toolIconDp = toolIconDp,
        trayIconDp = trayIconDp,
        floatingCornerDp = floatingCornerDp,
        floatingVerticalPaddingDp = floatingVerticalPaddingDp,
        compactClock = clamped < 46,
    )
}

enum class DockAlignment(@get:StringRes val label: Int) {
    Center(R.string.dock_align_center),
    Start(R.string.dock_align_start),
}

enum class DisplayScale(
    @get:StringRes val label: Int,
    val scaleFactor: Float,
    val percent: Int,
) {
    MoreSpace(R.string.display_scale_more_space, 0.75f, 75),
    Compact(R.string.display_scale_compact, 0.85f, 85),
    Default(R.string.display_scale_default, 1.00f, 100),
    Large(R.string.display_scale_large, 1.15f, 115),
    Larger(R.string.display_scale_larger, 1.25f, 125),
}

enum class WindowScale(@get:StringRes val label: Int, val densityFactor: Float) {
    Compact(R.string.scale_compact, 0.85f),
    Standard(R.string.scale_standard, 1.0f),
    Comfortable(R.string.scale_comfortable, 1.20f),
}

enum class WindowResizeMode(@get:StringRes val label: Int) {
    Live(R.string.resize_mode_live),
    Outline(R.string.resize_mode_outline),
}

internal fun scaledDensity(nativeDensity: Float, scale: DisplayScale): Float =
    (nativeDensity * scale.scaleFactor).coerceIn(0.75f, 4.5f)

internal fun logicalResolution(
    physicalWidthPx: Int,
    physicalHeightPx: Int,
    nativeDensity: Float,
    scale: DisplayScale,
): Pair<Int, Int> {
    val effective = scaledDensity(nativeDensity, scale)
    return (physicalWidthPx / effective).roundToInt() to (physicalHeightPx / effective).roundToInt()
}

/**
 * Normalize high-DPI mobile/tablet touch densities down to desktop proportions inside hosted
 * windows while preserving native density on external monitors (<= 240 dpi), then apply the
 * active workspace display scale and window scale.
 */
internal fun computeHostedDensityDpi(
    baseDensityDpi: Int,
    scale: WindowScale,
    displayScale: DisplayScale = DisplayScale.Default,
): Int {
    val desktopBase = if (baseDensityDpi > 240) {
        (baseDensityDpi * 0.75f).toInt().coerceAtLeast(213)
    } else {
        baseDensityDpi
    }
    return (desktopBase * displayScale.scaleFactor * scale.densityFactor).roundToInt().coerceIn(120, 640)
}

data class WorkspacePreferences(
    val pins: List<String> = emptyList(),
    val desktopApps: List<String> = emptyList(),
    val note: String = "",
    val theme: ThemeMode = ThemeMode.Dark,
    val wallpaper: Wallpaper = Wallpaper.Mountain,
    val customWallpaperPath: String = "",
    val dock: DockStyle = DockStyle.Floating,
    val localDockHeightDp: Int = DEFAULT_LOCAL_DOCK_HEIGHT_DP,
    val externalDockHeightDp: Int = DEFAULT_EXTERNAL_DOCK_HEIGHT_DP,
    val dockAlignment: DockAlignment = DockAlignment.Center,
    val showClock: Boolean = true,
    val showSystemTray: Boolean = true,
    val showDesktopShortcuts: Boolean = true,
    val localDisplayScale: DisplayScale = DisplayScale.Default,
    val externalDisplayScale: DisplayScale = DisplayScale.Default,
    val externalOverscanPercent: Int = DEFAULT_EXTERNAL_OVERSCAN_PERCENT,
    val windowScale: WindowScale = WindowScale.Standard,
    val windowResizeMode: WindowResizeMode = WindowResizeMode.Live,
    val restoreOpenApps: Boolean = true,
    val pointerSpeed: Float = 1.2f,
    val localWindows: String = "",
    val externalWindows: String = "",
    val localAppWindows: String = "",
    val externalAppWindows: String = "",
) {
    fun displayScaleFor(external: Boolean): DisplayScale =
        if (external) externalDisplayScale else localDisplayScale

    fun dockHeightFor(external: Boolean): Int =
        (if (external) externalDockHeightDp else localDockHeightDp)
            .coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP)
}

class WorkspaceStore(context: Context) {
    private val store = context.applicationContext.workspaceData
    private val pins = stringPreferencesKey("pins")
    private val desktopApps = stringPreferencesKey("desktop_apps")
    private val note = stringPreferencesKey("note")
    private val theme = stringPreferencesKey("theme")
    private val wallpaper = stringPreferencesKey("wallpaper")
    private val customWallpaperPath = stringPreferencesKey("custom_wallpaper_path")
    private val dock = stringPreferencesKey("dock")
    private val legacyDockSize = stringPreferencesKey("dock_size")
    private val localDockHeightDp = intPreferencesKey("local_dock_height_dp")
    private val externalDockHeightDp = intPreferencesKey("external_dock_height_dp")
    private val dockAlignment = stringPreferencesKey("dock_alignment")
    private val showClock = booleanPreferencesKey("show_clock")
    private val showSystemTray = booleanPreferencesKey("show_system_tray")
    private val showDesktopShortcuts = booleanPreferencesKey("show_desktop_shortcuts")
    private val localDisplayScale = stringPreferencesKey("local_display_scale")
    private val externalDisplayScale = stringPreferencesKey("external_display_scale")
    private val externalOverscanPercent = intPreferencesKey("external_overscan_percent")
    private val windowScale = stringPreferencesKey("window_scale")
    private val windowResizeMode = stringPreferencesKey("window_resize_mode")
    private val restoreOpenApps = booleanPreferencesKey("restore_open_apps")
    private val pointerSpeed = floatPreferencesKey("pointer_speed")
    private val localWindows = stringPreferencesKey("local_windows")
    private val externalWindows = stringPreferencesKey("external_windows")
    private val localAppWindows = stringPreferencesKey("local_app_windows")
    private val externalAppWindows = stringPreferencesKey("external_app_windows")

    fun preferences(onReadError: (Boolean) -> Unit) = store.data.retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        onReadError(true)
        delay((attempt + 1).coerceAtMost(5) * 1_000)
        true
    }.map { p ->
        onReadError(false)
        val legacyLocalHeight = when (p[legacyDockSize]) {
            "Compact" -> 44
            "Large" -> 64
            else -> DEFAULT_LOCAL_DOCK_HEIGHT_DP
        }
        WorkspacePreferences(
            pins = p[pins].orEmpty().lineSequence().filter(String::isNotBlank).distinct().toList(),
            desktopApps = p[desktopApps].orEmpty().lineSequence().filter(String::isNotBlank).distinct().toList(),
            note = p[note].orEmpty(),
            theme = ThemeMode.entries.find { it.name == p[theme] } ?: ThemeMode.Dark,
            wallpaper = Wallpaper.entries.find { it.name == p[wallpaper] } ?: Wallpaper.Mountain,
            customWallpaperPath = p[customWallpaperPath].orEmpty(),
            dock = DockStyle.entries.find { it.name == p[dock] } ?: DockStyle.Floating,
            localDockHeightDp = (p[localDockHeightDp] ?: legacyLocalHeight).coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP),
            externalDockHeightDp = (p[externalDockHeightDp] ?: DEFAULT_EXTERNAL_DOCK_HEIGHT_DP).coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP),
            dockAlignment = DockAlignment.entries.find { it.name == p[dockAlignment] } ?: DockAlignment.Center,
            showClock = p[showClock] ?: true,
            showSystemTray = p[showSystemTray] ?: true,
            showDesktopShortcuts = p[showDesktopShortcuts] ?: true,
            localDisplayScale = DisplayScale.entries.find { it.name == p[localDisplayScale] } ?: DisplayScale.Default,
            externalDisplayScale = DisplayScale.entries.find { it.name == p[externalDisplayScale] } ?: DisplayScale.Default,
            externalOverscanPercent = (p[externalOverscanPercent] ?: DEFAULT_EXTERNAL_OVERSCAN_PERCENT)
                .coerceIn(MIN_OVERSCAN_PERCENT, MAX_OVERSCAN_PERCENT),
            windowScale = WindowScale.entries.find { it.name == p[windowScale] } ?: WindowScale.Standard,
            windowResizeMode = WindowResizeMode.entries.find { it.name == p[windowResizeMode] } ?: WindowResizeMode.Live,
            restoreOpenApps = p[restoreOpenApps] ?: true,
            pointerSpeed = (p[pointerSpeed] ?: 1.2f).coerceIn(0.4f, 3.0f),
            localWindows = p[localWindows].orEmpty(),
            externalWindows = p[externalWindows].orEmpty(),
            localAppWindows = p[localAppWindows].orEmpty(),
            externalAppWindows = p[externalAppWindows].orEmpty(),
        )
    }

    suspend fun togglePin(id: String) = toggleIdList(pins, id)
    suspend fun toggleDesktopApp(id: String) = toggleIdList(desktopApps, id)

    private suspend fun toggleIdList(key: Preferences.Key<String>, id: String) = store.edit { p ->
        val current = p[key].orEmpty().lines().filter(String::isNotBlank)
        p[key] = (if (id in current) current - id else current + id).joinToString("\n")
    }

    suspend fun note(value: String) = store.edit { it[note] = value }
    suspend fun theme(value: ThemeMode) = store.edit { it[theme] = value.name }
    suspend fun wallpaper(value: Wallpaper) = store.edit { it[wallpaper] = value.name }
    suspend fun customWallpaper(path: String) = store.edit {
        it[customWallpaperPath] = path
        it[wallpaper] = Wallpaper.Custom.name
    }
    suspend fun dock(value: DockStyle) = store.edit { it[dock] = value.name }
    suspend fun dockHeight(external: Boolean, heightDp: Int) = store.edit {
        it[if (external) externalDockHeightDp else localDockHeightDp] =
            heightDp.coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP)
    }
    suspend fun dockAlignment(value: DockAlignment) = store.edit { it[dockAlignment] = value.name }
    suspend fun showClock(value: Boolean) = store.edit { it[showClock] = value }
    suspend fun showSystemTray(value: Boolean) = store.edit { it[showSystemTray] = value }
    suspend fun showDesktopShortcuts(value: Boolean) = store.edit { it[showDesktopShortcuts] = value }
    suspend fun displayScale(external: Boolean, value: DisplayScale) = store.edit {
        it[if (external) externalDisplayScale else localDisplayScale] = value.name
    }
    suspend fun externalOverscan(percent: Int) = store.edit {
        it[externalOverscanPercent] = percent.coerceIn(MIN_OVERSCAN_PERCENT, MAX_OVERSCAN_PERCENT)
    }
    suspend fun windowScale(value: WindowScale) = store.edit { it[windowScale] = value.name }
    suspend fun windowResizeMode(value: WindowResizeMode) = store.edit { it[windowResizeMode] = value.name }
    suspend fun restoreOpenApps(value: Boolean) = store.edit { it[restoreOpenApps] = value }
    suspend fun pointerSpeed(value: Float) = store.edit { it[pointerSpeed] = value.coerceIn(0.4f, 3.0f) }

    suspend fun updateWindows(external: Boolean, update: (List<DesktopWindow>) -> List<DesktopWindow>) = store.edit {
        val key = if (external) externalWindows else localWindows
        it[key] = update(decodeWindows(it[key].orEmpty())).encodeWindows()
    }

    suspend fun saveAppWindows(external: Boolean, windows: List<AppWindowState>) = store.edit {
        val key = if (external) externalAppWindows else localAppWindows
        it[key] = windows.encodeAppWindows()
    }
}
