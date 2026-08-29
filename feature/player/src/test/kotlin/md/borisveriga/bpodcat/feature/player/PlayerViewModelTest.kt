package md.borisveriga.bpodcat.feature.player

import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [PlayerViewModel].
 *
 * The connection and the repositories are mocked: what this class contributes is the combining of
 * three sources and the translation of a button press into a command with the right argument, and
 * both are visible from the outside without a real player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playbackState = MutableStateFlow(PlaybackState())
    private val settings = MutableStateFlow(PlaybackSettings())
    private val queue = MutableStateFlow(emptyList<PlayableEpisode>())

    private lateinit var connection: PlaybackConnection
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var episodePlayer: EpisodePlayer
    private lateinit var viewModel: PlayerViewModel

    private fun playable(id: String, title: String = "Episode $id") = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = title,
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = null,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        connection = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        episodePlayer = mockk(relaxed = true)

        every { connection.playbackState } returns playbackState
        every { playbackRepository.observePlaybackSettings() } returns settings
        every { playbackRepository.observeQueue() } returns queue

        viewModel = PlayerViewModel(connection, playbackRepository, episodePlayer)
    }

    @Test
    fun `the persisted queue is restored as soon as the player is on screen`() = runTest {
        coVerify { episodePlayer.restoreQueue() }
    }

    @Test
    fun `the ui state combines playback, settings and the queue`() = runTest {
        playbackState.value = PlaybackState(episodeId = "a", title = "Episode a", isPlaying = true)
        settings.value = PlaybackSettings(speed = 1.5f)
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            val state = awaitItem()

            assertEquals("Episode a", state.playback.title)
            assertEquals(1.5f, state.settings.speed, 0.001f)
            assertEquals(listOf("a", "b"), state.queue.map { it.episode.id })
        }
    }

    @Test
    fun `up next excludes the episode that is playing`() = runTest {
        playbackState.value = PlaybackState(episodeId = "b")
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            assertEquals(listOf("c"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `up next is the whole queue when nothing in it is playing`() = runTest {
        // The user started an episode straight from a show, so the queue is untouched by it.
        playbackState.value = PlaybackState(episodeId = "elsewhere")
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("a", "b"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `skipping uses the user's configured intervals, not the defaults`() = runTest {
        settings.value = PlaybackSettings(skipForwardMs = 45_000L, skipBackMs = 15_000L)
        // The view model reads its own state, which only tracks the sources while collected.
        viewModel.uiState.test {
            awaitItem()

            viewModel.skipForward()
            viewModel.skipBack()

            coVerify { connection.skipForward(45_000L) }
            coVerify { connection.skipBack(15_000L) }
        }
    }

    @Test
    fun `cycling the speed both applies it and persists it`() = runTest {
        settings.value = PlaybackSettings(speed = 1f)

        viewModel.uiState.test {
            awaitItem()

            viewModel.cycleSpeed()

            // Persisted so the service picks it up again after being killed, and applied so the
            // change is audible now.
            coVerify { playbackRepository.setSpeed(1.2f) }
            coVerify { connection.setSpeed(1.2f) }
        }
    }

    @Test
    fun `marking the current episode played also takes it out of the queue`() = runTest {
        playbackState.value = PlaybackState(episodeId = "a")

        viewModel.uiState.test {
            awaitItem()

            viewModel.markCurrentPlayed()

            coVerify { playbackRepository.setPlayed("a", true) }
            coVerify { episodePlayer.removeFromQueue("a") }
        }
    }

    @Test
    fun `marking played does nothing when the player is idle`() = runTest {
        viewModel.markCurrentPlayed()

        coVerify(exactly = 0) { playbackRepository.setPlayed(any(), any()) }
    }

    @Test
    fun `play, pause and seek are forwarded to the connection`() = runTest {
        viewModel.togglePlayPause()
        viewModel.seekTo(42_000L)
        viewModel.skipToNext()
        viewModel.skipToPrevious()

        coVerify { connection.togglePlayPause() }
        coVerify { connection.seekTo(42_000L) }
        coVerify { connection.skipToNext() }
        coVerify { connection.skipToPrevious() }
    }

    @Test
    fun `tapping a queued episode plays it`() = runTest {
        viewModel.playQueued("c")

        coVerify { episodePlayer.play("c") }
    }
}
