package md.borisveriga.bpodcat.feature.podcast

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [PodcastDetailViewModel]'s download button.
 *
 * The button carries one action whose meaning follows the episode's state, so the branch it picks
 * is the whole behaviour — and getting it wrong means a tap that deletes an episode the user meant
 * to fetch.
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
}
