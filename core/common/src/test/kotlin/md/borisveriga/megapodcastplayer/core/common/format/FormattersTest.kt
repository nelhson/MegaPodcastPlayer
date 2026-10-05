package md.borisveriga.megapodcastplayer.core.common.format

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the episode-list and player formatters.
 *
 * Robolectric, for real [Resources]: the words these produce are string resources now, and a fake
 * that returned the format strings from memory would be testing the fake. It is also the only way
 * to check the thing this change was for — that a non-English locale gets a whole line in its own
 * language rather than half of one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormattersTest {

    private val now = Instant.parse("2026-08-28T12:00:00Z")

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    /**
     * The same resources as another locale would resolve them.
     *
     * @param locale the locale to read in.
     * @return resources configured for it.
     */
    private fun resourcesIn(locale: Locale): Resources {
        val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
        return ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(configuration)
            .resources
    }

    @Test
    fun `formats durations without ever showing seconds`() {
        assertEquals("1 h 23 min", formatDuration(resources, ((1 * 60) + 23) * 60_000L))
        assertEquals("2 h", formatDuration(resources, 2 * 3_600_000L))
        assertEquals("45 min", formatDuration(resources, 45 * 60_000L))
    }

    @Test
    fun `rounds a sub-minute duration up to one minute`() {
        assertEquals("1 min", formatDuration(resources, 20_000L))
    }

    @Test
    fun `returns null for an unknown or zero duration`() {
        assertNull(formatDuration(resources, null))
        assertNull(formatDuration(resources, 0L))
        assertNull(formatDuration(resources, -5L))
    }

    @Test
    fun `formats remaining time and hides it once finished`() {
        assertEquals(
            "20 min left",
            formatRemaining(resources, durationMs = 60 * 60_000L, positionMs = 40 * 60_000L),
        )
        assertNull(formatRemaining(resources, durationMs = 60 * 60_000L, positionMs = 60 * 60_000L))
        assertNull(formatRemaining(resources, durationMs = null, positionMs = 0L))
    }

    @Test
    fun `counts down beside the scrubber, to the second`() {
        // The scrubber's own label, distinct from formatRemaining's prose: it ticks once a second
        // and has to line up with the elapsed timecode on the other side of the bar.
        assertEquals("-30:06", formatCountdown(durationMs = 2_530_000L, positionMs = 724_000L))
        assertEquals("-1:00:00", formatCountdown(durationMs = 7_200_000L, positionMs = 3_600_000L))
    }

    @Test
    fun `a finished episode counts down to zero rather than to nothing`() {
        // The label is one half of a pair whose widths hold the layout apart, so it has to say
        // something at every position. Only an unknown duration leaves it with nothing to say.
        assertEquals("-0:00", formatCountdown(durationMs = 60_000L, positionMs = 60_000L))
        assertEquals("-0:00", formatCountdown(durationMs = 60_000L, positionMs = 99_000L))
        assertNull(formatCountdown(durationMs = null, positionMs = 0L))
        assertNull(formatCountdown(durationMs = 0L, positionMs = 0L))
    }

    @Test
    fun `projects the finish time at the speed it is playing at`() {
        val now = Instant.parse("2026-09-07T20:00:00Z")

        // Forty minutes left at 1.75x is twenty-three minutes of clock. An "ends at" that ignored
        // the speed would be wrong for almost every user of this app.
        assertEquals(
            "20:22",
            formatEndsAt(
                remainingMs = 40 * 60_000L,
                speed = 1.75f,
                now = now,
                zone = ZoneOffset.UTC,
                locale = Locale.UK,
            ),
        )
        assertEquals(
            "20:40",
            formatEndsAt(
                remainingMs = 40 * 60_000L,
                speed = 1f,
                now = now,
                zone = ZoneOffset.UTC,
                locale = Locale.UK,
            ),
        )
    }

    @Test
    fun `there is no finish time to project when there is nothing left`() {
        val now = Instant.parse("2026-09-07T20:00:00Z")

        assertNull(
            formatEndsAt(remainingMs = null, speed = 1f, now = now, zone = ZoneOffset.UTC),
        )
        assertNull(
            formatEndsAt(remainingMs = 0L, speed = 1f, now = now, zone = ZoneOffset.UTC),
        )
    }

    @Test
    fun `a nonsensical speed is treated as normal rather than dividing by zero`() {
        val now = Instant.parse("2026-09-07T20:00:00Z")

        assertEquals(
            "20:40",
            formatEndsAt(
                remainingMs = 40 * 60_000L,
                speed = 0f,
                now = now,
                zone = ZoneOffset.UTC,
                locale = Locale.UK,
            ),
        )
    }

    @Test
    fun `formats recent publication dates relatively`() {
        fun format(iso: String) =
            formatPublishedDate(resources, Instant.parse(iso), now, ZoneOffset.UTC)

        assertEquals("Today", format("2026-08-28T06:00:00Z"))
        assertEquals("Yesterday", format("2026-08-27T06:00:00Z"))
        assertEquals("3 days ago", format("2026-08-25T06:00:00Z"))
    }

    @Test
    fun `formats older dates absolutely and includes the year for other years`() {
        fun format(iso: String) =
            formatPublishedDate(resources, Instant.parse(iso), now, ZoneOffset.UTC)

        assertEquals("1 Aug", format("2026-08-01T06:00:00Z"))
        assertEquals("24 Aug 2024", format("2024-08-24T06:00:00Z"))
    }

    @Test
    fun `treats a slightly future publication date as today`() {
        val future = Instant.parse("2026-08-29T06:00:00Z")

        assertEquals("Today", formatPublishedDate(resources, future, now, ZoneOffset.UTC))
    }

    @Test
    fun `returns null for a missing publication date`() {
        assertNull(formatPublishedDate(resources, null, now, ZoneOffset.UTC))
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
        assertEquals("340 MB", formatBytes(resources, 340_000_000L))
        assertEquals("1.2 GB", formatBytes(resources, 1_200_000_000L))
    }

    /**
     * The whole point of the change. These strings sit on the same line as words that were already
     * resources — an episode row reads "24 Aug · 1 h 23 min · 90 MB" — so as literals they would
     * have produced a line half in one language and half in English.
     */
    @Test
    fun `the words follow the locale rather than staying in English`() {
        val german = resourcesIn(Locale.GERMANY)

        // No German translation is shipped, so the words themselves do not change — but the pieces
        // that the *platform* localises must, and they are half of every line.
        assertEquals("1,2 GB", formatBytes(german, 1_200_000_000L))
        assertNotEquals(
            formatPublishedDate(resources, Instant.parse("2026-08-01T06:00:00Z"), now, ZoneOffset.UTC),
            formatPublishedDate(german, Instant.parse("2026-08-01T06:00:00Z"), now, ZoneOffset.UTC),
        )
    }

    /**
     * A formatter that read the locale once, at class load, would keep formatting in whichever
     * language the process started in for the rest of its life. These used to.
     */
    @Test
    fun `the locale is read per call rather than frozen at class load`() {
        val first = formatBytes(resourcesIn(Locale.UK), 1_200_000_000L)
        val second = formatBytes(resourcesIn(Locale.GERMANY), 1_200_000_000L)

        assertEquals("1.2 GB", first)
        assertEquals("1,2 GB", second)
    }

    @Test
    fun `whole playback rates lose their decimals`() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("2x", formatSpeed(2f))
    }

    @Test
    fun `fractional playback rates keep only the digits they need`() {
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("1.75x", formatSpeed(1.75f))
        assertEquals("0.8x", formatSpeed(0.8f))
    }

    @Test
    fun `playback rates are formatted in a fixed locale`() {
        val previous = Locale.getDefault()
        try {
            // German writes 1,5 rather than 1.5. The speed chips sit next to each other across two
            // screens and the watch, and a separator that changed with the device locale would make
            // the same setting look like two.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5x", formatSpeed(1.5f))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
