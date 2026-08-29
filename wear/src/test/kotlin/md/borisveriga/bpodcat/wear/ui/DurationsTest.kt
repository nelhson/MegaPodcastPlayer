package md.borisveriga.bpodcat.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the two labels on the watch that are formatted rather than drawn. */
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
    fun `whole speeds lose their decimals`() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("2x", formatSpeed(2f))
    }

    @Test
    fun `fractional speeds keep only the digits they need`() {
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("1.75x", formatSpeed(1.75f))
        assertEquals("0.8x", formatSpeed(0.8f))
    }
}
