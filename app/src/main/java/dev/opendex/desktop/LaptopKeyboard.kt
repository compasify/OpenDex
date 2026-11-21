package dev.opendex.desktop

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class LaptopKeySpec(
    val label: String,
    val shiftLabel: String? = null,
    val keyCode: Int,
    val weight: Float = 1f,
    val repeatable: Boolean = false,
    val isLetter: Boolean = false,
    val modifierKind: ModifierKind? = null,
)

private enum class ModifierKind { Shift, Caps, Ctrl, Alt, Meta }

private val ROW_FN = listOf(
    LaptopKeySpec("Esc", keyCode = KeyEvent.KEYCODE_ESCAPE, weight = 1.2f),
    LaptopKeySpec("F1", keyCode = KeyEvent.KEYCODE_F1),
    LaptopKeySpec("F2", keyCode = KeyEvent.KEYCODE_F2),
    LaptopKeySpec("F3", keyCode = KeyEvent.KEYCODE_F3),
    LaptopKeySpec("F4", keyCode = KeyEvent.KEYCODE_F4),
    LaptopKeySpec("F5", keyCode = KeyEvent.KEYCODE_F5),
    LaptopKeySpec("F6", keyCode = KeyEvent.KEYCODE_F6),
    LaptopKeySpec("F7", keyCode = KeyEvent.KEYCODE_F7),
    LaptopKeySpec("F8", keyCode = KeyEvent.KEYCODE_F8),
    LaptopKeySpec("F9", keyCode = KeyEvent.KEYCODE_F9),
    LaptopKeySpec("F10", keyCode = KeyEvent.KEYCODE_F10),
    LaptopKeySpec("F11", keyCode = KeyEvent.KEYCODE_F11),
    LaptopKeySpec("F12", keyCode = KeyEvent.KEYCODE_F12),
    LaptopKeySpec("Del", keyCode = KeyEvent.KEYCODE_FORWARD_DEL, weight = 1.3f, repeatable = true),
)

private val ROW_NUM = listOf(
    LaptopKeySpec("`", "~", KeyEvent.KEYCODE_GRAVE),
    LaptopKeySpec("1", "!", KeyEvent.KEYCODE_1),
    LaptopKeySpec("2", "@", KeyEvent.KEYCODE_2),
    LaptopKeySpec("3", "#", KeyEvent.KEYCODE_3),
    LaptopKeySpec("4", "$", KeyEvent.KEYCODE_4),
    LaptopKeySpec("5", "%", KeyEvent.KEYCODE_5),
    LaptopKeySpec("6", "^", KeyEvent.KEYCODE_6),
    LaptopKeySpec("7", "&", KeyEvent.KEYCODE_7),
    LaptopKeySpec("8", "*", KeyEvent.KEYCODE_8),
    LaptopKeySpec("9", "(", KeyEvent.KEYCODE_9),
    LaptopKeySpec("0", ")", KeyEvent.KEYCODE_0),
    LaptopKeySpec("-", "_", KeyEvent.KEYCODE_MINUS),
    LaptopKeySpec("=", "+", KeyEvent.KEYCODE_EQUALS),
    LaptopKeySpec("⌫", keyCode = KeyEvent.KEYCODE_DEL, weight = 1.8f, repeatable = true),
)

private val ROW_TOP = listOf(
    LaptopKeySpec("Tab", keyCode = KeyEvent.KEYCODE_TAB, weight = 1.5f),
    LaptopKeySpec("q", "Q", KeyEvent.KEYCODE_Q, isLetter = true),
    LaptopKeySpec("w", "W", KeyEvent.KEYCODE_W, isLetter = true),
    LaptopKeySpec("e", "E", KeyEvent.KEYCODE_E, isLetter = true),
    LaptopKeySpec("r", "R", KeyEvent.KEYCODE_R, isLetter = true),
    LaptopKeySpec("t", "T", KeyEvent.KEYCODE_T, isLetter = true),
    LaptopKeySpec("y", "Y", KeyEvent.KEYCODE_Y, isLetter = true),
    LaptopKeySpec("u", "U", KeyEvent.KEYCODE_U, isLetter = true),
    LaptopKeySpec("i", "I", KeyEvent.KEYCODE_I, isLetter = true),
    LaptopKeySpec("o", "O", KeyEvent.KEYCODE_O, isLetter = true),
    LaptopKeySpec("p", "P", KeyEvent.KEYCODE_P, isLetter = true),
    LaptopKeySpec("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET),
    LaptopKeySpec("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
    LaptopKeySpec("\\", "|", KeyEvent.KEYCODE_BACKSLASH, weight = 1.3f),
)

private val ROW_HOME = listOf(
    LaptopKeySpec("Caps", keyCode = KeyEvent.KEYCODE_CAPS_LOCK, weight = 1.75f, modifierKind = ModifierKind.Caps),
    LaptopKeySpec("a", "A", KeyEvent.KEYCODE_A, isLetter = true),
    LaptopKeySpec("s", "S", KeyEvent.KEYCODE_S, isLetter = true),
    LaptopKeySpec("d", "D", KeyEvent.KEYCODE_D, isLetter = true),
    LaptopKeySpec("f", "F", KeyEvent.KEYCODE_F, isLetter = true),
    LaptopKeySpec("g", "G", KeyEvent.KEYCODE_G, isLetter = true),
    LaptopKeySpec("h", "H", KeyEvent.KEYCODE_H, isLetter = true),
    LaptopKeySpec("j", "J", KeyEvent.KEYCODE_J, isLetter = true),
    LaptopKeySpec("k", "K", KeyEvent.KEYCODE_K, isLetter = true),
    LaptopKeySpec("l", "L", KeyEvent.KEYCODE_L, isLetter = true),
    LaptopKeySpec(";", ":", KeyEvent.KEYCODE_SEMICOLON),
    LaptopKeySpec("'", "\"", KeyEvent.KEYCODE_APOSTROPHE),
    LaptopKeySpec("Enter ↵", keyCode = KeyEvent.KEYCODE_ENTER, weight = 2.05f),
)

private val ROW_BOTTOM = listOf(
    LaptopKeySpec("⇧ Shift", keyCode = KeyEvent.KEYCODE_SHIFT_LEFT, weight = 2.25f, modifierKind = ModifierKind.Shift),
    LaptopKeySpec("z", "Z", KeyEvent.KEYCODE_Z, isLetter = true),
    LaptopKeySpec("x", "X", KeyEvent.KEYCODE_X, isLetter = true),
    LaptopKeySpec("c", "C", KeyEvent.KEYCODE_C, isLetter = true),
    LaptopKeySpec("v", "V", KeyEvent.KEYCODE_V, isLetter = true),
    LaptopKeySpec("b", "B", KeyEvent.KEYCODE_B, isLetter = true),
    LaptopKeySpec("n", "N", KeyEvent.KEYCODE_N, isLetter = true),
    LaptopKeySpec("m", "M", KeyEvent.KEYCODE_M, isLetter = true),
    LaptopKeySpec(",", "<", KeyEvent.KEYCODE_COMMA),
    LaptopKeySpec(".", ">", KeyEvent.KEYCODE_PERIOD),
    LaptopKeySpec("/", "?", KeyEvent.KEYCODE_SLASH),
    LaptopKeySpec("Shift ⇧", keyCode = KeyEvent.KEYCODE_SHIFT_RIGHT, weight = 2.55f, modifierKind = ModifierKind.Shift),
)

/**
 * Built-in laptop-style virtual QWERTY keyboard deck designed for large landscape tablets (8"+).
 * Supports one-shot & held modifiers (Shift, Ctrl, Alt, Meta), Caps Lock, and key repeat on hold.
 */
@Composable
internal fun LaptopKeyboardDeck(
    controller: ExternalInputController,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var shiftLatched by remember { mutableStateOf(false) }
    var capsLocked by remember { mutableStateOf(false) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    var metaLatched by remember { mutableStateOf(false) }

    fun activeMetaState(isLetter: Boolean): Int {
        var meta = 0
        val effectiveShift = if (isLetter) shiftLatched xor capsLocked else shiftLatched
        if (effectiveShift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (capsLocked) meta = meta or KeyEvent.META_CAPS_LOCK_ON
        if (ctrlLatched) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altLatched) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (metaLatched) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return meta
    }

    fun clearOneShotModifiers() {
        shiftLatched = false
        ctrlLatched = false
        altLatched = false
        metaLatched = false
    }

    fun dispatchKey(spec: LaptopKeySpec) {
        when (spec.modifierKind) {
            ModifierKind.Shift -> { shiftLatched = !shiftLatched; return }
            ModifierKind.Caps -> { capsLocked = !capsLocked; return }
            ModifierKind.Ctrl -> { ctrlLatched = !ctrlLatched; return }
            ModifierKind.Alt -> { altLatched = !altLatched; return }
            ModifierKind.Meta -> { metaLatched = !metaLatched; return }
            null -> Unit
        }
        val meta = activeMetaState(spec.isLetter)
        controller.sendKeyCode(spec.keyCode, meta)
        if (shiftLatched || ctrlLatched || altLatched || metaLatched) {
            clearOneShotModifiers()
        }
    }

    fun isModifierActive(kind: ModifierKind?): Boolean = when (kind) {
        ModifierKind.Shift -> shiftLatched
        ModifierKind.Caps -> capsLocked
        ModifierKind.Ctrl -> ctrlLatched
        ModifierKind.Alt -> altLatched
        ModifierKind.Meta -> metaLatched
        null -> false
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Function row (slightly shorter like a real laptop)
            KeyRow(
                keys = ROW_FN,
                shiftActive = shiftLatched,
                capsActive = capsLocked,
                isModifierActive = ::isModifierActive,
                onKeyTrigger = ::dispatchKey,
                modifier = Modifier.weight(0.76f),
                compact = true,
            )
            KeyRow(
                keys = ROW_NUM,
                shiftActive = shiftLatched,
                capsActive = capsLocked,
                isModifierActive = ::isModifierActive,
                onKeyTrigger = ::dispatchKey,
                modifier = Modifier.weight(1f),
            )
            KeyRow(
                keys = ROW_TOP,
                shiftActive = shiftLatched,
                capsActive = capsLocked,
                isModifierActive = ::isModifierActive,
                onKeyTrigger = ::dispatchKey,
                modifier = Modifier.weight(1f),
            )
            KeyRow(
                keys = ROW_HOME,
                shiftActive = shiftLatched,
                capsActive = capsLocked,
                isModifierActive = ::isModifierActive,
                onKeyTrigger = ::dispatchKey,
                modifier = Modifier.weight(1f),
            )
            KeyRow(
                keys = ROW_BOTTOM,
                shiftActive = shiftLatched,
                capsActive = capsLocked,
                isModifierActive = ::isModifierActive,
                onKeyTrigger = ::dispatchKey,
                modifier = Modifier.weight(1f),
            )

            // Space & arrow cluster row (MacBook-style inverted-T arrows)
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1.04f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LaptopKeyCap(
                    spec = LaptopKeySpec("Ctrl", keyCode = KeyEvent.KEYCODE_CTRL_LEFT, modifierKind = ModifierKind.Ctrl),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = ctrlLatched,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.4f).fillMaxHeight(),
                )
                LaptopKeyCap(
                    spec = LaptopKeySpec("❖", keyCode = KeyEvent.KEYCODE_META_LEFT, modifierKind = ModifierKind.Meta),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = metaLatched,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.15f).fillMaxHeight(),
                )
                LaptopKeyCap(
                    spec = LaptopKeySpec("Alt", keyCode = KeyEvent.KEYCODE_ALT_LEFT, modifierKind = ModifierKind.Alt),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = altLatched,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.2f).fillMaxHeight(),
                )
                LaptopKeyCap(
                    spec = LaptopKeySpec("", keyCode = KeyEvent.KEYCODE_SPACE, repeatable = true),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = false,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(6.05f).fillMaxHeight(),
                )
                LaptopKeyCap(
                    spec = LaptopKeySpec("Alt", keyCode = KeyEvent.KEYCODE_ALT_RIGHT, modifierKind = ModifierKind.Alt),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = altLatched,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.2f).fillMaxHeight(),
                )
                LaptopKeyCap(
                    spec = LaptopKeySpec("←", keyCode = KeyEvent.KEYCODE_DPAD_LEFT, repeatable = true),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = false,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.0f).fillMaxHeight(),
                )
                Column(
                    Modifier.weight(1.0f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    LaptopKeyCap(
                        spec = LaptopKeySpec("↑", keyCode = KeyEvent.KEYCODE_DPAD_UP, repeatable = true),
                        shiftActive = shiftLatched,
                        capsActive = capsLocked,
                        active = false,
                        onTrigger = ::dispatchKey,
                        compact = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    LaptopKeyCap(
                        spec = LaptopKeySpec("↓", keyCode = KeyEvent.KEYCODE_DPAD_DOWN, repeatable = true),
                        shiftActive = shiftLatched,
                        capsActive = capsLocked,
                        active = false,
                        onTrigger = ::dispatchKey,
                        compact = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
                LaptopKeyCap(
                    spec = LaptopKeySpec("→", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, repeatable = true),
                    shiftActive = shiftLatched,
                    capsActive = capsLocked,
                    active = false,
                    onTrigger = ::dispatchKey,
                    modifier = Modifier.weight(1.0f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<LaptopKeySpec>,
    shiftActive: Boolean,
    capsActive: Boolean,
    isModifierActive: (ModifierKind?) -> Boolean,
    onKeyTrigger: (LaptopKeySpec) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { spec ->
            LaptopKeyCap(
                spec = spec,
                shiftActive = shiftActive,
                capsActive = capsActive,
                active = isModifierActive(spec.modifierKind),
                onTrigger = onKeyTrigger,
                compact = compact,
                modifier = Modifier.weight(spec.weight).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun LaptopKeyCap(
    spec: LaptopKeySpec,
    shiftActive: Boolean,
    capsActive: Boolean,
    active: Boolean,
    onTrigger: (LaptopKeySpec) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val latestTrigger by rememberUpdatedState(onTrigger)
    var pressed by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
    val bgColor = when {
        pressed -> colors.primary.copy(alpha = 0.28f)
        active -> colors.primaryContainer
        spec.modifierKind != null || spec.weight > 1.2f -> colors.surfaceContainerHigh
        else -> colors.surfaceContainerHighest
    }
    val borderColor = when {
        pressed || active -> colors.primary
        else -> colors.outlineVariant.copy(alpha = 0.55f)
    }
    val fgColor = when {
        active -> colors.onPrimaryContainer
        else -> colors.onSurface
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .pointerInput(spec) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        latestTrigger(spec)
                        val repeatJob = if (spec.repeatable) {
                            launch {
                                delay(380L)
                                while (true) {
                                    latestTrigger(spec)
                                    delay(55L)
                                }
                            }
                        } else null
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            repeatJob?.cancel()
                            pressed = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val showUpper = if (spec.isLetter) shiftActive xor capsActive else shiftActive
        if (!spec.isLetter && spec.shiftLabel != null) {
            // Dual-legend symbol/number key (like a real laptop keycap)
            Text(
                text = spec.shiftLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (shiftActive) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.72f),
                fontWeight = if (shiftActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 5.dp, top = 2.dp),
            )
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp),
                color = if (shiftActive) colors.onSurfaceVariant.copy(alpha = 0.65f) else fgColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 3.dp),
            )
        } else {
            val display = if (spec.isLetter && showUpper && spec.shiftLabel != null) spec.shiftLabel else spec.label
            Text(
                text = display,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                } else {
                    MaterialTheme.typography.labelLarge.copy(fontSize = if (display.length > 2) 12.sp else 14.sp)
                },
                color = fgColor,
                fontWeight = if (spec.isLetter) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }

        // Caps Lock LED dot indicator
        if (spec.modifierKind == ModifierKind.Caps) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 5.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (capsActive) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
