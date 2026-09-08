package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LibraryFilter] and [filteredBy].
 *
 * The rule worth pinning is what the chip means. *New* is what arrived since the user last looked;
 * a show with a large backlog and nothing new must be hidden by it, which is precisely the show a
 * filter written against the unplayed count would keep.
 *
 * The rest is about the field being forgiving in the ways a person expects: any part of the name,
 * either case, and the author counts too — half the shows in a library are remembered by who makes
 * them.
 */
class LibraryFilterTest {

    private fun show(
        title: String,
        author: String = "Someone",
        newEpisodeCount: Int = 0,
        unplayedCount: Int = 0,
    ) = PodcastWithCounts(
        podcast = Podcast(
            id = title,
            itunesId = null,
            title = title,
            author = author,
            feedUrl = "https://example.com/$title.rss",
            artworkUrl = null,
            description = "",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
        ),
        episodeCount = 10,
        newEpisodeCount = newEpisodeCount,
        downloadedCount = 0,
        unplayedCount = unplayedCount,
    )

    private val library = listOf(
        show("Podlodka Podcast", author = "Егор Толстой", newEpisodeCount = 3),
        show("Acquired", author = "Ben Gilbert", unplayedCount = 40),
        show("Zeitgeist", author = "Ben Gilbert"),
    )

    @Test
    fun `a filter with nothing in it narrows nothing`() {
        assertFalse(LibraryFilter.NONE.isActive)
        assertEquals(library, library.filteredBy(LibraryFilter.NONE))
    }

    @Test
    fun `whitespace alone is not a narrowing`() {
        assertFalse(LibraryFilter(query = "   ").isActive)
        assertEquals(library, library.filteredBy(LibraryFilter(query = "   ")))
    }

    @Test
    fun `any part of the title matches, in either case`() {
        val matched = library.filteredBy(LibraryFilter(query = "LODKA"))

        assertEquals(listOf("Podlodka Podcast"), matched.map { it.podcast.title })
    }

    @Test
    fun `the author is matched as well as the title`() {
        val matched = library.filteredBy(LibraryFilter(query = "gilbert"))

        assertEquals(listOf("Acquired", "Zeitgeist"), matched.map { it.podcast.title })
    }

    @Test
    fun `surrounding space is trimmed rather than made to match nothing`() {
        val matched = library.filteredBy(LibraryFilter(query = "  acquired  "))

        assertEquals(listOf("Acquired"), matched.map { it.podcast.title })
    }

    /**
     * *Has new episodes* means arrived since you last looked. "Acquired" has forty episodes never
     * started and nothing new, and it is the show this chip has to hide.
     */
    @Test
    fun `the new-episodes chip is about arrival, not about a backlog`() {
        val matched = library.filteredBy(LibraryFilter(onlyWithNewEpisodes = true))

        assertEquals(listOf("Podlodka Podcast"), matched.map { it.podcast.title })
    }

    @Test
    fun `the two narrowings apply together`() {
        val filter = LibraryFilter(query = "pod", onlyWithNewEpisodes = true)

        assertTrue(filter.isActive)
        assertEquals(
            listOf("Podlodka Podcast"),
            library.filteredBy(filter).map { it.podcast.title },
        )
    }

    @Test
    fun `a filter matching nothing empties the list rather than falling back to everything`() {
        assertEquals(emptyList<PodcastWithCounts>(), library.filteredBy(LibraryFilter("nope")))
    }
}
