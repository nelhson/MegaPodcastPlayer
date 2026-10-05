package md.borisveriga.megapodcastplayer.core.media

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [followSession] and the pacing of its retries.
 *
 * The loop is what keeps the bar truthful when the service is not there: at launch, when the first
 * connect fails, and later, when a session that was being followed goes away. Both are rare on a
 * phone and so are only ever going to be exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFollowingTest {

    /** What the flow under test has emitted so far. */
    private val seen = mutableListOf<PlaybackState>()

    /** How many times the loop has tried to connect. */
    private var connects = 0

    /**
     * Collects [followSession] into [seen] for the length of the test.
     *
     * @param connect what a connect does on a given attempt, numbered from one.
     * @param states the states of the session a connect returned.
     */
    private fun TestScope.follow(
        connect: suspend (attempt: Int) -> String,
        states: (String) -> Flow<PlaybackState>,
    ) {
        backgroundScope.launch {
            followSession(connect = { connect(++connects) }, states = states).collect { seen += it }
        }
        runCurrent()
    }

    /** A session that says [state] and then stays up for as long as it is collected. */
    private fun lasting(state: PlaybackState): Flow<PlaybackState> = flow {
        emit(state)
        awaitCancellation()
    }

    @Test
    fun `a session's states are passed on`() = runTest {
        follow(connect = { "session" }, states = { lasting(LOADED) })

        assertEquals(listOf(LOADED), seen)
        assertEquals(1, connects)
    }

    @Test
    fun `a failed connect is said as disconnected, and not as still restoring`() = runTest {
        // Restoring is what holds a restored sheet open; a service that cannot be reached must let
        // it go, or the navigation bar stays hidden behind a player that is not coming.
        follow(connect = { throw IOException("no service") }, states = { lasting(LOADED) })

        val said = seen.single()
        assertFalse(said.isConnected)
        assertFalse(said.isRestoring)
        assertEquals("no service", said.errorMessage)
        assertTrue(said.isEmptied)
    }

    @Test
    fun `a failed connect is tried again, and the queue it finds is shown`() = runTest {
        follow(
            connect = { attempt -> if (attempt == 1) throw IOException("no service") else "session" },
            states = { lasting(LOADED) },
        )
        assertEquals(1, connects)

        advanceTimeBy(reconnectDelayMs(0) + 1)

        assertEquals(2, connects)
        assertEquals(LOADED, seen.last())
    }

    @Test
    fun `connects that keep failing are spaced further and further apart`() = runTest {
        follow(connect = { throw IOException("no service") }, states = { lasting(LOADED) })

        advanceTimeBy(reconnectDelayMs(0) + 1)
        assertEquals(2, connects)
        // The second wait is longer than the first: the first one again is not enough.
        advanceTimeBy(reconnectDelayMs(0))
        assertEquals(2, connects)
        advanceTimeBy(reconnectDelayMs(1) - reconnectDelayMs(0))
        assertEquals(3, connects)
    }

    @Test
    fun `a session that ends is said as restoring, then connected to again`() = runTest {
        val alive = MutableSharedFlow<PlaybackState>()
        var sessions = 0
        follow(
            connect = { "session-$it" },
            // The first session ends when it is told to; the one after it stays up.
            states = { if (++sessions == 1) alive.takeWhile { it.isConnected } else lasting(LOADED) },
        )
        alive.emit(LOADED.copy(positionMs = 5_000L))
        alive.emit(PlaybackState(isConnected = false))
        runCurrent()

        // What the first session last said is not left standing over a player that has gone.
        assertEquals(PlaybackState(isRestoring = true), seen.last())
        assertEquals(1, connects)

        advanceTimeBy(reconnectDelayMs(0) + 1)

        assertEquals(2, connects)
        assertEquals(LOADED, seen.last())
    }

    @Test
    fun `a success puts the pacing back to the start`() = runTest {
        val alive = MutableSharedFlow<PlaybackState>()
        follow(
            connect = { attempt -> if (attempt in 1..2) throw IOException("no service") else "session" },
            states = { alive.takeWhile { it.isConnected } },
        )
        advanceTimeBy(reconnectDelayMs(0) + reconnectDelayMs(1) + 1)
        assertEquals(3, connects)

        alive.emit(PlaybackState(isConnected = false))
        advanceTimeBy(reconnectDelayMs(0) + 1)

        assertEquals(4, connects)
    }

    @Test
    fun `the wait doubles from a second to a ceiling of thirty`() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(16_000L, reconnectDelayMs(4))
        assertEquals(30_000L, reconnectDelayMs(5))
        assertEquals(30_000L, reconnectDelayMs(500))
        // A count that could not happen still gets an answer rather than a negative shift.
        assertEquals(1_000L, reconnectDelayMs(-1))
    }

    private companion object {
        /** A connected player with an episode loaded. */
        val LOADED = PlaybackState(isConnected = true, episodeId = "episode-1")
    }
}
