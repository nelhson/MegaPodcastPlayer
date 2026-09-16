package md.borisveriga.megapodcastplayer.feature.podcast

import app.cash.turbine.test
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.export.ExportNetwork
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PodcastDetailViewModel]'s *Download and export*: the episodes on screen, downloaded and
 * then copied into a folder the user named.
 *
 * What matters is what gets started and what the user is told. The run is handed the filter on
 * screen and the name the user typed; it runs in the background, so its outcome is announced only
 * when this screen saw it run or started it, never replayed from last week.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailExportTest : PodcastDetailViewModelFixture() {

    @Test
    fun `starting hands over the folder, its name and the filter on screen`() = runTest {
        storedSettings.value = ShowSettings.DEFAULT.copy(episodeFilter = EpisodeFilter.UNPLAYED)
        downloadSettings.value = DownloadSettings(unmeteredOnly = false)
        viewModel.uiState.test {
            awaitItem()

            viewModel.downloadAndExport("content://tree/music", "  Talks  ")
            runCurrent()

            verify {
                downloadExporter.start(
                    podcast.id,
                    "content://tree/music",
                    "Talks",
                    EpisodeFilter.UNPLAYED,
                    ExportNetwork.NONE,
                )
            }
            assertEquals(
                PodcastDetailMessage.ExportStarted(waitingForWifi = false),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the filter comes from storage, not from a screen state that may not have loaded`() =
        runTest {
            // What a view model rebuilt after process death looks like: nobody has collected its
            // state yet, so it still holds the default filter — All.
            storedSettings.value =
                ShowSettings.DEFAULT.copy(episodeFilter = EpisodeFilter.UNPLAYED)

            viewModel.downloadAndExport("content://tree/music", "Talks")
            runCurrent()

            verify {
                downloadExporter.start(
                    podcast.id,
                    "content://tree/music",
                    "Talks",
                    EpisodeFilter.UNPLAYED,
                    any(),
                )
            }
        }

    @Test
    fun `a run with downloads to make waits for Wi-Fi when downloads do, and says so`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        downloadSettings.value = DownloadSettings(unmeteredOnly = true)
        viewModel.uiState.test {
            awaitItem()

            viewModel.downloadAndExport("content://tree/music", "Talks")
            runCurrent()

            verify {
                downloadExporter.start(any(), any(), any(), any(), ExportNetwork.UNMETERED)
            }
            assertEquals(
                PodcastDetailMessage.ExportStarted(waitingForWifi = true),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a run with nothing left to download waits for no network, and says nothing of Wi-Fi`() =
        runTest {
            episodes.value = listOf(episode("a", DownloadState.COMPLETED))
            downloadSettings.value = DownloadSettings(unmeteredOnly = true)
            viewModel.uiState.test {
                awaitItem()

                viewModel.downloadAndExport("content://tree/music", "Talks")
                runCurrent()

                verify { downloadExporter.start(any(), any(), any(), any(), ExportNetwork.NONE) }
                assertEquals(
                    PodcastDetailMessage.ExportStarted(waitingForWifi = false),
                    expectMostRecentItem().message,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a blank folder name starts nothing`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.downloadAndExport("content://tree/music", "   ")

            verify(exactly = 0) { downloadExporter.start(any(), any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an export already running is not started again`() = runTest {
        exportRun.value = ExportRun.Running(ExportProgress(done = 1, total = 4))
        viewModel.uiState.test {
            assertEquals(ExportProgress(1, 4), awaitItem().exportProgress)

            viewModel.downloadAndExport("content://tree/music", "Talks")

            verify(exactly = 0) { downloadExporter.start(any(), any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an export seen running reports its summary when it finishes`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            exportRun.value = ExportRun.Running(ExportProgress(done = 2, total = 3))
            assertEquals(ExportProgress(2, 3), awaitItem().exportProgress)

            val summary = ExportSummary(exported = 2, alreadyThere = 0, failed = 1)
            exportRun.value = ExportRun.Finished(summary)

            val finished = expectMostRecentItem()
            assertNull(finished.exportProgress)
            assertEquals(PodcastDetailMessage.ExportFinished(summary), finished.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an export seen running that dies reports the failure`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            exportRun.value = ExportRun.Running(ExportProgress(done = 0, total = 0))
            awaitItem()

            exportRun.value = ExportRun.Failed

            assertEquals(PodcastDetailMessage.ExportFailed, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an export started here that fails before running is still reported`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            viewModel.downloadAndExport("content://tree/music", "Talks")
            runCurrent()

            exportRun.value = ExportRun.Failed

            assertEquals(PodcastDetailMessage.ExportFailed, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a finished export replayed on opening the show is not announced again`() = runTest {
        exportRun.value = ExportRun.Finished(
            ExportSummary(exported = 5, alreadyThere = 0, failed = 0),
        )
        viewModel.uiState.test {
            runCurrent()
            val state = expectMostRecentItem()
            assertNull(state.message)
            assertNull(state.exportProgress)
        }
    }

    @Test
    fun `a show with nothing downloaded can still be exported`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        viewModel.uiState.test {
            assertTrue(expectMostRecentItem().hasExportableEpisodes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a filter that lists nothing leaves nothing to export`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        storedSettings.value = ShowSettings.DEFAULT.copy(episodeFilter = EpisodeFilter.DOWNLOADED)
        viewModel.uiState.test {
            assertFalse(expectMostRecentItem().hasExportableEpisodes)

            episodes.value = listOf(episode("a", DownloadState.COMPLETED))

            assertTrue(awaitItem().hasExportableEpisodes)
        }
    }
}
