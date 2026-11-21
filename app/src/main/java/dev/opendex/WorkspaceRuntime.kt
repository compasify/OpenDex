package dev.opendex

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.Surface
import dev.opendex.data.WorkspaceStore
import dev.opendex.desktop.AppWindowState
import dev.opendex.desktop.Area
import dev.opendex.desktop.ExternalInputController
import dev.opendex.desktop.NO_VIRTUAL_DISPLAY
import dev.opendex.desktop.WindowKey
import dev.opendex.desktop.activateApp
import dev.opendex.desktop.decodeAppWindows
import dev.opendex.desktop.minimizeAllApps
import dev.opendex.desktop.openApp
import dev.opendex.desktop.restoreApps
import dev.opendex.platform.AdbWindows
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.createAdbWindows
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped runtime that keeps hosted VirtualDisplay sessions and window Z-order alive
 * across Activity exits/re-entries and synchronizes open app windows with persistent storage.
 */
class WorkspaceRuntime private constructor(appContext: Context) {
    val storage = WorkspaceStore(appContext)
    val adbWindows: AdbWindows = createAdbWindows(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val externalInput = ExternalInputController(adbWindows, scope)
    private val localApps = MutableStateFlow<List<AppWindowState>>(emptyList())
    private val externalApps = MutableStateFlow<List<AppWindowState>>(emptyList())
    private val zOrder = MutableStateFlow<List<WindowKey>>(emptyList())
    val windowOrder: StateFlow<List<WindowKey>> = zOrder.asStateFlow()

    private val hydratedScopes = mutableSetOf<Boolean>()
    private val appLocks = ConcurrentHashMap<String, Mutex>()
    private val generationCounter = AtomicLong(0L)
    private val activeSurfaceGen = ConcurrentHashMap<String, Long>()
    private val lastSurfaceSize = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val pendingResizeTarget = ConcurrentHashMap<String, Triple<Int, Int, Int>>()
    private val pendingLaunchActions = ConcurrentHashMap<String, Pair<String, String?>>()

    private fun flowFor(external: Boolean): MutableStateFlow<List<AppWindowState>> =
        if (external) externalApps else localApps

    private fun lockFor(appId: String): Mutex = appLocks.computeIfAbsent(appId) { Mutex() }

    fun appWindows(external: Boolean): StateFlow<List<AppWindowState>> = flowFor(external).asStateFlow()

    fun hydrateIfNeeded(external: Boolean, serialized: String, restoreEnabled: Boolean) {
        if (!hydratedScopes.add(external)) return
        if (!restoreEnabled || serialized.isBlank()) return
        val flow = flowFor(external)
        if (flow.value.isNotEmpty()) return
        val restored = decodeAppWindows(serialized)
        if (restored.isEmpty()) return
        flow.value = restored
        zOrder.update { current ->
            val existing = current.toSet()
            current + restored.map { WindowKey.AppKey(it.appId) }.filterNot { it in existing }
        }
    }

    fun pruneMissingApps(external: Boolean, installedIds: Set<String>) {
        if (installedIds.isEmpty()) return
        val flow = flowFor(external)
        val current = flow.value
        val stale = current.filter { it.appId !in installedIds && it.virtualDisplayId == NO_VIRTUAL_DISPLAY }
        if (stale.isEmpty()) return
        val kept = current.filterNot { it in stale }
        flow.value = kept
        val staleKeys = stale.map { WindowKey.AppKey(it.appId) }.toSet()
        zOrder.update { list -> list.filterNot { it in staleKeys } }
        persistAppWindows(external, kept)
    }

    fun bringToFront(key: WindowKey) {
        zOrder.update { current ->
            if (current.lastOrNull() == key) current else current.filterNot { it == key } + key
        }
    }

    fun openAppWindow(external: Boolean, appId: String, area: Area) {
        val flow = flowFor(external)
        val updated = flow.value.openApp(appId, area)
        flow.value = updated
        bringToFront(WindowKey.AppKey(appId))
        persistAppWindows(external, updated)
    }

    fun openAppWindowWithAction(external: Boolean, appId: String, action: String, area: Area, dataUri: String? = null) {
        pendingLaunchActions[appId] = action to dataUri
        val flow = flowFor(external)
        val existingDisplayId = flow.value.firstOrNull { it.appId == appId }?.virtualDisplayId ?: NO_VIRTUAL_DISPLAY
        val updated = flow.value.openApp(appId, area)
        flow.value = updated
        bringToFront(WindowKey.AppKey(appId))
        persistAppWindows(external, updated)
        if (existingDisplayId != NO_VIRTUAL_DISPLAY) {
            scope.launch {
                lockFor(appId).withLock {
                    val pending = pendingLaunchActions.remove(appId) ?: return@withLock
                    val liveDisplayId = flowFor(external).value.firstOrNull { it.appId == appId }?.virtualDisplayId
                        ?: NO_VIRTUAL_DISPLAY
                    if (liveDisplayId != NO_VIRTUAL_DISPLAY) {
                        adbWindows.launchActionOnDisplay(pending.first, liveDisplayId, pending.second)
                    } else {
                        pendingLaunchActions[appId] = pending
                    }
                }
            }
        }
    }

    fun activateAppWindow(external: Boolean, appId: String) {
        val flow = flowFor(external)
        val updated = flow.value.activateApp(appId)
        flow.value = updated
        bringToFront(WindowKey.AppKey(appId))
        persistAppWindows(external, updated)
    }

    fun updateAppWindow(
        external: Boolean,
        appId: String,
        persist: Boolean = true,
        transform: (AppWindowState) -> AppWindowState,
    ) {
        val flow = flowFor(external)
        var changed = false
        val updated = flow.value.map {
            if (it.appId == appId) {
                val next = transform(it)
                if (next != it) changed = true
                next
            } else it
        }
        if (!changed) return
        flow.value = updated
        if (persist) persistAppWindows(external, updated)
    }

    fun minimizeAllAppWindows(external: Boolean) {
        val flow = flowFor(external)
        if (flow.value.none { !it.minimized }) return
        val updated = flow.value.minimizeAllApps()
        flow.value = updated
        persistAppWindows(external, updated)
    }

    fun restoreAppWindows(external: Boolean, appIds: Set<String>? = null) {
        val flow = flowFor(external)
        val updated = flow.value.restoreApps(appIds)
        if (updated == flow.value) return
        flow.value = updated
        persistAppWindows(external, updated)
    }

    fun closeAppWindow(external: Boolean, appId: String) {
        val flow = flowFor(external)
        val removed = flow.value.firstOrNull { it.appId == appId } ?: return
        val updated = flow.value.filterNot { it.appId == appId }
        flow.value = updated
        zOrder.update { list -> list.filterNot { it == WindowKey.AppKey(appId) } }
        activeSurfaceGen.remove(appId)
        lastSurfaceSize.remove(appId)
        pendingResizeTarget.remove(appId)
        pendingLaunchActions.remove(appId)
        persistAppWindows(external, updated)
        val displayId = removed.virtualDisplayId
        if (displayId != NO_VIRTUAL_DISPLAY) {
            scope.launch {
                lockFor(appId).withLock {
                    adbWindows.releaseAppDisplay(displayId)
                }
            }
        }
    }

    fun closeAllAppWindows(external: Boolean, persist: Boolean = true): Boolean {
        val flow = flowFor(external)
        val current = flow.value
        if (current.isEmpty()) return false
        flow.value = emptyList()
        val removedKeys = current.map { WindowKey.AppKey(it.appId) }.toSet()
        zOrder.update { list -> list.filterNot { it in removedKeys } }
        current.forEach {
            activeSurfaceGen.remove(it.appId)
            lastSurfaceSize.remove(it.appId)
            pendingResizeTarget.remove(it.appId)
            pendingLaunchActions.remove(it.appId)
        }
        if (persist) persistAppWindows(external, emptyList())
        scope.launch {
            current.forEach { win ->
                if (win.virtualDisplayId != NO_VIRTUAL_DISPLAY) {
                    lockFor(win.appId).withLock {
                        adbWindows.releaseAppDisplay(win.virtualDisplayId)
                    }
                }
            }
        }
        return true
    }

    fun disconnectExternalDisplay(displayId: Int) {
        closeAllAppWindows(external = true, persist = false)
        externalInput.closeExternalHost(displayId)
    }

    fun attachAppSurface(
        external: Boolean,
        app: InstalledApp,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        onFailure: (InstalledApp) -> Unit,
    ): Long {
        val gen = generationCounter.incrementAndGet()
        activeSurfaceGen[app.id] = gen
        lastSurfaceSize[app.id] = width to height
        scope.launch {
            lockFor(app.id).withLock {
                if (activeSurfaceGen[app.id] != gen) return@withLock
                val flow = flowFor(external)
                val current = flow.value.firstOrNull { it.appId == app.id } ?: return@withLock
                val existingDisplayId = current.virtualDisplayId
                if (existingDisplayId != NO_VIRTUAL_DISPLAY) {
                    val reattached = adbWindows.setAppDisplaySurface(existingDisplayId, surface)
                    if (reattached) {
                        adbWindows.resizeAppDisplay(existingDisplayId, width, height, densityDpi)
                        pendingLaunchActions.remove(app.id)?.let { (pendingAction, pendingDataUri) ->
                            adbWindows.launchActionOnDisplay(pendingAction, existingDisplayId, pendingDataUri)
                        }
                        updateAppWindow(external, app.id, persist = false) { it.copy(launching = false) }
                        return@withLock
                    }
                    // Virtual display no longer exists (e.g. Shizuku restarted): transparently recreate.
                    updateAppWindow(external, app.id, persist = false) {
                        it.copy(virtualDisplayId = NO_VIRTUAL_DISPLAY, launching = true)
                    }
                }
                val createdId = adbWindows.createAppDisplay(width, height, densityDpi, surface)
                if (createdId == Display.INVALID_DISPLAY) {
                    closeAppWindow(external, app.id)
                    onFailure(app)
                    return@withLock
                }
                if (flow.value.none { it.appId == app.id }) {
                    adbWindows.releaseAppDisplay(createdId)
                    return@withLock
                }
                updateAppWindow(external, app.id, persist = false) { it.copy(virtualDisplayId = createdId) }
                if (activeSurfaceGen[app.id] != gen) {
                    adbWindows.setAppDisplaySurface(createdId, null)
                }
                val pendingAction = pendingLaunchActions.remove(app.id)
                val launched = if (pendingAction != null) {
                    adbWindows.launchActionOnDisplay(pendingAction.first, createdId, pendingAction.second) ||
                        adbWindows.launchOnDisplay(app, createdId)
                } else {
                    adbWindows.launchOnDisplay(app, createdId)
                }
                if (!launched || flow.value.none { it.appId == app.id }) {
                    closeAppWindow(external, app.id)
                    if (!launched) onFailure(app)
                    return@withLock
                }
                updateAppWindow(external, app.id, persist = false) { it.copy(launching = false) }
            }
        }
        return gen
    }

    fun resizeAppSurface(
        external: Boolean,
        appId: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        onComplete: () -> Unit = {},
    ) {
        val target = Triple(width, height, densityDpi)
        lastSurfaceSize[appId] = width to height
        pendingResizeTarget[appId] = target
        scope.launch {
            try {
                lockFor(appId).withLock {
                    // Coalesce rapid resizes so stale intermediate sizes never queue up behind the shell lock.
                    if (pendingResizeTarget[appId] != target) return@withLock
                    val displayId = flowFor(external).value.firstOrNull { it.appId == appId }?.virtualDisplayId
                        ?: return@withLock
                    if (displayId == NO_VIRTUAL_DISPLAY) return@withLock
                    adbWindows.resizeAppDisplay(displayId, width, height, densityDpi)
                }
            } finally {
                onComplete()
            }
        }
    }

    fun rescaleOpenDisplays(external: Boolean, densityDpi: Int) {
        val snapshot = flowFor(external).value
        snapshot.forEach { win ->
            if (win.virtualDisplayId != NO_VIRTUAL_DISPLAY) {
                val size = lastSurfaceSize[win.appId] ?: return@forEach
                resizeAppSurface(external, win.appId, size.first, size.second, densityDpi)
            }
        }
    }

    fun detachAppSurface(external: Boolean, appId: String, generation: Long) {
        if (generation == 0L) return
        val detached = activeSurfaceGen.replace(appId, generation, -generation)
        if (!detached) return
        scope.launch {
            lockFor(appId).withLock {
                if (activeSurfaceGen[appId] != -generation) return@withLock
                val displayId = flowFor(external).value.firstOrNull { it.appId == appId }?.virtualDisplayId
                    ?: return@withLock
                if (displayId == NO_VIRTUAL_DISPLAY) return@withLock
                adbWindows.setAppDisplaySurface(displayId, null)
            }
        }
    }

    private fun persistAppWindows(external: Boolean, windows: List<AppWindowState>) {
        scope.launch {
            try {
                storage.saveAppWindows(external, windows)
            } catch (e: IOException) {
                Log.w("OpenDex", "Failed to persist open app windows", e)
            }
        }
    }

    companion object {
        @Volatile private var instance: WorkspaceRuntime? = null

        fun get(context: Context): WorkspaceRuntime =
            instance ?: synchronized(this) {
                instance ?: WorkspaceRuntime(context.applicationContext).also { instance = it }
            }
    }
}
