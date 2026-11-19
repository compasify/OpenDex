package dev.opendex.desktop

import androidx.annotation.StringRes
import dev.opendex.R

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

data class Area(val width: Float, val height: Float)

enum class Tool(@StringRes val title: Int) { Notes(R.string.notes), Settings(R.string.workspace_settings) }
enum class Placement { Floating, Full, Left, Right }

data class DesktopWindow(
    val tool: Tool,
    val floating: Bounds,
    val placement: Placement = Placement.Floating,
    val minimized: Boolean = false,
) {
    fun bounds(area: Area): Bounds = when (placement) {
        Placement.Floating -> floating.fit(area)
        Placement.Full -> Bounds(0f, 0f, area.width, area.height)
        Placement.Left -> Bounds(0f, 0f, area.width / 2, area.height)
        Placement.Right -> Bounds(area.width / 2, 0f, area.width / 2, area.height)
    }
}

fun List<DesktopWindow>.open(tool: Tool, area: Area): List<DesktopWindow> {
    val existing = firstOrNull { it.tool == tool }
    val offset = if (tool == Tool.Notes) 0f else 56f
    val initial = Bounds(
        area.width * .10f + offset, area.height * .12f + offset,
        if (tool == Tool.Notes) 480f else 450f, 390f,
    ).fit(area)
    return filterNot { it.tool == tool } + (existing?.copy(minimized = false) ?: DesktopWindow(tool, initial))
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
