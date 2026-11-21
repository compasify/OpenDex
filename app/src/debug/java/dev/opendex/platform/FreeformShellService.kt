package dev.opendex.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.ui.unit.IntRect
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

internal const val SHELL_UID = 2000
internal const val FREEFORM_DESCRIPTOR = "dev.opendex.platform.FreeformShell"
internal const val OPEN_FREEFORM = Binder.FIRST_CALL_TRANSACTION
internal const val CREATE_DISPLAY = Binder.FIRST_CALL_TRANSACTION + 1
internal const val LAUNCH_ON_DISPLAY = Binder.FIRST_CALL_TRANSACTION + 2
internal const val RESIZE_DISPLAY = Binder.FIRST_CALL_TRANSACTION + 3
internal const val SET_DISPLAY_SURFACE = Binder.FIRST_CALL_TRANSACTION + 4
internal const val INJECT_MOTION = Binder.FIRST_CALL_TRANSACTION + 5
internal const val INJECT_KEY = Binder.FIRST_CALL_TRANSACTION + 6
internal const val RELEASE_DISPLAY = Binder.FIRST_CALL_TRANSACTION + 7
internal const val RELEASE_ALL_DISPLAYS = Binder.FIRST_CALL_TRANSACTION + 8
internal const val LAUNCH_ACTION_ON_DISPLAY = Binder.FIRST_CALL_TRANSACTION + 9
private const val DESTROY_USER_SERVICE = 16777115
private const val PER_ANDROID_USER_UID_RANGE = 100_000

private val ALLOWED_SETTINGS_ACTIONS = setOf(
    Settings.ACTION_SETTINGS,
    Settings.ACTION_WIFI_SETTINGS,
    Settings.ACTION_BLUETOOTH_SETTINGS,
    Settings.ACTION_CAST_SETTINGS,
    Settings.ACTION_DISPLAY_SETTINGS,
    Settings.ACTION_SOUND_SETTINGS,
    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
)

private val PACKAGE_URI_PATTERN = Regex("^package:[A-Za-z0-9._]+$")

private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
private const val ANIMATION_SCALE_TRANSITION = 1

/** Created by Shizuku, not exported as an Android component. Accepts no arbitrary commands. */
class FreeformShellService(private val context: Context) : Binder() {
    private val ownerUid = context.applicationInfo.uid
    private val userId = ownerUid / PER_ANDROID_USER_UID_RANGE
    private val displayCounter = AtomicInteger(0)
    private val displays = ConcurrentHashMap<Int, HostedDisplay>()

    private data class HostedDisplay(val virtualDisplay: VirtualDisplay, @Volatile var surface: Surface?)

    private val shellContext: Context by lazy {
        runCatching {
            context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse { context }
    }

    private val setDisplayIdMethod: Method? by lazy {
        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.onFailure { Log.w("OpenDex", "InputEvent.setDisplayId unavailable", it) }.getOrNull()
    }

    private val inputInjector: Pair<Any, Method>? by lazy { resolveInputInjector() }
    private val transitionLock = Any()

    private val windowManager: Any? by lazy {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as? IBinder ?: return@runCatching null
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    private val getAnimationScaleMethod: Method? by lazy {
        runCatching {
            windowManager?.javaClass?.getMethod("getAnimationScale", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private val setAnimationScaleMethod: Method? by lazy {
        runCatching {
            windowManager?.javaClass?.getMethod(
                "setAnimationScale",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    init { attachInterface(null, FREEFORM_DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val caller = Binder.getCallingUid()
        if (code == DESTROY_USER_SERVICE && caller == SHELL_UID) {
            releaseAllDisplays()
            exitProcess(0)
        }
        if (code !in OPEN_FREEFORM..LAUNCH_ACTION_ON_DISPLAY || reply == null) {
            return super.onTransact(code, data, reply, flags)
        }
        if (caller != ownerUid || Process.myUid() != SHELL_UID) throw SecurityException("Unexpected caller or backend")
        data.enforceInterface(FREEFORM_DESCRIPTOR)
        when (code) {
            OPEN_FREEFORM -> {
                val component = data.readString()?.let(ComponentName::unflattenFromString)
                val display = data.readInt()
                val bounds = if (data.readInt() == 0) null else IntRect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                val accepted = component != null && display == Display.DEFAULT_DISPLAY && isLaunchable(component) &&
                    if (bounds == null) launch(component, display) else fitAboveDock(component, display, bounds)
                reply.writeNoException()
                reply.writeInt(if (accepted) 1 else 0)
            }
            CREATE_DISPLAY -> {
                val width = data.readInt().coerceIn(240, 4096)
                val height = data.readInt().coerceIn(160, 4096)
                val densityDpi = data.readInt().coerceIn(120, 640)
                val surface = if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null
                val displayId = createVirtualDisplay(width, height, densityDpi, surface)
                reply.writeNoException()
                reply.writeInt(displayId)
            }
            LAUNCH_ON_DISPLAY -> {
                val component = data.readString()?.let(ComponentName::unflattenFromString)
                val displayId = data.readInt()
                val ok = component != null && displays.containsKey(displayId) && isLaunchable(component) &&
                    launchOnVirtualDisplay(component, displayId)
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
            RESIZE_DISPLAY -> {
                val displayId = data.readInt()
                val width = data.readInt().coerceIn(240, 4096)
                val height = data.readInt().coerceIn(160, 4096)
                val densityDpi = data.readInt().coerceIn(120, 640)
                val ok = resizeVirtualDisplay(displayId, width, height, densityDpi)
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
            SET_DISPLAY_SURFACE -> {
                val displayId = data.readInt()
                val surface = if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null
                val ok = setVirtualDisplaySurface(displayId, surface)
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
            INJECT_MOTION -> {
                val displayId = data.readInt()
                val event = MotionEvent.CREATOR.createFromParcel(data)
                val ok = try {
                    isAllowedInputDisplay(displayId) && injectInputEvent(displayId, event)
                } finally {
                    event.recycle()
                }
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
            INJECT_KEY -> {
                val displayId = data.readInt()
                val event = KeyEvent.CREATOR.createFromParcel(data)
                val ok = isAllowedInputDisplay(displayId) && injectInputEvent(displayId, event)
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
            RELEASE_DISPLAY -> {
                val displayId = data.readInt()
                releaseVirtualDisplay(displayId)
                reply.writeNoException()
                reply.writeInt(1)
            }
            RELEASE_ALL_DISPLAYS -> {
                releaseAllDisplays()
                reply.writeNoException()
                reply.writeInt(1)
            }
            LAUNCH_ACTION_ON_DISPLAY -> {
                val action = data.readString()
                val displayId = data.readInt()
                val dataUri = data.readString()
                val validUri = dataUri == null || (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS && PACKAGE_URI_PATTERN.matches(dataUri))
                val ok = action != null && action in ALLOWED_SETTINGS_ACTIONS && validUri && displays.containsKey(displayId) &&
                    launchActionOnVirtualDisplay(action, displayId, dataUri)
                reply.writeNoException()
                reply.writeInt(if (ok) 1 else 0)
            }
        }
        return true
    }

    private fun createVirtualDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?): Int {
        val dm = shellContext.getSystemService(DisplayManager::class.java) ?: run {
            surface?.release()
            return Display.INVALID_DISPLAY
        }
        val name = "OpenDex-${displayCounter.incrementAndGet()}"
        val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val flagCandidates = intArrayOf(
            baseFlags or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED,
            baseFlags or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                VIRTUAL_DISPLAY_FLAG_TRUSTED,
            baseFlags or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL,
            baseFlags,
        )
        var vd: VirtualDisplay? = null
        for (candidate in flagCandidates) {
            vd = runCatching {
                dm.createVirtualDisplay(name, width, height, densityDpi, surface, candidate)
            }.onFailure {
                Log.w("OpenDex", "createVirtualDisplay failed for flags=0x${candidate.toString(16)}", it)
            }.getOrNull()
            if (vd != null) break
        }
        val created = vd ?: run {
            surface?.release()
            return Display.INVALID_DISPLAY
        }
        val displayId = created.display?.displayId ?: Display.INVALID_DISPLAY
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
            runCatching { created.release() }
            surface?.release()
            return Display.INVALID_DISPLAY
        }
        displays[displayId] = HostedDisplay(created, surface)
        configureImeFallback(displayId)
        return displayId
    }

    private fun configureImeFallback(displayId: Int) {
        runCatching {
            val iwm = windowManager ?: return
            iwm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(iwm, displayId, DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        }.onFailure { Log.d("OpenDex", "setDisplayImePolicy skipped: ${it.message}") }
    }

    private fun launchOnVirtualDisplay(component: ComponentName, displayId: Int): Boolean {
        val started = runAm(
            "start", "-W", "--user", userId.toString(),
            "--display", displayId.toString(),
            "--windowingMode", "1",
            "-a", Intent.ACTION_MAIN,
            "-c", Intent.CATEGORY_LAUNCHER,
            "-f", "0x10000000",
            "-n", component.flattenToString(),
        )
        if (started == null) return false
        // If an existing task for this app lived on another display, move its stack onto the virtual display.
        val stacks = runAm("stack", "list") ?: return true
        val existing = findComponentRootTask(stacks, component.flattenToString(), component.flattenToShortString(), userId)
        if (existing != null && existing.displayId != displayId) {
            runAm("display", "move-stack", existing.rootTaskId.toString(), displayId.toString())
            runAm(
                "start", "--user", userId.toString(),
                "--display", displayId.toString(),
                "--windowingMode", "1",
                "-a", Intent.ACTION_MAIN,
                "-c", Intent.CATEGORY_LAUNCHER,
                "-f", "0x10000000",
                "-n", component.flattenToString(),
            )
        }
        return true
    }

    private fun launchActionOnVirtualDisplay(action: String, displayId: Int, dataUri: String? = null): Boolean {
        val intent = Intent(action).apply {
            if (dataUri != null) data = android.net.Uri.parse(dataUri)
        }
        val resolved = runCatching {
            shellContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        }.getOrNull()
        val targetComponent = resolved?.let { ComponentName(it.packageName, it.name) }

        // Move any existing task for the target settings package onto this virtual display first so it never pulls focus to Display 0.
        if (targetComponent != null) {
            runAm("stack", "list")?.let { stacks ->
                val existing = findComponentRootTask(stacks, targetComponent.flattenToString(), targetComponent.flattenToShortString(), userId)
                if (existing != null && existing.displayId != displayId) {
                    runAm("display", "move-stack", existing.rootTaskId.toString(), displayId.toString())
                }
            }
        }

        val startArgs = buildList {
            add("start")
            add("-W")
            add("--user"); add(userId.toString())
            add("--display"); add(displayId.toString())
            add("--windowingMode"); add("1")
            add("-a"); add(action)
            if (dataUri != null) {
                add("-d"); add(dataUri)
            }
            add("-f"); add("0x10000000")
        }
        val started = runAm(*startArgs.toTypedArray())
        if (started == null) return false

        if (targetComponent != null) {
            val stacksAfter = runAm("stack", "list") ?: return true
            val after = findComponentRootTask(stacksAfter, targetComponent.flattenToString(), targetComponent.flattenToShortString(), userId)
            if (after != null && after.displayId != displayId) {
                runAm("display", "move-stack", after.rootTaskId.toString(), displayId.toString())
                val retryArgs = buildList {
                    add("start")
                    add("--user"); add(userId.toString())
                    add("--display"); add(displayId.toString())
                    add("--windowingMode"); add("1")
                    add("-a"); add(action)
                    if (dataUri != null) {
                        add("-d"); add(dataUri)
                    }
                    add("-f"); add("0x10000000")
                }
                runAm(*retryArgs.toTypedArray())
            }
        }
        return true
    }

    private fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean {
        val hosted = displays[displayId] ?: return false
        return runCatching {
            withoutTransitionAnimation {
                hosted.virtualDisplay.resize(width, height, densityDpi)
            }
            true
        }.onFailure { Log.w("OpenDex", "resizeVirtualDisplay failed for display $displayId", it) }.getOrDefault(false)
    }

    /**
     * Suppress the system display-change transition animation while resizing a VirtualDisplay.
     * Some ROMs (e.g. HyperOS/MIUI) leak the VirtualDisplay's TRANSIT_CHANGE screenshot leash
     * onto Display 0 at (0, 0), causing a visible top-left flash on every window resize.
     */
    private fun withoutTransitionAnimation(action: () -> Unit) {
        val iwm = windowManager
        val getScale = getAnimationScaleMethod
        val setScale = setAnimationScaleMethod
        if (iwm == null || getScale == null || setScale == null) {
            action()
            return
        }
        synchronized(transitionLock) {
            val previous = runCatching { getScale.invoke(iwm, ANIMATION_SCALE_TRANSITION) as? Float }.getOrNull()
            val suppressed = previous != null && previous > 0f &&
                runCatching { setScale.invoke(iwm, ANIMATION_SCALE_TRANSITION, 0f) }.isSuccess
            try {
                action()
                if (suppressed) {
                    SystemClock.sleep(140)
                }
            } finally {
                if (suppressed) {
                    runCatching { setScale.invoke(iwm, ANIMATION_SCALE_TRANSITION, previous) }
                }
            }
        }
    }

    private fun setVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        val hosted = displays[displayId] ?: run {
            surface?.release()
            return false
        }
        val previous = hosted.surface
        return try {
            hosted.virtualDisplay.surface = surface
            hosted.surface = surface
            previous?.release()
            true
        } catch (e: RuntimeException) {
            Log.w("OpenDex", "setVirtualDisplaySurface failed for display $displayId", e)
            surface?.release()
            false
        }
    }

    private fun releaseVirtualDisplay(displayId: Int) {
        val hosted = displays.remove(displayId) ?: return
        runAm("stack", "list")?.let { stacks ->
            for (rootId in findRootTaskIdsOnDisplay(stacks, displayId)) {
                runAm("stack", "remove", rootId.toString())
            }
        }
        runCatching { hosted.virtualDisplay.surface = null }
        runCatching { hosted.virtualDisplay.release() }
        runCatching { hosted.surface?.release() }
        hosted.surface = null
    }

    private fun releaseAllDisplays() {
        for (displayId in displays.keys.toList()) {
            releaseVirtualDisplay(displayId)
        }
    }

    private fun resolveInputInjector(): Pair<Any, Method>? {
        runCatching {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = globalClass.getMethod("getInstance").invoke(null)
            val method = globalClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            if (instance != null) return instance to method
        }
        runCatching {
            val im = shellContext.getSystemService(InputManager::class.java)
            val method = InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            if (im != null) return im to method
        }
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
            val iim = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            if (iim != null) {
                val method = iim.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                return iim to method
            }
        }
        Log.w("OpenDex", "No InputManager injector available")
        return null
    }

    private fun isAllowedInputDisplay(displayId: Int): Boolean {
        if (displays.containsKey(displayId)) return true
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return false
        val dm = shellContext.getSystemService(DisplayManager::class.java) ?: return false
        return dm.getDisplay(displayId)?.isValid == true
    }

    private fun injectInputEvent(displayId: Int, event: InputEvent): Boolean {
        val (target, injectMethod) = inputInjector ?: return false
        val setDisplay = setDisplayIdMethod ?: return false
        return runCatching {
            setDisplay.invoke(event, displayId)
            (injectMethod.invoke(target, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean) ?: true
        }.onFailure {
            Log.w("OpenDex", "injectInputEvent failed on display $displayId", it)
        }.getOrDefault(false)
    }

    private fun isLaunchable(component: ComponentName): Boolean {
        if (component.packageName == context.packageName) return false
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.packageName)
        return pm.queryIntentActivities(launcher, 0).any { entry ->
            val activity = entry.activityInfo
            activity.exported && activity.name == component.className && activity.packageName == component.packageName &&
                (activity.permission?.let { pm.checkPermission(it, context.packageName) == PackageManager.PERMISSION_GRANTED } ?: true)
        }
    }

    private fun fitAboveDock(component: ComponentName, display: Int, bounds: IntRect): Boolean {
        if (bounds.left < 0 || bounds.top < 0 || bounds.width <= 0 || bounds.height <= 0) return false
        fun findTask() = runAm("stack", "list")?.let {
            findVisibleFreeformTask(it, component.flattenToString(), component.flattenToShortString(), display, userId)
        }
        if (findTask() == null) {
            if (!launch(component, display, wait = true)) return false
        }
        // ActivityManager can report a launch complete before the ROM finishes placing the window.
        repeat(4) {
            SystemClock.sleep(250)
            val task = findTask() ?: return@repeat
            if (task.bounds != bounds && runAm("task", "resize", task.id.toString(), bounds.left.toString(),
                    bounds.top.toString(), bounds.right.toString(), bounds.bottom.toString()) == null) return false
            SystemClock.sleep(250)
            if (findTask() == FreeformTask(task.id, bounds)) return true
        }
        Log.w("OpenDex", "Freeform task did not settle at the requested bounds")
        return false
    }

    private fun launch(component: ComponentName, display: Int, wait: Boolean = false): Boolean = runAm(
        "start", *(if (wait) arrayOf("-W") else emptyArray()), "--user", userId.toString(),
        "--display", display.toString(), "--windowingMode", "5", "-a", Intent.ACTION_MAIN,
        "-c", Intent.CATEGORY_LAUNCHER, "-n", component.flattenToString(),
    ) != null

    private fun runAm(vararg args: String): String? {
        val process = try {
            ProcessBuilder("/system/bin/am", *args).redirectErrorStream(true).start()
        } catch (_: java.io.IOException) { return null }
        // Stack listings can exceed the pipe buffer; drain output while am runs.
        val output = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
        Thread(output, "opendex-am-output").apply { isDaemon = true }.start()
        return try {
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            // Some ROMs report an ActivityManager error in text but still exit with status zero.
            output.get(1, TimeUnit.SECONDS).takeUnless { text ->
                text.lineSequence().any { it.trimStart().startsWith("Error", ignoreCase = true) || it.contains("SecurityException") }
            }
        } catch (_: java.util.concurrent.ExecutionException) { null }
        catch (_: java.util.concurrent.TimeoutException) { null }
        finally { process.destroyForcibly(); output.cancel(true) }
    }
}
