package dev.opendex.platform

import android.content.Context
import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("UNUSED_PARAMETER")
fun createAdbWindows(context: Context): AdbWindows = object : AdbWindows {
    override val access = MutableStateFlow(AdbAccess.NotIncluded)
    override fun refresh() = Unit
    override fun requestAccess() = false
    override suspend fun open(app: InstalledApp, displayId: Int, bounds: IntRect?, stillOnDisplay: () -> Boolean) = false
    override fun close() = Unit
}
