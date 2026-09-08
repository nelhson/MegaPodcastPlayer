package md.borisveriga.megapodcastplayer.core.model

/**
 * How the moments screen is narrowed and arranged.
 *
 * Not persisted, and that is the decision rather than an omission. A moments list is opened to find
 * one thing; a narrowing that survived until next time would greet the user with most of their
 * moments missing and nothing on screen saying why they had gone. The controls are visible while
 * they are in force and forgotten when the screen is left.
 *
 * @property query text to look for, matched case-insensitively. Blank narrows nothing.
 * @property feedUrl the show to keep, or null for every show. The feed URL rather than the title,
 *   because two shows can share a name and the feed URL is a show's identity everywhere else here.
 * @property groupByShow whether to gather the list under show headings instead of listing it in
 *   time order.
 */
data class MomentsFilter(
    val query: String = "",
    val feedUrl: String? = null,
    val groupByShow: Boolean = false,
) {
    /** True when the list is being narrowed at all; grouping rearranges but hides nothing. */
    val isNarrowing: Boolean get() = query.isNotBlank() || feedUrl != null
}

/**
 * A show that has at least one moment in it, for the filter's menu.
 *
 * Derived from the moments rather than read from the library, because a show with no moments in it
 * is not an answer this menu can give — offering it would be offering an empty list.
 *
 * @property feedUrl the show's identity, and what the filter stores.
 * @property title what to draw.
 * @property momentCount how many moments it holds, so the menu says which shows are worth opening.
 */
data class MomentShow(
    val feedUrl: String,
    val title: String,
    val momentCount: Int,
)

/**
 * One show's moments, under its own heading.
 *
 * @property feedUrl the show's identity; the key a list uses so a section keeps its identity as
 *   moments are added to and removed from it.
 * @property title the heading.
 * @property moments the show's moments, in the order the whole list was in.
 */
data class MomentGroup(
    val feedUrl: String,
    val title: String,
    val moments: List<MomentWithEpisode>,
)

/**
 * Keeps only the moments a filter admits.
 *
 * **What the search matches is the interesting part: the note and the episode, and not the show.**
 * A note is the only thing in this app the user wrote, so it is the first thing they would type a
 * word from; an episode title is what identifies a moment that has no note, and without it half a
 * library's moments would be unsearchable. The show is deliberately left out — it has a control of
 * its own two chips away, and letting a typed word do the same job would mean a search for "radio"
 * silently returning every moment in a show called Radio T alongside the one about radios.
 *
 * @param filter what to keep.
 * @return the moments that survive, in the order they arrived.
 */
fun List<MomentWithEpisode>.narrowedBy(filter: MomentsFilter): List<MomentWithEpisode> {
    val query = filter.query.trim()
    return filter { entry ->
        val matchesShow = filter.feedUrl == null || entry.feedUrl == filter.feedUrl
        val matchesQuery = query.isEmpty() ||
            entry.moment.note?.contains(query, ignoreCase = true) == true ||
            entry.episodeTitle.contains(query, ignoreCase = true)
        matchesShow && matchesQuery
    }
}

/**
 * The shows these moments are in, most moments first.
 *
 * Most first rather than alphabetical: the menu exists to answer "which show was that in", and the
 * show someone has marked forty things in is a likelier answer than the one they marked once. Ties
 * fall back to the title so the order is stable rather than whatever the list happened to hold.
 *
 * @return one entry per show, never empty for a non-empty list.
 */
fun List<MomentWithEpisode>.showsWithMoments(): List<MomentShow> =
    groupBy { it.feedUrl }
        .map { (feedUrl, entries) ->
            MomentShow(
                feedUrl = feedUrl,
                title = entries.first().showTitle,
                momentCount = entries.size,
            )
        }
        .sortedWith(compareByDescending<MomentShow> { it.momentCount }.thenBy { it.title })

/**
 * Gathers the moments under show headings.
 *
 * The shows come out in the order their *first* moment appears in the list, and each show's moments
 * keep the list's own order. That is the least surprising arrangement of a list that was newest
 * first: the show marked most recently is at the top, and inside it the most recent mark is still
 * first. Sorting the sections alphabetically would move the thing the user came for.
 *
 * @return one group per show, in first-appearance order.
 */
fun List<MomentWithEpisode>.groupedByShow(): List<MomentGroup> =
    groupBy { it.feedUrl }
        .map { (feedUrl, entries) ->
            MomentGroup(
                feedUrl = feedUrl,
                title = entries.first().showTitle,
                moments = entries,
            )
        }
