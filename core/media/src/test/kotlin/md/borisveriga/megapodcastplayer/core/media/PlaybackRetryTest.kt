package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PlaybackRetryPolicy], [PlaybackRetryListener] and [whileRetrying]: a failed episode is
 * asked for again three times, a second apart and doubling, before the error is let through.
 * Robolectric because a [PlaybackException] reads the system clock and a [MediaItem] holds a Uri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackRetryTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val crashReporter: CrashReporter = mockk(relaxed = true)
    private val error = PlaybackException("refused", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

    private var currentItem: MediaItem? = item("ep-1")
    private var heldError: PlaybackException? = error
    private val player: Player = mockk(relaxed = true) {
        every { currentMediaItem } answers { currentItem }
        every { playerError } answers { heldError }
    }

    private val retryingChanges = mutableListOf<Boolean>()
    private val gaveUp = mutableListOf<PlaybackException>()
    private val listener = PlaybackRetryListener(
        player = player,
        scope = scope,
        crashReporter = crashReporter,
        onRetryingChanged = { retryingChanges += it },
        onGaveUp = { gaveUp += it },
    )

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    // --- the policy -----------------------------------------------------------

    @Test
    fun `the policy waits one, two and four seconds, then gives up`() {
        val policy = PlaybackRetryPolicy()

        assertEquals(listOf(1_000L, 2_000L, 4_000L), (0 until 3).map { policy.delayBeforeRetry(it) })
        assertNull(policy.delayBeforeRetry(3))
    }

    @Test
    fun `a policy of no retries gives up at once`() {
        assertNull(PlaybackRetryPolicy(maxRetries = 0).delayBeforeRetry(0))
    }

    // --- the listener ---------------------------------------------------------

    @Test
    fun `a failure is prepared again after the wait, and not before`() {
        listener.onPlayerError(error)

        scheduler.advanceTimeBy(999L)
        scheduler.runCurrent()
        verify(exactly = 0) { player.prepare() }

        scheduler.advanceTimeBy(1L)
        scheduler.runCurrent()
        verify(exactly = 1) { player.prepare() }
        assertEquals(listOf(true), retryingChanges)
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), error) }
    }

    @Test
    fun `three retries are made, and the fourth failure is given up on`() {
        repeat(3) {
            listener.onPlayerError(error)
            scheduler.advanceUntilIdle()
        }
        verify(exactly = 3) { player.prepare() }
        assertTrue(gaveUp.isEmpty())

        listener.onPlayerError(error)

        assertEquals(listOf(error), gaveUp)
        // Told that nothing is pending before the error is let through, so it is shown.
        assertEquals(listOf(true, false), retryingChanges)
        scheduler.advanceUntilIdle()
        verify(exactly = 3) { player.prepare() }
    }

    @Test
    fun `playing again starts the count over`() {
        repeat(3) {
            listener.onPlayerError(error)
            scheduler.advanceUntilIdle()
        }
        listener.onPlaybackStateChanged(Player.STATE_READY)

        listener.onPlayerError(error)

        assertTrue(gaveUp.isEmpty())
        scheduler.advanceUntilIdle()
        verify(exactly = 4) { player.prepare() }
    }

    @Test
    fun `a new item drops the pending retry and starts the count over`() {
        listener.onPlayerError(error)
        currentItem = item("ep-2")
        listener.onMediaItemTransition(currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        scheduler.advanceUntilIdle()

        verify(exactly = 0) { player.prepare() }
        assertEquals(listOf(true, false), retryingChanges)
    }

    @Test
    fun `an error already cleared by someone else is not prepared again`() {
        listener.onPlayerError(error)
        heldError = null

        scheduler.advanceUntilIdle()

        verify(exactly = 0) { player.prepare() }
        assertEquals(listOf(true, false), retryingChanges)
    }

    // --- how a retrying player is shown -------------------------------------

    private val failed = PlaybackState(errorMessage = "refused", error = PlaybackError.UNKNOWN)

    @Test
    fun `a retrying player is shown buffering, without its error`() {
        val shown = failed.whileRetrying(retrying = true, hasPlayerError = true, commandError = null)

        assertTrue(shown.isBuffering)
        assertNull(shown.error)
        assertNull(shown.errorMessage)
    }

    @Test
    fun `a failed command is still said while retrying`() {
        val shown = failed.whileRetrying(retrying = true, hasPlayerError = true, commandError = "gone")

        assertEquals("gone", shown.errorMessage)
        assertEquals(PlaybackError.UNKNOWN, shown.error)
    }

    @Test
    fun `a retry flag with no error to hold back changes nothing`() {
        val idle = PlaybackState()

        assertSame(idle, idle.whileRetrying(retrying = true, hasPlayerError = false, commandError = null))
        assertFalse(idle.isBuffering)
    }

    @Test
    fun `an error with no retry pending is shown as it is`() {
        assertSame(failed, failed.whileRetrying(retrying = false, hasPlayerError = true, commandError = null))
    }
}
