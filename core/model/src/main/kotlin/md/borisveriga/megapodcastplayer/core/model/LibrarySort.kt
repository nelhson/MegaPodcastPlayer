package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant

/**
 * The order the library lists its shows in.
 *
 * Four, where a show's episode list gets two, because a library is not a chronology. It is a set of
 * things the user chose, and the useful questions about it are different in kind: *which of these
 * has just published*, *where is the one I can name*, *what have I fallen behind on* — and, still,
 * *the arrangement I made by hand*.
 *
 * [MANUAL] is not one option among four so much as the absence of the other three: it is the order
 * stored in the database, the one the drag gesture writes, and the only one a drag can be offered
 * in. Choosing any other order turns dragging off for as long as it is chosen, because a gesture
 * that writes an arrangement nobody can see is a gesture that quietly loses work.
 */
enum class LibrarySort {

    /** The arrangement the user dragged into place. The stored order, and the default. */
    MANUAL,

    /** Whichever show published most recently, first. Shows with no dated episode go last. */
    RECENTLY_UPDATED,

    /** By title, ignoring case. For finding a show whose name is known and whose cover is not. */
    TITLE,

    /** Most never-started episodes first — what has been fallen behind on. */
    MOST_UNPLAYED,
    ;

    /** Whether shows can be dragged around while this order is applied. */
    val isReorderable: Boolean get() = this == MANUAL

    companion object {

        /**
         * What a library with no stored preference uses.
         *
         * Manual, because it is what the library did before this control existed, and because the
         * order a user arranged by hand is the one answer no computed sort can reproduce.
         */
        val DEFAULT: LibrarySort = MANUAL
    }
}

/**
 * Puts a library in the chosen order.
 *
 * Named `orderedBy` rather than `sortedBy` for the same reason [List.orderedBy] on episodes is: the
 * standard library's `sortedBy` takes a selector, and an overload that differs only in its argument
 * type is one refactor away from being resolved to silently.
 *
 * Applied over the loaded list rather than in SQL because the library is tens of rows already in
 * memory, and because [LibrarySort.MANUAL] is the order the query returns — sorting in Kotlin keeps
 * one code path for all four instead of a second query for three of them.
 *
 * Every computed order breaks ties on title, so that two shows that agree on the sort key do not
 * swap places between emissions; [LibrarySort.MANUAL] has no ties to break.
 *
 * @param sort the order to apply.
 * @return the shows in that order.
 */
fun List<PodcastWithCounts>.orderedBy(sort: LibrarySort): List<PodcastWithCounts> = when (sort) {
    LibrarySort.MANUAL -> this

    // Nulls last: a show whose feed dates nothing has not "published a long time ago", it has said
    // nothing at all, and floating it to the top of a recency list would be a lie.
    LibrarySort.RECENTLY_UPDATED -> sortedWith(
        compareByDescending<PodcastWithCounts> { it.latestPublishedAt ?: NEVER }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.podcast.title },
    )

    LibrarySort.TITLE -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.podcast.title })

    LibrarySort.MOST_UNPLAYED -> sortedWith(
        compareByDescending<PodcastWithCounts> { it.unplayedCount }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.podcast.title },
    )
}

/**
 * The instant a show with no dated episode sorts as.
 *
 * `Instant.MIN` rather than the epoch: a feed that publishes a 1970 date is unusual but not
 * impossible, and it should still outrank a show that published nothing.
 */
private val NEVER: Instant = Instant.MIN
