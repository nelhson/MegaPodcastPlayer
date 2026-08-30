package md.borisveriga.bpodcat.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.repository.NewEpisode
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the periodic refresh worker.
 *
 * Robolectric supplies the [Context] a [ListenableWorker] cannot be built without; everything the
 * worker actually depends on is a fake, so nothing here touches the network or the database.
 */
@RunWith(RobolectricTestRunner::class)
class RefreshWorkerTest {

    private lateinit var repository: PodcastRepository
    private lateinit var notifier: RecordingNotifier

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        notifier = RecordingNotifier()
    }

    /** Records what it was asked to post, so a test can assert on it. */
    private class RecordingNotifier : NewEpisodeNotifier {
        var posted: List<NewEpisode>? = null

        override fun notifyNewEpisodes(newEpisodes: List<NewEpisode>) {
            posted = newEpisodes
        }
    }

    /**
     * Builds the worker under test.
     *
     * @param runAttemptCount which attempt this is, so the retry ceiling can be exercised.
     */
    private fun buildWorker(runAttemptCount: Int = 0): RefreshWorker =
        TestListenableWorkerBuilder<RefreshWorker>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = RefreshWorker(
                        appContext,
                        workerParameters,
                        repository,
                        notifier,
                    )
                },
            )
            .build()

    private fun newEpisode(id: String) = NewEpisode(
        episodeId = id,
        episodeTitle = "Episode $id",
        podcastId = "pod-1",
        podcastTitle = "Show pod-1",
    )

    @Test
    fun `refreshes only the shows that opted in`() = runTest {
        coEvery { repository.refreshAll(any()) } returns RefreshSummary(refreshedCount = 1)

        buildWorker().doWork()

        // The per-show toggle is the whole point of the background run; refreshing everything here
        // would silently override it.
        coVerify(exactly = 1) { repository.refreshAll(onlyAutoRefreshable = true) }
    }

    @Test
    fun `tells the user about what it found and reports success`() = runTest {
        val discovered = listOf(newEpisode("a"), newEpisode("b"))
        coEvery { repository.refreshAll(any()) } returns
            RefreshSummary(refreshedCount = 1, newEpisodes = discovered)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(discovered, notifier.posted)
    }

    @Test
    fun `a quiet run posts nothing`() = runTest {
        coEvery { repository.refreshAll(any()) } returns RefreshSummary(notModifiedCount = 3)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<NewEpisode>(), notifier.posted)
    }

    @Test
    fun `an empty library succeeds instead of retrying forever`() = runTest {
        coEvery { repository.refreshAll(any()) } returns RefreshSummary()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
    }

    @Test
    fun `every feed failing is treated as a bad network and retried`() = runTest {
        coEvery { repository.refreshAll(any()) } returns
            RefreshSummary(failedTitles = listOf("Podlodka Podcast", "Радио-Т"))

        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
    }

    @Test
    fun `one feed failing among several is not a failed run`() = runTest {
        coEvery { repository.refreshAll(any()) } returns
            RefreshSummary(refreshedCount = 1, failedTitles = listOf("Podlodka Podcast"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
    }

    @Test
    fun `retrying stops once the attempts are spent`() = runTest {
        coEvery { repository.refreshAll(any()) } returns
            RefreshSummary(failedTitles = listOf("Podlodka Podcast"))

        val result = buildWorker(runAttemptCount = 3).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
