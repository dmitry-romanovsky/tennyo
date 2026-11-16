package app.tennyo.mobile.feature.local

import kotlin.jvm.JvmInline

/** One requirement line that passed [requirementOrNull]: a name, extras, version specifiers, a marker, no more. */
@JvmInline
value class Requirement internal constructor(val line: String)

sealed interface RequirementsParse {
    data class Valid(val requirements: List<Requirement>) : RequirementsParse

    /** [line] is 1-based; 0 means the file as a whole (too large or unreadable). */
    data class Rejected(val line: Int) : RequirementsParse
}

/**
 * The requirements in a `requirements.txt`. Blank lines, comments and a BOM are dropped. Anything that is not a plain
 * requirement (options, includes, URLs, paths, continuations) rejects the whole file, so a hostile line never reaches
 * the installer.
 */
fun parseRequirements(text: String): RequirementsParse {
    val requirements = mutableListOf<Requirement>()
    text.removePrefix(BYTE_ORDER_MARK).split("\r\n", "\n").forEachIndexed { index, raw ->
        val line = raw.substringBefore(" #").trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
        val requirement = requirementOrNull(line)
        if (raw.length > MAX_LINE_LENGTH || requirement == null || requirements.size == MAX_REQUIREMENTS) {
            return RequirementsParse.Rejected(index + 1)
        }
        requirements += requirement
    }
    return RequirementsParse.Valid(requirements.distinct())
}

/** The requirement [input] spells, or `null` when it is anything beyond the allowlist. */
fun requirementOrNull(input: String): Requirement? {
    val line = input.trim()
    return if (line.length <= MAX_LINE_LENGTH && REQUIREMENT.matches(line)) Requirement(line) else null
}

/** The most bytes of `requirements.txt` read. */
internal const val MAX_REQUIREMENTS_FILE_BYTES = 64L * 1024

// The UTF-8 byte order mark (U+FEFF) some Windows editors put at the start of a file.
private const val BYTE_ORDER_MARK = "﻿"
private const val MAX_LINE_LENGTH = 200
private const val MAX_REQUIREMENTS = 200

// ASCII only, so a lookalike letter from another script can never name a different project.
private const val NAME = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"
private const val SPECIFIER = "(?:==|!=|<=|>=|~=|<|>) *[A-Za-z0-9.*+!-]+"
private const val MARKER = "[A-Za-z0-9 \"'._<>=!()~-]+"
private val REQUIREMENT = Regex("$NAME(?:\\[$NAME(?:,$NAME)*])?(?: *$SPECIFIER(?: *, *$SPECIFIER)*)?(?: *; *$MARKER)?")
