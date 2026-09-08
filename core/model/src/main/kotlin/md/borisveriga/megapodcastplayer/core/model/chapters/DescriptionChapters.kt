package md.borisveriga.megapodcastplayer.core.model.chapters

import md.borisveriga.megapodcastplayer.core.model.format.parseTimecodeMs

/**
 * Reads a chapter list out of an episode description.
 *
 * This is what gives a YouTube-sourced show chapters: a video's `shortDescription` is stored
 * verbatim as the episode's description, and the timestamp block creators write there is exactly
 * this shape. Plenty of RSS publishers do the same instead of publishing `podcast:chapters`.
 *
 * The hard part is not matching timestamps — it is refusing prose that happens to contain one. The
 * rules below are all about that, and they are deliberately strict: a wrong chapter list is worse
 * than none, because it silently misplaces every seek the user makes.
 *
 * @param plainText the description with its markup already stripped, so that `<br>` and `</p>` have
 *   become the line breaks this reads by.
 * @param durationMs the episode's duration when known, used to reject entries past the end.
 * @return the chapters, in order, or an empty list when the text does not hold a chapter list.
 */
fun chaptersFromDescription(plainText: String, durationMs: Long?): List<Chapter> {
    val candidates = plainText.lineSequence()
        .take(MAX_SCANNED_LINES)
        .mapNotNull(::parseChapterLine)
        .toList()

    if (!candidates.looksLikeAChapterList()) return emptyList()

    val withinEpisode = when (durationMs) {
        null -> candidates
        else -> candidates.filter { it.startMs < durationMs }
    }

    return if (withinEpisode.size < MIN_CHAPTERS) emptyList() else withinEpisode.take(MAX_CHAPTERS)
}

/**
 * Whether a set of matched lines is a chapter list rather than prose that mentions times.
 *
 * All three rules exist to reject the same thing from different angles: too few matches is a
 * sentence with a timestamp in it, a late first entry is a single "the good bit is at 34:12", and
 * times that do not run forwards are a paragraph rather than a list.
 *
 * @return true when the lines can be trusted as a chapter list.
 */
private fun List<Chapter>.looksLikeAChapterList(): Boolean =
    size >= MIN_CHAPTERS &&
        first().startMs <= FIRST_START_TOLERANCE_MS &&
        zipWithNext().all { (earlier, later) -> later.startMs > earlier.startMs }

/**
 * Reads one line as a chapter.
 *
 * Leading-timestamp form is tried first because it is far commoner; the trailing form turns up in
 * hand-written show notes.
 *
 * @param line one line of the description.
 * @return the chapter, or null when the line is not one.
 */
private fun parseChapterLine(line: String): Chapter? {
    val leading = LEADING_TIMESTAMP.matchEntire(line.trim())
    if (leading != null) {
        return chapterOf(timecode = leading.groupValues[1], title = leading.groupValues[2])
    }
    val trailing = TRAILING_TIMESTAMP.matchEntire(line.trim())
    if (trailing != null) {
        return chapterOf(timecode = trailing.groupValues[2], title = trailing.groupValues[1])
    }
    return null
}

/**
 * Builds a chapter from a matched line, rejecting titles that are really paragraphs.
 *
 * @param timecode the matched timestamp.
 * @param title the rest of the line.
 * @return the chapter, or null when either half fails to convince.
 */
private fun chapterOf(timecode: String, title: String): Chapter? {
    val startMs = parseTimecodeMs(timecode) ?: return null
    val cleaned = title.trim().trimStart(*TITLE_LEAD_PUNCTUATION).trim()
    if (cleaned.isEmpty() || cleaned.length > MAX_TITLE_LENGTH) return null
    return Chapter(startMs = startMs, title = cleaned)
}

/**
 * `0:00 Intro`, `- [1:02:33] Something`, `2) 4:32 — Something`.
 *
 * The separator between the timestamp and the title is optional, because plenty of lists use a
 * single space.
 */
private val LEADING_TIMESTAMP =
    Regex("""(?:[-–—•*]\s*)?[(\[]?(\d{1,3}:\d{1,2}(?::\d{1,2})?)[)\]]?\s*[-–—:·|]?\s*(\S.*)""")

/** `Something — 4:32`, `Something (1:02:33)`. */
private val TRAILING_TIMESTAMP =
    Regex("""(\S.*?)\s*[-–—(\[]\s*(\d{1,3}:\d{1,2}(?::\d{1,2})?)\s*[)\]]?""")

/** Punctuation a title is allowed to start with and that is not part of it. */
private val TITLE_LEAD_PUNCTUATION = charArrayOf('-', '–', '—', ':', '·', '|', '.', ')', ']')

/**
 * The fewest entries that count as a list.
 *
 * Two matches is almost always "0:00" plus one stray time in a sentence.
 */
private const val MIN_CHAPTERS = 3

/** How late the first chapter may start before the list stops looking like one. */
private const val FIRST_START_TOLERANCE_MS = 60_000L

/** Longer than this and the "title" is a paragraph that began with a timestamp. */
private const val MAX_TITLE_LENGTH = 120

/** A cap on the work done for a pathological description. */
private const val MAX_SCANNED_LINES = 500
