package dev.opendex.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowInsets
import androidx.compose.ui.unit.IntRect
import dev.opendex.ExternalDisplayActivity
import java.text.Collator
import java.util.Locale

internal fun isOwnHostedVirtualDisplay(name: String?): Boolean =
    name != null && name.startsWith("OpenDex-")

internal fun isDisplayActive(state: Int): Boolean = state != Display.STATE_OFF

internal fun isSystemDesktopDisplay(name: String?): Boolean =
    name.equals("Desktop", ignoreCase = true)

/**
 * When an OEM desktop environment (such as Samsung DeX Dual Mode) creates an active virtual
 * "Desktop" display that takes over physical HDMI scanout, the underlying HDMI presentation
 * display remains in DisplayManager with a separate, unrendered layerStack. Hide those shadowed
 * presentation displays while the active "Desktop" display is online so users target the display
 * that is actually visible on the TV/monitor.
 */
internal fun filterShadowedExternalDisplays(screens: List<ScreenInfo>, currentDisplay: Int): List<ScreenInfo> {
    val hasActiveSystemDesktop = screens.any {
        it.id != Display.DEFAULT_DISPLAY && isSystemDesktopDisplay(it.name)
    }
    if (!hasActiveSystemDesktop) return screens
    return screens.filterNot {
        it.id != Display.DEFAULT_DISPLAY && it.id != currentDisplay && !isSystemDesktopDisplay(it.name) && it.canPresent
    }
}

enum class ExternalLaunchMethod { Activity, ShellActivity, Presentation }

enum class ExternalLaunchOutcome { Opened, DisplayDisconnected, Rejected }

internal fun isMisplacedExternalLaunch(actualDisplayId: Int, expectedDisplayId: Int): Boolean =
    actualDisplayId == Display.DEFAULT_DISPLAY ||
        (expectedDisplayId != Display.INVALID_DISPLAY && actualDisplayId != expectedDisplayId)

internal fun resolveExternalLaunchOrder(
    canLaunchActivity: Boolean,
    canPresent: Boolean,
    adbGranted: Boolean,
): List<ExternalLaunchMethod> = when {
    canLaunchActivity -> buildList {
        add(ExternalLaunchMethod.Activity)
        if (adbGranted) add(ExternalLaunchMethod.ShellActivity)
        add(ExternalLaunchMethod.Presentation)
    }
    canPresent -> listOf(ExternalLaunchMethod.Presentation)
    adbGranted -> listOf(ExternalLaunchMethod.ShellActivity)
    else -> emptyList()
}

data class InstalledApp(
    val component: ComponentName,
    val label: String,
    val icon: Drawable,
    val isSystemApp: Boolean = false,
) {
    val id: String get() = component.flattenToShortString()
}

fun Context.installedApps(): List<InstalledApp> {
    val manager = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.getDefault())
    return manager.queryIntentActivities(intent, 0).mapNotNull { entry ->
        val activity = entry.activityInfo
        if (!activity.exported || activity.packageName == packageName) return@mapNotNull null
        if (activity.permission?.let { manager.checkPermission(it, packageName) != PackageManager.PERMISSION_GRANTED } == true) return@mapNotNull null
        val isSystem = (activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        InstalledApp(
            ComponentName(activity.packageName, activity.name),
            entry.loadLabel(manager).toString(),
            entry.loadIcon(manager),
            isSystemApp = isSystem,
        )
    }.distinctBy { it.id }.sortedWith { a, b -> collator.compare(a.label, b.label) }
}

data class ScreenInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val canOpenWorkspace: Boolean,
    val canLaunchActivity: Boolean = canOpenWorkspace,
    val canPresent: Boolean = false,
)
data class DesktopCapabilities(
    val currentDisplay: Int = Display.DEFAULT_DISPLAY,
    val screens: List<ScreenInfo> = emptyList(),
    val secondaryActivities: Boolean = false,
    val freeform: Boolean = false,
    val home: Boolean = false,
) {
    val external get() = currentDisplay != Display.DEFAULT_DISPLAY
}

enum class LaunchResult { Requested, DisplayDisconnected, DisplayRejected, Unavailable }

class AndroidDesktop(private val activity: Activity, private val fixedDisplayId: Int? = null) {
    val displayManager: DisplayManager = activity.getSystemService(DisplayManager::class.java)
    private val activityManager = activity.getSystemService(ActivityManager::class.java)
    private val roles = activity.getSystemService(RoleManager::class.java)

    @Suppress("DEPRECATION") // Activity.display was added in API 30.
    fun currentDisplayId(): Int =
        fixedDisplayId
            ?: (if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay)
                ?.displayId
            ?: Display.DEFAULT_DISPLAY

    fun findDisplay(displayId: Int): Display? =
        displayManager.getDisplay(displayId)?.takeIf { it.isValid && isDisplayActive(it.state) }

    fun boundsAboveDock(dockHeightPx: Int): IntRect? {
        if (fixedDisplayId != null) return null
        if (Build.VERSION.SDK_INT < 30 || dockHeightPx <= 0 || activity.isInMultiWindowMode) return null
        val metrics = activity.windowManager.currentWindowMetrics
        if (metrics.bounds != activity.windowManager.maximumWindowMetrics.bounds) return null
        // System bars are hidden in immersive mode; respect only visible system bars and the display cutout.
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val frame = metrics.bounds
        val result = IntRect(frame.left + insets.left, frame.top + insets.top,
            frame.right - insets.right, frame.bottom - insets.bottom - dockHeightPx)
        return result.takeIf { it.width > 0 && it.height > 0 }
    }

    private val supportsDisplayTargeting =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)

    fun inspect(): DesktopCapabilities {
        val current = currentDisplayId()
        val presentationIds = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .mapTo(HashSet()) { it.displayId }
        val activeScreens = displayManager.displays.filter {
            it.isValid && isDisplayActive(it.state) && !isOwnHostedVirtualDisplay(it.name)
        }.map {
            val id = it.displayId
            val canActivity = id != Display.DEFAULT_DISPLAY && id != current && supportsDisplayTargeting &&
                isStartAllowed(id, externalWorkspaceIntent(id))
            val canPresent = id != Display.DEFAULT_DISPLAY && id != current &&
                (id in presentationIds || (it.flags and Display.FLAG_PRESENTATION) != 0)
            val isDesktopTarget = id != Display.DEFAULT_DISPLAY && id != current &&
                isSystemDesktopDisplay(it.name)
            ScreenInfo(
                id = id,
                name = it.name,
                width = it.mode.physicalWidth,
                height = it.mode.physicalHeight,
                canOpenWorkspace = canActivity || canPresent || isDesktopTarget,
                canLaunchActivity = canActivity,
                canPresent = canPresent || isDesktopTarget,
            )
        }
        return DesktopCapabilities(
            currentDisplay = current,
            screens = filterShadowedExternalDisplays(activeScreens, current),
            secondaryActivities = supportsDisplayTargeting,
            freeform = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
            home = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME),
        )
    }

    fun open(app: InstalledApp, displayId: Int): LaunchResult = launch(
        Intent.makeMainActivity(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), displayId,
    )

    fun openAppInfo(app: InstalledApp, displayId: Int): LaunchResult = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        displayId,
    )

    fun requestUninstall(app: InstalledApp, displayId: Int): LaunchResult = launch(
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        displayId,
    )

    fun openWorkspaceOn(displayId: Int): LaunchResult = launch(externalWorkspaceIntent(displayId), displayId)

    private fun externalWorkspaceIntent(displayId: Int): Intent =
        Intent(activity, ExternalDisplayActivity::class.java)
            .putExtra(ExternalDisplayActivity.EXTRA_TARGET_DISPLAY_ID, displayId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun homeIntent(): Intent = if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
        roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    } else Intent(Settings.ACTION_HOME_SETTINGS)

    private fun launch(intent: Intent, displayId: Int): LaunchResult = try {
        when {
            displayManager.getDisplay(displayId)?.isValid != true -> LaunchResult.DisplayDisconnected
            // Standard phone/tablet launch must work even when the device has no display targeting.
            !supportsDisplayTargeting && displayId == Display.DEFAULT_DISPLAY && currentDisplayId() == Display.DEFAULT_DISPLAY -> {
                activity.startActivity(intent)
                LaunchResult.Requested
            }
            !supportsDisplayTargeting || !isStartAllowed(displayId, intent) -> LaunchResult.DisplayRejected
            else -> {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                activity.startActivity(intent, options.toBundle())
                // The OS can still reuse an existing task elsewhere. This reports a request, not placement.
                LaunchResult.Requested
            }
        }
    } catch (_: ActivityNotFoundException) {
        LaunchResult.Unavailable
    } catch (_: SecurityException) {
        if (displayId == Display.DEFAULT_DISPLAY && currentDisplayId() == Display.DEFAULT_DISPLAY) {
            LaunchResult.Unavailable
        } else rejectedOrDisconnected(displayId)
    } catch (_: IllegalArgumentException) {
        rejectedOrDisconnected(displayId)
    }

    private fun rejectedOrDisconnected(displayId: Int): LaunchResult =
        if (displayManager.getDisplay(displayId)?.isValid == true) LaunchResult.DisplayRejected
        else LaunchResult.DisplayDisconnected

    private fun isStartAllowed(displayId: Int, intent: Intent): Boolean = try {
        activityManager.isActivityStartAllowedOnDisplay(activity, displayId, intent)
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
