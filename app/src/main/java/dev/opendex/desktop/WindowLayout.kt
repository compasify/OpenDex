package dev.opendex.desktop

import androidx.annotation.StringRes
import dev.opendex.R

const val NO_VIRTUAL_DISPLAY = -1

data class Bounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun fit(area: Area): Bounds {
        val w = width.coerceIn(minOf(240f, area.width), area.width)
        val h = height.coerceIn(minOf(160f, area.height), area.height)
        return Bounds(x.coerceIn(0f, area.width - w), y.coerceIn(0f, area.height - h), w, h)
    }

    fun move(dx: Float, dy: Float, area: Area) = copy(x = x + dx, y = y + dy).fit(area)

    fun resize(dx: Float, dy: Float, area: Area): Bounds {
        val b = fit(area)
        return b.copy(
            width = (b.width + dx).coerceIn(minOf(240f, area.width), area.width - b.x),
            height = (b.height + dy).coerceIn(minOf(160f, area.height), area.height - b.y),
        )
    }
}

data class Area(val width: Float, val height: Float) {
    val canTile: Boolean get() = width >= 540f
}

enum class Tool(@get:StringRes val title: Int) { Notes(R.string.notes), Settings(R.string.workspace_settings) }
enum class Placement { Floating, Full, Left, Right }

sealed interface WindowKey {
    data class ToolKey(val tool: Tool) : WindowKey
    data class AppKey(val appId: String) : WindowKey
}

fun Placement.boundsIn(floating: Bounds, area: Area): Bounds = when (this) {
    Placement.Floating -> floating.fit(area)
    Placement.Full -> Bounds(0f, 0f, area.width, area.height)
    Placement.Left -> Bounds(0f, 0f, if (area.canTile) area.width / 2 else area.width, area.height)
    Placement.Right -> if (area.canTile) Bounds(area.width / 2, 0f, area.width / 2, area.height)
        else Bounds(0f, 0f, area.width, area.height)
}

fun detectEdgeSnap(
    pointerX: Float,
    pointerY: Float,
    area: Area,
    edgeThresholdDp: Float = 24f,
    topThresholdDp: Float = 16f,
): Placement? = when {
    area.canTile && pointerX <= edgeThresholdDp -> Placement.Left
    area.canTile && pointerX >= area.width - edgeThresholdDp -> Placement.Right
    pointerY <= topThresholdDp -> Placement.Full
    else -> null
}

data class DesktopWindow(
    val tool: Tool,
    val floating: Bounds,
    val placement: Placement = Placement.Floating,
    val minimized: Boolean = false,
) {
    fun bounds(area: Area): Bounds = placement.boundsIn(floating, area)
}

data class AppWindowState(
    val appId: String,
    val floating: Bounds,
    val placement: Placement = Placement.Floating,
    val minimized: Boolean = false,
    val virtualDisplayId: Int = NO_VIRTUAL_DISPLAY,
    val launching: Boolean = true,
) {
    fun bounds(area: Area): Bounds = placement.boundsIn(floating, area)
}

fun defaultAppBounds(area: Area, cascadeIndex: Int): Bounds {
    val w = minOf(area.width * 0.62f, 760f).coerceAtLeast(minOf(420f, area.width))
    val h = minOf(area.height * 0.84f, 620f).coerceAtLeast(minOf(360f, area.height))
    val step = (cascadeIndex % 5) * 32f
    val startX = ((area.width - w) * 0.35f + step).coerceAtLeast(16f)
    val startY = ((area.height - h) * 0.25f + step).coerceAtLeast(12f)
    return Bounds(startX, startY, w, h).fit(area)
}

fun List<AppWindowState>.openApp(appId: String, area: Area): List<AppWindowState> {
    val existing = firstOrNull { it.appId == appId }
    if (existing != null) {
        return filterNot { it.appId == appId } + existing.copy(minimized = false)
    }
    return this + AppWindowState(appId = appId, floating = defaultAppBounds(area, size))
}

fun List<AppWindowState>.activateApp(appId: String): List<AppWindowState> {
    val target = firstOrNull { it.appId == appId } ?: return this
    if (lastOrNull() == target) return this
    return filterNot { it.appId == appId } + target
}

fun List<AppWindowState>.minimizeAllApps(): List<AppWindowState> = map {
    if (it.minimized) it else it.copy(minimized = true)
}

fun List<AppWindowState>.restoreApps(ids: Set<String>? = null): List<AppWindowState> = map {
    if (!it.minimized) it
    else if (ids == null || it.appId in ids) it.copy(minimized = false)
    else it
}

fun List<AppWindowState>.encodeAppWindows(): String = joinToString("\n") {
    val b = it.floating
    listOf(it.appId, b.x, b.y, b.width, b.height, it.placement.name, it.minimized).joinToString("|")
}

fun decodeAppWindows(value: String): List<AppWindowState> = value.lineSequence().mapNotNull { entry ->
    if (entry.isBlank()) return@mapNotNull null
    val p = entry.split('|')
    if (p.size != 7) return@mapNotNull null
    val appId = p[0].trim()
    if (appId.isEmpty()) return@mapNotNull null
    val geometry = p.subList(1, 5).mapNotNull(String::toFloatOrNull)
    if (geometry.size != 4 || geometry.any { !it.isFinite() }) return@mapNotNull null
    val placement = Placement.entries.find { it.name == p[5] } ?: return@mapNotNull null
    val minimized = p[6].toBooleanStrictOrNull() ?: return@mapNotNull null
    AppWindowState(
        appId = appId,
        floating = Bounds(geometry[0], geometry[1], geometry[2], geometry[3]),
        placement = placement,
        minimized = minimized,
        virtualDisplayId = NO_VIRTUAL_DISPLAY,
        launching = true,
    )
}.distinctBy { it.appId }.toList()

fun List<DesktopWindow>.open(tool: Tool, area: Area): List<DesktopWindow> {
    val existing = firstOrNull { it.tool == tool }
    val offset = if (tool == Tool.Notes) 0f else 56f
    val initial = Bounds(
        area.width * .10f + offset, area.height * .12f + offset,
        if (tool == Tool.Notes) 480f else 520f, if (tool == Tool.Notes) 390f else 520f,
    ).fit(area)
    return filterNot { it.tool == tool } + (existing?.copy(minimized = false) ?: DesktopWindow(tool, initial))
}

fun List<DesktopWindow>.minimizeAllTools(): List<DesktopWindow> = map {
    if (it.minimized) it else it.copy(minimized = true)
}

fun List<DesktopWindow>.restoreTools(tools: Set<Tool>? = null): List<DesktopWindow> = map {
    if (!it.minimized) it
    else if (tools == null || it.tool in tools) it.copy(minimized = false)
    else it
}

fun List<DesktopWindow>.activate(tool: Tool): List<DesktopWindow> {
    val target = firstOrNull { it.tool == tool } ?: return this
    if (lastOrNull() == target) return this
    return filterNot { it.tool == tool } + target
}

fun List<DesktopWindow>.encodeWindows(): String = joinToString(";") {
    val b = it.floating
    listOf(it.tool.name, b.x, b.y, b.width, b.height, it.placement.name, it.minimized).joinToString(",")
}

fun decodeWindows(value: String): List<DesktopWindow> = value.split(';').mapNotNull { entry ->
    val p = entry.split(',')
    if (p.size != 7) return@mapNotNull null
    val geometry = p.subList(1, 5).mapNotNull(String::toFloatOrNull)
    if (geometry.size != 4 || geometry.any { !it.isFinite() }) return@mapNotNull null
    val tool = Tool.entries.find { it.name == p[0] } ?: return@mapNotNull null
    val placement = Placement.entries.find { it.name == p[5] } ?: return@mapNotNull null
    val minimized = p[6].toBooleanStrictOrNull() ?: return@mapNotNull null
    DesktopWindow(tool, Bounds(geometry[0], geometry[1], geometry[2], geometry[3]), placement, minimized)
}.distinctBy { it.tool }
