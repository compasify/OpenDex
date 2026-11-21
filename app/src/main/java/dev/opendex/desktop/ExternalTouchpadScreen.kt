package dev.opendex.desktop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Build
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opendex.R
import dev.opendex.platform.ScreenInfo
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val SPEED_PRESETS = floatArrayOf(0.6f, 1.0f, 1.5f, 2.2f)

private fun hasExternalMouseConnected(): Boolean =
    InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        !device.isVirtual && (device.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }

private fun hideSystemIme(context: Context, view: View) {
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
    var ctx: Context? = context
    while (ctx is ContextWrapper && ctx !is Activity) {
        ctx = ctx.baseContext
    }
    (ctx as? Activity)?.window?.let { window ->
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }
}

@Composable
internal fun ExternalTouchpadScreen(
    screen: ScreenInfo,
    controller: ExternalInputController,
    pointerSpeed: Float,
    onPointerSpeedChange: (Float) -> Unit,
    onDimScreen: (Boolean) -> Unit = {},
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var liveSpeed by remember(pointerSpeed) { mutableFloatStateOf(pointerSpeed.coerceIn(0.4f, 3.0f)) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var blackoutMode by remember { mutableStateOf(false) }
    var pointerCaptured by remember { mutableStateOf(false) }
    var mouseDetected by remember { mutableStateOf(hasExternalMouseConnected()) }
    var hasInteracted by remember { mutableStateOf(false) }
    var touchpadViewRef by remember { mutableStateOf<ExternalTouchpadView?>(null) }
    var keyboardEditRef by remember { mutableStateOf<RemoteKeyboardEditText?>(null) }

    LaunchedEffect(screen.id, screen.width, screen.height) {
        controller.activateTarget(screen.id, screen.width, screen.height)
    }

    // Automatically capture physical mouse when entering Touchpad Mode if a mouse is attached.
    LaunchedEffect(touchpadViewRef, mouseDetected) {
        val view = touchpadViewRef ?: return@LaunchedEffect
        if (mouseDetected && !keyboardOpen) {
            view.post {
                if (view.hasWindowFocus() && !view.hasPointerCapture()) {
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }
        }
    }

    LaunchedEffect(blackoutMode) {
        onDimScreen(blackoutMode)
    }

    DisposableEffect(Unit) {
        onDispose {
            onDimScreen(false)
            controller.setSuppressSystemIme(false)
            touchpadViewRef?.let { view ->
                if (view.hasPointerCapture()) view.releasePointerCapture()
            }
            controller.hidePointer()
        }
    }

    BackHandler {
        if (blackoutMode) {
            blackoutMode = false
        } else if (keyboardOpen) {
            keyboardOpen = false
            keyboardEditRef?.hideIme()
            touchpadViewRef?.requestFocus()
        } else {
            onExit()
        }
    }

    val rootView = LocalView.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var imeWasVisibleForSystemKeyboard by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.captionBar)),
    ) {
        val isLandscape = maxWidth > maxHeight
        val isLargeScreen = remember(context, maxWidth, maxHeight) {
            val dm = context.resources.displayMetrics
            val xDpi = dm.xdpi.takeIf { it in 72f..900f } ?: dm.densityDpi.toFloat()
            val yDpi = dm.ydpi.takeIf { it in 72f..900f } ?: dm.densityDpi.toFloat()
            val diagInches = hypot(
                (dm.widthPixels / xDpi).toDouble(),
                (dm.heightPixels / yDpi).toDouble(),
            )
            diagInches >= 7.8 || minOf(maxWidth, maxHeight) >= 540.dp
        }
        val laptopDeckMode = isLandscape && isLargeScreen && !keyboardOpen
        val compactBar = maxWidth < 680.dp

        // Suppress Android's fallback system IME whenever our built-in laptop keyboard is active.
        LaunchedEffect(laptopDeckMode, screen.id) {
            controller.setSuppressSystemIme(laptopDeckMode)
        }
        LaunchedEffect(laptopDeckMode, imeVisible) {
            if (laptopDeckMode && imeVisible) {
                hideSystemIme(context, rootView)
            }
        }
        // If user dismisses the system IME via the system hide-keyboard button, return to Laptop Deck.
        LaunchedEffect(keyboardOpen, imeVisible) {
            if (!keyboardOpen) {
                imeWasVisibleForSystemKeyboard = false
            } else if (imeVisible) {
                imeWasVisibleForSystemKeyboard = true
            } else if (imeWasVisibleForSystemKeyboard) {
                imeWasVisibleForSystemKeyboard = false
                keyboardOpen = false
                touchpadViewRef?.requestFocus()
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .then(if (laptopDeckMode) Modifier else Modifier.imePadding())
                .padding(horizontal = if (laptopDeckMode) 14.dp else 12.dp, vertical = if (laptopDeckMode) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (laptopDeckMode) 8.dp else 10.dp),
        ) {
        // Top control bar (single row on wide tablets, 2 compact rows on mobile so Close X is always visible)
        val toggleCaptureMouse: () -> Unit = {
            val view = touchpadViewRef
            if (view != null) {
                if (pointerCaptured) {
                    view.releasePointerCapture()
                } else {
                    if (keyboardOpen) {
                        keyboardOpen = false
                        keyboardEditRef?.hideIme()
                    }
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }
        }
        val toggleSystemKeyboard: () -> Unit = {
            val opening = !keyboardOpen
            keyboardOpen = opening
            if (opening) {
                touchpadViewRef?.let { if (it.hasPointerCapture()) it.releasePointerCapture() }
            } else {
                keyboardEditRef?.hideIme()
                touchpadViewRef?.requestFocus()
            }
        }
        val enterBlackout: () -> Unit = {
            if (keyboardOpen) {
                keyboardOpen = false
                keyboardEditRef?.hideIme()
            }
            blackoutMode = true
        }

        val actionControls: @Composable RowScope.() -> Unit = {
            // Physical mouse capture toggle
            FilledTonalButton(
                onClick = toggleCaptureMouse,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = if (pointerCaptured) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(Icons.Default.Mouse, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (pointerCaptured) R.string.touchpad_mouse_captured else R.string.touchpad_capture_mouse),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }

            // Speed / DPI popover button
            Box {
                OutlinedButton(
                    onClick = { speedMenuOpen = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Speed, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${(liveSpeed * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false },
                ) {
                    Column(
                        Modifier
                            .width(236.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.touchpad_pointer_speed),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${(liveSpeed * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary,
                            )
                        }
                        Slider(
                            value = liveSpeed,
                            onValueChange = { liveSpeed = it },
                            onValueChangeFinished = { onPointerSpeedChange(liveSpeed) },
                            valueRange = 0.4f..3.0f,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SPEED_PRESETS.forEach { preset ->
                                val selected = abs(liveSpeed - preset) < 0.08f
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        liveSpeed = preset
                                        onPointerSpeedChange(preset)
                                    },
                                    label = { Text("${preset}×", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = { controller.sendKeyCode(KeyEvent.KEYCODE_BACK) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.touchpad_back),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = { controller.sendKeyCode(KeyEvent.KEYCODE_F11) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = stringResource(R.string.true_fullscreen),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = { controller.sendKeyCode(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = stringResource(R.string.apps),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = toggleSystemKeyboard,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (keyboardOpen) colors.primaryContainer else Color.Transparent),
            ) {
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = stringResource(R.string.touchpad_keyboard),
                    tint = if (keyboardOpen) colors.onPrimaryContainer else colors.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceContainer,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (compactBar) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.touchpad_mode_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.touchpad_target_display, screen.id, screen.width, screen.height),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = enterBlackout, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = stringResource(R.string.touchpad_blackout),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        FilledTonalIconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.touchpad_exit),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        actionControls()
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.touchpad_mode_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.touchpad_target_display, screen.id, screen.width, screen.height),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    actionControls()
                    IconButton(onClick = enterBlackout, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = stringResource(R.string.touchpad_blackout),
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    FilledTonalIconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.touchpad_exit),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (laptopDeckMode) {
            // Laptop deck: full QWERTY keyboard on top, centered trackpad with palm-rest margins below.
            LaptopKeyboardDeck(
                controller = controller,
                modifier = Modifier.weight(0.55f),
            )
            Box(
                Modifier
                    .weight(0.45f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.62f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TouchpadSurfaceBox(
                        controller = controller,
                        liveSpeed = liveSpeed,
                        pointerCaptured = pointerCaptured,
                        mouseDetected = mouseDetected,
                        hasInteracted = hasInteracted,
                        compact = true,
                        onViewCreated = { touchpadViewRef = it },
                        onInteracted = { hasInteracted = true },
                        onPointerCaptureChanged = { pointerCaptured = it },
                        onMouseSeen = { mouseDetected = true },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    MouseClickBar(
                        controller = controller,
                        height = 38.dp,
                        onInteracted = { hasInteracted = true },
                    )
                }
            }
        } else {
            // Mobile / portrait / system IME layout
            TouchpadSurfaceBox(
                controller = controller,
                liveSpeed = liveSpeed,
                pointerCaptured = pointerCaptured,
                mouseDetected = mouseDetected,
                hasInteracted = hasInteracted,
                compact = false,
                onViewCreated = { touchpadViewRef = it },
                onInteracted = { hasInteracted = true },
                onPointerCaptureChanged = { pointerCaptured = it },
                onMouseSeen = { mouseDetected = true },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        // Optional keyboard input strip & quick keys
        if (keyboardOpen) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickKeyChip("Esc") { controller.sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
                        QuickKeyChip("Tab") { controller.sendKeyCode(KeyEvent.KEYCODE_TAB) }
                        QuickKeyChip("Ctrl+C") { controller.sendKeyCode(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON) }
                        QuickKeyChip("Ctrl+V") { controller.sendKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON) }
                        QuickKeyChip("Ctrl+A") { controller.sendKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON) }
                        QuickKeyChip("←") { controller.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
                        QuickKeyChip("↑") { controller.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
                        QuickKeyChip("↓") { controller.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
                        QuickKeyChip("→") { controller.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val hintText = stringResource(R.string.touchpad_keyboard_placeholder)
                        val textColor = colors.onSurface.toArgb()
                        val hintColor = colors.onSurfaceVariant.toArgb()
                        AndroidView(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceContainerLow)
                                .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            factory = { ctx ->
                                RemoteKeyboardEditText(
                                    context = ctx,
                                    onSendText = { controller.sendText(it) },
                                    onSendKey = { controller.sendKeyEvent(it) },
                                    onBackspace = { controller.sendKeyCode(KeyEvent.KEYCODE_DEL) },
                                    onEnter = { controller.sendKeyCode(KeyEvent.KEYCODE_ENTER) },
                                ).apply {
                                    hint = hintText
                                    setTextColor(textColor)
                                    setHintTextColor(hintColor)
                                    background = null
                                    textSize = 14f
                                    keyboardEditRef = this
                                    post { showIme() }
                                }
                            },
                            update = { edit ->
                                edit.hint = hintText
                                edit.setTextColor(textColor)
                                edit.setHintTextColor(hintColor)
                            },
                        )
                        FilledTonalButton(
                            onClick = {
                                keyboardOpen = false
                                keyboardEditRef?.hideIme()
                                touchpadViewRef?.requestFocus()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.touchpad_hide_keyboard), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (!laptopDeckMode) {
            MouseClickBar(
                controller = controller,
                height = 52.dp,
                onInteracted = { hasInteracted = true },
            )
        }
        }

        if (blackoutMode) {
            var showBlackoutHint by remember { mutableStateOf(true) }
            LaunchedEffect(showBlackoutHint) {
                if (showBlackoutHint) {
                    delay(3500L)
                    showBlackoutHint = false
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showBlackoutHint = true },
                            onDoubleTap = { blackoutMode = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (showBlackoutHint) {
                    Text(
                        stringResource(R.string.touchpad_blackout_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchpadSurfaceBox(
    controller: ExternalInputController,
    liveSpeed: Float,
    pointerCaptured: Boolean,
    mouseDetected: Boolean,
    hasInteracted: Boolean,
    compact: Boolean,
    onViewCreated: (ExternalTouchpadView) -> Unit,
    onInteracted: () -> Unit,
    onPointerCaptureChanged: (Boolean) -> Unit,
    onMouseSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceContainerLowest)
            .border(
                width = if (pointerCaptured) 2.dp else 1.dp,
                color = if (pointerCaptured) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                shape = shape,
            ),
    ) {
        val dotColor = colors.onSurfaceVariant.copy(alpha = 0.16f).toArgb()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ExternalTouchpadView(
                    context = ctx,
                    dotColorArgb = dotColor,
                    controller = controller,
                    initialSensitivity = liveSpeed,
                    onInteracted = onInteracted,
                    onPointerCaptureChanged = onPointerCaptureChanged,
                    onMouseSeen = onMouseSeen,
                ).also(onViewCreated)
            },
            update = { view ->
                view.setDotColor(dotColor)
                view.setSensitivity(liveSpeed)
            },
        )

        if (!hasInteracted || pointerCaptured) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(if (compact) 12.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                if (pointerCaptured) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colors.primaryContainer.copy(alpha = 0.92f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Mouse, null, Modifier.size(16.dp), tint = colors.onPrimaryContainer)
                            Text(
                                stringResource(R.string.touchpad_mouse_captured_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onPrimaryContainer,
                            )
                        }
                    }
                } else {
                    if (!compact) {
                        Icon(
                            Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(38.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.touchpad_gestures_hint),
                        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                    if (mouseDetected) {
                        Text(
                            stringResource(R.string.touchpad_physical_mouse_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MouseClickBar(
    controller: ExternalInputController,
    height: androidx.compose.ui.unit.Dp,
    onInteracted: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var leftHeld by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(shape)
                .background(if (leftHeld) colors.primaryContainer else colors.surfaceContainer)
                .border(
                    1.dp,
                    if (leftHeld) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                    shape,
                )
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    onInteracted()
                                    leftHeld = true
                                    controller.primaryDown()
                                }
                                PointerEventType.Release -> {
                                    if (leftHeld) {
                                        leftHeld = false
                                        controller.primaryUp()
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.touchpad_left_click),
                style = MaterialTheme.typography.labelMedium,
                color = if (leftHeld) colors.onPrimaryContainer else colors.onSurface,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(shape)
                .background(colors.surfaceContainer)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = {
                        onInteracted()
                        controller.secondaryClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.touchpad_right_click),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun QuickKeyChip(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Crisp desktop arrow cursor rendered in physical pixel coordinates on the external display.
 * Uses graphicsLayer translation so moving the cursor causes zero Compose recompositions or
 * layout passes on the external desktop.
 */
@Composable
internal fun BoxScope.ExternalCursorOverlay(
    pointerState: StateFlow<ExternalPointerState>,
    currentDisplayId: Int,
) {
    val pointer = pointerState.collectAsStateWithLifecycle()
    val visible by remember(currentDisplayId) {
        derivedStateOf {
            val p = pointer.value
            p.visible && p.targetDisplayId == currentDisplayId
        }
    }
    if (!visible) return
    Canvas(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .size(28.dp)
            .graphicsLayer {
                val p = pointer.value
                translationX = p.x
                translationY = p.y
                val s = if (p.pressed) 0.90f else 1.0f
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        val s = size.minDimension / 24f
        val shadowPath = Path().apply {
            moveTo(1.5f * s, 2.2f * s)
            lineTo(1.5f * s, 19.2f * s)
            lineTo(5.9f * s, 14.9f * s)
            lineTo(9.1f * s, 21.6f * s)
            lineTo(11.6f * s, 20.4f * s)
            lineTo(8.4f * s, 13.7f * s)
            lineTo(14.4f * s, 13.7f * s)
            close()
        }
        drawPath(shadowPath, color = Color.Black.copy(alpha = 0.35f))

        val cursorPath = Path().apply {
            moveTo(0.5f * s, 0.8f * s)
            lineTo(0.5f * s, 17.8f * s)
            lineTo(4.9f * s, 13.5f * s)
            lineTo(8.1f * s, 20.2f * s)
            lineTo(10.6f * s, 19.0f * s)
            lineTo(7.4f * s, 12.3f * s)
            lineTo(13.4f * s, 12.3f * s)
            close()
        }
        drawPath(cursorPath, color = Color(0xFF14161A))
        drawPath(
            cursorPath,
            color = Color.White,
            style = Stroke(width = 1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Native View for the touchpad surface that supports:
 * 1) Multi-touch laptop-like trackpad gestures (1-finger move/tap/double-tap-drag, 2-finger tap/scroll)
 * 2) Physical mouse pointer capture (View.requestPointerCapture) so a mouse plugged into the tablet
 *    directly drives the external display cursor, clicks, drags, and scroll wheel.
 * 3) Hardware keyboard forwarding to the external display.
 */
private class ExternalTouchpadView(
    context: Context,
    dotColorArgb: Int,
    private val controller: ExternalInputController,
    initialSensitivity: Float,
    private val onInteracted: () -> Unit,
    private val onPointerCaptureChanged: (Boolean) -> Unit,
    private val onMouseSeen: () -> Unit,
) : View(context) {

    private var sensitivity: Float = initialSensitivity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val dotSpacingPx = 28f * resources.displayMetrics.density
    private val dotRadiusPx = 1.4f * resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColorArgb
        style = Paint.Style.FILL
    }

    // Touch gesture tracking
    private var maxPointers = 0
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTwoX = 0f
    private var lastTwoY = 0f
    private var totalMove = 0f
    private var lastSingleTapUpMs = 0L
    private var tapDragging = false

    // Captured physical mouse button tracking
    private var capturedPrimaryDown = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    fun setDotColor(colorArgb: Int) {
        if (dotPaint.color != colorArgb) {
            dotPaint.color = colorArgb
            invalidate()
        }
    }

    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val step = dotSpacingPx.coerceAtLeast(16f)
        var y = step
        while (y < height - step / 2f) {
            var x = step
            while (x < width - step / 2f) {
                canvas.drawCircle(x, y, dotRadiusPx, dotPaint)
                x += step
            }
            y += step
        }
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (!hasCapture && capturedPrimaryDown) {
            capturedPrimaryDown = false
            controller.primaryUp()
        }
        onPointerCaptureChanged(hasCapture)
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        onInteracted()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Sum all batched hardware mouse reports so high-polling-rate mice never drop deltas.
                var rawDx = 0f
                var rawDy = 0f
                for (i in 0 until event.historySize) {
                    rawDx += event.getHistoricalX(i)
                    rawDy += event.getHistoricalY(i)
                }
                rawDx += event.x
                rawDy += event.y
                val dx = rawDx * sensitivity
                val dy = rawDy * sensitivity
                if (dx != 0f || dy != 0f) {
                    controller.moveBy(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> {
                        capturedPrimaryDown = true
                        controller.primaryDown()
                    }
                    MotionEvent.BUTTON_SECONDARY -> controller.secondaryClick()
                    MotionEvent.BUTTON_BACK -> controller.sendKeyCode(KeyEvent.KEYCODE_BACK)
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY && capturedPrimaryDown) {
                    capturedPrimaryDown = false
                    controller.primaryUp()
                }
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                // Some mice emit ACTION_DOWN/UP instead of ACTION_BUTTON_PRESS/RELEASE while captured.
                if ((event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    controller.secondaryClick()
                } else if (!capturedPrimaryDown) {
                    capturedPrimaryDown = true
                    controller.primaryDown()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (capturedPrimaryDown) {
                    capturedPrimaryDown = false
                    controller.primaryUp()
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                controller.scroll(h, v)
                return true
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isPhysicalMouse(event)) {
            onMouseSeen()
            return true
        }
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPhysicalMouse(event)) {
            onMouseSeen()
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                controller.scroll(h, v)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Clicking the touchpad with a physical mouse immediately captures the mouse onto the external display.
        if (isPhysicalMouse(event)) {
            onMouseSeen()
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !hasPointerCapture()) {
                requestFocus()
                requestPointerCapture()
            }
            return true
        }

        onInteracted()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                maxPointers = 1
                downTimeMs = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                totalMove = 0f
                if (event.eventTime - lastSingleTapUpMs <= DOUBLE_TAP_WINDOW_MS) {
                    tapDragging = true
                    controller.primaryDown()
                } else {
                    tapDragging = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (tapDragging) {
                    tapDragging = false
                    controller.primaryUp()
                }
                if (event.pointerCount >= 2) {
                    lastTwoX = (event.getX(0) + event.getX(1)) / 2f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    maxPointers = maxOf(maxPointers, event.pointerCount)
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val dx = cx - lastTwoX
                    val dy = cy - lastTwoY
                    lastTwoX = cx
                    lastTwoY = cy
                    totalMove += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                        val scrollScale = 1f / (24f * resources.displayMetrics.density)
                        controller.scroll(dx * scrollScale, dy * scrollScale)
                    }
                } else if (maxPointers == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    val step = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    totalMove += step
                    if (dx != 0f || dy != 0f) {
                        // Gentle acceleration on fast finger swipes while keeping slow movements precise.
                        val dpStep = step / resources.displayMetrics.density.coerceAtLeast(1f)
                        val accel = 1f + (dpStep / 14f).coerceAtMost(1.35f)
                        val gain = sensitivity * accel
                        controller.moveBy(dx * gain, dy * gain)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTimeMs
                val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                if (tapDragging) {
                    tapDragging = false
                    controller.primaryUp()
                    lastSingleTapUpMs = 0L
                } else if (duration <= TAP_TIMEOUT_MS && totalMove < touchSlop * 1.6f && moved < touchSlop * 1.6f) {
                    if (maxPointers >= 2) {
                        controller.secondaryClick()
                        lastSingleTapUpMs = 0L
                    } else {
                        controller.primaryClick()
                        lastSingleTapUpMs = event.eventTime
                    }
                }
                maxPointers = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (tapDragging) {
                    tapDragging = false
                    controller.primaryUp()
                }
                maxPointers = 0
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Allow Ctrl+Alt to release captured physical mouse back to the tablet.
        if (hasPointerCapture() && event.action == KeyEvent.ACTION_DOWN &&
            event.isCtrlPressed && event.isAltPressed
        ) {
            releasePointerCapture()
            return true
        }
        // Let volume keys work normally on the device.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.dispatchKeyEvent(event)
        }
        controller.sendKeyEvent(event)
        return true
    }

    private fun isPhysicalMouse(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true
        return false
    }

    companion object {
        private const val TAP_TIMEOUT_MS = 260L
        private const val DOUBLE_TAP_WINDOW_MS = 280L
    }
}

/**
 * Lightweight EditText whose InputConnection forwards committed text (including Vietnamese IME
 * compositions), Backspace, and Enter directly to the external display.
 */
private class RemoteKeyboardEditText(
    context: Context,
    private val onSendText: (String) -> Unit,
    private val onSendKey: (KeyEvent) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
) : EditText(context) {

    init {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val pending = text?.toString().orEmpty()
                if (pending.isNotEmpty()) {
                    onSendText(pending)
                    setText("")
                }
                onEnter()
                true
            } else {
                false
            }
        }
    }

    fun showIme() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideIme() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    onSendText(str)
                    // Clear composing buffer after forwarding committed text.
                    setText("")
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (text.isNullOrEmpty() && beforeLength > 0) {
                    repeat(beforeLength) { onBackspace() }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && text.isNullOrEmpty()) {
                    if (event.action == KeyEvent.ACTION_DOWN) onBackspace()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_ENTER && text.isNullOrEmpty()) {
                    if (event.action == KeyEvent.ACTION_DOWN) onEnter()
                    return true
                }
                if (text.isNullOrEmpty()) {
                    onSendKey(event)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
