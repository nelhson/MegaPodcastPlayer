package md.borisveriga.megapodcastplayer.core.model

/**
 * Which downloads the downloads screen shows, by what kind of media they hold.
 *
 * Every row on that screen is an episode's audio download — a video is never downloaded without
 * its sound, and deleting the sound takes the picture with it — so the useful split is not
 * "audio or video" but "with a picture or without one". [AUDIO] therefore means *sound only*:
 * read as "has sound" it would be every row, the same as [ALL], and a chip that changes nothing
 * reads as a chip that is broken. [AUDIO] and [VIDEO] between them cover [ALL] exactly once.
 *
 * Not stored, for the reason [LibraryFilter] is not: a narrowing is something done to find a
 * thing, and a screen that opened showing half its downloads because of a chip tapped last week
 * would look like data loss.
 */
enum class DownloadKindFilter {

    /** Every download. What the screen opens with. */
    ALL,

    /** Downloads with no video beside them: sound only. */
    AUDIO,

    /**
     * Downloads with a video beside them, in any state — a picture still arriving or one that
     * failed is as much what this chip is looked for as one that is ready.
     */
    VIDEO,
    ;

    /**
     * Whether one download survives this filter.
     *
     * @param hasVideo whether the episode has a video download of any state.
     * @return true when the row stays on screen.
     */
    fun matches(hasVideo: Boolean): Boolean = when (this) {
        ALL -> true
        AUDIO -> !hasVideo
        VIDEO -> hasVideo
    }
}

/**
 * Applies a [DownloadKindFilter] to the tracked downloads.
 *
 * A function beside the rule rather than a `filter` at the call site, so that "what the downloads
 * screen is showing" has one definition and can be tested without a composition.
 *
 * @param filter what to narrow to.
 * @param videoDownloads every episode's video download, keyed by episode id; an id that is absent
 *   has no picture on the way or on the device.
 * @return the downloads that survive, in the order they arrived — which is the user's arrangement.
 */
fun List<EpisodeWithShow>.filteredBy(
    filter: DownloadKindFilter,
    videoDownloads: Map<String, VideoDownload>,
): List<EpisodeWithShow> =
    if (filter == DownloadKindFilter.ALL) {
        this
    } else {
        filter { filter.matches(hasVideo = it.episode.id in videoDownloads) }
    }

/**
 * Writes a reorder made on a filtered view back into the full arrangement.
 *
 * The stored order of downloads is a whole list, replaced on every drag. A drag on a filtered
 * screen only sees some of the rows, and storing just those would drop every hidden row out of
 * the arrangement — they would sort as never placed, losing the order the user gave them. So the
 * reordered rows are poured back into the slots the visible rows held in the full order, in their
 * new sequence, and every hidden row stays in the slot it was in.
 *
 * With nothing hidden, every slot is a visible one and the result is [visibleReordered] itself.
 *
 * @param fullOrder every row of the arrangement the drag was made in, first row first.
 * @param visibleReordered the rows that were on screen, in the order the drag left them.
 * @return the full arrangement with the visible rows re-sequenced. A visible id that is not in
 *   [fullOrder] — a row that arrived while the drag was in flight — is placed after the rest.
 */
fun mergeVisibleOrder(fullOrder: List<String>, visibleReordered: List<String>): List<String> {
    val visible = visibleReordered.toSet()
    val known = fullOrder.toSet()
    val incoming = visibleReordered.filter { it in known }.iterator()
    val merged = fullOrder.map { id ->
        // A visible slot takes the next row in its new sequence; a hidden one keeps its row.
        if (id in visible && incoming.hasNext()) incoming.next() else id
    }
    return merged + visibleReordered.filter { it !in known }
}
