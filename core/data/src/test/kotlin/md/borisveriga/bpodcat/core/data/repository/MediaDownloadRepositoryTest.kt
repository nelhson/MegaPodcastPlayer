package md.borisveriga.bpodcat.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
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
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloadStatus
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloader
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [MediaDownloadRepository] against a real in-memory database.
 *
 * The database is real for the same reason [DefaultPlaybackRepositoryTest] uses one: the behaviour
 * under test is expressed in SQL and in the ordering the DAO guarantees, so a fake DAO would only
 * test the fake. Media3 itself is mocked — starting a real [EpisodeDownloader] would mean a real
 * download service, and what matters here is *which* commands it is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaDownloadRepositoryTest {

    private lateinit var database: BPodcatDatabase
    private lateinit var preferences: UserPreferencesDataSource
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

    /** An episode published [publishedAt] ms into the epoch; higher is newer. */
    private fun episode(id: String, publishedAt: Long) = EpisodeEntity(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = publishedAt,
        sizeBytes = null,
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BPodcatDatabase::class.java,
        ).allowMainThreadQueries().build()

        preferences = UserPreferencesDataSource(InMemoryDataStore())
        downloader = mockk(relaxed = true)
        repository = MediaDownloadRepository(
            episodeDao = database.episodeDao(),
            queueDao = database.queueDao(),
            userPreferences = preferences,
            downloader = downloader,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        database.podcastDao().upsert(podcast)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `downloading an episode marks it queued straight away and asks media3 for it`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))

        assertTrue(repository.download("a"))

        // Optimistic: the row says "queued" before Media3 has reported anything, so the button
        // changes on the tap rather than a beat later.
        assertEquals(DownloadState.QUEUED, database.episodeDao().getById("a")?.downloadState)
        coVerify { downloader.download("a", "https://cdn.example.com/a.mp3", true) }
    }

    @Test
    fun `downloading an episode that is not stored reports failure and asks for nothing`() =
        runTest {
            assertFalse(repository.download("missing"))

            coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        }

    @Test
    fun `a media3 event is mirrored into the episode row`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))

        repository.recordDownloadStatus(
            EpisodeDownloadStatus(
                episodeId = "a",
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 2_500_000L,
                percent = 25f,
            ),
        )

        val stored = checkNotNull(database.episodeDao().getById("a"))
        assertEquals(DownloadState.DOWNLOADING, stored.downloadState)
        assertEquals(2_500_000L, stored.downloadedBytes)
        assertEquals(25f, stored.downloadPercent, 0.001f)
    }

    @Test
    fun `removing all downloads clears every row in one go`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L), episode("b", 2_000L)))
        markDownloaded("a", "b")

        repository.removeAllDownloads()

        coVerify { downloader.removeAll(any()) }
        assertTrue(database.episodeDao().observeDownloaded().first().isEmpty())
    }

    @Test
    fun `the keep limit removes the oldest downloads only`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(episode("new", 3_000L), episode("mid", 2_000L), episode("old", 1_000L)),
        )
        markDownloaded("new", "mid", "old")
        preferences.setKeepLimitPerPodcast(2)

        repository.enforceKeepLimit(podcast.id)

        coVerify { downloader.remove("old", any()) }
        coVerify(exactly = 0) { downloader.remove("new", any()) }
        coVerify(exactly = 0) { downloader.remove("mid", any()) }
        assertEquals(
            listOf("new", "mid"),
            database.episodeDao().observeDownloaded().first().map { it.id },
        )
    }

    @Test
    fun `the keep limit never removes a queued episode`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(episode("new", 3_000L), episode("mid", 2_000L), episode("old", 1_000L)),
        )
        markDownloaded("new", "mid", "old")
        preferences.setKeepLimitPerPodcast(1)
        // The user is about to listen to the oldest one; deleting it out from under them would be
        // worse than holding one episode more than they asked for.
        database.queueDao().enqueue("old")

        repository.enforceKeepLimit(podcast.id)

        coVerify(exactly = 0) { downloader.remove("old", any()) }
        coVerify { downloader.remove("mid", any()) }
    }

    @Test
    fun `keep-all sweeps nothing`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L), episode("b", 2_000L)))
        markDownloaded("a", "b")
        preferences.setKeepLimitPerPodcast(DownloadSettings.KEEP_ALL)

        repository.enforceKeepLimit(podcast.id)

        coVerify(exactly = 0) { downloader.remove(any(), any()) }
    }

    @Test
    fun `discovered episodes are ignored while auto-download is off`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))

        repository.onEpisodesDiscovered(podcast.id, listOf("a"))

        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        assertEquals(
            DownloadState.NOT_DOWNLOADED,
            database.episodeDao().getById("a")?.downloadState,
        )
    }

    @Test
    fun `discovered episodes are downloaded in the background when auto-download is on`() =
        runTest {
            database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
            preferences.setAutoDownloadNewEpisodes(true)

            repository.onEpisodesDiscovered(podcast.id, listOf("a"))

            // foreground = false: a refresh can run from a worker, where starting a foreground
            // service is forbidden.
            coVerify { downloader.download("a", "https://cdn.example.com/a.mp3", false) }
        }

    @Test
    fun `auto-download fetches no more than the keep limit`() = runTest {
        val ids = listOf("e1", "e2", "e3", "e4", "e5")
        database.episodeDao().upsertFromFeed(
            ids.mapIndexed { index, id -> episode(id, (5 - index).toLong() * 1_000L) },
        )
        preferences.setAutoDownloadNewEpisodes(true)
        preferences.setKeepLimitPerPodcast(2)

        repository.onEpisodesDiscovered(podcast.id, ids)

        // A back-catalogue of five must not become five downloads when the user asked to keep two.
        coVerify(exactly = 1) { downloader.download("e1", any(), false) }
        coVerify(exactly = 1) { downloader.download("e2", any(), false) }
        coVerify(exactly = 0) { downloader.download("e3", any(), any()) }
    }

    @Test
    fun `turning on wi-fi only stores the preference and tells media3`() = runTest {
        repository.setUnmeteredOnly(false)

        assertFalse(repository.observeDownloadSettings().first().unmeteredOnly)
        coVerify { downloader.setUnmeteredOnly(false) }
    }

    /** Marks [ids] as fully downloaded, as a completed Media3 event would. */
    private suspend fun markDownloaded(vararg ids: String) {
        ids.forEach { id ->
            database.episodeDao().updateDownloadState(
                id = id,
                state = DownloadState.COMPLETED,
                downloadedBytes = 10_000_000L,
                percent = 100f,
            )
        }
    }

    /**
     * An in-memory preferences store.
     *
     * A file-backed DataStore cannot survive a second write in a JVM unit test on Windows — it
     * renames a `.tmp` sibling over the target, which fails when the target exists.
     */
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
