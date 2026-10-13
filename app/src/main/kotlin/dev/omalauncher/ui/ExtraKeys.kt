package dev.omalauncher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.wayland.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row's height, keycap and the gap around it; the bar is two rows, like Termux's default. */
private val RowHeight = 40.dp
private val KeyGap = 3.dp
private val FrameWidth = 2.dp

/** The gap between the bar and the keyboard, and to the screen's sides: the tiles' gap. */
private val Gap = 6.dp

/** The bar's full height, gap included; the home screen lifts a typing tile by this much more. */
val ExtraKeysHeight = RowHeight * 2 + (FrameWidth + KeyGap) * 2 + Gap

private sealed interface ExtraKey

/**
 * A popup key action triggered by swiping up on a keycap.
 */
private data class Popup(
    val display: String,
    val code: Int,
    val held: Int? = null,
)

/** A key that sends [code], with [held] down around it; drawn as [label], or as a chevron turned [arrow] degrees from up. */
private data class Plain(
    val label: String,
    val code: Int,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    val held: Int? = null,
    val popup: Popup? = null,
) : ExtraKey

private data class Sticky(
    val label: String,
    val modifier: (KeyboardBridge) -> StickyModifier,
) : ExtraKey

// Linux evdev scancodes for alphanumeric keys used in Termux macros
private const val KEY_A = 30
private const val KEY_E = 18
private const val KEY_J = 36

/**
 * Custom 7-column layout with swipe-up (popup) actions imported from Termux.
 */
private val Layout: List<List<ExtraKey>> =
    listOf(
        listOf(
            Plain("esc", Keys.KEY_ESC),
            Plain("/", Keys.KEY_SLASH),
            Plain("-", Keys.KEY_MINUS),
            Plain(
                label = "home",
                code = Keys.KEY_HOME,
                popup = Popup(display = "⇤", code = KEY_A, held = Keys.KEY_LEFTCTRL), // CTRL A (FIRST)
            ),
            Plain("up", Keys.KEY_UP, repeats = true, arrow = 0f),
            Plain(
                label = "end",
                code = Keys.KEY_END,
                popup = Popup(display = "⇥", code = KEY_E, held = Keys.KEY_LEFTCTRL), // CTRL E (LAST)
            ),
            Plain("pgup", Keys.KEY_PAGEUP, repeats = true),
        ),
        listOf(
            Plain(
                label = "tab",
                code = Keys.KEY_TAB,
                popup = Popup(display = "⇧TAB", code = Keys.KEY_TAB, held = Keys.KEY_LEFTSHIFT), // Shift+Tab
            ),
            Sticky("ctrl") { it.ctrl },
            Sticky("alt") { it.alt },
            Plain(
                label = "left",
                code = Keys.KEY_LEFT,
                repeats = true,
                arrow = -90f,
                popup = Popup(display = "W←", code = Keys.KEY_LEFT, held = Keys.KEY_LEFTCTRL), // CTRL LEFT (WORD ←)
            ),
            Plain("down", Keys.KEY_DOWN, repeats = true, arrow = 180f),
            Plain(
                label = "right",
                code = Keys.KEY_RIGHT,
                repeats = true,
                arrow = 90f,
                popup = Popup(display = "W→", code = Keys.KEY_RIGHT, held = Keys.KEY_LEFTCTRL), // CTRL RIGHT (WORD →)
            ),
            Plain(
                label = "pgdn",
                code = Keys.KEY_PAGEDOWN,
                repeats = true,
                popup = Popup(display = "⇧⏎", code = KEY_J, held = Keys.KEY_LEFTCTRL), // CTRL j (⇧ENTER)
            ),
        ),
    )

/**
 * Whether the extra-keys row belongs on screen: only over a docked soft
 * keyboard. With a hardware keyboard, Gboard shows just a floating toolbar
 * that counts as visible but takes no height, and the hardware keyboard has
 * these keys already.
 */
@Composable
fun extraKeysWanted(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val docked by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return docked
}

/**
 * The keys a phone keyboard lacks, docked above it for terminals: a framed
 * panel of keycaps, like the tiles above it. Keys never take focus, so the
 * soft keyboard stays up; ctrl and alt latch for the next key on a tap and
 * lock on a long press.
 */
@Composable
fun ExtraKeys(
    bridge: KeyboardBridge,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = Gap, end = Gap, bottom = Gap)
            .frame(FrameWidth, Palette.border, Palette.background, radius = 12.dp)
            .padding(KeyGap),
    ) {
        for (row in Layout) {
            Row(Modifier.fillMaxWidth().height(RowHeight)) {
                for (key in row) {
                    val cap = Modifier.weight(1f).fillMaxHeight().padding(KeyGap)
                    when (key) {
                        is Plain -> PlainKey(key, bridge, cap)
                        is Sticky -> StickyKey(key, key.modifier(bridge), cap)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainKey(
    key: Plain,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var popupTriggered by remember { mutableStateOf(false) }

    KeyCap(
        modifier.pointerInput(key) {
            val swipeThresholdPx = with(density) { 16.dp.toPx() }
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    popupTriggered = false
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    var repeatJob =
                        if (key.repeats && key.popup == null) {
                            launch {
                                bridge.tap(key.code, key.held)
                                delay(RepeatDelay)
                                while (true) {
                                    bridge.tap(key.code, key.held)
                                    delay(RepeatInterval)
                                }
                            }
                        } else null

                    var totalDragY = 0f
                    var isSwipeUp = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        totalDragY += (change.position.y - change.previousPosition.y)
                        if (key.popup != null && totalDragY < -swipeThresholdPx && !isSwipeUp) {
                            isSwipeUp = true
                            popupTriggered = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            change.consume()
                            break
                        }
                    }

                    repeatJob?.cancel()

                    if (isSwipeUp && key.popup != null) {
                        bridge.tap(key.popup.code, key.popup.held)
                    } else if (repeatJob == null) {
                        bridge.tap(key.code, key.held)
                    }

                    pressed = false
                    popupTriggered = false
                }
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Popup hint badge in top-right corner, like Termux
            if (key.popup != null) {
                BasicText(
                    text = key.popup.display,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 3.dp),
                    style = Mono.copy(
                        color = if (popupTriggered) Palette.background else Palette.accent,
                        fontSize = 8.sp,
                    ),
                )
            }

            if (key.arrow != null) {
                Image(
                    Icons.Chevron,
                    key.label,
                    Modifier.size(20.dp).rotate(key.arrow),
                    colorFilter = ColorFilter.tint(color),
                )
            } else {
                BasicText(key.label, style = Mono.copy(color = color))
            }
        }
    }
}

@Composable
private fun StickyKey(
    key: Sticky,
    sticky: StickyModifier,
    modifier: Modifier,
) {
    val view = LocalView.current
    val latch = sticky.latch
    KeyCap(
        modifier.pointerInput(sticky) {
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    var locked = false
                    val hold =
                        launch {
                            delay(RepeatDelay)
                            sticky.latch = Latch.Locked
                            locked = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    // Wait for pointer release
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    hold.cancel()
                    if (!locked) sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
                }
            }
        },
        // Once: outlined in the accent, for the next key. Locked: filled, until tapped off.
        fill = if (latch == Latch.Locked) Palette.accent else Palette.surface,
        outline = if (latch == Latch.Once) Palette.accent else null,
    ) {
        BasicText(
            key.label,
            style =
                Mono.copy(
                    color =
                        when (latch) {
                            Latch.Off -> Palette.foreground
                            Latch.Once -> Palette.accent
                            Latch.Locked -> Palette.background
                        },
                ),
        )
    }
}

@Composable
private fun KeyCap(
    modifier: Modifier,
    fill: Color,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = LauncherStyle.shape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(FrameWidth, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val RepeatDelay = 400L
private const val RepeatInterval = 50L
