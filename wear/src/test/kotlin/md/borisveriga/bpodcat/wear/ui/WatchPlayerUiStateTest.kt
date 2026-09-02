package md.borisveriga.bpodcat.wear.ui

import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.QueuedEpisode
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.ReceivedSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the watch's screen state, and in particular for the position it works out itself
 * between the phone's publishes.
 */
class WatchPlayerUiStateTest {

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "Episode one",
        isPlaying = true,
        positionMs = 30_000L,
        durationMs = 300_000L,
        speed = 1f,
    )

    @Test
    fun `the position advances between publishes`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received, nowElapsedMs = 11_000L)

        assertEquals(40_000L, uiState.positionMs)
    }

    @Test
    fun `progress follows the advancing position`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received, nowElapsedMs = 121_000L)

        assertEquals(0.5f, uiState.progress, 0.001f)
    }

    @Test
    fun `a paused phone does not drift`() {
        val received = ReceivedSnapshot(playing.copy(isPlaying = false), receivedAtElapsedMs = 0L)

        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received, nowElapsedMs = 600_000L)

        assertEquals(30_000L, uiState.positionMs)
    }

    @Test
    fun `nothing received yet reads as idle rather than crashing`() {
        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received = null, nowElapsedMs = 5_000L)

        assertTrue(uiState.snapshot.isIdle)
        assertEquals(0L, uiState.positionMs)
        assertFalse(uiState.showsControls)
        assertTrue(uiState.showsEmptyQueue)
    }

    @Test
    fun `controls are hidden while the phone is unreachable, however fresh the snapshot`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerUiState(PhoneLink.DISCONNECTED, received, nowElapsedMs = 0L)

        assertFalse(uiState.showsControls)
    }

    @Test
    fun `controls appear once the phone is reachable and has something loaded`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received, nowElapsedMs = 0L)

        assertTrue(uiState.showsControls)
        assertFalse(uiState.showsEmptyQueue)
    }

    @Test
    fun `an idle phone with a queue is not the empty state`() {
        val idleWithQueue = NowPlayingSnapshot(
            upNext = listOf(QueuedEpisode(id = "ep-2", title = "Two", showTitle = "Show")),
        )
        val received = ReceivedSnapshot(idleWithQueue, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerUiState(PhoneLink.CONNECTED, received, nowElapsedMs = 0L)

        assertFalse(uiState.showsControls)
        assertFalse(uiState.showsEmptyQueue)
    }

    @Test
    fun `a scrub in progress overrides the extrapolated position`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val uiState = watchPlayerUiState(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 11_000L,
            scrub = ScrubState(positionMs = 200_000L),
        )

        assertEquals(200_000L, uiState.positionMs)
        assertEquals(200_000f / 300_000f, uiState.progress, 0.001f)
        assertTrue(uiState.isScrubbing)
    }

    @Test
    fun `a committed seek holds the position across the round trip`() {
        // The phone's last word still describes the old position; extrapolating it would walk the
        // bar back to where the user just dragged it away from.
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val uiState = watchPlayerUiState(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 3_000L,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        assertEquals(200_000L, uiState.positionMs)
        // Held, but no longer being dragged: the user has let go.
        assertFalse(uiState.isScrubbing)
    }

    @Test
    fun `the hold releases once the phone confirms`() {
        val confirmation = ReceivedSnapshot(
            playing.copy(positionMs = 200_000L),
            receivedAtElapsedMs = 2_500L,
        )

        val uiState = watchPlayerUiState(
            link = PhoneLink.CONNECTED,
            received = confirmation,
            nowElapsedMs = 3_500L,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        // Back to extrapolating, from the confirmed snapshot rather than the held value.
        assertEquals(201_000L, uiState.positionMs)
    }

    @Test
    fun `the hold releases on its own if the phone never confirms`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val uiState = watchPlayerUiState(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 2_000L + SEEK_HOLD_MS,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        // A phone that went silent must not freeze the bar where the user left it forever.
        assertEquals(playing.positionAfter(1_000L + SEEK_HOLD_MS), uiState.positionMs)
    }

    @Test
    fun `a failed command is carried into the state`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerUiState(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 0L,
            lastCommandFailed = true,
        )

        assertTrue(uiState.lastCommandFailed)
    }
}
