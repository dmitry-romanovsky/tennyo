package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatCommand

/**
 * Project files the draft names with `@path`, as the agent should receive them: attached links, not only text. A path
 * that is not a project file is refused by the host, never silently dropped.
 */
internal fun mentionedFiles(text: String): List<String> =
    MENTION.findAll(text)
        .map { it.groupValues[1].trimEnd('.', ',', ':', ';') }
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

/** The commands a draft that starts with `/` is still typing, or none once it has a space. */
internal fun commandSuggestions(text: String, commands: List<ChatCommand>): List<ChatCommand> {
    if (!text.startsWith('/') || ' ' in text) return emptyList()
    val typed = text.removePrefix("/")
    return commands.filter { it.name.startsWith(typed, ignoreCase = true) }
}

private val MENTION = Regex("(?:^|\\s)@([\\w./-]+)")
