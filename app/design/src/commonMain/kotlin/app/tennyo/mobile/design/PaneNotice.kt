package app.tennyo.mobile.design

import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.failure_with_detail
import org.jetbrains.compose.resources.StringResource

/**
 * What a workspace pane tells the user outside its own content: a failed action, which the workspace banner keeps
 * until it is dismissed or — when [Failure.sticky] — until the pane reports it has [Recovered]. A success needs no
 * notice: the pane's own content shows it.
 */
sealed interface PaneNotice {
    data class Failure(val text: UiText, val sticky: Boolean = false) : PaneNotice

    data object Recovered : PaneNotice
}

/** The failure sentence, followed by the host's or transport's own detail when there is one. */
fun failureText(failure: StringResource, detail: String?): UiText =
    if (detail.isNullOrBlank()) {
        UiText.Resource(failure)
    } else {
        UiText.Resource(Res.string.failure_with_detail, UiText.Resource(failure), detail)
    }
