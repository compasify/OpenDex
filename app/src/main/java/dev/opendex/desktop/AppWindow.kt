package dev.opendex.desktop

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opendex.R
import dev.opendex.platform.InstalledApp

@Composable
internal fun BoxScope.AppWindow(
    window: AppWindowState,
    app: InstalledApp,
    area: Area,
    active: Boolean,
    resizing: Boolean,
    onActivate: () -> Unit,
    onTransform: (Float, Float, Boolean) -> Unit,
    onTransformEnd: () -> Unit,
    onPlacement: (Placement) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onSurfaceReady: (Surface, Int, Int) -> Long,
    onSurfaceResized: (Int, Int) -> Unit,
    onSurfaceDetached: (Long) -> Unit,
    onInjectMotion: (MotionEvent) -> Unit,
    onInjectKey: (KeyEvent) -> Unit,
) {
    val latestActivate by rememberUpdatedState(onActivate)
    val latestSurfaceReady by rememberUpdatedState(onSurfaceReady)
    val latestSurfaceResized by rememberUpdatedState(onSurfaceResized)
    val latestSurfaceDetached by rememberUpdatedState(onSurfaceDetached)
    val latestInjectMotion by rememberUpdatedState(onInjectMotion)
    val latestInjectKey by rememberUpdatedState(onInjectKey)
    val latestResizing by rememberUpdatedState(resizing)
    val latestActive by rememberUpdatedState(active)

    var settledWidth by remember { mutableIntStateOf(0) }
    var settledHeight by remember { mutableIntStateOf(0) }
    var syncedWidth by remember { mutableIntStateOf(0) }
    var syncedHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(resizing, settledWidth, settledHeight, window.virtualDisplayId) {
        if (!resizing && window.virtualDisplayId != NO_VIRTUAL_DISPLAY &&
            settledWidth > 0 && settledHeight > 0 &&
            (settledWidth != syncedWidth || settledHeight != syncedHeight)
        ) {
            syncedWidth = settledWidth
            syncedHeight = settledHeight
            latestSurfaceResized(settledWidth, settledHeight)
        }
    }

    WindowFrame(
        bounds = window.bounds(area),
        placement = window.placement,
        area = area,
        title = app.label,
        active = active,
        leadingIcon = { AppIcon(app, 16) },
        onActivate = onActivate,
        onTransform = onTransform,
        onTransformEnd = onTransformEnd,
        onPlacement = onPlacement,
        onMinimize = onMinimize,
        onClose = onClose,
        reserveResizeFooter = false,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    HostedAppTextureView(
                        context = ctx,
                        onFocusRequested = { latestActivate() },
                        onMotion = { latestInjectMotion(it) },
                        onKey = { latestInjectKey(it) },
                        isWindowActive = { latestActive },
                        onAvailable = { surface, w, h ->
                            settledWidth = w
                            settledHeight = h
                            syncedWidth = w
                            syncedHeight = h
                            latestSurfaceReady(surface, w, h)
                        },
                        onSizeChanged = { w, h ->
                            settledWidth = w
                            settledHeight = h
                            if (!latestResizing && window.virtualDisplayId != NO_VIRTUAL_DISPLAY) {
                                syncedWidth = w
                                syncedHeight = h
                                latestSurfaceResized(w, h)
                            }
                        },
                        onDestroyed = { generation -> latestSurfaceDetached(generation) },
                    )
                },
                update = { view ->
                    view.updateDisplaySize(syncedWidth, syncedHeight)
                },
            )
            if (window.launching) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(app, 42)
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            stringResource(R.string.hosted_window_loading, app.label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private class HostedAppTextureView(
    context: Context,
    private val onFocusRequested: () -> Unit,
    private val onMotion: (MotionEvent) -> Unit,
    private val onKey: (KeyEvent) -> Unit,
    private val isWindowActive: () -> Boolean,
    private val onAvailable: (Surface, Int, Int) -> Long,
    private val onSizeChanged: (Int, Int) -> Unit,
    private val onDestroyed: (Long) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var boundSurface: Surface? = null
    private var surfaceGeneration: Long = 0L
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isOpaque = true
        surfaceTextureListener = this
    }

    fun updateDisplaySize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            displayWidth = width
            displayHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)
        boundSurface = surface
        surfaceGeneration = onAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        onSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        val gen = surfaceGeneration
        surfaceGeneration = 0L
        onDestroyed(gen)
        boundSurface?.release()
        boundSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onFocusRequested()
            if (!hasFocus()) requestFocus()
        }
        forwardMotion(event, isGeneric = false)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton == MotionEvent.BUTTON_BACK) {
            onFocusRequested()
            val now = SystemClock.uptimeMillis()
            onKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            onKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            return true
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_EXIT -> {
                forwardMotion(event, isGeneric = true)
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isWindowActive()) return super.dispatchKeyEvent(event)
        // Preserve OpenDex global search shortcut (Ctrl+K).
        if (event.keyCode == KeyEvent.KEYCODE_K && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            return super.dispatchKeyEvent(event)
        }
        onKey(event)
        return true
    }

    private fun forwardMotion(event: MotionEvent, isGeneric: Boolean) {
        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)
        val targetW = if (displayWidth > 0) displayWidth else viewW
        val targetH = if (displayHeight > 0) displayHeight else viewH
        val scaleX = targetW.toFloat() / viewW.toFloat()
        val scaleY = targetH.toFloat() / viewH.toFloat()

        val pointerCount = event.pointerCount.coerceAtLeast(1)
        val properties = Array(pointerCount) { index ->
            MotionEvent.PointerProperties().also { props ->
                event.getPointerProperties(index, props)
                if (!isGeneric && props.toolType == MotionEvent.TOOL_TYPE_MOUSE &&
                    (event.buttonState and MotionEvent.BUTTON_SECONDARY) == 0
                ) {
                    props.toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        }
        val coords = Array(pointerCount) { index ->
            MotionEvent.PointerCoords().also { c ->
                event.getPointerCoords(index, c)
                c.x = (c.x * scaleX).coerceIn(0f, (targetW - 1).coerceAtLeast(0).toFloat())
                c.y = (c.y * scaleY).coerceIn(0f, (targetH - 1).coerceAtLeast(0).toFloat())
            }
        }
        val mappedSource = when {
            isGeneric -> InputDevice.SOURCE_MOUSE
            event.source == InputDevice.SOURCE_MOUSE && (event.buttonState and MotionEvent.BUTTON_SECONDARY) == 0 ->
                InputDevice.SOURCE_TOUCHSCREEN
            event.source != InputDevice.SOURCE_UNKNOWN -> event.source
            else -> InputDevice.SOURCE_TOUCHSCREEN
        }
        val mappedButtonState = if (mappedSource == InputDevice.SOURCE_TOUCHSCREEN) 0 else event.buttonState
        val mapped = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            pointerCount,
            properties,
            coords,
            event.metaState,
            mappedButtonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            mappedSource,
            event.flags,
        )
        try {
            onMotion(mapped)
        } finally {
            mapped.recycle()
        }
    }
}
