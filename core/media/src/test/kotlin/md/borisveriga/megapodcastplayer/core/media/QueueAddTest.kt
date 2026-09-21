package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [enqueueEpisode]: what "add to queue" does to a player, for each place the episode may already be.
 *
 * The case this exists for is the one in the middle: an episode queued behind the one playing is
 * listed nowhere, so swiping it into the queue used to say "Queued" and change nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueAddTest {

    /**
     * A player holding [ids], with [current] loaded.
     *
     * @param ids the queued episodes, in play order.
     * @param current the index of the loaded one.
     * @param started whether it has been played at all.
     */
    private fun player(ids: List<String>, current: Int = 0, started: Boolean = true): Player =
        mockk(relaxed = true) {
            every { mediaItemCount } returns ids.size
            ids.forEachIndexed { index, id ->
                every { getMediaItemAt(index) } returns item(id)
            }
            every { currentMediaItemIndex } returns current
            every { isPlaying } returns false
            every { currentPosition } returns if (started) 90_000L else 0L
        }

    /** A media item for [id], as [toMediaItemOrNull] would key it. */
    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun `an episode that is not queued is appended`() {
        val player = player(listOf("a", "b"))

        assertEquals(QueueAddResult.ADDED, player.enqueueEpisode("c") { item("c") })

        verify(exactly = 1) { player.addMediaItem(any<MediaItem>()) }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `the first episode into an empty player is prepared, ready for the first tap`() {
        val player = mockk<Player>(relaxed = true)
        // Empty when searched, one item once added.
        every { player.mediaItemCount } returnsMany listOf(0, 1)

        assertEquals(QueueAddResult.ADDED, player.enqueueEpisode("a") { item("a") })

        verify(exactly = 1) { player.prepare() }
    }

    /** The bug: queued, invisible, and until now impossible to queue again. */
    @Test
    fun `an episode left behind the one playing is moved to the end`() {
        val player = player(listOf("a", "b", "c"), current = 1)

        assertEquals(QueueAddResult.MOVED_TO_END, player.enqueueEpisode("a") { item("a") })

        verify(exactly = 1) { player.moveMediaItem(0, 2) }
        verify(exactly = 0) { player.addMediaItem(any<MediaItem>()) }
    }

    @Test
    fun `an episode already waiting is left where it is, and says so`() {
        val player = player(listOf("a", "b", "c"), current = 0)

        assertEquals(QueueAddResult.ALREADY_QUEUED, player.enqueueEpisode("c") { item("c") })

        verify(exactly = 0) { player.moveMediaItem(any(), any()) }
        verify(exactly = 0) { player.addMediaItem(any<MediaItem>()) }
    }

    @Test
    fun `the episode playing is left alone, and says so`() {
        val player = player(listOf("a", "b"), current = 0, started = true)

        assertEquals(QueueAddResult.ALREADY_PLAYING, player.enqueueEpisode("a") { item("a") })
    }

    /** Queueing into an empty player loads the episode; the queue screen lists it as waiting. */
    @Test
    fun `the loaded episode that was never started counts as queued, not playing`() {
        val player = player(listOf("a"), current = 0, started = false)

        assertEquals(QueueAddResult.ALREADY_QUEUED, player.enqueueEpisode("a") { item("a") })
    }

    @Test
    fun `an episode the player may not be handed is refused, and nothing is added`() {
        val player = player(listOf("a"))

        assertEquals(QueueAddResult.UNPLAYABLE, player.enqueueEpisode("x") { null })

        verify(exactly = 0) { player.addMediaItem(any<MediaItem>()) }
    }
}
