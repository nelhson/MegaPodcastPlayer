package md.borisveriga.bpodcat.feature.downloads

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.DownloadedEpisode
import md.borisveriga.bpodcat.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [DownloadsViewModel].
 *
 * The screen's whole job is to be an honest picture of what is on the device and to let the user
 * free that space, so the cases worth pinning are the storage total it reports and the two removal
 * paths — including the one that must not fire on an empty list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = MutableStateFlow(emptyList<DownloadedEpisode>())

    private lateinit var downloadRepository: DownloadRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: DownloadsViewModel

    private fun download(
        id: String,
        showTitle: String = "Podlodka Podcast",
        downloadedBytes: Long = 1_000L,
    ) = DownloadedEpisode(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = downloadedBytes,
            downloadState = DownloadState.COMPLETED,
            downloadedBytes = downloadedBytes,
            downloadPercent = 100f,
        ),
        showTitle = showTitle,
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        downloadRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        every { downloadRepository.observeDownloads() } returns downloads
        viewModel = DownloadsViewModel(downloadRepository, episodePlayer)
    }

    @Test
    fun `the storage total is the sum of what each download occupies`() = runTest {
        downloads.value = listOf(
            download("a", downloadedBytes = 5_000_000L),
            download("b", downloadedBytes = 3_000_000L),
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.downloads.size)
            assertEquals(8_000_000L, state.totalBytes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing a download deletes it and names it in the message`() = runTest {
        downloads.value = listOf(download("a"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.remove("a")

            coVerify { downloadRepository.removeDownload("a") }
            assertEquals(DownloadsMessage.Removed("Episode a"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing all reports the count that was there before the sweep`() = runTest {
        downloads.value = listOf(download("a"), download("b"), download("c"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.removeAll()

            coVerify { downloadRepository.removeAllDownloads() }
            assertEquals(DownloadsMessage.RemovedAll(3), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing all does nothing when there is nothing downloaded`() = runTest {
        downloads.value = emptyList()
        viewModel.uiState.test {
            awaitItem()

            viewModel.removeAll()

            coVerify(exactly = 0) { downloadRepository.removeAllDownloads() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing a downloaded episode opens the player`() = runTest {
        downloads.value = listOf(download("a"))
        coEvery { episodePlayer.play("a") } returns true
        var opened = false

        viewModel.uiState.test {
            awaitItem()

            viewModel.play("a") { opened = true }

            assertTrue(opened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an episode that has gone is reported rather than opening the player`() = runTest {
        downloads.value = listOf(download("a"))
        coEvery { episodePlayer.play("a") } returns false
        var opened = false

        viewModel.uiState.test {
            awaitItem()

            viewModel.play("a") { opened = true }

            assertFalse(opened)
            assertEquals(DownloadsMessage.EpisodeUnavailable, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queueing an episode confirms with its title`() = runTest {
        downloads.value = listOf(download("a"))
        coEvery { episodePlayer.addToQueue("a") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("a")

            assertEquals(DownloadsMessage.Queued("Episode a"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a shown message is cleared so it cannot be announced twice`() = runTest {
        downloads.value = listOf(download("a"))
        viewModel.uiState.test {
            awaitItem()
            viewModel.remove("a")
            assertEquals(DownloadsMessage.Removed("Episode a"), awaitItem().message)

            viewModel.onMessageShown()

            assertNull(awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
