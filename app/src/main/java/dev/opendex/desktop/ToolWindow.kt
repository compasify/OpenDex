package dev.opendex.desktop

import android.os.Build
import android.view.Display
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opendex.R
import dev.opendex.data.DisplayScale
import dev.opendex.data.DockAlignment
import dev.opendex.data.DockStyle
import dev.opendex.data.MAX_DOCK_HEIGHT_DP
import dev.opendex.data.MIN_DOCK_HEIGHT_DP
import dev.opendex.data.ThemeMode
import dev.opendex.data.Wallpaper
import dev.opendex.data.WindowResizeMode
import dev.opendex.data.WindowScale
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.defaultDockHeightDp
import dev.opendex.data.logicalResolution
import dev.opendex.platform.AdbAccess
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.ui.DesktopWallpaper
import dev.opendex.ui.isThemeDark
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.ToolWindow(
    window: DesktopWindow, area: Area, active: Boolean, onActivate: () -> Unit,
    onTransform: (Float, Float, Boolean) -> Unit, onTransformEnd: () -> Unit,
    onPlacement: (Placement) -> Unit, onMinimize: () -> Unit, onClose: () -> Unit,
    onSnapPreview: ((Bounds?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    WindowFrame(
        bounds = window.bounds(area),
        placement = window.placement,
        area = area,
        title = stringResource(window.tool.title),
        active = active,
        leadingIcon = {
            Icon(
                if (window.tool == Tool.Notes) Icons.Default.EditNote else Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onActivate = onActivate,
        onTransform = onTransform,
        onTransformEnd = onTransformEnd,
        onPlacement = onPlacement,
        onMinimize = onMinimize,
        onClose = onClose,
        onSnapPreview = onSnapPreview,
        reserveResizeFooter = true,
    ) { content() }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.WindowFrame(
    bounds: Bounds,
    placement: Placement,
    area: Area,
    title: String,
    active: Boolean,
    leadingIcon: (@Composable () -> Unit)? = null,
    onActivate: () -> Unit,
    onTransform: (Float, Float, Boolean) -> Unit,
    onTransformEnd: () -> Unit,
    onPlacement: (Placement) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onSnapPreview: ((Bounds?) -> Unit)? = null,
    reserveResizeFooter: Boolean = true,
    immersive: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val canTile = area.canTile
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val titleFocus = remember { FocusRequester() }
    val titleInteractions = remember { MutableInteractionSource() }
    val titleFocused by titleInteractions.collectIsFocusedAsState()
    var menu by remember { mutableStateOf(false) }
    var windowHasFocus by remember { mutableStateOf(false) }
    var pointerActivated by remember { mutableStateOf(false) }
    var pendingSnap by remember { mutableStateOf<Placement?>(null) }
    val isActive by rememberUpdatedState(active)
    val activate by rememberUpdatedState(onActivate)
    val transform by rememberUpdatedState(onTransform)
    val finish by rememberUpdatedState(onTransformEnd)
    val latestBounds by rememberUpdatedState(bounds)
    val latestPlacement by rememberUpdatedState(onPlacement)
    val latestSnapPreview by rememberUpdatedState(onSnapPreview)
    DisposableEffect(Unit) {
        onDispose { latestSnapPreview?.invoke(null) }
    }
    LaunchedEffect(active) {
        if (active) {
            // Let pointer targets (including the note field or hosted app view) handle their own focus.
            if (!pointerActivated && !windowHasFocus) titleFocus.requestFocus()
            pointerActivated = false
        }
    }
    fun Modifier.windowDrag(resize: Boolean) = pointerInput(area, density, resize) {
        detectDragGestures(
            onDragEnd = {
                val snap = if (!resize) pendingSnap else null
                pendingSnap = null
                if (!resize) latestSnapPreview?.invoke(null)
                finish()
                if (snap != null) latestPlacement(snap)
            },
            onDragCancel = {
                pendingSnap = null
                if (!resize) latestSnapPreview?.invoke(null)
                finish()
            },
        ) { change, delta ->
            change.consume()
            with(density) {
                transform(delta.x.toDp().value, delta.y.toDp().value, resize)
                if (!resize) {
                    val pointerX = latestBounds.x + change.position.x.toDp().value
                    val pointerY = latestBounds.y + change.position.y.toDp().value
                    val snap = detectEdgeSnap(pointerX, pointerY, area)
                    if (snap != pendingSnap) {
                        pendingSnap = snap
                        latestSnapPreview?.invoke(snap?.boundsIn(latestBounds, area))
                    }
                }
            }
        }
    }
    val isFloating = placement == Placement.Floating && !immersive
    val shape = RoundedCornerShape(if (isFloating) 12.dp else 0.dp)
    val borderStroke = if (immersive || placement == Placement.Full) null
        else BorderStroke(1.dp, if (active) colors.outline.copy(alpha = 0.55f) else colors.outlineVariant.copy(alpha = 0.5f))

    Surface(
        Modifier.align(AbsoluteAlignment.TopLeft)
            .then(
                if (immersive) Modifier.fillMaxSize()
                else Modifier
                    .absoluteOffset { with(density) { IntOffset(bounds.x.dp.toPx().roundToInt(), bounds.y.dp.toPx().roundToInt()) } }
                    .size(bounds.width.dp, bounds.height.dp),
            )
            .onFocusChanged {
                val entered = it.hasFocus && !windowHasFocus
                windowHasFocus = it.hasFocus
                if (entered && !isActive && !pointerActivated) activate()
            }
            .focusGroup()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!isActive && event.changes.any { it.changedToDownIgnoreConsumed() }) {
                            pointerActivated = true
                            activate()
                        }
                    }
                }
            },
        color = colors.surface,
        shape = shape,
        border = borderStroke,
        shadowElevation = if (immersive || placement == Placement.Full) 0.dp else if (active) 14.dp else 4.dp,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                if (!immersive) {
                Row(
                    Modifier.fillMaxWidth()
                        .height(34.dp)
                        .background(if (active) colors.surfaceContainerHigh else colors.surfaceContainerLow)
                        .padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (titleFocused) Modifier.border(1.5.dp, colors.primary) else Modifier)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .onSecondaryClick { menu = true }
                            .windowDrag(false)
                            .focusRequester(titleFocus)
                            .combinedClickable(
                                interactionSource = titleInteractions,
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    titleFocus.requestFocus()
                                    onActivate()
                                },
                                onDoubleClick = {
                                    onPlacement(if (placement == Placement.Full) Placement.Floating else Placement.Full)
                                },
                            )
                            .padding(start = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        leadingIcon?.invoke()
                        Text(
                            text = title,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
                            color = if (active) colors.onSurface else colors.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        WindowCaptionButton(
                            icon = Icons.Default.GridView,
                            label = stringResource(R.string.window_layout),
                            iconSize = 13.dp,
                            active = active,
                            onClick = { menu = true },
                        )
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            fun choose(target: Placement) { menu = false; onPlacement(target) }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.float_window)) },
                                leadingIcon = { Icon(Icons.Default.CropFree, null, Modifier.size(18.dp)) },
                                onClick = { choose(Placement.Floating) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.maximize)) },
                                leadingIcon = { Icon(Icons.Default.CropSquare, null, Modifier.size(18.dp)) },
                                onClick = { choose(Placement.Full) },
                            )
                            if (onToggleFullscreen != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.true_fullscreen)) },
                                    leadingIcon = { Icon(Icons.Default.Fullscreen, null, Modifier.size(18.dp)) },
                                    onClick = { menu = false; onToggleFullscreen() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.snap_left)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewSidebar, null, Modifier.size(18.dp)) },
                                enabled = canTile,
                                onClick = { choose(Placement.Left) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.snap_right)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewSidebar, null, Modifier.size(18.dp)) },
                                enabled = canTile,
                                onClick = { choose(Placement.Right) },
                            )
                        }
                    }
                    WindowCaptionButton(
                        icon = Icons.Default.Remove,
                        label = stringResource(R.string.minimize_tool, title),
                        iconSize = 15.dp,
                        active = active,
                        onClick = onMinimize,
                    )
                    val isMaximized = placement == Placement.Full
                    WindowCaptionButton(
                        icon = if (isMaximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                        label = stringResource(if (isMaximized) R.string.restore_window else R.string.maximize_window, title),
                        iconSize = 13.dp,
                        active = active,
                        onClick = { onPlacement(if (isMaximized) Placement.Floating else Placement.Full) },
                    )
                    if (onToggleFullscreen != null) {
                        WindowCaptionButton(
                            icon = Icons.Default.Fullscreen,
                            label = stringResource(R.string.true_fullscreen),
                            iconSize = 16.dp,
                            active = active,
                            onClick = onToggleFullscreen,
                        )
                    }
                    WindowCaptionButton(
                        icon = Icons.Default.Close,
                        label = stringResource(R.string.close_tool, title),
                        iconSize = 15.dp,
                        active = active,
                        isClose = true,
                        onClick = onClose,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.55f))
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = if (reserveResizeFooter && isFloating) 16.dp else 0.dp)) {
                    content()
                }
            }
            if (immersive && onToggleFullscreen != null) {
                var revealBar by remember { mutableStateOf(true) }
                LaunchedEffect(revealBar) {
                    if (revealBar) {
                        delay(2800L)
                        revealBar = false
                    }
                }
                // Thin top-edge hot zone: moving the cursor or tapping near the top reveals exit controls.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(0.5f)
                        .height(if (revealBar) 52.dp else 14.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Enter ||
                                        event.type == PointerEventType.Move ||
                                        event.type == PointerEventType.Press
                                    ) {
                                        revealBar = true
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (revealBar) {
                        Surface(
                            modifier = Modifier.padding(top = 8.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = colors.surfaceContainer.copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
                            shadowElevation = 10.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                leadingIcon?.invoke()
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FilledTonalButton(
                                    onClick = onToggleFullscreen,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Icon(Icons.Default.FullscreenExit, null, Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.exit_fullscreen), style = MaterialTheme.typography.labelSmall)
                                }
                                WindowCaptionButton(
                                    icon = Icons.Default.Close,
                                    label = stringResource(R.string.close_tool, title),
                                    iconSize = 15.dp,
                                    active = true,
                                    isClose = true,
                                    onClick = onClose,
                                )
                            }
                        }
                    }
                }
            }
            if (isFloating) {
                val gripColor = colors.onSurfaceVariant.copy(alpha = if (active) 0.55f else 0.30f)
                Box(
                    Modifier.align(AbsoluteAlignment.BottomRight)
                        .size(28.dp)
                        .windowDrag(true)
                        .pointerHoverIcon(PointerIcon.Crosshair),
                    contentAlignment = AbsoluteAlignment.BottomRight,
                ) {
                    Canvas(Modifier.padding(5.dp).size(10.dp)) {
                        val stroke = 1.4.dp.toPx()
                        drawLine(
                            color = gripColor,
                            start = Offset(size.width * 0.15f, size.height),
                            end = Offset(size.width, size.height * 0.15f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = gripColor,
                            start = Offset(size.width * 0.58f, size.height),
                            end = Offset(size.width, size.height * 0.58f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowCaptionButton(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    active: Boolean,
    isClose: Boolean = false,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val highlighted = hovered || focused
    val bg = when {
        isClose && highlighted -> colors.errorContainer
        highlighted -> colors.surfaceContainerHighest
        else -> Color.Transparent
    }
    val tint = when {
        isClose && highlighted -> colors.onErrorContainer
        active -> colors.onSurface.copy(alpha = 0.85f)
        else -> colors.onSurfaceVariant.copy(alpha = 0.72f)
    }
    Box(
        Modifier.size(width = 30.dp, height = 24.dp)
            .clip(shape)
            .background(bg)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
    }
}

@Composable
internal fun NotesPanel(note: String, onNote: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            note, { onNote(it.take(32_000)) }, Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            label = { Text(stringResource(R.string.note_draft)) },
            placeholder = { Text(stringResource(R.string.note_placeholder)) },
        )
        Text(stringResource(R.string.local_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsPanel(
    preferences: WorkspacePreferences,
    customWallpaperBitmap: ImageBitmap?,
    caps: DesktopCapabilities,
    widthDp: Int,
    heightDp: Int,
    physicalWidthPx: Int,
    physicalHeightPx: Int,
    nativeDensity: Float,
    densityDpi: Int,
    displayScale: DisplayScale,
    adbAccess: AdbAccess,
    openAppCount: Int,
    onAdbPermission: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onWallpaper: (Wallpaper) -> Unit,
    onPickWallpaper: () -> Unit,
    onDisplayScale: (DisplayScale) -> Unit,
    onDock: (DockStyle) -> Unit,
    dockHeightDp: Int,
    onDockHeightPreview: (Int) -> Unit,
    onDockHeightCommit: (Int) -> Unit,
    onDockAlignment: (DockAlignment) -> Unit,
    onShowClock: (Boolean) -> Unit,
    onShowSystemTray: (Boolean) -> Unit,
    onShowDesktopShortcuts: (Boolean) -> Unit,
    onWindowScale: (WindowScale) -> Unit,
    onWindowResizeMode: (WindowResizeMode) -> Unit,
    onRestoreOpenApps: (Boolean) -> Unit,
    onCloseAllApps: () -> Unit,
    onHome: () -> Unit,
    onScreen: (Int) -> Unit,
    onTouchpad: (Int) -> Unit = {},
    activeExternalDisplayId: Int = Display.INVALID_DISPLAY,
    onDisconnectExternal: (Int) -> Unit = {},
    launchingDisplayId: Int? = null,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = isThemeDark(preferences.theme)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard(title = R.string.display_resolution_title) {
            DisplayScalePicker(
                current = displayScale,
                externalDisplay = caps.external,
                displayId = caps.currentDisplay,
                physicalWidthPx = physicalWidthPx,
                physicalHeightPx = physicalHeightPx,
                nativeDensity = nativeDensity,
                onSelect = onDisplayScale,
            )
            ChoiceChipRow(R.string.window_scale, WindowScale.entries, preferences.windowScale, WindowScale::label, onWindowScale)
        }

        SettingsCard(title = R.string.appearance) {
            ChoiceChipRow(R.string.theme, ThemeMode.entries, preferences.theme, ThemeMode::label, onTheme)
            WallpaperPicker(
                current = preferences.wallpaper,
                dark = dark,
                customBitmap = customWallpaperBitmap,
                onWallpaper = onWallpaper,
                onPickWallpaper = onPickWallpaper,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.show_desktop_shortcuts),
                checked = preferences.showDesktopShortcuts,
                onCheckedChange = onShowDesktopShortcuts,
            )
        }

        SettingsCard(title = R.string.dock) {
            ChoiceChipRow(R.string.dock_style, DockStyle.entries, preferences.dock, DockStyle::label, onDock)
            DockHeightSlider(
                dockHeightDp = dockHeightDp,
                externalDisplay = caps.external,
                displayId = caps.currentDisplay,
                onPreview = onDockHeightPreview,
                onCommit = onDockHeightCommit,
            )
            ChoiceChipRow(R.string.dock_alignment, DockAlignment.entries, preferences.dockAlignment, DockAlignment::label, onDockAlignment)
            SettingsSwitchRow(
                title = stringResource(R.string.show_system_tray),
                checked = preferences.showSystemTray,
                onCheckedChange = onShowSystemTray,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.show_clock),
                checked = preferences.showClock,
                onCheckedChange = onShowClock,
            )
        }

        SettingsCard(title = R.string.app_windows_section) {
            ChoiceChipRow(R.string.window_resize_mode, WindowResizeMode.entries, preferences.windowResizeMode, WindowResizeMode::label, onWindowResizeMode)
            SettingsSwitchRow(
                title = stringResource(R.string.restore_open_apps),
                subtitle = stringResource(R.string.restore_open_apps_desc),
                checked = preferences.restoreOpenApps,
                onCheckedChange = onRestoreOpenApps,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.open_app_count, openAppCount, openAppCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
                if (openAppCount > 0) {
                    FilledTonalButton(
                        onClick = onCloseAllApps,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.close_all_windows), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (adbAccess != AdbAccess.NotIncluded) {
            SettingsCard(title = R.string.adb_window_title) {
                AdbAccessPanel(adbAccess, onAdbPermission)
            }
        }

        SettingsCard(title = R.string.home_screen) {
            Text(stringResource(if (caps.home) R.string.home_active else R.string.home_optional),
                style = MaterialTheme.typography.bodySmall, color = secondary)
            OutlinedButton(onClick = onHome) { Text(stringResource(if (caps.home) R.string.change_home else R.string.set_home)) }
        }

        SettingsCard(title = R.string.device_check) {
            Text(stringResource(R.string.android_version, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_metrics, widthDp, heightDp, densityDpi, caps.currentDisplay),
                style = MaterialTheme.typography.bodySmall, color = secondary)
            Text(stringResource(R.string.reported_features, reported(caps.secondaryActivities), reported(caps.freeform)),
                style = MaterialTheme.typography.bodySmall, color = secondary)
            Text(stringResource(R.string.reported_features_notice), style = MaterialTheme.typography.labelSmall, color = secondary)
        }

        SettingsCard(title = R.string.external_displays) {
            ConnectedDisplays(caps, onScreen, onTouchpad, activeExternalDisplayId, onDisconnectExternal, launchingDisplayId)
        }
    }
}

@Composable
private fun SettingsCard(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun <T> ChoiceChipRow(
    @StringRes label: Int,
    choices: List<T>,
    current: T,
    text: (T) -> Int,
    onChoice: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { value ->
                val selected = value == current
                FilterChip(
                    selected = selected,
                    onClick = { onChoice(value) },
                    label = { Text(stringResource(text(value)), style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = if (selected) ({ Icon(Icons.Default.Check, null, Modifier.size(15.dp)) }) else null,
                )
            }
        }
    }
}

@Composable
private fun WallpaperPicker(
    current: Wallpaper,
    dark: Boolean,
    customBitmap: ImageBitmap?,
    onWallpaper: (Wallpaper) -> Unit,
    onPickWallpaper: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val presets = remember { Wallpaper.entries.filterNot { it == Wallpaper.Custom } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wallpaper), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { item ->
                WallpaperSwatch(
                    label = stringResource(item.label),
                    selected = item == current,
                    onClick = { onWallpaper(item) },
                ) {
                    DesktopWallpaper(item, dark = dark, modifier = Modifier.fillMaxSize())
                }
            }
            WallpaperSwatch(
                label = stringResource(R.string.wallpaper_custom),
                selected = current == Wallpaper.Custom && customBitmap != null,
                onClick = {
                    if (customBitmap != null && current != Wallpaper.Custom) onWallpaper(Wallpaper.Custom)
                    else onPickWallpaper()
                },
            ) {
                if (customBitmap != null) {
                    DesktopWallpaper(Wallpaper.Custom, dark = dark, customBitmap = customBitmap, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier.fillMaxSize().background(colors.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(22.dp), tint = colors.primary)
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = onPickWallpaper,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.choose_wallpaper_image), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WallpaperSwatch(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 80.dp, height = 50.dp)
                .clip(shape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                    shape = shape,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            preview()
            if (selected) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, stringResource(R.string.setting_selected),
                        Modifier.size(13.dp), tint = colors.onPrimary)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DockHeightSlider(
    dockHeightDp: Int,
    externalDisplay: Boolean,
    displayId: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val defaultHeight = defaultDockHeightDp(externalDisplay)
    var sliderValue by remember { mutableFloatStateOf(dockHeightDp.toFloat()) }
    LaunchedEffect(dockHeightDp) {
        if (sliderValue.roundToInt() != dockHeightDp) {
            sliderValue = dockHeightDp.toFloat()
        }
    }
    val liveHeight = sliderValue.roundToInt().coerceIn(MIN_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.dock_size),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.dock_size_value, liveHeight),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
            }
            if (liveHeight != defaultHeight) {
                TextButton(
                    onClick = {
                        sliderValue = defaultHeight.toFloat()
                        onCommit(defaultHeight)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text(stringResource(R.string.dock_size_reset), style = MaterialTheme.typography.labelSmall)
                }
            }
            DisplayTargetBadge(externalDisplay = externalDisplay, displayId = displayId)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.dock_size_small),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onPreview(it.roundToInt())
                },
                onValueChangeFinished = {
                    onCommit(sliderValue.roundToInt())
                },
                valueRange = MIN_DOCK_HEIGHT_DP.toFloat()..MAX_DOCK_HEIGHT_DP.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.dock_size_large),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisplayTargetBadge(externalDisplay: Boolean, displayId: Int) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.secondaryContainer.copy(alpha = 0.7f),
    ) {
        Text(
            if (externalDisplay) stringResource(R.string.tray_display_external, displayId)
            else stringResource(R.string.tray_display_builtin),
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSecondaryContainer,
        )
    }
}

@Composable
private fun DisplayScalePicker(
    current: DisplayScale,
    externalDisplay: Boolean,
    displayId: Int,
    physicalWidthPx: Int,
    physicalHeightPx: Int,
    nativeDensity: Float,
    onSelect: (DisplayScale) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hasPhysical = physicalWidthPx > 0 && physicalHeightPx > 0
    val (currentW, currentH) = if (hasPhysical) {
        logicalResolution(physicalWidthPx, physicalHeightPx, nativeDensity, current)
    } else 0 to 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (hasPhysical) {
                Text(
                    stringResource(
                        R.string.display_resolution_summary,
                        currentW,
                        currentH,
                        current.percent,
                        physicalWidthPx,
                        physicalHeightPx,
                    ),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            DisplayTargetBadge(externalDisplay = externalDisplay, displayId = displayId)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DisplayScale.entries.forEach { option ->
                val selected = option == current
                val interactions = remember { MutableInteractionSource() }
                val hovered by interactions.collectIsHoveredAsState()
                val focused by interactions.collectIsFocusedAsState()
                val (optW, optH) = if (hasPhysical) {
                    logicalResolution(physicalWidthPx, physicalHeightPx, nativeDensity, option)
                } else 0 to 0
                val shape = RoundedCornerShape(12.dp)
                val cardBg = when {
                    selected -> colors.primary.copy(alpha = 0.14f)
                    hovered || focused -> colors.surfaceContainerHighest
                    else -> colors.surfaceContainerLow
                }
                val labelText = stringResource(option.label)
                Column(
                    Modifier.width(86.dp)
                        .clip(shape)
                        .background(cardBg)
                        .border(
                            width = if (selected || focused) 2.dp else 1.dp,
                            color = if (selected || focused) colors.primary else colors.outlineVariant.copy(alpha = 0.55f),
                            shape = shape,
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            role = Role.RadioButton,
                            onClickLabel = "$labelText ${option.percent}%",
                            onClick = { onSelect(option) },
                        )
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Stylized macOS-like miniature display preview scaled to represent UI density.
                    val windowFraction = (0.36f + option.scaleFactor * 0.34f).coerceIn(0.54f, 0.84f)
                    val dockHeight = (3.5f + option.scaleFactor * 3f).dp
                    Box(
                        Modifier.fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceContainerHighest)
                            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                            .padding(3.dp),
                    ) {
                        Box(
                            Modifier.align(Alignment.TopStart)
                                .fillMaxWidth(windowFraction)
                                .fillMaxHeight(windowFraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (selected) colors.primary.copy(alpha = 0.25f) else colors.surfaceContainer)
                                .border(0.75.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(3.dp)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .height((3.5f * option.scaleFactor).dp)
                                    .background(if (selected) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.7f)),
                            )
                        }
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth(0.72f)
                                .height(dockHeight)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) colors.primary.copy(alpha = 0.85f) else colors.onSurfaceVariant.copy(alpha = 0.45f)),
                        )
                    }
                    Text(
                        "${option.percent}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.primary else colors.onSurface,
                    )
                    if (hasPhysical) {
                        Text(
                            "${optW}×${optH}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        labelText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = if (selected) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AdbAccessPanel(access: AdbAccess, onPermission: () -> Unit) {
    val message = when (access) {
        AdbAccess.NotIncluded -> return
        AdbAccess.NotRunning -> R.string.adb_not_running
        AdbAccess.WrongBackend -> R.string.adb_wrong_backend
        AdbAccess.NeedsPermission -> R.string.adb_needs_permission
        AdbAccess.Granted -> R.string.adb_available
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (access == AdbAccess.NeedsPermission) OutlinedButton(onClick = onPermission) { Text(stringResource(R.string.adb_grant)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectedDisplays(
    caps: DesktopCapabilities,
    onScreen: (Int) -> Unit,
    onTouchpad: (Int) -> Unit = {},
    activeExternalDisplayId: Int = Display.INVALID_DISPLAY,
    onDisconnectExternal: (Int) -> Unit = {},
    launchingDisplayId: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val secondary = colors.onSurfaceVariant
    val screens = caps.screens.filterNot { it.id == Display.DEFAULT_DISPLAY }
    if (screens.isEmpty()) Text(stringResource(R.string.no_external_display), style = MaterialTheme.typography.bodySmall, color = secondary)
    screens.forEach { screen ->
        val runningOnScreen = screen.id == caps.currentDisplay || screen.id == activeExternalDisplayId
        Text(stringResource(R.string.display_details, screen.id, screen.name, screen.width, screen.height),
            style = MaterialTheme.typography.bodySmall, color = secondary)
        if (screen.id == caps.currentDisplay) {
            Text(stringResource(R.string.display_current), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!runningOnScreen && screen.canOpenWorkspace) {
                val launching = launchingDisplayId == screen.id
                OutlinedButton(
                    onClick = { onScreen(screen.id) },
                    enabled = launchingDisplayId == null,
                ) {
                    if (launching) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.opening_external_display, screen.id))
                    } else {
                        Text(stringResource(R.string.test_display, screen.id))
                    }
                }
            }
            if (!caps.external) {
                FilledTonalButton(onClick = { onTouchpad(screen.id) }) {
                    Icon(Icons.Default.TouchApp, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.open_touchpad_mode))
                }
            }
            if (runningOnScreen) {
                OutlinedButton(
                    onClick = { onDisconnectExternal(screen.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                    border = BorderStroke(1.dp, colors.error.copy(alpha = 0.6f)),
                ) {
                    Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.disconnect_external_display))
                }
            }
        }
        if (!runningOnScreen && !screen.canOpenWorkspace) {
            Text(stringResource(R.string.display_direct_launch_unavailable), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
    }
    Text(stringResource(R.string.dex_tip), style = MaterialTheme.typography.labelSmall, color = secondary)
}

@Composable
private fun reported(value: Boolean) = stringResource(if (value) R.string.supported else R.string.not_supported)
