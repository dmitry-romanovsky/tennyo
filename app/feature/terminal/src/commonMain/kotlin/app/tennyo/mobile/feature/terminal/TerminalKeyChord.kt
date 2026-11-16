package app.tennyo.mobile.feature.terminal

import androidx.compose.runtime.Composable
import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_alt
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_arrow_down
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_arrow_left
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_arrow_right
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_arrow_up
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_backspace
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_control
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_delete
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_end
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_enter
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_escape
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_home
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_insert
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_page_down
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_page_up
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_shift
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_space
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_key_tab
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Modifiers a PTY can observe. There is deliberately no ⌘: it never reaches a terminal, so a chord using it
 * could not be encoded.
 */
@Serializable
data class TerminalModifiers(
    @SerialName("ctrl") val ctrl: Boolean = false,
    @SerialName("alt") val alt: Boolean = false,
    @SerialName("shift") val shift: Boolean = false,
) {
    operator fun plus(other: TerminalModifiers): TerminalModifiers =
        TerminalModifiers(ctrl = ctrl || other.ctrl, alt = alt || other.alt, shift = shift || other.shift)

    /** The xterm modifier parameter: `1 + shift + 2·alt + 4·ctrl`, where 1 means "no modifiers". */
    internal val xtermParameter: Int
        get() = 1 + (if (shift) SHIFT_BIT else 0) + (if (alt) ALT_BIT else 0) + (if (ctrl) CTRL_BIT else 0)

    private companion object {
        const val SHIFT_BIT = 1
        const val ALT_BIT = 2
        const val CTRL_BIT = 4
    }
}

/** One modifier as a pressable key, in the order chord labels list them: `Ctrl+Alt+Shift+<key>`. */
enum class TerminalModifierKey(val label: String, val spokenName: StringResource) {
    Ctrl("Ctrl", Res.string.terminal_key_control),
    Alt("Alt", Res.string.terminal_key_alt),
    Shift("Shift", Res.string.terminal_key_shift),
}

internal operator fun TerminalModifiers.contains(key: TerminalModifierKey): Boolean =
    when (key) {
        TerminalModifierKey.Ctrl -> ctrl
        TerminalModifierKey.Alt -> alt
        TerminalModifierKey.Shift -> shift
    }

internal fun TerminalModifiers.toggled(key: TerminalModifierKey): TerminalModifiers =
    when (key) {
        TerminalModifierKey.Ctrl -> copy(ctrl = !ctrl)
        TerminalModifierKey.Alt -> copy(alt = !alt)
        TerminalModifierKey.Shift -> copy(shift = !shift)
    }

@Serializable
sealed interface TerminalKey {
    /** A printable ASCII character (`0x20..0x7E`); validation rejects anything else before it is stored. */
    @Serializable
    @SerialName("char")
    data class Character(@SerialName("char") val char: Char) : TerminalKey

    @Serializable
    @SerialName("named")
    data class Named(@SerialName("key") val key: NamedTerminalKey) : TerminalKey
}

/**
 * Non-character keys with their key-cap label, spoken name (null when the label is already spoken as is), and xterm
 * sequence. Escape, Tab, Enter, and
 * Backspace have no sequence: they are single control bytes with their own modifier rules.
 */
@Serializable
enum class NamedTerminalKey(
    internal val shortLabel: String,
    internal val spokenName: StringResource?,
    internal val xtermSequence: XtermSequence?,
) {
    @SerialName("Escape") Escape("Esc", Res.string.terminal_key_escape, xtermSequence = null),
    @SerialName("Tab") Tab("Tab", Res.string.terminal_key_tab, xtermSequence = null),
    @SerialName("Enter") Enter("Enter", Res.string.terminal_key_enter, xtermSequence = null),
    @SerialName("Backspace") Backspace("Bksp", Res.string.terminal_key_backspace, xtermSequence = null),
    @SerialName("Insert") Insert("Ins", Res.string.terminal_key_insert, XtermSequence.Tilde(number = 2)),
    @SerialName("Delete") Delete("Del", Res.string.terminal_key_delete, XtermSequence.Tilde(number = 3)),
    @SerialName("Home") Home("Home", Res.string.terminal_key_home, XtermSequence.Csi(final = 'H')),
    @SerialName("End") End("End", Res.string.terminal_key_end, XtermSequence.Csi(final = 'F')),
    @SerialName("PageUp") PageUp("PgUp", Res.string.terminal_key_page_up, XtermSequence.Tilde(number = 5)),
    @SerialName("PageDown") PageDown("PgDn", Res.string.terminal_key_page_down, XtermSequence.Tilde(number = 6)),
    @SerialName("ArrowUp") ArrowUp("↑", Res.string.terminal_key_arrow_up, XtermSequence.Csi(final = 'A')),
    @SerialName("ArrowDown") ArrowDown("↓", Res.string.terminal_key_arrow_down, XtermSequence.Csi(final = 'B')),
    @SerialName("ArrowLeft") ArrowLeft("←", Res.string.terminal_key_arrow_left, XtermSequence.Csi(final = 'D')),
    @SerialName("ArrowRight") ArrowRight("→", Res.string.terminal_key_arrow_right, XtermSequence.Csi(final = 'C')),
    @SerialName("F1") F1("F1", spokenName = null, XtermSequence.Ss3(final = 'P')),
    @SerialName("F2") F2("F2", spokenName = null, XtermSequence.Ss3(final = 'Q')),
    @SerialName("F3") F3("F3", spokenName = null, XtermSequence.Ss3(final = 'R')),
    @SerialName("F4") F4("F4", spokenName = null, XtermSequence.Ss3(final = 'S')),
    @SerialName("F5") F5("F5", spokenName = null, XtermSequence.Tilde(number = 15)),
    @SerialName("F6") F6("F6", spokenName = null, XtermSequence.Tilde(number = 17)),
    @SerialName("F7") F7("F7", spokenName = null, XtermSequence.Tilde(number = 18)),
    @SerialName("F8") F8("F8", spokenName = null, XtermSequence.Tilde(number = 19)),
    @SerialName("F9") F9("F9", spokenName = null, XtermSequence.Tilde(number = 20)),
    @SerialName("F10") F10("F10", spokenName = null, XtermSequence.Tilde(number = 21)),
    @SerialName("F11") F11("F11", spokenName = null, XtermSequence.Tilde(number = 23)),
    @SerialName("F12") F12("F12", spokenName = null, XtermSequence.Tilde(number = 24)),
}

internal sealed interface XtermSequence {
    /** `ESC [ X`, or `ESC [ 1 ; m X` with modifiers. */
    data class Csi(val final: Char) : XtermSequence

    /** `ESC O X` without modifiers, or `ESC [ 1 ; m X` with them (F1–F4). */
    data class Ss3(val final: Char) : XtermSequence

    /** `ESC [ n ~`, or `ESC [ n ; m ~` with modifiers. */
    data class Tilde(val number: Int) : XtermSequence
}

@Serializable
data class TerminalKeyChord(
    @SerialName("key") val key: TerminalKey,
    @SerialName("modifiers") val modifiers: TerminalModifiers = TerminalModifiers(),
) {
    constructor(key: NamedTerminalKey, modifiers: TerminalModifiers = TerminalModifiers()) :
        this(TerminalKey.Named(key), modifiers)

    constructor(char: Char, modifiers: TerminalModifiers = TerminalModifiers()) :
        this(TerminalKey.Character(char), modifiers)

    /** Stable identity for list keys and settings updates, e.g. `ctrl+alt+named:PageUp` or `ctrl+char:d`. */
    val id: String
        get() = buildString {
            if (modifiers.ctrl) append("ctrl+")
            if (modifiers.alt) append("alt+")
            if (modifiers.shift) append("shift+")
            when (key) {
                is TerminalKey.Character -> append("char:").append(key.char)
                is TerminalKey.Named -> append("named:").append(key.key.name)
            }
        }
}

/**
 * Encodes the chord as the bytes xterm sends for it, with [latched] bar modifiers added to the chord's own.
 */
internal fun TerminalKeyChord.encode(latched: TerminalModifiers = TerminalModifiers()): String {
    val effective = modifiers + latched
    return when (key) {
        is TerminalKey.Character -> key.char.toString().toTerminalInput(effective)
        is TerminalKey.Named -> key.key.encode(effective)
    }
}

/**
 * Applies latched modifiers to typed text. Ctrl maps each character through the control table and passes
 * unmapped characters through; otherwise Shift uppercases. Alt then prefixes ESC once per code point, never
 * between the halves of a surrogate pair.
 */
internal fun String.toTerminalInput(modifiers: TerminalModifiers): String {
    val base = when {
        modifiers.ctrl -> buildString {
            this@toTerminalInput.forEach { char -> append(char.toControlInput() ?: char) }
        }

        modifiers.shift -> uppercase()
        else -> this
    }
    return if (modifiers.alt) base.withMetaPrefixPerCodePoint() else base
}

internal fun TerminalKeyChord.label(): String =
    (pressedModifierKeys().map { it.label } + key.shortLabel()).joinToString("+")

/** What a screen reader says for a key: a localized name, or the key cap itself when it has none. */
internal sealed interface SpokenKey {
    data class Named(val name: StringResource) : SpokenKey
    data class Literal(val text: String) : SpokenKey
}

internal fun TerminalKeyChord.spokenKeys(): List<SpokenKey> =
    pressedModifierKeys().map { SpokenKey.Named(it.spokenName) } + key.spokenKey()

@Composable
internal fun TerminalKeyChord.accessibilityLabel(): String =
    spokenKeys().map { spoken ->
        when (spoken) {
            is SpokenKey.Named -> stringResource(spoken.name)
            is SpokenKey.Literal -> spoken.text
        }
    }.joinToString(" ")

@Composable
internal fun NamedTerminalKey.spokenLabel(): String = spokenName?.let { stringResource(it) } ?: shortLabel

internal fun Char.toControlInput(): Char? =
    when (val upper = uppercaseChar()) {
        in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar()
        '@', ' ' -> NUL
        '[' -> ESC
        '\\' -> 0x1C.toChar()
        ']' -> 0x1D.toChar()
        '^' -> 0x1E.toChar()
        '_' -> 0x1F.toChar()
        '?' -> DEL
        else -> null
    }

internal val Char.isPrintableAscii: Boolean
    get() = this in ' '..'~'

private fun NamedTerminalKey.encode(modifiers: TerminalModifiers): String {
    val metaPrefix = if (modifiers.alt) ESC.toString() else ""
    return when (this) {
        NamedTerminalKey.Escape -> "$metaPrefix$ESC"
        NamedTerminalKey.Enter -> "$metaPrefix\r"
        NamedTerminalKey.Backspace -> metaPrefix + if (modifiers.ctrl) '\b' else DEL
        // Shift+Tab is back-tab; Ctrl+Tab and Ctrl+Shift+Tab have no xterm encoding and send a plain tab.
        NamedTerminalKey.Tab -> metaPrefix + if (modifiers.shift && !modifiers.ctrl) "${CSI}Z" else "\t"
        else -> checkNotNull(xtermSequence).encode(modifiers.xtermParameter)
    }
}

private fun XtermSequence.encode(parameter: Int): String {
    val modified = parameter != 1
    return when (this) {
        is XtermSequence.Csi -> if (modified) "${CSI}1;$parameter$final" else "$CSI$final"
        is XtermSequence.Ss3 -> if (modified) "${CSI}1;$parameter$final" else "${ESC}O$final"
        is XtermSequence.Tilde -> if (modified) "$CSI$number;$parameter~" else "$CSI$number~"
    }
}

private fun String.withMetaPrefixPerCodePoint(): String = buildString {
    val text = this@withMetaPrefixPerCodePoint
    text.forEachIndexed { index, char ->
        val continuesSurrogatePair = char.isLowSurrogate() && index > 0 && text[index - 1].isHighSurrogate()
        if (!continuesSurrogatePair) append(ESC)
        append(char)
    }
}

private fun TerminalKeyChord.pressedModifierKeys(): List<TerminalModifierKey> =
    TerminalModifierKey.entries.filter { it in modifiers }

private fun TerminalKey.shortLabel(): String =
    when (this) {
        is TerminalKey.Character -> characterName()
        is TerminalKey.Named -> key.shortLabel
    }

private fun TerminalKey.spokenKey(): SpokenKey =
    when (this) {
        is TerminalKey.Character ->
            if (char == ' ') SpokenKey.Named(Res.string.terminal_key_space) else SpokenKey.Literal(char.uppercase())

        is TerminalKey.Named -> key.spokenName?.let(SpokenKey::Named) ?: SpokenKey.Literal(key.shortLabel)
    }

private fun TerminalKey.Character.characterName(): String =
    if (char == ' ') SPACE_KEY_CAP else char.uppercase()

private const val SPACE_KEY_CAP = "Space"
private const val NUL = '\u0000'
private const val ESC = '\u001B'
private const val DEL = '\u007F'
private const val CSI = "$ESC["
