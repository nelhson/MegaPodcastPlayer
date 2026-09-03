package md.borisveriga.megapodcastplayer.feature.library

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.NewEpisode
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RefreshSummary
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import md.borisveriga.megapodcastplayer.core.testing.testEpisode
import md.borisveriga.megapodcastplayer.core.testing.testPodcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [LibraryViewModel].
 *
 * The repository is mocked: nothing here is storage behaviour, which is covered in
 * `OfflineFirstPodcastRepositoryTest` — including which feeds a staleness window actually skips.
 * What matters on this screen is the orchestration around the two refreshes, which differ in every
 * way that is visible to the user: the pull-to-refresh checks everything and answers with a
 * snackbar, the one that runs on entering the screen checks only what is stale and opted in, and
 * says nothing. Neither may run while the other is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = MutableStateFlow(emptyList<PodcastWithCounts>())

    private val layout = MutableStateFlow(LibraryLayout.DEFAULT)

    private lateinit var repository: PodcastRepository
    private lateinit var uiPreferences: UiPreferencesRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: LibraryViewModel

    private fun withCounts(
        id: String,
        newEpisodeCount: Int = 0,
    ) = PodcastWithCounts(
        podcast = testPodcast(id = id),
        episodeCount = 10,
        newEpisodeCount = newEpisodeCount,
        downloadedCount = 0,
    )

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        every { repository.observeLibrary() } returns library
        uiPreferences = mockk(relaxed = true)
        every { uiPreferences.observeLibraryLayout() } returns layout
        episodePlayer = mockk(relaxed = true)
        viewModel = LibraryViewModel(repository, uiPreferences, episodePlayer)
    }

    @Test
    fun `the first database emission clears the loading flag`() = runTest {
        library.value = listOf(withCounts("a"), withCounts("b"))

        viewModel.uiState.test {
            // The seeded initial value is discarded; the first combined emission is the real one.
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(listOf("a", "b"), state.podcasts.map { it.podcast.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh all raises the spinner, drops it, and reports the summary`() = runTest {
        val summary = RefreshSummary(
            refreshedCount = 2,
            newEpisodes = List(3) { index ->
                NewEpisode(
                    episodeId = "ep-$index",
                    episodeTitle = "Episode $index",
                    podcastId = "pod-1",
                    podcastTitle = "Show pod-1",
                )
            },
        )
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll(onlyAutoRefreshable = false) } coAnswers {
            release.await()
            summary
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshAll()
            assertTrue(awaitItem().isRefreshing)

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isRefreshing)
            assertEquals(LibraryMessage.RefreshFinished(summary), done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an explicit refresh checks every show, not only the auto-refreshable ones`() = runTest {
        coEvery { repository.refreshAll(any()) } returns RefreshSummary()

        viewModel.uiState.test {
            awaitItem()
            viewModel.refreshAll()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.refreshAll(onlyAutoRefreshable = false) }
    }

    @Test
    fun `a second refresh while one is in flight is ignored`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll(any()) } coAnswers {
            release.await()
            RefreshSummary()
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshAll()
            assertTrue(awaitItem().isRefreshing)
            viewModel.refreshAll()

            release.complete(Unit)
            assertFalse(awaitItem().isRefreshing)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.refreshAll(any()) }
    }

    @Test
    fun `removing a show confirms with its title`() = runTest {
        val podcast = withCounts("a")

        viewModel.uiState.test {
            awaitItem()

            viewModel.remove(podcast)
            val state = awaitItem()
            assertEquals(LibraryMessage.Removed(podcast.podcast.title), state.message)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.remove("a") }
    }

    @Test
    fun `a shown message is cleared and does not come back`() = runTest {
        viewModel.uiState.test {
            awaitItem()

            viewModel.remove(withCounts("a"))
            assertTrue(awaitItem().message is LibraryMessage.Removed)

            viewModel.onMessageShown()
            assertEquals(null, awaitItem().message)

            // A later library emission must not resurrect the message.
            library.value = listOf(withCounts("b"))
            assertEquals(null, awaitItem().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `entering the screen refreshes only stale opted-in shows`() = runTest {
        coEvery { repository.refreshAll(any(), any()) } returns RefreshSummary()

        viewModel.uiState.test {
            awaitItem()
            viewModel.refreshStale()
            cancelAndIgnoreRemainingEvents()
        }

        // Both arguments matter. The window is what stops a burst of tab switches becoming a burst
        // of requests, and honouring the per-show toggle is what keeps that toggle meaning
        // something once the manual refresh button is gone.
        coVerify(exactly = 1) {
            repository.refreshAll(
                onlyAutoRefreshable = true,
                staleAfter = Duration.ofMinutes(15),
            )
        }
    }

    @Test
    fun `an automatic refresh raises its own flag and says nothing when it finishes`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll(any(), any()) } coAnswers {
            release.await()
            RefreshSummary(refreshedCount = 2, newEpisodes = emptyList())
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshStale()
            val running = awaitItem()
            assertTrue(running.isAutoRefreshing)
            assertFalse("The pull-to-refresh spinner must stay down", running.isRefreshing)

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isAutoRefreshing)
            // The whole point of the quiet refresh: no snackbar on entering the screen.
            assertEquals(null, done.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an automatic refresh does not start on top of a pull-to-refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll(any(), any()) } coAnswers {
            release.await()
            RefreshSummary()
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshAll()
            assertTrue(awaitItem().isRefreshing)
            // Returning to the screen while a pull-to-refresh runs must not cancel out its answer.
            viewModel.refreshStale()

            release.complete(Unit)
            val done = awaitItem()
            assertFalse(done.isRefreshing)
            assertTrue(done.message is LibraryMessage.RefreshFinished)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.refreshAll(any(), any()) }
    }

    @Test
    fun `a pull-to-refresh does not start on top of an automatic refresh`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll(any(), any()) } coAnswers {
            release.await()
            RefreshSummary()
        }

        viewModel.uiState.test {
            awaitItem()

            viewModel.refreshStale()
            assertTrue(awaitItem().isAutoRefreshing)
            viewModel.refreshAll()

            release.complete(Unit)
            assertFalse(awaitItem().isAutoRefreshing)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { repository.refreshAll(any(), any()) }
    }

    @Test
    fun `the stored layout reaches the state`() = runTest {
        layout.value = LibraryLayout.LIST

        viewModel.uiState.test {
            assertEquals(LibraryLayout.LIST, awaitItem().layout)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `choosing a layout stores it rather than keeping it in the composition`() = runTest {
        viewModel.setLayout(LibraryLayout.LIST)
        runCurrent()

        coVerify { uiPreferences.setLibraryLayout(LibraryLayout.LIST) }
    }

    @Test
    fun `a reorder is written as the whole arrangement`() = runTest {
        library.value = listOf(withCounts("a"), withCounts("b"), withCounts("c"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.move(from = 0, to = 2)
            runCurrent()

            // The screen reports two positions; the database wants the whole order, and rebuilding
            // it here keeps "what the library contains" in one place rather than trusting a copy
            // the UI may be a frame behind on mid-drag.
            coVerify { repository.reorderLibrary(listOf("b", "c", "a")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder that lands where it started writes nothing`() = runTest {
        library.value = listOf(withCounts("a"), withCounts("b"))
        viewModel.uiState.test {
            awaitItem()

            viewModel.move(from = 1, to = 1)
            runCurrent()

            coVerify(exactly = 0) { repository.reorderLibrary(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder naming a position the library no longer has writes nothing`() = runTest {
        library.value = listOf(withCounts("a"), withCounts("b"))
        viewModel.uiState.test {
            awaitItem()

            // The gesture raced an unsubscribe. Guessing what the user meant would be worse than
            // doing nothing, and clamping would silently move the wrong show.
            viewModel.move(from = 0, to = 5)
            runCurrent()

            coVerify(exactly = 0) { repository.reorderLibrary(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a full swipe queues the newest unplayed episode and names it back`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.newestUnplayedEpisode("a") } returns
            testEpisode(id = "ep-7", podcastId = "a").copy(title = "The AI bubble, revisited")
        coEvery { episodePlayer.addToQueue("ep-7") } returns true

        viewModel.uiState.test {
            awaitItem()

            viewModel.queueNewest(podcast)
            runCurrent()

            coVerify(exactly = 1) { episodePlayer.addToQueue("ep-7") }
            // The episode rather than the show: the row said which show it was, and which episode
            // was queued is the part the gesture leaves invisible.
            assertEquals(
                LibraryMessage.Queued("The AI bubble, revisited"),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a full swipe on a finished show queues nothing and says so`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.newestUnplayedEpisode("a") } returns null

        viewModel.uiState.test {
            awaitItem()

            viewModel.queueNewest(podcast)
            runCurrent()

            coVerify(exactly = 0) { episodePlayer.addToQueue(any()) }
            // Silence would be indistinguishable from a gesture that never registered.
            assertEquals(
                LibraryMessage.NothingToQueue("Show a"),
                expectMostRecentItem().message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an episode the player will not accept is not reported as queued`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.newestUnplayedEpisode("a") } returns
            testEpisode(id = "ep-7", podcastId = "a")
        // The show was removed under the gesture, so the player cannot resolve the episode.
        coEvery { episodePlayer.addToQueue("ep-7") } returns false

        viewModel.uiState.test {
            awaitItem()

            viewModel.queueNewest(podcast)
            runCurrent()

            assertTrue(expectMostRecentItem().message is LibraryMessage.NothingToQueue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `mark all played reports what changed, and the undo restores exactly that`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.markPodcastPlayed("a") } returns listOf("ep-1", "ep-2")

        viewModel.uiState.test {
            awaitItem()

            viewModel.markAllPlayed(podcast)
            runCurrent()
            assertEquals(
                LibraryMessage.MarkedAllPlayed("Show a", count = 2),
                expectMostRecentItem().message,
            )

            viewModel.undoMarkAllPlayed()
            runCurrent()

            // The two it marked, not the show. Un-playing the whole show would also reopen
            // episodes the user finished months before the gesture.
            coVerify(exactly = 1) {
                repository.setEpisodesPlayed(listOf("ep-1", "ep-2"), isPlayed = false)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the undo is spent once, and does not survive its snackbar`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.markPodcastPlayed("a") } returns listOf("ep-1")

        viewModel.uiState.test {
            awaitItem()

            viewModel.markAllPlayed(podcast)
            runCurrent()
            viewModel.undoMarkAllPlayed()
            runCurrent()
            // A second tap on a snackbar that has already been acted on.
            viewModel.undoMarkAllPlayed()
            runCurrent()

            coVerify(exactly = 1) { repository.setEpisodesPlayed(any(), any()) }

            viewModel.markAllPlayed(podcast)
            runCurrent()
            // The snackbar timed out rather than being tapped. An undo still armed here would fire
            // against whichever message came next.
            viewModel.onMessageShown()
            viewModel.undoMarkAllPlayed()
            runCurrent()

            coVerify(exactly = 1) { repository.setEpisodesPlayed(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `marking an already finished show off says so rather than counting zero`() = runTest {
        val podcast = withCounts("a")
        library.value = listOf(podcast)
        coEvery { repository.markPodcastPlayed("a") } returns emptyList()

        viewModel.uiState.test {
            awaitItem()

            viewModel.markAllPlayed(podcast)
            runCurrent()

            assertEquals(
                LibraryMessage.MarkedAllPlayed("Show a", count = 0),
                expectMostRecentItem().message,
            )
            // Nothing changed, so there is nothing to offer back.
            viewModel.undoMarkAllPlayed()
            runCurrent()
            coVerify(exactly = 0) { repository.setEpisodesPlayed(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
