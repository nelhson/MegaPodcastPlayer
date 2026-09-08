package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import md.borisveriga.megapodcastplayer.core.model.format.formatTimecode
import md.borisveriga.megapodcastplayer.core.model.format.positionSeconds

/**
 * Turns saved moments into text that outlives the app.
 *
 * The database is recreated rather than migrated and the app is sideloaded, so anything a moment
 * knows is one release away from being gone. That makes the export the feature's real payload
 * rather than a convenience: every line it writes carries a *link*, so a document read on a machine
 * that has never had MegaPodcastPlayer installed still leads back to the audio — and so a user
 * starting from an empty library can re-add every show it names.
 *
 * Two shapes, one vocabulary. [momentShareText] is one moment handed to whatever the share sheet
 * offers; [momentsMarkdown] is the whole collection as a document. Both are built here, in a pure
 * module, so they can be tested without a device.
 *
 * The literals below are document content rather than interface text, which is why they are not in
 * a `strings.xml`: `:core:model` has no resources, and a Markdown heading that changed with the
 * device language would make two exports by the same user impossible to read as one file.
 */

/** Seconds parameter YouTube reads a start offset from; `s`-suffixed, as its own share links are. */
private const val YOUTUBE_TIME_PARAM = "&t="

/** How a watch URL is spelled. Only ever built from an id [isPlayableMediaUrl] has validated. */
private const val YOUTUBE_WATCH_PREFIX = "https://www.youtube.com/watch?v="

/**
 * The Media Fragments offset an ordinary media URL takes.
 *
 * A fragment rather than a query parameter deliberately: it is never sent to the server, so a URL
 * carrying one still fetches the same bytes from a host that has never heard of it, while a player
 * that does understand fragments starts in the right place.
 */
private const val MEDIA_FRAGMENT_PREFIX = "#t="

/** Date the export header is stamped with, in the reader's own locale. */
private val EXPORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Collapses any run of whitespace, so a note typed over three lines still fits on one bullet. */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * A link that opens [audioUrl] at [positionMs].
 *
 * Three cases, and the third is the honest one:
 *  - a `youtube://video/<id>` sentinel becomes a real watch URL with a start offset, which is what
 *    makes a shared moment from a video open on the second it was marked;
 *  - an ordinary `http(s)` enclosure gains a Media Fragments offset, which a browser honours for a
 *    direct media file and every other reader ignores harmlessly;
 *  - anything else returns null rather than a guess. A moment is allowed to have no link — the show
 *    and the timecode beside it still say where to look.
 *
 * The URL is put through [isPlayableMediaUrl] first. It arrived from a feed, and a link built from
 * it is about to be handed to another application entirely.
 *
 * @param audioUrl the episode's stored audio URL.
 * @param positionMs where in the episode the moment sits.
 * @return the link, or null when [audioUrl] cannot carry one.
 */
fun momentLink(audioUrl: String, positionMs: Long): String? {
    if (!isPlayableMediaUrl(audioUrl)) return null
    val seconds = positionSeconds(positionMs)

    val videoId = youTubeVideoIdOrNull(audioUrl)
    if (videoId != null) return YOUTUBE_WATCH_PREFIX + videoId + YOUTUBE_TIME_PARAM + seconds + "s"

    // An enclosure that already carries a fragment gets no second one: whatever the publisher put
    // there is more likely to be meaningful than an offset appended after it.
    if (audioUrl.contains('#')) return audioUrl
    return audioUrl + MEDIA_FRAGMENT_PREFIX + seconds
}

/**
 * One moment, as a message.
 *
 * Written to be read by a person in a chat window rather than parsed: the note first, because that
 * is the reason the moment exists, then what it is a moment *of*, then the link. A moment with no
 * note leads with the episode instead, so the message never opens on a blank line.
 *
 * @param moment the moment and its episode.
 * @return the text to share.
 */
fun momentShareText(moment: MomentWithEpisode): String = buildString {
    moment.moment.note?.takeIf { it.isNotBlank() }?.let { note ->
        appendLine(note.trim())
        appendLine()
    }
    appendLine(moment.showTitle + " — " + moment.episodeTitle)
    appendLine("at " + formatTimecode(moment.moment.positionMs))
    moment.link?.let { appendLine(it) }
}.trimEnd()

/**
 * One episode, as a message.
 *
 * The same shape as [momentShareText] with the note left out, because it is the same job: say what
 * this is, then give the reader a way to hear it. The link is [momentLink] at position zero — an
 * episode shared from the sheet is being recommended from the beginning, not from where the sender
 * happens to have got to.
 *
 * @param showTitle the show the episode belongs to.
 * @param episodeTitle the episode.
 * @param audioUrl the episode's stored audio URL; a URL that cannot carry a link simply omits one.
 * @return the text to share.
 */
fun episodeShareText(showTitle: String, episodeTitle: String, audioUrl: String): String =
    buildString {
        appendLine("$showTitle — $episodeTitle")
        momentLink(audioUrl, positionMs = 0L)?.let(::appendLine)
    }.trimEnd()

/**
 * One show, as a message.
 *
 * A name and the feed URL, and nothing else. The URL is the show's identity everywhere outside this
 * app — it is what the add field takes, what a backup carries and what every other podcast app
 * subscribes from — so a share that named a web page instead would be a link the recipient has to
 * translate before they can follow the show.
 *
 * @param showTitle the show.
 * @param feedUrl its feed.
 * @return the text to share.
 */
fun showShareText(showTitle: String, feedUrl: String): String =
    buildString {
        appendLine(showTitle)
        append(feedUrl)
    }

/**
 * Every moment as one Markdown document.
 *
 * Grouped show, then episode, then position — the order the moments were *heard* in rather than the
 * order they were saved, because a document is read as a record of a show and a list sorted by save
 * time interleaves four of them.
 *
 * Each show heading carries its feed URL, which is the part that makes this an import as well as an
 * export: that is the string the add-a-show field and a backup both take, so a user with nothing
 * but this file can rebuild the library it describes.
 *
 * @param moments the moments to write, in any order.
 * @param exportedAtMs when the export was made, epoch milliseconds.
 * @param zone time zone for the header date; defaults to the device's.
 * @return the document, ending in a newline.
 */
fun momentsMarkdown(
    moments: List<MomentWithEpisode>,
    exportedAtMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    appendLine("# MegaPodcastPlayer moments")
    appendLine()
    appendLine(exportHeader(moments.size, exportedAtMs, zone))

    moments.groupBy { it.feedUrl }
        .entries
        .sortedBy { (_, showMoments) -> showMoments.first().showTitle.lowercase(Locale.ROOT) }
        .forEach { (feedUrl, showMoments) ->
            appendLine()
            appendLine("## " + showMoments.first().showTitle)
            appendLine()
            appendLine("Feed: <" + feedUrl + ">")
            appendShow(showMoments)
        }
}

/**
 * Writes one show's episodes and their moments.
 *
 * Episodes are grouped by their audio URL rather than by title: two episodes of a show may share a
 * title, and the URL is the thing a link is built from anyway.
 *
 * @param showMoments every moment belonging to one show.
 */
private fun StringBuilder.appendShow(showMoments: List<MomentWithEpisode>) {
    showMoments.groupBy { it.audioUrl }.forEach { (_, episodeMoments) ->
        appendLine()
        appendLine("### " + episodeMoments.first().episodeTitle)
        appendLine()
        episodeMoments.sortedBy { it.moment.positionMs }.forEach { entry ->
            appendLine(momentBullet(entry))
        }
    }
}

/**
 * One moment as a list item: its timecode, its note, and its link.
 *
 * @param entry the moment to write.
 * @return the bullet line, without a trailing newline.
 */
private fun momentBullet(entry: MomentWithEpisode): String = buildString {
    append("- **" + formatTimecode(entry.moment.positionMs) + "**")
    entry.moment.note?.takeIf { it.isNotBlank() }?.let { note ->
        append(" — " + note.trim().replace(WHITESPACE_RUN, " "))
    }
    entry.link?.let { append(" — <" + it + ">") }
}

/**
 * The line under the title, saying how much is here and when it was written.
 *
 * @param count how many moments the document holds.
 * @param exportedAtMs when the export was made.
 * @param zone time zone to render the date in.
 * @return the header line.
 */
private fun exportHeader(count: Int, exportedAtMs: Long, zone: ZoneId): String {
    val date = EXPORT_DATE.withZone(zone).format(Instant.ofEpochMilli(exportedAtMs))
    val noun = if (count == 1) "moment" else "moments"
    return "$count $noun, exported $date."
}
