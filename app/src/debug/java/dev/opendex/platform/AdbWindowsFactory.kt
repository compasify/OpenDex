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
import java.util.concurrent.Executors
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
    @Volatile private var activeBinder: IBinder? = null
    @Volatile private var activeConnection: ServiceConnection? = null
    @Volatile private var closed = false

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, FreeformShellService::class.java))
        .tag("opendex-freeform-${Process.myUid()}")
        .processNameSuffix("freeform_shell")
        .daemon(false)
        .version(3)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        activeBinder = null
        activeConnection = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun refresh() { status.value = detect() }

    private fun detect(): AdbAccess = try {
        when {
            !Shizuku.pingBinder() -> AdbAccess.NotRunning
            Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() != SHELL_UID -> AdbAccess.WrongBackend
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> AdbAccess.NeedsPermission
            else -> AdbAccess.Granted
        }
    } catch (_: RuntimeException) { AdbAccess.NotRunning }

    override fun requestAccess(): Boolean = try {
        if (detect() != AdbAccess.NeedsPermission || Shizuku.shouldShowRequestPermissionRationale()) false
        else { Shizuku.requestPermission(501); true }
    } catch (_: RuntimeException) { false }

    private suspend fun ensureBinder(): IBinder? = serviceLock.withLock {
        if (closed || detect() != AdbAccess.Granted) return@withLock null
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

    override fun injectMotion(displayId: Int, event: MotionEvent) {
        if (closed || displayId == Display.INVALID_DISPLAY) return
        val binder = activeBinder?.takeIf { it.isBinderAlive } ?: return
        val copy = MotionEvent.obtain(event)
        try {
            inputExecutor.execute {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(FREEFORM_DESCRIPTOR)
                    data.writeInt(displayId)
                    copy.writeToParcel(data, 0)
                    binder.transact(INJECT_MOTION, data, reply, 0)
                    reply.readException()
                } catch (_: RemoteException) {
                    activeBinder = null
                } catch (_: RuntimeException) {
                    // Ignore transient injection errors during teardown.
                } finally {
                    reply.recycle()
                    data.recycle()
                    copy.recycle()
                }
            }
        } catch (_: RuntimeException) {
            copy.recycle()
        }
    }

    override fun injectKey(displayId: Int, event: KeyEvent) {
        if (closed || displayId == Display.INVALID_DISPLAY) return
        val binder = activeBinder?.takeIf { it.isBinderAlive } ?: return
        val copy = KeyEvent(event)
        runCatching {
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
        }
    }

    override suspend fun releaseAppDisplay(displayId: Int) {
        if (displayId == Display.INVALID_DISPLAY) return
        if (activeBinder?.isBinderAlive != true) return
        transact(
            code = RELEASE_DISPLAY,
            defaultValue = Unit,
            write = { data -> data.writeInt(displayId) },
            read = { Unit },
        )
    }

    override suspend fun releaseAllAppDisplays() {
        if (activeBinder?.isBinderAlive != true) return
        transact(
            code = RELEASE_ALL_DISPLAYS,
            defaultValue = Unit,
            write = {},
            read = { Unit },
        )
    }

    override fun close() {
        closed = true
        val binder = activeBinder
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
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }
}
