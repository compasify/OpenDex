package dev.opendex

import android.app.Application
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.opendex.data.WorkspacePreferences
import dev.opendex.data.WorkspaceStore
import dev.opendex.desktop.Desktop
import dev.opendex.desktop.DesktopWindow
import dev.opendex.platform.AndroidDesktop
import dev.opendex.platform.DesktopCapabilities
import dev.opendex.platform.InstalledApp
import dev.opendex.platform.installedApps
import dev.opendex.ui.OpenDexTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var desktop: AndroidDesktop
    private lateinit var model: WorkspaceModel
    private var capabilities by mutableStateOf(DesktopCapabilities())
    private var searchRequests by mutableStateOf(0)
    private val homeRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
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
        desktop = AndroidDesktop(this)
        model = ViewModelProvider(this)[WorkspaceModel::class.java]
        refresh()
        setContent {
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val apps by model.apps.collectAsStateWithLifecycle()
            val error by model.saveError.collectAsStateWithLifecycle()
            val readError by model.readError.collectAsStateWithLifecycle()
            val noteDraft by model.noteDraft.collectAsStateWithLifecycle()
            OpenDexTheme(preferences?.theme) {
                Desktop(preferences, apps, capabilities, desktop, model, error, readError, noteDraft, searchRequests) {
                    runCatching { homeRequest.launch(desktop.homeIntent()) }.isSuccess
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        desktop.displayManager.registerDisplayListener(displayListener, null)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(windowLayoutListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        model.refreshApps()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_K && event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) searchRequests++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(windowLayoutListener)
        desktop.displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun refresh() { if (::desktop.isInitialized) capabilities = desktop.inspect() }
}

class WorkspaceModel(application: Application) : AndroidViewModel(application) {
    private val storage = WorkspaceStore(application)
    private val readFailure = MutableStateFlow(false)
    val readError = readFailure.asStateFlow()
    val preferences = storage.preferences { readFailure.value = it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null as WorkspacePreferences?)
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps = installed.asStateFlow()
    private val error = MutableStateFlow(false)
    val saveError = error.asStateFlow()
    private var refreshJob: Job? = null
    private val draft = MutableStateFlow<String?>(null)
    val noteDraft = draft.asStateFlow()
    private var noteRevision = 0L

    fun dismissSaveError() { error.value = false }

    fun editNote(value: String) {
        draft.value = value
        val revision = ++noteRevision
        save {
            note(value)
            // Keep the local text until the persisted change reaches the UI collector.
            if (revision == noteRevision) viewModelScope.launch {
                kotlinx.coroutines.flow.first(preferences) { it?.note == value }
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
