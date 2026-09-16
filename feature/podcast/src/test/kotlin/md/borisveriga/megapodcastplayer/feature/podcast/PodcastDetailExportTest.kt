package md.borisveriga.megapodcastplayer.feature.podcast

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PodcastDetailViewModel]'s two exports: the show's audio to a folder, and its download
 * list to a Markdown file.
 *
 * What matters is what the user is told. An audio export runs in the background, so its outcome is
 * announced only when this screen saw it run or started it, never replayed from last week; a list
 * export says whether it wrote, found nothing to write, or failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailExportTest : PodcastDetailViewModelFixture() {

    @Test
    fun `exporting starts the exporter for this show and confirms it`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.exportDownloads("content://tree/music")

            verify { downloadExporter.start(podcast.id, "content://tree/music") }
            assertEquals(PodcastDetailMessage.ExportStarted, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a written download list lists only this show and says so`() = runTest {
        coEvery { downloadRepository.exportListMarkdown(podcast.id) } returns "# list"
        coEvery { fileStore.write(documentUri, "# list") } returns Result.success(Unit)
        viewModel.uiState.test {
            awaitItem()

            viewModel.exportDownloadList(documentUri)

            assertEquals(
                PodcastDetailMessage.DownloadListExport(DownloadListOutcome.WRITTEN),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a download list that could not be written says so`() = runTest {
        coEvery { downloadRepository.exportListMarkdown(podcast.id) } returns "# list"
        coEvery { fileStore.write(any(), any()) } returns Result.failure(RuntimeException("gone"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.exportDownloadList(documentUri)

            assertEquals(
                PodcastDetailMessage.DownloadListExport(DownloadListOutcome.FAILED),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty download list writes no file`() = runTest {
        coEvery { downloadRepository.exportListMarkdown(podcast.id) } returns ""
        viewModel.uiState.test {
            awaitItem()

            viewModel.exportDownloadList(documentUri)

            assertEquals(
                PodcastDetailMessage.DownloadListExport(DownloadListOutcome.EMPTY),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { fileStore.write(any(), any()) }
    }

    @Test
    fun `an export already running is not started again`() = runTest {
        exportRun.value = ExportRun.Running(ExportProgress(done = 1, total = 4))
        viewModel.uiState.test {
            assertEquals(ExportProgress(1, 4), awaitItem().exportProgress)

            viewModel.exportDownloads("content://tree/music")

            verify(exactly = 0) { downloadExporter.start(any(), any()) }
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
            viewModel.exportDownloads("content://tree/music")
            awaitItem()

            exportRun.value = ExportRun.Failed

            assertEquals(PodcastDetailMessage.ExportFailed, expectMostRecentItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a download list that cannot be read says so instead of crashing`() = runTest {
        coEvery {
            downloadRepository.exportListMarkdown(podcast.id)
        } throws IllegalStateException("db")
        viewModel.uiState.test {
            awaitItem()

            viewModel.exportDownloadList(documentUri)

            assertEquals(
                PodcastDetailMessage.DownloadListExport(DownloadListOutcome.FAILED),
                expectMostRecentItem().message,
            )
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
    fun `only a fully downloaded episode makes the show exportable`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.DOWNLOADING))
        viewModel.uiState.test {
            assertFalse(awaitItem().hasDownloads)

            episodes.value = listOf(episode("a", DownloadState.COMPLETED))

            assertTrue(awaitItem().hasDownloads)
        }
    }
}
