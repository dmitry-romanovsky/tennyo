package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAuthMethod
import app.tennyo.contract.ChatChoice
import app.tennyo.contract.ChatCommand
import app.tennyo.contract.ChatConfigOption
import app.tennyo.contract.ChatDiff
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatEntry.Message
import app.tennyo.contract.ChatEntry.Prompt
import app.tennyo.contract.ChatEntry.Thought
import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFieldKind
import app.tennyo.contract.ChatMode
import app.tennyo.contract.ChatPermissionKind
import app.tennyo.contract.ChatPlanPriority
import app.tennyo.contract.ChatPlanStatus
import app.tennyo.contract.ChatPlanStep
import app.tennyo.contract.ChatPromptCapabilities
import app.tennyo.contract.ChatSessionSummary
import app.tennyo.contract.ChatState
import app.tennyo.contract.ChatToolKind
import app.tennyo.contract.ChatToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * ACP v1 messages read into the phone's [ChatState], and the phone's answers written back. ACP evolves on its own, so
 * every reader tolerates missing and unknown fields: a value it cannot read is left out, never an error.
 */

/** What `initialize` says the agent can do. */
internal fun ChatState.withAgentCapabilities(initialize: JsonObject): ChatState {
    val capabilities = initialize.obj("agentCapabilities")
    val prompt = capabilities?.obj("promptCapabilities")
    return copy(
        canLoadSessions = capabilities?.bool("loadSession") == true,
        canListSessions = capabilities?.obj("sessionCapabilities")?.get("list") is JsonObject,
        promptCapabilities = ChatPromptCapabilities(
            image = prompt?.bool("image") == true,
            embeddedContext = prompt?.bool("embeddedContext") == true,
        ),
        authMethods = emptyList(),
    )
}

/** The sign-in methods `initialize` offered; a terminal method runs the agent's own [command] with its arguments. */
internal fun authMethods(initialize: JsonObject, command: String): List<ChatAuthMethod> =
    initialize.array("authMethods").mapNotNull { element ->
        val method = element as? JsonObject ?: return@mapNotNull null
        val id = method.string("id") ?: return@mapNotNull null
        val terminal = method.string("type") == "terminal"
        ChatAuthMethod(
            id = id,
            name = method.string("name") ?: id,
            description = method.string("description"),
            terminalCommand = if (terminal) (listOf(command) + method.strings("args")).joinToString(" ") else null,
        )
    }

/** Modes and settings from a `session/new` or `session/load` answer. */
internal fun ChatState.withSessionSetup(response: JsonObject?): ChatState {
    val modes = response?.obj("modes")
    // ACP agents moving to session settings send both; ACP has clients prefer the setting, so one picker shows.
    val modeSetting = response?.array("configOptions").orEmpty()
        .any { (it as? JsonObject)?.string("category") == "mode" }
    return copy(
        modes = if (modeSetting) emptyList() else modes?.array("availableModes")?.mapNotNull(::mode).orEmpty(),
        currentModeId = modes?.string("currentModeId"),
        configOptions = response?.array("configOptions")?.let(::configOptions).orEmpty(),
    )
}

internal fun sessionSummaries(response: JsonElement): List<ChatSessionSummary> =
    (response as? JsonObject)?.array("sessions").orEmpty().mapNotNull { element ->
        val session = element as? JsonObject ?: return@mapNotNull null
        val id = session.string("sessionId") ?: return@mapNotNull null
        ChatSessionSummary(id, session.string("title"), session.string("updatedAt"))
    }.sortedByDescending { it.updatedAt.orEmpty() }

internal fun configOptions(options: JsonArray): List<ChatConfigOption> = options.mapNotNull { element ->
    val option = element as? JsonObject ?: return@mapNotNull null
    val id = option.string("id") ?: return@mapNotNull null
    val name = option.string("name") ?: id
    val description = option.string("description")
    when (option.string("type")) {
        "boolean" -> ChatConfigOption(
            id = id,
            name = name,
            value = (option.bool("currentValue") == true).toString(),
            isSwitch = true,
            description = description,
        )

        else -> ChatConfigOption(
            id = id,
            name = name,
            value = option.string("currentValue").orEmpty(),
            // Grouped options are flattened: the phone shows one list.
            choices = option.array("options").flatMap { choice ->
                val group = choice as? JsonObject ?: return@flatMap emptyList()
                if (group["options"] is JsonArray) {
                    group.array("options").mapNotNull(::choice)
                } else {
                    listOfNotNull(choice(group))
                }
            },
            description = description,
        )
    }
}

/** Folds one `session/update` into the state. An update kind this does not know leaves the state as it was. */
internal fun ChatState.applyUpdate(change: JsonObject): ChatState = when (change.string("sessionUpdate")) {
    "agent_message_chunk" -> appendText(change, ::Message) { last, text -> last.copy(text = last.text + text) }
    "agent_thought_chunk" -> appendText(change, ::Thought) { last, text -> last.copy(text = last.text + text) }
    // Replayed history, when a session is loaded: what the user once sent.
    "user_message_chunk" -> appendText(change, ::Prompt) { last, text -> last.copy(text = last.text + text) }
    "tool_call", "tool_call_update" -> upsertTool(change)
    "plan" -> withPlan(change.array("entries").mapNotNull(::planStep))
    "available_commands_update" -> copy(commands = change.array("availableCommands").mapNotNull(::command))
    "current_mode_update" -> copy(currentModeId = change.string("currentModeId") ?: currentModeId)
    "config_option_update" -> copy(configOptions = configOptions(change.array("configOptions")))
    else -> this
}

/** A chunk extends the last entry when it is the same kind, and starts a new one otherwise. */
private inline fun <reified T : ChatEntry> ChatState.appendText(
    change: JsonObject,
    create: (String) -> T,
    extend: (T, String) -> T,
): ChatState {
    val text = change.obj("content")?.string("text") ?: return this
    val last = entries.lastOrNull()
    return copy(entries = if (last is T) entries.dropLast(1) + extend(last, text) else entries + create(text))
}

private fun ChatState.upsertTool(change: JsonObject): ChatState {
    val id = change.string("toolCallId") ?: return this
    val existing = entries.filterIsInstance<ChatEntry.Tool>().firstOrNull { it.toolCallId == id }
    val content = change["content"] as? JsonArray
    val tool = ChatEntry.Tool(
        toolCallId = id,
        title = change.string("title") ?: existing?.title.orEmpty(),
        kind = change.string("kind")?.let(::toolKind) ?: existing?.kind ?: ChatToolKind.OTHER,
        status = change.string("status")?.let(::toolStatus) ?: existing?.status ?: ChatToolStatus.RUNNING,
        diff = content?.firstNotNullOfOrNull(::diff) ?: existing?.diff,
        output = content?.mapNotNull(::contentText)?.joinToString("\n")?.ifEmpty { null } ?: existing?.output,
    )
    return copy(entries = if (existing == null) entries + tool else entries.map { if (it === existing) tool else it })
}

private fun ChatState.withPlan(steps: List<ChatPlanStep>): ChatState {
    val plan = ChatEntry.Plan(steps)
    val index = entries.indexOfLast { it is ChatEntry.Plan }
    return copy(entries = if (index < 0) entries + plan else entries.toMutableList().also { it[index] = plan })
}

/** The fields of a form `elicitation/create` asks for; a property type this does not know is left out. */
internal fun formFields(schema: JsonObject?): List<ChatField> {
    val required = schema?.strings("required").orEmpty().toSet()
    return schema?.obj("properties")?.mapNotNull { (name, value) ->
        val property = value as? JsonObject ?: return@mapNotNull null
        val enumChoices = property.array("oneOf").mapNotNull { choice ->
            enumChoice(choice)
        }.ifEmpty { property.strings("enum").map { ChatChoice(it, it) } }
        val kind = when (property.string("type")) {
            "string" -> if (enumChoices.isEmpty()) ChatFieldKind.TEXT else ChatFieldKind.CHOICE
            "number" -> ChatFieldKind.NUMBER
            "integer" -> ChatFieldKind.INTEGER
            "boolean" -> ChatFieldKind.SWITCH
            "array" -> ChatFieldKind.MULTI_CHOICE
            else -> return@mapNotNull null
        }
        val items = property.obj("items")
        val multiChoices = items?.array("anyOf")?.mapNotNull { choice ->
            enumChoice(choice)
        }.orEmpty().ifEmpty { items?.strings("enum").orEmpty().map { ChatChoice(it, it) } }
        ChatField(
            name = name,
            title = property.string("title") ?: name,
            kind = kind,
            required = name in required,
            description = property.string("description"),
            choices = if (kind == ChatFieldKind.MULTI_CHOICE) multiChoices else enumChoices,
            default = (property["default"] as? JsonPrimitive)?.contentOrNull,
        )
    }.orEmpty()
}

/**
 * The phone's typed [values] as the JSON types [fields] declare; a multi-choice value is comma-separated. A value that
 * does not parse is left out, and the agent sees the field as unanswered.
 */
internal fun formContent(fields: List<ChatField>, values: Map<String, String>): JsonObject = buildJsonObject {
    fields.forEach { field ->
        val value = values[field.name] ?: return@forEach
        when (field.kind) {
            ChatFieldKind.TEXT, ChatFieldKind.CHOICE -> put(field.name, value)
            ChatFieldKind.NUMBER -> JsonPrimitive(value).doubleOrNull?.let { put(field.name, it) }
            ChatFieldKind.INTEGER -> JsonPrimitive(value).longOrNull?.let { put(field.name, it) }
            ChatFieldKind.SWITCH -> put(field.name, value.toBooleanStrictOrNull() ?: return@forEach)
            ChatFieldKind.MULTI_CHOICE -> put(
                field.name,
                buildJsonArray {
                    value.split(',').map(String::trim).filter(String::isNotEmpty).forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }
}

internal fun permissionKind(kind: String?): ChatPermissionKind = when (kind) {
    "allow_always" -> ChatPermissionKind.ALLOW_ALWAYS
    "reject_once" -> ChatPermissionKind.REJECT_ONCE
    "reject_always" -> ChatPermissionKind.REJECT_ALWAYS
    else -> ChatPermissionKind.ALLOW_ONCE
}

/** One `{const, title}` choice of an enum property. */
private fun enumChoice(element: JsonElement): ChatChoice? {
    val option = element as? JsonObject ?: return null
    val value = option.string("const") ?: return null
    return ChatChoice(value, option.string("title") ?: value)
}

private fun mode(element: JsonElement): ChatMode? {
    val mode = element as? JsonObject ?: return null
    val id = mode.string("id") ?: return null
    return ChatMode(id, mode.string("name") ?: id, mode.string("description"))
}

private fun choice(element: JsonElement): ChatChoice? {
    val option = element as? JsonObject ?: return null
    val value = option.string("value") ?: return null
    return ChatChoice(value, option.string("name") ?: value)
}

private fun command(element: JsonElement): ChatCommand? {
    val command = element as? JsonObject ?: return null
    val name = command.string("name") ?: return null
    return ChatCommand(name, command.string("description").orEmpty(), command.obj("input")?.string("hint"))
}

private fun planStep(element: JsonElement): ChatPlanStep? {
    val step = element as? JsonObject ?: return null
    return ChatPlanStep(
        content = step.string("content") ?: return null,
        status = when (step.string("status")) {
            "completed" -> ChatPlanStatus.COMPLETED
            "in_progress" -> ChatPlanStatus.IN_PROGRESS
            else -> ChatPlanStatus.PENDING
        },
        priority = when (step.string("priority")) {
            "high" -> ChatPlanPriority.HIGH
            "low" -> ChatPlanPriority.LOW
            else -> ChatPlanPriority.MEDIUM
        },
    )
}

private fun diff(element: JsonElement): ChatDiff? {
    val content = element as? JsonObject ?: return null
    if (content.string("type") != "diff") return null
    val path = content.string("path") ?: return null
    return ChatDiff(path, content.string("oldText"), content.string("newText").orEmpty())
}

private fun contentText(element: JsonElement): String? {
    val content = element as? JsonObject ?: return null
    if (content.string("type") != "content") return null
    return content.obj("content")?.string("text")
}

private fun toolKind(kind: String): ChatToolKind = when (kind) {
    "read" -> ChatToolKind.READ
    "edit", "delete", "move" -> ChatToolKind.EDIT
    "execute" -> ChatToolKind.EXECUTE
    "search", "fetch" -> ChatToolKind.SEARCH
    else -> ChatToolKind.OTHER
}

private fun toolStatus(status: String): ChatToolStatus = when (status) {
    "completed" -> ChatToolStatus.DONE
    "failed" -> ChatToolStatus.FAILED
    else -> ChatToolStatus.RUNNING
}
