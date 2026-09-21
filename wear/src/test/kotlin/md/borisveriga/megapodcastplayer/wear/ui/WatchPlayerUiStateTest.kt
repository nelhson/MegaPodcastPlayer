package md.borisveriga.megapodcastplayer.wear.ui

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WatchEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 11_000L)

        assertEquals(40_000L, frame.position.positionMs)
    }

    @Test
    fun `progress follows the advancing position`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 121_000L)

        assertEquals(0.5f, frame.position.progress, 0.001f)
    }

    @Test
    fun `a paused phone does not drift`() {
        val received = ReceivedSnapshot(playing.copy(isPlaying = false), receivedAtElapsedMs = 0L)

        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 600_000L)

        assertEquals(30_000L, frame.position.positionMs)
    }

    // ---- Volume ---------------------------------------------------------------------------------

    private val playingWithVolume = playing.copy(volume = 9, maxVolume = 15)

    @Test
    fun `the level shown is the phone's until the wearer touches it`() {
        val received = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_000L)

        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 2_000L)

        assertEquals(9, frame.uiState.volumeLevel)
    }

    /**
     * The half-second between sending a level and the phone publishing it is the whole reason this
     * hold exists: the last snapshot still describes the step the wearer turned away from.
     */
    @Test
    fun `a level just set is held over the round trip`() {
        val received = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_000L)
        val sent = VolumeAdjustment(level = 12, sentAtElapsedMs = 1_500L)

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            received,
            nowElapsedMs = 2_000L,
            volume = sent,
        )

        assertEquals(12, frame.uiState.volumeLevel)
    }

    @Test
    fun `the phone's own reading wins once it confirms`() {
        val sent = VolumeAdjustment(level = 12, sentAtElapsedMs = 1_000L)
        // Arrived after the command went out, so this is the phone answering it.
        val received = ReceivedSnapshot(
            playingWithVolume.copy(volume = 12),
            receivedAtElapsedMs = 1_200L,
        )

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            received,
            nowElapsedMs = 1_300L,
            volume = sent,
        )

        assertEquals(12, frame.uiState.volumeLevel)
    }

    /**
     * The snap-back. The phone publishes for many reasons, and a snapshot sent for another one —
     * the position drifting, the reply to the step before — arrives after the send still carrying
     * the old level. Arriving is not answering.
     */
    @Test
    fun `a snapshot that still carries the old level does not take the bar back`() {
        val sent = VolumeAdjustment(level = 12, sentAtElapsedMs = 1_000L)
        val stale = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_200L)

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            stale,
            nowElapsedMs = 1_300L,
            volume = sent,
        )

        assertEquals(12, frame.uiState.volumeLevel)
    }

    /** A phone that refused the level, or clamped it, still gets the last word. */
    @Test
    fun `a level the phone never reaches is let go of after the hold`() {
        val sent = VolumeAdjustment(level = 12, sentAtElapsedMs = 1_000L)
        val refused = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_200L)

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            refused,
            nowElapsedMs = 1_000L + VOLUME_HOLD_MS + 1L,
            volume = sent,
        )

        assertEquals(9, frame.uiState.volumeLevel)
    }

    /** A phone that never answers must not freeze the bar on a level it never applied. */
    @Test
    fun `a level nobody confirmed is let go of eventually`() {
        val received = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_000L)
        val sent = VolumeAdjustment(level = 12, sentAtElapsedMs = 1_500L)

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            received,
            nowElapsedMs = 1_500L + VOLUME_HOLD_MS + 1L,
            volume = sent,
        )

        assertEquals(9, frame.uiState.volumeLevel)
    }

    @Test
    fun `a level the wearer is still turning to is shown before it is sent`() {
        val received = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 1_000L)
        val turning = VolumeAdjustment(level = 11, sentAtElapsedMs = null)

        val frame = watchPlayerFrame(
            PhoneLink.CONNECTED,
            received,
            nowElapsedMs = 600_000L,
            volume = turning,
        )

        assertEquals(11, frame.uiState.volumeLevel)
    }

    @Test
    fun `a phone with no volume scale offers no volume row`() {
        val withScale = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 0L)
        val without = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        assertTrue(
            watchPlayerFrame(PhoneLink.CONNECTED, withScale, nowElapsedMs = 0L)
                .uiState.canSetVolume,
        )
        assertFalse(
            watchPlayerFrame(PhoneLink.CONNECTED, without, nowElapsedMs = 0L)
                .uiState.canSetVolume,
        )
    }

    @Test
    fun `an unreachable phone offers no volume row either`() {
        val received = ReceivedSnapshot(playingWithVolume, receivedAtElapsedMs = 0L)

        val frame = watchPlayerFrame(PhoneLink.DISCONNECTED, received, nowElapsedMs = 0L)

        assertFalse(frame.uiState.canSetVolume)
    }

    // ---- The clock, and what it is allowed to change --------------------------------------------

    /**
     * The reason the state is in two parts. The clock ticks once a second, and whatever it changes
     * is recomposed once a second; the pages must not be on that list.
     */
    @Test
    fun `a ticking clock moves the position and nothing else`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val earlier = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 11_000L)
        val later = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 12_000L)

        assertNotEquals(earlier.position, later.position)
        assertEquals(earlier.uiState, later.uiState)
    }

    /** The bar reads the position from one place, so the snapshot does not carry a second copy. */
    @Test
    fun `the snapshot's own position is not carried into the screen state`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 0L)

        assertEquals(0L, frame.uiState.snapshot.positionMs)
        assertEquals(30_000L, frame.position.positionMs)
    }

    @Test
    fun `nothing received yet reads as idle rather than crashing`() {
        val frame = watchPlayerFrame(PhoneLink.CONNECTED, received = null, nowElapsedMs = 5_000L)

        assertTrue(frame.uiState.snapshot.isIdle)
        assertEquals(0L, frame.position.positionMs)
        assertFalse(frame.uiState.showsControls)
        assertTrue(frame.uiState.showsEmptyQueue)
    }

    @Test
    fun `controls are hidden while the phone is unreachable, however fresh the snapshot`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(PhoneLink.DISCONNECTED, received, nowElapsedMs = 0L).uiState

        assertFalse(uiState.showsControls)
    }

    @Test
    fun `controls appear once the phone is reachable and has something loaded`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 0L).uiState

        assertTrue(uiState.showsControls)
        assertFalse(uiState.showsEmptyQueue)
    }

    @Test
    fun `an idle phone with a queue is not the empty state`() {
        val idleWithQueue = NowPlayingSnapshot(
            upNext = listOf(WatchEpisode(id = "ep-2", title = "Two", showTitle = "Show")),
        )
        val received = ReceivedSnapshot(idleWithQueue, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 0L).uiState

        assertFalse(uiState.showsControls)
        assertFalse(uiState.showsEmptyQueue)
    }

    /**
     * The watch is a remote control and nothing else, so a phone it cannot reach leaves it nothing
     * to show but the sentence saying so — whatever the last snapshot claimed.
     */
    @Test
    fun `an unreachable phone is a link problem, whatever the last snapshot said`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(PhoneLink.DISCONNECTED, received, nowElapsedMs = 0L).uiState

        assertTrue(uiState.showsLinkProblem)
    }

    @Test
    fun `a reachable phone is not a link problem`() {
        val uiState = watchPlayerFrame(PhoneLink.CONNECTED, received = null, nowElapsedMs = 0L).uiState

        assertFalse(uiState.showsLinkProblem)
    }

    @Test
    fun `a scrub in progress overrides the extrapolated position`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val frame = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 11_000L,
            scrub = ScrubState(positionMs = 200_000L),
        )

        assertEquals(200_000L, frame.position.positionMs)
        assertEquals(200_000f / 300_000f, frame.position.progress, 0.001f)
        assertTrue(frame.uiState.isScrubbing)
    }

    @Test
    fun `a committed seek holds the position across the round trip`() {
        // The phone's last word still describes the old position; extrapolating it would walk the
        // bar back to where the user just dragged it away from.
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val frame = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 3_000L,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        assertEquals(200_000L, frame.position.positionMs)
        // Held, but no longer being dragged: the user has let go.
        assertFalse(frame.uiState.isScrubbing)
    }

    @Test
    fun `the hold releases once the phone confirms`() {
        val confirmation = ReceivedSnapshot(
            playing.copy(positionMs = 200_000L),
            receivedAtElapsedMs = 2_500L,
        )

        val frame = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = confirmation,
            nowElapsedMs = 3_500L,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        // Back to extrapolating, from the confirmed snapshot rather than the held value.
        assertEquals(201_000L, frame.position.positionMs)
    }

    @Test
    fun `the hold releases on its own if the phone never confirms`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 1_000L)

        val frame = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 2_000L + SEEK_HOLD_MS,
            scrub = ScrubState(positionMs = 200_000L, committedAtElapsedMs = 2_000L),
        )

        // A phone that went silent must not freeze the bar where the user left it forever.
        assertEquals(playing.positionAfter(1_000L + SEEK_HOLD_MS), frame.position.positionMs)
    }

    @Test
    fun `a failed command is carried into the state`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 0L,
            lastCommandFailed = true,
        ).uiState

        assertTrue(uiState.lastCommandFailed)
    }

    @Test
    fun `the transient cues are carried into the state`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 0L,
            momentSaved = true,
            showsScrubHint = true,
        ).uiState

        assertTrue(uiState.momentSaved)
        assertTrue(uiState.showsScrubHint)
    }
}
