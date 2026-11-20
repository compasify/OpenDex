package dev.opendex.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.opendex.R
import dev.opendex.data.DockAlignment
import dev.opendex.data.DockSize
import dev.opendex.data.DockStyle
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.SystemStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Dock(
    apps: List<InstalledApp>,
    pins: List<String>,
    recent: List<String>,
    windows: List<DesktopWindow>,
    style: DockStyle,
    dockSize: DockSize,
    dockAlignment: DockAlignment,
    showClock: Boolean,
    showSystemTray: Boolean,
    drawer: Boolean,
    windowManagerOpen: Boolean,
    quickSettingsOpen: Boolean,
    notificationsOpen: Boolean,
    external: Boolean,
    systemStatus: SystemStatus,
    notificationCount: Int,
    onDrawer: () -> Unit,
    onWindowManager: () -> Unit,
    onQuickSettings: () -> Unit,
    onNotifications: () -> Unit,
    onOpen: (InstalledApp) -> Unit,
    onPin: (InstalledApp) -> Unit,
    onTool: (Tool) -> Unit,
    onOpenFreeform: ((InstalledApp) -> Unit)?,
    onOpenFullscreen: ((InstalledApp) -> Unit)? = null,
    onFitAboveDock: ((InstalledApp) -> Unit)? = null,
    appWindows: List<AppWindowState> = emptyList(),
    onAppWindow: ((AppWindowState) -> Unit)? = null,
    onCloseAppWindow: ((String) -> Unit)? = null,
    onOpenInWindow: ((InstalledApp) -> Unit)? = null,
    onToggleDockStyle: (() -> Unit)? = null,
    onShowDesktop: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val byId = remember(apps) { apps.associateBy { it.id } }
    val openById = remember(appWindows) { appWindows.associateBy { it.appId } }
    val floating = style == DockStyle.Floating
    val totalOpenCount = windows.size + appWindows.count { it.appId in byId }
    val shape = RoundedCornerShape(if (floating) 18.dp else 0.dp)
    val buttonDp = dockSize.buttonDp.dp
    val iconDp = dockSize.iconDp
    var dockMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier.fillMaxWidth()
            .padding(
                horizontal = if (floating) 12.dp else 0.dp,
                vertical = if (floating) 6.dp else 0.dp,
            ),
        contentAlignment = when {
            !floating -> Alignment.Center
            dockAlignment == DockAlignment.Start -> Alignment.CenterStart
            else -> Alignment.Center
        },
    ) {
        val canShowClock = showClock && maxWidth >= 420.dp
        val canShowTray = showSystemTray && maxWidth >= 360.dp
        val expandedTrayIcons = maxWidth >= 560.dp
        val colors = MaterialTheme.colorScheme
        val surfaceColor = colors.surfaceContainer.copy(alpha = if (floating) 0.90f else 0.94f)
        val dockModifier = if (floating) Modifier.widthIn(max = maxWidth) else Modifier.fillMaxWidth()

        Box {
            Surface(
                modifier = dockModifier
                    .onSecondaryClick { dockMenu = true }
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onLongClick = { dockMenu = true },
                        onClick = {},
                    ),
                color = surfaceColor,
                shape = shape,
                shadowElevation = if (floating) 12.dp else 4.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = colors.outlineVariant.copy(alpha = if (floating) 0.55f else 0.45f),
                ),
            ) {
                val pinnedApps = pins.mapNotNull(byId::get)
                val unpinnedOpen = appWindows.filterNot { it.appId in pins }.mapNotNull { w -> byId[w.appId]?.let { it to w } }
                val openIds = openById.keys
                val history = recent.filterNot { it in pins || it in openIds }.mapNotNull(byId::get)
                val hasCenterItems = windows.isNotEmpty() || pinnedApps.isNotEmpty() || unpinnedOpen.isNotEmpty() || history.isNotEmpty()
                Row(
                    Modifier.height(dockSize.heightDp.dp).padding(horizontal = if (floating) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ToolButton(Icons.Default.Apps, stringResource(R.string.apps), onDrawer, selected = drawer, size = buttonDp)
                    if (hasCenterItems) {
                        DockDivider(dockSize.heightDp)

                        val centerModifier = if (floating) {
                            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())
                        } else {
                            Modifier.weight(1f).horizontalScroll(rememberScrollState())
                        }
                        Row(
                            modifier = centerModifier,
                            horizontalArrangement = if (!floating && dockAlignment == DockAlignment.Center) {
                                Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                            } else {
                                Arrangement.spacedBy(4.dp, Alignment.Start)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            windows.forEach { window ->
                                val icon = if (window.tool == Tool.Notes) Icons.Default.EditNote else Icons.Default.Settings
                                val title = stringResource(window.tool.title)
                                val label = if (window.minimized) stringResource(R.string.minimized_tool, title) else title
                                ToolButton(
                                    icon = icon,
                                    label = label,
                                    onClick = { onTool(window.tool) },
                                    selected = !window.minimized,
                                    running = true,
                                    size = buttonDp,
                                )
                            }
                            pinnedApps.forEach { app ->
                                val openWindow = openById[app.id]
                                AppButton(
                                    app = app,
                                    pinned = true,
                                    onOpen = { if (openWindow != null && onAppWindow != null) onAppWindow(openWindow) else onOpen(app) },
                                    onPin = { onPin(app) },
                                    onOpenInWindow = onOpenInWindow?.let { { it(app) } },
                                    onOpenFreeform = onOpenFreeform?.let { { it(app) } },
                                    onOpenFullscreen = onOpenFullscreen?.let { { it(app) } },
                                    onFitAboveDock = onFitAboveDock?.let { { it(app) } },
                                    onCloseWindow = if (openWindow != null && onCloseAppWindow != null) ({ onCloseAppWindow(app.id) }) else null,
                                    running = openWindow != null,
                                    runningVisible = openWindow != null && !openWindow.minimized,
                                    buttonSize = buttonDp,
                                    iconSizeDp = iconDp,
                                )
                            }
                            if ((unpinnedOpen.isNotEmpty() || history.isNotEmpty()) && (pinnedApps.isNotEmpty() || windows.isNotEmpty())) {
                                DockDivider(dockSize.heightDp)
                            }
                            unpinnedOpen.forEach { (app, openWindow) ->
                                AppButton(
                                    app = app,
                                    pinned = false,
                                    onOpen = { if (onAppWindow != null) onAppWindow(openWindow) else onOpen(app) },
                                    onPin = { onPin(app) },
                                    onOpenInWindow = onOpenInWindow?.let { { it(app) } },
                                    onOpenFreeform = onOpenFreeform?.let { { it(app) } },
                                    onOpenFullscreen = onOpenFullscreen?.let { { it(app) } },
                                    onFitAboveDock = onFitAboveDock?.let { { it(app) } },
                                    onCloseWindow = onCloseAppWindow?.let { { it(app.id) } },
                                    running = true,
                                    runningVisible = !openWindow.minimized,
                                    buttonSize = buttonDp,
                                    iconSizeDp = iconDp,
                                )
                            }
                            history.forEach { app ->
                                AppButton(
                                    app = app,
                                    pinned = false,
                                    onOpen = { onOpen(app) },
                                    onPin = { onPin(app) },
                                    onOpenInWindow = onOpenInWindow?.let { { it(app) } },
                                    onOpenFreeform = onOpenFreeform?.let { { it(app) } },
                                    onOpenFullscreen = onOpenFullscreen?.let { { it(app) } },
                                    onFitAboveDock = onFitAboveDock?.let { { it(app) } },
                                    buttonSize = buttonDp,
                                    iconSizeDp = iconDp,
                                )
                            }
                        }
                    } else if (!floating) {
                        Spacer(Modifier.weight(1f))
                    }

                    if (totalOpenCount > 0) {
                        DockDivider(dockSize.heightDp)
                        WindowManagerButton(
                            count = totalOpenCount,
                            selected = windowManagerOpen,
                            size = buttonDp,
                            onClick = onWindowManager,
                        )
                    }

                    if (canShowTray) {
                        DockDivider(dockSize.heightDp)
                        SystemTrayCluster(
                            status = systemStatus,
                            expandedIcons = expandedTrayIcons,
                            selected = quickSettingsOpen,
                            height = buttonDp,
                            onClick = onQuickSettings,
                        )
                        NotificationTrayButton(
                            count = notificationCount,
                            selected = notificationsOpen,
                            size = buttonDp,
                            onClick = onNotifications,
                        )
                    }

                    if (canShowClock) {
                        if (!canShowTray) DockDivider(dockSize.heightDp)
                        DockClock(
                            external = external,
                            compact = dockSize == DockSize.Compact,
                            onClick = if (canShowTray) onQuickSettings else null,
                        )
                    }
                    ToolButton(Icons.Default.Tune, stringResource(R.string.settings_and_diagnostics), { onTool(Tool.Settings) }, size = buttonDp)
                }
            }

            DropdownMenu(expanded = dockMenu, onDismissRequest = { dockMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dock_settings)) },
                    leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                    onClick = { dockMenu = false; onTool(Tool.Settings) },
                )
                if (onToggleDockStyle != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (floating) R.string.switch_dock_panel else R.string.switch_dock_floating)) },
                        leadingIcon = { Icon(Icons.Default.Dock, null, Modifier.size(18.dp)) },
                        onClick = { dockMenu = false; onToggleDockStyle() },
                    )
                }
                if (onShowDesktop != null && totalOpenCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_desktop)) },
                        leadingIcon = { Icon(Icons.Default.DesktopWindows, null, Modifier.size(18.dp)) },
                        onClick = { dockMenu = false; onShowDesktop() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DockDivider(dockHeightDp: Int) = VerticalDivider(
    Modifier.height((dockHeightDp * 0.45f).dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
)

@Composable
private fun WindowManagerButton(count: Int, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focus by interactions.collectIsFocusedAsState()
    val hover by interactions.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val label = pluralStringResource(R.plurals.manage_open_windows, count, count)
    val fill = when {
        selected -> colors.secondaryContainer
        hover || focus -> colors.surfaceContainerHighest
        else -> Color.Transparent
    }
    Row(
        Modifier.height(size)
            .clip(shape)
            .background(fill)
            .then(if (focus) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactions, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Default.DynamicFeed, null, Modifier.size(18.dp), tint = colors.primary)
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(colors.primary.copy(alpha = 0.16f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun DockClock(external: Boolean, compact: Boolean, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val date = remember(now) { Date(now) }
    val timeText = android.text.format.DateFormat.getTimeFormat(context).format(date)
    val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    val shape = RoundedCornerShape(8.dp)
    val clickModifier = if (onClick != null) {
        Modifier.clip(shape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick)
    } else Modifier
    Column(
        clickModifier.padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            timeText,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (!compact) {
            Text(
                if (external) stringResource(R.string.external_display) else dateText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun OpenWindowsPopover(
    appWindows: List<AppWindowState>,
    toolWindows: List<DesktopWindow>,
    appsById: Map<String, InstalledApp>,
    onFocusApp: (String) -> Unit,
    onToggleMinimizeApp: (AppWindowState) -> Unit,
    onCloseApp: (String) -> Unit,
    onFocusTool: (Tool) -> Unit,
    onToggleMinimizeTool: (DesktopWindow) -> Unit,
    onCloseTool: (Tool) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onClosePopover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val visibleAppEntries = appWindows.mapNotNull { w -> appsById[w.appId]?.let { it to w } }
    val total = visibleAppEntries.size + toolWindows.size
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 380.dp).heightIn(max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pluralStringResource(R.plurals.manage_open_windows, total, total),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (total > 0) {
                    TextButton(
                        onClick = { onMinimizeAll(); onClosePopover() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.minimize_all), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { onCloseAll(); onClosePopover() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    ) {
                        Text(stringResource(R.string.close_all_windows), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
            if (total == 0) {
                Text(
                    stringResource(R.string.no_open_windows),
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visibleAppEntries.forEach { (app, win) ->
                        OpenWindowRow(
                            title = app.label,
                            minimized = win.minimized,
                            leading = { AppIcon(app, 22) },
                            onFocus = { onFocusApp(app.id); onClosePopover() },
                            onToggleMinimize = { onToggleMinimizeApp(win) },
                            onClose = { onCloseApp(app.id) },
                        )
                    }
                    toolWindows.forEach { win ->
                        val title = stringResource(win.tool.title)
                        val icon = if (win.tool == Tool.Notes) Icons.Default.EditNote else Icons.Default.Settings
                        OpenWindowRow(
                            title = title,
                            minimized = win.minimized,
                            leading = { Icon(icon, null, Modifier.size(20.dp), tint = colors.primary) },
                            onFocus = { onFocusTool(win.tool); onClosePopover() },
                            onToggleMinimize = { onToggleMinimizeTool(win) },
                            onClose = { onCloseTool(win.tool) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenWindowRow(
    title: String,
    minimized: Boolean,
    leading: @Composable () -> Unit,
    onFocus: () -> Unit,
    onToggleMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (hovered) colors.surfaceContainerHighest else colors.surfaceContainerLow)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactions, LocalIndication.current, role = Role.Button, onClick = onFocus)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (minimized) R.string.window_state_minimized else R.string.window_state_open),
                style = MaterialTheme.typography.labelSmall,
                color = if (minimized) colors.onSurfaceVariant else colors.primary,
            )
        }
        IconButton(onClick = onToggleMinimize, modifier = Modifier.size(28.dp)) {
            Icon(
                if (minimized) Icons.Default.OpenInFull else Icons.Default.Remove,
                contentDescription = stringResource(if (minimized) R.string.restore_window else R.string.minimize_tool, title),
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.close_tool, title),
                modifier = Modifier.size(16.dp),
                tint = colors.error,
            )
        }
    }
}

@Composable
internal fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    running: Boolean = false,
    desktop: Boolean = false,
    size: Dp = 44.dp,
) {
    Column(
        modifier = if (desktop) Modifier.width(88.dp) else Modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val interactions = remember { MutableInteractionSource() }
        val focus by interactions.collectIsFocusedAsState()
        val hover by interactions.collectIsHoveredAsState()
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(11.dp)
        val fill = when {
            selected -> colors.secondaryContainer
            hover || focus -> colors.surfaceContainerHighest
            desktop -> colors.surfaceContainer.copy(alpha = 0.72f)
            else -> Color.Transparent
        }
        Box(
            Modifier.size(size)
                .clip(shape)
                .background(fill)
                .then(
                    when {
                        focus -> Modifier.border(2.dp, colors.primary, shape)
                        desktop -> Modifier.border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), shape)
                        else -> Modifier
                    },
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactions, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(if (size <= 40.dp) 19.dp else 22.dp),
                tint = if (desktop && !hover && !focus) colors.primary else colors.primary,
            )
            if (!desktop && running) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(width = if (selected) 14.dp else 6.dp, height = 2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (selected) colors.primary else colors.onSurfaceVariant),
                )
            }
        }
        if (desktop) {
            Surface(
                Modifier.padding(top = 4.dp),
                shape = RoundedCornerShape(6.dp),
                color = colors.surfaceContainer.copy(alpha = 0.65f),
            ) {
                Text(
                    label,
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AppDrawer(
    apps: List<InstalledApp>,
    pins: List<String>,
    runningAppIds: Set<String> = emptySet(),
    onOpen: (InstalledApp) -> Unit,
    onTogglePin: (InstalledApp) -> Unit,
    onTool: (Tool) -> Unit,
    onClose: () -> Unit,
    onOpenFreeform: ((InstalledApp) -> Unit)?,
    onOpenFullscreen: ((InstalledApp) -> Unit)? = null,
    onFitAboveDock: ((InstalledApp) -> Unit)? = null,
    onOpenInWindow: ((InstalledApp) -> Unit)? = null,
    focusSearchOnOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val results = remember(apps, query) { searchApps(apps, query, InstalledApp::label) }
    val searchFocus = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val colors = MaterialTheme.colorScheme
    var scrollToSelection by remember { mutableStateOf(false) }
    LaunchedEffect(focusSearchOnOpen) {
        if (focusSearchOnOpen) searchFocus.requestFocus()
    }
    LaunchedEffect(selected, results, scrollToSelection) {
        if (scrollToSelection && selected in results.indices && grid.layoutInfo.visibleItemsInfo.none { it.index == selected }) {
            grid.scrollToItem(selected)
        }
    }
    Surface(
        modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 520.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 18.dp,
    ) {
        BoxWithConstraints {
            val columns = if (maxWidth >= 480.dp) 3 else if (maxWidth >= 300.dp) 2 else 1
            val showShortcuts = maxHeight >= 320.dp
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            pluralStringResource(R.plurals.result_count, results.size, results.size),
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    ToolButton(Icons.Default.EditNote, stringResource(Tool.Notes.title), { onTool(Tool.Notes) }, size = 38.dp)
                    ToolButton(Icons.Default.Settings, stringResource(Tool.Settings.title), { onTool(Tool.Settings) }, size = 38.dp)
                    ToolButton(Icons.Default.Close, stringResource(R.string.close_app_drawer), onClose, size = 38.dp)
                }
                val searchInteraction = remember { MutableInteractionSource() }
                val searchFocused by searchInteraction.collectIsFocusedAsState()
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0; scrollToSelection = true },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    interactionSource = searchInteraction,
                    modifier = Modifier.fillMaxWidth().height(36.dp).focusRequester(searchFocus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown, Key.DirectionUp -> {
                                if (results.isNotEmpty()) {
                                    val delta = if (event.key == Key.DirectionDown) 1 else -1
                                    selected = (selected + delta).coerceIn(0, results.lastIndex)
                                    scrollToSelection = true
                                }
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> { results.getOrNull(selected)?.let(onOpen); true }
                            else -> false
                        }
                    },
                    decorationBox = { innerTextField ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surfaceContainerLow,
                            border = BorderStroke(
                                width = if (searchFocused) 1.5.dp else 1.dp,
                                color = if (searchFocused) colors.primary else colors.outline,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.onSurfaceVariant,
                                )
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) {
                                        Text(
                                            stringResource(R.string.search_apps),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    },
                )
                if (results.isEmpty()) {
                    Text(
                        stringResource(if (query.isBlank()) R.string.no_apps else R.string.no_results),
                        Modifier.padding(12.dp),
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(columns),
                        Modifier.weight(1f),
                        state = grid,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(results, key = { _, app -> app.id }) { index, app ->
                            AppButton(
                                app = app,
                                pinned = app.id in pins,
                                onOpen = { onOpen(app) },
                                onPin = { onTogglePin(app) },
                                onOpenInWindow = onOpenInWindow?.let { { it(app) } },
                                onOpenFreeform = onOpenFreeform?.let { { it(app) } },
                                onOpenFullscreen = onOpenFullscreen?.let { { it(app) } },
                                onFitAboveDock = onFitAboveDock?.let { { it(app) } },
                                isRow = true,
                                highlighted = index == selected && (query.isNotBlank() || scrollToSelection),
                                running = app.id in runningAppIds,
                            )
                        }
                    }
                }
                if (showShortcuts) Text(
                    stringResource(R.string.search_shortcuts),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppButton(
    app: InstalledApp,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onOpenFreeform: (() -> Unit)?,
    onOpenFullscreen: (() -> Unit)? = null,
    onFitAboveDock: (() -> Unit)? = null,
    onOpenInWindow: (() -> Unit)? = null,
    onCloseWindow: (() -> Unit)? = null,
    isRow: Boolean = false,
    highlighted: Boolean = false,
    running: Boolean = false,
    runningVisible: Boolean = false,
    buttonSize: Dp = 44.dp,
    iconSizeDp: Int = 28,
) {
    var menu by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val dockDescription = when {
        running && !runningVisible -> stringResource(R.string.minimized_app, app.label)
        running -> stringResource(R.string.running_app, app.label)
        else -> app.label
    }
    Box(if (isRow) Modifier.fillMaxWidth() else Modifier.size(buttonSize)) {
        Row(
            Modifier.fillMaxWidth().height(if (isRow) 44.dp else buttonSize).clip(shape)
                .background(
                    when {
                        highlighted || runningVisible -> colors.secondaryContainer
                        focused || hovered -> colors.surfaceContainerHighest
                        else -> Color.Transparent
                    },
                )
                .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
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
                )
                .then(if (isRow) Modifier.padding(horizontal = 8.dp) else Modifier.semantics { contentDescription = dockDescription }),
            horizontalArrangement = if (isRow) Arrangement.spacedBy(8.dp) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, if (isRow) 24 else iconSizeDp)
            if (isRow) {
                Text(
                    app.label,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.primary))
                }
                if (pinned) Icon(Icons.Default.PushPin, stringResource(R.string.pinned), Modifier.size(15.dp), tint = colors.primary)
            }
        }
        if (!isRow && running) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(width = if (runningVisible) 16.dp else 7.dp, height = 2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (runningVisible) colors.primary else colors.onSurfaceVariant),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onOpenInWindow != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.open_app_in_window)) },
                leadingIcon = { Icon(Icons.Default.Window, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onOpenInWindow() },
            )
            if (onOpenFullscreen != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.open_app_fullscreen)) },
                leadingIcon = { Icon(Icons.Default.Fullscreen, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onOpenFullscreen() },
            )
            if (onOpenFreeform != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.open_app_freeform_adb)) },
                leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onOpenFreeform() },
            )
            if (onFitAboveDock != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.fit_above_dock)) },
                leadingIcon = { Icon(Icons.Default.CropSquare, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onFitAboveDock() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.unpin else R.string.pin_to_dock)) },
                onClick = { menu = false; onPin() },
                leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(18.dp)) },
            )
            if (onCloseWindow != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.close_tool, app.label), color = colors.error) },
                    leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = colors.error) },
                    onClick = { menu = false; onCloseWindow() },
                )
            }
        }
    }
}

@Composable
internal fun AppIcon(app: InstalledApp, dp: Int) {
    val px = with(LocalDensity.current) { dp.dp.roundToPx().coerceAtLeast(1) }
    val icon = remember(app.icon, px) { app.icon.toBitmap(px, px).asImageBitmap() }
    Image(icon, null, Modifier.size(dp.dp))
}

internal fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}
