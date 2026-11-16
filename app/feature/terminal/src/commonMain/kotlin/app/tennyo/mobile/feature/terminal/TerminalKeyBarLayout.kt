package app.tennyo.mobile.feature.terminal

internal sealed interface TerminalKeyBarItem {
    val stableKey: String

    /** A modifier that stays latched until tapped again and applies to every key sent meanwhile. */
    data class Latch(val modifier: TerminalModifierKey) : TerminalKeyBarItem {
        override val stableKey: String get() = "latch:${modifier.name.lowercase()}"
    }

    data class Key(
        val chord: TerminalKeyChord,
        val label: String,
        /** The user-given name of a custom hotkey; without one the chord is spoken. */
        val customName: String? = null,
        val isCustom: Boolean,
        val emphasized: Boolean = false,
    ) : TerminalKeyBarItem {
        override val stableKey: String get() = (if (isCustom) "custom:" else "fixed:") + chord.id
    }

    data object AddHotkey : TerminalKeyBarItem {
        override val stableKey: String = "add"
    }
}

/** Key bar columns, top to bottom; the fixed section sits before the divider. */
internal data class TerminalKeyBarLayout(
    val fixedColumns: List<List<TerminalKeyBarItem>>,
    val toggleableColumns: List<List<TerminalKeyBarItem>>,
)

internal fun terminalKeyBarLayout(settings: TerminalKeyBarSettings, rows: Int): TerminalKeyBarLayout {
    val builtIns = ToggleableTerminalKey.entries
        .filterNot { it in settings.hiddenBuiltInKeys }
        .map { builtInKey(it.chord) }
    val customs = settings.customHotkeys
        .filter { it.enabled }
        .map { hotkey ->
            TerminalKeyBarItem.Key(
                chord = hotkey.chord,
                label = hotkey.chord.label(),
                customName = hotkey.name.ifBlank { null },
                isCustom = true,
            )
        }
    return TerminalKeyBarLayout(
        fixedColumns = FIXED_TERMINAL_KEY_BAR_ITEMS.chunkedColumnMajor(rows),
        toggleableColumns = (builtIns + customs + TerminalKeyBarItem.AddHotkey).chunkedColumnMajor(rows),
    )
}

/** Fills each column top to bottom before starting the next, so with 2 rows items 1,3,5… are on top. */
internal fun <T> List<T>.chunkedColumnMajor(rows: Int): List<List<T>> {
    require(rows > 0) { "A key bar needs at least one row." }
    return chunked(rows)
}

internal val ToggleableTerminalKey.namedKey: NamedTerminalKey
    get() = when (this) {
        ToggleableTerminalKey.PageUp -> NamedTerminalKey.PageUp
        ToggleableTerminalKey.PageDown -> NamedTerminalKey.PageDown
    }

internal val ToggleableTerminalKey.chord: TerminalKeyChord
    get() = TerminalKeyChord(namedKey)

private val interruptChord = TerminalKeyChord('c', TerminalModifiers(ctrl = true))

// Column-major: [Esc/Shift] [Tab/Ctrl] [^C/Alt] [Home/←] [↑/↓] [End/→] [⌫/⏎].
private val FIXED_TERMINAL_KEY_BAR_ITEMS: List<TerminalKeyBarItem> = listOf(
    builtInKey(TerminalKeyChord(NamedTerminalKey.Escape)),
    TerminalKeyBarItem.Latch(TerminalModifierKey.Shift),
    builtInKey(TerminalKeyChord(NamedTerminalKey.Tab)),
    TerminalKeyBarItem.Latch(TerminalModifierKey.Ctrl),
    builtInKey(interruptChord, label = "^C"),
    TerminalKeyBarItem.Latch(TerminalModifierKey.Alt),
    builtInKey(TerminalKeyChord(NamedTerminalKey.Home)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.ArrowLeft)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.ArrowUp)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.ArrowDown)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.End)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.ArrowRight)),
    builtInKey(TerminalKeyChord(NamedTerminalKey.Backspace), label = "⌫"),
    builtInKey(TerminalKeyChord(NamedTerminalKey.Enter), label = "⏎", emphasized = true),
)

/** Every built-in key's chord, hidden or not; custom hotkeys must not duplicate their bytes. */
internal val BUILT_IN_TERMINAL_KEY_CHORDS: List<TerminalKeyChord> =
    FIXED_TERMINAL_KEY_BAR_ITEMS.filterIsInstance<TerminalKeyBarItem.Key>().map { it.chord } +
        ToggleableTerminalKey.entries.map { it.chord }

private fun builtInKey(
    chord: TerminalKeyChord,
    label: String = chord.label(),
    emphasized: Boolean = false,
): TerminalKeyBarItem.Key =
    TerminalKeyBarItem.Key(
        chord = chord,
        label = label,
        isCustom = false,
        emphasized = emphasized,
    )
