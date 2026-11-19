package dev.opendex.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import dev.opendex.MainActivity
import java.text.Collator
import java.util.Locale

data class InstalledApp(val component: ComponentName, val label: String, val icon: Drawable) {
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
        InstalledApp(ComponentName(activity.packageName, activity.name),
            entry.loadLabel(manager).toString(), entry.loadIcon(manager))
    }.distinctBy { it.id }.sortedWith { a, b -> collator.compare(a.label, b.label) }
}

data class ScreenInfo(
    val id: Int, val name: String, val width: Int, val height: Int,
    val canOpenWorkspace: Boolean,
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

class AndroidDesktop(private val activity: Activity) {
    val displayManager: DisplayManager = activity.getSystemService(DisplayManager::class.java)
    private val activityManager = activity.getSystemService(ActivityManager::class.java)
    private val roles = activity.getSystemService(RoleManager::class.java)

    @Suppress("DEPRECATION") // Activity.display was added in API 30.
    fun currentDisplayId(): Int =
        (if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay)
            ?.displayId ?: Display.DEFAULT_DISPLAY

    private val supportsDisplayTargeting =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)

    fun inspect(): DesktopCapabilities {
        val current = currentDisplayId()
        return DesktopCapabilities(
            currentDisplay = current,
            screens = displayManager.displays.filter { it.isValid }.map {
                val id = it.displayId
                val canOpen = id != Display.DEFAULT_DISPLAY && id != current && supportsDisplayTargeting &&
                    isStartAllowed(id, workspaceIntent())
                ScreenInfo(id, it.name, it.mode.physicalWidth, it.mode.physicalHeight, canOpen)
            },
            secondaryActivities = supportsDisplayTargeting,
            freeform = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
            home = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME),
        )
    }

    fun open(app: InstalledApp, displayId: Int): LaunchResult = launch(
        Intent.makeMainActivity(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), displayId,
    )

    fun openWorkspaceOn(displayId: Int): LaunchResult = launch(workspaceIntent(), displayId)

    private fun workspaceIntent(): Intent =
        Intent(activity, MainActivity::class.java)
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
