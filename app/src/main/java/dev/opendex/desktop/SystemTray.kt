package dev.opendex.desktop

import android.graphics.drawable.Drawable
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.opendex.R
import dev.opendex.platform.NetworkKind
import dev.opendex.platform.NotificationBanner
import dev.opendex.platform.SystemStatus
import dev.opendex.platform.TrayNotification
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal data class CalendarDayCell(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
    val inCurrentMonth: Boolean,
)

internal fun buildMonthGrid(year: Int, month: Int, firstDayOfWeek: Int): List<CalendarDayCell> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val leading = (dayOfWeek - firstDayOfWeek + 7) % 7
    cal.add(Calendar.DAY_OF_MONTH, -leading)
    return List(42) {
        val cell = CalendarDayCell(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH),
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
            inCurrentMonth = cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month,
        )
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cell
    }
}

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
    onRequestLocationPermission: () -> Unit,
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
    val wifiSubtitle = when {
        status.network == NetworkKind.Wifi && !status.wifiSsid.isNullOrBlank() -> status.wifiSsid
        status.network == NetworkKind.Wifi -> stringResource(R.string.tray_connected)
        status.network == NetworkKind.Ethernet -> stringResource(R.string.tray_ethernet)
        status.network == NetworkKind.Cellular -> stringResource(R.string.tray_cellular)
        status.wifiEnabled -> stringResource(R.string.tray_on)
        else -> stringResource(R.string.tray_off)
    }

    Surface(
        modifier = modifier.widthIn(min = 316.dp, max = 368.dp),
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
                        subtitle = wifiSubtitle,
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

            if (status.network == NetworkKind.Wifi && status.wifiSsid == null && !status.hasLocationPermission) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceContainerLow,
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Wifi, null, Modifier.size(16.dp), tint = colors.primary)
                        Text(
                            stringResource(R.string.wifi_show_name_prompt),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = onRequestLocationPermission,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(R.string.wifi_show_name_action), style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
        Icon(icon, null, Modifier.size(18.dp), tint = if (active) contentTint else colors.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.labelMedium, color = contentTint, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = subTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CalendarPopover(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val locale = Locale.getDefault()

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L - (nowMillis % 1_000L))
        }
    }

    val todayCal = remember(nowMillis / 60_000L) { Calendar.getInstance().apply { timeInMillis = nowMillis } }
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayMonth = todayCal.get(Calendar.MONTH)
    val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = todayCal.firstDayOfWeek

    var displayYear by remember { mutableIntStateOf(todayYear) }
    var displayMonth by remember { mutableIntStateOf(todayMonth) }
    var selectedDate by remember { mutableStateOf(Triple(todayYear, todayMonth, todayDay)) }

    val timePattern = remember(context, locale) {
        val skeleton = if (AndroidDateFormat.is24HourFormat(context)) "Hms" else "hms"
        AndroidDateFormat.getBestDateTimePattern(locale, skeleton)
    }
    val timeFormatter = remember(timePattern, locale) { SimpleDateFormat(timePattern, locale) }
    val fullDateFormatter = remember(locale) { DateFormat.getDateInstance(DateFormat.FULL, locale) }
    val monthYearPattern = remember(locale) { AndroidDateFormat.getBestDateTimePattern(locale, "yMMMM") }
    val monthYearFormatter = remember(monthYearPattern, locale) { SimpleDateFormat(monthYearPattern, locale) }

    val nowDate = Date(nowMillis)
    val liveTimeText = timeFormatter.format(nowDate)
    val fullTodayText = remember(todayYear, todayMonth, todayDay, fullDateFormatter) {
        fullDateFormatter.format(todayCal.time).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
    val monthTitle = remember(displayYear, displayMonth, monthYearFormatter) {
        val c = Calendar.getInstance().apply { set(Calendar.YEAR, displayYear); set(Calendar.MONTH, displayMonth); set(Calendar.DAY_OF_MONTH, 1) }
        monthYearFormatter.format(c.time).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
    val weekdayLabels = remember(firstDayOfWeek, locale) {
        val shortNames = DateFormatSymbols.getInstance(locale).shortWeekdays
        List(7) { offset ->
            val dow = ((firstDayOfWeek - 1 + offset) % 7) + 1
            shortNames.getOrNull(dow).orEmpty().trim()
        }
    }
    val cells = remember(displayYear, displayMonth, firstDayOfWeek) {
        buildMonthGrid(displayYear, displayMonth, firstDayOfWeek)
    }
    val isCurrentMonth = displayYear == todayYear && displayMonth == todayMonth

    fun shiftMonth(delta: Int) {
        val c = Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        displayYear = c.get(Calendar.YEAR)
        displayMonth = c.get(Calendar.MONTH)
    }

    Surface(
        modifier = modifier.widthIn(min = 310.dp, max = 356.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Live Clock + Full Date Header
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = liveTimeText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = fullTodayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.6f))

            // Month navigation
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = monthTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isCurrentMonth || selectedDate != Triple(todayYear, todayMonth, todayDay)) {
                    TextButton(
                        onClick = {
                            displayYear = todayYear
                            displayMonth = todayMonth
                            selectedDate = Triple(todayYear, todayMonth, todayDay)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(stringResource(R.string.calendar_today), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { shiftMonth(-1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ChevronLeft, stringResource(R.string.calendar_prev_month), Modifier.size(18.dp))
                }
                IconButton(onClick = { shiftMonth(1) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.calendar_next_month), Modifier.size(18.dp))
                }
            }

            // Weekday headers
            Row(Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // 6x7 day grid
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEach { cell ->
                            val isToday = cell.year == todayYear && cell.month == todayMonth && cell.dayOfMonth == todayDay
                            val isSelected = selectedDate == Triple(cell.year, cell.month, cell.dayOfMonth)
                            val interactions = remember { MutableInteractionSource() }
                            val hovered by interactions.collectIsHoveredAsState()
                            val bg = when {
                                isToday -> colors.primary
                                isSelected -> colors.primaryContainer
                                hovered -> colors.surfaceContainerHighest
                                else -> Color.Transparent
                            }
                            val fg = when {
                                isToday -> colors.onPrimary
                                isSelected -> colors.onPrimaryContainer
                                cell.inCurrentMonth -> colors.onSurface
                                else -> colors.onSurfaceVariant.copy(alpha = 0.42f)
                            }
                            Box(
                                Modifier.weight(1f).height(34.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(32.dp)
                                        .clip(CircleShape)
                                        .background(bg)
                                        .then(
                                            if (isSelected && !isToday) Modifier.border(1.dp, colors.primary, CircleShape)
                                            else Modifier,
                                        )
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(interactions, LocalIndication.current, role = Role.Button) {
                                            selectedDate = Triple(cell.year, cell.month, cell.dayOfMonth)
                                            if (!cell.inCurrentMonth) {
                                                displayYear = cell.year
                                                displayMonth = cell.month
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = cell.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = fg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotificationBannerStack(
    banners: List<NotificationBanner>,
    canOpenPackageInWindow: (String) -> Boolean,
    onOpenNotification: (TrayNotification) -> Unit,
    onOpenPackageInWindow: (String) -> Unit,
    onDismissBanner: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(min = 300.dp, max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        banners.forEach { banner ->
            key(banner.id) {
                NotificationBannerCard(
                    banner = banner,
                    canOpenInWindow = canOpenPackageInWindow(banner.notification.packageName),
                    onOpen = {
                        onDismissBanner(banner.id)
                        onOpenNotification(banner.notification)
                    },
                    onOpenInWindow = {
                        onDismissBanner(banner.id)
                        onOpenPackageInWindow(banner.notification.packageName)
                    },
                    onDismiss = { onDismissBanner(banner.id) },
                )
            }
        }
    }
}

@Composable
private fun NotificationBannerCard(
    banner: NotificationBanner,
    canOpenInWindow: Boolean,
    onOpen: () -> Unit,
    onOpenInWindow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val item = banner.notification
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var visible by remember(banner.id) { mutableStateOf(false) }

    LaunchedEffect(banner.id) { visible = true }
    LaunchedEffect(banner.id, hovered) {
        if (!hovered) {
            delay(6_000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut(),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Surface(
            modifier = Modifier.fillMaxWidth()
                .clip(shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactions, LocalIndication.current, role = Role.Button, onClick = onOpen),
            shape = shape,
            color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
            shadowElevation = 14.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(colors.primary))
                Row(
                    Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NotificationAppIcon(item.appIcon, size = 28.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                item.appName,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.notification_now),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            if (canOpenInWindow) {
                                IconButton(onClick = onOpenInWindow, modifier = Modifier.size(20.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        stringResource(R.string.open_notification_in_window),
                                        Modifier.size(13.dp),
                                        tint = colors.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    stringResource(R.string.dismiss_notification),
                                    Modifier.size(14.dp),
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotificationCenterPopover(
    notifications: List<TrayNotification>,
    accessGranted: Boolean,
    canOpenPackageInWindow: (String) -> Boolean,
    onRequestAccess: () -> Unit,
    onOpenNotification: (TrayNotification) -> Unit,
    onOpenPackageInWindow: (String) -> Unit,
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
                            canOpenInWindow = canOpenPackageInWindow(item.packageName),
                            onOpen = {
                                onOpenNotification(item)
                                onDismissPopover()
                            },
                            onOpenInWindow = {
                                onOpenPackageInWindow(item.packageName)
                                onDismissPopover()
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
    canOpenInWindow: Boolean,
    onOpen: () -> Unit,
    onOpenInWindow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val timeText = remember(item.postTime) { AndroidDateFormat.getTimeFormat(context).format(Date(item.postTime)) }
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = item.contentIntent != null || canOpenInWindow, role = Role.Button, onClick = onOpen),
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
                if (canOpenInWindow) {
                    IconButton(onClick = onOpenInWindow, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            stringResource(R.string.open_notification_in_window),
                            Modifier.size(13.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
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
private fun NotificationAppIcon(drawable: Drawable?, size: Dp = 16.dp) {
    if (drawable == null) {
        Icon(Icons.Default.Notifications, null, Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
        return
    }
    val px = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
    val bitmap = remember(drawable, px) { runCatching { drawable.toBitmap(px, px).asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size).clip(RoundedCornerShape(if (size >= 24.dp) 6.dp else 4.dp)))
    } else {
        Icon(Icons.Default.Notifications, null, Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
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
