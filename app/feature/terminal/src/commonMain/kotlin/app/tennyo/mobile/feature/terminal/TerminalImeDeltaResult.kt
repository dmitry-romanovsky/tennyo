package app.tennyo.mobile.feature.terminal


/**
 * Reconciles cumulative or composing IME text-field updates into a safe
 * append delta and the updated local field text.
 *
 * Android keyboards may report snapshots like "" → "a" → "aa" instead of
 * per-keystroke deltas.  This helper extracts only the newly appended
 * character(s) so the terminal receives one delta per callback.
 *
 * When the update is not a simple append (e.g., backspace inside the
 * IME buffer), we conservatively accept the new field text but send no
 * terminal input — the real backspace path goes through [onPreviewKeyEvent].
 */
internal data class TerminalImeDeltaResult(
    val newFieldText: String,
    val inputDelta: String,
)

/**
 * @param previousText the text we retained from the last callback.
 * @param newText the full text from the current IME callback.
 * @return the updated field text to retain and the terminal input delta to send.
 */
internal fun computeTerminalImeDelta(previousText: String, newText: String): TerminalImeDeltaResult {
    // A non-append update (e.g. IME composing correction) keeps the field but sends nothing: the intended
    // terminal keystroke is unknown.
    val inputDelta = if (newText.startsWith(previousText)) newText.substring(previousText.length) else ""
    return TerminalImeDeltaResult(newFieldText = newText, inputDelta = inputDelta)
}

/**
 * Caps the retained [fieldText] to avoid unbounded growth across a long
 * terminal session.  We keep only the last [maxRetainedChars] characters
 * as the new baseline — already-sent deltas are never re-emitted.
 */
internal fun rebaseTerminalImeField(fieldText: String, maxRetainedChars: Int = 200): String {
    return if (fieldText.length > maxRetainedChars) {
        fieldText.takeLast(maxRetainedChars)
    } else {
        fieldText
    }
}
