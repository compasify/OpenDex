package dev.opendex.desktop

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opendex.R
import dev.opendex.WorkspaceModel
import dev.opendex.data.DockAlignment
import dev.opendex.data.DockStyle
import dev.opendex.data.ThemeMode
import dev.opendex.data.WallpaperStorage
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.computeHostedDensityDpi
import dev.opendex.data.scaledDensity
import dev.opendex.platform.AdbAccess
import dev.opendex.platform.AdbWindows
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.LaunchResult
import dev.opendex.platform.NotificationBanner
import dev.opendex.platform.NotificationCenterStore
import dev.opendex.platform.SystemStatus
import dev.opendex.platform.SystemTrayController
import dev.opendex.platform.TrayNotification
import dev.opendex.ui.DesktopWallpaper
import dev.opendex.ui.isThemeDark
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class Notice(@get:StringRes val message: Int, val fallback: InstalledApp? = null, val appName: String? = null)

private class ResizePreviewState {
    var bounds by mutableStateOf<Bounds?>(null)
}

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
    notificationBanners: List<NotificationBanner> = emptyList(),
    notificationAccessGranted: Boolean = false,
    onPickWallpaper: () -> Boolean = { false },
    onRequestLocationPermission: () -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onHideSystemBars: () -> Unit = {},
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
    val externalScreens = remember(caps.screens) {
        caps.screens.filterNot { it.id == Display.DEFAULT_DISPLAY }
    }
    var touchpadTargetDisplayId by rememberSaveable { mutableStateOf<Int?>(null) }
    val activeTouchpadScreen = remember(external, touchpadTargetDisplayId, externalScreens) {
        if (external) null else externalScreens.firstOrNull { it.id == touchpadTargetDisplayId }
    }
    LaunchedEffect(external, touchpadTargetDisplayId, activeTouchpadScreen) {
        if (!external && touchpadTargetDisplayId != null && activeTouchpadScreen == null) {
            touchpadTargetDisplayId = null
            model.externalInput.hidePointer()
        }
    }
    if (activeTouchpadScreen != null) {
        ExternalTouchpadScreen(
            screen = activeTouchpadScreen,
            controller = model.externalInput,
            pointerSpeed = preferences.pointerSpeed,
            onPointerSpeedChange = { speed -> model.save { pointerSpeed(speed) } },
            onExit = { touchpadTargetDisplayId = null },
        )
        return
    }
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
    val resizePreview = remember { ResizePreviewState() }
    val appsById = remember(apps) { apps.associateBy { it.id } }
    val appsByPackage = remember(apps) { apps.associateBy { it.component.packageName } }
    val settingsApp = remember(appsByPackage, context) {
        val pkg = runCatching {
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        pkg?.let(appsByPackage::get) ?: appsByPackage["com.android.settings"]
    }

    var drawer by rememberSaveable { mutableStateOf(false) }
    var windowManagerOpen by rememberSaveable { mutableStateOf(false) }
    var quickSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var desktopMenu by remember { mutableStateOf(false) }
    var focusSearchOnOpen by remember { mutableStateOf(false) }

    fun closePopovers() {
        drawer = false
        windowManagerOpen = false
        quickSettingsOpen = false
        notificationsOpen = false
        calendarOpen = false
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

    fun toggleCalendar() {
        val opening = !calendarOpen
        closePopovers()
        calendarOpen = opening
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
    var pendingSettingsLaunch by remember { mutableStateOf<Triple<InstalledApp, String, String?>?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var sendingFreeform by remember { mutableStateOf(false) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var recent by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    val nativeDensity = LocalDensity.current
    val nativeDensityDpi = (nativeDensity.density * 160).roundToInt()
    val activeDisplayScale = preferences.displayScaleFor(external)
    val effectiveDensity = remember(nativeDensity, activeDisplayScale) {
        Density(
            density = scaledDensity(nativeDensity.density, activeDisplayScale),
            fontScale = nativeDensity.fontScale,
        )
    }
    val effectiveDensityDpi = (effectiveDensity.density * 160).roundToInt()
    var physicalSizePx by remember { mutableStateOf(IntSize.Zero) }

    val hostedDensityDpi = computeHostedDensityDpi(nativeDensityDpi, preferences.windowScale, activeDisplayScale)
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

    var minimizedSnapshot by remember { mutableStateOf<Set<WindowKey>>(emptySet()) }

    fun minimizeAllWindows() {
        updateWindows { it.minimizeAllTools() }
        model.minimizeAllAppWindows(layoutIsExternal)
    }

    fun toggleShowDesktop() {
        closePopovers()
        val visibleToolKeys = windows.filterNot { it.minimized }.map { WindowKey.ToolKey(it.tool) }
        val visibleAppKeys = appWindows.filter { !it.minimized && it.appId in appsById }.map { WindowKey.AppKey(it.appId) }
        val visibleKeys = (visibleToolKeys + visibleAppKeys).toSet()
        if (visibleKeys.isNotEmpty()) {
            minimizedSnapshot = visibleKeys
            minimizeAllWindows()
        } else {
            val openToolSet = windows.mapTo(HashSet()) { it.tool }
            val openAppSet = appWindows.filter { it.appId in appsById }.mapTo(HashSet()) { it.appId }
            val snapTools = minimizedSnapshot.filterIsInstance<WindowKey.ToolKey>().map { it.tool }.filterTo(HashSet()) { it in openToolSet }
            val snapApps = minimizedSnapshot.filterIsInstance<WindowKey.AppKey>().map { it.appId }.filterTo(HashSet()) { it in openAppSet }
            val useSnapshot = snapTools.isNotEmpty() || snapApps.isNotEmpty()
            updateWindows { it.restoreTools(if (useSnapshot) snapTools else null) }
            model.restoreAppWindows(layoutIsExternal, if (useSnapshot) snapApps else null)
            minimizedSnapshot = emptySet()
        }
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

    fun openSettingsAction(action: String, dataUri: String? = null, fallback: () -> Unit) {
        val targetApp = settingsApp
        if (hostedOnTap && targetApp != null) {
            recordLaunch(targetApp)
            pendingSettingsLaunch = Triple(targetApp, action, dataUri)
        } else {
            fallback()
        }
    }

    fun openPackageInWindow(packageName: String) {
        val targetApp = appsByPackage[packageName] ?: return
        if (hostedOnTap) requestHostedAppWindow(targetApp) else openApp(targetApp)
    }

    fun openNotification(item: TrayNotification) {
        if (!NotificationCenterStore.open(item)) {
            openPackageInWindow(item.packageName)
        }
    }
    val openInWindow: ((InstalledApp) -> Unit)? =
        if (!hostedOnTap && adbAccess == AdbAccess.Granted && adbWindows.supportsHostedWindows) { app -> requestHostedAppWindow(app) } else null
    val openAppInfo: (InstalledApp) -> Unit = { app ->
        closePopovers()
        openSettingsAction(
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            dataUri = "package:${app.component.packageName}",
            fallback = {
                when (platform.openAppInfo(app, caps.currentDisplay)) {
                    LaunchResult.Requested -> notice = null
                    LaunchResult.DisplayDisconnected -> notice = Notice(R.string.display_disconnected, app)
                    LaunchResult.DisplayRejected -> {
                        if (caps.currentDisplay != Display.DEFAULT_DISPLAY) {
                            platform.openAppInfo(app, Display.DEFAULT_DISPLAY)
                        } else {
                            notice = Notice(R.string.launch_app_unavailable, appName = app.label)
                        }
                    }
                    LaunchResult.Unavailable -> notice = Notice(R.string.launch_app_unavailable, appName = app.label)
                }
            },
        )
    }
    val uninstallApp: (InstalledApp) -> Unit = { app ->
        closePopovers()
        when (platform.requestUninstall(app, caps.currentDisplay)) {
            LaunchResult.Requested -> notice = null
            LaunchResult.DisplayDisconnected -> notice = Notice(R.string.display_disconnected, app)
            LaunchResult.DisplayRejected -> {
                if (caps.currentDisplay != Display.DEFAULT_DISPLAY) {
                    platform.requestUninstall(app, Display.DEFAULT_DISPLAY)
                } else {
                    notice = Notice(R.string.launch_app_unavailable, appName = app.label)
                }
            }
            LaunchResult.Unavailable -> notice = Notice(R.string.launch_app_unavailable, appName = app.label)
        }
    }

    val visibleTools = windows.filterNot { it.minimized }
    val visibleApps = if (adbAccess == AdbAccess.Granted) {
        appWindows.filterNot { it.minimized }.filter { it.appId in appsById }
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
    val fullscreenApp = visibleApps.lastOrNull { it.fullscreen }
    val isImmersive = fullscreenApp != null
    LaunchedEffect(isImmersive) {
        if (isImmersive) {
            closePopovers()
            onHideSystemBars()
        }
    }
    val runningAppIds = remember(appWindows) { appWindows.mapTo(HashSet()) { it.appId } }
    val darkTheme = isThemeDark(preferences.theme)
    val anyPopoverOpen = drawer || windowManagerOpen || quickSettingsOpen || notificationsOpen || calendarOpen

    BackHandler(anyPopoverOpen || notice != null || !caps.home) {
        when {
            notice != null -> notice = null
            anyPopoverOpen -> closePopovers()
            !caps.home -> onMoveToBackground()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged {
                physicalSizePx = it
                if (external && it.width > 0 && it.height > 0) {
                    model.externalInput.updateExternalSize(caps.currentDisplay, it.width, it.height)
                }
            },
    ) {
        CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        DesktopWallpaper(
            wallpaper = preferences.wallpaper,
            dark = darkTheme,
            customBitmap = customWallpaperBitmap,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            // System bars are hidden in immersive mode; reserve display cutout and caption bar only
            // when not in true app fullscreen. Do not shrink the desktop work area with IME insets,
            // or every open app window/VirtualDisplay would resize on every frame of the soft keyboard animation.
            Modifier.fillMaxSize()
                .then(
                    if (isImmersive) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.captionBar)),
                )
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.F11 -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                val targetId = fullscreenApp?.appId
                                    ?: (activeKey as? WindowKey.AppKey)?.appId
                                    ?: visibleApps.lastOrNull()?.appId
                                if (targetId != null) {
                                    model.updateAppWindow(layoutIsExternal, targetId) { it.copy(fullscreen = !it.fullscreen) }
                                    model.activateAppWindow(layoutIsExternal, targetId)
                                }
                            }
                            true
                        }
                        e.key == Key.Escape && notice != null -> { notice = null; true }
                        e.key == Key.Escape && anyPopoverOpen -> { closePopovers(); true }
                        e.key == Key.K && e.nativeKeyEvent.hasModifiers(android.view.KeyEvent.META_CTRL_ON) -> {
                            if (e.nativeKeyEvent.repeatCount == 0) toggleDrawer(focusSearch = true)
                            true
                        }
                        activeKey != null && (
                            e.nativeKeyEvent.hasModifiers(android.view.KeyEvent.META_META_ON) ||
                                e.nativeKeyEvent.hasModifiers(android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_ALT_ON)
                        ) && e.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                val targetKey = activeKey
                                val targetPlacement = when (e.key) {
                                    Key.DirectionLeft -> Placement.Left
                                    Key.DirectionRight -> Placement.Right
                                    Key.DirectionUp -> Placement.Full
                                    else -> Placement.Floating
                                }
                                when (targetKey) {
                                    is WindowKey.AppKey -> model.updateAppWindow(layoutIsExternal, targetKey.appId) { win ->
                                        if (e.key == Key.DirectionDown && win.placement == Placement.Floating) {
                                            win.copy(minimized = true)
                                        } else {
                                            win.copy(placement = targetPlacement)
                                        }
                                    }
                                    is WindowKey.ToolKey -> updateWindows { current ->
                                        current.map { win ->
                                            if (win.tool != targetKey.tool) win
                                            else if (e.key == Key.DirectionDown && win.placement == Placement.Floating) win.copy(minimized = true)
                                            else win.copy(placement = targetPlacement)
                                        }
                                    }
                                    null -> Unit
                                }
                            }
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
                    LaunchedEffect(pendingSettingsLaunch, area) {
                        pendingSettingsLaunch?.let { (app, action, dataUri) ->
                            model.openAppWindowWithAction(layoutIsExternal, app.id, action, area, dataUri)
                        }
                        pendingSettingsLaunch = null
                    }
                    if (preferences.showDesktopShortcuts) {
                        WorkspaceShortcuts(
                            desktopApps = preferences.desktopApps.mapNotNull(appsById::get),
                            onTool = openTool,
                            onOpenApp = openApp,
                            onOpenInWindow = openInWindow,
                            onAppInfo = openAppInfo,
                            onUninstallApp = uninstallApp,
                            onRemoveDesktopApp = { app -> model.save { toggleDesktopApp(app.id) } },
                            modifier = Modifier.align(Alignment.TopStart),
                        )
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
                                            val activeDraft = gesture?.takeIf { it.tool == window.tool }
                                            val b = if (activeDraft != null) {
                                                activeDraft.bounds(area)
                                            } else if (!resize && window.placement != Placement.Floating) {
                                                val restored = window.floating.fit(area)
                                                val current = window.bounds(area)
                                                restored.copy(
                                                    x = (current.x + (current.width - restored.width) / 2f).coerceIn(0f, area.width - restored.width),
                                                    y = current.y.coerceIn(0f, area.height - restored.height),
                                                )
                                            } else {
                                                window.bounds(area)
                                            }
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
                                        onSnapPreview = { preview -> resizePreview.bounds = preview },
                                    ) {
                                        when (window.tool) {
                                            Tool.Notes -> NotesPanel(noteDraft ?: preferences.note, model::editNote)
                                            Tool.Settings -> SettingsPanel(
                                                preferences = preferences,
                                                customWallpaperBitmap = customWallpaperBitmap,
                                                caps = caps,
                                                widthDp = area.width.roundToInt(),
                                                heightDp = area.height.roundToInt(),
                                                physicalWidthPx = physicalSizePx.width,
                                                physicalHeightPx = physicalSizePx.height,
                                                nativeDensity = nativeDensity.density,
                                                densityDpi = effectiveDensityDpi,
                                                displayScale = activeDisplayScale,
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
                                                onDisplayScale = { scale -> model.save { displayScale(external, scale) } },
                                                onDock = { model.save { dock(it) } },
                                                onDockSize = { model.save { dockSize(it) } },
                                                onDockAlignment = { model.save { dockAlignment(it) } },
                                                onShowClock = { model.save { showClock(it) } },
                                                onShowSystemTray = { model.save { showSystemTray(it) } },
                                                onShowDesktopShortcuts = { model.save { showDesktopShortcuts(it) } },
                                                onWindowScale = { model.save { windowScale(it) } },
                                                onWindowResizeMode = { model.save { windowResizeMode(it) } },
                                                onRestoreOpenApps = { model.save { restoreOpenApps(it) } },
                                                onCloseAllApps = { model.closeAllAppWindows(layoutIsExternal) },
                                                onHome = {
                                                    if (!changeHome()) notice = Notice(R.string.home_settings_unavailable)
                                                },
                                                onScreen = { id ->
                                                    val result = platform.openWorkspaceOn(id)
                                                    notice = when (result) {
                                                        LaunchResult.Requested -> null
                                                        LaunchResult.DisplayDisconnected -> Notice(R.string.display_disconnected)
                                                        LaunchResult.DisplayRejected, LaunchResult.Unavailable -> Notice(R.string.workspace_display_rejected)
                                                    }
                                                    if (result == LaunchResult.Requested && !external) {
                                                        touchpadTargetDisplayId = id
                                                    }
                                                },
                                                onTouchpad = { id ->
                                                    if (!external) touchpadTargetDisplayId = id
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
                                        resizeMode = preferences.windowResizeMode,
                                        onActivate = {
                                            if (activeKey != winKey) {
                                                model.activateAppWindow(layoutIsExternal, app.id)
                                            }
                                        },
                                        onCommitBounds = { target ->
                                            model.updateAppWindow(layoutIsExternal, app.id) {
                                                it.copy(floating = target, placement = Placement.Floating)
                                            }
                                            model.activateAppWindow(layoutIsExternal, app.id)
                                        },
                                        onOutlinePreview = { preview ->
                                            resizePreview.bounds = preview
                                        },
                                        onPlacement = { placement ->
                                            model.updateAppWindow(layoutIsExternal, app.id) { it.copy(placement = placement, fullscreen = false) }
                                            model.activateAppWindow(layoutIsExternal, app.id)
                                        },
                                        onToggleFullscreen = {
                                            val entering = !appWindow.fullscreen
                                            model.updateAppWindow(layoutIsExternal, app.id) { it.copy(fullscreen = entering) }
                                            model.activateAppWindow(layoutIsExternal, app.id)
                                        },
                                        onMinimize = {
                                            model.updateAppWindow(layoutIsExternal, app.id) { it.copy(minimized = true, fullscreen = false) }
                                        },
                                        onClose = {
                                            model.closeAppWindow(layoutIsExternal, app.id)
                                        },
                                        onSurfaceReady = { surface, w, h ->
                                            model.attachAppSurface(layoutIsExternal, app, surface, w, h, hostedDensityDpi) { failed ->
                                                notice = Notice(R.string.hosted_window_failed, appName = failed.label)
                                            }
                                        },
                                        onSurfaceResized = { w, h, onComplete ->
                                            model.resizeAppSurface(layoutIsExternal, app.id, w, h, hostedDensityDpi, onComplete)
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
                    ResizeOutlineOverlay(resizePreview)

                    val floatingCentered = preferences.dock == DockStyle.Floating && preferences.dockAlignment == DockAlignment.Center
                    if (drawer) {
                        AppDrawer(
                            apps = apps,
                            pins = preferences.pins,
                            runningAppIds = runningAppIds,
                            onOpen = openApp,
                            onTogglePin = { app -> model.save { togglePin(app.id) } },
                            onOpenInWindow = openInWindow,
                            onTool = openTool,
                            onClose = { drawer = false },
                            desktopApps = preferences.desktopApps,
                            onToggleDesktopApp = { app -> model.save { toggleDesktopApp(app.id) } },
                            onAppInfo = openAppInfo,
                            onUninstallApp = uninstallApp,
                            focusSearchOnOpen = focusSearchOnOpen,
                            modifier = Modifier.align(if (floatingCentered) Alignment.BottomCenter else Alignment.BottomStart)
                                .imePadding()
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
                            onInternet = {
                                openSettingsAction(Settings.ACTION_WIFI_SETTINGS) { systemTray?.openInternetPanel(context) }
                            },
                            onRequestLocationPermission = onRequestLocationPermission,
                            onBluetooth = {
                                openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS) { systemTray?.openBluetoothSettings(context) }
                            },
                            onToggleMute = { systemTray?.toggleMute() },
                            onToggleTheme = {
                                val next = if (darkTheme) ThemeMode.Light else ThemeMode.Dark
                                model.save { theme(next) }
                            },
                            onCast = {
                                openSettingsAction(Settings.ACTION_CAST_SETTINGS) { systemTray?.openCastSettings(context) }
                            },
                            onDisplaySettings = {
                                openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS) { systemTray?.openDisplaySettings(context) }
                            },
                            onVolumeChange = { level -> systemTray?.setMediaVolume(level) },
                            onBrightnessChange = onBrightnessChange,
                            onWorkspaceSettings = { openTool(Tool.Settings) },
                            onSystemSettings = {
                                openSettingsAction(Settings.ACTION_SETTINGS) { systemTray?.openSystemSettings(context) }
                            },
                            onShowDesktop = ::minimizeAllWindows,
                            onDismiss = { quickSettingsOpen = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (notificationsOpen) {
                        NotificationCenterPopover(
                            notifications = notifications,
                            accessGranted = notificationAccessGranted,
                            canOpenPackageInWindow = { pkg -> hostedOnTap && pkg in appsByPackage },
                            onRequestAccess = {
                                openSettingsAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                                    systemTray?.openNotificationAccessSettings(context)
                                }
                            },
                            onOpenNotification = ::openNotification,
                            onOpenPackageInWindow = ::openPackageInWindow,
                            onDismissNotification = { NotificationCenterStore.dismiss(it) },
                            onClearAll = { NotificationCenterStore.clearAll() },
                            onDismissPopover = { notificationsOpen = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (calendarOpen) {
                        CalendarPopover(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (preferences.showSystemTray && !notificationsOpen && notificationBanners.isNotEmpty()) {
                        NotificationBannerStack(
                            banners = notificationBanners,
                            canOpenPackageInWindow = { pkg -> hostedOnTap && pkg in appsByPackage },
                            onOpenNotification = ::openNotification,
                            onOpenPackageInWindow = ::openPackageInWindow,
                            onDismissBanner = { NotificationCenterStore.dismissBanner(it) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp),
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
            if (!isImmersive) {
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
                calendarOpen = calendarOpen,
                external = external,
                systemStatus = systemStatus,
                notificationCount = if (notificationAccessGranted) notifications.size else 0,
                onDrawer = { toggleDrawer(focusSearch = false) },
                onWindowManager = ::toggleWindowManager,
                onQuickSettings = ::toggleQuickSettings,
                onNotifications = ::toggleNotifications,
                onCalendar = ::toggleCalendar,
                onOpen = openApp,
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
                onToggleShowDesktop = ::toggleShowDesktop,
                modifier = Modifier.onSizeChanged { dockHeightPx = it.height },
                onPin = { app -> model.save { togglePin(app.id) } },
                desktopApps = preferences.desktopApps,
                onToggleDesktopApp = { app -> model.save { toggleDesktopApp(app.id) } },
                onAppInfo = openAppInfo,
                onUninstallApp = uninstallApp,
                onOpenTouchpad = if (!external && externalScreens.isNotEmpty()) {
                    { touchpadTargetDisplayId = externalScreens.first().id }
                } else null,
                onTool = { tool ->
                    // Compute geometry inside the desktop, where the dock and insets are excluded.
                    pendingTool = tool
                },
            )
            }
        }
        }
        if (external) {
            ExternalCursorOverlay(
                pointerState = model.externalInput.pointerState,
                currentDisplayId = caps.currentDisplay,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceShortcuts(
    desktopApps: List<InstalledApp>,
    onTool: (Tool) -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onOpenInWindow: ((InstalledApp) -> Unit)?,
    onAppInfo: (InstalledApp) -> Unit,
    onUninstallApp: (InstalledApp) -> Unit,
    onRemoveDesktopApp: (InstalledApp) -> Unit,
    modifier: Modifier,
) {
    FlowColumn(
        modifier = modifier.fillMaxHeight().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolButton(Icons.Default.EditNote, stringResource(Tool.Notes.title), { onTool(Tool.Notes) }, desktop = true, size = 46.dp)
        ToolButton(Icons.Default.Settings, stringResource(Tool.Settings.title), { onTool(Tool.Settings) }, desktop = true, size = 46.dp)
        desktopApps.forEach { app ->
            DesktopAppShortcut(
                app = app,
                onOpen = { onOpenApp(app) },
                onOpenInWindow = onOpenInWindow?.let { { it(app) } },
                onAppInfo = { onAppInfo(app) },
                onUninstall = if (!app.isSystemApp) ({ onUninstallApp(app) }) else null,
                onRemoveFromDesktop = { onRemoveDesktopApp(app) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopAppShortcut(
    app: InstalledApp,
    onOpen: () -> Unit,
    onOpenInWindow: (() -> Unit)?,
    onAppInfo: () -> Unit,
    onUninstall: (() -> Unit)?,
    onRemoveFromDesktop: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box {
        Column(
            modifier = Modifier
                .width(88.dp)
                .clip(shape)
                .background(if (hovered || focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .onSecondaryClick { menu = true }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || (it.isShiftPressed && it.key == Key.F10))) {
                        menu = true
                        true
                    } else false
                }
                .combinedClickable(
                    interactionSource = interactions,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.open_app, app.label),
                    onLongClickLabel = stringResource(R.string.app_options, app.label),
                    onLongClick = { menu = true },
                    onClick = onOpen,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, 42)
            Text(
                app.label,
                Modifier.padding(top = 4.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.8f), offset = Offset(0f, 1.5f), blurRadius = 4f),
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onOpenInWindow != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.open_app_in_window)) },
                leadingIcon = { Icon(Icons.Default.Window, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onOpenInWindow() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_desktop)) },
                leadingIcon = { Icon(Icons.Default.DesktopWindows, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onRemoveFromDesktop() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_info)) },
                leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onAppInfo() },
            )
            if (onUninstall != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.uninstall_app), color = colors.error) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp), tint = colors.error) },
                onClick = { menu = false; onUninstall() },
            )
        }
    }
}

@Composable
private fun BoxScope.ResizeOutlineOverlay(state: ResizePreviewState) {
    val bounds = state.bounds ?: return
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                with(density) { IntOffset(bounds.x.dp.toPx().roundToInt(), bounds.y.dp.toPx().roundToInt()) }
            }
            .size(bounds.width.dp, bounds.height.dp)
            .clip(shape)
            .background(primary.copy(alpha = 0.08f))
            .border(2.dp, primary.copy(alpha = 0.85f), shape),
    ) {
        ResizeDimensionBadge(
            width = bounds.width.roundToInt(),
            height = bounds.height.roundToInt(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}
