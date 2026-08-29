package md.borisveriga.bpodcat.core.common.format

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the episode-list and player formatters. */
class FormattersTest {

    private val now = Instant.parse("2026-08-28T12:00:00Z")

    @Test
    fun `formats durations without ever showing seconds`() {
        assertEquals("1 h 23 min", formatDuration(((1 * 60) + 23) * 60_000L))
        assertEquals("2 h", formatDuration(2 * 3_600_000L))
        assertEquals("45 min", formatDuration(45 * 60_000L))
    }

    @Test
    fun `rounds a sub-minute duration up to one minute`() {
        assertEquals("1 min", formatDuration(20_000L))
    }

    @Test
    fun `returns null for an unknown or zero duration`() {
        assertNull(formatDuration(null))
        assertNull(formatDuration(0L))
        assertNull(formatDuration(-5L))
    }

    @Test
    fun `formats remaining time and hides it once finished`() {
        assertEquals("20 min left", formatRemaining(durationMs = 60 * 60_000L, positionMs = 40 * 60_000L))
        assertNull(formatRemaining(durationMs = 60 * 60_000L, positionMs = 60 * 60_000L))
        assertNull(formatRemaining(durationMs = null, positionMs = 0L))
    }

    @Test
    fun `formats recent publication dates relatively`() {
        fun format(iso: String) = formatPublishedDate(Instant.parse(iso), now, ZoneOffset.UTC)

        assertEquals("Today", format("2026-08-28T06:00:00Z"))
        assertEquals("Yesterday", format("2026-08-27T06:00:00Z"))
        assertEquals("3 days ago", format("2026-08-25T06:00:00Z"))
    }

    @Test
    fun `formats older dates absolutely and includes the year for other years`() {
        fun format(iso: String) = formatPublishedDate(Instant.parse(iso), now, ZoneOffset.UTC)

        assertEquals("1 Aug", format("2026-08-01T06:00:00Z"))
        assertEquals("24 Aug 2024", format("2024-08-24T06:00:00Z"))
    }

    @Test
    fun `treats a slightly future publication date as today`() {
        val future = Instant.parse("2026-08-29T06:00:00Z")

        assertEquals("Today", formatPublishedDate(future, now, ZoneOffset.UTC))
    }

    @Test
    fun `returns null for a missing publication date`() {
        assertNull(formatPublishedDate(null, now, ZoneOffset.UTC))
    }

    @Test
    fun `formats playback positions with and without hours`() {
        assertEquals("0:05", formatPosition(5_000L))
        assertEquals("12:30", formatPosition(750_000L))
        assertEquals("1:23:45", formatPosition(((1 * 3600) + (23 * 60) + 45) * 1000L))
        assertEquals("0:00", formatPosition(-1L))
    }

    @Test
    fun `formats byte counts in megabytes and gigabytes`() {
        assertEquals("340 MB", formatBytes(340_000_000L))
        assertEquals("1.2 GB", formatBytes(1_200_000_000L))
    }
}
