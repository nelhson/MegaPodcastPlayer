package md.borisveriga.megapodcastplayer.core.media

import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * Puts a player behind [connection] whose playhead the test moves by hand.
     *
     * @param positionMs where it starts.
     * @return the state to move.
     */
    private fun playing(positionMs: Long): MutableStateFlow<PlaybackState> {
        val playback = MutableStateFlow(
            PlaybackState(isConnected = true, episodeId = EPISODE, isPlaying = true, positionMs = positionMs),
        )
        every { connection.playbackState } returns playback
        return playback
    }

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

        // Pausing instantly would be the most surprising possible answer to a tap on a sleep timer.
        sleepTimer.armAfter(0L)
        runCurrent()

        assertFalse(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `end of chapter leaves the player alone until the chapter is nearly over`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()
        playback.value = playback.value.copy(positionMs = 19 * MINUTE_MS)
        // However long the room waits, the playhead is what counts.
        advanceTimeBy(60 * MINUTE_MS)
        runCurrent()

        assertEquals(3, sleepTimer.state.value.endOfChapterIndex)
        assertNull(sleepTimer.state.value.remainingMs)
        assertTrue(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `end of chapter fades out as the playhead reaches the end of it`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        // Ten seconds short of the boundary: the fade ends on it rather than starting there.
        playback.value = playback.value.copy(positionMs = 20 * MINUTE_MS - 10_000L)
        advanceTimeBy(FADE_MS)
        runCurrent()

        coVerifyOrder {
            connection.setVolume(0f)
            connection.pause()
            connection.setVolume(1f)
        }
        assertFalse(sleepTimer.state.value.isArmed)
    }

    @Test
    fun `a seek well past the chapter calls the timer off without pausing`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        playback.value = playback.value.copy(positionMs = 35 * MINUTE_MS)
        advanceTimeBy(FADE_MS)
        runCurrent()

        assertFalse(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `another episode calls an end-of-chapter timer off without pausing`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        // Past the boundary as well, which must not matter: it is a position in something else.
        playback.value = playback.value.copy(episodeId = "another", positionMs = 20 * MINUTE_MS)
        advanceTimeBy(FADE_MS)
        runCurrent()

        assertFalse(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `a player that has not connected yet is not a change of episode`() = runTest {
        val playback = playing(positionMs = 0L)
        playback.value = PlaybackState()
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfChapter(chapterIndex = 0, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        assertEquals(0, sleepTimer.state.value.endOfChapterIndex)
    }

    @Test
    fun `the last chapter ends with the episode, so the bell is what is armed`() = runTest {
        playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfChapter(chapterIndex = 7, episodeId = EPISODE, stopAtMs = null)
        runCurrent()

        assertTrue(bell.armed.value)
        assertEquals(7, sleepTimer.state.value.endOfChapterIndex)
        assertFalse(sleepTimer.state.value.isEndOfEpisode)
    }

    @Test
    fun `a bell that has rung leaves nothing armed`() = runTest {
        playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)

        sleepTimer.armEndOfEpisode()
        runCurrent()
        // What the player's own listener does when the episode ends.
        bell.consume()
        runCurrent()
        assertFalse(sleepTimer.state.value.isArmed)

        sleepTimer.armEndOfChapter(chapterIndex = 7, episodeId = EPISODE, stopAtMs = null)
        runCurrent()
        bell.consume()
        runCurrent()
        assertFalse(sleepTimer.state.value.isArmed)
    }

    @Test
    fun `another episode takes the bell back from a last-chapter timer`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 7, episodeId = EPISODE, stopAtMs = null)
        runCurrent()

        playback.value = playback.value.copy(episodeId = "another", positionMs = 0L)
        runCurrent()

        // Otherwise it would ring at the end of an episode nobody set it for.
        assertFalse(bell.armed.value)
        assertFalse(sleepTimer.state.value.isArmed)
    }

    @Test
    fun `a faster speed starts the fade earlier, so that it still ends on the boundary`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        playback.value = playback.value.copy(speed = 2f)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        // Twenty seconds of episode is ten seconds of listening at 2x.
        playback.value = playback.value.copy(positionMs = 20 * MINUTE_MS - 20_000L)
        advanceTimeBy(FADE_MS)
        runCurrent()

        coVerify { connection.pause() }
    }

    @Test
    fun `armed with seconds of the chapter left, the fade is cut to fit`() = runTest {
        playing(positionMs = 20 * MINUTE_MS - 2_000L)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)

        advanceTimeBy(2_500L)
        runCurrent()

        // Two seconds, not ten: the pause belongs on the boundary, not nine seconds past it.
        coVerify { connection.pause() }
    }

    @Test
    fun `a paused player scrubbed to the end of the chapter does not spend the timer`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        playback.value = playback.value.copy(isPlaying = false)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        playback.value = playback.value.copy(positionMs = 20 * MINUTE_MS - 5_000L)
        advanceTimeBy(FADE_MS)
        runCurrent()

        assertTrue(sleepTimer.state.value.isArmed)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `skipping back out of a fade abandons it and keeps the timer`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()
        playback.value = playback.value.copy(positionMs = 20 * MINUTE_MS - 10_000L)
        advanceTimeBy(3_000L)

        playback.value = playback.value.copy(positionMs = 19 * MINUTE_MS)
        advanceTimeBy(FADE_MS)
        runCurrent()

        coVerify(exactly = 0) { connection.pause() }
        coVerify { connection.setVolume(1f) }
        assertEquals(3, sleepTimer.state.value.endOfChapterIndex)
    }

    @Test
    fun `turning the timer off during the fade puts the volume back`() = runTest {
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armAfter(MINUTE_MS)
        // Into the fade, not through it.
        advanceTimeBy(MINUTE_MS + 3_000L)

        sleepTimer.cancel()
        runCurrent()

        coVerify(exactly = 0) { connection.pause() }
        coVerify { connection.setVolume(1f) }
    }

    @Test
    fun `a countdown replaces an end-of-chapter timer`() = runTest {
        val playback = playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        sleepTimer.armAfter(30 * MINUTE_MS)
        runCurrent()
        playback.value = playback.value.copy(positionMs = 20 * MINUTE_MS)
        advanceTimeBy(MINUTE_MS)
        runCurrent()

        assertNull(sleepTimer.state.value.endOfChapterIndex)
        assertEquals(29 * MINUTE_MS, sleepTimer.state.value.remainingMs)
        coVerify(exactly = 0) { connection.pause() }
    }

    @Test
    fun `a shake does nothing to an end-of-chapter timer`() = runTest {
        playing(positionMs = 2 * MINUTE_MS)
        val sleepTimer = timer(backgroundScope)
        sleepTimer.armEndOfChapter(chapterIndex = 3, episodeId = EPISODE, stopAtMs = 20 * MINUTE_MS)
        runCurrent()

        sleepTimer.extend(15 * MINUTE_MS)
        runCurrent()

        assertEquals(SleepTimerState(endOfChapterIndex = 3), sleepTimer.state.value)
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        const val EPISODE = "episode"

        /** Comfortably past the fade, so the pause has certainly happened. */
        const val FADE_MS = 30_000L
    }
}
