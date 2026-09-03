package md.borisveriga.megapodcastplayer.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the phone-link poll backoff.
 *
 * The poller used to run at a fixed ten-second interval for as long as any screen was collecting,
 * which on a watch is a permanent wake-up cost for a check whose answer almost never changes. What
 * matters is the shape asserted here: responsive for the first minute, cheap thereafter.
 */
class LinkPollDelayTest {

    @Test
    fun `polls eagerly while a screen has just come up`() {
        // Poll 0 is the immediate one at collection time, so the first delay follows one completed
        // poll; every delay through the last eager poll must be the short one.
        for (completedPolls in 1 until EAGER_POLL_COUNT) {
            assertEquals(
                "poll $completedPolls should still be in the eager phase",
                EAGER_POLL_INTERVAL_MS,
                linkPollDelayMs(completedPolls),
            )
        }
    }

    @Test
    fun `backs off once the eager phase is over`() {
        assertEquals(IDLE_POLL_INTERVAL_MS, linkPollDelayMs(EAGER_POLL_COUNT))
        assertEquals(IDLE_POLL_INTERVAL_MS, linkPollDelayMs(EAGER_POLL_COUNT + 1))
        assertEquals(IDLE_POLL_INTERVAL_MS, linkPollDelayMs(1_000))
    }

    @Test
    fun `the eager phase lasts about a minute`() {
        // The constants are tuned together: changing one without the other silently changes how
        // long the responsive window is, which is the part a reader cares about.
        assertEquals(60_000L, EAGER_POLL_COUNT * EAGER_POLL_INTERVAL_MS)
    }

    @Test
    fun `backing off actually costs less`() {
        assertTrue(
            "the idle interval must be longer than the eager one, or the backoff does nothing",
            IDLE_POLL_INTERVAL_MS > EAGER_POLL_INTERVAL_MS,
        )
    }
}
