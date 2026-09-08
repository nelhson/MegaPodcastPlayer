package md.borisveriga.megapodcastplayer.core.model.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the timecode pair.
 *
 * [parseTimecodeMs] is driven by the shapes chapter sources actually publish; [formatTimecode] is
 * checked against it, because the two are only useful if they are inverses.
 */
class TimecodeTest {

    @Test
    fun `parses the start of an episode`() {
        assertEquals(0L, parseTimecodeMs("0:00"))
        assertEquals(0L, parseTimecodeMs("00:00"))
        assertEquals(0L, parseTimecodeMs("00:00:00.000"))
    }

    @Test
    fun `parses minutes and seconds`() {
        assertEquals(272_000L, parseTimecodeMs("4:32"))
        assertEquals(3_490_000L, parseTimecodeMs("58:10"))
    }

    @Test
    fun `parses hours minutes and seconds`() {
        assertEquals(3_753_000L, parseTimecodeMs("1:02:33"))
        assertEquals(36_000_000L, parseTimecodeMs("10:00:00"))
    }

    @Test
    fun `parses a fractional seconds part`() {
        assertEquals(754_500L, parseTimecodeMs("12:34.5"))
        assertEquals(754_050L, parseTimecodeMs("12:34.05"))
        assertEquals(754_123L, parseTimecodeMs("12:34.123"))
        // Podlove publishes a comma in some locales.
        assertEquals(754_500L, parseTimecodeMs("12:34,5"))
    }

    @Test
    fun `ignores surrounding whitespace`() {
        assertEquals(272_000L, parseTimecodeMs("  4:32 "))
    }

    @Test
    fun `refuses an impossible clock reading`() {
        assertNull(parseTimecodeMs("99:99"))
        assertNull(parseTimecodeMs("1:60:00"))
        assertNull(parseTimecodeMs("1:00:60"))
    }

    @Test
    fun `refuses text that is not a timecode`() {
        assertNull(parseTimecodeMs(""))
        assertNull(parseTimecodeMs("   "))
        assertNull(parseTimecodeMs("abc"))
        assertNull(parseTimecodeMs("12"))
        assertNull(parseTimecodeMs("1:2:3:4"))
        assertNull(parseTimecodeMs("4:32 Intro"))
    }

    @Test
    fun `a chapter at zero is a value rather than an absence`() {
        // The difference from parseItunesDurationMs, and the reason this function exists.
        assertEquals(0L, parseTimecodeMs("0:00"))
    }

    @Test
    fun `formats minutes and seconds without a leading hour`() {
        assertEquals("0:00", formatTimecode(0L))
        assertEquals("12:23", formatTimecode(743_000L))
        assertEquals("58:10", formatTimecode(3_490_000L))
    }

    @Test
    fun `formats hours once there is an hour to show`() {
        assertEquals("1:02:33", formatTimecode(3_753_000L))
        assertEquals("10:00:00", formatTimecode(36_000_000L))
    }

    @Test
    fun `truncates rather than rounds, so a timecode never names a second not yet reached`() {
        assertEquals("0:12", formatTimecode(12_999L))
    }

    @Test
    fun `a negative position clamps to the start`() {
        assertEquals("0:00", formatTimecode(-5_000L))
    }

    @Test
    fun `formatting and parsing are inverses at whole seconds`() {
        listOf(0L, 12_000L, 743_000L, 3_753_000L).forEach { positionMs ->
            assertEquals(positionMs, parseTimecodeMs(formatTimecode(positionMs)))
        }
    }

    @Test
    fun `whole seconds are what a timestamped link takes`() {
        assertEquals(743L, positionSeconds(743_400L))
        assertEquals(0L, positionSeconds(-1L))
    }
}
