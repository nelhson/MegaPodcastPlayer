package md.borisveriga.megapodcastplayer.wear.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

/**
 * Tests that the watch's buttons become the right commands, and that failures are surfaced.
 *
 * The opt-in is for the test scheduler's clock. The volume throttle is the one thing here made of
 * time rather than of calls, and the only honest way to assert on it is to move a virtual clock
 * past the interval — a real wait would be a slow test that still proved nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private companion object {
        /**
         * Long enough for the volume throttle to have let a level through.
         *
         * The interval itself is the view model's business; this only has to be past it, and
         * short of the hold that clears the pending level afterwards.
         */
        const val SETTLED_MS = 300L

        /** Past the stillness after which the volume row hands the bezel back to the list. */
        const val VOLUME_RELEASED_MS = 5_000L

        /** An arrival time after any send these tests make, whatever the clock under them reads. */
        const val LATER_MS = 1_000_000_000L
    }

    @Before
    fun setUp() {
        every { client.phoneLink } returns flowOf(PhoneLink.CONNECTED)
        // Emits null rather than nothing: the screen state is a combine, and a flow that never
        // emits would leave it pinned to its initial value forever.
        every { client.snapshots } returns flowOf<ReceivedSnapshot?>(null)
        coEvery { client.send(any()) } returns true
        // Seen already, so the scrub tests are not also asserting on a hint they are not about.
        coEvery { hints.hasSeenScrubHint() } returns true
        coEvery { hints.hasSeenVolumeHint() } returns true
    }

    /**
     * How many times the view model has read the clock.
     *
     * The clock is read once per frame the view model builds, so this is the count of its
     * wake-ups — the one thing the clock-gating tests below can observe, since a paused frame is
     * identical to the last and the state flow drops it whether or not it was built.
     */
    private var clockReads = 0

    /**
     * Builds the view model under test with its sources stubbed, on the test scheduler's clock.
     *
     * The clock follows virtual time, so a hold measured in milliseconds expires when the test
     * advances past it, and a snapshot stamped `0L` has arrived at the moment the test began.
     */
    private fun TestScope.viewModel() = WatchPlayerViewModel(client, hints) {
        clockReads++
        testScheduler.currentTime
    }

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

    // ---- Volume ---------------------------------------------------------------------------------

    /** The same episode, on a phone that reported a volume scale to move along. */
    private val playingWithVolume = playing.copy(volume = 9, maxVolume = 15)

    /**
     * A bezel delivers a turn as a burst, and each step is absolute, so only the last one carries
     * any information. Sending them all would put a Bluetooth write behind every detent for a
     * result nobody could tell apart from this.
     */
    @Test
    fun `a burst of volume steps sends only the level the wearer settled on`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()

            viewModel.beginVolume()
            viewModel.adjustVolumeBy(1)
            viewModel.adjustVolumeBy(1)
            viewModel.adjustVolumeBy(1)
            advanceTimeBy(SETTLED_MS)
            runCurrent()

            coVerify(exactly = 1) { client.send(WearCommand.SetVolume(12)) }
            coVerify(exactly = 0) { client.send(WearCommand.SetVolume(10)) }
            coVerify(exactly = 0) { client.send(WearCommand.SetVolume(11)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Read from the settled state rather than from the next emission: taking hold of the bar and
     * turning it are two changes, and which of them the collector sees first is not the claim.
     */
    @Test
    fun `the level the bezel reaches is shown before the phone confirms it`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.beginVolume()
        viewModel.adjustVolumeBy(-2)
        runCurrent()

        assertEquals(7, viewModel.uiState.value.volumeLevel)
    }

    /**
     * The snap-back, end to end. The other tests here hand the view model one snapshot and stop,
     * which is how this got through: the phone publishes for reasons of its own, and what it
     * publishes between a send and its answer still carries the level the wearer turned away from.
     */
    @Test
    fun `a stale snapshot after a send does not take the bar back`() = runTest {
        val snapshots = MutableStateFlow<ReceivedSnapshot?>(ReceivedSnapshot(playingWithVolume, 0L))
        every { client.snapshots } returns snapshots
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.setVolume(12)
        advanceTimeBy(SETTLED_MS)
        runCurrent()
        // Far in the future rather than "a little later", so that it counts as arriving after the
        // send whatever the clock under this test reads.
        snapshots.value = ReceivedSnapshot(playingWithVolume, LATER_MS)
        runCurrent()

        assertEquals(12, viewModel.uiState.value.volumeLevel)

        // And the answer, when it comes, is believed: the phone went one further on its own keys.
        snapshots.value = ReceivedSnapshot(playingWithVolume.copy(volume = 12), LATER_MS + 1L)
        runCurrent()
        snapshots.value = ReceivedSnapshot(playingWithVolume.copy(volume = 13), LATER_MS + 2L)
        advanceTimeBy(VOLUME_RELEASED_MS)
        runCurrent()

        assertEquals(13, viewModel.uiState.value.volumeLevel)
    }

    @Test
    fun `the volume is clamped to the phone's own scale`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()

            viewModel.beginVolume()
            viewModel.adjustVolumeBy(100)
            advanceTimeBy(SETTLED_MS)
            runCurrent()
            viewModel.adjustVolumeBy(-100)
            advanceTimeBy(SETTLED_MS)
            runCurrent()

            coVerify(exactly = 1) { client.send(WearCommand.SetVolume(15)) }
            coVerify(exactly = 1) { client.send(WearCommand.SetVolume(0)) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The bezel has one owner, so entering either mode has to leave the other. */
    @Test
    fun `taking the bezel for the volume ends a scrub, and the other way round`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.beginScrub()
        runCurrent()
        assertTrue(viewModel.uiState.value.isScrubbing)

        viewModel.beginVolume()
        runCurrent()
        assertTrue(viewModel.uiState.value.isAdjustingVolume)
        assertFalse(viewModel.uiState.value.isScrubbing)

        viewModel.beginScrub()
        runCurrent()
        assertTrue(viewModel.uiState.value.isScrubbing)
        assertFalse(viewModel.uiState.value.isAdjustingVolume)
    }

    /** A mode nobody leaves is a bezel that stopped scrolling, and there is no commit to leave by. */
    @Test
    fun `the bezel goes back to the list after a while of stillness`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()

            viewModel.beginVolume()
            assertTrue(awaitItem().isAdjustingVolume)

            advanceTimeBy(VOLUME_RELEASED_MS)
            runCurrent()

            assertFalse(viewModel.uiState.value.isAdjustingVolume)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A bar taken hold of and let go without a turn must not keep showing the level it was seeded
     * with, or a volume changed on the phone afterwards would never reach the watch again.
     */
    @Test
    fun `letting go without turning hands the reading back to the phone`() = runTest {
        val snapshots = MutableStateFlow<ReceivedSnapshot?>(
            ReceivedSnapshot(playingWithVolume, 0L),
        )
        every { client.snapshots } returns snapshots
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.beginVolume()
        viewModel.endVolume()
        snapshots.value = ReceivedSnapshot(playingWithVolume.copy(volume = 3), 0L)
        runCurrent()

        assertEquals(3, viewModel.uiState.value.volumeLevel)
        coVerify(exactly = 0) { client.send(ofType<WearCommand.SetVolume>()) }
    }

    @Test
    fun `a phone with no volume scale gives its bar nothing to take hold of`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertFalse(awaitItem().canSetVolume)

            viewModel.beginVolume()
            viewModel.adjustVolumeBy(1)
            advanceTimeBy(SETTLED_MS)
            runCurrent()

            coVerify(exactly = 0) { client.send(ofType<WearCommand.SetVolume>()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a volume that could not be delivered says so`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        coEvery { client.send(ofType<WearCommand.SetVolume>()) } returns false
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()

            viewModel.beginVolume()
            viewModel.adjustVolumeBy(1)
            advanceTimeBy(SETTLED_MS)
            runCurrent()

            assertTrue(viewModel.uiState.value.lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the first time the volume bar is held it says what the bezel does`() = runTest {
        coEvery { hints.hasSeenVolumeHint() } returns false
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingWithVolume, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.beginVolume()
        runCurrent()

        assertTrue(viewModel.uiState.value.showsVolumeHint)
        coVerify(exactly = 1) { hints.markVolumeHintSeen() }
    }

    /**
     * Keeps the screen state hot, so that it can be read directly rather than awaited.
     *
     * [WatchPlayerViewModel.uiState] shares while subscribed, and a test that only calls methods
     * on the view model is not a subscriber — its value would sit at the initial one forever.
     */
    private fun TestScope.keepStateLive(viewModel: WatchPlayerViewModel) {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        runCurrent()
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

    // ---- Play and pause -------------------------------------------------------------------------

    /** The same episode, playing, for the tests that press pause. */
    private val playingAloud = playing.copy(isPlaying = true)

    @Test
    fun `pressing pause flips the button before the phone answers`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingAloud, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)
        assertTrue(viewModel.uiState.value.snapshot.isPlaying)

        viewModel.togglePlayPause()
        runCurrent()

        assertFalse(viewModel.uiState.value.snapshot.isPlaying)
        coVerify(exactly = 1) { client.send(WearCommand.TogglePlayPause) }
    }

    /** A tap that never reached the phone changed nothing there, so the button must not say it did. */
    @Test
    fun `a toggle that could not be delivered puts the button back`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingAloud, 0L))
        coEvery { client.send(WearCommand.TogglePlayPause) } returns false
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.togglePlayPause()
        runCurrent()

        assertTrue(viewModel.uiState.value.snapshot.isPlaying)
        assertTrue(viewModel.uiState.value.lastCommandFailed)
    }

    /**
     * A publish for another reason still says playing and must not flip the button back; and once
     * the hold is over, the phone's later word is believed again.
     *
     * What this does not claim is that the answer in the middle *releases* the hold. A snapshot
     * that agrees with the tap draws the same frame whether it is counted as the answer or not,
     * so no assertion here could tell; the hold's bound is what the last step proves.
     */
    @Test
    fun `a stale snapshot does not flip the button back, and the hold lets go`() = runTest {
        val snapshots = MutableStateFlow<ReceivedSnapshot?>(ReceivedSnapshot(playingAloud, 0L))
        every { client.snapshots } returns snapshots
        val viewModel = viewModel()
        keepStateLive(viewModel)

        viewModel.togglePlayPause()
        runCurrent()
        snapshots.value = ReceivedSnapshot(playingAloud.copy(volume = 3), LATER_MS)
        runCurrent()
        assertFalse(viewModel.uiState.value.snapshot.isPlaying)

        snapshots.value = ReceivedSnapshot(playing, LATER_MS + 1L)
        runCurrent()
        assertFalse(viewModel.uiState.value.snapshot.isPlaying)

        advanceTimeBy(PLAY_HOLD_MS + 1L)
        runCurrent()
        snapshots.value = ReceivedSnapshot(playingAloud, LATER_MS + 2L)
        runCurrent()
        assertTrue(viewModel.uiState.value.snapshot.isPlaying)
    }

    // ---- The clock ------------------------------------------------------------------------------

    /**
     * The position clock used to tick every second regardless, which on a watch was a wake-up per
     * second to build a frame a paused snapshot made identical to the last. The frame is not
     * observable — the state flow drops it — but the clock read that builds it is.
     */
    @Test
    fun `a paused phone does not wake the watch every second`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)
        val settled = clockReads

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(settled, clockReads)
    }

    @Test
    fun `a playing phone reads the clock once a second and moves the bar`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playingAloud, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.position.collect {} }
        runCurrent()
        val settled = clockReads
        assertEquals(30_000L, viewModel.position.value.positionMs)

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(settled + 5, clockReads)
        assertEquals(35_000L, viewModel.position.value.positionMs)
    }

    /** The bar starts moving from the press, while the phone is still starting up. */
    @Test
    fun `a play just asked for starts the clock while the phone still says paused`() = runTest {
        every { client.snapshots } returns flowOf(ReceivedSnapshot(playing, 0L))
        val viewModel = viewModel()
        keepStateLive(viewModel)
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.position.collect {} }
        runCurrent()

        viewModel.togglePlayPause()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(viewModel.uiState.value.snapshot.isPlaying)
        assertEquals(32_000L, viewModel.position.value.positionMs)
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
}
