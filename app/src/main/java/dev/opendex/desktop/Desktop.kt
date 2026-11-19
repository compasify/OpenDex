package dev.opendex.desktop

import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opendex.R
import dev.opendex.WorkspaceModel
import dev.opendex.data.DockStyle
import dev.opendex.data.Wallpaper
import dev.opendex.data.WorkspacePreferences
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.LaunchResult
import kotlin.math.roundToInt

private data class Notice(val message: Int, val fallback: InstalledApp? = null, val appName: String? = null)

@Composable
fun Desktop(
    preferences: WorkspacePreferences?, apps: List<InstalledApp>, caps: DesktopCapabilities,
    platform: AndroidDesktop, model: WorkspaceModel, saveError: Boolean, searchRequests: Int, changeHome: () -> Boolean,
) {
    if (preferences == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val external = caps.external
    // Seed the layout once for this window; moving it must not replace its open tools.
    val layoutIsExternal = rememberSaveable { external }
    var serialized by rememberSaveable {
        mutableStateOf(if (layoutIsExternal) preferences.externalWindows else preferences.localWindows)
    }
    // Only checkpoint after a gesture; the draft provides live drag/resize feedback.
    var gesture by remember(external) { mutableStateOf<List<DesktopWindow>?>(null) }
    val windows = gesture ?: remember(serialized) { decodeWindows(serialized) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var lastSearchRequest by remember { mutableIntStateOf(searchRequests) }
    LaunchedEffect(searchRequests) {
        if (lastSearchRequest != searchRequests) {
            drawer = !drawer
            lastSearchRequest = searchRequests
        }
    }
    var pendingTool by remember { mutableStateOf<Tool?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var note by rememberSaveable { mutableStateOf(preferences.note) }
    var noteDirty by remember { mutableStateOf(false) }
    LaunchedEffect(preferences.note) {
        if (!noteDirty) note = preferences.note
        if (note == preferences.note) noteDirty = false
    }
    var recent by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val densityDpi = (LocalDensity.current.density * 160).roundToInt()

    fun checkpoint(values: List<DesktopWindow>) {
        val encoded = values.encodeWindows()
        gesture = null
        if (serialized != encoded) {
            serialized = encoded
            model.save { windows(layoutIsExternal, values) }
        }
    }
    fun launch(app: InstalledApp, target: Int = caps.currentDisplay) {
        when (platform.open(app, target)) {
            LaunchResult.Requested -> {
                recent = ArrayList((listOf(app.id) + recent.filterNot { it == app.id }).take(6))
                drawer = false
                notice = null
            }
            LaunchResult.DisplayDisconnected -> notice = Notice(R.string.display_disconnected, app.takeIf { target != Display.DEFAULT_DISPLAY })
            LaunchResult.DisplayRejected -> notice = Notice(
                if (target != Display.DEFAULT_DISPLAY) R.string.external_launch_rejected else R.string.primary_launch_rejected,
                app.takeIf { target != Display.DEFAULT_DISPLAY },
            )
            LaunchResult.Unavailable -> notice = Notice(R.string.app_unavailable, appName = app.label)
        }
    }

    BackHandler(drawer || notice != null) { if (notice != null) notice = null else drawer = false }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopWallpaper(preferences.wallpaper)
        Column(
            // safeDrawing also covers the system caption in desktop windows.
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Escape && notice != null -> { notice = null; true }
                        e.key == Key.Escape && drawer -> { drawer = false; true }
                        else -> false
                    }
                },
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val area = Area(maxWidth.value, maxHeight.value)
                if (area.width > 0f && area.height > 0f) {
                    val openTool: (Tool) -> Unit = { drawer = false; checkpoint(windows.open(it, area)) }
                    LaunchedEffect(pendingTool, area) {
                        pendingTool?.let(openTool)
                        pendingTool = null
                    }
                    WorkspaceShortcuts(external, openTool, Modifier.align(Alignment.TopStart))
                    windows.filterNot { it.minimized }.forEach { window ->
                        key(window.tool) {
                            ToolWindow(
                                window = window, area = area,
                                active = windows.lastOrNull { !it.minimized }?.tool == window.tool,
                                onActivate = { if (gesture == null) checkpoint(windows.activate(window.tool)) },
                                onTransform = { dx, dy, resize ->
                                    val base = gesture ?: windows
                                    val b = base.firstOrNull { it.tool == window.tool }?.bounds(area) ?: window.bounds(area)
                                    val target = if (resize) b.resize(dx, dy, area) else b.move(dx, dy, area)
                                    gesture = base.activate(window.tool).map {
                                        if (it.tool == window.tool) it.copy(floating = target, placement = Placement.Floating) else it
                                    }
                                },
                                onTransformEnd = { gesture?.let(::checkpoint) },
                                onPlacement = { placement -> checkpoint(windows.activate(window.tool).map { if (it.tool == window.tool) it.copy(placement = placement) else it }) },
                                onMinimize = { checkpoint(windows.map { if (it.tool == window.tool) it.copy(minimized = true) else it }) },
                                onClose = { checkpoint(windows.filterNot { it.tool == window.tool }) },
                            ) {
                                when (window.tool) {
                                    Tool.Notes -> NotesPanel(note) { value ->
                                        note = value
                                        noteDirty = value != preferences.note
                                        model.save { note(value) }
                                    }
                                    Tool.Settings -> SettingsPanel(
                                        preferences, caps, area.width.roundToInt(), area.height.roundToInt(), densityDpi,
                                        onTheme = { model.save { theme(it) } },
                                        onWallpaper = { model.save { wallpaper(it) } },
                                        onDock = { model.save { dock(it) } },
                                        onHome = {
                                            if (!changeHome()) notice = Notice(R.string.home_settings_unavailable)
                                        },
                                        onScreen = { id ->
                                            notice = when (platform.openWorkspaceOn(id)) {
                                                LaunchResult.Requested -> null
                                                LaunchResult.DisplayDisconnected -> Notice(R.string.display_disconnected)
                                                LaunchResult.DisplayRejected, LaunchResult.Unavailable -> Notice(R.string.workspace_launch_rejected)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (drawer) {
                        AppDrawer(
                            apps, preferences.pins, onOpen = { launch(it) }, onTogglePin = { app -> model.save { togglePin(app.id) } },
                            onTool = openTool, onClose = { drawer = false },
                            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    if (notice != null || saveError) {
                        val item = notice
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).widthIn(max = 520.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(when {
                                    item?.appName != null -> stringResource(item.message, item.appName)
                                    item != null -> stringResource(item.message)
                                    else -> stringResource(R.string.workspace_save_error)
                                })
                                Row {
                                    item?.fallback?.let { app -> TextButton(onClick = { launch(app, Display.DEFAULT_DISPLAY) }) { Text(stringResource(R.string.open_on_primary)) } }
                                    TextButton(onClick = { if (item != null) notice = null else model.dismissSaveError() }) { Text(stringResource(R.string.close)) }
                                }
                            }
                        }
                    }
                }
            }
            Dock(
                apps, preferences.pins, recent, windows, preferences.dock, drawer, external,
                onDrawer = { drawer = !drawer }, onOpen = { launch(it) },
                onPin = { app -> model.save { togglePin(app.id) } },
                onTool = { tool ->
                    // Compute geometry inside the desktop, where the dock and insets are excluded.
                    pendingTool = tool
                },
            )
        }
    }
}

@Composable
private fun WorkspaceShortcuts(external: Boolean, onTool: (Tool) -> Unit, modifier: Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("OPENDEX", color = Color(0xFFEAF2FF), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (external) R.string.external_displays else R.string.workspace), color = Color(0xFFB8C9E3), style = MaterialTheme.typography.labelSmall)
        }
        ToolButton(Icons.Default.EditNote, Tool.Notes.title, { onTool(Tool.Notes) }, desktop = true)
        ToolButton(Icons.Default.Settings, Tool.Settings.title, { onTool(Tool.Settings) }, desktop = true)
    }
}

@Composable
private fun DesktopWallpaper(wallpaper: Wallpaper) {
    val palette = when (wallpaper) {
        Wallpaper.Aurora -> listOf(Color(0xFF101620), Color(0xFF182D48), Color(0xFF203856))
        Wallpaper.Dusk -> listOf(Color(0xFF1B1722), Color(0xFF392644), Color(0xFF593451))
        Wallpaper.Solid -> listOf(Color(0xFF17202C), Color(0xFF17202C), Color(0xFF17202C))
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(palette, end = Offset(size.width, size.height)))
        if (wallpaper != Wallpaper.Solid) {
            drawOval(Color.White.copy(alpha = .045f), Offset(size.width * .36f, size.height * .38f), Size(size.width, size.height))
            drawOval(Color.White.copy(alpha = .035f), Offset(size.width * .51f, size.height * .53f), Size(size.width, size.height))
        }
    }
}
