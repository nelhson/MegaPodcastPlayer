package md.borisveriga.megapodcastplayer.feature.downloads

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
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
 * episodes even though the list shows more than those — the retry and removal paths, and the
 * pull-to-refresh, whose whole job is the one figure on the screen that is sampled rather than
 * observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloads = MutableStateFlow(emptyList<EpisodeWithShow>())
    private val downloadSettings = MutableStateFlow(DownloadSettings())

    private lateinit var downloadRepository: DownloadRepository
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
        every { downloadRepository.observeDownloads() } returns downloads
        every { downloadRepository.observeDownloadSettings() } returns downloadSettings
        coEvery { downloadRepository.freeBytes() } returns FREE_BYTES
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
    fun `queueing a download adds it to the queue and confirms it by name`() = runTest {
        downloads.value = listOf(download("a"))
        coEvery { episodePlayer.addToQueue("a") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("a")

            coVerify { episodePlayer.addToQueue("a") }
            // The queue is another tab, so the row does not visibly change: without the message a
            // tap on the button looks like a tap that missed.
            assertEquals(DownloadsMessage.Queued("Episode a"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a transfer that has not finished can still be queued`() = runTest {
        downloads.value = listOf(
            download("busy", downloadState = DownloadState.DOWNLOADING, downloadPercent = 40f),
        )
        coEvery { episodePlayer.addToQueue("busy") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("busy")

            // The file will have arrived by the time the queue reaches it; refusing here would
            // mean coming back to this screen and remembering.
            coVerify { episodePlayer.addToQueue("busy") }
            assertEquals(DownloadsMessage.Queued("Episode busy"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queueing an episode that has gone is reported rather than claimed`() = runTest {
        downloads.value = listOf(download("a"))
        // The show was removed between the row rendering and the tap landing.
        coEvery { episodePlayer.addToQueue("a") } returns false

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("a")

            assertEquals(DownloadsMessage.EpisodeUnavailable, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a completed drag stores the whole arrangement, not the two positions`() = runTest {
        downloads.value = listOf(download("a"), download("b"), download("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.move(visibleIds = listOf("a", "b", "c"), from = 2, to = 0)

            coVerify { downloadRepository.reorderDownloads(listOf("c", "a", "b")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a drag reported against a list that has moved on is ignored`() = runTest {
        downloads.value = listOf(download("a"))

        viewModel.uiState.test {
            awaitItem()

            // A transfer finishing can re-sort the list mid-gesture, leaving an index that names
            // nothing; writing an arrangement built from it would scramble the order.
            viewModel.move(visibleIds = listOf("a", "b"), from = 1, to = 5)
            viewModel.move(visibleIds = listOf("a", "b"), from = 0, to = 0)

            coVerify(exactly = 0) { downloadRepository.reorderDownloads(any()) }
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
    fun `a pull re-reads free space and puts the spinner away afterwards`() = runTest {
        downloads.value = listOf(download("a"))

        viewModel.uiState.test {
            assertEquals(FREE_BYTES, awaitItem().freeBytes)

            // A different figure the second time, so the assertion is about the re-read rather
            // than about the number happening to match.
            coEvery { downloadRepository.freeBytes() } returns FREE_BYTES / 2

            viewModel.refresh()

            assertTrue("The gesture has to show it registered", awaitItem().isRefreshing)

            val done = awaitItem()
            assertFalse(done.isRefreshing)
            assertEquals(FREE_BYTES / 2, done.freeBytes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second pull while one is running reads nothing twice`() = runTest {
        downloads.value = listOf(download("a"))
        // Held open so the two pulls genuinely overlap: without this the first finishes between
        // them and the second is a fresh gesture rather than the one the guard is there for.
        val firstRead = CompletableDeferred<Unit>()
        coEvery { downloadRepository.freeBytes() } coAnswers {
            firstRead.await()
            FREE_BYTES
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            viewModel.refresh()
            firstRead.complete(Unit)

            cancelAndIgnoreRemainingEvents()
        }

        // Once from `init` and once from the pair of pulls: the second found one already running.
        coVerify(exactly = 2) { downloadRepository.freeBytes() }
    }
}

/** A plausible amount of free space; the figure only has to be recognisable in an assertion. */
private const val FREE_BYTES = 12_000_000_000L
