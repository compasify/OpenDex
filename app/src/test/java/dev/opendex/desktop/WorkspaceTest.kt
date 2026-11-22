package dev.opendex.desktop

import dev.opendex.data.DEFAULT_EXTERNAL_DOCK_HEIGHT_DP
import dev.opendex.data.DEFAULT_EXTERNAL_OVERSCAN_PERCENT
import dev.opendex.data.DEFAULT_LOCAL_DOCK_HEIGHT_DP
import dev.opendex.data.DisplayScale
import dev.opendex.data.MAX_DOCK_HEIGHT_DP
import dev.opendex.data.MAX_OVERSCAN_PERCENT
import dev.opendex.data.MIN_DOCK_HEIGHT_DP
import dev.opendex.data.WindowResizeMode
import dev.opendex.data.WindowScale
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.computeDockMetrics
import dev.opendex.data.computeHostedDensityDpi
import dev.opendex.data.computeOverscanInsetPx
import dev.opendex.data.defaultDockHeightDp
import dev.opendex.data.logicalResolution
import dev.opendex.platform.cleanWifiSsid
import java.util.Calendar

import android.view.Display
import dev.opendex.platform.AdbAccess
import dev.opendex.platform.ExternalLaunchMethod
import dev.opendex.platform.ScreenInfo
import dev.opendex.platform.filterShadowedExternalDisplays
import dev.opendex.platform.isDisplayActive
import dev.opendex.platform.isMisplacedExternalLaunch
import dev.opendex.platform.isOwnHostedVirtualDisplay
import dev.opendex.platform.resolveExternalLaunchOrder
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

    @Test fun dockHeightDefaultsSlimmerOnExternalAndClampsOutOfRangeValues() {
        val prefs = WorkspacePreferences()
        assertEquals(DEFAULT_EXTERNAL_DOCK_HEIGHT_DP, prefs.dockHeightFor(external = true))
        assertEquals(DEFAULT_LOCAL_DOCK_HEIGHT_DP, prefs.dockHeightFor(external = false))
        assertEquals(DEFAULT_EXTERNAL_DOCK_HEIGHT_DP, defaultDockHeightDp(external = true))
        assertEquals(DEFAULT_LOCAL_DOCK_HEIGHT_DP, defaultDockHeightDp(external = false))
        assertTrue(prefs.dockHeightFor(external = true) < prefs.dockHeightFor(external = false))

        val outOfRange = WorkspacePreferences(localDockHeightDp = 999, externalDockHeightDp = 10)
        assertEquals(MAX_DOCK_HEIGHT_DP, outOfRange.dockHeightFor(external = false))
        assertEquals(MIN_DOCK_HEIGHT_DP, outOfRange.dockHeightFor(external = true))
    }

    @Test fun computeDockMetricsScalesProportionallyAcrossRange() {
        val heights = listOf(MIN_DOCK_HEIGHT_DP, DEFAULT_EXTERNAL_DOCK_HEIGHT_DP, 45, 46, DEFAULT_LOCAL_DOCK_HEIGHT_DP, MAX_DOCK_HEIGHT_DP)
        val metrics = heights.map(::computeDockMetrics)

        metrics.forEach { m ->
            assertTrue(m.heightDp in MIN_DOCK_HEIGHT_DP..MAX_DOCK_HEIGHT_DP)
            assertTrue(m.iconDp < m.buttonDp)
            assertTrue(m.buttonDp < m.heightDp)
            assertTrue(m.toolIconDp in 15..24)
            assertTrue(m.trayIconDp in 13..18)
            assertTrue(m.toolIconDp < m.buttonDp)
            assertTrue(m.trayIconDp <= m.toolIconDp)
            assertEquals(m.heightDp < 46, m.compactClock)
        }

        for (i in 1 until metrics.size) {
            assertTrue(metrics[i].heightDp >= metrics[i - 1].heightDp)
            assertTrue(metrics[i].buttonDp >= metrics[i - 1].buttonDp)
            assertTrue(metrics[i].iconDp >= metrics[i - 1].iconDp)
            assertTrue(metrics[i].toolIconDp >= metrics[i - 1].toolIconDp)
            assertTrue(metrics[i].trayIconDp >= metrics[i - 1].trayIconDp)
        }

        assertEquals(computeDockMetrics(MIN_DOCK_HEIGHT_DP), computeDockMetrics(12))
        assertEquals(computeDockMetrics(MAX_DOCK_HEIGHT_DP), computeDockMetrics(120))
    }

    @Test fun ownHostedVirtualDisplayFilterMatchesOnlyOpenDexPrefixes() {
        assertTrue(isOwnHostedVirtualDisplay("OpenDex-1"))
        assertTrue(isOwnHostedVirtualDisplay("OpenDex-42"))
        assertFalse(isOwnHostedVirtualDisplay("Samsung DeX"))
        assertFalse(isOwnHostedVirtualDisplay("HDMI Screen"))
        assertFalse(isOwnHostedVirtualDisplay(null))
        assertFalse(isOwnHostedVirtualDisplay(""))
    }

    @Test fun displayActiveFilterExcludesOffDisplays() {
        assertFalse(isDisplayActive(Display.STATE_OFF))
        assertTrue(isDisplayActive(Display.STATE_ON))
        assertTrue(isDisplayActive(Display.STATE_UNKNOWN))
    }

    @Test fun shadowedHdmiDisplayIsHiddenWhenSystemDesktopDisplayIsActive() {
        val phone = ScreenInfo(0, "Built-in", 1080, 2280, canOpenWorkspace = false, canLaunchActivity = false, canPresent = false)
        val dexDesktop = ScreenInfo(2, "Desktop", 1920, 1080, canOpenWorkspace = true, canLaunchActivity = true, canPresent = false)
        val shadowedHdmi = ScreenInfo(14, "Màn hình HDMI", 1920, 1080, canOpenWorkspace = true, canLaunchActivity = true, canPresent = true)
        assertEquals(
            listOf(phone, dexDesktop),
            filterShadowedExternalDisplays(listOf(phone, dexDesktop, shadowedHdmi), currentDisplay = 0),
        )
        assertEquals(
            listOf(phone, shadowedHdmi),
            filterShadowedExternalDisplays(listOf(phone, shadowedHdmi), currentDisplay = 0),
        )
    }

    @Test fun misplacedExternalLaunchDetectsDefaultAndMismatchedDisplays() {
        assertTrue(isMisplacedExternalLaunch(Display.DEFAULT_DISPLAY, 2))
        assertTrue(isMisplacedExternalLaunch(Display.DEFAULT_DISPLAY, Display.INVALID_DISPLAY))
        assertTrue(isMisplacedExternalLaunch(3, 2))
        assertFalse(isMisplacedExternalLaunch(2, 2))
        assertFalse(isMisplacedExternalLaunch(2, Display.INVALID_DISPLAY))
    }

    @Test fun resolveExternalLaunchOrderCoversAllCapabilityCombinations() {
        assertEquals(
            listOf(ExternalLaunchMethod.Activity, ExternalLaunchMethod.ShellActivity, ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = true, canPresent = true, adbGranted = true),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.Activity, ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = true, canPresent = true, adbGranted = false),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.Activity, ExternalLaunchMethod.ShellActivity, ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = true, canPresent = false, adbGranted = true),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.Activity, ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = true, canPresent = false, adbGranted = false),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = false, canPresent = true, adbGranted = true),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.Presentation),
            resolveExternalLaunchOrder(canLaunchActivity = false, canPresent = true, adbGranted = false),
        )
        assertEquals(
            listOf(ExternalLaunchMethod.ShellActivity),
            resolveExternalLaunchOrder(canLaunchActivity = false, canPresent = false, adbGranted = true),
        )
        assertEquals(
            emptyList<ExternalLaunchMethod>(),
            resolveExternalLaunchOrder(canLaunchActivity = false, canPresent = false, adbGranted = false),
        )
    }

    @Test fun externalOverscanInsetsScaleProportionallyAndClamp() {
        assertEquals(DEFAULT_EXTERNAL_OVERSCAN_PERCENT, WorkspacePreferences().externalOverscanPercent)
        assertEquals(0 to 0, computeOverscanInsetPx(1920, 1080, 0))
        assertEquals(0 to 0, computeOverscanInsetPx(1920, 1080, -3))
        assertEquals(0 to 0, computeOverscanInsetPx(0, 1080, 5))
        assertEquals(77 to 43, computeOverscanInsetPx(1920, 1080, 4))
        assertEquals(164 to 86, computeOverscanInsetPx(4096, 2160, 4))
        // Values above MAX_OVERSCAN_PERCENT clamp cleanly to MAX_OVERSCAN_PERCENT.
        assertEquals(
            computeOverscanInsetPx(1920, 1080, MAX_OVERSCAN_PERCENT),
            computeOverscanInsetPx(1920, 1080, 50),
        )
    }
}
