package app.tennyo.mobile.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text chosen outside composition and worded only when shown: a string resource with its arguments, or text that
 * arrives already worded (from the host or the file system). Arguments that are themselves [UiText] are resolved
 * first, so a sentence can embed another localized sentence.
 */
sealed interface UiText {
    class Resource(val resource: StringResource, vararg val args: Any) : UiText {
        override fun equals(other: Any?): Boolean =
            other is Resource && other.resource == resource && other.args.contentEquals(args)

        override fun hashCode(): Int = 31 * resource.hashCode() + args.contentHashCode()
    }

    data class Verbatim(val text: String) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(resource, *args.map { if (it is UiText) it.resolve() else it }.toTypedArray())
    is UiText.Verbatim -> text
}

