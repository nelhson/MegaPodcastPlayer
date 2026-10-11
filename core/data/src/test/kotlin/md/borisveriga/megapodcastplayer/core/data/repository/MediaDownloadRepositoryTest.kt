package md.borisveriga.megapodcastplayer.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.database.MegaPodcastPlayerDatabase
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloadStatus
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloader
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private lateinit var database: MegaPodcastPlayerDatabase
    private lateinit var preferences: UserPreferencesDataSource
    private lateinit var downloader: EpisodeDownloader
    private lateinit var folders: DefaultDownloadFolderRepository
    private lateinit var repository: MediaDownloadRepository

    /** Stands in for the application scope the repository restores download requirements on. */
    private val requirementScope = CoroutineScope(UnconfinedTestDispatcher())

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
            MegaPodcastPlayerDatabase::class.java,
        ).allowMainThreadQueries().build()

        preferences = UserPreferencesDataSource(InMemoryDataStore())
        downloader = mockk(relaxed = true)
        // The real folders over the same preferences: where a download is filed is part of what is
        // under test, and a mock would only say that a call was made.
        folders = DefaultDownloadFolderRepository(preferences)
        repository = MediaDownloadRepository(
            episodeDao = database.episodeDao(),
            userPreferences = preferences,
            downloader = downloader,
            folders = folders,
            clock = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC),
            ioDispatcher = UnconfinedTestDispatcher(),
            // The scope the "download now" rule-restore waits on. A test scope of its own rather
            // than `backgroundScope`: the wait is meant to outlive the call that started it, and
            // the tests below drive it deliberately.
            scope = requirementScope,
        )

        database.podcastDao().upsert(podcast)
    }

    @After
    fun tearDown() {
        requirementScope.cancel()
        database.close()
    }

    /**
     * Media3 enforces one network requirement for the whole download manager, so "download this
     * one now" is really "stop waiting for Wi-Fi". These three pin the consequences: the rule is
     * lifted before the request (Media3 evaluates it as the download is added), it comes back on
     * its own once nothing is left downloading, and a request for an episode that is not there
     * does not leave it lifted.
     */
    @Test
    fun `downloading now lifts the wi-fi rule before asking for the episode`() = runTest {
        preferences.setUnmeteredOnly(true)
        database.episodeDao().insertIgnoringExisting(listOf(episode("e1", publishedAt = 1L)))

        assertTrue(repository.downloadNow("e1"))

        coVerifyOrder {
            downloader.setUnmeteredOnly(false)
            downloader.download(episodeId = "e1", audioUrl = any())
        }
    }

    @Test
    fun `the wi-fi rule comes back once nothing is downloading`() = runTest {
        preferences.setUnmeteredOnly(true)
        database.episodeDao().insertIgnoringExisting(listOf(episode("e1", publishedAt = 1L)))
        repository.downloadNow("e1")

        // The download finishes, the way Media3's event would report it.
        repository.recordDownloadStatus(
            EpisodeDownloadStatus(
                episodeId = "e1",
                state = DownloadState.COMPLETED,
                downloadedBytes = 1_000L,
                percent = 100f,
            ),
        )

        coVerify { downloader.setUnmeteredOnly(true) }
    }

    @Test
    fun `a download now for an episode that is gone puts the rule straight back`() = runTest {
        preferences.setUnmeteredOnly(true)

        assertFalse(repository.downloadNow("missing"))

        // Nothing was started, so nothing will ever finish and restore it.
        coVerify { downloader.setUnmeteredOnly(true) }
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

    /**
     * A refresh used to end by sweeping a show's oldest downloads back down to the limit, which
     * deleted what the user had saved by hand because a new episode arrived. The limit now bounds
     * only what auto-download fetches; nothing on the device is touched.
     */
    @Test
    fun `a refresh never removes a download already on the device`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(
                episode("newest", 4_000L),
                episode("new", 3_000L),
                episode("mid", 2_000L),
                episode("old", 1_000L),
            ),
        )
        markDownloaded("new", "mid", "old")
        preferences.setAutoDownloadNewEpisodes(true)
        preferences.setKeepLimitPerPodcast(1)

        repository.onEpisodesDiscovered(podcast.id, listOf("newest"))

        coVerify { downloader.download("newest", any(), false) }
        coVerify(exactly = 0) { downloader.remove(any(), any()) }
        assertEquals(
            listOf("new", "mid", "old"),
            database.episodeDao().observeDownloaded().first().map { it.id },
        )
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
    fun `a show that always downloads is fetched with the app setting off`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        preferences.updateShowSettings(podcast.id) { it.copy(autoDownload = true) }

        repository.onEpisodesDiscovered(podcast.id, listOf("a"))

        coVerify { downloader.download("a", "https://cdn.example.com/a.mp3", false) }
    }

    @Test
    fun `a show that never downloads is skipped with the app setting on`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        preferences.setAutoDownloadNewEpisodes(true)
        preferences.updateShowSettings(podcast.id) { it.copy(autoDownload = false) }

        repository.onEpisodesDiscovered(podcast.id, listOf("a"))

        // The show has the last word in both directions; a per-show "never" that only worked
        // while the app setting was already off would be a control that does nothing.
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
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

    @Test
    fun `with nothing dragged, the downloads list keeps the ordering the query gives it`() =
        runTest {
            database.episodeDao().upsertFromFeed(
                listOf(episode("old", 1_000L), episode("new", 3_000L)),
            )
            markDownloaded("old", "new")

            assertEquals(
                listOf("new", "old"),
                repository.observeDownloads().first().map { it.episode.id },
            )
        }

    @Test
    fun `a stored arrangement is what the downloads list comes back in`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(episode("old", 1_000L), episode("new", 3_000L)),
        )
        markDownloaded("old", "new")

        repository.reorderDownloads(listOf("old", "new"))

        // Newest-first is only the starting point; once the user has said otherwise, they have
        // said otherwise.
        assertEquals(
            listOf("old", "new"),
            repository.observeDownloads().first().map { it.episode.id },
        )
    }

    @Test
    fun `a download nobody has placed follows the ones they have`() = runTest {
        database.episodeDao().upsertFromFeed(
            listOf(episode("a", 1_000L), episode("b", 2_000L), episode("late", 3_000L)),
        )
        markDownloaded("a", "b", "late")

        // The arrangement was made before "late" was ever downloaded.
        repository.reorderDownloads(listOf("b", "a"))

        // It is the newest, so the query would have put it first; it goes last instead, because
        // the two rows in front of it are where they are on purpose.
        assertEquals(
            listOf("b", "a", "late"),
            repository.observeDownloads().first().map { it.episode.id },
        )
    }

    @Test
    fun `an arrangement naming episodes that have gone still orders the ones that remain`() =
        runTest {
            database.episodeDao().upsertFromFeed(
                listOf(episode("a", 1_000L), episode("b", 2_000L)),
            )
            markDownloaded("a", "b")

            // "gone" was removed from the device after it was dragged; its id is kept so that
            // fetching it again brings it back where it was put.
            repository.reorderDownloads(listOf("gone", "b", "a"))

            assertEquals(
                listOf("b", "a"),
                repository.observeDownloads().first().map { it.episode.id },
            )
        }

    @Test
    fun `the download list is empty when nothing has finished downloading`() = runTest {
        database.episodeDao().insertIgnoringExisting(listOf(episode("e1", publishedAt = 1L)))

        assertEquals("", repository.exportListMarkdown())
    }

    @Test
    fun `the download list names each finished episode under its show`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L), episode("b", 2_000L)))
        markDownloaded("a")

        val document = repository.exportListMarkdown()

        assertTrue(document, document.startsWith("# MegaPodcastPlayer downloads\n"))
        assertTrue(document, document.contains("## Podlodka Podcast\n\nFeed: <${podcast.feedUrl}>"))
        assertTrue(document, document.contains("- **Episode a** — "))
        assertFalse(document, document.contains("Episode b"))
        assertEquals(document, repository.exportListMarkdown(podcastId = podcast.id))
        assertEquals("", repository.exportListMarkdown(podcastId = "someone-else"))
    }

    @Test
    fun `a video download fetches the audio with the picture when the audio is not there`() =
        runTest {
            database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y")))

            assertTrue(repository.downloadVideo("y", VideoQuality(1080)))

            // The picture plays merged with the audio, so offline it needs both.
            assertEquals(DownloadState.QUEUED, database.episodeDao().getById("y")?.downloadState)
            coVerify { downloader.download("y", youTubeAudioSentinel(VIDEO_ID), true) }
            coVerify { downloader.downloadVideo("y", VIDEO_ID, VideoQuality(1080), true) }
        }

    @Test
    fun `a video download leaves audio already on the device alone`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y")))
        markDownloaded("y")

        assertTrue(repository.downloadVideo("y", VideoQuality(720)))

        assertEquals(DownloadState.COMPLETED, database.episodeDao().getById("y")?.downloadState)
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        coVerify { downloader.downloadVideo("y", VIDEO_ID, VideoQuality(720), true) }
    }

    @Test
    fun `a feed episode has no video to download`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))

        assertFalse(repository.downloadVideo("a", VideoQuality(720)))
        assertFalse(repository.downloadVideo("missing", VideoQuality(720)))

        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        coVerify(exactly = 0) { downloader.downloadVideo(any(), any(), any(), any()) }
    }

    // --- The official YouTube source ----------------------------------------

    @Test
    fun `under the official source a youtube episode is not downloaded`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y")))
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        assertFalse(repository.download("y"))
        assertFalse(repository.downloadNow("y"))
        assertFalse(repository.downloadVideo("y", VideoQuality(720)))

        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        coVerify(exactly = 0) { downloader.downloadVideo(any(), any(), any(), any()) }
        assertEquals(DownloadState.NOT_DOWNLOADED, database.episodeDao().getById("y")?.downloadState)
    }

    @Test
    fun `under the official source an rss episode downloads as ever`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        assertTrue(repository.download("a"))

        coVerify { downloader.download("a", "https://cdn.example.com/a.mp3", true) }
    }

    @Test
    fun `under the official source auto-download skips youtube and keeps going`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y"), episode("a", 1_000L)))
        preferences.setAutoDownloadNewEpisodes(true)
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        repository.onEpisodesDiscovered(podcast.id, listOf("y", "a"))

        coVerify(exactly = 0) { downloader.download("y", any(), any()) }
        coVerify { downloader.download("a", any(), false) }
        // Skipped means untouched: not marked queued for a download that will never be asked for.
        assertEquals(DownloadState.NOT_DOWNLOADED, database.episodeDao().getById("y")?.downloadState)
    }

    @Test
    fun `under the official source youtube downloads leave every list and come back`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y"), episode("a", 1_000L)))
        markDownloaded("y", "a")

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        assertEquals(listOf("a"), repository.observeDownloadedEpisodes().first().map { it.id })
        assertEquals(listOf("a"), repository.observeDownloads().first().map { it.episode.id })
        // The row is as it was: hidden, not deleted.
        assertEquals(DownloadState.COMPLETED, database.episodeDao().getById("y")?.downloadState)

        preferences.setYouTubeSource(YouTubeSource.EXTRACTOR)

        assertEquals(setOf("a", "y"), repository.observeDownloadedEpisodes().first().mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun `under the official source there are no video downloads to show`() = runTest {
        val video = VideoDownload(quality = VideoQuality(720), state = DownloadState.COMPLETED, percent = 100f)
        every { downloader.videoDownloads } returns flowOf(mapOf("y" to video))
        assertEquals(1, repository.observeVideoDownloads().first().size)

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)

        assertTrue(repository.observeVideoDownloads().first().isEmpty())
    }

    @Test
    fun `deleting a video keeps the audio`() = runTest {
        repository.removeVideoDownload("y")

        coVerify { downloader.removeVideo("y", true) }
        coVerify(exactly = 0) { downloader.remove(any(), any()) }
    }

    @Test
    fun `a download nobody filed goes to the default folder`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        val commute = createFolder("Commute")
        folders.setDefaultFolder(commute)

        repository.download("a")

        assertEquals(commute, folderOf("a"))
    }

    @Test
    fun `a download filed on purpose goes where it was told`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        folders.setDefaultFolder(createFolder("Commute"))
        val lectures = createFolder("Lectures")

        repository.download("a", DownloadDestination.Folder(lectures))

        assertEquals(lectures, folderOf("a"))
    }

    @Test
    fun `an episode that is not stored is filed nowhere`() = runTest {
        folders.setDefaultFolder(createFolder("Commute"))

        assertFalse(repository.download("missing"))

        assertNull(folderOf("missing"))
    }

    @Test
    fun `a video joining audio on the device stays in the audio's folder`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y")))
        markDownloaded("y")
        val lectures = createFolder("Lectures")
        folders.moveToFolder(listOf("y"), lectures)
        folders.setDefaultFolder(createFolder("Commute"))

        repository.downloadVideo("y", VideoQuality(720))

        assertEquals(lectures, folderOf("y"))
    }

    @Test
    fun `a video filed on purpose moves its audio with it`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(youTubeEpisode("y")))
        markDownloaded("y")
        val lectures = createFolder("Lectures")

        repository.downloadVideo("y", VideoQuality(720), DownloadDestination.Folder(lectures))

        assertEquals(lectures, folderOf("y"))
    }

    @Test
    fun `auto-downloaded episodes go to the default folder`() = runTest {
        preferences.setAutoDownloadNewEpisodes(true)
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        val commute = createFolder("Commute")
        folders.setDefaultFolder(commute)

        repository.onEpisodesDiscovered(podcast.id, listOf("a"))

        assertEquals(commute, folderOf("a"))
    }

    @Test
    fun `deleting a download takes it out of its folder`() = runTest {
        val commute = createFolder("Commute")
        folders.moveToFolder(listOf("a", "b"), commute)

        repository.removeDownload("a")

        assertNull(folderOf("a"))
        assertEquals(commute, folderOf("b"))
    }

    @Test
    fun `a removal reported by media3 takes the download out of its folder`() = runTest {
        // How delete-after-playing ends: the player asks Media3, and only the event comes back here.
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        folders.moveToFolder(listOf("a"), createFolder("Commute"))

        repository.recordDownloadStatus(EpisodeDownloadStatus.notDownloaded("a"))

        assertNull(folderOf("a"))
    }

    @Test
    fun `progress on a download leaves it in its folder`() = runTest {
        database.episodeDao().upsertFromFeed(listOf(episode("a", 1_000L)))
        val commute = createFolder("Commute")
        folders.moveToFolder(listOf("a"), commute)

        repository.recordDownloadStatus(
            EpisodeDownloadStatus(
                episodeId = "a",
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 1L,
                percent = 1f,
            ),
        )

        assertEquals(commute, folderOf("a"))
    }

    @Test
    fun `removing all downloads empties every folder and keeps the folders`() = runTest {
        val commute = createFolder("Commute")
        folders.moveToFolder(listOf("a", "b"), commute)

        repository.removeAllDownloads()

        val stored = folders.observeFolders().first()
        assertTrue(stored.membership.isEmpty())
        assertEquals(listOf(commute), stored.folders.map { it.id })
    }

    /** Makes a folder and returns its id. */
    private suspend fun createFolder(name: String): String =
        (folders.createFolder(name) as FolderEdit.Done).folder.id

    /** The folder an episode is filed under now; null for Downloads. */
    private suspend fun folderOf(episodeId: String): String? =
        folders.observeFolders().first().folderOf(episodeId)

    private companion object {
        /** The YouTube video behind [youTubeEpisode]. */
        const val VIDEO_ID = "niTJ2221aS8"
    }

    /** A YouTube episode, whose stored audio URL is the sentinel for [VIDEO_ID]. */
    private fun youTubeEpisode(id: String) =
        episode(id, publishedAt = 1_000L).copy(audioUrl = youTubeAudioSentinel(VIDEO_ID))

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
