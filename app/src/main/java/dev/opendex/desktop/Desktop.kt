package dev.opendex.desktop

import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opendex.R
import dev.opendex.WorkspaceModel
import dev.opendex.data.DockAlignment
import dev.opendex.data.DockStyle
import dev.opendex.data.ThemeMode
import dev.opendex.data.WallpaperStorage
import dev.opendex.data.WorkspacePreferences
import dev.opendex.platform.AdbAccess
import dev.opendex.platform.AdbWindows
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.LaunchResult
import dev.opendex.platform.NotificationCenterStore
import dev.opendex.platform.SystemStatus
import dev.opendex.platform.SystemTrayController
import dev.opendex.platform.TrayNotification
import dev.opendex.ui.DesktopWallpaper
import dev.opendex.ui.isThemeDark
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class Notice(@get:StringRes val message: Int, val fallback: InstalledApp? = null, val appName: String? = null)

/**
 * A regular tap should feel like a desktop: when the ADB backend is authorized on the built-in
 * display and Android reports freeform support, open apps in floating windows by default.
 */
internal fun prefersFloatingOpen(access: AdbAccess, external: Boolean, freeformReported: Boolean): Boolean =
    access == AdbAccess.Granted && !external && freeformReported

/**
 * When the ADB backend supports hosted virtual-display windows and is authorized, open apps
 * directly inside OpenDex desktop windows (with title bar, minimize, maximize, close) on any display.
 */
internal fun prefersHostedWindowOpen(access: AdbAccess, supportsHostedWindows: Boolean): Boolean =
    access == AdbAccess.Granted && supportsHostedWindows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Desktop(
    preferences: WorkspacePreferences?,
    apps: List<InstalledApp>,
    appsLoaded: Boolean,
    caps: DesktopCapabilities,
    platform: AndroidDesktop,
    model: WorkspaceModel,
    saveError: Boolean,
    readError: Boolean,
    noteDraft: String?,
    searchRequests: Int,
    adbWindows: AdbWindows,
    adbAccess: AdbAccess,
    windowOrder: List<WindowKey> = emptyList(),
    systemStatus: SystemStatus = SystemStatus(),
    systemTray: SystemTrayController? = null,
    notifications: List<TrayNotification> = emptyList(),
    notificationAccessGranted: Boolean = false,
    onPickWallpaper: () -> Boolean = { false },
    onBrightnessChange: (Float) -> Unit = {},
    onMoveToBackground: () -> Unit = {},
    changeHome: () -> Boolean,
) {
    if (preferences == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            if (readError) Text(stringResource(R.string.read_error), Modifier.padding(16.dp)) else CircularProgressIndicator()
        }
        return
    }
    val context = LocalContext.current
    val external = caps.external
    // Seed each instance from the saved layout, then keep its open tools independent.
    val layoutIsExternal = rememberSaveable { external }
    var serialized by rememberSaveable {
        mutableStateOf(if (layoutIsExternal) preferences.externalWindows else preferences.localWindows)
    }
    val storedWindows = remember(serialized) { decodeWindows(serialized) }
    val appWindows by remember(model, layoutIsExternal) { model.appWindows(layoutIsExternal) }
        .collectAsStateWithLifecycle()

    val customWallpaperBitmap by produceState<ImageBitmap?>(initialValue = null, preferences.customWallpaperPath) {
        value = WallpaperStorage.loadBitmap(preferences.customWallpaperPath)
    }

    LaunchedEffect(layoutIsExternal) {
        val savedApps = if (layoutIsExternal) preferences.externalAppWindows else preferences.localAppWindows
        model.hydrateAppWindows(layoutIsExternal, savedApps, preferences.restoreOpenApps)
    }
    LaunchedEffect(appsLoaded, apps, layoutIsExternal) {
        if (appsLoaded) {
            model.pruneMissingApps(layoutIsExternal, apps.mapTo(HashSet()) { it.id })
        }
    }

    // Persist only the changed tool after a gesture so another session's tools are not overwritten.
    var gesture by remember(external) { mutableStateOf<DesktopWindow?>(null) }
    val windows = gesture?.let { draft -> storedWindows.filterNot { it.tool == draft.tool } + draft } ?: storedWindows
    var appGesture by remember { mutableStateOf<AppWindowState?>(null) }
    var appResizing by remember { mutableStateOf(false) }
    val activeAppWindows = appGesture?.let { draft ->
        appWindows.map { if (it.appId == draft.appId) draft else it }
    } ?: appWindows
    val appsById = remember(apps) { apps.associateBy { it.id } }

    var drawer by rememberSaveable { mutableStateOf(false) }
    var windowManagerOpen by rememberSaveable { mutableStateOf(false) }
    var quickSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    var desktopMenu by remember { mutableStateOf(false) }
    var focusSearchOnOpen by remember { mutableStateOf(false) }

    fun closePopovers() {
        drawer = false
        windowManagerOpen = false
        quickSettingsOpen = false
        notificationsOpen = false
    }

    fun toggleDrawer(focusSearch: Boolean) {
        val opening = !drawer
        closePopovers()
        focusSearchOnOpen = opening && focusSearch
        drawer = opening
    }

    fun toggleWindowManager() {
        val opening = !windowManagerOpen
        closePopovers()
        windowManagerOpen = opening
    }

    fun toggleQuickSettings() {
        val opening = !quickSettingsOpen
        closePopovers()
        quickSettingsOpen = opening
        if (opening) systemTray?.refresh()
    }

    fun toggleNotifications() {
        val opening = !notificationsOpen
        closePopovers()
        notificationsOpen = opening
    }

    var lastSearchRequest by remember { mutableIntStateOf(searchRequests) }
    LaunchedEffect(searchRequests) {
        if (lastSearchRequest != searchRequests) {
            toggleDrawer(focusSearch = true)
            lastSearchRequest = searchRequests
        }
    }
    var pendingTool by remember { mutableStateOf<Tool?>(null) }
    var pendingAppWindow by remember { mutableStateOf<InstalledApp?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var sendingFreeform by remember { mutableStateOf(false) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var recent by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    val baseDensityDpi = (LocalDensity.current.density * 160).roundToInt()
    val hostedDensityDpi = (baseDensityDpi * preferences.windowScale.densityFactor).roundToInt().coerceIn(120, 640)
    LaunchedEffect(hostedDensityDpi, layoutIsExternal) {
        model.rescaleOpenDisplays(layoutIsExternal, hostedDensityDpi)
    }

    var hadAdbGranted by remember { mutableStateOf(adbAccess == AdbAccess.Granted) }
    LaunchedEffect(adbAccess) {
        if (adbAccess == AdbAccess.Granted) {
            hadAdbGranted = true
        } else if (hadAdbGranted) {
            hadAdbGranted = false
            if (model.closeAllAppWindows(layoutIsExternal, persist = false)) {
                notice = Notice(R.string.adb_disconnected_windows)
            }
        }
    }

    fun updateWindows(update: (List<DesktopWindow>) -> List<DesktopWindow>) {
        val updated = update(decodeWindows(serialized)).encodeWindows()
        if (updated == serialized) return
        serialized = updated
        // Other running windows keep their own layouts; this only updates the next window's seed.
        model.editWindows(layoutIsExternal, update)
    }

    fun minimizeAllWindows() {
        updateWindows { it.minimizeAllTools() }
        model.minimizeAllAppWindows(layoutIsExternal)
    }

    fun recordLaunch(app: InstalledApp) {
        recent = (listOf(app.id) + recent.filterNot { it == app.id }).take(6)
        closePopovers()
        notice = null
    }
    fun launch(app: InstalledApp, target: Int = caps.currentDisplay, explicit: Boolean = false) {
        val outcome = if (!explicit && platform.currentDisplayId() != target) LaunchResult.DisplayDisconnected
            else platform.open(app, target)
        when (outcome) {
            LaunchResult.Requested -> recordLaunch(app)
            LaunchResult.DisplayDisconnected -> notice = Notice(R.string.display_disconnected, app.takeIf { target != Display.DEFAULT_DISPLAY })
            LaunchResult.DisplayRejected -> notice = Notice(
                if (target != Display.DEFAULT_DISPLAY) R.string.launch_display_rejected else R.string.launch_primary_rejected,
                app.takeIf { target != Display.DEFAULT_DISPLAY },
            )
            LaunchResult.Unavailable -> notice = Notice(R.string.launch_app_unavailable, appName = app.label)
        }
    }

    fun openAppInNativeFreeform(app: InstalledApp, bounds: IntRect? = null) {
        if (sendingFreeform) return
        val target = caps.currentDisplay
        if (platform.currentDisplayId() != target) {
            notice = Notice(R.string.display_disconnected)
        } else {
            sendingFreeform = true
            scope.launch {
                try {
                    val opened = adbWindows.open(app, target, bounds) {
                        platform.currentDisplayId() == target &&
                            (bounds == null || platform.boundsAboveDock(dockHeightPx) == bounds)
                    }
                    if (opened) recordLaunch(app)
                    else notice = Notice(if (bounds == null) R.string.adb_launch_failed else R.string.adb_fit_failed)
                } finally { sendingFreeform = false }
            }
        }
    }

    fun requestHostedAppWindow(app: InstalledApp) {
        recordLaunch(app)
        pendingAppWindow = app
    }

    val hostedOnTap = prefersHostedWindowOpen(adbAccess, adbWindows.supportsHostedWindows)
    val floatingOnTap = !hostedOnTap && prefersFloatingOpen(adbAccess, external, caps.freeform)
    val openApp: (InstalledApp) -> Unit = { app ->
        when {
            hostedOnTap -> requestHostedAppWindow(app)
            floatingOnTap -> openAppInNativeFreeform(app)
            else -> launch(app)
        }
    }
    val openInWindow: ((InstalledApp) -> Unit)? =
        if (!hostedOnTap && adbAccess == AdbAccess.Granted && adbWindows.supportsHostedWindows) { app -> requestHostedAppWindow(app) } else null
    val openFreeform: ((InstalledApp) -> Unit)? =
        if (adbAccess != AdbAccess.Granted || sendingFreeform || external || floatingOnTap || !caps.freeform) null
        else { app -> openAppInNativeFreeform(app) }
    val openFullscreen: ((InstalledApp) -> Unit)? = if (hostedOnTap || floatingOnTap) { app -> launch(app) } else null
    val fitAboveDock: ((InstalledApp) -> Unit)? = if ((hostedOnTap || floatingOnTap) && !external && caps.freeform && !sendingFreeform &&
        platform.boundsAboveDock(dockHeightPx) != null) { app ->
        val bounds = platform.boundsAboveDock(dockHeightPx)
        if (bounds != null) openAppInNativeFreeform(app, bounds) else notice = Notice(R.string.adb_fit_failed)
    } else null

    val visibleTools = windows.filterNot { it.minimized }
    val visibleApps = if (adbAccess == AdbAccess.Granted) {
        activeAppWindows.filterNot { it.minimized }.filter { it.appId in appsById }
    } else emptyList()
    val orderedKeys = remember(visibleTools, visibleApps, windowOrder) {
        val visibleSet = buildSet<WindowKey> {
            visibleTools.forEach { add(WindowKey.ToolKey(it.tool)) }
            visibleApps.forEach { add(WindowKey.AppKey(it.appId)) }
        }
        val known = windowOrder.filter { it in visibleSet }
        val knownSet = known.toSet()
        val extras = buildList<WindowKey> {
            visibleTools.forEach {
                val k = WindowKey.ToolKey(it.tool)
                if (k !in knownSet) add(k)
            }
            visibleApps.forEach {
                val k = WindowKey.AppKey(it.appId)
                if (k !in knownSet) add(k)
            }
        }
        extras + known
    }
    val activeKey = orderedKeys.lastOrNull()
    val runningAppIds = remember(appWindows) { appWindows.mapTo(HashSet()) { it.appId } }
    val darkTheme = isThemeDark(preferences.theme)
    val anyPopoverOpen = drawer || windowManagerOpen || quickSettingsOpen || notificationsOpen

    BackHandler(anyPopoverOpen || notice != null || !caps.home) {
        when {
            notice != null -> notice = null
            anyPopoverOpen -> closePopovers()
            !caps.home -> onMoveToBackground()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopWallpaper(
            wallpaper = preferences.wallpaper,
            dark = darkTheme,
            customBitmap = customWallpaperBitmap,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            // System bars are hidden in immersive mode; reserve only display cutout, caption bar, and IME insets.
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.captionBar).union(WindowInsets.ime))
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Escape && notice != null -> { notice = null; true }
                        e.key == Key.Escape && anyPopoverOpen -> { closePopovers(); true }
                        e.key == Key.K && e.nativeKeyEvent.hasModifiers(android.view.KeyEvent.META_CTRL_ON) -> {
                            if (e.nativeKeyEvent.repeatCount == 0) toggleDrawer(focusSearch = true)
                            true
                        }
                        else -> false
                    }
                },
        ) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .onSecondaryClick { desktopMenu = true }
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = { desktopMenu = true },
                        onClick = { if (anyPopoverOpen) closePopovers() },
                    ),
            ) {
                val area = Area(maxWidth.value, maxHeight.value)
                if (area.width > 0f && area.height > 0f) {
                    val openTool: (Tool) -> Unit = { tool ->
                        closePopovers()
                        updateWindows { it.open(tool, area) }
                        model.bringToFront(WindowKey.ToolKey(tool))
                    }
                    LaunchedEffect(pendingTool, area) {
                        pendingTool?.let(openTool)
                        pendingTool = null
                    }
                    LaunchedEffect(pendingAppWindow, area) {
                        pendingAppWindow?.let { app ->
                            model.openAppWindow(layoutIsExternal, app.id, area)
                        }
                        pendingAppWindow = null
                    }
                    if (preferences.showDesktopShortcuts) {
                        WorkspaceShortcuts(openTool, Modifier.align(Alignment.TopStart))
                    }
                    Box(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        DropdownMenu(expanded = desktopMenu, onDismissRequest = { desktopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.choose_wallpaper_image)) },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)) },
                                onClick = {
                                    desktopMenu = false
                                    if (!onPickWallpaper()) notice = Notice(R.string.wallpaper_picker_unavailable)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.desktop_settings)) },
                                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                                onClick = { desktopMenu = false; openTool(Tool.Settings) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Tool.Notes.title)) },
                                leadingIcon = { Icon(Icons.Default.EditNote, null, Modifier.size(18.dp)) },
                                onClick = { desktopMenu = false; openTool(Tool.Notes) },
                            )
                            if (windows.any { !it.minimized } || appWindows.any { !it.minimized }) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_desktop)) },
                                    leadingIcon = { Icon(Icons.Default.DesktopWindows, null, Modifier.size(18.dp)) },
                                    onClick = { desktopMenu = false; minimizeAllWindows() },
                                )
                            }
                            if (appWindows.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.close_all_windows), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                    onClick = { desktopMenu = false; model.closeAllAppWindows(layoutIsExternal) },
                                )
                            }
                        }
                    }

                    val toolsByKey = visibleTools.associateBy { it.tool }
                    val appsByWindowKey = visibleApps.associateBy { it.appId }
                    orderedKeys.forEach { winKey ->
                        when (winKey) {
                            is WindowKey.ToolKey -> {
                                val window = toolsByKey[winKey.tool] ?: return@forEach
                                key(winKey) {
                                    ToolWindow(
                                        window = window, area = area,
                                        active = activeKey == winKey,
                                        onActivate = {
                                            if (gesture == null && activeKey != winKey) {
                                                updateWindows { it.activate(window.tool) }
                                                model.bringToFront(winKey)
                                            }
                                        },
                                        onTransform = { dx, dy, resize ->
                                            val b = (gesture?.takeIf { it.tool == window.tool } ?: window).bounds(area)
                                            val target = if (resize) b.resize(dx, dy, area) else b.move(dx, dy, area)
                                            gesture = window.copy(floating = target, placement = Placement.Floating)
                                        },
                                        onTransformEnd = {
                                            gesture?.let { draft ->
                                                updateWindows { current ->
                                                    current.activate(draft.tool).map {
                                                        if (it.tool == draft.tool) it.copy(floating = draft.floating, placement = draft.placement) else it
                                                    }
                                                }
                                                model.bringToFront(WindowKey.ToolKey(draft.tool))
                                            }
                                            gesture = null
                                        },
                                        onPlacement = { placement ->
                                            updateWindows { current ->
                                                current.activate(window.tool).map { if (it.tool == window.tool) it.copy(placement = placement) else it }
                                            }
                                            model.bringToFront(winKey)
                                        },
                                        onMinimize = { updateWindows { current -> current.map { if (it.tool == window.tool) it.copy(minimized = true) else it } } },
                                        onClose = { updateWindows { current -> current.filterNot { it.tool == window.tool } } },
                                    ) {
                                        when (window.tool) {
                                            Tool.Notes -> NotesPanel(noteDraft ?: preferences.note, model::editNote)
                                            Tool.Settings -> SettingsPanel(
                                                preferences = preferences,
                                                customWallpaperBitmap = customWallpaperBitmap,
                                                caps = caps,
                                                widthDp = area.width.roundToInt(),
                                                heightDp = area.height.roundToInt(),
                                                densityDpi = baseDensityDpi,
                                                adbAccess = adbAccess,
                                                openAppCount = appWindows.count { it.appId in appsById },
                                                onAdbPermission = {
                                                    if (!adbWindows.requestAccess()) notice = Notice(R.string.adb_permission_unavailable)
                                                },
                                                onTheme = { model.save { theme(it) } },
                                                onWallpaper = { model.save { wallpaper(it) } },
                                                onPickWallpaper = {
                                                    if (!onPickWallpaper()) notice = Notice(R.string.wallpaper_picker_unavailable)
                                                },
                                                onDock = { model.save { dock(it) } },
                                                onDockSize = { model.save { dockSize(it) } },
                                                onDockAlignment = { model.save { dockAlignment(it) } },
                                                onShowClock = { model.save { showClock(it) } },
                                                onShowSystemTray = { model.save { showSystemTray(it) } },
                                                onShowDesktopShortcuts = { model.save { showDesktopShortcuts(it) } },
                                                onWindowScale = { model.save { windowScale(it) } },
                                                onRestoreOpenApps = { model.save { restoreOpenApps(it) } },
                                                onCloseAllApps = { model.closeAllAppWindows(layoutIsExternal) },
                                                onHome = {
                                                    if (!changeHome()) notice = Notice(R.string.home_settings_unavailable)
                                                },
                                                onScreen = { id ->
                                                    notice = when (platform.openWorkspaceOn(id)) {
                                                        LaunchResult.Requested -> null
                                                        LaunchResult.DisplayDisconnected -> Notice(R.string.display_disconnected)
                                                        LaunchResult.DisplayRejected, LaunchResult.Unavailable -> Notice(R.string.workspace_display_rejected)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            is WindowKey.AppKey -> {
                                val appWindow = appsByWindowKey[winKey.appId] ?: return@forEach
                                val app = appsById[winKey.appId] ?: return@forEach
                                key(winKey) {
                                    AppWindow(
                                        window = appWindow,
                                        app = app,
                                        area = area,
                                        active = activeKey == winKey,
                                        resizing = appResizing && appGesture?.appId == app.id,
                                        onActivate = {
                                            if (appGesture == null && activeKey != winKey) {
                                                model.activateAppWindow(layoutIsExternal, app.id)
                                            }
                                        },
                                        onTransform = { dx, dy, resize ->
                                            val current = appGesture?.takeIf { it.appId == app.id } ?: appWindow
                                            val b = current.bounds(area)
                                            val target = if (resize) b.resize(dx, dy, area) else b.move(dx, dy, area)
                                            appResizing = resize
                                            appGesture = current.copy(floating = target, placement = Placement.Floating)
                                        },
                                        onTransformEnd = {
                                            appGesture?.let { draft ->
                                                model.updateAppWindow(layoutIsExternal, draft.appId) {
                                                    it.copy(floating = draft.floating, placement = draft.placement)
                                                }
                                                model.activateAppWindow(layoutIsExternal, draft.appId)
                                            }
                                            appGesture = null
                                            appResizing = false
                                        },
                                        onPlacement = { placement ->
                                            model.updateAppWindow(layoutIsExternal, app.id) { it.copy(placement = placement) }
                                            model.activateAppWindow(layoutIsExternal, app.id)
                                        },
                                        onMinimize = {
                                            model.updateAppWindow(layoutIsExternal, app.id) { it.copy(minimized = true) }
                                        },
                                        onClose = {
                                            model.closeAppWindow(layoutIsExternal, app.id)
                                        },
                                        onSurfaceReady = { surface, w, h ->
                                            model.attachAppSurface(layoutIsExternal, app, surface, w, h, hostedDensityDpi) { failed ->
                                                notice = Notice(R.string.hosted_window_failed, appName = failed.label)
                                            }
                                        },
                                        onSurfaceResized = { w, h ->
                                            model.resizeAppSurface(layoutIsExternal, app.id, w, h, hostedDensityDpi)
                                        },
                                        onSurfaceDetached = { generation ->
                                            model.detachAppSurface(layoutIsExternal, app.id, generation)
                                        },
                                        onInjectMotion = { event ->
                                            adbWindows.injectMotion(appWindow.virtualDisplayId, event)
                                        },
                                        onInjectKey = { event ->
                                            adbWindows.injectKey(appWindow.virtualDisplayId, event)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    val floatingCentered = preferences.dock == DockStyle.Floating && preferences.dockAlignment == DockAlignment.Center
                    if (drawer) {
                        AppDrawer(
                            apps = apps,
                            pins = preferences.pins,
                            runningAppIds = runningAppIds,
                            onOpen = openApp,
                            onTogglePin = { app -> model.save { togglePin(app.id) } },
                            onOpenFreeform = openFreeform,
                            onOpenFullscreen = openFullscreen,
                            onFitAboveDock = fitAboveDock,
                            onOpenInWindow = openInWindow,
                            onTool = openTool,
                            onClose = { drawer = false },
                            focusSearchOnOpen = focusSearchOnOpen,
                            modifier = Modifier.align(if (floatingCentered) Alignment.BottomCenter else Alignment.BottomStart)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    if (windowManagerOpen) {
                        OpenWindowsPopover(
                            appWindows = appWindows,
                            toolWindows = windows,
                            appsById = appsById,
                            onFocusApp = { appId ->
                                model.updateAppWindow(layoutIsExternal, appId) { it.copy(minimized = false) }
                                model.activateAppWindow(layoutIsExternal, appId)
                            },
                            onToggleMinimizeApp = { win ->
                                val nextMin = !win.minimized
                                model.updateAppWindow(layoutIsExternal, win.appId) { it.copy(minimized = nextMin) }
                                if (!nextMin) model.activateAppWindow(layoutIsExternal, win.appId)
                            },
                            onCloseApp = { appId -> model.closeAppWindow(layoutIsExternal, appId) },
                            onFocusTool = openTool,
                            onToggleMinimizeTool = { win ->
                                val nextMin = !win.minimized
                                updateWindows { current ->
                                    current.map { if (it.tool == win.tool) it.copy(minimized = nextMin) else it }
                                }
                                if (!nextMin) model.bringToFront(WindowKey.ToolKey(win.tool))
                            },
                            onCloseTool = { tool ->
                                updateWindows { current -> current.filterNot { it.tool == tool } }
                            },
                            onMinimizeAll = ::minimizeAllWindows,
                            onCloseAll = {
                                model.closeAllAppWindows(layoutIsExternal)
                                updateWindows { emptyList() }
                            },
                            onClosePopover = { windowManagerOpen = false },
                            modifier = Modifier.align(if (floatingCentered) Alignment.BottomCenter else Alignment.BottomEnd)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (quickSettingsOpen) {
                        QuickSettingsPopover(
                            status = systemStatus,
                            darkTheme = darkTheme,
                            externalDisplay = external,
                            displayId = caps.currentDisplay,
                            onInternet = { systemTray?.openInternetPanel(context) },
                            onBluetooth = { systemTray?.openBluetoothSettings(context) },
                            onToggleMute = { systemTray?.toggleMute() },
                            onToggleTheme = {
                                val next = if (darkTheme) ThemeMode.Light else ThemeMode.Dark
                                model.save { theme(next) }
                            },
                            onCast = { systemTray?.openCastSettings(context) },
                            onDisplaySettings = { systemTray?.openDisplaySettings(context) },
                            onVolumeChange = { level -> systemTray?.setMediaVolume(level) },
                            onBrightnessChange = onBrightnessChange,
                            onWorkspaceSettings = { openTool(Tool.Settings) },
                            onSystemSettings = { systemTray?.openSystemSettings(context) },
                            onShowDesktop = ::minimizeAllWindows,
                            onDismiss = { quickSettingsOpen = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (notificationsOpen) {
                        NotificationCenterPopover(
                            notifications = notifications,
                            accessGranted = notificationAccessGranted,
                            onRequestAccess = { systemTray?.openNotificationAccessSettings(context) },
                            onOpenNotification = { NotificationCenterStore.open(it) },
                            onDismissNotification = { NotificationCenterStore.dismiss(it) },
                            onClearAll = { NotificationCenterStore.clearAll() },
                            onDismissPopover = { notificationsOpen = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (notice != null || saveError || readError) {
                        val item = notice
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).widthIn(max = 520.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    when {
                                        item?.appName != null -> stringResource(item.message, item.appName)
                                        item != null -> stringResource(item.message)
                                        readError -> stringResource(R.string.read_error)
                                        else -> stringResource(R.string.save_error)
                                    },
                                )
                                Row {
                                    item?.fallback?.let { app ->
                                        TextButton(onClick = { launch(app, Display.DEFAULT_DISPLAY, explicit = true) }) {
                                            Text(stringResource(R.string.open_on_primary))
                                        }
                                    }
                                    if (item != null || saveError) {
                                        TextButton(onClick = { if (item != null) notice = null else model.dismissSaveError() }) {
                                            Text(stringResource(R.string.close))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Dock(
                apps = apps,
                pins = preferences.pins,
                recent = recent,
                windows = windows,
                style = preferences.dock,
                dockSize = preferences.dockSize,
                dockAlignment = preferences.dockAlignment,
                showClock = preferences.showClock,
                showSystemTray = preferences.showSystemTray,
                drawer = drawer,
                windowManagerOpen = windowManagerOpen,
                quickSettingsOpen = quickSettingsOpen,
                notificationsOpen = notificationsOpen,
                external = external,
                systemStatus = systemStatus,
                notificationCount = if (notificationAccessGranted) notifications.size else 0,
                onDrawer = { toggleDrawer(focusSearch = false) },
                onWindowManager = ::toggleWindowManager,
                onQuickSettings = ::toggleQuickSettings,
                onNotifications = ::toggleNotifications,
                onOpen = openApp,
                onOpenFreeform = openFreeform,
                onOpenFullscreen = openFullscreen,
                onFitAboveDock = fitAboveDock,
                appWindows = appWindows,
                onAppWindow = { appWindow ->
                    closePopovers()
                    val key = WindowKey.AppKey(appWindow.appId)
                    when {
                        appWindow.minimized -> {
                            model.updateAppWindow(layoutIsExternal, appWindow.appId) { it.copy(minimized = false) }
                            model.activateAppWindow(layoutIsExternal, appWindow.appId)
                        }
                        activeKey == key -> {
                            model.updateAppWindow(layoutIsExternal, appWindow.appId) { it.copy(minimized = true) }
                        }
                        else -> model.activateAppWindow(layoutIsExternal, appWindow.appId)
                    }
                },
                onCloseAppWindow = { appId -> model.closeAppWindow(layoutIsExternal, appId) },
                onOpenInWindow = openInWindow,
                onToggleDockStyle = {
                    val next = if (preferences.dock == DockStyle.Floating) DockStyle.Panel else DockStyle.Floating
                    model.save { dock(next) }
                },
                onShowDesktop = ::minimizeAllWindows,
                modifier = Modifier.onSizeChanged { dockHeightPx = it.height },
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
private fun WorkspaceShortcuts(onTool: (Tool) -> Unit, modifier: Modifier) {
    Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolButton(Icons.Default.EditNote, stringResource(Tool.Notes.title), { onTool(Tool.Notes) }, desktop = true, size = 46.dp)
        ToolButton(Icons.Default.Settings, stringResource(Tool.Settings.title), { onTool(Tool.Settings) }, desktop = true, size = 46.dp)
    }
}
