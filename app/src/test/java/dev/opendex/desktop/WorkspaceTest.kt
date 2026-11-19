package dev.opendex.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {
    @Test fun searchIgnoresDiacriticsCaseAndTokenOrder() {
        val apps = listOf("Đồng hồ", "Cài đặt nhanh", "Ảnh")
        assertEquals(listOf("Cài đặt nhanh"), searchApps(apps, " NHANH  dat ") { it })
        assertEquals(listOf("Đồng hồ"), searchApps(apps, "dong ho") { it })
        assertEquals(apps, searchApps(apps, "  ") { it })
        assertTrue(searchApps(apps, "chrome") { it }.isEmpty())
    }

    @Test fun fittingAfterRotationKeepsAllControlsWithinArea() {
        assertEquals(Bounds(0f, 0f, 300f, 210f), Bounds(800f, -10f, 480f, 390f).fit(Area(300f, 210f)))
        assertEquals(Bounds(0f, 0f, 100f, 90f), Bounds(75f, 60f, 480f, 390f).fit(Area(100f, 90f)))
    }

    @Test fun draggingAndResizingRespectWorkAreaAndMinSize() {
        val area = Area(900f, 600f)
        val start = Bounds(120f, 80f, 480f, 300f)
        assertEquals(Bounds(420f, 0f, 480f, 300f), start.move(800f, -500f, area))
        assertEquals(Bounds(120f, 80f, 780f, 520f), start.resize(500f, 500f, area))
        assertEquals(Bounds(120f, 80f, 240f, 160f), start.resize(-500f, -500f, area))
    }

    @Test fun snapReflowsAndKeepsOriginalFloatingBounds() {
        val float = Bounds(100f, 120f, 420f, 320f)
        val window = DesktopWindow(Tool.Settings, float, Placement.Right)
        assertEquals(Bounds(400f, 0f, 400f, 600f), window.bounds(Area(800f, 600f)))
        assertEquals(Bounds(300f, 0f, 300f, 400f), window.bounds(Area(600f, 400f)))
        assertEquals(float, window.copy(placement = Placement.Floating).bounds(Area(800f, 600f)))
    }

    @Test fun reopenRestoresWindowAndMakesItTopmostWithoutDuplicating() {
        val area = Area(900f, 600f)
        val notes = DesktopWindow(Tool.Notes, Bounds(30f, 40f, 500f, 320f), Placement.Left, minimized = true)
        val windows = listOf(notes).open(Tool.Settings, area).open(Tool.Notes, area)
        assertEquals(2, windows.size)
        assertEquals(Tool.Notes, windows.last().tool)
        assertEquals(Placement.Left, windows.last().placement)
        assertFalse(windows.last().minimized)
    }

    @Test fun savedSessionRoundTripsAndIgnoresMalformedEntries() {
        val windows = listOf(DesktopWindow(Tool.Settings, Bounds(14f, 24f, 480f, 380f), Placement.Right, true))
        val encoded = windows.encodeWindows()
        assertEquals(windows, decodeWindows("$encoded;broken;Notes,NaN,0,300,300,Full,false;$encoded"))
        assertEquals(windows, decodeWindows(encoded))
    }
}
