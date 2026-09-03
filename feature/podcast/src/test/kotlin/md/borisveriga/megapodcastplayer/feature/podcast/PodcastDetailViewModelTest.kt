package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [PodcastDetailViewModel]'s download button and its two refreshes.
 *
 * The download button carries one action whose meaning follows the episode's state, so the branch
 * it picks is the whole behaviour — and getting it wrong means a tap that deletes an episode the
 * user meant to fetch.
 *
 * The refreshes differ in what they ask for and what they say. Entering the screen checks the feed
 * only if it is stale and reports nothing either way; pulling the list down always checks and
 * always answers. Which feeds a staleness window actually skips is `OfflineFirstPodcastRepositoryTest`'s
 * business, not this one's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val episodes = MutableStateFlow(emptyList<Episode>())
    private val downloadSettings = MutableStateFlow(DownloadSettings())

    private lateinit var repository: PodcastRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var viewModel: PodcastDetailViewModel

    private val podcast = Podcast(
        id = "podcast-1",
        itunesId = null,
        title = "Podlodka Podcast",
        author = "Егор Толстой",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.EPOCH,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String, downloadState: DownloadState) = Episode(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        downloadState = downloadState,
    )

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)

        every { repository.observePodcast(any()) } returns flowOf(podcast)
        every { repository.observeEpisodes(any()) } returns episodes
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        coEvery { downloadRepository.download(any()) } returns true

        viewModel = PodcastDetailViewModel(
            repository = repository,
            episodePlayer = episodePlayer,
            downloadRepository = downloadRepository,
            savedStateHandle = SavedStateHandle(
                mapOf(PodcastDetailViewModel.PODCAST_ID_ARG to podcast.id),
            ),
        )
    }

    @Test
    fun `tapping download on an absent episode requests it`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        coVerify { downloadRepository.download("a") }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }

    @Test
    fun `tapping download on a failed episode retries rather than clearing it`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.FAILED))
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        coVerify { downloadRepository.download("a") }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }

    @Test
    fun `tapping download on a finished episode removes it`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.COMPLETED))
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        coVerify { downloadRepository.removeDownload("a") }
        coVerify(exactly = 0) { downloadRepository.download(any()) }
    }

    @Test
    fun `tapping download on one in progress cancels it`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.DOWNLOADING))
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        coVerify { downloadRepository.removeDownload("a") }
    }

    @Test
    fun `a download on wi-fi only says so, because nothing else will happen yet`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = true)
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        viewModel.uiState.test {
            assertEquals(
                PodcastDetailMessage.DownloadQueued(title = "Episode a", waitingForWifi = true),
                awaitItem().message,
            )
        }
    }

    @Test
    fun `a download on any network reports that it started`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        viewModel.uiState.test {
            assertEquals(
                PodcastDetailMessage.DownloadQueued(title = "Episode a", waitingForWifi = false),
                awaitItem().message,
            )
        }
    }

    @Test
    fun `tapping download for an episode that has gone does nothing`() = runTest {
        episodes.value = emptyList()
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("missing")

        coVerify(exactly = 0) { downloadRepository.download(any()) }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }

    @Test
    fun `an episode the repository no longer has reports it as unavailable`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        // The show was removed between the list rendering and the tap landing.
        coEvery { downloadRepository.download("a") } returns false
        viewModel.uiState.test { awaitItem() }

        viewModel.toggleDownload("a")

        viewModel.uiState.test {
            assertEquals(PodcastDetailMessage.EpisodeUnavailable, awaitItem().message)
        }
    }

    @Test
    fun `entering the screen refreshes the feed only when it is stale`() = runTest {
        coEvery { repository.refresh(any(), any()) } returns Result.success(0)
        viewModel.uiState.test { awaitItem() }

        viewModel.refreshIfStale()

        coVerify(exactly = 1) {
            repository.refresh(podcast.id, staleAfter = Duration.ofMinutes(15))
        }
    }

    @Test
    fun `an automatic refresh raises its own flag and reports nothing`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refresh(any(), any()) } coAnswers {
            release.await()
            Result.success(3)
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshIfStale()
            val running = awaitItem()
            assertTrue(running.isAutoRefreshing)
            assertFalse("The pull-to-refresh spinner must stay down", running.isRefreshing)

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isAutoRefreshing)
            // Three new episodes is worth showing, not worth announcing: they are already in the
            // list the user is looking at.
            assertEquals(null, done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an automatic refresh that fails stays silent`() = runTest {
        coEvery { repository.refresh(any(), any()) } returns
            Result.failure(java.io.IOException("no route to host"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshIfStale()
            assertTrue(awaitItem().isAutoRefreshing)

            val done = awaitItem()
            assertFalse(done.isAutoRefreshing)
            // The deliberate cost of not interrupting on entry: a dead feed surfaces when the user
            // pulls to refresh, not before.
            assertEquals(null, done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pulling to refresh checks the feed however recent it is, and reports what it found`() =
        runTest {
            coEvery { repository.refresh(podcast.id) } returns Result.success(2)

            viewModel.uiState.test {
                awaitItem()

                viewModel.refresh()
                assertTrue(awaitItem().isRefreshing)

                val done = awaitItem()
                assertFalse(done.isRefreshing)
                assertEquals(PodcastDetailMessage.Refreshed(2), done.message)
                cancelAndIgnoreRemainingEvents()
            }

            // No staleness window: the gesture means "check now".
            coVerify(exactly = 1) { repository.refresh(podcast.id, null) }
        }

    @Test
    fun `a failed pull-to-refresh explains itself`() = runTest {
        coEvery { repository.refresh(podcast.id) } returns
            Result.failure(java.io.IOException("no route to host"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            assertTrue(awaitItem().isRefreshing)

            val done = awaitItem()
            assertEquals(
                PodcastDetailMessage.RefreshFailed("no route to host"),
                done.message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an automatic refresh does not start on top of a pull-to-refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refresh(any(), any()) } coAnswers {
            release.await()
            Result.success(0)
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            assertTrue(awaitItem().isRefreshing)
            viewModel.refreshIfStale()

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isRefreshing)
            assertEquals(PodcastDetailMessage.Refreshed(0), done.message)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.refresh(any(), any()) }
    }

    @Test
    fun `rebuilding raises its own flag and reports the list it ended up with`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.rebuild(podcast.id) } coAnswers {
            release.await()
            Result.success(412)
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.rebuild()
            val running = awaitItem()
            assertTrue(running.isRebuilding)
            // Neither refresh's indicator: a rebuild is about to replace the rows on screen, and
            // borrowing the pull-to-refresh spinner would understate that.
            assertFalse(running.isRefreshing)
            assertFalse(running.isAutoRefreshing)

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isRebuilding)
            assertEquals(PodcastDetailMessage.Rebuilt(412), done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a rebuild clears the downloads whose rows it just deleted`() = runTest {
        episodes.value = listOf(
            episode("kept", DownloadState.NOT_DOWNLOADED),
            episode("stored", DownloadState.COMPLETED),
            episode("arriving", DownloadState.DOWNLOADING),
        )
        coEvery { repository.rebuild(podcast.id) } returns Result.success(3)

        viewModel.uiState.test {
            awaitItem()
            viewModel.rebuild()
            cancelAndIgnoreRemainingEvents()
        }

        // Anything the download stack was tracking, not only what finished: a transfer in flight
        // also has bytes on disk and a row that is about to stop existing.
        coVerify { downloadRepository.removeDownload("stored") }
        coVerify { downloadRepository.removeDownload("arriving") }
        coVerify(exactly = 0) { downloadRepository.removeDownload("kept") }
    }

    @Test
    fun `a failed rebuild keeps the downloads, because it kept the episodes`() = runTest {
        episodes.value = listOf(episode("stored", DownloadState.COMPLETED))
        coEvery { repository.rebuild(podcast.id) } returns
            Result.failure(java.io.IOException("no route to host"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.rebuild()
            assertTrue(awaitItem().isRebuilding)

            val done = awaitItem()
            assertFalse(done.isRebuilding)
            assertEquals(PodcastDetailMessage.RebuildFailed("no route to host"), done.message)
            cancelAndIgnoreRemainingEvents()
        }

        // The repository deletes nothing when the fetch fails, so freeing the audio here would
        // throw away downloads the surviving list still points at.
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }

    @Test
    fun `neither refresh starts on top of a rebuild`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.rebuild(podcast.id) } coAnswers {
            release.await()
            Result.success(1)
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.rebuild()
            assertTrue(awaitItem().isRebuilding)
            // Both of them, because either would merge its episodes into a list that is halfway
            // through being replaced.
            viewModel.refresh()
            viewModel.refreshIfStale()

            release.complete(Unit)
            val done = awaitItem()
            assertEquals(PodcastDetailMessage.Rebuilt(1), done.message)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { repository.refresh(any(), any()) }
    }

    @Test
    fun `an unfiltered reorder is written straight through`() = runTest {
        episodes.value = listOf(
            episode("a", DownloadState.NOT_DOWNLOADED),
            episode("b", DownloadState.NOT_DOWNLOADED),
            episode("c", DownloadState.NOT_DOWNLOADED),
        )
        viewModel.uiState.test {
            awaitItem()

            viewModel.moveEpisode(visibleIds = listOf("a", "b", "c"), from = 2, to = 0)
            runCurrent()

            coVerify { repository.reorderEpisodes(podcast.id, listOf("c", "a", "b")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder under a filter leaves the hidden episodes where they were`() = runTest {
        episodes.value = listOf(
            episode("a", DownloadState.COMPLETED),
            episode("hidden", DownloadState.NOT_DOWNLOADED),
            episode("b", DownloadState.COMPLETED),
        )
        viewModel.uiState.test {
            awaitItem()

            // The screen is showing only the downloaded pair; dragging "b" above "a" must swap
            // exactly those two and leave the episode between them untouched. Treating the two
            // positions as positions in the full list would have moved the wrong rows entirely.
            viewModel.moveEpisode(visibleIds = listOf("a", "b"), from = 1, to = 0)
            runCurrent()

            coVerify { repository.reorderEpisodes(podcast.id, listOf("b", "hidden", "a")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder that lands where it started writes nothing`() = runTest {
        episodes.value = listOf(
            episode("a", DownloadState.NOT_DOWNLOADED),
            episode("b", DownloadState.NOT_DOWNLOADED),
        )
        viewModel.uiState.test {
            awaitItem()

            viewModel.moveEpisode(visibleIds = listOf("a", "b"), from = 0, to = 0)
            runCurrent()

            coVerify(exactly = 0) { repository.reorderEpisodes(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
