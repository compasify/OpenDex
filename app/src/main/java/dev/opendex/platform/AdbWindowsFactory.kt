package dev.opendex.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.ui.unit.IntRect
import dev.opendex.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

fun createAdbWindows(context: Context): AdbWindows = ShizukuAdbWindows(context.applicationContext)

private class ShizukuAdbWindows(private val context: Context) : AdbWindows {
    private val status = MutableStateFlow(detect())
    override val access = status.asStateFlow()
    override val supportsHostedWindows: Boolean = true

    private val serviceLock = Mutex()
    private val inputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opendex-input-inject").apply { isDaemon = true }
    }
    private val pendingMove = AtomicReference<Pair<Int, MotionEvent>?>(null)
    @Volatile private var activeBinder: IBinder? = null
    @Volatile private var activeConnection: ServiceConnection? = null
    @Volatile private var closed = false

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, FreeformShellService::class.java))
        .tag("opendex-freeform-${Process.myUid()}")
        .processNameSuffix("freeform_shell")
        .daemon(false)
        .version(9)

    private val directAdbListener: () -> Unit = { refresh() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        activeBinder = null
        activeConnection = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        DirectAdbBridge.addListener(directAdbListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        if (BuildConfig.ALLOW_ROOT_BACKEND && DirectAdbBridge.activeBinder == null && hasSuBinary()) {
            startRootServerAsync()
        }
    }

    override fun refresh() { status.value = detect() }

    private fun currentBinder(): IBinder? =
        DirectAdbBridge.activeBinder ?: activeBinder?.takeIf { it.isBinderAlive }

    private fun detect(): AdbAccess {
        if (DirectAdbBridge.activeBinder != null) return AdbAccess.Granted
        val shizukuStatus = try {
            when {
                !Shizuku.pingBinder() -> AdbAccess.NotRunning
                Shizuku.isPreV11() || Shizuku.getVersion() < 13 || !isAuthorizedBackendUid(Shizuku.getUid()) -> AdbAccess.WrongBackend
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> AdbAccess.NeedsPermission
                else -> AdbAccess.Granted
            }
        } catch (_: RuntimeException) { AdbAccess.NotRunning }
        if (shizukuStatus != AdbAccess.NotRunning) return shizukuStatus
        return if (BuildConfig.ALLOW_ROOT_BACKEND && hasSuBinary()) AdbAccess.NeedsPermission else AdbAccess.NotRunning
    }

    override fun requestAccess(): Boolean {
        if (BuildConfig.ALLOW_ROOT_BACKEND && hasSuBinary() && !isShizukuAwaitingPermission()) {
            return startRootServerAsync()
        }
        return try {
            if (detect() != AdbAccess.NeedsPermission || Shizuku.shouldShowRequestPermissionRationale()) false
            else { Shizuku.requestPermission(501); true }
        } catch (_: RuntimeException) { false }
    }

    private fun isShizukuAwaitingPermission(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= 13 &&
            isAuthorizedBackendUid(Shizuku.getUid()) &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) { false }

    private fun hasSuBinary(): Boolean {
        val candidates = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/vendor/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
        )
        if (candidates.any { File(it).exists() }) return true
        val path = System.getenv("PATH") ?: return false
        return path.split(':').any { dir -> dir.isNotBlank() && File(dir, "su").exists() }
    }

    private fun startRootServerAsync(): Boolean {
        if (!BuildConfig.ALLOW_ROOT_BACKEND || closed) return false
        val apkPath = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val shellCmd = "nohup sh -c 'CLASSPATH=\"$apkPath\" app_process /system/bin dev.opendex.platform.OpenDexShellServer $pkg' </dev/null >/dev/null 2>&1 &"
        return runCatching {
            Thread({
                val proc = runCatching {
                    ProcessBuilder("su", "-c", shellCmd).redirectErrorStream(true).start()
                }.onFailure { Log.w("OpenDex", "Failed to launch root server via su", it) }.getOrNull()
                    ?: return@Thread
                runCatching { proc.waitFor(5, TimeUnit.SECONDS) }
                runCatching { proc.destroy() }
                refresh()
            }, "opendex-root-launcher").apply { isDaemon = true }.start()
            true
        }.getOrDefault(false)
    }

    private suspend fun ensureBinder(): IBinder? = serviceLock.withLock {
        if (closed) return@withLock null
        DirectAdbBridge.activeBinder?.let { return@withLock it }
        if (detect() != AdbAccess.Granted) return@withLock null
        activeBinder?.takeIf { it.isBinderAlive }?.let { return@withLock it }
        unbindLocked(destroy = false)
        val ready = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                activeBinder = binder
                ready.complete(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                activeBinder = null
                ready.complete(null)
            }
        }
        activeConnection = connection
        return@withLock try {
            Shizuku.bindUserService(userServiceArgs, connection)
            val binder = withTimeoutOrNull(6_000) { ready.await() }
            if (binder == null) unbindLocked(destroy = false)
            binder
        } catch (e: RuntimeException) {
            if (e is CancellationException) throw e
            Log.w("OpenDex", "Failed to bind Shizuku UserService", e)
            unbindLocked(destroy = false)
            null
        }
    }

    private fun unbindLocked(destroy: Boolean) {
        val conn = activeConnection
        activeBinder = null
        activeConnection = null
        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, destroy) }
        }
    }

    private suspend fun <T> transact(
        code: Int,
        defaultValue: T,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val binder = ensureBinder() ?: return defaultValue
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(FREEFORM_DESCRIPTOR)
                write(data)
                if (!binder.transact(code, data, reply, 0)) return@withContext defaultValue
                reply.readException()
                read(reply)
            } catch (e: RemoteException) {
                Log.w("OpenDex", "Shell service transaction $code failed", e)
                activeBinder = null
                defaultValue
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
                Log.w("OpenDex", "Shell service request $code failed", e)
                defaultValue
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    override suspend fun open(app: InstalledApp, displayId: Int, bounds: IntRect?, stillOnDisplay: () -> Boolean): Boolean {
        // A native freeform shell launch to an external display can be rerouted to the phone on disconnect.
        if (displayId != Display.DEFAULT_DISPLAY || detect() != AdbAccess.Granted) return false
        val displays = context.getSystemService(DisplayManager::class.java)
        if (!stillOnDisplay() || displays?.getDisplay(displayId)?.isValid != true) return false
        return transact(
            code = OPEN_FREEFORM,
            defaultValue = false,
            write = { data ->
                data.writeString(app.component.flattenToString())
                data.writeInt(displayId)
                data.writeInt(if (bounds == null) 0 else 1)
                if (bounds != null) {
                    data.writeInt(bounds.left)
                    data.writeInt(bounds.top)
                    data.writeInt(bounds.right)
                    data.writeInt(bounds.bottom)
                }
            },
            read = { reply -> reply.readInt() == 1 },
        ).also { refresh() }
    }

    override suspend fun createAppDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?): Int =
        transact(
            code = CREATE_DISPLAY,
            defaultValue = Display.INVALID_DISPLAY,
            write = { data ->
                data.writeInt(width)
                data.writeInt(height)
                data.writeInt(densityDpi)
                if (surface != null) {
                    data.writeInt(1)
                    surface.writeToParcel(data, 0)
                } else {
                    data.writeInt(0)
                }
            },
            read = { reply -> reply.readInt() },
        )

    override suspend fun launchOnDisplay(app: InstalledApp, displayId: Int): Boolean =
        transact(
            code = LAUNCH_ON_DISPLAY,
            defaultValue = false,
            write = { data ->
                data.writeString(app.component.flattenToString())
                data.writeInt(displayId)
            },
            read = { reply -> reply.readInt() == 1 },
        )

    override suspend fun launchActionOnDisplay(action: String, displayId: Int, dataUri: String?): Boolean =
        transact(
            code = LAUNCH_ACTION_ON_DISPLAY,
            defaultValue = false,
            write = { data ->
                data.writeString(action)
                data.writeInt(displayId)
                data.writeString(dataUri)
            },
            read = { reply -> reply.readInt() == 1 },
        )

    override suspend fun resizeAppDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean =
        transact(
            code = RESIZE_DISPLAY,
            defaultValue = false,
            write = { data ->
                data.writeInt(displayId)
                data.writeInt(width)
                data.writeInt(height)
                data.writeInt(densityDpi)
            },
            read = { reply -> reply.readInt() == 1 },
        )

    override suspend fun setAppDisplaySurface(displayId: Int, surface: Surface?): Boolean =
        transact(
            code = SET_DISPLAY_SURFACE,
            defaultValue = false,
            write = { data ->
                data.writeInt(displayId)
                if (surface != null) {
                    data.writeInt(1)
                    surface.writeToParcel(data, 0)
                } else {
                    data.writeInt(0)
                }
            },
            read = { reply -> reply.readInt() == 1 },
        )

    override suspend fun ensureConnected(): Boolean = ensureBinder() != null

    override suspend fun setSuppressExternalIme(externalDisplayId: Int, suppress: Boolean) {
        if (closed || detect() != AdbAccess.Granted) return
        transact(
            code = SET_SUPPRESS_IME,
            defaultValue = Unit,
            write = { data ->
                data.writeInt(externalDisplayId)
                data.writeInt(if (suppress) 1 else 0)
            },
            read = { Unit },
        )
    }

    override suspend fun launchExternalWorkspace(displayId: Int): Boolean {
        if (closed || displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return false
        if (detect() != AdbAccess.Granted) return false
        return transact(
            code = LAUNCH_EXTERNAL_WORKSPACE,
            defaultValue = false,
            write = { data -> data.writeInt(displayId) },
            read = { reply -> reply.readInt() == 1 },
        )
    }

    override fun injectMotion(displayId: Int, event: MotionEvent): Boolean {
        if (closed || displayId == Display.INVALID_DISPLAY) return false
        val binder = currentBinder() ?: return false
        val copy = MotionEvent.obtain(event)
        val isCoalesceable = copy.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            copy.actionMasked == MotionEvent.ACTION_MOVE
        if (isCoalesceable) {
            val prev = pendingMove.getAndSet(displayId to copy)
            prev?.second?.recycle()
            if (prev != null) return true
            return try {
                inputExecutor.execute {
                    val latest = pendingMove.getAndSet(null) ?: return@execute
                    transactMotion(binder, latest.first, latest.second)
                }
                true
            } catch (_: RuntimeException) {
                pendingMove.getAndSet(null)?.second?.recycle()
                false
            }
        }
        return try {
            inputExecutor.execute {
                // Flush any pending drag move before DOWN/UP so clicks/releases land at exact latest coordinates.
                pendingMove.getAndSet(null)?.let { (pendingDisplayId, pendingEvent) ->
                    if (pendingEvent.actionMasked == MotionEvent.ACTION_MOVE) {
                        transactMotion(binder, pendingDisplayId, pendingEvent)
                    } else {
                        pendingEvent.recycle()
                    }
                }
                transactMotion(binder, displayId, copy)
            }
            true
        } catch (_: RuntimeException) {
            copy.recycle()
            false
        }
    }

    private fun transactMotion(binder: IBinder, displayId: Int, event: MotionEvent) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FREEFORM_DESCRIPTOR)
            data.writeInt(displayId)
            event.writeToParcel(data, 0)
            binder.transact(INJECT_MOTION, data, reply, 0)
            reply.readException()
        } catch (_: RemoteException) {
            activeBinder = null
        } catch (_: RuntimeException) {
            // Ignore transient injection errors during teardown.
        } finally {
            reply.recycle()
            data.recycle()
            event.recycle()
        }
    }

    override fun injectKey(displayId: Int, event: KeyEvent): Boolean {
        if (closed || displayId == Display.INVALID_DISPLAY) return false
        val binder = currentBinder() ?: return false
        val copy = KeyEvent(event)
        return runCatching {
            inputExecutor.execute {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(FREEFORM_DESCRIPTOR)
                    data.writeInt(displayId)
                    copy.writeToParcel(data, 0)
                    binder.transact(INJECT_KEY, data, reply, 0)
                    reply.readException()
                } catch (_: RemoteException) {
                    activeBinder = null
                } catch (_: RuntimeException) {
                    // Ignore transient injection errors during teardown.
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun releaseAppDisplay(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        if (currentBinder() == null) return
        transact(
            code = RELEASE_DISPLAY,
            defaultValue = Unit,
            write = { data -> data.writeInt(displayId) },
            read = { Unit },
        )
    }

    override suspend fun releaseAllAppDisplays() {
        if (currentBinder() == null) return
        transact(
            code = RELEASE_ALL_DISPLAYS,
            defaultValue = Unit,
            write = {},
            read = { Unit },
        )
    }

    override fun close() {
        closed = true
        val binder = currentBinder()
        val conn = activeConnection
        activeBinder = null
        activeConnection = null
        if (binder?.isBinderAlive == true) {
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(FREEFORM_DESCRIPTOR)
                    binder.transact(RELEASE_ALL_DISPLAYS, data, reply, 0)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
        }
        inputExecutor.shutdownNow()
        DirectAdbBridge.removeListener(directAdbListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }
}
