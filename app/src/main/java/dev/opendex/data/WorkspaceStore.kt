package dev.opendex.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.opendex.desktop.DesktopWindow
import dev.opendex.R
import dev.opendex.desktop.decodeWindows
import dev.opendex.desktop.encodeWindows
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val Context.workspaceData by preferencesDataStore(name = "workspace")

enum class Wallpaper(@StringRes val label: Int) { Aurora(R.string.wallpaper_aurora), Dusk(R.string.wallpaper_dusk), Solid(R.string.wallpaper_solid) }
enum class ThemeMode(@StringRes val label: Int) { Dark(R.string.theme_dark), Light(R.string.theme_light), System(R.string.theme_system) }
enum class DockStyle(@StringRes val label: Int) { Panel(R.string.dock_panel), Floating(R.string.dock_floating) }

data class WorkspacePreferences(
    val pins: List<String> = emptyList(),
    val note: String = "",
    val theme: ThemeMode = ThemeMode.Dark,
    val wallpaper: Wallpaper = Wallpaper.Aurora,
    val dock: DockStyle = DockStyle.Panel,
    val localWindows: String = "",
    val externalWindows: String = "",
)

class WorkspaceStore(context: Context) {
    private val store = context.applicationContext.workspaceData
    private val pins = stringPreferencesKey("pins")
    private val note = stringPreferencesKey("note")
    private val theme = stringPreferencesKey("theme")
    private val wallpaper = stringPreferencesKey("wallpaper")
    private val dock = stringPreferencesKey("dock")
    private val localWindows = stringPreferencesKey("local_windows")
    private val externalWindows = stringPreferencesKey("external_windows")

    fun preferences(onReadError: (Boolean) -> Unit) = store.data.retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        onReadError(true)
        delay((attempt + 1).coerceAtMost(5) * 1_000)
        true
    }.map { p ->
        onReadError(false)
        WorkspacePreferences(
            pins = p[pins].orEmpty().lineSequence().filter(String::isNotBlank).distinct().toList(),
            note = p[note].orEmpty(),
            theme = ThemeMode.entries.find { it.name == p[theme] } ?: ThemeMode.Dark,
            wallpaper = Wallpaper.entries.find { it.name == p[wallpaper] } ?: Wallpaper.Aurora,
            dock = DockStyle.entries.find { it.name == p[dock] } ?: DockStyle.Panel,
            localWindows = p[localWindows].orEmpty(), externalWindows = p[externalWindows].orEmpty(),
        )
    }

    suspend fun togglePin(id: String) = store.edit { p ->
        val current = p[pins].orEmpty().lines().filter(String::isNotBlank)
        p[pins] = (if (id in current) current - id else current + id).joinToString("\n")
    }

    suspend fun note(value: String) = store.edit { it[note] = value }
    suspend fun theme(value: ThemeMode) = store.edit { it[theme] = value.name }
    suspend fun wallpaper(value: Wallpaper) = store.edit { it[wallpaper] = value.name }
    suspend fun dock(value: DockStyle) = store.edit { it[dock] = value.name }
    suspend fun updateWindows(external: Boolean, update: (List<DesktopWindow>) -> List<DesktopWindow>) = store.edit {
        val key = if (external) externalWindows else localWindows
        it[key] = update(decodeWindows(it[key].orEmpty())).encodeWindows()
    }
}
