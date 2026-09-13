package md.borisveriga.megapodcastplayer.wear.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.PhonePlayerClient
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import md.borisveriga.megapodcastplayer.wear.data.WatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests that the watch's buttons become the right commands, and that failures are surfaced. */
class WatchPlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = mockk<PhonePlayerClient>(relaxed = true)
    private val hints = mockk<WatchHints>(relaxed = true)

    /** Paused, so the scrub tests are not racing the position ticker while they assert on it. */
    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "Episode one",
        isPlaying = false,
        positionMs = 30_000L,
        durationMs = 300_000L,
        speed = 1f,
    )

    @Before
    fun setUp() {
        every { client.phoneLink } returns flowOf(PhoneLink.CONNECTED)
        // Emits null rather than nothing: the screen state is a combine, and a flow that never
        // emits would leave it pinned to its initial value forever.
        every { client.snapshots } returns flowOf<ReceivedSnapshot?>(null)
        coEvery { client.send(any()) } returns true
        // Seen already, so the scrub tests are not also asserting on a hint they are not about.
        coEvery { hints.hasSeenScrubHint() } returns true
    }

    /** Builds the view model under test with its sources stubbed. */
    private fun viewModel() = WatchPlayerViewModel(client, hints)

    @Test
    fun `opening the app asks the phone to republish its state`() = runTest {
        viewModel()

        coVerify(exactly = 1) { client.send(WearCommand.RequestState) }
    }

    @Test
    fun `each control sends its own command`() = runTest {
        val viewModel = viewModel()

        viewModel.togglePlayPause()
        viewModel.skipForward()
        viewModel.skipBack()
        viewModel.skipToNext()
        viewModel.skipToPrevious()
        viewModel.cycleSpeed()
        viewModel.seekTo(90_000L)
        viewModel.playOnPhone("ep-7")

        coVerify(exactly = 1) { client.send(WearCommand.TogglePlayPause) }
        coVerify(exactly = 1) { client.send(WearCommand.SkipForward) }
        coVerify(exactly = 1) { client.send(WearCommand.SkipBack) }
        coVerify(exactly = 1) { client.send(WearCommand.SkipToNext) }
        coVerify(exactly = 1) { client.send(WearCommand.SkipToPrevious) }
        coVerify(exactly = 1) { client.send(WearCommand.CycleSpeed) }
        coVerify(exactly = 1) { client.send(WearCommand.SeekTo(90_000L)) }
        coVerify(exactly = 1) { client.send(WearCommand.PlayEpisode("ep-7")) }
    }

    @Test
    fun `the phone's state reaches the screen`() = runTest {
        val snapshot = NowPlayingSnapshot(
            episodeId = "ep-1",
            title = "Episode one",
            showTitle = "The Show",
            isPlaying = true,
            positionMs = 30_000L,
            durationMs = 300_000L,
        )
        every { client.snapshots } returns flowOf(ReceivedSnapshot(snapshot, 0L))

        val viewModel = viewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Episode one", state.snapshot.title)
            assertEquals(PhoneLink.CONNECTED, state.link)
            assertTrue(state.showsControls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The screen is a list that collects [WatchPlayerViewModel.uiState] above all of its rows,
     * so anything that changes it rebuilds them — and the position changes by itself once a
     * second. It therefore travels separately, and a phone reporting the same episode further
     * along must reach the bar without waking the rows.
     *
     * Driven by a fresh snapshot rather than by the ticker: on the JVM the clock the ticker reads
     * stands still, so a ticking clock here would prove nothing.
     */
    @Test
    fun `a new reading of the position reaches the bar without re-emitting the screen state`() =
        runTest {
            val snapshots = MutableStateFlow<ReceivedSnapshot?>(ReceivedSnapshot(playing, 0L))
            every { client.snapshots } returns snapshots
            val viewModel = viewModel()
            // Kept subscribed, so the flow is live and its value can be read directly below.
            backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.position.collect {} }

            viewModel.uiState.test {
                awaitItem()
                assertEquals(30_000L, viewModel.position.value.positionMs)

                snapshots.value = ReceivedSnapshot(playing.copy(positionMs = 31_000L), 0L)

                assertEquals(31_000L, viewModel.position.value.positionMs)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `scrubbing sends one seek, on commit, and not before`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.beginScrub()
            viewModel.scrubBy(10_000L)
            viewModel.scrubBy(5_000L)
            // Dragging along an hour-long episode would otherwise put a seek on the link per frame.
            coVerify(exactly = 0) { client.send(ofType<WearCommand.SeekTo>()) }

            viewModel.commitScrub()

            coVerify(exactly = 1) { client.send(WearCommand.SeekTo(45_000L)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the scrub position is clamped to the episode`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.beginScrub()
            viewModel.scrubBy(-5_000_000L)
            viewModel.commitScrub()

            coVerify(exactly = 1) { client.send(WearCommand.SeekTo(0L)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the scrub position is clamped to the duration`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.beginScrub()
            viewModel.scrubBy(5_000_000L)
            viewModel.commitScrub()

            coVerify(exactly = 1) { client.send(WearCommand.SeekTo(300_000L)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an abandoned scrub seeks nowhere`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.beginScrub()
            viewModel.scrubBy(10_000L)
            viewModel.cancelScrub()
            viewModel.commitScrub()

            coVerify(exactly = 0) { client.send(ofType<WearCommand.SeekTo>()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an episode of unknown length cannot be scrubbed`() = runTest {
        val unknownLength = playing.copy(durationMs = 0L)
        every { client.snapshots } returns flowOf(ReceivedSnapshot(unknownLength, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertFalse(awaitItem().canScrub)

            viewModel.beginScrub()
            viewModel.scrubBy(10_000L)
            viewModel.commitScrub()

            // There is no scale to seek along, so the gesture must not invent one.
            coVerify(exactly = 0) { client.send(ofType<WearCommand.SeekTo>()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the first scrub is explained, and the explanation is remembered`() = runTest {
        coEvery { hints.hasSeenScrubHint() } returns false
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertFalse(awaitItem().showsScrubHint)

            viewModel.beginScrub()

            // Two updates, in this order: the bar is taken hold of, and then the sentence appears
            // under it once the disk has said it has never been shown.
            assertFalse(awaitItem().showsScrubHint)
            assertTrue(awaitItem().showsScrubHint)
            // Written as it is shown, not when it is dismissed: a wearer who taps into scrub mode
            // and straight back out has still read the sentence.
            coVerify(exactly = 1) { hints.markScrubHintSeen() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a scrub that has been explained before says nothing`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()

            viewModel.beginScrub()

            val state = awaitItem()
            assertTrue(state.isScrubbing)
            assertFalse(state.showsScrubHint)
            coVerify(exactly = 0) { hints.markScrubHintSeen() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `moving the bar takes the explanation away`() = runTest {
        coEvery { hints.hasSeenScrubHint() } returns false
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            viewModel.beginScrub()
            awaitItem()
            assertTrue(awaitItem().showsScrubHint)

            viewModel.scrubBy(10_000L)

            // The sentence sits where the times do; once the gesture is understood it is in the way.
            // One emission, not two: the bar moving is the position's business, not the screen's.
            assertFalse(awaitItem().showsScrubHint)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an undeliverable command is reported rather than swallowed`() = runTest {
        coEvery { client.send(any()) } returns false

        val viewModel = viewModel()
        viewModel.togglePlayPause()

        viewModel.uiState.test {
            assertTrue(awaitItem().lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a command that gets through clears an earlier failure`() = runTest {
        coEvery { client.send(any()) } returns false
        val viewModel = viewModel()
        viewModel.togglePlayPause()

        coEvery { client.send(any()) } returns true
        viewModel.togglePlayPause()

        viewModel.uiState.test {
            assertFalse(awaitItem().lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The command carries no position: the watch only ever has an extrapolation of a snapshot up
     * to a second old, and the phone's own playhead is the one that is true.
     */
    @Test
    fun `marking asks the phone to mark its own position`() = runTest {
        val viewModel = viewModel()

        viewModel.markMoment()

        coVerify(exactly = 1) { client.send(WearCommand.MarkMoment) }
    }

    @Test
    fun `a mark that reaches the phone is confirmed on the screen`() = runTest {
        val viewModel = viewModel()

        viewModel.markMoment()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.momentSaved)
            assertFalse(state.lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A phone that cannot be reached is not playing, so there is nothing to keep for later. */
    @Test
    fun `a mark that cannot reach the phone is reported as a failed command`() = runTest {
        coEvery { client.send(WearCommand.MarkMoment) } returns false
        val viewModel = viewModel()

        viewModel.markMoment()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.momentSaved)
            assertTrue(state.lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
