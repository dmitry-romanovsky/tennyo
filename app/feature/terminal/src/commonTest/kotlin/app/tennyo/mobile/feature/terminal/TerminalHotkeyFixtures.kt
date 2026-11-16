package app.tennyo.mobile.feature.terminal

/** Distinct valid Alt+<character> hotkeys, starting at Alt+a; at most 30 fit before leaving printable ASCII. */
internal fun hotkeys(count: Int): List<CustomTerminalHotkey> =
    List(count) { index ->
        CustomTerminalHotkey(TerminalKeyChord('a' + index, TerminalModifiers(alt = true)), "hotkey $index")
    }
