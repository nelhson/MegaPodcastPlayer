package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [EndOfEpisodeBellListener].
 *
 * The listener decides two things a refactor could quietly flip, and both are the difference
 * between a bell that works and one that is worse than useless: which player callback means "the
 * episode ended on its own", and whether the queue is stopped when it does. A user tapping "next"
 * must not ring anything, and an automatic transition must not be allowed to keep playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndOfEpisodeBellListenerTest {

    private val player: Player = mockk(relaxed = true)
    private val bell = EpisodeEndBell()
    private val ringer = RecordingBellRinger()

    /** Unconfined, so a launched ring has happened by the time the callback returns. */
    private val listener = EndOfEpisodeBellListener(
        player = player,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        bell = bell,
        ringer = ringer,
    )

    @Test
    fun `an automatic transition rings the bell and stops the queue`() {
        bell.arm()

        listener.onPositionDiscontinuity(
            positionInfo("ep-1", title = "The one about ovens"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertEquals(listOf("The one about ovens"), ringer.rung)
        // The next episode has already started by the time this callback arrives; a user who asked
        // to be woken at the end of one episode has not asked to be read the rest of their queue.
        verify(exactly = 1) { player.pause() }
    }

    @Test
    fun `the last episode ending rings the bell`() {
        bell.arm()
        every { player.currentMediaItem } returns mediaItem("ep-3", title = "The last one")

        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals(listOf("The last one"), ringer.rung)
    }

    @Test
    fun `a disarmed bell rings nothing and leaves playback alone`() {
        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertTrue(ringer.rung.isEmpty())
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `the user skipping ahead rings nothing`() {
        // "Next" arrives as a seek, not as an automatic transition. Ringing here would wake someone
        // who was demonstrably awake enough to press a button.
        bell.arm()

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_SEEK,
        )

        assertTrue(ringer.rung.isEmpty())
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `becoming ready rings nothing`() {
        bell.arm()
        every { player.currentMediaItem } returns mediaItem("ep-3")

        listener.onPlaybackStateChanged(Player.STATE_READY)

        assertTrue(ringer.rung.isEmpty())
    }

    @Test
    fun `the bell rings once even when both signals arrive`() {
        bell.arm()
        every { player.currentMediaItem } returns mediaItem("ep-2")

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assertEquals(1, ringer.rung.size)
    }

    @Test
    fun `the next episode ending does not ring a bell that already fired`() {
        bell.arm()

        listener.onPositionDiscontinuity(
            positionInfo("ep-1"),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        listener.onPositionDiscontinuity(
            positionInfo("ep-2"),
            positionInfo("ep-3"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertEquals(1, ringer.rung.size)
    }

    @Test
    fun `an episode with no title still rings`() {
        // Nothing here is worth swallowing a bell for; the notification has its own wording.
        bell.arm()

        listener.onPositionDiscontinuity(
            positionInfo("ep-1", title = null),
            positionInfo("ep-2"),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        assertEquals(listOf<String?>(null), ringer.rung)
    }

    private fun mediaItem(episodeId: String, title: String? = "Episode $episodeId"): MediaItem =
        MediaItem.Builder()
            .setMediaId(episodeId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

    private fun positionInfo(
        episodeId: String,
        title: String? = "Episode $episodeId",
    ): Player.PositionInfo = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ 0,
        mediaItem(episodeId, title),
        /* periodUid = */ null,
        /* periodIndex = */ 0,
        /* positionMs = */ 0L,
        /* contentPositionMs = */ 0L,
        /* adGroupIndex = */ C.INDEX_UNSET,
        /* adIndexInAdGroup = */ C.INDEX_UNSET,
    )

    /** A [BellRinger] that keeps the title of everything it was asked to ring for. */
    private class RecordingBellRinger : BellRinger {
        val rung = mutableListOf<String?>()

        override suspend fun ring(episodeTitle: String?) {
            rung += episodeTitle
        }
    }
}
