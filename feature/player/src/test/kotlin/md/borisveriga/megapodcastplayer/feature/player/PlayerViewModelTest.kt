package md.borisveriga.megapodcastplayer.feature.player

import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
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
    private val lastPlayedEpisodeId = MutableStateFlow<String?>(null)

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
        every { playbackRepository.observeLastPlayedEpisodeId() } returns lastPlayedEpisodeId

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
    fun `up next hides the loaded episode before the player has said what it is`() = runTest {
        // A cold start, or the service having been killed: binding a controller takes long enough
        // that the queue is on screen first, and until it lands the player reports no episode at
        // all. The durable queue mirrors the player's timeline, so it holds that episode — and
        // without the stored fallback it would be drawn as a queued row in an empty queue.
        playbackState.value = PlaybackState(isConnected = false, episodeId = null)
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("b"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `the loaded episode is the only queue entry, so up next is empty`() = runTest {
        // The shape the bug was reported in: one episode played straight from a show, nothing
        // queued behind it, and a queue screen showing a row the user never added.
        playbackState.value = PlaybackState(isConnected = false, episodeId = null)
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"))

        viewModel.uiState.test {
            assertEquals(emptyList<String>(), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `the player's own episode wins over the stored one`() = runTest {
        // The stored id is only a fallback: it lags a transition by a write, and following it once
        // the player has answered would hide the wrong row.
        playbackState.value = PlaybackState(episodeId = "b")
        lastPlayedEpisodeId.value = "a"
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            assertEquals(listOf("c"), awaitItem().upNext.map { it.episode.id })
        }
    }

    @Test
    fun `a queue built without playing anything is entirely up next`() = runTest {
        // Nothing loaded and nothing played recently that is still queued: every row is waiting.
        lastPlayedEpisodeId.value = "played-and-gone"
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            assertEquals(listOf("a", "b"), awaitItem().upNext.map { it.episode.id })
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

    @Test
    fun `a reorder is translated from list positions to player indices`() = runTest {
        // The screen lists what comes after "b", so its index 0 is "c" and its index 1 is "d" —
        // while the player's queue still holds "a" and "b" in front of them. Passing the list
        // indices straight through would move "a" onto "b" and leave the queue in an order the
        // user never asked for.
        playbackState.value = PlaybackState(
            episodeId = "b",
            queueEpisodeIds = listOf("a", "b", "c", "d"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"), playable("d"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 0)

            coVerify { episodePlayer.moveInQueue(3, 2, listOf("a", "b", "d", "c")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder is refused when the player queue disagrees with the stored one`() = runTest {
        // The player has moved on and no longer holds "d"; reordering by position would move
        // whatever now sits at that index. There is no safe interpretation, so nothing happens.
        playbackState.value = PlaybackState(
            episodeId = "b",
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"), playable("d"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 0)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a reorder off the end of the list does nothing`() = runTest {
        playbackState.value = PlaybackState(
            episodeId = "b",
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 1,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 0, toIndex = 5)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dropping an episode back where it started does nothing`() = runTest {
        playbackState.value = PlaybackState(
            episodeId = "a",
            queueEpisodeIds = listOf("a", "b", "c"),
            queueIndex = 0,
        )
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.moveInUpNext(fromIndex = 1, toIndex = 1)

            coVerify(exactly = 0) { episodePlayer.moveInQueue(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a removal is offered back, and the undo puts the episode where it was`() = runTest {
        queue.value = listOf(playable("a"), playable("b"), playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeFromQueue("b")

            coVerify { episodePlayer.removeFromQueue("b") }
            assertEquals(QueueMessage.Removed("Episode b"), expectMostRecentItem().message)

            // The queue as it stood *before* the removal, which is the only description of where
            // "b" belongs that survives it. Restoring it by appending would put it after "c".
            viewModel.undoQueueChange()
            coVerify { episodePlayer.restoreToQueue("b", listOf("a", "b", "c")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `marking a queued episode played drops it, and the undo restores its position`() = runTest {
        val partlyHeard = playable("b").let {
            it.copy(episode = it.episode.copy(positionMs = 42_000L))
        }
        queue.value = listOf(playable("a"), partlyHeard, playable("c"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.markQueuedPlayed("b")

            coVerify { playbackRepository.setPlayed("b", true) }
            // A finished episode has no business sitting in "up next"; leaving it there would make
            // the gesture need a second one every time.
            coVerify { episodePlayer.removeFromQueue("b") }
            assertEquals(QueueMessage.MarkedPlayed("Episode b"), expectMostRecentItem().message)

            viewModel.undoQueueChange()

            // Both halves, and the position with them: an undo that put the flag back but left the
            // user at zero would cost them the 42 seconds they were trying to save.
            coVerify {
                playbackRepository.setPlayed(
                    episodeId = "b",
                    isPlayed = false,
                    positionMs = 42_000L,
                )
            }
            coVerify { episodePlayer.restoreToQueue("b", listOf("a", "b", "c")) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the undo is spent once, and does not survive its snackbar`() = runTest {
        queue.value = listOf(playable("a"), playable("b"))

        viewModel.uiState.test {
            awaitItem()

            viewModel.removeFromQueue("b")
            viewModel.undoQueueChange()
            // A second tap on a snackbar that has already been acted on.
            viewModel.undoQueueChange()

            coVerify(exactly = 1) { episodePlayer.restoreToQueue(any(), any()) }

            viewModel.removeFromQueue("a")
            // The snackbar timed out rather than being tapped. An undo still armed here would fire
            // against whichever message came next.
            viewModel.onQueueMessageShown()
            viewModel.undoQueueChange()

            coVerify(exactly = 1) { episodePlayer.restoreToQueue(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing an episode the queue does not hold reports nothing`() = runTest {
        queue.value = listOf(playable("a"))

        viewModel.uiState.test {
            awaitItem()

            // The gesture raced the player finishing the episode.
            viewModel.removeFromQueue("gone")

            coVerify(exactly = 0) { episodePlayer.removeFromQueue(any()) }
            // Nothing changed, so nothing is emitted and there is no snackbar to dismiss.
            expectNoEvents()
            assertEquals(null, viewModel.uiState.value.message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
