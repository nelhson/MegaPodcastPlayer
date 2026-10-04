package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [restoreIfEmpty], the service's cold-start queue restore.
 *
 * Two rules matter: the queue comes back on the episode that was playing, and a player that
 * already holds something is left exactly as it is — which is what keeps a restore from replacing
 * what a widget, a headset or the watch has just started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueRestoreTest {

    private val player: Player = mockk(relaxed = true)

    private fun playable(
        id: String,
        positionMs: Long = 0L,
        audioUrl: String = "https://cdn.example.com/$id.mp3",
    ) = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = audioUrl,
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = null,
            positionMs = positionMs,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    private val point = ResumePoint(
        queue = listOf(playable("a", positionMs = 9_000L), playable("b"), playable("c")),
        index = 1,
        positionMs = 42_000L,
    )

    @Test
    fun `an empty player gets the queue back on the episode that was playing`() {
        every { player.mediaItemCount } returns 0
        val items = slot<List<MediaItem>>()

        assertTrue(player.restoreIfEmpty(point))

        verify { player.setMediaItems(capture(items), 1, 42_000L) }
        assertEquals(listOf("a", "b", "c"), items.captured.map { it.mediaId })
    }

    @Test
    fun `restoring does not decide whether the player plays`() {
        every { player.mediaItemCount } returns 0

        player.restoreIfEmpty(point)

        // Launching the app must not start making noise, and a play that is already waiting on
        // the player must not be cancelled.
        verify(exactly = 0) { player.playWhenReady = any() }
        verify(exactly = 0) { player.play() }
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `restoring does not open the stream`() {
        every { player.mediaItemCount } returns 0

        player.restoreIfEmpty(point)

        // Preparing is what reaches for the network, and opening the app is not a reason to.
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `the duration shown is the player's once it has measured one`() {
        assertEquals(61_000L, durationToShowMs(measuredMs = 61_000L, publishedMs = 60_000L))
    }

    @Test
    fun `the duration shown is the feed's until the player has measured one`() {
        assertEquals(60_000L, durationToShowMs(measuredMs = null, publishedMs = 60_000L))
    }

    @Test
    fun `no duration is shown when neither is known`() {
        assertEquals(0L, durationToShowMs(measuredMs = null, publishedMs = null))
        assertEquals(0L, durationToShowMs(measuredMs = null, publishedMs = 0L))
    }

    @Test
    fun `a player that already has a queue is left alone`() {
        // Something got there first — a system resumption, the widget — and it is more current
        // than what was stored.
        every { player.mediaItemCount } returns 2

        assertFalse(player.restoreIfEmpty(point))

        verify(exactly = 0) { player.setMediaItems(any(), any<Int>(), any()) }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `nothing stored means nothing is loaded`() {
        every { player.mediaItemCount } returns 0

        assertFalse(player.restoreIfEmpty(ResumePoint.EMPTY))

        verify(exactly = 0) { player.setMediaItems(any(), any<Int>(), any()) }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `an episode that may not be played is dropped without losing the place`() {
        every { player.mediaItemCount } returns 0
        val items = slot<List<MediaItem>>()
        val withUnplayableHead = point.copy(
            queue = listOf(playable("a", audioUrl = "file:///data/secret"), playable("b"), playable("c")),
        )

        assertTrue(player.restoreIfEmpty(withUnplayableHead))

        verify { player.setMediaItems(capture(items), 0, 42_000L) }
        assertEquals(listOf("b", "c"), items.captured.map { it.mediaId })
    }
}
