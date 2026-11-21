package dev.opendex

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.opendex.desktop.Desktop
import dev.opendex.desktop.ExternalDisplayHost
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.NotificationCenterStore
import dev.opendex.platform.SystemTrayController
import dev.opendex.ui.OpenDexTheme
import java.util.concurrent.atomic.AtomicBoolean

private class PresentationOwners :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val destroyed = AtomicBoolean(false)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val onBackPressedDispatcher = OnBackPressedDispatcher {}

    fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
    }

    fun onStart() {
        if (destroyed.get()) return
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            onCreate()
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun onDestroy() {
        if (!destroyed.compareAndSet(false, true)) return
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }
}

/**
 * Fallback secondary-display workspace rendered via Android's [Presentation] window when launching a
 * separate Activity onto an external display is blocked by an OEM desktop (such as Samsung DeX) or
 * unavailable on presentation-only displays (such as screen mirroring / Miracast).
 */
class ExternalDesktopPresentation(
    hostActivity: Activity,
    display: Display,
    private val model: WorkspaceModel,
    private val systemTray: SystemTrayController,
    private val searchRequests: Int,
    private val onPickWallpaper: () -> Boolean,
    private val onRequestLocationPermission: () -> Unit,
    private val onNotificationAccessCheck: () -> Boolean,
    private val changeHome: () -> Boolean,
    private val onDismissed: (Int) -> Unit,
) : Presentation(hostActivity, display, android.R.style.Theme_Material_NoActionBar) {

    val targetDisplayId: Int = display.displayId

    private val owners = PresentationOwners()
    private val desktop = AndroidDesktop(hostActivity, fixedDisplayId = targetDisplayId)
    private var capabilities by mutableStateOf(desktop.inspect())
    private val cleanedUp = AtomicBoolean(false)

    private val hostAdapter = object : ExternalDisplayHost {
        override val displayId: Int = targetDisplayId
        override val isAlive: Boolean get() = isShowing && !cleanedUp.get()
        override val isPresentation: Boolean = true
        override fun decorView(): View? = window?.decorView
        override fun closeHost() {
            if (isShowing) dismiss()
        }
        override fun onBackPressed() {
            this@ExternalDesktopPresentation.onBackPressed()
        }
    }

    fun refreshCapabilities() {
        capabilities = desktop.inspect()
    }

    internal fun release() = cleanup()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            )
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }

        owners.onCreate()
        window?.decorView?.let(::attachOwners)

        val composeView = ComposeView(context).apply {
            attachOwners(this)
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
                        notificationAccessGranted = onNotificationAccessCheck(),
                        onPickWallpaper = onPickWallpaper,
                        onRequestLocationPermission = onRequestLocationPermission,
                        onBrightnessChange = {},
                        onHideSystemBars = ::hideSystemBars,
                        onKeepScreenOn = ::setKeepScreenOn,
                        onDimScreen = {},
                        onMoveToBackground = {},
                        changeHome = changeHome,
                    )
                }
            }
        }
        setContentView(composeView)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        owners.onStart()
        hideSystemBars()
        refreshCapabilities()
        val decor = window?.decorView
        val w = decor?.width?.takeIf { it > 0 } ?: display.mode.physicalWidth
        val h = decor?.height?.takeIf { it > 0 } ?: display.mode.physicalHeight
        model.externalInput.registerExternalHost(hostAdapter, w, h)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            refreshCapabilities()
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.deviceId > 0 && ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            model.externalInput.onNativeExternalMouseEvent()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (owners.onBackPressedDispatcher.hasEnabledCallbacks()) {
            owners.onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDisplayRemoved() {
        cleanup()
        super.onDisplayRemoved()
    }

    override fun onStop() {
        cleanup()
        super.onStop()
    }

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(owners)
        view.setViewTreeViewModelStoreOwner(owners)
        view.setViewTreeSavedStateRegistryOwner(owners)
        view.setViewTreeOnBackPressedDispatcherOwner(owners)
    }

    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        model.externalInput.unregisterExternalHost(hostAdapter)
        owners.onDestroy()
        onDismissed(targetDisplayId)
    }

    private fun hideSystemBars() {
        val win = window ?: return
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setKeepScreenOn(keepOn: Boolean) {
        val win = window ?: return
        if (keepOn) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
