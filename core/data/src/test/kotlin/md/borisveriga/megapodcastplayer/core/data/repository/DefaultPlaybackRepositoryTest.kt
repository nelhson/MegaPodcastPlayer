package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.ResumePoint
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloader
import md.borisveriga.megapodcastplayer.core.model.PlayerMode
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DefaultPlaybackRepository] against a real in-memory database.
 *
 * The database is real for the same reason [OfflineFirstPodcastRepositoryTest] uses one: the
 * behaviour under test — queue ordering, and a finished episode leaving the queue — is expressed in
 * SQL, and a fake DAO would only test the fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultPlaybackRepositoryTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var preferences: UserPreferencesDataSource
    private lateinit var repository: DefaultPlaybackRepository

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = "https://art/show.jpg",
        description = "",
        addedAt = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String, durationMs: Long? = 60_000L) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "notes",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = durationMs,
        publishedAt = 1_000L,
        sizeBytes = null,
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()

        preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        repository = DefaultPlaybackRepository(
            queueDao = database.queueDao(),
            episodeDao = database.episodeDao(),
            userPreferences = preferences,
            // Relaxed: these tests are about queue and progress SQL. Delete-after-playing is
            // covered in MediaDownloadRepositoryTest, where the downloader is the point.
            downloader = mockk(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        database.podcastDao().upsert(podcast)
        database.episodeDao().upsertFromFeed(listOf(episode("a"), episode("b"), episode("c")))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the queue is observed in play order with show details attached`() = runTest {
        repository.enqueue("b")
        repository.enqueue("a")

        val queue = repository.observeQueue().first()

        assertEquals(listOf("b", "a"), queue.map { it.episode.id })
        assertEquals("Podlodka Podcast", queue.first().showTitle)
        // The episodes have no artwork of their own, so the show's stands in.
        assertEquals("https://art/show.jpg", queue.first().artworkUrl)
    }

    @Test
    fun `enqueueing an episode twice does not duplicate it`() = runTest {
        repository.enqueue("a")
        repository.enqueue("a")

        assertEquals(listOf("a"), repository.observeQueue().first().map { it.episode.id })
    }

    @Test
    fun `playableEpisodes preserves the caller's order`() = runTest {
        // SQLite is free to return `IN (...)` rows in any order; play order is the caller's.
        val loaded = repository.playableEpisodes(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), loaded.map { it.episode.id })
    }

    @Test
    fun `playableEpisodes drops ids that are no longer stored`() = runTest {
        val loaded = repository.playableEpisodes(listOf("a", "gone", "b"))

        assertEquals(listOf("a", "b"), loaded.map { it.episode.id })
    }

    @Test
    fun `recording a position stores it without marking the episode played`() = runTest {
        repository.recordPosition(episodeId = "a", positionMs = 42_000L, durationMs = null)

        val stored = checkNotNull(repository.playableEpisode("a")).episode
        assertEquals(42_000L, stored.positionMs)
        assertTrue(!stored.isPlayed)
    }

    @Test
    fun `a measured duration fills in what the feed never published`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("d", durationMs = null)))

        repository.recordPosition(episodeId = "d", positionMs = 1_000L, durationMs = 3_600_000L)

        assertEquals(3_600_000L, checkNotNull(repository.playableEpisode("d")).episode.durationMs)
    }

    @Test
    fun `completing an episode marks it played, resets it and drops it from the queue`() = runTest {
        repository.enqueue("a")
        repository.enqueue("b")
        repository.recordPosition(episodeId = "a", positionMs = 59_000L, durationMs = null)

        repository.recordCompleted("a")

        val stored = checkNotNull(repository.playableEpisode("a")).episode
        assertTrue(stored.isPlayed)
        assertEquals(0L, stored.positionMs)
        assertEquals(listOf("b"), repository.observeQueue().first().map { it.episode.id })
    }

    @Test
    fun `recording the queue replaces it wholesale, which is how a reorder is persisted`() =
        runTest {
            repository.enqueue("a")
            repository.enqueue("b")

            repository.recordQueue(listOf("c", "a"))

            assertEquals(listOf("c", "a"), repository.observeQueue().first().map { it.episode.id })
        }

    @Test
    fun `recording a queue that holds an unstored episode keeps the stored ones in order`() =
        runTest {
            // What the player's live queue holds once a search preview has been played beside a
            // queue: an episode built from a feed, with no row for a queue entry to point at. This
            // write used to fail the queue's foreign key and take the app down with it.
            repository.recordQueue(listOf("b", "search-preview", "a"))

            assertEquals(listOf("b", "a"), repository.observeQueue().first().map { it.episode.id })
        }

    @Test
    fun `reordering the queue stores the order the user dropped it in`() = runTest {
        repository.enqueue("a")
        repository.enqueue("b")
        repository.enqueue("c")

        repository.reorderQueue(listOf("c", "a", "b"))

        assertEquals(
            listOf("c", "a", "b"),
            repository.observeQueue().first().map { it.episode.id },
        )
    }

    @Test
    fun `a reorder leaves positions an append can still extend`() = runTest {
        repository.enqueue("a")
        repository.enqueue("b")
        repository.enqueue("c")
        repository.reorderQueue(listOf("c", "b"))

        // Appending is where stale positions would show: a new entry takes max(position) + 1, so
        // a queue left holding the positions of a longer list would sort the newcomer into the
        // middle. It also pins the documented contract — an id left out of the reorder is dropped.
        repository.enqueue("a")

        assertEquals(
            listOf("c", "b", "a"),
            repository.observeQueue().first().map { it.episode.id },
        )
    }

    @Test
    fun `the last played episode is observed, which is what the queue screen falls back to`() =
        runTest {
            preferences.setLastPlayedEpisodeId("b")

            assertEquals("b", repository.observeLastPlayedEpisodeId().first())
        }

    @Test
    fun `the resume point is the stored queue at its head when the last played is not in it`() =
        runTest {
            preferences.setLastPlayedEpisodeId("c")
            repository.enqueue("a")
            repository.enqueue("b")
            repository.recordPosition(episodeId = "a", positionMs = 9_000L, durationMs = null)

            val point = repository.resumePoint()

            assertEquals(listOf("a", "b"), point.queue.map { it.episode.id })
            assertEquals(0, point.index)
            assertEquals(9_000L, point.positionMs)
        }

    @Test
    fun `the resume point is the episode that was playing, with the ones before it kept`() =
        runTest {
            // Played "a", then pressed play on "b": the queue still holds "a" ahead of it, and the
            // head used to be what came back after a restart.
            repository.enqueue("a")
            repository.enqueue("b")
            repository.enqueue("c")
            repository.recordPosition(episodeId = "a", positionMs = 9_000L, durationMs = null)
            repository.recordPosition(episodeId = "b", positionMs = 42_000L, durationMs = null)
            preferences.setLastPlayedEpisodeId("b")

            val point = repository.resumePoint()

            assertEquals(listOf("a", "b", "c"), point.queue.map { it.episode.id })
            assertEquals(1, point.index)
            assertEquals("b", point.episode?.episode?.id)
            assertEquals(42_000L, point.positionMs)
        }

    @Test
    fun `a queue with no last played episode resumes at its head`() = runTest {
        repository.enqueue("b")
        repository.enqueue("a")

        val point = repository.resumePoint()

        assertEquals(0, point.index)
        assertEquals("b", point.episode?.episode?.id)
    }

    @Test
    fun `an empty queue resumes the last played episode instead`() = runTest {
        repository.recordPosition(episodeId = "c", positionMs = 5_000L, durationMs = null)
        preferences.setLastPlayedEpisodeId("c")

        val point = repository.resumePoint()

        assertEquals(listOf("c"), point.queue.map { it.episode.id })
        assertEquals(0, point.index)
        assertEquals(5_000L, point.positionMs)
    }

    @Test
    fun `nothing queued and nothing played means nothing to resume`() = runTest {
        assertEquals(ResumePoint.EMPTY, repository.resumePoint())
    }

    @Test
    fun `a last played episode that has since been removed is not resumed`() = runTest {
        preferences.setLastPlayedEpisodeId("deleted-episode")

        assertEquals(ResumePoint.EMPTY, repository.resumePoint())
    }

    @Test
    fun `an unknown episode has nothing playable`() = runTest {
        assertNull(repository.playableEpisode("nope"))
    }

    @Test
    fun `speed is persisted through the settings flow`() = runTest {
        repository.setSpeed(1.5f)

        assertEquals(1.5f, repository.observePlaybackSettings().first().speed, 0.001f)
    }

    @Test
    fun `the video quality defaults and then round trips`() = runTest {
        assertEquals(VideoQuality.DEFAULT, repository.observeVideoQuality().first())

        repository.setVideoQuality(VideoQuality(1080))

        assertEquals(VideoQuality(1080), repository.observeVideoQuality().first())
    }

    @Test
    fun `the player mode defaults to audio and then round trips`() = runTest {
        assertEquals(PlayerMode.AUDIO, repository.observePlayerMode().first())

        repository.setPlayerMode(PlayerMode.VIDEO)

        assertEquals(PlayerMode.VIDEO, repository.observePlayerMode().first())
    }
}
