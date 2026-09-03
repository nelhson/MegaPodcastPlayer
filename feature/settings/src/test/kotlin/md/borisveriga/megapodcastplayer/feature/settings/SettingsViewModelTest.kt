package md.borisveriga.megapodcastplayer.feature.settings

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [SettingsViewModel].
 *
 * The repositories are mocked because none of the behaviour under test is storage behaviour — that
 * is covered where it lives, in `MediaDownloadRepositoryTest` and `UserPreferencesDataSourceTest`.
 * What matters here is that the screen reads one consistent picture and that the two settings the
 * repository takes in pairs are not clobbered when only one of them changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playbackSettings = MutableStateFlow(PlaybackSettings())
    private val downloadSettings = MutableStateFlow(DownloadSettings())
    private val downloadedEpisodes = MutableStateFlow(emptyList<Episode>())

    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var viewModel: SettingsViewModel

    private fun downloadedEpisode(id: String, bytes: Long) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        downloadState = DownloadState.COMPLETED,
        downloadedBytes = bytes,
        downloadPercent = 100f,
    )

    @Before
    fun setUp() {
        playbackRepository = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)
        every { playbackRepository.observePlaybackSettings() } returns playbackSettings
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        every { downloadRepository.observeDownloadedEpisodes() } returns downloadedEpisodes
        coEvery { downloadRepository.downloadedBytes() } returns 0L
        viewModel = SettingsViewModel(playbackRepository, downloadRepository)
    }

    @Test
    fun `the screen shows both settings groups and the storage summary`() = runTest {
        playbackSettings.value = PlaybackSettings(speed = 1.5f)
        downloadSettings.value = DownloadSettings(autoDownloadNewEpisodes = true)
        downloadedEpisodes.value = listOf(
            downloadedEpisode("a", 5_000_000L),
            downloadedEpisode("b", 7_000_000L),
        )
        coEvery { downloadRepository.downloadedBytes() } returns 12_500_000L

        viewModel.refreshStorageUsage()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1.5f, state.playback.speed, 0.001f)
            assertTrue(state.downloads.autoDownloadNewEpisodes)
            assertEquals(2, state.downloadedEpisodeCount)
            // The cache's own figure, not the sum of the rows: partial downloads and Media3's index
            // occupy storage that no episode row accounts for.
            assertEquals(12_500_000L, state.downloadedBytes)
            assertTrue(state.hasDownloads)
        }
    }

    @Test
    fun `an empty library reports no downloads`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(0, state.downloadedEpisodeCount)
            assertFalse(state.hasDownloads)
        }
    }

    @Test
    fun `changing the skip-ahead interval leaves the skip-back one alone`() = runTest {
        playbackSettings.value = PlaybackSettings(skipForwardMs = 30_000L, skipBackMs = 15_000L)
        // The state flow only produces values while collected, and the view model reads it to fill
        // in the interval it is not changing.
        viewModel.uiState.test { awaitItem() }

        viewModel.setSkipForward(45_000L)

        // The repository takes both at once, so a careless implementation would reset the other to
        // its default here.
        coVerify { playbackRepository.setSkipIntervals(forwardMs = 45_000L, backMs = 15_000L) }
    }

    @Test
    fun `changing the skip-back interval leaves the skip-ahead one alone`() = runTest {
        playbackSettings.value = PlaybackSettings(skipForwardMs = 30_000L, skipBackMs = 15_000L)
        viewModel.uiState.test { awaitItem() }

        viewModel.setSkipBack(5_000L)

        coVerify { playbackRepository.setSkipIntervals(forwardMs = 30_000L, backMs = 5_000L) }
    }

    @Test
    fun `each toggle reaches its repository`() = runTest {
        viewModel.setSpeed(2f)
        viewModel.setAutoPlayNext(false)
        viewModel.setAutoDownload(true)
        viewModel.setUnmeteredOnly(false)
        viewModel.setKeepLimit(5)
        viewModel.setDeleteAfterPlaying(false)

        coVerify { playbackRepository.setSpeed(2f) }
        coVerify { playbackRepository.setAutoPlayNext(false) }
        coVerify { downloadRepository.setAutoDownloadNewEpisodes(true) }
        coVerify { downloadRepository.setUnmeteredOnly(false) }
        coVerify { downloadRepository.setKeepLimitPerPodcast(5) }
        coVerify { downloadRepository.setDeleteAfterPlaying(false) }
    }

    @Test
    fun `removing all downloads reports how much was freed`() = runTest {
        downloadedEpisodes.value = listOf(downloadedEpisode("a", 5_000_000L))
        coEvery { downloadRepository.downloadedBytes() } returns 8_000_000L
        viewModel.refreshStorageUsage()
        viewModel.uiState.test { awaitItem() }

        coEvery { downloadRepository.downloadedBytes() } returns 0L
        viewModel.removeAllDownloads()

        coVerify { downloadRepository.removeAllDownloads() }
        viewModel.uiState.test {
            val state = awaitItem()
            // The figure is captured before the removal — afterwards there is nothing left to
            // measure, and "freed 0 MB" would be a useless confirmation.
            assertEquals(
                SettingsMessage.DownloadsRemoved(8_000_000L),
                state.message,
            )
            assertFalse(state.isRemovingDownloads)
            assertEquals(0L, state.downloadedBytes)
        }
    }

    @Test
    fun `a message is cleared once its snackbar has been shown`() = runTest {
        viewModel.removeAllDownloads()
        viewModel.uiState.test { awaitItem() }

        viewModel.onMessageShown()

        viewModel.uiState.test {
            assertEquals(null, awaitItem().message)
        }
    }

}
