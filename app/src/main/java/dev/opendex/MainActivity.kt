package dev.opendex

import android.Manifest
import android.app.Application
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.opendex.data.WallpaperStorage
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.WorkspaceStore
import dev.opendex.desktop.AppWindowState
import dev.opendex.desktop.Area
import dev.opendex.desktop.Desktop
import dev.opendex.desktop.DesktopWindow
import dev.opendex.desktop.ExternalInputController
import dev.opendex.desktop.WindowKey
import dev.opendex.platform.AdbWindows
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.NotificationCenterStore
import dev.opendex.platform.SystemTrayController
import dev.opendex.platform.installedApps
import dev.opendex.ui.OpenDexTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var desktop: AndroidDesktop
    private lateinit var model: WorkspaceModel
    private lateinit var systemTray: SystemTrayController
    private var capabilities by mutableStateOf(DesktopCapabilities())
    private var notificationAccessGranted by mutableStateOf(false)
    private var searchRequests by mutableIntStateOf(0)
    private val homeRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importCustomWallpaper(uri)
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        systemTray.onPermissionsChanged()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }
    private val windowLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (desktop.currentDisplayId() != capabilities.currentDisplay) refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        hideSystemBars()
        desktop = AndroidDesktop(this)
        model = ViewModelProvider(this)[WorkspaceModel::class.java]
        systemTray = SystemTrayController(this)
        refresh()
        setContent {
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val apps by model.apps.collectAsStateWithLifecycle()
            val appsLoaded by model.appsLoaded.collectAsStateWithLifecycle()
            val error by model.saveError.collectAsStateWithLifecycle()
            val readError by model.readError.collectAsStateWithLifecycle()
            val noteDraft by model.noteDraft.collectAsStateWithLifecycle()
            val adbAccess by model.adbWindows.access.collectAsStateWithLifecycle()
            val windowOrder by model.windowOrder.collectAsStateWithLifecycle()
            val systemStatus by systemTray.status.collectAsStateWithLifecycle()
            val notifications by NotificationCenterStore.notifications.collectAsStateWithLifecycle()
            val notificationBanners by NotificationCenterStore.banners.collectAsStateWithLifecycle()
            OpenDexTheme(preferences?.theme) {
                Desktop(
                    preferences = preferences,
                    apps = apps,
                    appsLoaded = appsLoaded,
                    caps = capabilities,
                    platform = desktop,
                    model = model,
                    saveError = error,
                    readError = readError,
                    noteDraft = noteDraft,
                    searchRequests = searchRequests,
                    adbWindows = model.adbWindows,
                    adbAccess = adbAccess,
                    windowOrder = windowOrder,
                    systemStatus = systemStatus,
                    systemTray = systemTray,
                    notifications = notifications,
                    notificationBanners = notificationBanners,
                    notificationAccessGranted = notificationAccessGranted,
                    onPickWallpaper = {
                        runCatching { wallpaperPicker.launch(arrayOf("image/*")) }.isSuccess
                    },
                    onRequestLocationPermission = {
                        runCatching { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                    },
                    onBrightnessChange = { fraction -> systemTray.setBrightness(fraction, window) },
                    onMoveToBackground = { moveTaskToBack(true) },
                ) {
                    runCatching { homeRequest.launch(desktop.homeIntent()) }.isSuccess
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        systemTray.start()
        desktop.displayManager.registerDisplayListener(displayListener, null)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(windowLayoutListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        model.adbWindows.refresh()
        systemTray.refresh()
        notificationAccessGranted = NotificationCenterStore.isAccessGranted(this)
        refresh()
        model.refreshApps()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_K && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            searchRequests++
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onStop() {
        model.externalInput.unregisterExternalHost(this)
        systemTray.stop()
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(windowLayoutListener)
        desktop.displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (capabilities.external && ev.deviceId > 0 && ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            model.externalInput.onNativeExternalMouseEvent()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            systemTray.refresh()
            notificationAccessGranted = NotificationCenterStore.isAccessGranted(this)
            refresh()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun refresh() {
        if (::desktop.isInitialized) {
            val next = desktop.inspect()
            capabilities = next
            if (::model.isInitialized) {
                if (next.external) {
                    val decor = window?.decorView
                    val screen = next.screens.firstOrNull { it.id == next.currentDisplay }
                    val w = decor?.width?.takeIf { it > 0 } ?: screen?.width ?: 0
                    val h = decor?.height?.takeIf { it > 0 } ?: screen?.height ?: 0
                    model.externalInput.registerExternalHost(this, next.currentDisplay, w, h)
                } else {
                    model.externalInput.unregisterExternalHost(this)
                }
            }
        }
    }
}

class WorkspaceModel(application: Application) : AndroidViewModel(application) {
    private val runtime = WorkspaceRuntime.get(application)
    private val storage = runtime.storage
    val adbWindows: AdbWindows = runtime.adbWindows
    val externalInput: ExternalInputController = runtime.externalInput
    val windowOrder: StateFlow<List<WindowKey>> = runtime.windowOrder

    private val readFailure = MutableStateFlow(false)
    val readError = readFailure.asStateFlow()
    val preferences = storage.preferences { readFailure.value = it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null as WorkspacePreferences?)
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps = installed.asStateFlow()
    private val loaded = MutableStateFlow(false)
    val appsLoaded = loaded.asStateFlow()
    private val error = MutableStateFlow(false)
    val saveError = error.asStateFlow()
    private var refreshJob: Job? = null
    private val draft = MutableStateFlow<String?>(null)
    val noteDraft = draft.asStateFlow()
    private var noteRevision = 0L

    fun dismissSaveError() { error.value = false }

    fun appWindows(external: Boolean): StateFlow<List<AppWindowState>> = runtime.appWindows(external)

    fun hydrateAppWindows(external: Boolean, serialized: String, restoreEnabled: Boolean) =
        runtime.hydrateIfNeeded(external, serialized, restoreEnabled)

    fun pruneMissingApps(external: Boolean, installedIds: Set<String>) =
        runtime.pruneMissingApps(external, installedIds)

    fun bringToFront(key: WindowKey) = runtime.bringToFront(key)

    fun openAppWindow(external: Boolean, appId: String, area: Area) =
        runtime.openAppWindow(external, appId, area)

    fun openAppWindowWithAction(external: Boolean, appId: String, action: String, area: Area, dataUri: String? = null) =
        runtime.openAppWindowWithAction(external, appId, action, area, dataUri)

    fun activateAppWindow(external: Boolean, appId: String) =
        runtime.activateAppWindow(external, appId)

    fun updateAppWindow(external: Boolean, appId: String, transform: (AppWindowState) -> AppWindowState) =
        runtime.updateAppWindow(external, appId, persist = true, transform = transform)

    fun minimizeAllAppWindows(external: Boolean) = runtime.minimizeAllAppWindows(external)

    fun restoreAppWindows(external: Boolean, appIds: Set<String>? = null) =
        runtime.restoreAppWindows(external, appIds)

    fun closeAppWindow(external: Boolean, appId: String) = runtime.closeAppWindow(external, appId)

    fun closeAllAppWindows(external: Boolean, persist: Boolean = true): Boolean =
        runtime.closeAllAppWindows(external, persist)

    fun attachAppSurface(
        external: Boolean,
        app: InstalledApp,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        onFailure: (InstalledApp) -> Unit,
    ): Long = runtime.attachAppSurface(external, app, surface, width, height, densityDpi, onFailure)

    fun resizeAppSurface(
        external: Boolean,
        appId: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        onComplete: () -> Unit = {},
    ) = runtime.resizeAppSurface(external, appId, width, height, densityDpi, onComplete)

    fun rescaleOpenDisplays(external: Boolean, densityDpi: Int) =
        runtime.rescaleOpenDisplays(external, densityDpi)

    fun detachAppSurface(external: Boolean, appId: String, generation: Long) =
        runtime.detachAppSurface(external, appId, generation)

    fun importCustomWallpaper(uri: Uri) {
        viewModelScope.launch {
            val path = WallpaperStorage.importWallpaper(getApplication(), uri)
            if (path != null) {
                save { customWallpaper(path) }
            } else {
                error.value = true
            }
        }
    }

    fun editNote(value: String) {
        draft.value = value
        val revision = ++noteRevision
        save {
            note(value)
            // Keep the local text until the persisted change reaches the UI collector.
            if (revision == noteRevision) viewModelScope.launch {
                preferences.first { it?.note == value }
                if (revision == noteRevision) draft.value = null
            }
        }
    }

    fun editWindows(external: Boolean, update: (List<DesktopWindow>) -> List<DesktopWindow>) {
        save { updateWindows(external, update) }
    }

    fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            installed.value = withContext(Dispatchers.IO) { getApplication<Application>().installedApps() }
            loaded.value = true
        }
    }

    fun save(action: suspend WorkspaceStore.() -> Unit) {
        viewModelScope.launch {
            try {
                storage.action()
                error.value = false
            } catch (e: IOException) {
                Log.w("OpenDex", "Workspace storage unavailable", e)
                error.value = true
            }
        }
    }
}
