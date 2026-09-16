package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import md.borisveriga.megapodcastplayer.core.model.format.formatTimecode

/**
 * Turns the downloaded episodes into a Markdown list you can keep.
 *
 * This is the text companion to the audio export. The audio export copies the files, and this file
 * says what they were and where they came from: the show, its feed URL, and a link back to every
 * episode. Like [momentsMarkdown], it is written so it still makes sense on a machine that has
 * never had the app installed.
 *
 * The literals are document content, not interface text, for the same reason given in
 * `MomentExport.kt`.
 */

/** Bytes in a kilobyte, a megabyte and a gigabyte, in decimal units as storage is sold. */
private const val BYTES_PER_UNIT = 1_000.0

/** Separates the facts about one episode (date, duration, size) on its bullet. */
private const val FACT_SEPARATOR = " · "

/**
 * One downloaded episode, with the show fields the list needs.
 *
 * @property showTitle the show's title.
 * @property feedUrl the show's feed URL. It identifies the show and is the string that re-adds it.
 * @property episodeTitle the episode's title.
 * @property audioUrl the episode's stored audio URL, either a real URL or the YouTube sentinel.
 * @property publishedAtMs when the episode was published, epoch milliseconds, if the feed said.
 * @property durationMs how long the episode is, if known.
 * @property sizeBytes how large the audio is, if known.
 */
data class DownloadListEntry(
    val showTitle: String,
    val feedUrl: String,
    val episodeTitle: String,
    val audioUrl: String,
    val publishedAtMs: Long?,
    val durationMs: Long?,
    val sizeBytes: Long?,
)

/**
 * Every downloaded episode as one Markdown document.
 *
 * Shows are sorted by title and each gets a heading with its feed URL. Inside a show, episodes are
 * newest first, and undated ones go last. A bullet leaves out any fact that is unknown, and gets no
 * link when [episodeLink] refuses the URL.
 *
 * @param entries the episodes to list, in any order.
 * @param exportedAtMs when the export was made, epoch milliseconds.
 * @param zone time zone for the dates; defaults to the device's.
 * @return the document, ending in a newline.
 */
fun downloadListMarkdown(
    entries: List<DownloadListEntry>,
    exportedAtMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    appendLine("# MegaPodcastPlayer downloads")
    appendLine()
    appendLine(downloadListHeader(entries, exportedAtMs, zone))

    entries.groupBy { it.feedUrl }
        .entries
        .sortedBy { (_, showEntries) -> showEntries.first().showTitle.lowercase(Locale.ROOT) }
        .forEach { (feedUrl, showEntries) ->
            appendLine()
            appendLine("## " + oneLine(showEntries.first().showTitle))
            appendLine()
            appendLine("Feed: <$feedUrl>")
            appendLine()
            showEntries
                .sortedWith(compareBy(nullsLast(reverseOrder())) { it.publishedAtMs })
                .forEach { appendLine(downloadBullet(it, zone)) }
        }
}

/**
 * One episode as a list item: its title, then what is known about it, then its link.
 *
 * @param entry the episode to write.
 * @param zone time zone to render the publication date in.
 * @return the bullet line, without a trailing newline.
 */
private fun downloadBullet(entry: DownloadListEntry, zone: ZoneId): String = buildString {
    append("- **" + oneLine(entry.episodeTitle) + "**")
    val facts = listOfNotNull(
        entry.publishedAtMs?.let { formatDate(it, zone) },
        entry.durationMs?.takeIf { it > 0L }?.let(::formatTimecode),
        entry.sizeBytes?.takeIf { it > 0L }?.let(::formatSize),
    )
    if (facts.isNotEmpty()) append(" — " + facts.joinToString(FACT_SEPARATOR))
    episodeLink(entry.audioUrl)?.let { append(" — <$it>") }
}

/**
 * The line under the title: how many episodes, how much space when known, and the date.
 *
 * @param entries every episode in the document.
 * @param exportedAtMs when the export was made.
 * @param zone time zone to render the date in.
 * @return the header line.
 */
private fun downloadListHeader(
    entries: List<DownloadListEntry>,
    exportedAtMs: Long,
    zone: ZoneId,
): String {
    val noun = if (entries.size == 1) "episode" else "episodes"
    val totalBytes = entries.sumOf { it.sizeBytes?.coerceAtLeast(0L) ?: 0L }
    val size = if (totalBytes > 0L) ", " + formatSize(totalBytes) else ""
    return "${entries.size} $noun$size, exported ${formatDate(exportedAtMs, zone)}."
}

/**
 * A date in the reader's own locale, the same style as the moments export.
 *
 * @param epochMs the instant to render.
 * @param zone time zone to render it in.
 * @return the formatted date.
 */
private fun formatDate(epochMs: Long, zone: ZoneId): String =
    exportDate.withZone(zone).format(Instant.ofEpochMilli(epochMs))

/**
 * A size in decimal units: whole kilobytes and megabytes, gigabytes to one decimal place.
 *
 * Written with [Locale.US] digits, so the decimal point is the same in every export.
 *
 * @param bytes a positive byte count.
 * @return e.g. `84 MB` or `1.4 GB`.
 */
internal fun formatSize(bytes: Long): String {
    val kilobytes = bytes / BYTES_PER_UNIT
    val megabytes = kilobytes / BYTES_PER_UNIT
    val gigabytes = megabytes / BYTES_PER_UNIT
    return when {
        gigabytes >= 1.0 -> String.format(Locale.US, "%.1f GB", gigabytes)
        megabytes >= 1.0 -> String.format(Locale.US, "%.0f MB", megabytes)
        kilobytes >= 1.0 -> String.format(Locale.US, "%.0f KB", kilobytes)
        else -> "$bytes B"
    }
}

/**
 * Collapses line breaks and runs of spaces, so a title from a feed cannot break the Markdown.
 *
 * @param text the text to flatten.
 * @return the text on one line.
 */
private fun oneLine(text: String): String = text.trim().replace(whitespaceRun, " ")
