package app.tennyo.mobile.feature.terminal

import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.close_terminal_failed
import app.tennyo.mobile.feature.terminal.generated.resources.create_terminal_failed
import app.tennyo.mobile.feature.terminal.generated.resources.load_terminal_tabs_failed
import app.tennyo.mobile.feature.terminal.generated.resources.refresh_terminal_tabs_failed
import app.tennyo.mobile.feature.terminal.generated.resources.select_terminal_failed
import app.tennyo.mobile.feature.terminal.generated.resources.send_terminal_input_failed
import org.jetbrains.compose.resources.StringResource

/** A terminal request that can fail on its own without ending the session, with how its failure is worded. */
internal enum class TerminalAction(val failure: StringResource) {
    SendTerminalInput(Res.string.send_terminal_input_failed),
    CreateTerminal(Res.string.create_terminal_failed),
    LoadTerminalTabs(Res.string.load_terminal_tabs_failed),
    SelectTerminal(Res.string.select_terminal_failed),
    RefreshTerminalTabs(Res.string.refresh_terminal_tabs_failed),
    CloseTerminal(Res.string.close_terminal_failed),
}
