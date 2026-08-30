package md.borisveriga.bpodcat.feature.home

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.EpisodeWithShow
import md.borisveriga.bpodcat.core.testing.MainDispatcherRule
import md.borisveriga.bpodcat.core.testing.testEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [HomeViewModel].
 *
 * The repository is mocked; the date bucketing itself is pinned in [LatestSectionsTest] against a
 * fixed zone. What is worth pinning here is the orchestration the screen depends on: that the feed
 * is grouped through the *injected* clock rather than a call to `Instant.now()` buried in the
 * mapping, that "continue listening" is a different slice of the same query rather than a second
 * one, and that the two refreshes behave as differently as they look — the pull answers with a
 * snackbar and checks everything, the one on entry says nothing and skips fresh feeds, and neither
 * may start while the other is in flight.
 *
 * Episode timestamps are expressed relative to the clock's instant rather than as literals, so the
 * assertions hold in any machine time zone: "now" is today everywhere, and ten days ago is
 * [LatestSection.EARLIER] everywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now: Instant = Instant.parse("2026-08-30T14:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.systemDefault())

    private val latest = MutableStateFlow(emptyList<EpisodeWithShow>())

    private lateinit var podcastRepository: PodcastRepository
    private lateinit var downloadRepository: DownloadRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: HomeViewModel

    private fun entry(
        id: String,
        publishedAt: Instant = now,
        positionMs: Long = 0L,
        isPlayed: Boolean = false,
        downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    ) = EpisodeWithShow(
        episode = testEpisode(
            id = id,
            publishedAt = publishedAt,
            positionMs = positionMs,
            isPlayed = isPlayed,
            downloadState = downloadState,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        podcastRepository = mockk(relaxed = true)
        downloadRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        every { podcastRepository.observeLatestEpisodes(any()) } returns latest
        viewModel = HomeViewModel(podcastRepository, downloadRepository, episodePlayer, clock)
    }

    @Test
    fun `the first database emission clears the loading flag and buckets the feed`() = runTest {
        latest.value = listOf(
            entry("today"),
            entry("old", publishedAt = now.minus(Duration.ofDays(10))),
        )

        viewModel.uiState.test {
            val state = awaitItem()

            assertFalse(state.isLoading)
            assertFalse(state.isEmpty)
            assertEquals(
                listOf(LatestSection.TODAY, LatestSection.EARLIER),
                state.groups.map { it.section },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty feed is empty rather than loading`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()

            assertFalse(state.isLoading)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `continue listening holds the started but unfinished episodes only`() = runTest {
        latest.value = listOf(
            entry("untouched"),
            entry("started", positionMs = 30_000L),
            entry("finished", positionMs = 60_000L, isPlayed = true),
        )

        viewModel.uiState.test {
            val state = awaitItem()

            assertEquals(listOf("started"), state.continueListening.map { it.episode.id })
            // The shelf is a slice of the same feed, not a filter applied to it: everything still
            // appears in the list below, or resuming something would remove it from the feed.
            assertEquals(3, state.groups.sumOf { it.episodes.size })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing an episode hands off to the caller so it can open the player`() = runTest {
        latest.value = listOf(entry("a"))
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
    fun `an episode that has gone reports itself rather than opening an empty player`() = runTest {
        latest.value = listOf(entry("a"))
        coEvery { episodePlayer.play("a") } returns false
        var opened = false

        viewModel.uiState.test {
            awaitItem()

            viewModel.play("a") { opened = true }

            assertEquals(HomeMessage.EpisodeUnavailable, awaitItem().message)
            assertFalse(opened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queueing an episode names it in the confirmation`() = runTest {
        latest.value = listOf(entry("a"))
        coEvery { episodePlayer.addToQueue("a") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("a")

            assertEquals(HomeMessage.Queued("Episode a"), awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `queueing an episode that is no longer there says so`() = runTest {
        latest.value = listOf(entry("a"))
        coEvery { episodePlayer.addToQueue("a") } returns false

        viewModel.uiState.test {
            awaitItem()

            viewModel.addToQueue("a")

            assertEquals(HomeMessage.EpisodeUnavailable, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the download button starts a download when there is none`() = runTest {
        latest.value = listOf(entry("a"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.toggleDownload("a")

            coVerify(exactly = 1) { downloadRepository.download("a") }
            coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the download button cancels a transfer that is still running`() = runTest {
        // The in-flight states matter as much as COMPLETED: the button is the only way to stop a
        // download, so treating QUEUED or DOWNLOADING as "not downloaded" would start a second one.
        latest.value = listOf(
            entry("done", downloadState = DownloadState.COMPLETED),
            entry("busy", downloadState = DownloadState.DOWNLOADING),
            entry("waiting", downloadState = DownloadState.QUEUED),
        )

        viewModel.uiState.test {
            awaitItem()

            viewModel.toggleDownload("done")
            viewModel.toggleDownload("busy")
            viewModel.toggleDownload("waiting")

            coVerify(exactly = 1) { downloadRepository.removeDownload("done") }
            coVerify(exactly = 1) { downloadRepository.removeDownload("busy") }
            coVerify(exactly = 1) { downloadRepository.removeDownload("waiting") }
            coVerify(exactly = 0) { downloadRepository.download(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the download button retries a failed transfer`() = runTest {
        latest.value = listOf(entry("broken", downloadState = DownloadState.FAILED))

        viewModel.uiState.test {
            awaitItem()

            viewModel.toggleDownload("broken")

            coVerify(exactly = 1) { downloadRepository.download("broken") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pulling to refresh raises the spinner, drops it, and reports the summary`() = runTest {
        val summary = RefreshSummary(refreshedCount = 2)
        val release = CompletableDeferred<Unit>()
        coEvery { podcastRepository.refreshAll(onlyAutoRefreshable = false) } coAnswers {
            release.await()
            summary
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            assertTrue(awaitItem().isRefreshing)

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isRefreshing)
            assertEquals(HomeMessage.RefreshFinished(summary), done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pulling to refresh checks every show, including the ones opted out of background refresh`() =
        runTest {
            coEvery { podcastRepository.refreshAll(any()) } returns RefreshSummary()

            viewModel.uiState.test {
                awaitItem()
                viewModel.refresh()
                cancelAndIgnoreRemainingEvents()
            }

            // No staleAfter either: the gesture means "check now", and answering "nothing to do"
            // would be indistinguishable from the gesture not registering.
            coVerify(exactly = 1) { podcastRepository.refreshAll(onlyAutoRefreshable = false) }
        }

    @Test
    fun `the refresh on entry only touches stale opted-in feeds and says nothing`() = runTest {
        coEvery { podcastRepository.refreshAll(any(), any()) } returns
            RefreshSummary(refreshedCount = 1)

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshStale()
            assertTrue(awaitItem().isAutoRefreshing)

            val done = awaitItem()
            assertFalse(done.isAutoRefreshing)
            // A refresh the user did not ask for must not interrupt them with a snackbar.
            assertNull(done.message)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            podcastRepository.refreshAll(
                onlyAutoRefreshable = true,
                staleAfter = Duration.ofMinutes(15),
            )
        }
    }

    @Test
    fun `a pull while the entry refresh is running is dropped rather than queued`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { podcastRepository.refreshAll(any(), any()) } coAnswers {
            release.await()
            RefreshSummary()
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshStale()
            assertTrue(awaitItem().isAutoRefreshing)

            viewModel.refresh()

            release.complete(Unit)
            assertFalse(awaitItem().isAutoRefreshing)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { podcastRepository.refreshAll(onlyAutoRefreshable = false) }
    }

    @Test
    fun `a second pull while one is running is dropped rather than queued`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { podcastRepository.refreshAll(onlyAutoRefreshable = false) } coAnswers {
            release.await()
            RefreshSummary()
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            assertTrue(awaitItem().isRefreshing)
            viewModel.refresh()

            release.complete(Unit)
            assertFalse(awaitItem().isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { podcastRepository.refreshAll(onlyAutoRefreshable = false) }
    }

    @Test
    fun `a message is cleared once it has been shown`() = runTest {
        latest.value = listOf(entry("a"))
        coEvery { episodePlayer.addToQueue("a") } returns true

        viewModel.uiState.test {
            awaitItem()
            viewModel.addToQueue("a")
            assertEquals(HomeMessage.Queued("Episode a"), awaitItem().message)

            viewModel.onMessageShown()

            assertNull(awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
