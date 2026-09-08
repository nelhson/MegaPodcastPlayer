package md.borisveriga.megapodcastplayer.core.media

import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SleepTimer].
 *
 * Everything asserted here happens minutes after the tap that caused it, on a phone that is face
 * down in the dark, which is the one place a bug in it would never be noticed and never be
 * forgiven. Virtual time makes an hour cost nothing, so the whole countdown is exercised rather
 * than the first second of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val connection: PlaybackConnection = mockk(relaxed = true)
    private val bell = EpisodeEndBell()

    private fun timer(scope: kotlinx.coroutines.CoroutineScope) =
        SleepTimer(connection = connection, bell = bell, scope = scope)

    @Test
    fun `a fresh timer is not armed`() = runTest {
        assertFalse(timer(backgroundScope).state.value.isArmed)
        assertNull(timer(backgroundScope).state.value.remainingMs)
    }

    @Test
    fun `the countdown ticks down and stops the player at zero`() = runTest {
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armAfter(15 * MINUTE_MS)
        runCurrent()
        assertEquals(15 * MINUTE_MS, sleepTimer.state.value.remainingMs)

        advanceTimeBy(10 * MINUTE_MS)
        runCurrent()
        assertEquals(5 * MINUTE_MS, sleepTimer.state.value.remainingMs)
        coVerify(exactly = 0) { connection.pause() }

        advanceTimeBy(5 * MINUTE_MS + FADE_MS)
        runCurrent()

        // Faded out first, then paused, then the volume put back — a player left silent by a timer
        // is a player that appears broken tomorrow morning.
        coVerifyOrder {
            connection.setVolume(0f)
            connection.pause()
            connection.setVolume(1f)
        }
        assertFalse(sleepTimer.state.value.isArmed)
    }

    @Test
    fun `arming again replaces the countdown rather than running two`() = runTest {
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armAfter(15 * MINUTE_MS)
        advanceTimeBy(MINUTE_MS)
        sleepTimer.armAfter(30 * MINUTE_MS)
        runCurrent()

        assertEquals(30 * MINUTE_MS, sleepTimer.state.value.remainingMs)

        // The first timer would have fired here; only the second one is still running.
        advanceTimeBy(20 * MINUTE_MS)
        runCurrent()
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `a shake adds to what is left, not to what was chosen`() = runTest {
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armAfter(15 * MINUTE_MS)
        advanceTimeBy(10 * MINUTE_MS)
        runCurrent()

        sleepTimer.extend(15 * MINUTE_MS)
        runCurrent()

        // Five left plus fifteen, not fifteen plus fifteen.
        assertEquals(20 * MINUTE_MS, sleepTimer.state.value.remainingMs)
    }

    @Test
    fun `a shake with nothing counting down does nothing`() = runTest {
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfEpisode()
        sleepTimer.extend(15 * MINUTE_MS)
        runCurrent()

        // There is no number to add to, and turning the end-of-episode option into a countdown
        // would be the app deciding something the user did not say.
        assertNull(sleepTimer.state.value.remainingMs)
        assertTrue(sleepTimer.state.value.isEndOfEpisode)
    }

    @Test
    fun `the end-of-episode option arms the bell the service reads`() = runTest {
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfEpisode()

        assertTrue(sleepTimer.state.value.isEndOfEpisode)
        assertTrue(bell.armed.value)
    }

    @Test
    fun `cancelling stops the countdown and disarms the bell`() = runTest {
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfEpisode()

        sleepTimer.cancel()
        runCurrent()

        assertFalse(sleepTimer.state.value.isArmed)
        assertFalse(bell.armed.value)
    }

    @Test
    fun `a countdown cancelled part-way never reaches the player`() = runTest {
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armAfter(15 * MINUTE_MS)
        advanceTimeBy(10 * MINUTE_MS)

        sleepTimer.cancel()
        advanceTimeBy(30 * MINUTE_MS)
        runCurrent()

        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `a non-positive duration cancels rather than firing at once`() = runTest {
        val sleepTimer = timer(backgroundScope)

        // What "end of chapter" computes on the last second of a chapter. Pausing instantly would
        // be the most surprising possible answer to a tap on a sleep timer.
        sleepTimer.armAfter(0L)
        runCurrent()

        assertFalse(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        /** Comfortably past the fade, so the pause has certainly happened. */
        const val FADE_MS = 30_000L
    }
}
