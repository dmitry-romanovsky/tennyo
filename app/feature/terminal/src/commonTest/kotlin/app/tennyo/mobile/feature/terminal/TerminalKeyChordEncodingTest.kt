package app.tennyo.mobile.feature.terminal

import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_alt
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_control
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_page_up
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalKeyChordEncodingTest {
    /** The terminal byte sequences each named key encodes to under no modifier, Shift, Ctrl and Ctrl+Shift. */
    @Test
    fun encodingMatchesTerminalKeySequences() {
        // Expected bytes for: no modifiers, Shift, Ctrl, Ctrl+Shift.
        val expected = mapOf(
            NamedTerminalKey.Escape to listOf("$ESC", "$ESC", "$ESC", "$ESC"),
            NamedTerminalKey.Home to listOf("$ESC[H", "$ESC[1;2H", "$ESC[1;5H", "$ESC[1;6H"),
            NamedTerminalKey.ArrowUp to listOf("$ESC[A", "$ESC[1;2A", "$ESC[1;5A", "$ESC[1;6A"),
            NamedTerminalKey.End to listOf("$ESC[F", "$ESC[1;2F", "$ESC[1;5F", "$ESC[1;6F"),
            NamedTerminalKey.PageUp to listOf("$ESC[5~", "$ESC[5;2~", "$ESC[5;5~", "$ESC[5;6~"),
            NamedTerminalKey.Tab to listOf("\t", "$ESC[Z", "\t", "\t"),
            NamedTerminalKey.ArrowLeft to listOf("$ESC[D", "$ESC[1;2D", "$ESC[1;5D", "$ESC[1;6D"),
            NamedTerminalKey.ArrowDown to listOf("$ESC[B", "$ESC[1;2B", "$ESC[1;5B", "$ESC[1;6B"),
            NamedTerminalKey.ArrowRight to listOf("$ESC[C", "$ESC[1;2C", "$ESC[1;5C", "$ESC[1;6C"),
            NamedTerminalKey.PageDown to listOf("$ESC[6~", "$ESC[6;2~", "$ESC[6;5~", "$ESC[6;6~"),
        )
        val modifierSets = listOf(
            TerminalModifiers(),
            TerminalModifiers(shift = true),
            TerminalModifiers(ctrl = true),
            TerminalModifiers(ctrl = true, shift = true),
        )
        expected.forEach { (key, sequences) ->
            modifierSets.zip(sequences).forEach { (modifiers, bytes) ->
                assertEquals(bytes, TerminalKeyChord(key).encode(modifiers), "$key $modifiers")
            }
        }
    }

    @Test
    fun altIsPartOfTheXtermParameterOrAMetaPrefix() {
        assertEquals("${ESC}b", TerminalKeyChord('b', TerminalModifiers(alt = true)).encode())
        assertEquals("$ESC[1;3A", TerminalKeyChord(NamedTerminalKey.ArrowUp, TerminalModifiers(alt = true)).encode())
        assertEquals(
            "$ESC[5;8~",
            TerminalKeyChord(NamedTerminalKey.PageUp, TerminalModifiers(ctrl = true, alt = true, shift = true))
                .encode(),
        )
    }

    @Test
    fun functionKeysUseSs3OrTildeSequences() {
        assertEquals("${ESC}OP", TerminalKeyChord(NamedTerminalKey.F1).encode())
        assertEquals("$ESC[1;5P", TerminalKeyChord(NamedTerminalKey.F1, TerminalModifiers(ctrl = true)).encode())
        assertEquals("$ESC[15~", TerminalKeyChord(NamedTerminalKey.F5).encode())
        assertEquals("$ESC[24;2~", TerminalKeyChord(NamedTerminalKey.F12, TerminalModifiers(shift = true)).encode())
    }

    @Test
    fun controlKeysEncodeAsSingleBytes() {
        assertEquals("\r", TerminalKeyChord(NamedTerminalKey.Enter).encode())
        assertEquals("$ESC\r", TerminalKeyChord(NamedTerminalKey.Enter, TerminalModifiers(alt = true)).encode())
        assertEquals("\u007F", TerminalKeyChord(NamedTerminalKey.Backspace).encode())
        assertEquals("\b", TerminalKeyChord(NamedTerminalKey.Backspace, TerminalModifiers(ctrl = true)).encode())
        assertEquals("$ESC[Z", TerminalKeyChord(NamedTerminalKey.Tab, TerminalModifiers(shift = true)).encode())
        assertEquals("\u0003", TerminalKeyChord('c', TerminalModifiers(ctrl = true)).encode())
        assertEquals("\u0004", TerminalKeyChord('d', TerminalModifiers(ctrl = true)).encode())
        assertEquals("\u0000", TerminalKeyChord('@', TerminalModifiers(ctrl = true)).encode())
        assertEquals("\u0000", TerminalKeyChord(' ', TerminalModifiers(ctrl = true)).encode())
    }

    @Test
    fun escapeIgnoresShiftAndCtrl() {
        listOf(
            TerminalModifiers(),
            TerminalModifiers(shift = true),
            TerminalModifiers(ctrl = true),
            TerminalModifiers(ctrl = true, shift = true),
        ).forEach { modifiers ->
            assertEquals(ESC.toString(), TerminalKeyChord(NamedTerminalKey.Escape).encode(modifiers))
        }
        assertEquals(
            "\t",
            TerminalKeyChord(NamedTerminalKey.Tab, TerminalModifiers(ctrl = true, shift = true)).encode(),
        )
    }

    @Test
    fun latchedModifiersJoinTheChordModifiers() {
        val ctrlD = TerminalKeyChord('d', TerminalModifiers(ctrl = true))

        assertEquals("$ESC\u0004", ctrlD.encode(latched = TerminalModifiers(alt = true)))
    }

    @Test
    fun imeTextAppliesLatchedModifiers() {
        assertEquals("\u0001\u0002", "ab".toTerminalInput(TerminalModifiers(ctrl = true)))
        assertEquals("\u00011", "a1".toTerminalInput(TerminalModifiers(ctrl = true)))
        assertEquals("${ESC}a${ESC}b", "ab".toTerminalInput(TerminalModifiers(alt = true)))
        assertEquals("AB", "ab".toTerminalInput(TerminalModifiers(shift = true)))
        assertEquals("", "".toTerminalInput(TerminalModifiers(ctrl = true, alt = true, shift = true)))
    }

    @Test
    fun altPrefixesEscOncePerCodePoint() {
        val emoji = "\uD83D\uDE00"

        assertEquals("$ESC$emoji${ESC}x", "${emoji}x".toTerminalInput(TerminalModifiers(alt = true)))
    }

    @Test
    fun labelsJoinModifiersInFixedOrder() {
        val chord = TerminalKeyChord('d', TerminalModifiers(ctrl = true, alt = true, shift = true))

        assertEquals("Ctrl+Alt+Shift+D", chord.label())
        assertEquals("Ctrl+Space", TerminalKeyChord(' ', TerminalModifiers(ctrl = true)).label())
        assertEquals("PgUp", TerminalKeyChord(NamedTerminalKey.PageUp).label())
        assertEquals(
            listOf(
                SpokenKey.Named(Res.string.terminal_key_control),
                SpokenKey.Named(Res.string.terminal_key_alt),
                SpokenKey.Named(Res.string.terminal_key_page_up),
            ),
            TerminalKeyChord(NamedTerminalKey.PageUp, TerminalModifiers(ctrl = true, alt = true)).spokenKeys(),
        )
        assertEquals(listOf(SpokenKey.Literal("F5")), TerminalKeyChord(NamedTerminalKey.F5).spokenKeys())
    }

    @Test
    fun chordIdsNameModifiersAndKey() {
        assertEquals(
            "ctrl+alt+named:PageUp",
            TerminalKeyChord(NamedTerminalKey.PageUp, TerminalModifiers(ctrl = true, alt = true)).id,
        )
        assertEquals("ctrl+char:d", TerminalKeyChord('d', TerminalModifiers(ctrl = true)).id)
    }

    private companion object {
        const val ESC = '\u001B'
    }
}
