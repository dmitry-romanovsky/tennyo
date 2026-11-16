package app.tennyo.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TerminalTab(
    val id: String,
    val title: String,
    val cwd: String,
    @Transient
    val text: String = "",
    val isSelected: Boolean = false,
    val snapshot: TerminalSnapshot? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TerminalSnapshot(
    val text: String = "",
    val cursorOffset: Int? = null,
    val cursorVisible: Boolean = true,
    val spans: List<TerminalStyleSpan> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styleEpoch: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styles: Map<Int, TerminalStyleDefinition> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styleRuns: List<TerminalStyleRun> = emptyList(),
)

@Serializable
data class TerminalStyleSpan(
    val start: Int,
    val end: Int,
    val foreground: String? = null,
    val background: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TerminalStyleDefinition(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val foreground: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val background: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bold: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val italic: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val underline: Boolean = false,
)

@Serializable
data class TerminalStyleRun(
    val styleId: Int,
    val ranges: List<Int>,
)

@Serializable
data class TerminalTabsState(
    val tabs: List<TerminalTab> = emptyList(),
    val selectedTerminalId: String? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TerminalFrame(
    val terminalId: String,
    val text: String = "",
    val isError: Boolean = false,
    val snapshot: TerminalSnapshot? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val baseRevision: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val revision: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styleEpoch: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dropPrefixChars: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val append: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cursorOffset: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cursorVisible: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styles: Map<Int, TerminalStyleDefinition> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val styleRuns: List<TerminalStyleRun> = emptyList(),
)

@Serializable
data class TerminalSnapshotResponse(
    val terminalId: String,
    val snapshot: TerminalSnapshot,
    val revision: Long? = null,
)
