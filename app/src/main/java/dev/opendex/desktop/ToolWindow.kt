package dev.opendex.desktop

import android.os.Build
import android.view.Display
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opendex.R
import dev.opendex.data.DockStyle
import dev.opendex.data.ThemeMode
import dev.opendex.data.Wallpaper
import dev.opendex.data.WorkspacePreferences
import dev.opendex.platform.DesktopCapabilities
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.ToolWindow(
    window: DesktopWindow, area: Area, active: Boolean, onActivate: () -> Unit,
    onTransform: (Float, Float, Boolean) -> Unit, onTransformEnd: () -> Unit,
    onPlacement: (Placement) -> Unit, onMinimize: () -> Unit, onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val b = window.bounds(area)
    val canTile = area.width >= 540f
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val title = stringResource(window.tool.title)
    val titleFocus = remember { FocusRequester() }
    val titleInteractions = remember { MutableInteractionSource() }
    val titleFocused by titleInteractions.collectIsFocusedAsState()
    var menu by remember { mutableStateOf(false) }
    var windowHasFocus by remember { mutableStateOf(false) }
    var pointerActivated by remember { mutableStateOf(false) }
    val isActive by rememberUpdatedState(active)
    val activate by rememberUpdatedState(onActivate)
    val transform by rememberUpdatedState(onTransform)
    val finish by rememberUpdatedState(onTransformEnd)
    LaunchedEffect(active) {
        if (active) {
            // Let pointer targets (including the note field) handle their own focus.
            if (!pointerActivated && !windowHasFocus) titleFocus.requestFocus()
            pointerActivated = false
        }
    }
    fun Modifier.windowDrag(resize: Boolean) = pointerInput(area, density, resize) {
        detectDragGestures(onDragEnd = { finish() }, onDragCancel = { finish() }) { change, delta ->
            change.consume()
            with(density) { transform(delta.x.toDp().value, delta.y.toDp().value, resize) }
        }
    }

    Surface(
        Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { with(density) { IntOffset(b.x.dp.toPx().roundToInt(), b.y.dp.toPx().roundToInt()) } }
            .size(b.width.dp, b.height.dp)
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
        color = colors.surface, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) colors.outline else colors.outlineVariant),
        shadowElevation = if (active) 12.dp else 4.dp,
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).background(if (active) colors.surfaceContainerHigh else colors.surfaceContainer),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .then(if (titleFocused) Modifier.border(2.dp, colors.primary) else Modifier)
                        .pointerHoverIcon(PointerIcon.Hand).windowDrag(false)
                        .focusRequester(titleFocus)
                        .clickable(titleInteractions, indication = null, role = Role.Button, onClick = onActivate)
                        .padding(start = 12.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        ToolButton(Icons.Default.Window, stringResource(R.string.window_layout), { menu = true })
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            fun choose(placement: Placement) { menu = false; onPlacement(placement) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.float_window)) }, onClick = { choose(Placement.Floating) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.maximize)) }, onClick = { choose(Placement.Full) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.snap_left)) }, enabled = canTile, onClick = { choose(Placement.Left) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.snap_right)) }, enabled = canTile, onClick = { choose(Placement.Right) })
                        }
                    }
                    ToolButton(Icons.Default.Remove, stringResource(R.string.minimize_tool, title), onMinimize)
                    ToolButton(Icons.Default.Close, stringResource(R.string.close_tool, title), onClose)
                }
                HorizontalDivider(color = colors.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = if (window.placement == Placement.Floating) 22.dp else 0.dp)) { content() }
            }
            if (window.placement == Placement.Floating) {
                Box(Modifier.align(AbsoluteAlignment.BottomRight).size(38.dp).windowDrag(true).pointerHoverIcon(PointerIcon.Crosshair),
                    contentAlignment = AbsoluteAlignment.BottomRight) {
                    Icon(Icons.Default.DragHandle, null, Modifier.padding(4.dp).size(19.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun NotesPanel(note: String, onNote: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            note, { onNote(it.take(32_000)) }, Modifier.weight(1f).fillMaxWidth(),
            label = { Text(stringResource(R.string.note_draft)) }, placeholder = { Text(stringResource(R.string.note_placeholder)) },
        )
        Text(stringResource(R.string.local_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsPanel(
    preferences: WorkspacePreferences, caps: DesktopCapabilities, widthDp: Int, heightDp: Int, densityDpi: Int,
    onTheme: (ThemeMode) -> Unit, onWallpaper: (Wallpaper) -> Unit, onDock: (DockStyle) -> Unit,
    onHome: () -> Unit, onScreen: (Int) -> Unit,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        SettingChoice(R.string.theme, ThemeMode.entries, preferences.theme, ThemeMode::label, onTheme)
        SettingChoice(R.string.wallpaper, Wallpaper.entries, preferences.wallpaper, Wallpaper::label, onWallpaper)
        SettingChoice(R.string.dock, DockStyle.entries, preferences.dock, DockStyle::label, onDock)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(stringResource(R.string.home_screen), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(if (caps.home) R.string.home_active else R.string.home_optional), color = secondary)
        OutlinedButton(onClick = onHome) { Text(stringResource(if (caps.home) R.string.change_home else R.string.set_home)) }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(stringResource(R.string.device_check), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.android_version, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        Text(stringResource(R.string.workspace_metrics, widthDp, heightDp, densityDpi, caps.currentDisplay), color = secondary)
        Text(stringResource(R.string.reported_features, reported(caps.secondaryActivities), reported(caps.freeform)), color = secondary)
        Text(stringResource(R.string.reported_features_notice), color = secondary)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        ConnectedDisplays(caps, onScreen)
    }
}

@Composable
internal fun ConnectedDisplays(caps: DesktopCapabilities, onScreen: (Int) -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val screens = caps.screens.filterNot { it.id == Display.DEFAULT_DISPLAY }
    Text(stringResource(R.string.external_displays), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (screens.isEmpty()) Text(stringResource(R.string.no_external_display), color = secondary)
    screens.forEach { screen ->
        Text(stringResource(R.string.display_details, screen.id, screen.name, screen.width, screen.height), color = secondary)
        when {
            screen.id == caps.currentDisplay -> Text(stringResource(R.string.display_current), color = secondary)
            screen.canOpenWorkspace -> OutlinedButton(onClick = { onScreen(screen.id) }) {
                Text(stringResource(R.string.test_display, screen.id))
            }
            else -> Text(stringResource(R.string.display_direct_launch_unavailable), color = secondary)
        }
    }
    Text(stringResource(R.string.dex_tip), color = secondary)
}

@Composable
private fun reported(value: Boolean) = stringResource(if (value) R.string.supported else R.string.not_supported)

@Composable
private fun <T> SettingChoice(@StringRes label: Int, choices: List<T>, current: T, text: (T) -> Int, onChoice: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }) {
            Text(stringResource(R.string.setting_value, stringResource(label), stringResource(text(current))))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            choices.forEach { value ->
                DropdownMenuItem(text = { Text(stringResource(text(value))) }, onClick = { menu = false; onChoice(value) },
                    trailingIcon = if (value == current) ({ Icon(Icons.Default.Check, stringResource(R.string.selected)) }) else null)
            }
        }
    }
}
