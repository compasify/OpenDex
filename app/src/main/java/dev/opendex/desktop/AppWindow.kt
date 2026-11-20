package dev.opendex.desktop

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opendex.R
import dev.opendex.data.WindowResizeMode
import dev.opendex.platform.InstalledApp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class ResizeSnapshot(
    val bitmap: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
    val expectedResizeSeq: Long,
)

@Composable
internal fun BoxScope.AppWindow(
    window: AppWindowState,
    app: InstalledApp,
    area: Area,
    active: Boolean,
    resizeMode: WindowResizeMode,
    onActivate: () -> Unit,
    onCommitBounds: (Bounds) -> Unit,
    onOutlinePreview: (Bounds?) -> Unit,
    onPlacement: (Placement) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onSurfaceReady: (Surface, Int, Int) -> Long,
    onSurfaceResized: (Int, Int, () -> Unit) -> Unit,
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
    val latestActive by rememberUpdatedState(active)
    val latestResizeMode by rememberUpdatedState(resizeMode)
    val latestCommitBounds by rememberUpdatedState(onCommitBounds)
    val latestOutlinePreview by rememberUpdatedState(onOutlinePreview)
    val latestWindow by rememberUpdatedState(window)

    var liveBounds by remember { mutableStateOf<Bounds?>(null) }
    var outlineBounds by remember { mutableStateOf<Bounds?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var liveResizing by remember { mutableStateOf(false) }
    val latestLiveResizing by rememberUpdatedState(liveResizing)

    var textureViewRef by remember { mutableStateOf<HostedAppTextureView?>(null) }
    var settledWidth by remember { mutableIntStateOf(0) }
    var settledHeight by remember { mutableIntStateOf(0) }
    var syncedWidth by remember { mutableIntStateOf(0) }
    var syncedHeight by remember { mutableIntStateOf(0) }
    var freezeSnapshot by remember { mutableStateOf<ResizeSnapshot?>(null) }
    var snapshotVisible by remember { mutableStateOf(false) }
    val snapshotAlpha by animateFloatAsState(
        targetValue = if (snapshotVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "resizeSnapshotAlpha",
    )
    var resizeRequestSeq by remember { mutableLongStateOf(0L) }
    var resizeDoneSeq by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { latestOutlinePreview(null) }
    }

    // Hold committed bounds locally until the StateFlow collector catches up, avoiding a 1-frame snap-back.
    LaunchedEffect(window.floating, window.placement, area, dragging) {
        if (!dragging && liveBounds != null) {
            val committed = window.bounds(area)
            val held = liveBounds
            if (held != null &&
                abs(committed.x - held.x) < 0.5f &&
                abs(committed.y - held.y) < 0.5f &&
                abs(committed.width - held.width) < 0.5f &&
                abs(committed.height - held.height) < 0.5f
            ) {
                liveBounds = null
            }
        }
    }

    fun requestDisplayResize(w: Int, h: Int) {
        syncedWidth = w
        syncedHeight = h
        textureViewRef?.updateDisplaySize(w, h)
        val seq = ++resizeRequestSeq
        latestSurfaceResized(w, h) {
            if (seq > resizeDoneSeq) resizeDoneSeq = seq
        }
    }

    LaunchedEffect(freezeSnapshot) {
        val snap = freezeSnapshot ?: return@LaunchedEffect
        snapshotVisible = true
        // Wait until VirtualDisplay.resize() (with transition suppressed) completes on the shell side.
        withTimeoutOrNull(320L) {
            snapshotFlow { resizeDoneSeq }.first { it >= snap.expectedResizeSeq }
        }
        delay(50L)
        snapshotVisible = false
        delay(100L)
        if (freezeSnapshot === snap) freezeSnapshot = null
    }

    LaunchedEffect(liveResizing, settledWidth, settledHeight, window.virtualDisplayId) {
        if (!liveResizing && window.virtualDisplayId != NO_VIRTUAL_DISPLAY &&
            settledWidth > 0 && settledHeight > 0 &&
            (settledWidth != syncedWidth || settledHeight != syncedHeight)
        ) {
            requestDisplayResize(settledWidth, settledHeight)
        }
    }

    fun captureFreezeIfResized(targetBounds: Bounds) {
        val current = latestWindow.bounds(area)
        val sizeChanged = abs(targetBounds.width - current.width) > 0.5f ||
            abs(targetBounds.height - current.height) > 0.5f
        if (!sizeChanged || latestWindow.virtualDisplayId == NO_VIRTUAL_DISPLAY) return
        val view = textureViewRef ?: return
        val bmp = view.captureSnapshot() ?: return
        val w = syncedWidth.takeIf { it > 0 } ?: view.width
        val h = syncedHeight.takeIf { it > 0 } ?: view.height
        if (w > 0 && h > 0) {
            snapshotVisible = true
            freezeSnapshot = ResizeSnapshot(bmp, w, h, expectedResizeSeq = resizeRequestSeq + 1L)
        }
    }

    val displayBounds = liveBounds ?: window.bounds(area)
    val displayPlacement = if (liveBounds != null) Placement.Floating else window.placement

    WindowFrame(
        bounds = displayBounds,
        placement = displayPlacement,
        area = area,
        title = app.label,
        active = active,
        leadingIcon = { AppIcon(app, 16) },
        onActivate = {
            if (!dragging && outlineBounds == null) onActivate()
        },
        onTransform = { dx, dy, resize ->
            dragging = true
            if (resize && latestResizeMode == WindowResizeMode.Outline) {
                val base = outlineBounds ?: latestWindow.bounds(area)
                val target = base.resize(dx, dy, area)
                outlineBounds = target
                latestOutlinePreview(target)
            } else {
                val base = liveBounds ?: if (!resize && latestWindow.placement != Placement.Floating) {
                    val restored = latestWindow.floating.fit(area)
                    val current = latestWindow.bounds(area)
                    restored.copy(
                        x = (current.x + (current.width - restored.width) / 2f).coerceIn(0f, area.width - restored.width),
                        y = current.y.coerceIn(0f, area.height - restored.height),
                    )
                } else {
                    latestWindow.bounds(area)
                }
                val target = if (resize) base.resize(dx, dy, area) else base.move(dx, dy, area)
                if (resize) liveResizing = true
                liveBounds = target
            }
        },
        onTransformEnd = {
            dragging = false
            val pendingOutline = outlineBounds
            if (pendingOutline != null) {
                captureFreezeIfResized(pendingOutline)
                outlineBounds = null
                latestOutlinePreview(null)
                // Apply target immediately on the same frame the outline disappears.
                liveBounds = pendingOutline
                latestCommitBounds(pendingOutline)
            }
            val pendingLive = liveBounds
            if (pendingLive != null && pendingOutline == null) {
                if (liveResizing) captureFreezeIfResized(pendingLive)
                liveResizing = false
                latestCommitBounds(pendingLive)
            } else {
                liveResizing = false
            }
        },
        onPlacement = { placement ->
            liveBounds = null
            captureFreezeIfResized(placement.boundsIn(latestWindow.floating, area))
            onPlacement(placement)
        },
        onMinimize = onMinimize,
        onClose = onClose,
        onSnapPreview = { preview -> latestOutlinePreview(preview) },
        reserveResizeFooter = false,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds(),
        ) {
            val keepFixedSurface = liveResizing && syncedWidth > 0 && syncedHeight > 0

            AndroidView(
                modifier = Modifier.layout { measurable, constraints ->
                    val targetW = if (keepFixedSurface) syncedWidth else constraints.maxWidth
                    val targetH = if (keepFixedSurface) syncedHeight else constraints.maxHeight
                    val placeable = measurable.measure(Constraints.fixed(targetW, targetH))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(0, 0)
                    }
                },
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
                            if (!latestLiveResizing && latestWindow.virtualDisplayId != NO_VIRTUAL_DISPLAY &&
                                (w != syncedWidth || h != syncedHeight)
                            ) {
                                requestDisplayResize(w, h)
                            }
                        },
                        onDestroyed = { generation -> latestSurfaceDetached(generation) },
                    ).also { textureViewRef = it }
                },
                update = { view ->
                    if (!liveResizing) {
                        view.updateDisplaySize(syncedWidth, syncedHeight)
                    }
                },
            )

            freezeSnapshot?.let { snap ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(snapshotAlpha)
                        .background(MaterialTheme.colorScheme.surface)
                        .clipToBounds(),
                ) {
                    Image(
                        bitmap = snap.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.None,
                        alignment = Alignment.TopStart,
                        modifier = Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(snap.widthPx, snap.heightPx))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(0, 0)
                            }
                        },
                    )
                }
            }

            if (liveResizing) {
                ResizeDimensionBadge(
                    width = displayBounds.width.roundToInt(),
                    height = displayBounds.height.roundToInt(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 26.dp, bottom = 10.dp),
                )
            }

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

@Composable
internal fun ResizeDimensionBadge(width: Int, height: Int, modifier: Modifier = Modifier) {
    ComposeSurface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
        shadowElevation = 2.dp,
    ) {
        Text(
            text = stringResource(R.string.resize_dimensions, width, height),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
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
        if (width <= 0 || height <= 0) return
        if (width == displayWidth && height == displayHeight) return
        displayWidth = width
        displayHeight = height
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun captureSnapshot(): ImageBitmap? {
        if (!isAvailable || width <= 0 || height <= 0) return null
        return runCatching { getBitmap(width, height)?.asImageBitmap() }.getOrNull()
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
