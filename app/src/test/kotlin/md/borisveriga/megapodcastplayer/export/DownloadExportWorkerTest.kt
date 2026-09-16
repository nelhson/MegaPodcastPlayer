package md.borisveriga.megapodcastplayer.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.export.EpisodeAudioExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [DownloadExportWorker] and for how its work states read back as [ExportRun].
 *
 * The copying itself is `EpisodeAudioExporter`'s and is tested there; what is the worker's own is
 * the handover in, the summary out, and a failure that becomes a failed run instead of a crash.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadExportWorkerTest {

    private lateinit var exporter: EpisodeAudioExporter
    private lateinit var crashReporter: CrashReporter

    @Before
    fun setUp() {
        exporter = mockk()
        crashReporter = mockk(relaxed = true)
    }

    private fun buildWorker(podcastId: String? = "show", treeUri: String? = "content://tree/1") =
        TestListenableWorkerBuilder<DownloadExportWorker>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setInputData(
                workDataOf(
                    DownloadExportWorker.KEY_PODCAST_ID to podcastId,
                    DownloadExportWorker.KEY_TREE_URI to treeUri,
                ),
            )
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = DownloadExportWorker(
                        appContext,
                        workerParameters,
                        exporter,
                        crashReporter,
                    )
                },
            )
            .build()

    @Test
    fun `exports the handed-over show into the handed-over folder and reports the summary`() =
        runTest {
            coEvery { exporter.export("show", "content://tree/1", any()) } returns
                Result.success(ExportSummary(exported = 4, alreadyThere = 2, failed = 1))

            val result = buildWorker().doWork()

            val output = (result as ListenableWorker.Result.Success).outputData
            assertEquals(4, output.getInt(DownloadExportWorker.KEY_EXPORTED, -1))
            assertEquals(2, output.getInt(DownloadExportWorker.KEY_ALREADY_THERE, -1))
            assertEquals(1, output.getInt(DownloadExportWorker.KEY_FAILED, -1))
        }

    @Test
    fun `an export that cannot reach the folder fails the run`() = runTest {
        coEvery { exporter.export(any(), any(), any()) } returns
            Result.failure(IOException("No permission"))

        assertEquals(ListenableWorker.Result.failure(), buildWorker().doWork())
    }

    @Test
    fun `fails without exporting when the input is missing`() = runTest {
        assertEquals(ListenableWorker.Result.failure(), buildWorker(podcastId = null).doWork())
        assertEquals(ListenableWorker.Result.failure(), buildWorker(treeUri = null).doWork())
        coVerify(exactly = 0) { exporter.export(any(), any(), any()) }
    }

    @Test
    fun `a running or waiting export reads as running with its progress`() {
        val running = workInfo(
            WorkInfo.State.RUNNING,
            progress = workDataOf(
                DownloadExportWorker.KEY_DONE to 3,
                DownloadExportWorker.KEY_TOTAL to 9,
            ),
        )

        assertEquals(ExportRun.Running(ExportProgress(3, 9)), running.asExportRun())
        assertEquals(
            ExportRun.Running(ExportProgress(0, 0)),
            workInfo(WorkInfo.State.ENQUEUED).asExportRun(),
        )
    }

    @Test
    fun `a finished export reads back its summary`() {
        val finished = workInfo(
            WorkInfo.State.SUCCEEDED,
            output = workDataOf(
                DownloadExportWorker.KEY_EXPORTED to 5,
                DownloadExportWorker.KEY_ALREADY_THERE to 1,
                DownloadExportWorker.KEY_FAILED to 0,
            ),
        )

        assertEquals(ExportRun.Finished(ExportSummary(5, 1, 0)), finished.asExportRun())
    }

    @Test
    fun `a failed or cancelled export reads as failed`() {
        assertEquals(ExportRun.Failed, workInfo(WorkInfo.State.FAILED).asExportRun())
        assertEquals(ExportRun.Failed, workInfo(WorkInfo.State.CANCELLED).asExportRun())
    }

    private fun workInfo(
        state: WorkInfo.State,
        output: androidx.work.Data = workDataOf(),
        progress: androidx.work.Data = workDataOf(),
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = emptySet(),
        outputData = output,
        progress = progress,
    )
}
