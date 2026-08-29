package md.borisveriga.bpodcat.core.common.format

/** Matches an HTML tag, including self-closing and attribute-bearing forms. */
private val HTML_TAG = Regex("""<[^>]*>""")

/** Matches a numeric character reference, decimal or hexadecimal. */
private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

/** Matches a named character reference. */
private val NAMED_ENTITY = Regex("""&([a-zA-Z]+);""")

/** Runs of whitespace to collapse, excluding the newlines we deliberately keep. */
private val HORIZONTAL_WHITESPACE = Regex("""[ \t ]{2,}""")

/** Three or more consecutive newlines, collapsed to a paragraph break. */
private val EXCESS_NEWLINES = Regex("""\n{3,}""")

/** Tags that mark a line break when stripped. */
private val LINE_BREAK_TAG = Regex("""<\s*(br\s*/?|/p|/div|/li)\s*>""", RegexOption.IGNORE_CASE)

/** The named entities that actually turn up in podcast feeds. */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "mdash" to "—",
    "ndash" to "–",
    "hellip" to "…",
    "laquo" to "«",
    "raquo" to "»",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
)

/**
 * Converts feed-supplied HTML into readable plain text.
 *
 * Podcast descriptions are HTML fragments and are frequently *double* escaped: SoundCloud, for one,
 * publishes `&amp;#10;` where a newline was meant, so a single decode pass leaves a literal `&#10;`
 * on screen. Entities are therefore decoded up to [MAX_DECODE_PASSES] times, until the text stops
 * changing.
 *
 * The stored description keeps the publisher's original markup; this runs at display time so that
 * rendering real HTML show notes later remains possible.
 *
 * @return tag-free, entity-decoded text with whitespace tidied.
 */
fun String.toPlainText(): String {
    if (isEmpty()) return this

    var text = this
    repeat(MAX_DECODE_PASSES) {
        val decoded = text.decodeEntitiesOnce()
        if (decoded == text) return@repeat
        text = decoded
    }

    return text
        .replace(LINE_BREAK_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(HORIZONTAL_WHITESPACE, " ")
        .replace(EXCESS_NEWLINES, "\n\n")
        .trim()
}

/** Decodes every numeric and known named character reference exactly once. */
private fun String.decodeEntitiesOnce(): String =
    replace(NUMERIC_ENTITY) { match ->
        val isHex = match.groupValues[1].isNotEmpty()
        val code = match.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
        // Out-of-range code points are left as written rather than throwing.
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
    }.replace(NAMED_ENTITY) { match ->
        NAMED_ENTITIES[match.groupValues[1].lowercase()] ?: match.value
    }

/**
 * Two passes handle the common single- and double-escaped cases without turning a deliberately
 * written literal `&amp;amp;` into `&` on the third.
 */
private const val MAX_DECODE_PASSES = 2
