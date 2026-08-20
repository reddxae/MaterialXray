package com.material.xray.ui.configviewer

/** Syntax class of a slice of a pretty-printed JSON document. */
enum class JsonTokenKind {
    /** A string literal used as an object key. */
    Key,

    /** A string literal used as a value. */
    StringValue,

    /** A numeric literal. */
    Number,

    /** `true`, `false` or `null`. */
    Literal,

    /** Braces, brackets, commas and colons. */
    Punctuation,

    /** Whitespace and anything the scanner does not recognise. */
    Plain,
}

data class JsonToken(val text: String, val kind: JsonTokenKind)

/**
 * Splits pretty-printed JSON into one token list per line.
 *
 * Scanning a line at a time is safe because a JSON string literal cannot contain a raw newline, so
 * after pretty-printing every line is self-contained. Malformed input is never rejected: anything
 * the scanner does not recognise comes back as [JsonTokenKind.Plain], and concatenating the text of
 * every token always reproduces the input exactly.
 */
fun tokenizeJsonLines(json: String): List<List<JsonToken>> = json.lines().map(::tokenizeJsonLine)

private fun tokenizeJsonLine(line: String): List<JsonToken> {
    if (line.isEmpty()) return emptyList()

    val tokens = mutableListOf<JsonToken>()
    var index = 0
    var plainStart = 0

    fun flushPlain(until: Int) {
        if (until > plainStart) {
            tokens += JsonToken(line.substring(plainStart, until), JsonTokenKind.Plain)
        }
    }

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' -> {
                flushPlain(index)
                val end = stringLiteralEnd(line, index)
                val kind = if (isFollowedByColon(line, end)) JsonTokenKind.Key else JsonTokenKind.StringValue
                tokens += JsonToken(line.substring(index, end), kind)
                index = end
                plainStart = end
            }
            char in PUNCTUATION -> {
                flushPlain(index)
                tokens += JsonToken(char.toString(), JsonTokenKind.Punctuation)
                index++
                plainStart = index
            }
            char.isWhitespace() -> index++
            else -> {
                // Consume the whole bareword before classifying it, so `nullable` is not read as
                // the literal `null` followed by stray text.
                flushPlain(index)
                val end = barewordEnd(line, index)
                val text = line.substring(index, end)
                tokens += JsonToken(text, text.barewordKind())
                index = end
                plainStart = end
            }
        }
    }
    flushPlain(line.length)
    return tokens
}

/**
 * Returns the index just past the closing quote of the string literal starting at [start], or the
 * end of the line when the literal is unterminated.
 */
private fun stringLiteralEnd(line: String, start: Int): Int {
    var index = start + 1
    while (index < line.length) {
        when (line[index]) {
            '\\' -> index += 2
            '"' -> return index + 1
            else -> index++
        }
    }
    return line.length
}

private fun isFollowedByColon(line: String, from: Int): Boolean {
    var index = from
    while (index < line.length && line[index].isWhitespace()) index++
    return index < line.length && line[index] == ':'
}

private fun barewordEnd(line: String, start: Int): Int {
    var index = start
    while (index < line.length) {
        val char = line[index]
        if (char == '"' || char.isWhitespace() || char in PUNCTUATION) break
        index++
    }
    return index
}

private fun String.barewordKind(): JsonTokenKind = when {
    this in LITERALS -> JsonTokenKind.Literal
    NUMBER_PATTERN.matches(this) -> JsonTokenKind.Number
    else -> JsonTokenKind.Plain
}

private val PUNCTUATION = charArrayOf('{', '}', '[', ']', ',', ':')
private val LITERALS = setOf("true", "false", "null")
private val NUMBER_PATTERN = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
