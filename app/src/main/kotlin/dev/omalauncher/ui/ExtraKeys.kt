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

/** The bar's full height, gap included; dynamically adapts to the current layout's row count. */
val ExtraKeysHeight: Dp
    get() = RowHeight * ExtraKeysState.layout.size + (FrameWidth + KeyGap) * 2 + Gap

sealed interface ExtraKey

/**
 * A popup key action triggered by swiping up on a keycap.
 */
data class Popup(
    val display: String,
    val code: Int = 0,
    val held: Int? = null,
    val text: String? = null,
)

/** A key that sends [code], with [held] down around it; drawn as [label], or as a chevron turned [arrow] degrees from up. */
data class Plain(
    val label: String,
    val code: Int,
    val repeats: Boolean = false,
    val arrow: Float? = null,
    val held: Int? = null,
    val popup: Popup? = null,
) : ExtraKey

data class Sticky(
    val label: String,
    val modifier: (KeyboardBridge) -> StickyModifier,
) : ExtraKey

/** Default 8-column layout matching upstream Omalauncher. */
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

/** Reactive singleton state holding the active extra-keys layout. */
object ExtraKeysState {
    var layout by mutableStateOf(DefaultLayout)
}

/**
 * Parses extra-keys configurations from Termux properties or JSON files.
 */
object ExtraKeysConfig {
    const val DEFAULT_CONFIG_TEXT = """# Omalauncher extra keys configuration (compatible with Termux extra-keys)
#
# Custom 7-column layout with swipe-up (popup) actions:
extra-keys = [ \
  ['ESC', '/', '-', {key: HOME, popup: {macro: "CTRL A", display: "⇤ FIRST"}}, 'UP', {key: END, popup: {macro: "CTRL E", display: "LAST ⇥"}}, 'PGUP'], \
  [{key: TAB, popup: {macro: "ESC [ Z", display: "⇧TAB"}}, \
   'CTRL', \
   'ALT', \
   {key: LEFT, popup: {macro: "CTRL LEFT", display: "WORD ←"}}, \
   'DOWN', \
   {key: RIGHT, popup: {macro: "CTRL RIGHT", display: "WORD →"}}, \
   {key: PGDN, popup: {macro: "CTRL j", display: "⇧ENTER"}} \
  ] \
]
"""

    fun parse(text: String): List<List<ExtraKey>> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DefaultLayout

        val arrayText = extractArrayString(trimmed) ?: return DefaultLayout
        val cleaned = normalizeJson(arrayText)

        val rows = mutableListOf<List<ExtraKey>>()
        try {
            val root = JSONArray(cleaned)
            for (r in 0 until root.length()) {
                val rowArray = root.optJSONArray(r) ?: continue
                val rowKeys = mutableListOf<ExtraKey>()
                for (k in 0 until rowArray.length()) {
                    val item = rowArray.get(k)
                    val key =
                        when (item) {
                            is String -> parseKeySpec(item)
                            is JSONObject -> parseKeyObject(item)
                            else -> null
                        }
                    if (key != null) rowKeys.add(key)
                }
                if (rowKeys.isNotEmpty()) rows.add(rowKeys)
            }
        } catch (e: Exception) {
            Log.w("ExtraKeys", "Failed to parse extra-keys configuration", e)
            return DefaultLayout
        }

        return if (rows.isNotEmpty()) rows else DefaultLayout
    }

    private fun extractArrayString(raw: String): String? {
        val regex = Regex("""extra-keys\s*=\s*(\[.*)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(raw)
        val content = match?.groupValues?.get(1)?.trim() ?: raw
        if (!content.startsWith("[")) return null

        var depth = 0
        var inQuotes = false
        var quoteChar = '"'
        var endIndex = -1

        for (i in content.indices) {
            val c = content[i]
            if (inQuotes) {
                if (c == quoteChar && (i == 0 || content[i - 1] != '\\')) {
                    inQuotes = false
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true
                    quoteChar = c
                } else if (c == '[') {
                    depth++
                } else if (c == ']') {
                    depth--
                    if (depth == 0) {
                        endIndex = i + 1
                        break
                    }
                }
            }
        }

        return if (endIndex > 0) content.substring(0, endIndex) else content
    }

    private fun normalizeJson(raw: String): String {
        var s = raw.replace(Regex("""\\\s*\n"""), "\n")
        s = s.replace(Regex("""(?m)^[ \t]*[#//].*$"""), "")
        s = s.replace(Regex("""'([^']*)'"""), "\"$1\"")
        s = s.replace(Regex("""([{\s,])([a-zA-Z0-9_-]+)\s*:"""), "$1\"$2\":")
        s = s.replace(Regex(""":\s*([a-zA-Z0-9_-]+)\s*([,}])"""), ": \"$1\"$2")
        return s.trim()
    }

    private fun parseKeyObject(obj: JSONObject): ExtraKey? {
        val keyName =
            obj.optString("key").takeIf { it.isNotEmpty() }
                ?: obj.optString("macro").takeIf { it.isNotEmpty() }
                ?: return null
        val display = obj.optString("display").takeIf { it.isNotEmpty() }
        val popup = parsePopup(obj.opt("popup"))
        return parseKeySpec(keyName, display, popup)
    }

    fun parseKeySpec(
        rawName: String,
        customDisplay: String? = null,
        popup: Popup? = null,
    ): ExtraKey? {
        val name = rawName.trim()
        val upper = name.uppercase()
        val disp = customDisplay ?: name.lowercase()

        return when (upper) {
            "CTRL", "CONTROL" -> Sticky(customDisplay ?: "ctrl") { it.ctrl }
            "ALT" -> Sticky(customDisplay ?: "alt") { it.alt }
            "SHIFT" -> Sticky(customDisplay ?: "shift") { it.shift }
            "ESC", "ESCAPE" -> Plain(disp, Keys.KEY_ESC, popup = popup)
            "TAB" -> Plain(disp, Keys.KEY_TAB, popup = popup)
            "HOME" -> Plain(disp, Keys.KEY_HOME, popup = popup)
            "END" -> Plain(disp, Keys.KEY_END, popup = popup)
            "UP" -> Plain(disp, Keys.KEY_UP, repeats = true, arrow = 0f, popup = popup)
            "DOWN" -> Plain(disp, Keys.KEY_DOWN, repeats = true, arrow = 180f, popup = popup)
            "LEFT" -> Plain(disp, Keys.KEY_LEFT, repeats = true, arrow = -90f, popup = popup)
            "RIGHT" -> Plain(disp, Keys.KEY_RIGHT, repeats = true, arrow = 90f, popup = popup)
            "PGUP", "PAGEUP", "PAGE_UP" -> Plain(disp, Keys.KEY_PAGEUP, repeats = true, popup = popup)
            "PGDN", "PAGEDOWN", "PAGE_DOWN" -> Plain(disp, Keys.KEY_PAGEDOWN, repeats = true, popup = popup)
            "ENTER", "RETURN" -> Plain(disp, Keys.KEY_ENTER, popup = popup)
            "BKSP", "BACKSPACE" -> Plain(disp, Keys.KEY_BACKSPACE, repeats = true, popup = popup)
            "DEL", "DELETE" -> Plain(disp, 111 /* KEY_FORWARD_DEL */, repeats = true, popup = popup)
            "INS", "INSERT" -> Plain(disp, Keys.KEY_INSERT, popup = popup)
            "COPY" -> Plain(disp, Keys.KEY_INSERT, held = Keys.KEY_LEFTCTRL, popup = popup)
            "PASTE" -> Plain(disp, Keys.KEY_INSERT, held = Keys.KEY_LEFTSHIFT, popup = popup)
            else -> {
                val fMatch = Regex("""F(\d+)""").matchEntire(upper)
                if (fMatch != null) {
                    val fNum = fMatch.groupValues[1].toIntOrNull() ?: 0
                    val fCode =
                        when (fNum) {
                            in 1..10 -> 58 + fNum
                            11 -> 87
                            12 -> 88
                            else -> null
                        }
                    if (fCode != null) return Plain(disp, fCode, popup = popup)
                }
                if (name.length == 1) {
                    val c = name[0]
                    val mapped = Keys.forChar(c)
                    if (mapped != null) {
                        val (code, shift) = mapped
                        return Plain(disp, code, held = if (shift) Keys.KEY_LEFTSHIFT else null, popup = popup)
                    }
                }
                null
            }
        }
    }

    private fun parsePopup(item: Any?): Popup? {
        if (item == null) return null
        var keyName: String? = null
        var macro: String? = null
        var display: String? = null

        when (item) {
            is String -> {
                if (item.contains(" ") || item.contains("+")) {
                    macro = item
                } else {
                    keyName = item
                }
            }
            is JSONObject -> {
                macro = item.optString("macro").takeIf { it.isNotEmpty() }
                keyName = item.optString("key").takeIf { it.isNotEmpty() }
                display = item.optString("display").takeIf { it.isNotEmpty() }
            }
            else -> return null
        }

        val badge = resolveBadge(display, macro, keyName)

        if (macro != null) {
            val trimmedMacro = macro.trim()
            if (trimmedMacro.equals("ESC [ Z", ignoreCase = true)) {
                return Popup(display = badge, code = Keys.KEY_TAB, held = Keys.KEY_LEFTSHIFT)
            }
            val parts = trimmedMacro.split(Regex("""[\s+]+"""))
            var held: Int? = null
            var keyToken: String? = null
            for (p in parts) {
                when (p.uppercase()) {
                    "CTRL", "CONTROL" -> held = Keys.KEY_LEFTCTRL
                    "ALT" -> held = Keys.KEY_LEFTALT
                    "SHIFT" -> held = Keys.KEY_LEFTSHIFT
                    else -> keyToken = p
                }
            }
            if (keyToken != null) {
                val code = resolveKeyCode(keyToken)
                if (code != null) return Popup(display = badge, code = code, held = held)
            }
            return Popup(display = badge, code = 0, text = macro)
        }

        if (keyName != null) {
            val code = resolveKeyCode(keyName)
            if (code != null) return Popup(display = badge, code = code)
            if (keyName.length == 1) {
                val mapped = Keys.forChar(keyName[0])
                if (mapped != null) {
                    return Popup(display = badge, code = mapped.first, held = if (mapped.second) Keys.KEY_LEFTSHIFT else null)
                }
            }
            return Popup(display = badge, code = 0, text = keyName)
        }

        return null
    }

    private fun resolveKeyCode(token: String): Int? {
        val upper = token.uppercase()
        return when (upper) {
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
            else -> if (token.length == 1) Keys.forChar(token[0].lowercaseChar())?.first else null
        }
    }

    private fun resolveBadge(
        display: String?,
        macro: String?,
        keyName: String?,
    ): String {
        if (!display.isNullOrBlank()) {
            val d = display.trim()
            return when {
                d == "⇤ FIRST" -> "⇤"
                d == "LAST ⇥" -> "⇥"
                d == "WORD ←" -> "W←"
                d == "WORD →" -> "W→"
                d == "⇧ENTER" -> "⇧⏎"
                d.length <= 4 -> d
                d.contains(" ") -> {
                    val parts = d.split(" ")
                    parts.firstOrNull { it.any { c -> c.code > 127 } } ?: parts[0]
                }
                else -> d.take(3)
            }
        }
        if (macro != null) {
            val parts = macro.trim().split(Regex("""[\s+]+"""))
            val hasCtrl = parts.any { it.equals("CTRL", true) }
            val hasAlt = parts.any { it.equals("ALT", true) }
            val key = parts.lastOrNull { !it.equals("CTRL", true) && !it.equals("ALT", true) && !it.equals("SHIFT", true) }
            val arrow =
                when (key?.uppercase()) {
                    "LEFT" -> "←"
                    "RIGHT" -> "→"
                    "UP" -> "↑"
                    "DOWN" -> "↓"
                    else -> key?.uppercase() ?: ""
                }
            return if (hasCtrl) "^$arrow" else if (hasAlt) "M-$arrow" else arrow
        }
        return keyName?.take(3) ?: ""
    }
}

/**
 * Manages configuration watching and updating for extra-keys.
 */
class ExtraKeysStore(
    private val context: Context,
    private val proot: Proot? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watchingJob: Job? = null

    init {
        val file = activeConfigFile()
        if (file != null && file.isFile) {
            val text = runCatching { file.readText() }.getOrNull().orEmpty()
            if (text.isNotBlank()) {
                val parsed = ExtraKeysConfig.parse(text)
                if (parsed.isNotEmpty()) ExtraKeysState.layout = parsed
            }
        }
    }

    private fun candidateFiles(): List<File> =
        listOfNotNull(
            File(LauncherData.styleDir(context), FILE_NAME),
            File(LauncherData.styleDir(context), JSON_NAME),
            proot?.let { File(it.launcherConfig, "style/$FILE_NAME") },
            proot?.let { File(it.launcherConfig, FILE_NAME) },
            proot?.let { File(it.launcherConfig, JSON_NAME) },
            proot?.let { File(File(it.home, ".termux"), TERMUX_FILE) },
        )

    private fun activeConfigFile(): File? = candidateFiles().firstOrNull { it.isFile }

    fun start() {
        if (watchingJob != null) return
        val primary = File(LauncherData.styleDir(context), FILE_NAME)
        val termux = proot?.let { File(File(it.home, ".termux"), TERMUX_FILE) }

        watchingJob =
            scope.launch {
                launch {
                    watchFile(primary).collect { text ->
                        if (text.isNotBlank()) {
                            val parsed = ExtraKeysConfig.parse(text)
                            if (parsed.isNotEmpty()) ExtraKeysState.layout = parsed
                        } else {
                            val fallback = termux?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                            ExtraKeysState.layout =
                                fallback?.takeIf { it.isNotBlank() }?.let { ExtraKeysConfig.parse(it) } ?: DefaultLayout
                        }
                    }
                }
                if (termux != null) {
                    launch {
                        watchFile(termux).collect { text ->
                            if (!primary.isFile && text.isNotBlank()) {
                                val parsed = ExtraKeysConfig.parse(text)
                                if (parsed.isNotEmpty()) ExtraKeysState.layout = parsed
                            }
                        }
                    }
                }
            }
    }

    companion object {
        const val FILE_NAME = "extra-keys.properties"
        const val JSON_NAME = "extra-keys.json"
        const val TERMUX_FILE = "termux.properties"
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

                    val repeatJob =
                        if (key.repeats && key.popup == null) {
                            launch {
                                bridge.tap(key.code, key.held)
                                delay(RepeatDelay)
                                while (true) {
                                    bridge.tap(key.code, key.held)
                                    delay(RepeatInterval)
                                }
                            }
                        } else {
                            null
                        }

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
                        if (key.popup.code != 0) {
                            bridge.tap(key.popup.code, key.popup.held)
                        } else if (!key.popup.text.isNullOrEmpty()) {
                            bridge.type(key.popup.text)
                        }
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
            if (key.popup != null) {
                BasicText(
                    text = key.popup.display,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 3.dp),
                    style =
                        Mono.copy(
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
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    hold.cancel()
                    if (!locked) sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
                }
            }
        },
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
