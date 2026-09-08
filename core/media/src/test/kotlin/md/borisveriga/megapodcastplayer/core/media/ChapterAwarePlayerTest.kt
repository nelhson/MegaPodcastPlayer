package md.borisveriga.megapodcastplayer.core.media

import android.os.Looper
import androidx.media3.common.Player
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [ChapterAwarePlayer].
 *
 * This class exists so that one press of *next* on a lock screen means the same thing as one press
 * of *next* in the app, and the only way to see that is to press it: each test drives the wrapper
 * through the public `Player` API a notification button would, and asserts what reached the player
 * underneath. The two facts worth pinning are that a seek stayed *inside* the episode when it
 * should have, and that the command was offered at all — a chaptered episode alone in the queue is
 * exactly the case where the wrapped player says there is no next and the wrapper must disagree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterAwarePlayerTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 60_000L, title = "The interview"),
        Chapter(startMs = 600_000L, title = "Picks"),
    )

    @Test
    fun `next seeks to the next chapter without leaving the episode`() {
        val wrapped = FakePlayer(itemCount = 2, positionMs = 30_000L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        player.seekToNext()

        assertEquals(listOf(Seek(mediaItemIndex = 0, positionMs = 60_000L)), wrapped.contentSeeks)
        assertEquals(emptyList<Int>(), wrapped.mediaItemSeeks)
    }

    @Test
    fun `next in the last chapter moves to the next episode`() {
        val wrapped = FakePlayer(itemCount = 2, positionMs = 700_000L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        player.seekToNext()

        assertEquals(listOf(1), wrapped.mediaItemSeeks)
        assertEquals(emptyList<Seek>(), wrapped.contentSeeks)
    }

    @Test
    fun `previous restarts the chapter being played`() {
        val wrapped = FakePlayer(itemCount = 2, mediaItemIndex = 1, positionMs = 90_000L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        player.seekToPrevious()

        assertEquals(listOf(Seek(mediaItemIndex = 1, positionMs = 60_000L)), wrapped.contentSeeks)
    }

    @Test
    fun `previous just after a boundary steps back a chapter`() {
        val wrapped = FakePlayer(itemCount = 2, mediaItemIndex = 1, positionMs = 61_000L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        player.seekToPrevious()

        assertEquals(listOf(Seek(mediaItemIndex = 1, positionMs = 0L)), wrapped.contentSeeks)
    }

    @Test
    fun `next is offered for a chaptered episode the wrapped player has nothing after`() {
        val wrapped = FakePlayer(itemCount = 1, positionMs = 0L)
        val player = ChapterAwarePlayer(wrapped)

        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        player.setChapters(chapters)

        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `a new chapter list announces the commands it changed`() {
        val wrapped = FakePlayer(itemCount = 1, positionMs = 0L)
        val player = ChapterAwarePlayer(wrapped)
        // Reading the state once is what the session does the moment it is handed the player;
        // without it the first invalidation has no earlier state to differ from.
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        val announced = mutableListOf<Player.Commands>()
        player.addListener(
            object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    announced += availableCommands
                }
            },
        )

        player.setChapters(chapters)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, announced.size)
        assertTrue(announced.single().contains(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `the last chapter withdraws next once the ticker refreshes`() {
        val wrapped = FakePlayer(itemCount = 1, positionMs = 0L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        wrapped.moveTo(700_000L)
        player.refreshChapterCommands()

        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `without chapters next is the next episode`() {
        val wrapped = FakePlayer(itemCount = 2, positionMs = 30_000L)
        val player = ChapterAwarePlayer(wrapped)

        player.seekToNext()

        assertEquals(listOf(1), wrapped.mediaItemSeeks)
        assertEquals(emptyList<Seek>(), wrapped.contentSeeks)
    }

    @Test
    fun `chapters are dropped when the episode they belonged to is replaced`() {
        val wrapped = FakePlayer(itemCount = 2, positionMs = 30_000L)
        val player = ChapterAwarePlayer(wrapped).apply { setChapters(chapters) }

        player.setChapters(emptyList())
        player.seekToNext()

        assertEquals(listOf(1), wrapped.mediaItemSeeks)
    }
}
