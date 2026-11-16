package app.tennyo.mobile.feature.terminal

import app.tennyo.mobile.design.PaneNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The terminal pane: the IDE's terminals, typed into from the phone keyboard and the key bar. */
interface TerminalPaneComponent {
    val state: StateFlow<TerminalState>

    val keyBar: StateFlow<TerminalKeyBarSettings>

    /** Consumed by the workspace, which shows them in its message area and banner. */
    val notices: Flow<PaneNotice>

    fun onIntent(intent: TerminalIntent)
}

sealed interface TerminalIntent {
    data object Create : TerminalIntent
    data class Select(val id: String) : TerminalIntent
    data class Close(val id: String) : TerminalIntent
    data class SendInput(val text: String) : TerminalIntent
    data class AddHotkey(val hotkey: CustomTerminalHotkey) : TerminalIntent
}
