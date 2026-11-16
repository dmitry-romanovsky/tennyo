package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatChoice
import app.tennyo.contract.ChatConfigOption
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFieldKind
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatMode
import app.tennyo.contract.ChatPermissionKind
import app.tennyo.contract.ChatPermissionOption
import app.tennyo.contract.ChatSessionSummary
import app.tennyo.contract.ChatState
import app.tennyo.contract.ChatToolKind
import app.tennyo.contract.ChatToolStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * VS Code's agent host speaks AHP: whole chat states kept current by reducers, rather than ACP's stream of updates.
 * These read that state into the phone's transcript. AHP is pre-1.0, so, as with ACP, anything unread is skipped.
 */

/** The session config key AHP providers use for the agent's mode; the phone shows it as the mode picker. */
internal const val AHP_MODE_KEY = "mode"

/** The settings a client may change: the session config's schema properties that are not read-only. */
internal fun ahpConfigProperties(session: JsonObject): Map<String, JsonObject> =
    session.obj("config")?.obj("schema")?.obj("properties").orEmpty()
        .mapNotNull { (key, property) -> (property as? JsonObject)?.takeIf(::isSettable)?.let { key to it } }
        .toMap()

private fun isSettable(property: JsonObject) =
    property.bool("readOnly") != true && property.bool("sessionMutable") == true &&
        property.string("type").let { it == "boolean" || (it == "string" && property.array("enum").isNotEmpty()) }

/** Reads the host's session config into the modes and settings the phone offers. */
internal fun ChatState.withAhpConfig(session: JsonObject): ChatState {
    val values = session.obj("config")?.obj("values")
    val options = ahpConfigProperties(session).map { (key, property) ->
        val name = property.string("title") ?: key
        val description = property.string("description")
        val value = values?.string(key) ?: property.string("default").orEmpty()
        if (property.string("type") == "boolean") {
            ChatConfigOption(key, name, value, isSwitch = true, description = description)
        } else {
            val labels = property.strings("enumLabels")
            val choices = property.strings("enum").mapIndexed { index, choice ->
                ChatChoice(choice, labels.getOrNull(index) ?: choice)
            }
            ChatConfigOption(key, name, value, choices, description = description)
        }
    }
    val mode = options.firstOrNull { it.id == AHP_MODE_KEY }
    return copy(
        modes = mode?.choices?.map { ChatMode(it.value, it.name) }.orEmpty(),
        currentModeId = mode?.value,
        configOptions = options.filter { it !== mode },
    )
}

/** The agent ids AHP providers get: prefixed, so they never collide with an ACP agent's. */
const val AHP_AGENT_PREFIX = "ahp:"

internal fun ahpAgents(root: JsonObject): List<ChatAgent> = root.array("agents").mapNotNull { element ->
    val agent = element as? JsonObject ?: return@mapNotNull null
    val provider = agent.string("provider") ?: return@mapNotNull null
    ChatAgent("$AHP_AGENT_PREFIX$provider", agent.string("displayName") ?: provider)
}

internal fun ahpSessions(listed: JsonObject, provider: String): List<ChatSessionSummary> =
    listed.array("items").mapNotNull { element ->
        val session = element as? JsonObject ?: return@mapNotNull null
        if (session.string("provider") != provider) return@mapNotNull null
        val id = session.string("resource") ?: return@mapNotNull null
        ChatSessionSummary(id, session.string("title"), session.string("modifiedAt"))
    }.sortedByDescending { it.updatedAt.orEmpty() }

/** Every listed session, of any provider, under the agent ids [ahpAgents] gives, newest first. */
internal fun ahpHistory(listed: JsonObject): List<ChatHistoryEntry> =
    listed.array("items").mapNotNull { element ->
        val session = element as? JsonObject ?: return@mapNotNull null
        val provider = session.string("provider") ?: return@mapNotNull null
        val id = session.string("resource") ?: return@mapNotNull null
        ChatHistoryEntry(AHP_AGENT_PREFIX + provider, id, session.string("title"), session.string("modifiedAt"))
    }.sortedByDescending { it.updatedAt.orEmpty() }

/** A turn is running while the chat has an active one. */
internal fun ahpBusy(chat: JsonObject): Boolean = chat["activeTurn"] is JsonObject

/** The turn a tool call belongs to, which a confirmation has to name. */
internal fun ahpTurnOf(chat: JsonObject, toolCallId: String): String? =
    ahpTurns(chat).firstOrNull { turn ->
        turn.array("responseParts").any { (it as? JsonObject)?.obj("toolCall")?.string("toolCallId") == toolCallId }
    }?.string("id")

internal fun ahpEntries(chat: JsonObject): List<ChatEntry> = ahpTurns(chat).flatMap { turn ->
    listOfNotNull(turn.obj("message")?.string("text")?.let { ChatEntry.Prompt(it) }) +
        turn.array("responseParts").flatMap(::partEntries)
}

/** Completed turns in order, then the running one. */
private fun ahpTurns(chat: JsonObject): List<JsonObject> =
    chat.array("turns").mapNotNull { it as? JsonObject } + listOfNotNull(chat.obj("activeTurn"))

private fun partEntries(element: JsonElement): List<ChatEntry> {
    val part = element as? JsonObject ?: return emptyList()
    return when (part.string("kind")) {
        "markdown" -> listOfNotNull(part.string("content")?.let { ChatEntry.Message(it) })
        "reasoning" -> listOfNotNull(part.string("content")?.let { ChatEntry.Thought(it) })
        "toolCall" -> part.obj("toolCall")?.let(::toolEntries).orEmpty()
        "inputRequest" -> listOfNotNull(part.obj("request")?.let { form(it, part.string("response")) })
        "error" -> listOfNotNull(part.obj("error")?.string("message")?.let { ChatEntry.Message(it) })
        "systemNotification" -> listOfNotNull(part["content"]?.let(::text)?.let { ChatEntry.Message(it) })
        else -> emptyList()
    }
}

/** The tool row, and — while the host waits for approval — the permission that answers it. */
private fun toolEntries(call: JsonObject): List<ChatEntry> {
    val id = call.string("toolCallId") ?: return emptyList()
    val title = call.string("displayName") ?: call.string("toolName").orEmpty()
    val status = call.string("status")
    val tool = ChatEntry.Tool(
        toolCallId = id,
        title = title,
        kind = toolKind(call.string("toolName").orEmpty()),
        status = when (status) {
            "completed" -> if (call.string("success") == "false") ChatToolStatus.FAILED else ChatToolStatus.DONE
            "cancelled" -> ChatToolStatus.FAILED
            else -> ChatToolStatus.RUNNING
        },
        output = call.array("content")
            .mapNotNull { (it as? JsonObject)?.takeIf { content -> content.string("type") == "text" }?.string("text") }
            .joinToString("\n").ifEmpty { null },
    )
    if (status != "pending-confirmation") return listOf(tool)
    val permission = ChatEntry.Permission(
        requestId = id,
        title = call["confirmationTitle"]?.let(::text) ?: title,
        options = listOf(
            ChatPermissionOption(ALLOW_OPTION, "Allow", ChatPermissionKind.ALLOW_ONCE),
            ChatPermissionOption(DENY_OPTION, "Deny", ChatPermissionKind.REJECT_ONCE),
        ),
    )
    return listOf(tool, permission)
}

private fun form(request: JsonObject, response: String?): ChatEntry.Form? {
    val id = request.string("id") ?: return null
    val fields = request.array("questions").mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        val name = question.string("id") ?: return@mapNotNull null
        ChatField(
            name = name,
            title = question.string("title") ?: question.string("message") ?: name,
            kind = when (question.string("kind")) {
                "number" -> ChatFieldKind.NUMBER
                "integer" -> ChatFieldKind.INTEGER
                "boolean" -> ChatFieldKind.SWITCH
                "single-select" -> ChatFieldKind.CHOICE
                "multi-select" -> ChatFieldKind.MULTI_CHOICE
                else -> ChatFieldKind.TEXT
            },
            required = question.string("required") == "true",
            description = question.string("message").takeIf { question.string("title") != null },
            choices = question.array("options").mapNotNull { option ->
                (option as? JsonObject)?.let { choice ->
                    choice.string("id")?.let { ChatChoice(it, choice.string("label") ?: it) }
                }
            },
            default = question.string("defaultValue"),
        )
    }
    val answer = when (response) {
        "accept" -> ChatFormAction.ACCEPT
        "decline" -> ChatFormAction.DECLINE
        "cancel" -> ChatFormAction.CANCEL
        else -> null
    }
    return ChatEntry.Form(id, request.string("message").orEmpty(), fields, answer)
}

/** The phone's typed [values] as AHP answers, submitted; a value that does not parse is left unanswered. */
internal fun ahpAnswers(fields: List<ChatField>, values: Map<String, String>): JsonObject = buildJsonObject {
    fields.forEach { field ->
        val value = values[field.name] ?: return@forEach
        val answer = buildJsonObject {
            when (field.kind) {
                ChatFieldKind.TEXT -> {
                    put("kind", "text")
                    put("value", value)
                }
                ChatFieldKind.NUMBER, ChatFieldKind.INTEGER -> {
                    put("kind", "number")
                    put("value", JsonPrimitive(value).doubleOrNull ?: return@forEach)
                }
                ChatFieldKind.SWITCH -> {
                    put("kind", "boolean")
                    put("value", value.toBooleanStrictOrNull() ?: return@forEach)
                }
                ChatFieldKind.CHOICE -> {
                    put("kind", "selected")
                    put("value", value)
                }
                ChatFieldKind.MULTI_CHOICE -> {
                    put("kind", "selected-many")
                    val picked = value.split(',').map(String::trim).filter(String::isNotEmpty)
                    put("value", buildJsonArray { picked.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        putJsonObject(field.name) {
            put("state", "submitted")
            put("value", answer)
        }
    }
}

/** A `StringOrMarkdown`: a plain string, or `{markdown}`. */
private fun text(element: JsonElement): String? =
    (element as? JsonPrimitive)?.contentOrNull
        ?: (element as? JsonObject)?.let { it.string("markdown") ?: it.string("value") }

// Tool names are the host's own; these cover the usual verbs, and everything else is plain work.
private fun toolKind(name: String): ChatToolKind = with(name.lowercase()) {
    when {
        contains("read") || contains("view") -> ChatToolKind.READ
        contains("edit") || contains("write") || contains("replace") || contains("create") -> ChatToolKind.EDIT
        contains("search") || contains("find") || contains("grep") || contains("fetch") -> ChatToolKind.SEARCH
        contains("run") || contains("terminal") || contains("exec") || contains("bash") -> ChatToolKind.EXECUTE
        else -> ChatToolKind.OTHER
    }
}

/** The permission options the phone answers an AHP confirmation with. */
const val ALLOW_OPTION = "allow"
const val DENY_OPTION = "deny"
