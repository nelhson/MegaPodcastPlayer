package md.borisveriga.megapodcastplayer.feature.listen

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [ListenViewModel].
 *
 * Two of the three shelves are straight reads and the third is not: "up next" has to be the queue
 * *after* the episode playing, and getting that wrong shows the user the thing they are currently
 * listening to as the next thing to listen to. That, and the difference between "still loading" and
 * "there is nothing", are what this pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val inProgress = MutableStateFlow(emptyList<EpisodeWithShow>())
    private val newEpisodes = MutableStateFlow(emptyList<EpisodeWithShow>())
    private val queue = MutableStateFlow(emptyList<PlayableEpisode>())
    private val playbackState = MutableStateFlow(PlaybackState())

    private lateinit var podcastRepository: PodcastRepository
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var connection: PlaybackConnection
    private lateinit var viewModel: ListenViewModel

    private fun episode(id: String) = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.parse("2026-09-01T06:00:00Z"),
        sizeBytes = null,
    )

    private fun withShow(id: String) = EpisodeWithShow(
        episode = episode(id),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private fun playable(id: String) = PlayableEpisode(
        episode = episode(id),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        podcastRepository = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)
        connection = mockk(relaxed = true)

        every { podcastRepository.observeInProgressEpisodes(any()) } returns inProgress
        every { podcastRepository.observeNewEpisodes(any()) } returns newEpisodes
        every { playbackRepository.observeQueue() } returns queue
        every { connection.playbackState } returns playbackState
        coEvery { episodePlayer.play(any()) } returns true

        viewModel = ListenViewModel(
            podcastRepository = podcastRepository,
            playbackRepository = playbackRepository,
            episodePlayer = episodePlayer,
            connection = connection,
        )
    }

    @Test
    fun `the shelves are empty and loading before the database answers`() {
        assertTrue(viewModel.uiState.value.isLoading)
        // Loading is not emptiness: an empty state shown for the frame before the data arrives is
        // the app telling the user it has nothing, and then contradicting itself.
        assertFalse(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `a library with nothing waiting says so`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()

            assertFalse(state.isLoading)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `up next is the queue after the episode playing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            queue.value = listOf(playable("a"), playable("b"), playable("c"))
            playbackState.value = PlaybackState(episodeId = "a")

            // Not "a": the durable queue includes the episode playing, and showing it as the next
            // thing to listen to is the bug this exists to avoid.
            assertEquals(listOf("b", "c"), expectMostRecentItem().upNext.map { it.episode.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `before the player has bound, the whole queue is still ahead`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            queue.value = listOf(playable("a"), playable("b"))

            assertEquals(listOf("a", "b"), expectMostRecentItem().upNext.map { it.episode.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the two derived shelves are what the repository says`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            inProgress.value = listOf(withShow("half"))
            newEpisodes.value = listOf(withShow("fresh"))

            val state = expectMostRecentItem()
            assertEquals(listOf("half"), state.continueListening.map { it.episode.id })
            assertEquals(listOf("fresh"), state.newEpisodes.map { it.episode.id })
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a card's play button starts an episode that is not playing`() = runTest {
        var opened = false

        viewModel.uiState.test {
            awaitItem()

            viewModel.togglePlay("a") { opened = true }

            coVerify { episodePlayer.play("a") }
            assertTrue(opened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the same button pauses the episode that is already playing`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            playbackState.value = PlaybackState(episodeId = "a", isPlaying = true)
            expectMostRecentItem()

            var opened = false
            viewModel.togglePlay("a") { opened = true }

            coVerify { connection.togglePlayPause() }
            coVerify(exactly = 0) { episodePlayer.play(any()) }
            // And the player is not re-opened: it is already showing this episode.
            assertFalse(opened)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an episode can be found from whichever shelf it was tapped on`() = runTest {
        viewModel.uiState.test {
            awaitItem()
            inProgress.value = listOf(withShow("half"))
            queue.value = listOf(playable("queued"))
            val state = expectMostRecentItem()

            assertEquals("half", state.episodeById("half")?.episode?.id)
            assertEquals("queued", state.episodeById("queued")?.episode?.id)
            assertEquals(null, state.episodeById("gone"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening and closing the sheet is one piece of state`() {
        viewModel.openEpisode("a")
        assertEquals("a", viewModel.openEpisodeId.value)

        viewModel.closeEpisode()
        assertEquals(null, viewModel.openEpisodeId.value)
    }
}
