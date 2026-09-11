package md.borisveriga.megapodcastplayer.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.database.model.QueueEntryEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [QueueDao], focused on the rule that the queue holds stored episodes only.
 *
 * The player's live queue is not so constrained — an episode played from a search preview has no
 * row — and before these writes filtered, the table's foreign key turned every mirror of such a
 * queue into a crash. Each test pins one half of the rule: an unstored id is dropped, and dropping
 * it leaves the rest of the queue intact and in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueDaoTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var queueDao: QueueDao

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "notes",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = 1_000L,
        sizeBytes = null,
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()
        queueDao = database.queueDao()

        database.podcastDao().upsert(podcast)
        database.episodeDao().upsertFromFeed(listOf(episode("a"), episode("b"), episode("c")))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `only stored episodes are reported as stored`() = runTest {
        assertEquals(listOf("a"), queueDao.getStoredEpisodeIds(listOf("a", "search-preview")))
    }

    @Test
    fun `enqueueing an unstored episode stores nothing`() = runTest {
        queueDao.enqueue("search-preview")

        assertEquals(emptyList<QueueEntryEntity>(), queueDao.getEntries())
    }

    @Test
    fun `replacing the queue drops unstored ids and numbers the rest without a gap`() = runTest {
        queueDao.replaceAll(listOf("c", "search-preview", "a"))

        assertEquals(
            listOf(
                QueueEntryEntity(episodeId = "c", position = 0),
                QueueEntryEntity(episodeId = "a", position = 1),
            ),
            queueDao.getEntries(),
        )
    }

    @Test
    fun `replacing the queue with only an unstored episode leaves it empty`() = runTest {
        // A search preview played on its own: the player's queue is that one episode, and its
        // durable mirror is the queue with nothing stored in it.
        queueDao.enqueue("a")

        queueDao.replaceAll(listOf("search-preview"))

        assertEquals(emptyList<QueueEntryEntity>(), queueDao.getEntries())
    }
}
