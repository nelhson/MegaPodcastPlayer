package md.borisveriga.bpodcat.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the labels on the watch that are formatted rather than drawn. */
class DurationsTest {

    @Test
    fun `positions under an hour drop the hour field`() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:07", formatPlaybackTime(7_400L))
        assertEquals("4:12", formatPlaybackTime(252_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
    }

    @Test
    fun `positions over an hour pad the minutes`() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("2:05:09", formatPlaybackTime(7_509_000L))
    }

    @Test
    fun `a negative position reads as the start`() {
        assertEquals("0:00", formatPlaybackTime(-1_000L))
    }

    @Test
    fun `remaining time under an hour is minutes alone`() {
        assertEquals("48m", formatCompactRemaining(48L * 60 * 1_000))
        assertEquals("1m", formatCompactRemaining(60_000L))
    }

    @Test
    fun `remaining time over an hour runs the two together`() {
        assertEquals("1h20", formatCompactRemaining(80L * 60 * 1_000))
        // The minutes are padded, so the label cannot read as "1h5" for an hour and five minutes.
        assertEquals("1h05", formatCompactRemaining(65L * 60 * 1_000))
    }

    /** A whole hour drops its minutes: four characters is the whole budget, and "2h00" wastes two. */
    @Test
    fun `a whole number of hours drops the minutes`() {
        assertEquals("2h", formatCompactRemaining(2L * 60 * 60 * 1_000))
    }

    /**
     * Rounding up rather than down: "0m" on an episode that is still playing reads as a bug, and
     * the last minute is over soon enough to forgive it.
     */
    @Test
    fun `part of a minute rounds up to one`() {
        assertEquals("1m", formatCompactRemaining(1_000L))
        assertEquals("2m", formatCompactRemaining(61_000L))
    }

    @Test
    fun `nothing left reads as zero rather than as a minute`() {
        assertEquals("0m", formatCompactRemaining(0L))
        assertEquals("0m", formatCompactRemaining(-5_000L))
    }
}
