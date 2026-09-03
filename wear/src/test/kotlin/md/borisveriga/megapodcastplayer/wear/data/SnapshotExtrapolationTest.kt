package md.borisveriga.megapodcastplayer.wear.data

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the guess the watch-face surfaces make about where playback has got to.
 *
 * The app's screen never guesses — it stamps arrivals with its own clock — so this is the only place
 * the phone's wall clock is trusted, and the only place a cap is needed to stop a stale surface
 * walking an episode to its end on its own.
 */
class SnapshotExtrapolationTest {

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "The one about batteries",
        isPlaying = true,
        positionMs = 60_000L,
        durationMs = 3_600_000L,
        publishedAtMs = PUBLISHED_AT,
    )

    @Test
    fun `a paused episode stays exactly where the phone left it`() {
        val paused = playing.copy(isPlaying = false)

        assertEquals(60_000L, extrapolatedPositionMs(paused, PUBLISHED_AT + 600_000L))
    }

    @Test
    fun `a playing episode advances by the time since the phone published`() {
        assertEquals(90_000L, extrapolatedPositionMs(playing, PUBLISHED_AT + 30_000L))
    }

    /** The phone owns the speed, and the position moves at that rate rather than at real time. */
    @Test
    fun `a faster episode advances faster`() {
        val fast = playing.copy(speed = 2f)

        assertEquals(120_000L, extrapolatedPositionMs(fast, PUBLISHED_AT + 30_000L))
    }

    /**
     * The two devices' wall clocks are independent, so a watch running behind its phone would
     * otherwise be handed a negative interval and rewind the bar.
     */
    @Test
    fun `a watch clock behind the phone's does not walk the position backwards`() {
        assertEquals(60_000L, extrapolatedPositionMs(playing, PUBLISHED_AT - 60_000L))
    }

    /**
     * A tile can sit unlooked-at for a day. Without the cap its bar would quietly reach the end of
     * an episode nobody has been listening to, which is worse than being visibly out of date.
     */
    @Test
    fun `an old snapshot stops advancing rather than running to the end`() {
        val aDayLater = PUBLISHED_AT + 24L * 60 * 60 * 1_000

        // Half an hour of extrapolation on top of the minute the phone published.
        assertEquals(60_000L + 30 * 60 * 1_000L, extrapolatedPositionMs(playing, aDayLater))
    }

    private companion object {
        /** An arbitrary phone wall clock; only differences from it matter. */
        const val PUBLISHED_AT = 1_700_000_000_000L
    }
}
