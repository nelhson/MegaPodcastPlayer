package md.borisveriga.megapodcastplayer.wear.ui

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode
import md.borisveriga.megapodcastplayer.wear.data.TransferProgress
import md.borisveriga.megapodcastplayer.wear.playback.WatchPlaybackState
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

    /**
     * The watch's own player is polled twice a second, and each poll carries a new position. That
     * reading has to end up in the bar and nowhere else, for the same reason as the clock above.
     */
    @Test
    fun `the watch's own player being polled moves the position and nothing else`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val earlier = watchPlayerFrame(PhoneLink.CONNECTED, received, 0L, local = localPlayback)
        val later = watchPlayerFrame(
            PhoneLink.CONNECTED,
            received,
            nowElapsedMs = 0L,
            local = localPlayback.copy(positionMs = 60_500L),
        )

        assertEquals(60_500L, later.position.positionMs)
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
            upNext = listOf(QueuedEpisode(id = "ep-2", title = "Two", showTitle = "Show")),
        )
        val received = ReceivedSnapshot(idleWithQueue, receivedAtElapsedMs = 0L)

        val uiState = watchPlayerFrame(PhoneLink.CONNECTED, received, nowElapsedMs = 0L).uiState

        assertFalse(uiState.showsControls)
        assertFalse(uiState.showsEmptyQueue)
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

    // ---- What the watch itself is playing and holding ------------------------------------------

    /**
     * Local playback takes the screen over. Anything else would put the phone's episode title above
     * buttons that pause the watch.
     */
    @Test
    fun `playing on the watch replaces what the phone is showing`() {
        val received = ReceivedSnapshot(playing, receivedAtElapsedMs = 0L)

        val frame = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = received,
            nowElapsedMs = 0L,
            local = localPlayback,
        )

        assertEquals(PlaybackSource.WATCH, frame.uiState.source)
        assertEquals("The one about batteries", frame.uiState.snapshot.title)
        assertEquals(60_000L, frame.position.positionMs)
        assertTrue(frame.uiState.showsControls)
    }

    /**
     * The skip intervals are the user's preference, set once on the phone. A watch that jumped a
     * different distance for its own audio would be a second opinion nobody asked for.
     */
    @Test
    fun `local playback keeps the phone's skip intervals`() {
        val configured = playing.copy(skipForwardMs = 45_000L, skipBackMs = 15_000L)

        val uiState = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = ReceivedSnapshot(configured, receivedAtElapsedMs = 0L),
            nowElapsedMs = 0L,
            local = localPlayback,
        ).uiState

        assertEquals(45_000L, uiState.snapshot.skipForwardMs)
        assertEquals(15_000L, uiState.snapshot.skipBackMs)
    }

    /**
     * The whole point of carrying episodes: the phone is at home and the watch still works. The
     * unreachable-phone screen must not stand in front of that.
     */
    @Test
    fun `an unreachable phone does not hide the episodes the watch holds`() {
        val uiState = watchPlayerFrame(
            link = PhoneLink.DISCONNECTED,
            received = null,
            nowElapsedMs = 0L,
            stored = listOf(stored),
        ).uiState

        assertFalse(uiState.showsLinkProblem)
        assertTrue(uiState.showsPhoneOutOfRange)
    }

    @Test
    fun `an unreachable phone with nothing on the watch is still a dead end`() {
        val uiState = watchPlayerFrame(
            link = PhoneLink.DISCONNECTED,
            received = null,
            nowElapsedMs = 0L,
        ).uiState

        assertTrue(uiState.showsLinkProblem)
        assertFalse(uiState.showsPhoneOutOfRange)
    }

    @Test
    fun `playing on the watch keeps its controls with no phone in range`() {
        val uiState = watchPlayerFrame(
            link = PhoneLink.DISCONNECTED,
            received = null,
            nowElapsedMs = 0L,
            local = localPlayback,
            stored = listOf(stored),
        ).uiState

        assertTrue(uiState.showsControls)
        assertFalse(uiState.showsLinkProblem)
    }

    /**
     * The phone offers everything it holds, because it cannot know what arrived — a transfer that
     * died halfway leaves it thinking it sent an episode the watch threw away.
     */
    @Test
    fun `what can be copied leaves out what is already here or on its way`() {
        val uiState = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = null,
            nowElapsedMs = 0L,
            stored = listOf(stored),
            offered = listOf(
                OfflineEpisode(id = "ep-1", title = "Already here"),
                OfflineEpisode(id = "ep-2", title = "On its way"),
                OfflineEpisode(id = "ep-3", title = "Could be copied"),
            ),
            transfers = mapOf("ep-2" to TransferProgress(receivedBytes = 5L, expectedBytes = 10L)),
        ).uiState

        assertEquals(listOf("ep-3"), uiState.copyable.map { it.id })
    }

    @Test
    fun `an arriving episode is named by the offer it came from`() {
        val uiState = watchPlayerFrame(
            link = PhoneLink.CONNECTED,
            received = null,
            nowElapsedMs = 0L,
            offered = listOf(OfflineEpisode(id = "ep-2", title = "On its way")),
            transfers = mapOf("ep-2" to TransferProgress(receivedBytes = 5L, expectedBytes = 10L)),
        ).uiState

        val arriving = uiState.arriving.single()
        assertEquals("On its way", arriving.episode.title)
        assertEquals(0.5f, arriving.progress.fraction, 0.001f)
    }

    /** An episode on the watch, with the fields the rows and the header read. */
    private val stored = StoredEpisode(
        id = "ep-1",
        title = "The one about batteries",
        showTitle = "Radio Hardware",
        durationMs = 300_000L,
        sizeBytes = 28_000_000L,
    )

    /** The watch playing that episode, a minute in. */
    private val localPlayback = WatchPlaybackState(
        episode = stored,
        isPlaying = true,
        positionMs = 60_000L,
        durationMs = 300_000L,
    )
}
