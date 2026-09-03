package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PodcastDao]'s hand ordering.
 *
 * The library used to sort itself alphabetically in SQL, which could not be wrong. Now the order is
 * data, and data can be: a new subscription landing in the middle of an arrangement, or a reorder
 * that drops a row, are both silent failures that only show up as a library that looks shuffled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastDaoTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var podcastDao: PodcastDao

    private fun podcast(id: String, title: String) = PodcastEntity(
        id = id,
        itunesId = null,
        title = title,
        author = "",
        feedUrl = "https://feeds.example.com/$id.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    /** Adds a show the way the repository does: at the end of whatever is already there. */
    private suspend fun subscribe(id: String, title: String) {
        podcastDao.upsert(podcast(id, title).copy(sortOrder = podcastDao.nextSortOrder()))
    }

    private suspend fun titles(): List<String> =
        podcastDao.observeAllWithCounts().first().map { it.podcast.title }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        podcastDao = database.podcastDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the first show subscribed to sits at the top`() = runTest {
        subscribe("a", "Acquired")

        assertEquals(0, checkNotNull(podcastDao.getById("a")).sortOrder)
    }

    @Test
    fun `a new subscription is appended rather than slotted in alphabetically`() = runTest {
        subscribe("z", "Zeitgeist")
        subscribe("a", "Acquired")

        // Alphabetically "Acquired" is first, and that is exactly what must not happen: the
        // library is arranged by hand, so a new show goes where the user will find it — the end —
        // not into the middle of an order they built.
        assertEquals(listOf("Zeitgeist", "Acquired"), titles())
    }

    @Test
    fun `reordering writes the whole arrangement`() = runTest {
        subscribe("a", "Acquired")
        subscribe("p", "Podlodka Podcast")
        subscribe("z", "Zeitgeist")

        podcastDao.reorder(listOf("z", "a", "p"))

        assertEquals(listOf("Zeitgeist", "Acquired", "Podlodka Podcast"), titles())
    }

    @Test
    fun `reordering keeps the shows themselves intact`() = runTest {
        subscribe("a", "Acquired")
        subscribe("z", "Zeitgeist")

        podcastDao.reorder(listOf("z", "a"))

        // A per-row UPDATE rather than the queue's delete-and-reinsert: a podcast row carries the
        // subscription itself, so deleting it to reposition it would take the show with it.
        val moved = checkNotNull(podcastDao.getById("z"))
        assertEquals("https://feeds.example.com/z.rss", moved.feedUrl)
        assertEquals(2, podcastDao.getAll().size)
    }

    @Test
    fun `reordering ignores shows the library no longer has`() = runTest {
        subscribe("a", "Acquired")
        subscribe("z", "Zeitgeist")

        // A drag that raced an unsubscribe reports an id that has since gone; that has to be a
        // no-op rather than throwing halfway and leaving the library half-reordered.
        podcastDao.reorder(listOf("z", "gone", "a"))

        assertEquals(listOf("Zeitgeist", "Acquired"), titles())
    }

    @Test
    fun `a show added after a reorder still goes to the end`() = runTest {
        subscribe("a", "Acquired")
        subscribe("z", "Zeitgeist")
        podcastDao.reorder(listOf("z", "a"))

        subscribe("p", "Podlodka Podcast")

        assertEquals(listOf("Zeitgeist", "Acquired", "Podlodka Podcast"), titles())
    }
}
