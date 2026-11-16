package app.tennyo.mobile.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalImeDeltaTest {
    @Test
    fun emptyToSingleCharacter() {
        val result = computeTerminalImeDelta("", "a")
        assertEquals(TerminalImeDeltaResult("a", "a"), result)
    }

    @Test
    fun repeatedSameCharacterProducesOneCharAtATime() {
        val step1 = computeTerminalImeDelta("", "a")
        assertEquals("a", step1.inputDelta)

        val step2 = computeTerminalImeDelta(step1.newFieldText, "aa")
        assertEquals("a", step2.inputDelta)
    }

    @Test
    fun repeatedDifferentCharacters() {
        val step1 = computeTerminalImeDelta("", "b")
        assertEquals("b", step1.inputDelta)

        val step2 = computeTerminalImeDelta(step1.newFieldText, "bb")
        assertEquals("b", step2.inputDelta)
    }

    @Test
    fun mixedCharactersAppend() {
        val step1 = computeTerminalImeDelta("", "h")
        assertEquals("h", step1.inputDelta)

        val step2 = computeTerminalImeDelta(step1.newFieldText, "he")
        assertEquals("e", step2.inputDelta)

        val step3 = computeTerminalImeDelta(step2.newFieldText, "hel")
        assertEquals("l", step3.inputDelta)
    }

    @Test
    fun multiCharAppendDelta() {
        // Paste or fast IME can produce multiple new chars at once
        val result = computeTerminalImeDelta("ab", "abcde")
        assertEquals("cde", result.inputDelta)
    }

    @Test
    fun nonAppendShrinkSendsNothing() {
        // IME backspace inside composing buffer: "ab" -> "a"
        val result = computeTerminalImeDelta("ab", "a")
        assertEquals("", result.inputDelta)
        assertEquals("a", result.newFieldText)
    }

    @Test
    fun nonAppendReplacementSendsNothing() {
        val result = computeTerminalImeDelta("foo", "bar")
        assertEquals("", result.inputDelta)
        assertEquals("bar", result.newFieldText)
    }

    @Test
    fun ctrlCConversionWorksOnSingleCharDelta() {
        // Simulate Ctrl+c producing a control byte from the delta "c"
        val delta = "c"
        val controlInput = delta.toTerminalInput(TerminalModifiers(ctrl = true))
        assertEquals("\u0003", controlInput)
    }

    @Test
    fun repeatedCtrlCSendsOneControlBytePerDelta() {
        // "" -> "c" -> "cc"  with Ctrl active should produce one \u0003 per step
        val step1 = computeTerminalImeDelta("", "c")
        assertEquals("c", step1.inputDelta)
        assertEquals("\u0003", step1.inputDelta.toTerminalInput(TerminalModifiers(ctrl = true)))

        val step2 = computeTerminalImeDelta(step1.newFieldText, "cc")
        assertEquals("c", step2.inputDelta)
        assertEquals("\u0003", step2.inputDelta.toTerminalInput(TerminalModifiers(ctrl = true)))
    }

    @Test
    fun shiftActiveConvertsOnlyDelta() {
        val step1 = computeTerminalImeDelta("", "a")
        assertEquals("A", step1.inputDelta.toTerminalInput(TerminalModifiers(shift = true)))

        val step2 = computeTerminalImeDelta(step1.newFieldText, "aa")
        assertEquals("A", step2.inputDelta.toTerminalInput(TerminalModifiers(shift = true)))
    }

    @Test
    fun rebaseKeepsShortTextUnchanged() {
        assertEquals("hello", rebaseTerminalImeField("hello", maxRetainedChars = 200))
    }

    @Test
    fun rebaseTruncatesLongTextToMaxRetained() {
        val longText = "a".repeat(300)
        val rebased = rebaseTerminalImeField(longText, maxRetainedChars = 200)
        assertEquals(200, rebased.length)
        assertEquals("a".repeat(200), rebased)
    }

    @Test
    fun rebaseAtBoundaryKeepsText() {
        val exact = "x".repeat(200)
        assertEquals(exact, rebaseTerminalImeField(exact, maxRetainedChars = 200))
    }

    @Test
    fun rebaseDoesNotResendText() {
        // After rebasing, a new append delta is computed from the rebased text.
        // The rebased text is a suffix, so no already-sent prefix is re-emitted.
        val longText = "a".repeat(250)
        val rebased = rebaseTerminalImeField(longText, maxRetainedChars = 200)
        assertEquals("a".repeat(200), rebased)

        // Next append: "b"
        val nextText = rebased + "b"
        val result = computeTerminalImeDelta(rebased, nextText)
        assertEquals("b", result.inputDelta)
    }

    @Test
    fun emptyToEmptySendsNothing() {
        val result = computeTerminalImeDelta("", "")
        assertEquals("", result.inputDelta)
        assertEquals("", result.newFieldText)
    }
}
