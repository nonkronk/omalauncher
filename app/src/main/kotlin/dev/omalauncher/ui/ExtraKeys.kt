package dev.omalauncher.ui

import android.content.Context
import android.util.Log
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.Proot
import dev.omalauncher.wayland.Keys
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One row's height, keycap and the gap around it; the bar is two rows, like Termux's default. */
private val RowHeight = 40.dp
private val KeyGap = 3.dp
private val FrameWidth = 2.dp

/** The gap between the bar and the keyboard, and to the screen's sides: the tiles' gap. */
private val Gap = 6.dp

/** Height reserved by the home screen for the extra-keys bar. Empty layouts reserve nothing. */
val ExtraKeysHeight: Dp
    get() = if (ExtraKeysState.layout.isEmpty()) 0.dp else RowHeight * ExtraKeysState.layout.size + (FrameWidth + KeyGap) * 2 + Gap

sealed interface ExtraKey

/** A key press with zero or more simultaneous modifiers, or literal text. */
data class ExtraStroke(
    val code: Int? = null,
    val held: List<Int> = emptyList(),
    val text: String? = null,
)

/** The action displayed while dragging upward and executed only on release. */
data class Popup(
    val display: String,
    val strokes: List<ExtraStroke>,
)

data class Plain(
    val label: String,
    val code: Int,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    val held: Int? = null,
    val popup: Popup? = null,
    val actions: List<ExtraStroke>? = null,
) : ExtraKey

data class Sticky(
    val label: String,
    val modifier: (KeyboardBridge) -> StickyModifier,
    val popup: Popup? = null,
) : ExtraKey

/** The unchanged upstream 8-column, two-row keyboard. */
val DefaultLayout: List<List<ExtraKey>> =
    listOf(
        listOf(
            Plain("esc", Keys.KEY_ESC),
            Plain("/", Keys.KEY_SLASH),
            Plain("-", Keys.KEY_MINUS),
            Plain("home", Keys.KEY_HOME),
            Plain("up", Keys.KEY_UP, repeats = true, arrow = 0f),
            Plain("end", Keys.KEY_END),
            Plain("pgup", Keys.KEY_PAGEUP, repeats = true),
            Plain("copy", Keys.KEY_INSERT, held = Keys.KEY_LEFTCTRL),
        ),
        listOf(
            Plain("tab", Keys.KEY_TAB),
            Sticky("ctrl") { it.ctrl },
            Sticky("alt") { it.alt },
            Plain("left", Keys.KEY_LEFT, repeats = true, arrow = -90f),
            Plain("down", Keys.KEY_DOWN, repeats = true, arrow = 180f),
            Plain("right", Keys.KEY_RIGHT, repeats = true, arrow = 90f),
            Plain("pgdn", Keys.KEY_PAGEDOWN, repeats = true),
            Plain("paste", Keys.KEY_INSERT, held = Keys.KEY_LEFTSHIFT),
        ),
    )

object ExtraKeysState {
    var layout by mutableStateOf(DefaultLayout)
}

/** Termux-compatible matrix parser; invalid edits do not replace a working layout. */
object ExtraKeysConfig {
    const val DEFAULT_CONFIG_TEXT = """# Optional extra keys; when unset, Omalauncher's original 2x8 layout is used.
# Edit this file at ~/.config/omalauncher/style/extra-keys.properties.
# A defined layout here takes priority over ~/.termux/termux.properties.
# Un-comment and customize this example to enable swipe-up actions:
# extra-keys = [[ \
#   {key: ESC, popup: {macro: "CTRL f d", display: "tmux exit"}}, \
#   {key: LEFT, popup: {macro: "CTRL LEFT", display: "Word ←"}}, \
#   CTRL, ALT, TAB, UP, DOWN, RIGHT \
# ]]
# To remove the bar completely, use: extra-keys = []
"""

    const val MAX_ROWS = 3
    const val MAX_COLUMNS = 10
    private val propertyLine = Regex("""(?m)^[ \t]*extra-keys[ \t]*=""")
    private val repeatNames = setOf("UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PGDN", "PAGEUP", "PAGEDOWN", "PAGE_UP", "PAGE_DOWN", "BKSP", "BACKSPACE", "DEL", "DELETE")

    fun hasLayoutProperty(text: String): Boolean = propertyLine.containsMatchIn(text)

    /** Keep the legacy safe default for callers that do not handle parsing errors. */
    fun parse(text: String): List<List<ExtraKey>> = parseOrNull(text) ?: DefaultLayout

    /** Returns null for missing or invalid configuration; an intentional [] stays empty. */
    fun parseOrNull(text: String): List<List<ExtraKey>>? {
        if (text.isBlank()) return DefaultLayout
        val array = extractArrayString(text) ?: return null
        return try {
            val root = JSONArray(normalizeJson(array))
            require(root.length() <= MAX_ROWS) { "extra-keys permits at most $MAX_ROWS rows" }
            buildList {
                for (r in 0 until root.length()) {
                    val row = root.getJSONArray(r)
                    require(row.length() <= MAX_COLUMNS) { "extra-keys permits at most $MAX_COLUMNS columns" }
                    if (row.length() == 0) continue
                    add(buildList {
                        for (k in 0 until row.length()) {
                            val item = row.get(k)
                            val key = when (item) {
                                is String -> parseKeySpec(item)
                                is JSONObject -> parseKeyObject(item)
                                else -> null
                            }
                            requireNotNull(key) { "Unsupported extra key at row $r, column $k" }
                            add(key)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w("ExtraKeys", "Invalid extra-keys configuration (keeping previous layout)", e)
            null
        }
    }

    private fun extractArrayString(raw: String): String? {
        val start = if (raw.trimStart().startsWith("[")) raw.indexOf('[') else {
            val match = propertyLine.find(raw) ?: return null
            raw.indexOf('[', match.range.last + 1).takeIf { it >= 0 } ?: return null
        }
        var depth = 0
        var quoted: Char? = null
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (quoted != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quoted) quoted = null
            } else {
                when (c) {
                    '\'', '"' -> quoted = c
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                        if (depth < 0) return null
                    }
                }
            }
        }
        return null
    }

    /** Tokenize Termux's loose JSON without rewriting text inside quoted strings. */
    private fun normalizeJson(raw: String): String {
        val input = raw.replace(Regex("""\\[ \t]*\r?\n"""), " ")
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() || c in "[]{}:," -> { out.append(c); i++ }
                c == '#' || (c == '/' && input.getOrNull(i + 1) == '/' && (i == 0 || input[i - 1] == '\n')) -> {
                    while (i < input.length && input[i] != '\n') i++
                }
                c == '\'' || c == '"' -> {
                    val quote = c
                    i++
                    val value = StringBuilder()
                    var closed = false
                    while (i < input.length) {
                        val ch = input[i++]
                        if (ch == quote) { closed = true; break }
                        if (ch == '\\' && i < input.length) {
                            val next = input[i++]
                            value.append(when (next) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'u' -> {
                                    if (i + 4 <= input.length) {
                                        val digits = input.substring(i, i + 4)
                                        val cp = digits.toIntOrNull(16)
                                        if (cp != null) { i += 4; cp.toChar() } else 'u'
                                    } else 'u'
                                }
                                else -> next
                            })
                        } else value.append(ch)
                    }
                    require(closed) { "Unclosed extra-keys string" }
                    out.append(JSONObject.quote(value.toString()))
                }
                else -> {
                    val from = i
                    while (i < input.length && !input[i].isWhitespace() && input[i] !in "[]{}:,") i++
                    require(i > from) { "Invalid extra-keys token" }
                    out.append(JSONObject.quote(input.substring(from, i)))
                }
            }
        }
        return out.toString()
    }

    private fun parseKeyObject(obj: JSONObject): ExtraKey? {
        val display = obj.optString("display").takeIf { it.isNotEmpty() }
        val popup = parsePopup(obj.opt("popup"))
        val macro = obj.optString("macro").takeIf { it.isNotEmpty() }
        if (macro != null) {
            val strokes = parseMacro(macro)
            if (strokes.isEmpty()) return null
            return Plain(display ?: macro, 0, popup = popup, actions = strokes)
        }
        val key = obj.optString("key").takeIf { it.isNotEmpty() } ?: return null
        return parseKeySpec(key, display, popup)
    }

    fun parseKeySpec(rawName: String, customDisplay: String? = null, popup: Popup? = null): ExtraKey? {
        val name = rawName.trim()
        val upper = name.uppercase().removePrefix("KEY_")
        val label = customDisplay ?: name.lowercase()
        return when (upper) {
            "CTRL", "CONTROL" -> Sticky(customDisplay ?: "ctrl", { it.ctrl }, popup)
            "ALT" -> Sticky(customDisplay ?: "alt", { it.alt }, popup)
            "SHIFT" -> Sticky(customDisplay ?: "shift", { it.shift }, popup)
            else -> {
                val stroke = resolveStroke(name) ?: return null
                val arrow = when (upper) { "UP" -> 0f; "DOWN" -> 180f; "LEFT" -> -90f; "RIGHT" -> 90f; else -> null }
                Plain(
                    label, stroke.code ?: 0,
                    repeats = upper in repeatNames && stroke.code != null,
                    arrow = arrow, held = stroke.held.singleOrNull(),
                    popup = popup,
                    actions = if (stroke.code == null || stroke.held.size > 1) listOf(stroke) else null,
                )
            }
        }
    }

    private fun parsePopup(value: Any?): Popup? {
        val obj = value as? JSONObject
        val display = obj?.optString("display")?.takeIf { it.isNotEmpty() }
        val macro = obj?.optString("macro")?.takeIf { it.isNotEmpty() }
        val key = obj?.optString("key")?.takeIf { it.isNotEmpty() } ?: value as? String
        val strokes = when {
            macro != null -> parseMacro(macro)
            key != null -> listOfNotNull(resolveStroke(key))
            else -> emptyList()
        }
        if (strokes.isEmpty()) return null
        return Popup(display ?: macro ?: key ?: "", strokes)
    }

    /** CTRL f d = Ctrl+F followed by D; ALT SHIFT LEFT = one three-key chord. */
    private fun parseMacro(macro: String): List<ExtraStroke> {
        if (macro.equals("ESC [ Z", ignoreCase = true)) return listOf(ExtraStroke(code = Keys.KEY_TAB, held = listOf(Keys.KEY_LEFTSHIFT)))
        val pending = mutableListOf<Int>()
        val strokes = mutableListOf<ExtraStroke>()
        for (token in macro.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }) {
            val mod = when (token.uppercase()) {
                "CTRL", "CONTROL" -> Keys.KEY_LEFTCTRL
                "ALT" -> Keys.KEY_LEFTALT
                "SHIFT" -> Keys.KEY_LEFTSHIFT
                else -> null
            }
            if (mod != null) { if (mod !in pending) pending.add(mod); continue }
            val stroke = resolveStroke(token) ?: return emptyList()
            strokes += stroke.copy(held = (pending + stroke.held).distinct())
            pending.clear()
        }
        return if (pending.isEmpty()) strokes else emptyList()
    }

    private fun resolveStroke(raw: String): ExtraStroke? {
        val upper = raw.uppercase().removePrefix("KEY_")
        val code = when (upper) {
            "ESC", "ESCAPE" -> Keys.KEY_ESC
            "TAB" -> Keys.KEY_TAB
            "HOME" -> Keys.KEY_HOME
            "END" -> Keys.KEY_END
            "UP" -> Keys.KEY_UP
            "DOWN" -> Keys.KEY_DOWN
            "LEFT" -> Keys.KEY_LEFT
            "RIGHT" -> Keys.KEY_RIGHT
            "PGUP", "PAGEUP", "PAGE_UP" -> Keys.KEY_PAGEUP
            "PGDN", "PAGEDOWN", "PAGE_DOWN" -> Keys.KEY_PAGEDOWN
            "ENTER", "RETURN" -> Keys.KEY_ENTER
            "BKSP", "BACKSPACE" -> Keys.KEY_BACKSPACE
            "DEL", "DELETE" -> 111
            "INS", "INSERT" -> Keys.KEY_INSERT
            "SPACE" -> 57 /* KEY_SPACE */
            "F11" -> 87
            "F12" -> 88
            else -> {
                val n = Regex("""F(\d+)""").matchEntire(upper)?.groupValues?.get(1)?.toIntOrNull()
                if (n != null && n in 1..10) 58 + n else null
            }
        }
        if (code != null) return ExtraStroke(code = code)
        if (upper == "COPY") return ExtraStroke(Keys.KEY_INSERT, listOf(Keys.KEY_LEFTCTRL))
        if (upper == "PASTE") return ExtraStroke(Keys.KEY_INSERT, listOf(Keys.KEY_LEFTSHIFT))
        if (upper == "KEYBOARD") return null // A launcher keyboard-toggle action isn't a Wayland key.
        if (raw.length == 1) {
            val (key, shift) = Keys.forChar(raw[0]) ?: return null
            return ExtraStroke(key, if (shift) listOf(Keys.KEY_LEFTSHIFT) else emptyList())
        }
        // Termux accepts multi-character literal key text, in addition to special key names.
        return ExtraStroke(text = raw)
    }
}

/** Watches both sources; an explicit launcher layout overrides the guest Termux layout. */
class ExtraKeysStore(
    private val context: Context,
    private val proot: Proot? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watchingJob: Job? = null
    private val primary = LauncherData.extraKeysFile(context)
    private val termux = proot?.let { File(File(it.home, ".termux"), "termux.properties") }

    init { reload() }

    private fun reload() {
        val primaryText = runCatching { primary.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        val termuxText = runCatching { termux?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        val selected = when {
            ExtraKeysConfig.hasLayoutProperty(primaryText) -> primaryText
            ExtraKeysConfig.hasLayoutProperty(termuxText) -> termuxText
            else -> null
        }
        val layout = if (selected == null) DefaultLayout else ExtraKeysConfig.parseOrNull(selected)
        if (layout != null) ExtraKeysState.layout = layout
    }

    fun start() {
        if (watchingJob != null) return
        watchingJob = scope.launch {
            launch { watchFile(primary).collect { reload() } }
            termux?.let { file -> launch { watchFile(file).collect { reload() } } }
        }
    }
}

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
        for (row in ExtraKeysState.layout) {
            Row(Modifier.fillMaxWidth().height(RowHeight)) {
                for (key in row) {
                    val cap = Modifier.weight(1f).fillMaxHeight().padding(KeyGap)
                    when (key) {
                        is Plain -> PlainKey(key, bridge, cap)
                        is Sticky -> StickyKey(key, key.modifier(bridge), bridge, cap)
                    }
                }
            }
        }
    }
}

private fun modifierGlyphs(bridge: KeyboardBridge, held: Int? = null): String = buildString {
    if (bridge.ctrl.latch != Latch.Off || held == Keys.KEY_LEFTCTRL) append("⌃")
    if (bridge.alt.latch != Latch.Off || held == Keys.KEY_LEFTALT) append("⌥")
    if (bridge.shift.latch != Latch.Off || held == Keys.KEY_LEFTSHIFT) append("⇧")
}

private fun sendStrokes(bridge: KeyboardBridge, strokes: List<ExtraStroke>) {
    for (stroke in strokes) {
        if (stroke.code != null) bridge.chord(stroke.code, stroke.held)
        else stroke.text?.let(bridge::type)
    }
}

private fun sendPlain(bridge: KeyboardBridge, key: Plain) {
    val actions = key.actions
    if (actions != null) sendStrokes(bridge, actions)
    else bridge.tap(key.code, key.held)
}

@Composable
private fun PlainKey(key: Plain, bridge: KeyboardBridge, modifier: Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    var pressed by remember(key) { mutableStateOf(false) }
    var popupSelected by remember(key) { mutableStateOf(false) }
    val modifiers = modifierGlyphs(bridge, key.held)

    KeyCap(
        modifier.pointerInput(key) {
            val threshold = with(density) { 16.dp.toPx() }
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    popupSelected = false
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val repeatJob = if (key.popup == null && key.repeats) {
                        launch {
                            sendPlain(bridge, key)
                            delay(RepeatDelay)
                            while (true) {
                                sendPlain(bridge, key)
                                delay(RepeatInterval)
                            }
                        }
                    } else null
                    if (key.popup == null && !key.repeats) sendPlain(bridge, key)
                    var dragY = 0f
                    var released = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = !change.isConsumed
                                if (released) change.consume()
                                break
                            }
                            if (change.isConsumed) break
                            dragY += change.position.y - change.previousPosition.y
                            if (key.popup != null && dragY < -threshold && !popupSelected) {
                                popupSelected = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            } else if (popupSelected && dragY >= -threshold) {
                                popupSelected = false
                            }
                        }
                        if (released && key.popup != null) {
                            if (popupSelected) sendStrokes(bridge, key.popup.strokes)
                            else sendPlain(bridge, key)
                        }
                    } finally {
                        repeatJob?.cancel()
                        pressed = false
                        popupSelected = false
                    }
                }
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val popup = key.popup
            val showPopup = popupSelected && popup != null
            val display = if (showPopup) popup!!.display else modifiers + key.label
            if (showPopup || modifiers.isNotEmpty() || key.arrow == null) {
                BasicText(
                    display,
                    style = Mono.copy(color = color, fontSize = if (showPopup || modifiers.isNotEmpty()) 10.sp else Mono.fontSize),
                    maxLines = 1,
                )
            } else {
                Image(Icons.Chevron, key.label, Modifier.size(20.dp).rotate(key.arrow!!), colorFilter = ColorFilter.tint(color))
            }
            if (popup != null && !showPopup) {
                BasicText(
                    "↗" + popup.display,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp),
                    style = Mono.copy(color = if (pressed) Palette.background else Palette.accent, fontSize = 8.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StickyKey(key: Sticky, sticky: StickyModifier, bridge: KeyboardBridge, modifier: Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val latch = sticky.latch
    var popupSelected by remember(key) { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(sticky, key) {
            val threshold = with(density) { 16.dp.toPx() }
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    popupSelected = false
                    var locked = false
                    val hold = launch {
                        delay(RepeatDelay)
                        sticky.latch = Latch.Locked
                        locked = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    var dragY = 0f
                    var released = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = !change.isConsumed
                                if (released) change.consume()
                                break
                            }
                            if (change.isConsumed) break
                            dragY += change.position.y - change.previousPosition.y
                            if (key.popup != null && !locked && dragY < -threshold && !popupSelected) {
                                hold.cancel()
                                popupSelected = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            } else if (popupSelected && dragY >= -threshold) {
                                popupSelected = false
                            }
                        }
                        if (released) {
                            if (popupSelected && key.popup != null) sendStrokes(bridge = bridge, strokes = key.popup.strokes)
                            else if (!locked) sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
                        }
                    } finally {
                        hold.cancel()
                        popupSelected = false
                    }
                }
            }
        },
        fill = if (popupSelected || latch == Latch.Locked) Palette.accent else Palette.surface,
        outline = if (latch == Latch.Once) Palette.accent else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val color = if (latch == Latch.Locked || popupSelected) Palette.background else if (latch == Latch.Once) Palette.accent else Palette.foreground
            BasicText(if (popupSelected) key.popup?.display ?: key.label else key.label, style = Mono.copy(color = color), maxLines = 1)
            if (latch != Latch.Off && !popupSelected) {
                BasicText(if (latch == Latch.Locked) "LOCK" else "NEXT",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
                    style = Mono.copy(color = color, fontSize = 7.sp))
            }
            if (key.popup != null && !popupSelected) {
                BasicText("↗" + key.popup.display, modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp),
                    style = Mono.copy(color = Palette.accent, fontSize = 8.sp), maxLines = 1)
            }
        }
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
