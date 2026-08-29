package md.borisveriga.bpodcat.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.database.BPodcatDatabase
import md.borisveriga.bpodcat.core.database.model.EpisodeEntity
import md.borisveriga.bpodcat.core.database.model.PodcastEntity
import md.borisveriga.bpodcat.core.datastore.UserPreferencesDataSource
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloader
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

    private lateinit var database: BPodcatDatabase
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
            BPodcatDatabase::class.java,
        ).allowMainThreadQueries().build()

        preferences = UserPreferencesDataSource(InMemoryPreferences())
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
    fun `the resumable queue is the stored queue when there is one`() = runTest {
        preferences.setLastPlayedEpisodeId("c")
        repository.enqueue("a")
        repository.enqueue("b")

        assertEquals(listOf("a", "b"), repository.resumableQueue().map { it.episode.id })
    }

    @Test
    fun `an empty queue resumes the last played episode instead`() = runTest {
        preferences.setLastPlayedEpisodeId("c")

        assertEquals(listOf("c"), repository.resumableQueue().map { it.episode.id })
    }

    @Test
    fun `nothing queued and nothing played means nothing to resume`() = runTest {
        assertEquals(emptyList<String>(), repository.resumableQueue().map { it.episode.id })
    }

    @Test
    fun `a last played episode that has since been removed is not resumed`() = runTest {
        preferences.setLastPlayedEpisodeId("deleted-episode")

        assertEquals(emptyList<String>(), repository.resumableQueue().map { it.episode.id })
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

    /**
     * An in-memory preferences store.
     *
     * DataStore's file backend renames a temp file over the target on every write, which Windows
     * refuses once the target exists, so a file-backed store cannot survive a second write in a JVM
     * unit test here.
     */
    private class InMemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val writeLock = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = writeLock.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
