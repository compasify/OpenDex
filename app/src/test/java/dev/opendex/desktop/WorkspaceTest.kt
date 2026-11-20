package dev.opendex.desktop

import dev.opendex.data.DisplayScale
import dev.opendex.data.WindowResizeMode
import dev.opendex.data.WindowScale
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.computeHostedDensityDpi
import dev.opendex.data.logicalResolution
import dev.opendex.platform.cleanWifiSsid
import java.util.Calendar

import dev.opendex.platform.AdbAccess
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
        assertEquals(Bounds(0f, 0f, 280f, 350f), window.bounds(Area(280f, 350f)))
        assertEquals(Bounds(0f, 0f, 280f, 350f), window.copy(placement = Placement.Left).bounds(Area(280f, 350f)))
        assertEquals(Bounds(400f, 0f, 400f, 600f), window.bounds(Area(800f, 600f)))
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

    @Test fun regularTapPrefersFloatingOnlyWhenAdbFreeformCanRunOnBuiltInDisplay() {
        assertTrue(prefersFloatingOpen(AdbAccess.Granted, external = false, freeformReported = true))
        assertFalse(prefersFloatingOpen(AdbAccess.Granted, external = true, freeformReported = true))
        assertFalse(prefersFloatingOpen(AdbAccess.Granted, external = false, freeformReported = false))
        assertFalse(prefersFloatingOpen(AdbAccess.NeedsPermission, external = false, freeformReported = true))
        assertFalse(prefersFloatingOpen(AdbAccess.NotRunning, external = false, freeformReported = true))
        assertFalse(prefersFloatingOpen(AdbAccess.NotIncluded, external = false, freeformReported = true))
    }

    @Test fun regularTapPrefersHostedWindowWhenAuthorizedAndSupported() {
        assertTrue(prefersHostedWindowOpen(AdbAccess.Granted, supportsHostedWindows = true))
        assertFalse(prefersHostedWindowOpen(AdbAccess.Granted, supportsHostedWindows = false))
        assertFalse(prefersHostedWindowOpen(AdbAccess.NeedsPermission, supportsHostedWindows = true))
        assertFalse(prefersHostedWindowOpen(AdbAccess.NotRunning, supportsHostedWindows = true))
        assertFalse(prefersHostedWindowOpen(AdbAccess.NotIncluded, supportsHostedWindows = true))
    }

    @Test fun hostedAppWindowOpenRestoresAndCascadesWithinArea() {
        val area = Area(1280f, 700f)
        val opened = emptyList<AppWindowState>()
            .openApp("com.example/.Calc", area)
            .openApp("com.example/.Files", area)
        assertEquals(2, opened.size)
        assertTrue(opened[1].floating.x > opened[0].floating.x)
        val minimized = opened.map { if (it.appId == "com.example/.Calc") it.copy(minimized = true, placement = Placement.Full) else it }
        val restored = minimized.openApp("com.example/.Calc", area)
        assertEquals(2, restored.size)
        assertEquals("com.example/.Calc", restored.last().appId)
        assertFalse(restored.last().minimized)
        assertEquals(Placement.Full, restored.last().placement)
        assertEquals(Bounds(0f, 0f, 1280f, 700f), restored.last().bounds(area))
    }

    @Test fun savedAppWindowsRoundTripAndIgnoreMalformedEntries() {
        val windows = listOf(
            AppWindowState("com.android.chrome/com.google.android.apps.chrome.Main", Bounds(40f, 20f, 720f, 540f), Placement.Floating, minimized = false, virtualDisplayId = 12, launching = false),
            AppWindowState("com.miui.notes/.ui.NotesListActivity", Bounds(100f, 60f, 640f, 480f), Placement.Right, minimized = true, virtualDisplayId = 13, launching = false),
        )
        val encoded = windows.encodeAppWindows()
        val decoded = decodeAppWindows("$encoded\nbroken\n|1|2|3|4|Full|false\ncom.a/.B|NaN|0|300|300|Full|false\n$encoded")
        assertEquals(2, decoded.size)
        assertEquals(windows[0].appId, decoded[0].appId)
        assertEquals(windows[0].floating, decoded[0].floating)
        assertEquals(Placement.Floating, decoded[0].placement)
        assertFalse(decoded[0].minimized)
        assertEquals(NO_VIRTUAL_DISPLAY, decoded[0].virtualDisplayId)
        assertTrue(decoded[0].launching)
        assertEquals(windows[1].appId, decoded[1].appId)
        assertEquals(Placement.Right, decoded[1].placement)
        assertTrue(decoded[1].minimized)
    }

    @Test fun minimizeAllMarksAllOpenWindowsMinimized() {
        val apps = listOf(
            AppWindowState("a/.A", Bounds(0f, 0f, 400f, 300f), minimized = false),
            AppWindowState("b/.B", Bounds(10f, 10f, 400f, 300f), minimized = true),
        ).minimizeAllApps()
        assertTrue(apps.all { it.minimized })
        val tools = listOf(
            DesktopWindow(Tool.Notes, Bounds(0f, 0f, 400f, 300f), minimized = false),
            DesktopWindow(Tool.Settings, Bounds(10f, 10f, 400f, 300f), minimized = false),
        ).minimizeAllTools()
        assertTrue(tools.all { it.minimized })
        assertTrue(apps.restoreApps(setOf("a/.A")).let { !it[0].minimized && it[1].minimized })
        assertTrue(apps.restoreApps().all { !it.minimized })
        assertTrue(tools.restoreTools(setOf(Tool.Notes)).let { !it[0].minimized && it[1].minimized })
        assertTrue(tools.restoreTools().all { !it.minimized })
    }

    @Test fun wifiSsidCleanerHandlesQuotesAndPlaceholders() {
        assertEquals("Office_5GHz", cleanWifiSsid("\"Office_5GHz\""))
        assertEquals("Cafe Wifi", cleanWifiSsid("  \"Cafe Wifi\"  "))
        assertEquals(null, cleanWifiSsid("<unknown ssid>"))
        assertEquals(null, cleanWifiSsid("\"<unknown ssid>\""))
        assertEquals(null, cleanWifiSsid("0x"))
        assertEquals(null, cleanWifiSsid("   "))
        assertEquals(null, cleanWifiSsid(null))
    }

    @Test fun calendarMonthGridAlwaysProduces42AlignedCells() {
        // November 2026 starts on Sunday (Calendar.SUNDAY = 1), 30 days.
        val gridSunFirst = buildMonthGrid(2026, Calendar.NOVEMBER, Calendar.SUNDAY)
        assertEquals(42, gridSunFirst.size)
        assertEquals(CalendarDayCell(2026, Calendar.NOVEMBER, 1, inCurrentMonth = true), gridSunFirst.first())
        assertEquals(30, gridSunFirst.count { it.inCurrentMonth })

        val gridMonFirst = buildMonthGrid(2026, Calendar.NOVEMBER, Calendar.MONDAY)
        assertEquals(42, gridMonFirst.size)
        assertEquals(CalendarDayCell(2026, Calendar.OCTOBER, 26, inCurrentMonth = false), gridMonFirst.first())
        assertEquals(CalendarDayCell(2026, Calendar.NOVEMBER, 1, inCurrentMonth = true), gridMonFirst[6])
        assertEquals(30, gridMonFirst.count { it.inCurrentMonth })
    }

    @Test fun hostedDensityDpiNormalizesHighDpiScreensWithoutShrinkingMonitors() {
        // 1080p external monitor (160 dpi): stays native at Standard.
        assertEquals(160, computeHostedDensityDpi(160, WindowScale.Standard))
        assertEquals(136, computeHostedDensityDpi(160, WindowScale.Compact))
        assertEquals(192, computeHostedDensityDpi(160, WindowScale.Comfortable))
        // 320 dpi tablet: normalized to 240 dpi desktop scale at Standard.
        assertEquals(240, computeHostedDensityDpi(320, WindowScale.Standard))
        assertEquals(204, computeHostedDensityDpi(320, WindowScale.Compact))
        assertEquals(288, computeHostedDensityDpi(320, WindowScale.Comfortable))
        // 440 dpi phone: normalized to 330 dpi at Standard.
        assertEquals(330, computeHostedDensityDpi(440, WindowScale.Standard))
        // DisplayScale scales hosted apps proportionally too.
        assertEquals(180, computeHostedDensityDpi(320, WindowScale.Standard, DisplayScale.MoreSpace))
        assertEquals(300, computeHostedDensityDpi(320, WindowScale.Standard, DisplayScale.Larger))
    }

    @Test fun logicalResolutionMatchesMacOsScaledDisplayMath() {
        // 2560x1600 @ 2.0x native density (320 dpi tablet):
        assertEquals(1707 to 1067, logicalResolution(2560, 1600, 2.0f, DisplayScale.MoreSpace))
        assertEquals(1280 to 800, logicalResolution(2560, 1600, 2.0f, DisplayScale.Default))
        assertEquals(1024 to 640, logicalResolution(2560, 1600, 2.0f, DisplayScale.Larger))
        // 1920x1080 @ 1.0x external monitor:
        assertEquals(1920 to 1080, logicalResolution(1920, 1080, 1.0f, DisplayScale.Default))
        assertEquals(1536 to 864, logicalResolution(1920, 1080, 1.0f, DisplayScale.Larger))
    }

    @Test fun edgeSnapTargetsLeftRightTopAndRespectsCanTile() {
        val wide = Area(1000f, 600f)
        assertEquals(Placement.Left, detectEdgeSnap(10f, 200f, wide))
        assertEquals(Placement.Right, detectEdgeSnap(990f, 200f, wide))
        assertEquals(Placement.Full, detectEdgeSnap(500f, 8f, wide))
        assertEquals(null, detectEdgeSnap(500f, 200f, wide))

        val narrow = Area(400f, 600f)
        assertEquals(null, detectEdgeSnap(10f, 200f, narrow))
        assertEquals(null, detectEdgeSnap(390f, 200f, narrow))
        assertEquals(Placement.Full, detectEdgeSnap(200f, 8f, narrow))
    }

    @Test fun windowResizeModeDefaultsToLiveAndExposesBothStyles() {
        assertEquals(WindowResizeMode.Live, WorkspacePreferences().windowResizeMode)
        assertEquals(listOf(WindowResizeMode.Live, WindowResizeMode.Outline), WindowResizeMode.entries)
    }
}
