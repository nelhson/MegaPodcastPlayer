package md.borisveriga.bpodcat.wear.ui

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.testing.MainDispatcherRule
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.PhonePlayerClient
import md.borisveriga.bpodcat.wear.data.ReceivedSnapshot
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

    @Before
    fun setUp() {
        every { client.phoneLink } returns flowOf(PhoneLink.CONNECTED)
        // Emits null rather than nothing: the screen state is a combine, and a flow that never
        // emits would leave it pinned to its initial value forever.
        every { client.snapshots } returns flowOf<ReceivedSnapshot?>(null)
        coEvery { client.send(any()) } returns true
    }

    @Test
    fun `opening the app asks the phone to republish its state`() = runTest {
        WatchPlayerViewModel(client)

        coVerify(exactly = 1) { client.send(WearCommand.RequestState) }
    }

    @Test
    fun `each control sends its own command`() = runTest {
        val viewModel = WatchPlayerViewModel(client)

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

        val viewModel = WatchPlayerViewModel(client)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Episode one", state.snapshot.title)
            assertEquals(PhoneLink.CONNECTED, state.link)
            assertTrue(state.showsControls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an undeliverable command is reported rather than swallowed`() = runTest {
        coEvery { client.send(any()) } returns false

        val viewModel = WatchPlayerViewModel(client)
        viewModel.togglePlayPause()

        viewModel.uiState.test {
            assertTrue(awaitItem().lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a command that gets through clears an earlier failure`() = runTest {
        coEvery { client.send(any()) } returns false
        val viewModel = WatchPlayerViewModel(client)
        viewModel.togglePlayPause()

        coEvery { client.send(any()) } returns true
        viewModel.togglePlayPause()

        viewModel.uiState.test {
            assertFalse(awaitItem().lastCommandFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
