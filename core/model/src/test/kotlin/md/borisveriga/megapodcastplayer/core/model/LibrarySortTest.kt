package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LibrarySort] and [orderedBy].
 *
 * Three things here are easy to get wrong and invisible until a library is large. A show whose feed
 * dates nothing must not float to the top of a recency list; two shows that agree on the sort key
 * must not swap places between emissions, which is what a comparator with no tie-break does; and
 * *most unplayed* must count episodes that were never started rather than episodes not finished.
 */
class LibrarySortTest {

    private fun show(
        id: String,
        title: String,
        unplayedCount: Int = 0,
        latestPublishedAt: Instant? = null,
    ) = PodcastWithCounts(
        podcast = Podcast(
            id = id,
            itunesId = null,
            title = title,
            author = "Someone",
            feedUrl = "https://example.com/$id.rss",
            artworkUrl = null,
            description = "",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
        ),
        episodeCount = 10,
        newEpisodeCount = 0,
        downloadedCount = 0,
        unplayedCount = unplayedCount,
        latestPublishedAt = latestPublishedAt,
    )

    private val january = Instant.parse("2026-01-01T00:00:00Z")
    private val august = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun `the hand-made order is the order the library arrived in`() {
        val library = listOf(show("z", "Zeitgeist"), show("a", "Acquired"))

        assertEquals(library, library.orderedBy(LibrarySort.MANUAL))
    }

    @Test
    fun `only the hand-made order can be dragged`() {
        assertTrue(LibrarySort.MANUAL.isReorderable)
        LibrarySort.entries.filter { it != LibrarySort.MANUAL }.forEach { sort ->
            assertFalse("$sort must not offer a drag", sort.isReorderable)
        }
    }

    @Test
    fun `A to Z ignores case`() {
        val library = listOf(show("b", "banana"), show("a", "Apple"), show("c", "Cherry"))

        assertEquals(
            listOf("Apple", "banana", "Cherry"),
            library.orderedBy(LibrarySort.TITLE).map { it.podcast.title },
        )
    }

    @Test
    fun `recently updated puts the newest publication first`() {
        val library = listOf(
            show("old", "Old", latestPublishedAt = january),
            show("new", "New", latestPublishedAt = august),
        )

        assertEquals(
            listOf("New", "Old"),
            library.orderedBy(LibrarySort.RECENTLY_UPDATED).map { it.podcast.title },
        )
    }

    /**
     * A feed that dates nothing has not published a long time ago; it has said nothing at all, and
     * a comparator treating null as the epoch would fling it to one end of the list.
     */
    @Test
    fun `a show with no dated episode sorts last by recency, not first`() {
        val library = listOf(
            show("undated", "Undated"),
            show("old", "Old", latestPublishedAt = january),
        )

        assertEquals(
            listOf("Old", "Undated"),
            library.orderedBy(LibrarySort.RECENTLY_UPDATED).map { it.podcast.title },
        )
    }

    @Test
    fun `most unplayed counts down from the biggest backlog`() {
        val library = listOf(
            show("small", "Small", unplayedCount = 2),
            show("big", "Big", unplayedCount = 40),
        )

        assertEquals(
            listOf("Big", "Small"),
            library.orderedBy(LibrarySort.MOST_UNPLAYED).map { it.podcast.title },
        )
    }

    /**
     * Without a tie-break, two shows with the same key keep whatever order the previous emission
     * left them in — a list that reshuffles itself while nobody touches it.
     */
    @Test
    fun `shows that agree on the key are broken apart by title`() {
        val library = listOf(
            show("z", "Zeitgeist", unplayedCount = 5, latestPublishedAt = january),
            show("a", "Acquired", unplayedCount = 5, latestPublishedAt = january),
        )

        assertEquals(
            listOf("Acquired", "Zeitgeist"),
            library.orderedBy(LibrarySort.MOST_UNPLAYED).map { it.podcast.title },
        )
        assertEquals(
            listOf("Acquired", "Zeitgeist"),
            library.orderedBy(LibrarySort.RECENTLY_UPDATED).map { it.podcast.title },
        )
    }
}
