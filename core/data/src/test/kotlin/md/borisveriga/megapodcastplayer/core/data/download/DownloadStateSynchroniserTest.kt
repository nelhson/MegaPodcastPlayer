package md.borisveriga.megapodcastplayer.core.data.download

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.MediaDownloadRepository
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloadStatus
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloader
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [DownloadStateSynchroniser]'s start-up reconciliation.
 *
 * This is the part worth pinning down: downloads carry on while the app is dead, so the episodes
 * table is routinely stale by the time the app comes back, and both directions of the correction
 * have a way of going wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadStateSynchroniserTest {

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var downloader: EpisodeDownloader
    private lateinit var repository: MediaDownloadRepository

    private val podcast = PodcastEntity(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "",
        addedAt = 0L,
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
        description = "",
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

        downloader = mockk(relaxed = true)
        // No live events in these tests; only the start-up snapshot matters.
        every { downloader.statusUpdates } returns emptyFlow()

        repository = MediaDownloadRepository(
            episodeDao = database.episodeDao(),
            queueDao = database.queueDao(),
            userPreferences = UserPreferencesDataSource(InMemoryDataStore()),
            downloader = downloader,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        database.podcastDao().upsert(podcast)
        database.episodeDao().upsertFromFeed(listOf(episode("a"), episode("b")))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a download that finished while the app was dead is written through`() = runTest {
        // The row still says "downloading" because the completion event fired to nobody.
        database.episodeDao().updateDownloadState("a", DownloadState.DOWNLOADING, 1_000L, 10f)
        coEvery { downloader.currentStatuses() } returns listOf(
            EpisodeDownloadStatus("a", DownloadState.COMPLETED, 10_000_000L, 100f),
        )

        synchroniser().start()?.join()

        val stored = checkNotNull(database.episodeDao().getById("a"))
        assertEquals(DownloadState.COMPLETED, stored.downloadState)
        assertEquals(10_000_000L, stored.downloadedBytes)
    }

    @Test
    fun `a row claiming a download media3 has no record of is cleared`() = runTest {
        // What an episode whose audio the system reclaimed looks like. Left alone, the app would
        // offer offline playback that silently falls back to the network.
        database.episodeDao().updateDownloadState("b", DownloadState.COMPLETED, 9_000_000L, 100f)
        coEvery { downloader.currentStatuses() } returns emptyList()

        synchroniser().start()?.join()

        val stored = checkNotNull(database.episodeDao().getById("b"))
        assertEquals(DownloadState.NOT_DOWNLOADED, stored.downloadState)
        assertEquals(0L, stored.downloadedBytes)
    }

    @Test
    fun `an episode that was never downloaded is left alone`() = runTest {
        coEvery { downloader.currentStatuses() } returns emptyList()

        synchroniser().start()?.join()

        assertEquals(
            DownloadState.NOT_DOWNLOADED,
            database.episodeDao().getById("a")?.downloadState,
        )
    }

    @Test
    fun `starting twice reconciles once`() = runTest {
        coEvery { downloader.currentStatuses() } returns emptyList()
        val synchroniser = synchroniser()

        synchroniser.start()?.join()
        synchroniser.start()?.join()

        io.mockk.coVerify(exactly = 1) { downloader.currentStatuses() }
    }

    /**
     * A synchroniser wired to the real repository and database.
     *
     * The scope is a [TestScope] so the job `start()` returns can be joined: reconciliation
     * suspends on Room's own executor, so without the join the assertions would race it.
     */
    private fun synchroniser() = DownloadStateSynchroniser(
        downloader = downloader,
        recorder = repository,
        repository = repository,
        episodeDao = database.episodeDao(),
        scope = TestScope(UnconfinedTestDispatcher()),
    )

    /** See `MediaDownloadRepositoryTest.InMemoryDataStore` for why a file-backed store is unusable. */
    private class InMemoryDataStore : DataStore<Preferences> {
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
