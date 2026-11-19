package dev.opendex.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.opendex.R
import dev.opendex.data.DockStyle
import dev.opendex.platform.InstalledApp
import kotlinx.coroutines.delay

@Composable
internal fun Dock(
    apps: List<InstalledApp>, pins: List<String>, recent: List<String>, windows: List<DesktopWindow>, style: DockStyle,
    drawer: Boolean, external: Boolean, onDrawer: () -> Unit, onOpen: (InstalledApp) -> Unit,
    onPin: (InstalledApp) -> Unit, onTool: (Tool) -> Unit,
) {
    val byId = remember(apps) { apps.associateBy { it.id } }
    val floating = style == DockStyle.Floating
    val shape = RoundedCornerShape(if (floating) 20.dp else 0.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = if (floating) 12.dp else 0.dp)) {
        val showClock = maxWidth >= 430.dp
        val colors = MaterialTheme.colorScheme
        Surface(
            Modifier.fillMaxWidth().padding(bottom = if (floating) 8.dp else 0.dp),
            color = colors.surface, shape = shape, shadowElevation = if (floating) 8.dp else 0.dp,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Row(
                Modifier.height(62.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ToolButton(Icons.Default.Apps, stringResource(R.string.apps), onDrawer, selected = drawer)
                DockDivider()
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    windows.forEach { window ->
                        val icon = if (window.tool == Tool.Notes) Icons.Default.EditNote else Icons.Default.Settings
                        val title = stringResource(window.tool.title)
                        val label = if (window.minimized) stringResource(R.string.minimized_tool, title) else title
                        ToolButton(icon, label, { onTool(window.tool) }, selected = !window.minimized)
                    }
                    pins.mapNotNull(byId::get).forEach { app ->
                        AppButton(app, true, { onOpen(app) }, { onPin(app) })
                    }
                    val history = recent.filterNot { it in pins }.mapNotNull(byId::get)
                    if (history.isNotEmpty() && (pins.isNotEmpty() || windows.isNotEmpty())) DockDivider()
                    history.forEach { app -> AppButton(app, false, { onOpen(app) }, { onPin(app) }) }
                    if (showClock && windows.isEmpty() && pins.none { it in byId } && history.isEmpty()) {
                        Text(stringResource(R.string.dock_empty), Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (showClock) {
                    DockDivider()
                    DockClock(external)
                }
                ToolButton(Icons.Default.Tune, stringResource(R.string.settings_and_diagnostics), { onTool(Tool.Settings) })
            }
        }
    }
}

@Composable
private fun DockDivider() = VerticalDivider(Modifier.height(28.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun DockClock(external: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    Column(Modifier.padding(horizontal = 7.dp), horizontalAlignment = Alignment.End) {
        Text(android.text.format.DateFormat.getTimeFormat(context).format(now), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(if (external) R.string.external_display else R.string.workspace), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ToolButton(
    icon: ImageVector, label: String, onClick: () -> Unit, selected: Boolean = false, desktop: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val interactions = remember { MutableInteractionSource() }
        val focus by interactions.collectIsFocusedAsState()
        val hover by interactions.collectIsHoveredAsState()
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(12.dp)
        val fill = when {
            selected -> colors.secondaryContainer
            hover || focus -> colors.surfaceContainerHigh
            desktop -> Color(0xFF243955)
            else -> Color.Transparent
        }
        Box(
            Modifier.size(48.dp).clip(shape).background(fill)
                .then(if (focus) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactions, indication = LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (desktop && !hover && !focus) Color(0xFFA7CCFF) else colors.primary)
        }
        if (desktop) Text(label, Modifier.padding(top = 3.dp), color = Color(0xFFE5EFFF), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun AppDrawer(
    apps: List<InstalledApp>, pins: List<String>, onOpen: (InstalledApp) -> Unit,
    onTogglePin: (InstalledApp) -> Unit, onTool: (Tool) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val results = remember(apps, query) { searchApps(apps, query, InstalledApp::label) }
    val searchFocus = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val colors = MaterialTheme.colorScheme
    var scrollToSelection by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    LaunchedEffect(selected, results, scrollToSelection) {
        if (scrollToSelection && selected in results.indices && grid.layoutInfo.visibleItemsInfo.none { it.index == selected }) {
            grid.scrollToItem(selected)
        }
    }
    Surface(modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 520.dp),
        shape = RoundedCornerShape(20.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 14.dp,
    ) {
        BoxWithConstraints {
            val columns = if (maxWidth >= 480.dp) 3 else if (maxWidth >= 300.dp) 2 else 1
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.apps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.result_count, results.size, results.size),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    ToolButton(Icons.Default.EditNote, stringResource(Tool.Notes.title), { onTool(Tool.Notes) })
                    ToolButton(Icons.Default.Settings, stringResource(Tool.Settings.title), { onTool(Tool.Settings) })
                    ToolButton(Icons.Default.Close, stringResource(R.string.close_app_drawer), onClose)
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it; selected = 0; scrollToSelection = true }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onPreviewKeyEvent { event ->
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
                    label = { Text(stringResource(R.string.search_apps)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                )
                if (results.isEmpty()) {
                    Text(stringResource(if (query.isBlank()) R.string.no_apps else R.string.no_results),
                        Modifier.padding(12.dp), color = colors.onSurfaceVariant)
                } else {
                    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(results, key = { _, app -> app.id }) { index, app ->
                            AppButton(app, app.id in pins, { onOpen(app) }, { onTogglePin(app) },
                                isRow = true, highlighted = index == selected && (query.isNotBlank() || scrollToSelection))
                        }
                    }
                }
                if (maxHeight >= 320.dp) Text(stringResource(R.string.search_shortcuts),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AppButton(
    app: InstalledApp, pinned: Boolean, onOpen: () -> Unit, onPin: () -> Unit,
    isRow: Boolean = false, highlighted: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(if (isRow) Modifier.fillMaxWidth() else Modifier.size(48.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(shape)
                .background(when {
                    highlighted -> colors.secondaryContainer
                    focused || hovered -> colors.surfaceContainerHigh
                    else -> Color.Transparent
                })
                .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .pointerHoverIcon(PointerIcon.Hand)
                .onSecondaryClick { menu = true }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || (it.isShiftPressed && it.key == Key.F10))) {
                        menu = true
                        true
                    } else false
                }
                .combinedClickable(
                    interactionSource = interactions, indication = LocalIndication.current, role = Role.Button,
                    onClickLabel = stringResource(R.string.open_app, app.label),
                    onLongClickLabel = stringResource(R.string.app_options, app.label),
                    onLongClick = { menu = true }, onClick = onOpen,
                )
                .then(if (isRow) Modifier.padding(horizontal = 7.dp) else Modifier.semantics { contentDescription = app.label }),
            horizontalArrangement = if (isRow) Arrangement.spacedBy(7.dp) else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, if (isRow) 26 else 30)
            if (isRow) {
                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                if (pinned) Icon(Icons.Default.PushPin, stringResource(R.string.pinned), Modifier.size(16.dp), tint = colors.primary)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (pinned) R.string.unpin else R.string.pin_to_dock)) },
                onClick = { menu = false; onPin() }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
        }
    }
}

@Composable
private fun AppIcon(app: InstalledApp, dp: Int) {
    val px = with(LocalDensity.current) { dp.dp.roundToPx().coerceAtLeast(1) }
    val icon = remember(app.icon, px) { app.icon.toBitmap(px, px).asImageBitmap() }
    Image(icon, null, Modifier.size(dp.dp))
}

private fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) latest()
            }
        }
    }
}
