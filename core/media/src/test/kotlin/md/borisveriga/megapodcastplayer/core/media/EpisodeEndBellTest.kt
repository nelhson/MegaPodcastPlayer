package md.borisveriga.megapodcastplayer.core.media

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EpisodeEndBell].
 *
 * Small, but the two rules it encodes are the whole feature: the bell has to be readable as state
 * so the player can draw its button, and it has to fire exactly once so a user who armed it for one
 * episode is not woken at the end of the next.
 */
class EpisodeEndBellTest {

    private val bell = EpisodeEndBell()

    @Test
    fun `a fresh bell is disarmed`() {
        assertFalse(bell.armed.value)
        assertFalse(bell.consume())
    }

    @Test
    fun `arming it is visible to whoever is watching`() = runTest {
        bell.armed.test {
            assertFalse(awaitItem())

            bell.arm()

            assertTrue(awaitItem())
        }
    }

    @Test
    fun `disarming it is visible too`() = runTest {
        bell.arm()

        bell.armed.test {
            assertTrue(awaitItem())

            bell.disarm()

            assertFalse(awaitItem())
        }
    }

    @Test
    fun `consuming an armed bell rings once and disarms it`() {
        bell.arm()

        assertTrue(bell.consume())
        // The second read is the one that matters: two end-of-episode signals can describe the same
        // moment, and only one of them may ring.
        assertFalse(bell.consume())
        assertFalse(bell.armed.value)
    }

    @Test
    fun `arming twice still rings once`() {
        bell.arm()
        bell.arm()

        assertTrue(bell.consume())
        assertFalse(bell.consume())
    }

    @Test
    fun `a bell the user turned off does not ring`() {
        bell.arm()
        bell.disarm()

        assertFalse(bell.consume())
    }

    @Test
    fun `re-arming after it has rung works`() {
        bell.arm()
        bell.consume()

        bell.arm()

        assertTrue(bell.consume())
        assertEquals(false, bell.armed.value)
    }
}
