package md.borisveriga.megapodcastplayer.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreOptions
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.backup.BackupCodec
import md.borisveriga.megapodcastplayer.core.model.backup.BackupFile
import md.borisveriga.megapodcastplayer.core.model.backup.BackupPodcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [RestoreWorker].
 *
 * The worker's own logic is the handover: it is given a path rather than a picked `Uri`, so the
 * cases that matter are a good file, a missing one, and what it reports back to a screen that has
 * been watching for minutes.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreWorkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var backupRepository: BackupRepository
    private lateinit var crashReporter: CrashReporter

    private val backup = BackupFile(
        exportedAtMs = 5L,
        podcasts = listOf(
            BackupPodcast("https://feeds.example.com/a", PodcastSource.RSS, "First"),
        ),
    )

    @Before
    fun setUp() {
        backupRepository = mockk(relaxed = true)
        crashReporter = mockk(relaxed = true)
    }

    private fun buildWorker(path: String?, reDownload: Boolean = false): RestoreWorker {
        val input = if (path == null) {
            workDataOf(RestoreWorker.KEY_RE_DOWNLOAD to reDownload)
        } else {
            workDataOf(
                RestoreWorker.KEY_FILE_PATH to path,
                RestoreWorker.KEY_RE_DOWNLOAD to reDownload,
            )
        }
        return TestListenableWorkerBuilder<RestoreWorker>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setInputData(input)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = RestoreWorker(
                        appContext,
                        workerParameters,
                        backupRepository,
                        crashReporter,
                    )
                },
            )
            .build()
    }

    /** Writes a handover file holding [text], defaulting to a valid encoded backup. */
    private fun handover(text: String = BackupCodec.encode(backup)): File =
        temporaryFolder.newFile("pending-restore.json").apply { writeText(text) }

    @Test
    fun `restores the handed-over document and reports the summary`() = runTest {
        coEvery { backupRepository.restore(any(), any(), any()) } returns RestoreSummary(
            showsRestored = 3,
            episodesRestored = 12,
            episodesMissing = 1,
            queueRestored = 4,
            failedTitles = listOf("Dead Feed"),
        )

        val result = buildWorker(handover().absolutePath).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(3, output.getInt(RestoreWorker.KEY_SHOWS_RESTORED, 0))
        assertEquals(12, output.getInt(RestoreWorker.KEY_EPISODES_RESTORED, 0))
        assertEquals(1, output.getInt(RestoreWorker.KEY_EPISODES_MISSING, 0))
        assertEquals(4, output.getInt(RestoreWorker.KEY_QUEUE_RESTORED, 0))
        assertEquals(
            listOf("Dead Feed"),
            output.getStringArray(RestoreWorker.KEY_FAILED_TITLES)?.toList(),
        )
    }

    @Test
    fun `passes the re-download choice through`() = runTest {
        val options = slot<RestoreOptions>()
        coEvery { backupRepository.restore(any(), capture(options), any()) } returns RestoreSummary()

        buildWorker(handover().absolutePath, reDownload = true).doWork()

        assertTrue(options.captured.reDownload)
    }

    @Test
    fun `deletes the handover file once it is done with it`() = runTest {
        coEvery { backupRepository.restore(any(), any(), any()) } returns RestoreSummary()
        val file = handover()

        buildWorker(file.absolutePath).doWork()

        assertFalse("the cache file should not outlive the run", file.exists())
    }

    @Test
    fun `fails without throwing when the handover file is gone`() = runTest {
        val missing = File(temporaryFolder.root, "absent.json")

        val result = buildWorker(missing.absolutePath).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { backupRepository.restore(any(), any(), any()) }
    }

    @Test
    fun `fails when no path was handed over at all`() = runTest {
        val result = buildWorker(path = null).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `fails when the handover file is not a backup`() = runTest {
        val result = buildWorker(handover("not a backup").absolutePath).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { backupRepository.restore(any(), any(), any()) }
    }
}
