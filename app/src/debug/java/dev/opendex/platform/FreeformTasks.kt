package dev.opendex.platform

import androidx.compose.ui.unit.IntRect

internal data class FreeformTask(val id: Int, val bounds: IntRect)

internal data class RootTaskMatch(val rootTaskId: Int, val displayId: Int)

private val rootHeader = Regex("^RootTask id=(\\d+) .*\\bdisplayId=(\\d+)\\b")
private val anyTaskLine = Regex("^taskId=(\\d+): (\\S+) bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)] userId=(\\d+) visible=(true|false)(?:\\s|$)")

/** Match only an exact launcher component in a visible native freeform task on the requested display/user. */
internal fun findVisibleFreeformTask(
    stacks: String, component: String, shortComponent: String, displayId: Int, userId: Int,
): FreeformTask? {
    var onDisplay = false
    var freeform = false
    for (line in stacks.lineSequence().map(String::trim)) {
        if (line.startsWith("RootTask ")) {
            onDisplay = rootHeader.find(line)?.groupValues?.get(2)?.toIntOrNull() == displayId
            freeform = false
        } else if (line.startsWith("configuration=")) {
            freeform = "mWindowingMode=freeform " in line || "mWindowingMode=freeform}" in line
        } else if (onDisplay && freeform) {
            val parts = anyTaskLine.find(line)?.groupValues ?: continue
            if (parts[8] != "true") continue
            if (parts[2] != component && parts[2] != shortComponent || parts[7].toIntOrNull() != userId) continue
            val id = parts[1].toIntOrNull() ?: continue
            val edges = parts.subList(3, 7).mapNotNull(String::toIntOrNull)
            if (edges.size == 4) return FreeformTask(id, IntRect(edges[0], edges[1], edges[2], edges[3]))
        }
    }
    return null
}

/** Find all RootTask IDs currently hosted on a specific display. */
internal fun findRootTaskIdsOnDisplay(stacks: String, displayId: Int): List<Int> {
    val ids = mutableListOf<Int>()
    for (line in stacks.lineSequence().map(String::trim)) {
        if (!line.startsWith("RootTask ")) continue
        val groups = rootHeader.find(line)?.groupValues ?: continue
        val rootId = groups[1].toIntOrNull() ?: continue
        val disp = groups[2].toIntOrNull() ?: continue
        if (disp == displayId) ids += rootId
    }
    return ids
}

/** Find the first RootTask hosting the given component (or package) for the specified user. */
internal fun findComponentRootTask(
    stacks: String, component: String, shortComponent: String, userId: Int,
): RootTaskMatch? {
    val pkgPrefix = component.substringBefore('/') + "/"
    var currentRootId = -1
    var currentDisplayId = -1
    var fallback: RootTaskMatch? = null
    for (line in stacks.lineSequence().map(String::trim)) {
        if (line.startsWith("RootTask ")) {
            val groups = rootHeader.find(line)?.groupValues
            currentRootId = groups?.get(1)?.toIntOrNull() ?: -1
            currentDisplayId = groups?.get(2)?.toIntOrNull() ?: -1
        } else if (currentRootId >= 0) {
            val parts = anyTaskLine.find(line)?.groupValues ?: continue
            if (parts[7].toIntOrNull() != userId) continue
            val taskComp = parts[2]
            if (taskComp == component || taskComp == shortComponent) {
                return RootTaskMatch(currentRootId, currentDisplayId)
            }
            if (fallback == null && taskComp.startsWith(pkgPrefix)) {
                fallback = RootTaskMatch(currentRootId, currentDisplayId)
            }
        }
    }
    return fallback
}
