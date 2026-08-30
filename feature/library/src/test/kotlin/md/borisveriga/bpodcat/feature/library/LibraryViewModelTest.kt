package md.borisveriga.bpodcat.feature.library

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.repository.NewEpisode
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import md.borisveriga.bpodcat.core.model.PodcastWithCounts
import md.borisveriga.bpodcat.core.testing.MainDispatcherRule
import md.borisveriga.bpodcat.core.testing.testPodcast
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
 * `OfflineFirstPodcastRepositoryTest`. What matters on this screen is the orchestration around a
 * manual refresh — that it cannot be started twice, that the spinner goes down again, and that the
 * one-off outcomes reach the snackbar exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = MutableStateFlow(emptyList<PodcastWithCounts>())

    private lateinit var repository: PodcastRepository
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
        viewModel = LibraryViewModel(repository)
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
}
