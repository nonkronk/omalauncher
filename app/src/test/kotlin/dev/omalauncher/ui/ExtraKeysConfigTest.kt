package dev.omalauncher.ui

import dev.omalauncher.wayland.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysConfigTest {
    @Test
    fun keepsOriginalLayoutWithoutOptIn() {
        assertFalse(ExtraKeysConfig.hasLayoutProperty(ExtraKeysConfig.DEFAULT_CONFIG_TEXT))
        assertEquals(DefaultLayout, ExtraKeysConfig.parse(""))
        assertEquals(DefaultLayout, ExtraKeysConfig.parse(ExtraKeysConfig.DEFAULT_CONFIG_TEXT))
        assertEquals(2, DefaultLayout.size)
        assertEquals(8, DefaultLayout[0].size)
        assertEquals(8, DefaultLayout[1].size)
        assertEquals("copy", (DefaultLayout[0].last() as Plain).label)
        assertEquals("paste", (DefaultLayout[1].last() as Plain).label)
    }

    @Test
    fun emptyMatrixDisablesAllKeysInsteadOfReinstatingDefaults() {
        assertTrue(ExtraKeysConfig.parse("extra-keys = []").isEmpty())
        assertTrue(ExtraKeysConfig.parse("[]").isEmpty())
        assertEquals(emptyList<List<ExtraKey>>(), ExtraKeysConfig.parseOrNull("extra-keys = []"))
    }

    @Test
    fun parsesTermuxStyleMultilineMatrixAndCustomLabels() {
        val config = """
            # extra-keys = [[PGDN]] is only an example
            extra-keys = [ \
              ['ESC', '/', '-', {key: HOME, popup: {macro: "CTRL A", display: "⇤ FIRST"}}, 'UP', {key: END, popup: {macro: "CTRL E", display: "LAST ⇥"}}, 'PGUP'], \
              [{key: TAB, popup: {macro: "ESC [ Z", display: "⇧TAB"}}, CTRL, ALT, \
               {key: LEFT, popup: {macro: "CTRL LEFT", display: "WORD ←"}}, 'DOWN', \
               {key: RIGHT, popup: {macro: "CTRL RIGHT", display: "WORD →"}}, 'PGDN'] \
            ]
        """.trimIndent()
        val layout = ExtraKeysConfig.parse(config)
        assertEquals(2, layout.size)
        assertEquals(7, layout[0].size)
        assertEquals(7, layout[1].size)
        assertEquals(Keys.KEY_SLASH, (layout[0][1] as Plain).code)
        assertEquals(Keys.KEY_HOME, (layout[0][3] as Plain).code)
        val homePopup = (layout[0][3] as Plain).popup!!
        assertEquals("⇤ FIRST", homePopup.display)
        assertEquals(listOf(Keys.KEY_LEFTCTRL), homePopup.strokes.single().held)
        assertEquals(Keys.forChar('A')!!.first, homePopup.strokes.single().code)
        assertEquals(listOf(Keys.KEY_LEFTSHIFT), (layout[1][0] as Plain).popup!!.strokes.single().held)
        assertTrue(layout[1][1] is Sticky)
        assertTrue(layout[1][2] is Sticky)
        assertEquals(Keys.KEY_LEFT, (layout[1][3] as Plain).popup!!.strokes.single().code)
    }

    @Test
    fun parsesActualTermuxSequentialMacroInsteadOfLastKeyOnly() {
        val layout = ExtraKeysConfig.parse(
            """extra-keys = [[{key: ESC, popup: {macro: "CTRL f d", display: "tmux exit"}}]]"""
        )
        val popup = (layout.single().single() as Plain).popup!!
        assertEquals("tmux exit", popup.display)
        assertEquals(2, popup.strokes.size)
        assertEquals(Keys.forChar('f')!!.first, popup.strokes[0].code)
        assertEquals(listOf(Keys.KEY_LEFTCTRL), popup.strokes[0].held)
        assertEquals(Keys.forChar('d')!!.first, popup.strokes[1].code)
        assertTrue(popup.strokes[1].held.isEmpty())
    }

    @Test
    fun parsesMultiModifierChordAndTopLevelMacro() {
        val layout = ExtraKeysConfig.parse(
            """extra-keys = [[{macro: "ALT SHIFT LEFT", display: "select"}, {key: TAB, popup: {macro: "CTRL f d"}}]]"""
        )
        val macro = layout.single()[0] as Plain
        assertEquals("select", macro.label)
        assertEquals(1, macro.actions!!.size)
        assertEquals(Keys.KEY_LEFT, macro.actions.single().code)
        assertEquals(listOf(Keys.KEY_LEFTALT, Keys.KEY_LEFTSHIFT), macro.actions.single().held)
        assertEquals(2, (layout.single()[1] as Plain).popup!!.strokes.size)
    }

    @Test
    fun supportsStrictJsonQuotedStringsAndKeyAliases() {
        val layout = ExtraKeysConfig.parse(
            """[["ESC", {"key":"KEY_LEFT","display":"←","popup":"HOME"}],["TAB","CTRL","SHIFT"] ]"""
        )
        assertEquals(2, layout.size)
        val left = layout[0][1] as Plain
        assertEquals(Keys.KEY_LEFT, left.code)
        assertEquals("←", left.label)
        assertEquals(Keys.KEY_HOME, left.popup!!.strokes.single().code)
        assertTrue(layout[1][1] is Sticky)
        assertTrue(layout[1][2] is Sticky)
    }

    @Test
    fun quotedTextIsNeverRewrittenByLooseJsonNormalizer() {
        val config = """extra-keys = [[{key: ESC, display: 'don\'t [stop], CTRL: "ok" #tag'}]]"""
        val layout = ExtraKeysConfig.parse(config)
        assertEquals("""don't [stop], CTRL: "ok" #tag""", (layout.single().single() as Plain).label)
    }

    @Test
    fun preservesShiftedCharactersAndFKeys() {
        val layout = ExtraKeysConfig.parse("""extra-keys = [['/', ':', 'F1', 'F12', 'COPY', 'PASTE']]""").single()
        assertEquals(Keys.KEY_SLASH, (layout[0] as Plain).code)
        assertEquals(listOf(Keys.KEY_LEFTSHIFT), (layout[1] as Plain).actions?.single()?.held
            ?: listOfNotNull((layout[1] as Plain).held))
        assertEquals(59, (layout[2] as Plain).code)
        assertEquals(88, (layout[3] as Plain).code)
        assertEquals(Keys.KEY_LEFTCTRL, (layout[4] as Plain).held)
        assertEquals(Keys.KEY_LEFTSHIFT, (layout[5] as Plain).held)
    }

    @Test
    fun invalidAndOversizedMatricesAreRejectedWithoutHidingExistingKeys() {
        assertNull(ExtraKeysConfig.parseOrNull("extra-keys = [[ESC"))
        assertNull(ExtraKeysConfig.parseOrNull("extra-keys = [[ESC, KEYBOARD]]"))
        assertNull(ExtraKeysConfig.parseOrNull("extra-keys = [[ESC], [TAB], [CTRL], [ALT]]"))
        val wide = (1..11).joinToString(",") { "ESC" }
        assertNull(ExtraKeysConfig.parseOrNull("extra-keys = [[$wide]]"))
        assertEquals(DefaultLayout, ExtraKeysConfig.parse("extra-keys = [[ESC"))
        assertNull(ExtraKeysConfig.parseOrNull("this is not valid"))
    }

    @Test
    fun ignoresCommentedOutConfigurationAndSupportsPopupOnModifiers() {
        val config = """
            # extra-keys = [[ESC]]
            extra-keys = [[{key: CTRL, popup: {macro: "CTRL f BKSP", display: "tmux back"}}, TAB]]
        """.trimIndent()
        val layout = ExtraKeysConfig.parse(config)
        assertEquals(2, layout.single().size)
        val sticky = layout.single()[0] as Sticky
        assertNotNull(sticky.popup)
        assertEquals(2, sticky.popup!!.strokes.size)
        assertEquals(Keys.KEY_BACKSPACE, sticky.popup!!.strokes[1].code)
    }
}
