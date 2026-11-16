package app.tennyo.mobile.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class TerminalKeyBarSettingsTest {
    @Test
    fun validDraftAddsAnEnabledHotkey() {
        val settings = TerminalKeyBarSettings()

        val hotkey = settings.validHotkey(characterDraft("d", ctrl = true, name = "EOF"))
        val updated = settings.withHotkey(hotkey)

        assertEquals(
            listOf(CustomTerminalHotkey(TerminalKeyChord('d', TerminalModifiers(ctrl = true)), "EOF", enabled = true)),
            updated.customHotkeys,
        )
    }

    @Test
    fun hotkeysCanBeDisabledAndRemovedById() {
        val ctrlD = TerminalKeyChord('d', TerminalModifiers(ctrl = true))
        val ctrlE = TerminalKeyChord('e', TerminalModifiers(ctrl = true))
        val settings = TerminalKeyBarSettings(
            customHotkeys = listOf(CustomTerminalHotkey(ctrlD, "EOF"), CustomTerminalHotkey(ctrlE, "End")),
        )

        val disabled = settings.withHotkeyEnabled(ctrlD.id, enabled = false)
        val removed = disabled.withoutHotkey(ctrlE.id)

        assertEquals(listOf(CustomTerminalHotkey(ctrlD, "EOF", enabled = false)), removed.customHotkeys)
        assertEquals(settings, disabled.withHotkeyEnabled(ctrlD.id, enabled = true))
    }

    @Test
    fun builtInKeysCanBeHiddenAndShown() {
        val hidden = TerminalKeyBarSettings().withBuiltInKeyVisible(ToggleableTerminalKey.PageDown, visible = false)

        assertEquals(setOf(ToggleableTerminalKey.PageDown), hidden.hiddenBuiltInKeys)
        assertEquals(TerminalKeyBarSettings(), hidden.withBuiltInKeyVisible(ToggleableTerminalKey.PageDown, true))
    }

    @Test
    fun missingKeyIsReportedForBothKeyKinds() {
        val settings = TerminalKeyBarSettings()

        assertError(TerminalHotkeyDraftError.MissingKey, settings, characterDraft(""))
        assertError(
            TerminalHotkeyDraftError.MissingKey,
            settings,
            TerminalHotkeyDraft(keyKind = TerminalHotkeyKeyKind.Named),
        )
    }

    @Test
    fun charactersOutsidePrintableAsciiAreRejected() {
        val settings = TerminalKeyBarSettings()

        assertError(TerminalHotkeyDraftError.InvalidCharacter, settings, characterDraft("\t"))
        assertError(TerminalHotkeyDraftError.InvalidCharacter, settings, characterDraft("é"))
    }

    @Test
    fun ctrlOnACharacterWithoutAControlCodeIsRejected() {
        assertError(
            TerminalHotkeyDraftError.UnsupportedCtrlCharacter,
            TerminalKeyBarSettings(),
            characterDraft("1", ctrl = true),
        )
    }

    @Test
    fun chordsThatSendABuiltInKeysBytesAreRejected() {
        val pageUpHidden = TerminalKeyBarSettings(hiddenBuiltInKeys = setOf(ToggleableTerminalKey.PageUp))

        listOf(
            characterDraft("c", ctrl = true),
            characterDraft("c", ctrl = true, shift = true),
            namedDraft(NamedTerminalKey.Escape),
            namedDraft(NamedTerminalKey.Tab, TerminalModifiers(ctrl = true, shift = true)),
            namedDraft(NamedTerminalKey.PageUp),
        ).forEach { draft ->
            assertError(TerminalHotkeyDraftError.AlreadyOnKeyBar, pageUpHidden, draft)
        }
    }

    @Test
    fun chordsThatSendAnExistingHotkeysBytesAreDuplicates() {
        val settings = TerminalKeyBarSettings().withHotkey(
            TerminalKeyBarSettings().validHotkey(characterDraft("d", ctrl = true)),
        )

        assertError(TerminalHotkeyDraftError.Duplicate, settings, characterDraft("d", ctrl = true))
        assertError(TerminalHotkeyDraftError.Duplicate, settings, characterDraft("d", ctrl = true, shift = true))
    }

    @Test
    fun theLimitIsReachedAtTwentyFourHotkeys() {
        val settings = TerminalKeyBarSettings(customHotkeys = hotkeys(MAX_CUSTOM_TERMINAL_HOTKEYS))

        assertError(TerminalHotkeyDraftError.LimitReached, settings, namedDraft(NamedTerminalKey.F12))
        assertSame(settings, settings.withHotkey(CustomTerminalHotkey(TerminalKeyChord(NamedTerminalKey.F12), "")))
    }

    @Test
    fun draftsAreNormalizedBeforeValidation() {
        val settings = TerminalKeyBarSettings()

        assertEquals(TerminalKeyChord('|'), settings.validHotkey(characterDraft("|", shift = true)).chord)
        assertEquals(
            TerminalKeyChord('a', TerminalModifiers(shift = true)),
            settings.validHotkey(characterDraft("A")).chord,
        )
        assertEquals(
            TerminalKeyChord('d', TerminalModifiers(ctrl = true)),
            settings.validHotkey(characterDraft("d", ctrl = true, shift = true)).chord,
        )
    }

    @Test
    fun characterInputKeepsOnlyTheLastCodePoint() {
        assertEquals("b", TerminalHotkeyDraft().withCharacterInput("ab").character)

        val emojiDraft = TerminalHotkeyDraft().withCharacterInput("a😀")
        assertEquals("😀", emojiDraft.character)
        assertError(TerminalHotkeyDraftError.InvalidCharacter, TerminalKeyBarSettings(), emojiDraft)
    }

    @Test
    fun namesAreTrimmedStrippedAndCapped() {
        val settings = TerminalKeyBarSettings()

        assertEquals("EOF", settings.validHotkey(characterDraft("d", ctrl = true, name = "  E\u0000O\nF  ")).name)
        assertEquals(
            "x".repeat(MAX_TERMINAL_HOTKEY_NAME_LENGTH),
            settings.validHotkey(characterDraft("d", ctrl = true, name = "x".repeat(40))).name,
        )
    }

    @Test
    fun addingAStaleDuplicateLeavesSettingsUnchanged() {
        val empty = TerminalKeyBarSettings()
        val first = empty.validHotkey(characterDraft("d", ctrl = true, name = "first"))
        val stale = empty.validHotkey(characterDraft("d", ctrl = true, name = "stale"))
        val settings = empty.withHotkey(first)

        assertSame(settings, settings.withHotkey(stale))
    }

    @Test
    fun sanitizingDropsCorruptEntriesAndKeepsValidOnesInOrder() {
        val ctrlD = CustomTerminalHotkey(TerminalKeyChord('d', TerminalModifiers(ctrl = true)), "EOF")
        val f5 = CustomTerminalHotkey(TerminalKeyChord(NamedTerminalKey.F5), "Refresh", enabled = false)
        val settings = TerminalKeyBarSettings(
            customHotkeys = listOf(
                ctrlD,
                CustomTerminalHotkey(TerminalKeyChord('c', TerminalModifiers(ctrl = true)), "Reserved"),
                CustomTerminalHotkey(TerminalKeyChord('é'), "Accent"),
                CustomTerminalHotkey(TerminalKeyChord('1', TerminalModifiers(ctrl = true)), "Unsupported"),
                ctrlD.copy(name = "Duplicate"),
                f5,
            ),
        )

        assertEquals(listOf(ctrlD, f5), settings.sanitized().customHotkeys)
    }

    private fun TerminalKeyBarSettings.validHotkey(draft: TerminalHotkeyDraft): CustomTerminalHotkey =
        assertIs<TerminalHotkeyDraftResult.Valid>(validate(draft)).hotkey

    private fun assertError(
        expected: TerminalHotkeyDraftError,
        settings: TerminalKeyBarSettings,
        draft: TerminalHotkeyDraft,
    ) {
        assertEquals(TerminalHotkeyDraftResult.Invalid(expected), settings.validate(draft), "$draft")
    }

    private fun characterDraft(
        character: String,
        ctrl: Boolean = false,
        shift: Boolean = false,
        name: String = "",
    ) = TerminalHotkeyDraft(
        name = name,
        modifiers = TerminalModifiers(ctrl = ctrl, shift = shift),
        keyKind = TerminalHotkeyKeyKind.Character,
        character = character,
    )

    private fun namedDraft(key: NamedTerminalKey, modifiers: TerminalModifiers = TerminalModifiers()) =
        TerminalHotkeyDraft(modifiers = modifiers, keyKind = TerminalHotkeyKeyKind.Named, namedKey = key)
}
