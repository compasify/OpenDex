package dev.opendex.desktop

import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.opendex.R
import dev.opendex.platform.NetworkKind
import dev.opendex.platform.SystemStatus
import dev.opendex.platform.TrayNotification
import java.util.Date
import kotlin.math.roundToInt

@Composable
internal fun SystemTrayCluster(
    status: SystemStatus,
    expandedIcons: Boolean,
    selected: Boolean,
    height: Dp,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val label = stringResource(R.string.quick_settings)
    val fill = when {
        selected -> colors.secondaryContainer
        hovered || focused -> colors.surfaceContainerHighest
        else -> Color.Transparent
    }
    Row(
        Modifier.height(height)
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactions, LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = networkIcon(status.network),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (status.network != NetworkKind.None) colors.onSurface else colors.onSurfaceVariant,
        )
        if (expandedIcons || status.bluetoothEnabled) {
            Icon(
                imageVector = if (status.bluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (status.bluetoothEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (expandedIcons) {
            Icon(
                imageVector = volumeIcon(status),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (status.muted) colors.onSurfaceVariant else colors.onSurface,
            )
        }
        if (status.batteryLevel >= 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = batteryIcon(status.batteryLevel, status.batteryCharging),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = when {
                        status.batteryCharging -> colors.primary
                        status.batteryLevel <= 15 -> colors.error
                        else -> colors.onSurface
                    },
                )
                if (expandedIcons) {
                    Text(
                        "${status.batteryLevel}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotificationTrayButton(
    count: Int,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val label = if (count > 0) pluralStringResource(R.plurals.notifications_count, count, count)
        else stringResource(R.string.notifications)
    val fill = when {
        selected -> colors.secondaryContainer
        hovered || focused -> colors.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box(
        Modifier.size(size)
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactions, LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (count > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
            contentDescription = null,
            modifier = Modifier.size(if (size <= 40.dp) 18.dp else 20.dp),
            tint = if (count > 0 || selected) colors.primary else colors.onSurface,
        )
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .sizeIn(minWidth = 14.dp, minHeight = 14.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 9) "9+" else count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                    color = colors.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun QuickSettingsPopover(
    status: SystemStatus,
    darkTheme: Boolean,
    externalDisplay: Boolean,
    displayId: Int,
    onInternet: () -> Unit,
    onBluetooth: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleTheme: () -> Unit,
    onCast: () -> Unit,
    onDisplaySettings: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onWorkspaceSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onShowDesktop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val volumePct = ((status.volume.toFloat() / status.maxVolume.coerceAtLeast(1)) * 100f).roundToInt()
    val brightnessPct = (status.brightness * 100f).roundToInt()

    Surface(
        modifier = modifier.widthIn(min = 310.dp, max = 360.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.quick_settings),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (externalDisplay) stringResource(R.string.tray_display_external, displayId)
                        else stringResource(R.string.tray_display_builtin),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDismiss(); onWorkspaceSettings() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, stringResource(R.string.workspace_settings), Modifier.size(18.dp))
                }
                IconButton(onClick = { onDismiss(); onSystemSettings() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Settings, stringResource(R.string.android_settings), Modifier.size(18.dp))
                }
            }

            // 2x3 Samsung DeX-style quick tiles
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile(
                        icon = networkIcon(status.network),
                        title = stringResource(R.string.tray_wifi),
                        subtitle = stringResource(
                            when {
                                status.network == NetworkKind.Wifi -> R.string.tray_connected
                                status.network == NetworkKind.Ethernet -> R.string.tray_ethernet
                                status.network == NetworkKind.Cellular -> R.string.tray_cellular
                                status.wifiEnabled -> R.string.tray_on
                                else -> R.string.tray_off
                            },
                        ),
                        active = status.network != NetworkKind.None || status.wifiEnabled,
                        onClick = { onDismiss(); onInternet() },
                        modifier = Modifier.weight(1f),
                    )
                    QuickTile(
                        icon = if (status.bluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        title = stringResource(R.string.tray_bluetooth),
                        subtitle = stringResource(if (status.bluetoothEnabled) R.string.tray_on else R.string.tray_off),
                        active = status.bluetoothEnabled,
                        onClick = { onDismiss(); onBluetooth() },
                        modifier = Modifier.weight(1f),
                    )
                    QuickTile(
                        icon = volumeIcon(status),
                        title = stringResource(R.string.tray_sound),
                        subtitle = if (status.muted) stringResource(R.string.tray_muted) else "$volumePct%",
                        active = !status.muted,
                        onClick = onToggleMute,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile(
                        icon = if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        title = stringResource(R.string.theme),
                        subtitle = stringResource(if (darkTheme) R.string.theme_dark else R.string.theme_light),
                        active = darkTheme,
                        onClick = onToggleTheme,
                        modifier = Modifier.weight(1f),
                    )
                    QuickTile(
                        icon = Icons.Default.Cast,
                        title = stringResource(R.string.tray_cast),
                        subtitle = stringResource(R.string.tray_cast_sub),
                        active = externalDisplay,
                        onClick = { onDismiss(); onCast() },
                        modifier = Modifier.weight(1f),
                    )
                    QuickTile(
                        icon = Icons.Default.Brightness6,
                        title = stringResource(R.string.tray_display),
                        subtitle = "$brightnessPct%",
                        active = false,
                        onClick = { onDismiss(); onDisplaySettings() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Volume & Brightness Sliders
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onToggleMute, modifier = Modifier.size(28.dp)) {
                            Icon(volumeIcon(status), stringResource(R.string.tray_sound), Modifier.size(18.dp), tint = colors.primary)
                        }
                        Slider(
                            value = status.volume.toFloat(),
                            onValueChange = { onVolumeChange(it.roundToInt()) },
                            valueRange = 0f..status.maxVolume.toFloat(),
                            modifier = Modifier.weight(1f).height(28.dp),
                        )
                        Text(
                            "$volumePct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onDismiss(); onDisplaySettings() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.BrightnessMedium, stringResource(R.string.tray_brightness), Modifier.size(18.dp), tint = colors.primary)
                        }
                        Slider(
                            value = status.brightness,
                            onValueChange = onBrightnessChange,
                            valueRange = 0.05f..1f,
                            modifier = Modifier.weight(1f).height(28.dp),
                        )
                        Text(
                            "$brightnessPct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }
            }

            // Footer: Battery status + Show desktop shortcut
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (status.batteryLevel >= 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            batteryIcon(status.batteryLevel, status.batteryCharging),
                            null,
                            Modifier.size(16.dp),
                            tint = if (status.batteryCharging) colors.primary else colors.onSurfaceVariant,
                        )
                        Text(
                            if (status.batteryCharging) stringResource(R.string.battery_charging, status.batteryLevel)
                            else stringResource(R.string.battery_level, status.batteryLevel),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { onDismiss(); onShowDesktop() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.DesktopWindows, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.show_desktop), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val bg = if (active) colors.primaryContainer else colors.surfaceContainerLow
    val contentTint = if (active) colors.onPrimaryContainer else colors.onSurface
    val subTint = if (active) colors.onPrimaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant

    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (active) colors.primary.copy(alpha = 0.35f) else colors.outlineVariant.copy(alpha = 0.4f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.labelMedium, color = contentTint, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = subTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NotificationCenterPopover(
    notifications: List<TrayNotification>,
    accessGranted: Boolean,
    onRequestAccess: () -> Unit,
    onOpenNotification: (TrayNotification) -> Unit,
    onDismissNotification: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismissPopover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val clearableCount = notifications.count { it.clearable }

    Surface(
        modifier = modifier.widthIn(min = 310.dp, max = 380.dp).heightIn(max = 440.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.notifications),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (accessGranted && clearableCount > 0) {
                    TextButton(
                        onClick = onClearAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.clear_all_notifications), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.6f))
            if (!accessGranted) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.notifications_access_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = { onDismissPopover(); onRequestAccess() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.NotificationsActive, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.enable_notification_access))
                        }
                    }
                }
            } else if (notifications.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.NotificationsNone, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
                    Text(
                        stringResource(R.string.no_notifications),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    notifications.forEach { item ->
                        NotificationCard(
                            item = item,
                            onOpen = {
                                if (onOpenNotification(item).let { true }) onDismissPopover()
                            },
                            onDismiss = { onDismissNotification(item.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: TrayNotification,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val timeText = remember(item.postTime) { DateFormat.getTimeFormat(context).format(Date(item.postTime)) }
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = item.contentIntent != null, role = Role.Button, onClick = onOpen),
        shape = shape,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NotificationAppIcon(item.appIcon)
                Text(
                    item.appName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (item.clearable) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.dismiss_notification), Modifier.size(13.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NotificationAppIcon(drawable: Drawable?) {
    if (drawable == null) {
        Icon(Icons.Default.Notifications, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        return
    }
    val px = with(LocalDensity.current) { 16.dp.roundToPx().coerceAtLeast(1) }
    val bitmap = remember(drawable, px) { runCatching { drawable.toBitmap(px, px).asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
    } else {
        Icon(Icons.Default.Notifications, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private fun networkIcon(kind: NetworkKind): ImageVector = when (kind) {
    NetworkKind.Wifi -> Icons.Default.Wifi
    NetworkKind.Ethernet -> Icons.Default.SettingsEthernet
    NetworkKind.Cellular -> Icons.Default.SignalCellularAlt
    NetworkKind.None -> Icons.Default.WifiOff
}

private fun volumeIcon(status: SystemStatus): ImageVector = when {
    status.muted || status.volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
    status.volume < status.maxVolume / 2 -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun batteryIcon(level: Int, charging: Boolean): ImageVector = when {
    charging -> Icons.Default.BatteryChargingFull
    level >= 90 -> Icons.Default.BatteryFull
    level >= 70 -> Icons.Default.Battery6Bar
    level >= 50 -> Icons.Default.Battery4Bar
    level >= 25 -> Icons.Default.Battery2Bar
    else -> Icons.Default.BatteryAlert
}
