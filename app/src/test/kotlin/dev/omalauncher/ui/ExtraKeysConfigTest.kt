package dev.omalauncher.ui

import dev.omalauncher.wayland.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysConfigTest {
    @Test
    fun parsesDefaultWhenEmpty() {
        assertEquals(DefaultLayout, ExtraKeysConfig.parse(""))
        assertEquals(DefaultLayout, ExtraKeysConfig.parse("   "))
    }

    @Test
    fun parsesTermuxPropertiesFormat() {
        val termuxConfig =
            """
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
            """.trimIndent()

        val layout = ExtraKeysConfig.parse(termuxConfig)
        assertEquals(2, layout.size)
        assertEquals(7, layout[0].size)
        assertEquals(7, layout[1].size)

        // Check Row 1 keys
        val esc = layout[0][0] as Plain
        assertEquals(Keys.KEY_ESC, esc.code)

        val slash = layout[0][1] as Plain
        assertEquals(Keys.KEY_SLASH, slash.code)

        val minus = layout[0][2] as Plain
        assertEquals(Keys.KEY_MINUS, minus.code)

        val home = layout[0][3] as Plain
        assertEquals(Keys.KEY_HOME, home.code)
        assertNotNull(home.popup)
        assertEquals("⇤", home.popup?.display)
        assertEquals(30 /* KEY_A */, home.popup?.code)
        assertEquals(Keys.KEY_LEFTCTRL, home.popup?.held)

        val up = layout[0][4] as Plain
        assertEquals(Keys.KEY_UP, up.code)
        assertTrue(up.repeats)
        assertEquals(0f, up.arrow)

        val end = layout[0][5] as Plain
        assertEquals(Keys.KEY_END, end.code)
        assertNotNull(end.popup)
        assertEquals("⇥", end.popup?.display)
        assertEquals(18 /* KEY_E */, end.popup?.code)
        assertEquals(Keys.KEY_LEFTCTRL, end.popup?.held)

        val pgup = layout[0][6] as Plain
        assertEquals(Keys.KEY_PAGEUP, pgup.code)
        assertTrue(pgup.repeats)

        // Check Row 2 keys
        val tab = layout[1][0] as Plain
        assertEquals(Keys.KEY_TAB, tab.code)
        assertNotNull(tab.popup)
        assertEquals("⇧TAB", tab.popup?.display)
        assertEquals(Keys.KEY_TAB, tab.popup?.code)
        assertEquals(Keys.KEY_LEFTSHIFT, tab.popup?.held)

        val ctrl = layout[1][1] as Sticky
        assertEquals("ctrl", ctrl.label)

        val alt = layout[1][2] as Sticky
        assertEquals("alt", alt.label)

        val left = layout[1][3] as Plain
        assertEquals(Keys.KEY_LEFT, left.code)
        assertTrue(left.repeats)
        assertEquals(-90f, left.arrow)
        assertNotNull(left.popup)
        assertEquals("W←", left.popup?.display)
        assertEquals(Keys.KEY_LEFT, left.popup?.code)
        assertEquals(Keys.KEY_LEFTCTRL, left.popup?.held)

        val down = layout[1][4] as Plain
        assertEquals(Keys.KEY_DOWN, down.code)
        assertTrue(down.repeats)
        assertEquals(180f, down.arrow)

        val right = layout[1][5] as Plain
        assertEquals(Keys.KEY_RIGHT, right.code)
        assertTrue(right.repeats)
        assertEquals(90f, right.arrow)
        assertNotNull(right.popup)
        assertEquals("W→", right.popup?.display)
        assertEquals(Keys.KEY_RIGHT, right.popup?.code)
        assertEquals(Keys.KEY_LEFTCTRL, right.popup?.held)

        val pgdn = layout[1][6] as Plain
        assertEquals(Keys.KEY_PAGEDOWN, pgdn.code)
        assertTrue(pgdn.repeats)
        assertNotNull(pgdn.popup)
        assertEquals("⇧⏎", pgdn.popup?.display)
        assertEquals(36 /* KEY_J */, pgdn.popup?.code)
        assertEquals(Keys.KEY_LEFTCTRL, pgdn.popup?.held)
    }

    @Test
    fun parsesRawJsonArray() {
        val json = """[["ESC", "/"], ["TAB", "CTRL"]]"""
        val layout = ExtraKeysConfig.parse(json)
        assertEquals(2, layout.size)
        assertEquals(2, layout[0].size)
        assertEquals(2, layout[1].size)

        assertEquals(Keys.KEY_ESC, (layout[0][0] as Plain).code)
        assertEquals(Keys.KEY_SLASH, (layout[0][1] as Plain).code)
        assertEquals(Keys.KEY_TAB, (layout[1][0] as Plain).code)
        assertTrue(layout[1][1] is Sticky)
    }

    @Test
    fun handlesBadSyntaxGracefully() {
        val garbage = "this is not valid json or properties at all {{{ 123"
        val layout = ExtraKeysConfig.parse(garbage)
        assertEquals(DefaultLayout, layout)
    }
}
