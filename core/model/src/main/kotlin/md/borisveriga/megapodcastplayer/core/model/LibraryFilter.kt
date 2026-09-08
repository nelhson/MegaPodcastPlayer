package md.borisveriga.megapodcastplayer.core.model

/**
 * What the library is narrowed to.
 *
 * Two independent narrowings rather than a list of mutually exclusive chips, because they answer
 * different questions and are genuinely used together: *the one with "history" in the name* and
 * *the ones with something new* is a single thought when the library is sixty shows long.
 *
 * Not stored. A layout or a sort order is a standing preference — how the user likes to be shown
 * their library — while a narrowing is a thing done to find something, and a library that opened
 * showing four of its shows because of a chip tapped last week would look like data loss.
 *
 * @property query free text matched against the show's title and author; blank matches everything.
 * @property onlyWithNewEpisodes when true, only shows with at least one episode that arrived since
 *   the user last looked. *New*, not *unplayed* — see [PodcastWithCounts.newEpisodeCount].
 */
data class LibraryFilter(
    val query: String = "",
    val onlyWithNewEpisodes: Boolean = false,
) {
    /** Whether this narrows anything at all. */
    val isActive: Boolean get() = query.isNotBlank() || onlyWithNewEpisodes

    /**
     * Whether one show survives this filter.
     *
     * Title and author both, so that typing a host's name finds their shows: an author is how a
     * user refers to half the things in a library, and matching only the title would make the
     * field feel broken exactly when it is being trusted.
     *
     * Case-insensitive and unanchored — a substring match rather than a prefix — because nobody
     * remembers which word a podcast's name starts with.
     *
     * @param show the library entry to test.
     * @return true when it should stay on screen.
     */
    fun matches(show: PodcastWithCounts): Boolean {
        if (onlyWithNewEpisodes && show.newEpisodeCount == 0) return false
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return show.podcast.title.contains(needle, ignoreCase = true) ||
            show.podcast.author.contains(needle, ignoreCase = true)
    }

    companion object {
        /** A filter that narrows nothing; what the screen opens with. */
        val NONE = LibraryFilter()
    }
}

/**
 * Applies a filter to a library.
 *
 * A function beside the rule rather than a `filter` call at each call site, so that "what the
 * library is showing" has one definition and can be tested without a composition.
 *
 * @param filter what to narrow to.
 * @return the shows that survive, in the order they arrived.
 */
fun List<PodcastWithCounts>.filteredBy(filter: LibraryFilter): List<PodcastWithCounts> =
    if (filter.isActive) filter { filter.matches(it) } else this
