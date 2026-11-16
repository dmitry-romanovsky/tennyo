package app.tennyo.mobile.feature.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MAX_CUSTOM_TERMINAL_HOTKEYS = 24
internal const val MAX_TERMINAL_HOTKEY_NAME_LENGTH = 32

/** Built-in keys after the key bar divider that the user may hide. */
@Serializable
enum class ToggleableTerminalKey {
    @SerialName("PageUp") PageUp,
    @SerialName("PageDown") PageDown,
}

@Serializable
data class CustomTerminalHotkey(
    @SerialName("chord") val chord: TerminalKeyChord,
    @SerialName("name") val name: String,
    @SerialName("enabled") val enabled: Boolean = true,
)

@Serializable
data class TerminalKeyBarSettings(
    // A hidden set rather than a visible one, so built-ins added later default to visible.
    @SerialName("hiddenBuiltInKeys") val hiddenBuiltInKeys: Set<ToggleableTerminalKey> = emptySet(),
    @SerialName("customHotkeys") val customHotkeys: List<CustomTerminalHotkey> = emptyList(),
) {
    val isAtHotkeyLimit: Boolean
        get() = customHotkeys.size >= MAX_CUSTOM_TERMINAL_HOTKEYS
}

@Serializable
enum class TerminalHotkeyKeyKind {
    @SerialName("Character") Character,
    @SerialName("Named") Named,
}

/** The Add-hotkey form input. It is serializable only so the open sheet survives configuration changes. */
@Serializable
data class TerminalHotkeyDraft(
    @SerialName("name") val name: String = "",
    @SerialName("modifiers") val modifiers: TerminalModifiers = TerminalModifiers(),
    @SerialName("keyKind") val keyKind: TerminalHotkeyKeyKind = TerminalHotkeyKeyKind.Character,
    @SerialName("character") val character: String = "",
    @SerialName("namedKey") val namedKey: NamedTerminalKey? = null,
)

enum class TerminalHotkeyDraftError {
    MissingKey,
    InvalidCharacter,
    UnsupportedCtrlCharacter,
    AlreadyOnKeyBar,
    Duplicate,
    LimitReached,
}

sealed interface TerminalHotkeyDraftResult {
    data class Valid(val hotkey: CustomTerminalHotkey) : TerminalHotkeyDraftResult
    data class Invalid(val error: TerminalHotkeyDraftError) : TerminalHotkeyDraftResult
}

/** Keeps only the last typed code point, so typing into the key field replaces the key instead of appending. */
internal fun TerminalHotkeyDraft.withCharacterInput(text: String): TerminalHotkeyDraft {
    val endsWithSurrogatePair =
        text.length >= 2 && text[text.length - 2].isHighSurrogate() && text.last().isLowSurrogate()
    return copy(character = text.takeLast(if (endsWithSurrogatePair) 2 else 1))
}

/** The chord the draft would add, normalized, or null while its key is missing or not printable ASCII. */
internal fun TerminalHotkeyDraft.normalizedChord(): TerminalKeyChord? {
    val key = when (keyKind) {
        TerminalHotkeyKeyKind.Character -> character.singleOrNull()
            ?.takeIf { it.isPrintableAscii }
            ?.let(TerminalKey::Character)

        TerminalHotkeyKeyKind.Named -> namedKey?.let(TerminalKey::Named)
    }
    return key?.let { TerminalKeyChord(it, modifiers).normalized() }
}

/**
 * Validates in a fixed order. The limit comes first because nothing else matters once no hotkey can be added.
 */
internal fun TerminalKeyBarSettings.validate(draft: TerminalHotkeyDraft): TerminalHotkeyDraftResult {
    val chord = draft.normalizedChord()
    val error = when {
        isAtHotkeyLimit -> TerminalHotkeyDraftError.LimitReached
        draft.isMissingKey() -> TerminalHotkeyDraftError.MissingKey
        chord == null -> TerminalHotkeyDraftError.InvalidCharacter
        else -> chordError(chord)
    }
    return if (chord != null && error == null) {
        TerminalHotkeyDraftResult.Valid(CustomTerminalHotkey(chord = chord, name = draft.name.toHotkeyName()))
    } else {
        TerminalHotkeyDraftResult.Invalid(checkNotNull(error))
    }
}

/** Adds [hotkey] only if it is still valid against the current settings; a stale form snapshot is a no-op. */
internal fun TerminalKeyBarSettings.withHotkey(hotkey: CustomTerminalHotkey): TerminalKeyBarSettings {
    val normalized = hotkey.copy(chord = hotkey.chord.normalized(), name = hotkey.name.toHotkeyName())
    val error = if (isAtHotkeyLimit) TerminalHotkeyDraftError.LimitReached else chordError(normalized.chord)
    return if (error == null) copy(customHotkeys = customHotkeys + normalized) else this
}

internal fun TerminalKeyBarSettings.withoutHotkey(chordId: String): TerminalKeyBarSettings =
    copy(customHotkeys = customHotkeys.filterNot { it.chord.id == chordId })

internal fun TerminalKeyBarSettings.withHotkeyEnabled(chordId: String, enabled: Boolean): TerminalKeyBarSettings =
    copy(customHotkeys = customHotkeys.map { if (it.chord.id == chordId) it.copy(enabled = enabled) else it })

internal fun TerminalKeyBarSettings.withBuiltInKeyVisible(
    key: ToggleableTerminalKey,
    visible: Boolean,
): TerminalKeyBarSettings =
    copy(hiddenBuiltInKeys = if (visible) hiddenBuiltInKeys - key else hiddenBuiltInKeys + key)

/** Re-applies every rule in list order, dropping entries that fail and anything past the limit. */
internal fun TerminalKeyBarSettings.sanitized(): TerminalKeyBarSettings =
    customHotkeys.fold(copy(customHotkeys = emptyList())) { settings, hotkey -> settings.withHotkey(hotkey) }

internal fun String.toHotkeyName(): String {
    val name = filterNot { it.isISOControl() }.trim().take(MAX_TERMINAL_HOTKEY_NAME_LENGTH).trimEnd()
    return if (name.lastOrNull()?.isHighSurrogate() == true) name.dropLast(1) else name
}

private fun TerminalHotkeyDraft.isMissingKey(): Boolean =
    when (keyKind) {
        TerminalHotkeyKeyKind.Character -> character.isEmpty()
        TerminalHotkeyKeyKind.Named -> namedKey == null
    }

/**
 * Letters are stored lowercase with Shift meaning uppercase. Shift is dropped where it carries no meaning: on
 * symbols (the character already includes it) and under Ctrl (control encoding ignores it).
 */
private fun TerminalKeyChord.normalized(): TerminalKeyChord {
    val char = (key as? TerminalKey.Character)?.char ?: return this
    val shift = when {
        modifiers.ctrl -> false
        char.isLetter() -> modifiers.shift || char.isUpperCase()
        else -> false
    }
    return TerminalKeyChord(TerminalKey.Character(char.lowercaseChar()), modifiers.copy(shift = shift))
}

/** Compares encoded bytes, not chord ids, so equivalent chords such as Ctrl+Shift+Tab and Tab collide. */
private fun TerminalKeyBarSettings.chordError(chord: TerminalKeyChord): TerminalHotkeyDraftError? {
    val char = (chord.key as? TerminalKey.Character)?.char
    val bytes = chord.encode()
    return when {
        char != null && !char.isPrintableAscii -> TerminalHotkeyDraftError.InvalidCharacter
        char != null && chord.modifiers.ctrl && char.toControlInput() == null ->
            TerminalHotkeyDraftError.UnsupportedCtrlCharacter

        BUILT_IN_TERMINAL_KEY_CHORDS.any { it.encode() == bytes } -> TerminalHotkeyDraftError.AlreadyOnKeyBar
        customHotkeys.any { it.chord.encode() == bytes } -> TerminalHotkeyDraftError.Duplicate
        else -> null
    }
}
