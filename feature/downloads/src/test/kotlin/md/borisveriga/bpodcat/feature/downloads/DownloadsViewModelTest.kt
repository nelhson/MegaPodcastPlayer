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
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow
import md.borisveriga.bpodcat.core.testing.MainDispatcherRule
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
 * The screen has to be an honest picture of what the download stack is doing and let the user act
 * on it, so the cases worth pinning are the storage total it reports — which counts only finished
 * episodes even though the list shows more than those — the retry path, and the two removal paths,
 * including the one that must not fire on an empty list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = MutableStateFlow(emptyList<EpisodeWithShow>())
    private val downloadSettings = MutableStateFlow(DownloadSettings())

    private lateinit var downloadRepository: DownloadRepository
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: DownloadsViewModel

    private fun download(
        id: String,
        showTitle: String = "Podlodka Podcast",
        downloadedBytes: Long = 1_000L,
        downloadState: DownloadState = DownloadState.COMPLETED,
        downloadPercent: Float = 100f,
    ) = EpisodeWithShow(
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
            downloadState = downloadState,
            downloadedBytes = downloadedBytes,
            downloadPercent = downloadPercent,
        ),
        showTitle = showTitle,
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        downloadRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        every { downloadRepository.observeDownloads() } returns downloads
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        coEvery { downloadRepository.freeBytes() } returns FREE_BYTES
        viewModel = DownloadsViewModel(downloadRepository, playbackRepository, episodePlayer)
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
            assertEquals(2, state.completedCount)
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

    @Test
    fun `the storage figure counts only what has finished downloading`() = runTest {
        downloads.value = listOf(
            download("broken", downloadedBytes = 0L, downloadState = DownloadState.FAILED),
            download(
                "busy",
                downloadedBytes = 2_000_000L,
                downloadState = DownloadState.DOWNLOADING,
                downloadPercent = 40f,
            ),
            download("waiting", downloadedBytes = 0L, downloadState = DownloadState.QUEUED),
            download("done", downloadedBytes = 5_000_000L),
        )

        viewModel.uiState.test {
            val state = awaitItem()

            // Everything is listed...
            assertEquals(4, state.downloads.size)
            // ...but the storage line answers "what is on this device", and a transfer that is 40%
            // through is not an episode anyone can free by deleting it.
            assertEquals(1, state.completedCount)
            assertEquals(5_000_000L, state.totalBytes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the wifi-only setting reaches the state, so a waiting row can say why`() = runTest {
        downloads.value = listOf(download("waiting", downloadState = DownloadState.QUEUED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = true)

        viewModel.uiState.test {
            assertTrue(awaitItem().unmeteredOnly)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retrying a failed download asks for it again and confirms`() = runTest {
        downloads.value = listOf(download("broken", downloadState = DownloadState.FAILED))
        // Wi-Fi only is on by default, so an unqualified retry has to switch it off to be one.
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        coEvery { downloadRepository.download("broken") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.retry("broken")

            coVerify { downloadRepository.download("broken") }
            assertEquals(
                DownloadsMessage.RetryQueued(title = "Episode broken", waitingForWifi = false),
                awaitItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a retry that will wait for wifi says so`() = runTest {
        downloads.value = listOf(download("broken", downloadState = DownloadState.FAILED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = true)
        coEvery { downloadRepository.download("broken") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.retry("broken")

            // Without this the tap looks like it did nothing at all until the phone finds Wi-Fi.
            assertEquals(
                DownloadsMessage.RetryQueued(title = "Episode broken", waitingForWifi = true),
                awaitItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retrying an episode that has gone is reported rather than left silent`() = runTest {
        downloads.value = listOf(download("broken", downloadState = DownloadState.FAILED))
        // The show was removed between the row rendering and the tap landing.
        coEvery { downloadRepository.download("broken") } returns false

        viewModel.uiState.test {
            awaitItem()

            viewModel.retry("broken")

            assertEquals(DownloadsMessage.EpisodeUnavailable, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing all counts the transfers it cancels as well as the files it deletes`() = runTest {
        downloads.value = listOf(
            download("done"),
            download("busy", downloadState = DownloadState.DOWNLOADING),
            download("waiting", downloadState = DownloadState.QUEUED),
        )

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeAll()

            coVerify { downloadRepository.removeAllDownloads() }
            // Three rows go, so the confirmation says three: the sweep stops the queued and
            // in-flight transfers too, and claiming one would misdescribe what just happened.
            assertEquals(DownloadsMessage.RemovedAll(3), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `free space is read once on arrival rather than on every list emission`() = runTest {
        downloads.value = listOf(download("a"))

        viewModel.uiState.test {
            assertEquals(FREE_BYTES, awaitItem().freeBytes)
            cancelAndIgnoreRemainingEvents()
        }

        // Twice, not five times: once from `init`, and the list emitting again — which a running
        // transfer does several times a second — must not cost a disk stat each time.
        coVerify(exactly = 1) { downloadRepository.freeBytes() }
    }

    @Test
    fun `removing a selection deletes exactly what was picked`() = runTest {
        downloads.value = listOf(download("a"), download("b"), download("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeSelected(setOf("a", "c"))

            coVerify { downloadRepository.removeDownload("a") }
            coVerify { downloadRepository.removeDownload("c") }
            coVerify(exactly = 0) { downloadRepository.removeDownload("b") }
            coVerify(exactly = 0) { downloadRepository.removeAllDownloads() }
            assertEquals(DownloadsMessage.RemovedAll(2), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting everything is the sweep, not one cancellation per episode`() = runTest {
        downloads.value = listOf(download("a"), download("b"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeSelected(setOf("a", "b"))

            coVerify { downloadRepository.removeAllDownloads() }
            coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
            assertEquals(DownloadsMessage.RemovedAll(2), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty selection does nothing at all`() = runTest {
        downloads.value = listOf(download("a"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeSelected(emptySet())

            coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
            coVerify(exactly = 0) { downloadRepository.removeAllDownloads() }
            expectNoEvents()
        }
    }

    @Test
    fun `marking a download played leaves the file where it is`() = runTest {
        downloads.value = listOf(download("a"))
        viewModel.uiState.test { awaitItem() }

        viewModel.markPlayed("a")

        coVerify { playbackRepository.setPlayed("a", true) }
        // This screen is about disk. Having listened to something is not a request to delete it.
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
    }
}

/** A plausible amount of free space; the figure only has to be recognisable in an assertion. */
private const val FREE_BYTES = 12_000_000_000L
