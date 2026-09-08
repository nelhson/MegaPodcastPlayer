package md.borisveriga.megapodcastplayer.feature.search

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.AddPodcastResult
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastPreviewResult
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastPreview
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import md.borisveriga.megapodcastplayer.core.testing.testEpisode
import md.borisveriga.megapodcastplayer.core.testing.testPodcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [SearchViewModel].
 *
 * Two things on this screen are easy to break and invisible when broken: the debounce that keeps
 * the app inside Apple's rate limit, and the branch that decides whether typed text is a search
 * term or a link to add. Both are asserted against virtual time, so the 400 ms wait costs nothing
 * in wall clock.
 *
 * Collection is driven by a background job rather than Turbine, because Turbine's await timeouts
 * advance virtual time themselves — which would let the debounce fire behind a timing assertion's
 * back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: PodcastRepository
    private lateinit var library: MutableStateFlow<List<PodcastWithCounts>>
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        coEvery { repository.search(any()) } returns Result.success(emptyList())
        // `uiState` combines the library in, and `combine` emits nothing until every source has
        // emitted once — so an unstubbed library flow would freeze the whole screen at its initial
        // value and quietly pass every assertion below.
        library = MutableStateFlow(emptyList())
        every { repository.observeLibrary() } returns library
        viewModel = SearchViewModel(repository, episodePlayer, SavedStateHandle())
    }

    /**
     * Wraps shows as the library observes them.
     *
     * @param podcasts the shows; the counts are irrelevant to this screen.
     */
    private fun libraryOf(vararg podcasts: Podcast) = podcasts.map {
        PodcastWithCounts(podcast = it, episodeCount = 0, newEpisodeCount = 0, downloadedCount = 0)
    }

    /**
     * Subscribes to [SearchViewModel.uiState] for the rest of the test.
     *
     * `uiState` and the search pipeline behind it are both `WhileSubscribed`, so with no collector
     * nothing runs at all and every assertion here would trivially pass.
     */
    private fun TestScope.subscribe() {
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
    }

    private fun searchResult(id: Long) = PodcastSearchResult(
        itunesId = id,
        title = "Show $id",
        author = "Author $id",
        feedUrl = "https://example.com/$id.xml",
        artworkUrl = null,
        episodeCount = 10,
        genres = emptyList(),
    )

    /**
     * A preview of [result] as the repository would return it.
     *
     * @param result the search result the sheet was opened from.
     */
    private fun previewOf(result: PodcastSearchResult) = PodcastPreview(
        podcast = testPodcast(id = "podcast-${result.itunesId}", title = result.title),
        episodes = listOf(testEpisode(id = "ep-1", podcastId = "podcast-${result.itunesId}")),
        totalEpisodeCount = 412,
    )

    /**
     * Tapping a result used to subscribe on the spot. The sheet is what replaced that, and the
     * whole of its value rests on nothing being written until the user says so.
     */
    @Test
    fun `opening a preview fetches the feed and adds nothing`() = runTest {
        val result = searchResult(1L)
        coEvery { repository.preview(result) } returns
            PodcastPreviewResult.Loaded(previewOf(result))

        subscribe()
        viewModel.openPreview(result)
        runCurrent()

        val preview = checkNotNull(viewModel.uiState.value.preview)
        assertFalse(preview.isLoading)
        assertEquals(412, preview.preview?.totalEpisodeCount)
        coVerify(exactly = 0) { repository.addFromSearchResult(any()) }
    }

    @Test
    fun `a show with no feed is a different failure from an unreachable one`() = runTest {
        val noFeed = searchResult(1L)
        val broken = searchResult(2L)
        coEvery { repository.preview(noFeed) } returns
            PodcastPreviewResult.NoFeedAvailable(noFeed.title)
        coEvery { repository.preview(broken) } returns
            PodcastPreviewResult.Failed(IllegalStateException("boom"))

        subscribe()

        viewModel.openPreview(noFeed)
        runCurrent()
        assertEquals(PreviewError.NoFeed, viewModel.uiState.value.preview?.error)

        viewModel.openPreview(broken)
        runCurrent()
        assertEquals(PreviewError.Unreachable, viewModel.uiState.value.preview?.error)
    }

    /**
     * A slow feed must not fill in a sheet the user has moved on from — nor, worse, overwrite the
     * one they opened next.
     */
    @Test
    fun `a preview that arrives after the sheet moved on is dropped`() = runTest {
        val first = searchResult(1L)
        val second = searchResult(2L)
        val release = CompletableDeferred<Unit>()
        coEvery { repository.preview(first) } coAnswers {
            release.await()
            PodcastPreviewResult.Loaded(previewOf(first))
        }
        coEvery { repository.preview(second) } returns
            PodcastPreviewResult.Loaded(previewOf(second))

        subscribe()
        viewModel.openPreview(first)
        runCurrent()
        viewModel.openPreview(second)
        runCurrent()

        release.complete(Unit)
        runCurrent()

        assertEquals(second.itunesId, viewModel.uiState.value.preview?.result?.itunesId)
        assertEquals(second.title, viewModel.uiState.value.preview?.preview?.podcast?.title)
    }

    @Test
    fun `subscribing from the sheet adds the show and closes it`() = runTest {
        val result = searchResult(1L)
        coEvery { repository.preview(result) } returns
            PodcastPreviewResult.Loaded(previewOf(result))
        coEvery { repository.addFromSearchResult(result) } returns
            AddPodcastResult.Added(testPodcast(id = "podcast-1"), episodeCount = 412)

        subscribe()
        viewModel.openPreview(result)
        runCurrent()
        viewModel.subscribeFromPreview()
        runCurrent()

        coVerify(exactly = 1) { repository.addFromSearchResult(result) }
        assertNull(viewModel.uiState.value.preview)
        // Adding from a result never navigates: the user is reading a list and may want several.
        assertNull(viewModel.uiState.value.navigateToPodcastId)
    }

    /** The half a description cannot do: hearing the show without following it first. */
    @Test
    fun `playing a preview episode plays it and adds nothing`() = runTest {
        val result = searchResult(1L)
        val preview = previewOf(result)
        coEvery { repository.preview(result) } returns PodcastPreviewResult.Loaded(preview)

        subscribe()
        viewModel.openPreview(result)
        runCurrent()
        viewModel.playPreviewEpisode(preview.episodes.single())
        runCurrent()

        coVerify(exactly = 1) {
            episodePlayer.playUnsubscribed(
                episode = preview.episodes.single(),
                showTitle = preview.podcast.title,
                showArtworkUrl = preview.podcast.artworkUrl,
            )
        }
        coVerify(exactly = 0) { repository.addFromSearchResult(any()) }
    }

    @Test
    fun `the query is held back for the full debounce before Apple is asked`() = runTest {
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { repository.search(any()) }

        advanceTimeBy(2)
        runCurrent()
        coVerify(exactly = 1) { repository.search("podlodka") }
    }

    @Test
    fun `a fast-typed query costs exactly one request`() = runTest {
        subscribe()

        val typed = "podl"
        typed.indices.forEach { index ->
            viewModel.onQueryChange(typed.substring(0, index + 1))
            advanceTimeBy(DEBOUNCE_MS / 4)
        }
        advanceTimeBy(DEBOUNCE_MS)
        runCurrent()

        coVerify(exactly = 1) { repository.search(any()) }
        coVerify(exactly = 1) { repository.search(typed) }
    }

    @Test
    fun `clearing the field drops the results without another request`() = runTest {
        coEvery { repository.search("podlodka") } returns Result.success(listOf(searchResult(1L)))
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, viewModel.uiState.value.results.size)

        viewModel.onQueryChange("")
        runCurrent()

        assertTrue(viewModel.uiState.value.results.isEmpty())
        coVerify(exactly = 1) { repository.search(any()) }
    }

    @Test
    fun `an apple link is offered as an add rather than searched for`() = runTest {
        subscribe()

        viewModel.onQueryChange(APPLE_LINK)
        advanceTimeBy(DEBOUNCE_MS * 2)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isLink)
        assertFalse(state.isYouTubeLink)
        coVerify(exactly = 0) { repository.search(any()) }
    }

    @Test
    fun `a youtube playlist link is labelled as one`() = runTest {
        subscribe()

        viewModel.onQueryChange("https://www.youtube.com/playlist?list=$PLAYLIST_ID")
        advanceTimeBy(DEBOUNCE_MS * 2)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isLink)
        assertTrue(state.isYouTubeLink)
        coVerify(exactly = 0) { repository.search(any()) }
    }

    @Test
    fun `a search failure becomes a message instead of a crash`() = runTest {
        coEvery { repository.search(any()) } returns Result.failure(UnknownHostException("dns"))
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(SearchError.NoConnection, state.searchError)
        assertFalse(state.isSearching)
    }

    @Test
    fun `only one add runs at a time`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.addFromSearchResult(any()) } coAnswers {
            release.await()
            AddPodcastResult.Added(testPodcast(), episodeCount = 3)
        }
        subscribe()

        viewModel.addSearchResult(searchResult(1L))
        runCurrent()
        assertEquals("1", viewModel.uiState.value.addingId)

        viewModel.addSearchResult(searchResult(2L))
        runCurrent()

        release.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { repository.addFromSearchResult(any()) }
        assertNull(viewModel.uiState.value.addingId)
    }

    @Test
    fun `a successful add clears the field so the next paste starts clean`() = runTest {
        coEvery { repository.addFromInput(any()) } returns
            AddPodcastResult.Added(testPodcast(), episodeCount = 3)
        subscribe()

        viewModel.onQueryChange("https://example.com/feed.xml")
        runCurrent()
        viewModel.addPastedLink()
        runCurrent()

        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.message is AddPodcastResult.Added)

        viewModel.onMessageShown()
        runCurrent()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a rejected add leaves the field alone so it can be corrected`() = runTest {
        coEvery { repository.addFromInput(any()) } returns AddPodcastResult.InvalidInput
        subscribe()

        viewModel.onQueryChange("not a link")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        viewModel.addPastedLink()
        runCurrent()

        assertEquals("not a link", viewModel.uiState.value.query)
        assertEquals(AddPodcastResult.InvalidInput, viewModel.uiState.value.message)
    }

    @Test
    fun `a result whose apple id is in the library is reported as already added`() = runTest {
        coEvery { repository.search("podlodka") } returns Result.success(listOf(searchResult(1L)))
        // Stored under a different feed URL than Apple's copy — a redirect, in practice — so the
        // Apple id is the only thing the two have in common.
        library.value = libraryOf(
            testPodcast(id = "stored-1", itunesId = 1L, feedUrl = "https://redirected.example/1"),
        )
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(mapOf(1L to "stored-1"), viewModel.uiState.value.addedPodcastIds)
    }

    @Test
    fun `a result matching a stored feed url is reported as already added`() = runTest {
        val result = searchResult(2L)
        coEvery { repository.search("podlodka") } returns Result.success(listOf(result))
        // No Apple id at all — how a show added from a pasted RSS link is stored. The feed URL, and
        // therefore the derived id, is the only thing left to match on.
        val feedUrl = requireNotNull(result.feedUrl)
        library.value = libraryOf(
            testPodcast(id = podcastIdOf(feedUrl), itunesId = null, feedUrl = feedUrl),
        )
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(
            mapOf(2L to podcastIdOf(feedUrl)),
            viewModel.uiState.value.addedPodcastIds,
        )
    }

    @Test
    fun `a result nobody has added is not reported as added`() = runTest {
        coEvery { repository.search("podlodka") } returns Result.success(listOf(searchResult(1L)))
        library.value = libraryOf(testPodcast(id = "something-else", itunesId = 99L))
        subscribe()

        viewModel.onQueryChange("podlodka")
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()

        assertTrue(viewModel.uiState.value.addedPodcastIds.isEmpty())
    }

    /**
     * The point of the change: adding from a list of candidates leaves the user in that list. The
     * row's own tick is the confirmation, and a second show is usually one tap away.
     */
    @Test
    fun `adding from a result does not ask the screen to navigate`() = runTest {
        coEvery { repository.addFromSearchResult(any()) } returns
            AddPodcastResult.Added(testPodcast(id = "added-1"), episodeCount = 3)
        subscribe()

        viewModel.addSearchResult(searchResult(1L))
        runCurrent()

        assertNull(viewModel.uiState.value.navigateToPodcastId)
        assertTrue(viewModel.uiState.value.message is AddPodcastResult.Added)
    }

    @Test
    fun `adding a pasted link does ask the screen to navigate`() = runTest {
        coEvery { repository.addFromInput(any()) } returns
            AddPodcastResult.Added(testPodcast(id = "added-1"), episodeCount = 3)
        subscribe()

        viewModel.onQueryChange(APPLE_LINK)
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        viewModel.addPastedLink()
        runCurrent()

        assertEquals("added-1", viewModel.uiState.value.navigateToPodcastId)

        viewModel.onNavigationHandled()
        runCurrent()
        assertNull(viewModel.uiState.value.navigateToPodcastId)
    }

    @Test
    fun `a pasted link for a show already held navigates to it rather than complaining`() = runTest {
        coEvery { repository.addFromInput(any()) } returns
            AddPodcastResult.AlreadyInLibrary(testPodcast(id = "held-1"))
        subscribe()

        viewModel.onQueryChange(APPLE_LINK)
        advanceTimeBy(DEBOUNCE_MS + 1)
        runCurrent()
        viewModel.addPastedLink()
        runCurrent()

        assertEquals("held-1", viewModel.uiState.value.navigateToPodcastId)
    }

    private companion object {

        /** Mirrors `SearchViewModel.DEBOUNCE_MS`, which is private to the view model. */
        const val DEBOUNCE_MS = 400L

        /** A real playlist id shape; see `PodcastLinkParserTest`. */
        const val PLAYLIST_ID = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"

        const val APPLE_LINK =
            "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744"
    }

    @Test
    fun `a link shared from another app arrives in the field, offered rather than added`() {
        // The route argument, as a share or a tapped link delivers it.
        val link = "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744"
        viewModel = SearchViewModel(repository, episodePlayer, SavedStateHandle(mapOf("link" to link)))

        runTest {
            subscribe()

            val state = viewModel.uiState.value
            assertEquals(link, state.query)
            // Classified exactly as a paste would be: the screen offers "add this link".
            assertTrue(state.isLink)
            // And nothing has been added. An intent any app can send may fill the field; the tap
            // that acts on it is still the user's.
            coVerify(exactly = 0) { repository.addFromInput(any()) }
        }
    }

    @Test
    fun `no shared link leaves the field empty`() {
        viewModel = SearchViewModel(repository, episodePlayer, SavedStateHandle())

        runTest {
            subscribe()

            assertEquals("", viewModel.uiState.value.query)
            assertFalse(viewModel.uiState.value.isLink)
        }
    }
}
