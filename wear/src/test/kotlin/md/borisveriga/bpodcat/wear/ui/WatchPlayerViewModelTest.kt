package md.borisveriga.bpodcat.wear.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.testing.MainDispatcherRule
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.OfflineLibrary
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.PhonePlayerClient
import md.borisveriga.bpodcat.wear.data.PositionReporter
import md.borisveriga.bpodcat.wear.data.ReceivedSnapshot
import md.borisveriga.bpodcat.wear.data.StoredEpisode
import md.borisveriga.bpodcat.wear.data.TransferProgress
import md.borisveriga.bpodcat.wear.data.WatchEpisodeStore
import md.borisveriga.bpodcat.wear.data.WatchLibrary
import md.borisveriga.bpodcat.wear.playback.WatchPlayback
import md.borisveriga.bpodcat.wear.playback.WatchPlaybackState
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
    private val playback = mockk<WatchPlayback>(relaxed = true)
    private val store = mockk<WatchEpisodeStore>(relaxed = true)
    private val reporter = mockk<PositionReporter>(relaxed = true)
    private val library = mockk<WatchLibrary>(relaxed = true)

    /** What the watch's own player is doing; nothing, unless a test says otherwise. */
    private val localPlayback = MutableStateFlow<WatchPlaybackState?>(null)

    /** What the watch holds on disk. */
    private val stored = MutableStateFlow(emptyList<StoredEpisode>())

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
        // Same reasoning as the snapshots flow: a flow that never emits would stall the combine.
        every { playback.state } returns localPlayback
        every { store.episodes } returns stored
        every { store.transfers } returns MutableStateFlow(emptyMap<String, TransferProgress>())
        every { library.library } returns flowOf(OfflineLibrary())
    }

    /** Builds the view model under test with both of its sources stubbed. */
    private fun viewModel() = WatchPlayerViewModel(client, playback, store, reporter, library)

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
        viewModel.playQueued("ep-7")

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
