package dev.opendex.desktop

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import dev.opendex.platform.AdbAccess
import dev.opendex.platform.AdbWindows
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExternalPointerState(
    val visible: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val pressed: Boolean = false,
    val targetDisplayId: Int = Display.INVALID_DISPLAY,
)

/**
 * Coordinates virtual pointer + keyboard input from the built-in display (touchpad, soft keyboard,
 * or captured physical mouse/keyboard) to an active external display.
 *
 * When Shizuku/ADB injection is available, events are injected into Android's InputDispatcher for
 * the target external display (working across OpenDex, Compose popups/menus, hosted app windows,
 * and fullscreen apps). Otherwise, events fall back to in-process dispatch on the external OpenDex
 * Activity window so touchpad & mouse forwarding still works without Shizuku.
 */
class ExternalInputController(
    private val adbWindows: AdbWindows,
    private val scope: CoroutineScope,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyCharMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    private val _pointerState = MutableStateFlow(ExternalPointerState())
    val pointerState: StateFlow<ExternalPointerState> = _pointerState.asStateFlow()

    @Volatile private var hostRef: WeakReference<Activity>? = null
    @Volatile private var hostDisplayId: Int = Display.INVALID_DISPLAY
    @Volatile private var targetDisplayId: Int = Display.INVALID_DISPLAY
    @Volatile private var targetWidthPx: Int = 1920
    @Volatile private var targetHeightPx: Int = 1080
    @Volatile private var primaryDownTime: Long = 0L
    @Volatile private var primaryPressed: Boolean = false

    fun registerExternalHost(activity: Activity, displayId: Int, widthPx: Int, heightPx: Int) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return
        hostRef = WeakReference(activity)
        hostDisplayId = displayId
        if (widthPx > 0 && heightPx > 0 && (targetDisplayId == displayId || targetDisplayId == Display.INVALID_DISPLAY)) {
            targetWidthPx = widthPx
            targetHeightPx = heightPx
            clampPointerToBounds()
        }
    }

    fun updateExternalSize(displayId: Int, widthPx: Int, heightPx: Int) {
        if (displayId != hostDisplayId && displayId != targetDisplayId) return
        if (widthPx <= 0 || heightPx <= 0) return
        targetWidthPx = widthPx
        targetHeightPx = heightPx
        clampPointerToBounds()
    }

    fun unregisterExternalHost(activity: Activity) {
        if (hostRef?.get() === activity) {
            hostRef = null
            hostDisplayId = Display.INVALID_DISPLAY
        }
    }

    fun activateTarget(displayId: Int, widthPx: Int, heightPx: Int) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) return
        val switchingDisplay = targetDisplayId != displayId
        targetDisplayId = displayId
        if (widthPx > 0 && heightPx > 0) {
            targetWidthPx = widthPx
            targetHeightPx = heightPx
        }
        val current = _pointerState.value
        val startX = if (switchingDisplay || current.targetDisplayId != displayId) {
            targetWidthPx / 2f
        } else {
            current.x.coerceIn(0f, maxX())
        }
        val startY = if (switchingDisplay || current.targetDisplayId != displayId) {
            targetHeightPx / 2f
        } else {
            current.y.coerceIn(0f, maxY())
        }
        _pointerState.value = ExternalPointerState(
            visible = true,
            x = startX,
            y = startY,
            pressed = primaryPressed,
            targetDisplayId = displayId,
        )
        if (adbWindows.access.value == AdbAccess.Granted) {
            scope.launch { adbWindows.ensureConnected() }
        }
    }

    fun hidePointer() {
        if (primaryPressed) primaryUp()
        val current = _pointerState.value
        if (current.visible || current.pressed) {
            _pointerState.value = current.copy(visible = false, pressed = false)
        }
    }

    /** Hide the virtual cursor if a real hardware mouse is moving natively on the external display. */
    fun onNativeExternalMouseEvent() {
        val current = _pointerState.value
        if (current.visible && !primaryPressed) {
            _pointerState.value = current.copy(visible = false)
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val current = _pointerState.value
        val nextX = (current.x + dx).coerceIn(0f, maxX())
        val nextY = (current.y + dy).coerceIn(0f, maxY())
        _pointerState.value = current.copy(
            visible = true,
            x = nextX,
            y = nextY,
            targetDisplayId = displayId,
        )
        val now = SystemClock.uptimeMillis()
        if (primaryPressed) {
            dispatchMotion(
                displayId = displayId,
                downTime = primaryDownTime,
                eventTime = now,
                action = MotionEvent.ACTION_MOVE,
                x = nextX,
                y = nextY,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                pressure = 1f,
                isGeneric = false,
            )
        } else {
            dispatchMotion(
                displayId = displayId,
                downTime = now,
                eventTime = now,
                action = MotionEvent.ACTION_HOVER_MOVE,
                x = nextX,
                y = nextY,
                buttonState = 0,
                pressure = 0f,
                isGeneric = true,
            )
        }
    }

    fun primaryDown() {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        if (primaryPressed) return
        val now = SystemClock.uptimeMillis()
        primaryDownTime = now
        primaryPressed = true
        val state = _pointerState.value
        _pointerState.value = state.copy(visible = true, pressed = true, targetDisplayId = displayId)
        dispatchMotion(
            displayId = displayId,
            downTime = now,
            eventTime = now,
            action = MotionEvent.ACTION_DOWN,
            x = state.x,
            y = state.y,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            pressure = 1f,
            isGeneric = false,
        )
    }

    fun primaryUp() {
        val displayId = targetDisplayId
        if (!primaryPressed) return
        primaryPressed = false
        val state = _pointerState.value
        _pointerState.value = state.copy(pressed = false)
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val now = SystemClock.uptimeMillis()
        dispatchMotion(
            displayId = displayId,
            downTime = primaryDownTime,
            eventTime = now,
            action = MotionEvent.ACTION_UP,
            x = state.x,
            y = state.y,
            buttonState = 0,
            pressure = 0f,
            isGeneric = false,
        )
    }

    fun primaryClick() {
        primaryDown()
        primaryUp()
    }

    fun secondaryClick() {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val now = SystemClock.uptimeMillis()
        val state = _pointerState.value
        _pointerState.value = state.copy(visible = true, targetDisplayId = displayId)
        dispatchMotion(
            displayId = displayId,
            downTime = now,
            eventTime = now,
            action = MotionEvent.ACTION_DOWN,
            x = state.x,
            y = state.y,
            buttonState = MotionEvent.BUTTON_SECONDARY,
            pressure = 1f,
            isGeneric = false,
        )
        dispatchMotion(
            displayId = displayId,
            downTime = now,
            eventTime = now + 10L,
            action = MotionEvent.ACTION_UP,
            x = state.x,
            y = state.y,
            buttonState = 0,
            pressure = 0f,
            isGeneric = false,
        )
    }

    fun scroll(dx: Float, dy: Float) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        if (dx == 0f && dy == 0f) return
        val now = SystemClock.uptimeMillis()
        val state = _pointerState.value
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = state.x
                y = state.y
                setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
                setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
            },
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL,
            1, props, coords,
            0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            deliverMotion(displayId, event, isGeneric = true)
        } finally {
            event.recycle()
        }
    }

    fun sendKeyEvent(event: KeyEvent) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        deliverKey(displayId, event)
    }

    fun sendKeyCode(keyCode: Int, metaState: Int = 0) {
        val displayId = targetDisplayId
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent(
            now, now + 10L, KeyEvent.ACTION_UP, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        deliverKey(displayId, down)
        deliverKey(displayId, up)
    }

    fun sendText(text: String) {
        val displayId = targetDisplayId
        if (text.isEmpty() || displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val events = keyCharMap.getEvents(text.toCharArray())
        if (events != null) {
            events.forEach { deliverKey(displayId, it) }
            return
        }
        // Handle mixed ASCII and Unicode (e.g. Vietnamese diacritics) cleanly.
        val unicodeChunk = StringBuilder()
        fun flushUnicode() {
            if (unicodeChunk.isEmpty()) return
            @Suppress("DEPRECATION") // KeyEvent(time, characters, ...) is still the standard way to inject Unicode text.
            val multi = KeyEvent(SystemClock.uptimeMillis(), unicodeChunk.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
            deliverKey(displayId, multi)
            unicodeChunk.clear()
        }
        for (ch in text) {
            val singleEvents = keyCharMap.getEvents(charArrayOf(ch))
            if (singleEvents != null) {
                flushUnicode()
                singleEvents.forEach { deliverKey(displayId, it) }
            } else {
                unicodeChunk.append(ch)
            }
        }
        flushUnicode()
    }

    private fun dispatchMotion(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        pressure: Float,
        isGeneric: Boolean,
    ) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                this.size = 1f
            },
        )
        val event = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, buttonState, 1f, 1f,
            0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            deliverMotion(displayId, event, isGeneric)
        } finally {
            event.recycle()
        }
    }

    private fun deliverMotion(displayId: Int, event: MotionEvent, isGeneric: Boolean) {
        if (adbWindows.injectMotion(displayId, event)) return
        val host = hostRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && hostDisplayId == displayId } ?: return
        val decor = host.window?.decorView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (isGeneric) decor.dispatchGenericMotionEvent(event) else decor.dispatchTouchEvent(event)
        } else {
            val copy = MotionEvent.obtain(event)
            mainHandler.post {
                try {
                    if (isGeneric) decor.dispatchGenericMotionEvent(copy) else decor.dispatchTouchEvent(copy)
                } finally {
                    copy.recycle()
                }
            }
        }
    }

    private fun deliverKey(displayId: Int, event: KeyEvent) {
        if (adbWindows.injectKey(displayId, event)) return
        val host = hostRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && hostDisplayId == displayId } ?: return
        val decor = host.window?.decorView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            decor.dispatchKeyEvent(event)
        } else {
            val copy = KeyEvent(event)
            mainHandler.post { decor.dispatchKeyEvent(copy) }
        }
    }

    private fun clampPointerToBounds() {
        val current = _pointerState.value
        val clampedX = current.x.coerceIn(0f, maxX())
        val clampedY = current.y.coerceIn(0f, maxY())
        if (clampedX != current.x || clampedY != current.y) {
            _pointerState.value = current.copy(x = clampedX, y = clampedY)
        }
    }

    private fun maxX(): Float = (targetWidthPx - 1).coerceAtLeast(0).toFloat()
    private fun maxY(): Float = (targetHeightPx - 1).coerceAtLeast(0).toFloat()
}
