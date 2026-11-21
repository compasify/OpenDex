package dev.opendex.platform

import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.flow.StateFlow

enum class AdbAccess { NotIncluded, NotRunning, WrongBackend, NeedsPermission, Granted }

interface AdbWindows : AutoCloseable {
    val access: StateFlow<AdbAccess>
    val supportsHostedWindows: Boolean get() = false
    fun refresh()
    fun requestAccess(): Boolean
    suspend fun open(app: InstalledApp, displayId: Int, bounds: IntRect? = null, stillOnDisplay: () -> Boolean): Boolean
    suspend fun createAppDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?): Int = Display.INVALID_DISPLAY
    suspend fun launchOnDisplay(app: InstalledApp, displayId: Int): Boolean = false
    suspend fun launchActionOnDisplay(action: String, displayId: Int, dataUri: String? = null): Boolean = false
    suspend fun resizeAppDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean = false
    suspend fun setAppDisplaySurface(displayId: Int, surface: Surface?): Boolean = false
    suspend fun ensureConnected(): Boolean = false
    suspend fun setSuppressExternalIme(externalDisplayId: Int, suppress: Boolean) = Unit
    fun injectMotion(displayId: Int, event: MotionEvent): Boolean = false
    fun injectKey(displayId: Int, event: KeyEvent): Boolean = false
    suspend fun releaseAppDisplay(displayId: Int) = Unit
    suspend fun releaseAllAppDisplays() = Unit
}
