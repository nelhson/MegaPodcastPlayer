package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
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
 * Tests for the two flavours of a YouTube episode and the swap between them.
 *
 * The swap is the part worth pinning: the order of its three playlist calls is what keeps the
 * position, and the idempotence of the mode changes is what keeps a rotation from costing a
 * re-buffer. Robolectric only because [MediaItem] holds its URI as an `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoModeTest {

    private fun item(uri: String, id: String = "ep-1"): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("A talk").setArtist("A show").build())
        .build()

    private val audio = item(youTubeAudioSentinel(VIDEO_ID))
    private val video720 = item("youtube://video-only/$VIDEO_ID?h=720")
    private val feed = item("https://cdn.example.com/episode-42.mp3")

    // --- flavours -----------------------------------------------------------

    @Test
    fun `the video flavour keeps the id and the metadata and changes only the uri`() {
        val flavour = checkNotNull(audio.toVideoFlavour(VideoQuality(1080)))

        assertEquals("ep-1", flavour.mediaId)
        assertEquals("A talk", flavour.mediaMetadata.title)
        assertEquals("youtube://video-only/$VIDEO_ID?h=1080", flavour.localConfiguration?.uri.toString())
        assertEquals(VideoQuality(1080), flavour.videoQualityOrNull)
        assertTrue(flavour.isVideoFlavour)
    }

    @Test
    fun `the audio flavour is the stored sentinel`() {
        val flavour = video720.toAudioFlavour()

        assertEquals(youTubeAudioSentinel(VIDEO_ID), flavour.localConfiguration?.uri.toString())
        assertFalse(flavour.isVideoFlavour)
        assertNull(flavour.videoQualityOrNull)
    }

    @Test
    fun `a feed episode has one flavour`() {
        assertNull(feed.toVideoFlavour(VideoQuality.DEFAULT))
        assertSame(feed, feed.toAudioFlavour())
        assertNull(feed.youTubeVideoId)
    }

    @Test
    fun `either flavour names its video`() {
        assertEquals(VIDEO_ID, audio.youTubeVideoId)
        assertEquals(VIDEO_ID, video720.youTubeVideoId)
    }

    // --- the swap -----------------------------------------------------------

    @Test
    fun `a swap adds behind, crosses over, then removes`() {
        // Never replaceMediaItem: removing the item under the playhead sends ExoPlayer to the
        // *start* of what follows. Crossing first is what keeps the position.
        val player = playerAt(index = 1, positionMs = 42_000L)

        player.swapCurrentItem(video720)

        verifyOrder {
            player.addMediaItem(2, video720)
            player.seekTo(2, 42_000L)
            player.removeMediaItem(1)
        }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun `a swap re-prepares a player that had stopped on an error`() {
        val player = playerAt(index = 0, positionMs = 0L, state = Player.STATE_IDLE)
        every { player.playerError } returns mockk()

        player.swapCurrentItem(video720)

        verify(exactly = 1) { player.prepare() }
    }

    @Test
    fun `a swap does not prepare a restored player that was never started`() {
        // Idle because the queue was put back without opening a stream. Reopening the app on the
        // video screen swaps to the picture's flavour, and that must not be what touches the network.
        val player = playerAt(index = 0, positionMs = 42_000L, state = Player.STATE_IDLE)
        every { player.playerError } returns null

        player.swapCurrentItem(video720)

        verify(exactly = 1) { player.seekTo(1, 42_000L) }
        verify(exactly = 0) { player.prepare() }
    }

    // --- entering and leaving -----------------------------------------------

    @Test
    fun `entering video swaps the audio item for the rendition asked`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = audio)
        val added = slot<MediaItem>()
        every { player.addMediaItem(any(), capture(added)) } returns Unit

        assertEquals(VideoModeOutcome.SWAPPED, player.enterVideoMode(VideoQuality(720)))
        assertEquals(VideoQuality(720), added.captured.videoQualityOrNull)
        assertEquals("ep-1", added.captured.mediaId)
    }

    @Test
    fun `entering video for the rendition already showing touches nothing`() {
        // A rotation recreates the screen, which asks again. Asking must be free.
        val player = playerAt(index = 0, positionMs = 5_000L, current = video720)

        assertEquals(VideoModeOutcome.UNCHANGED, player.enterVideoMode(VideoQuality(720)))
        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    @Test
    fun `entering video at another rendition swaps again`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = video720)
        val added = slot<MediaItem>()
        every { player.addMediaItem(any(), capture(added)) } returns Unit

        assertEquals(VideoModeOutcome.SWAPPED, player.enterVideoMode(VideoQuality(1080)))
        assertEquals(VideoQuality(1080), added.captured.videoQualityOrNull)
    }

    @Test
    fun `a picture asked for an episode the player has left is not shown on its successor`() {
        // The ask is decided, then sent; the queue can move on in between.
        val player = playerAt(index = 0, positionMs = 5_000L, current = audio)

        assertEquals(
            VideoModeOutcome.SUPERSEDED,
            player.enterVideoMode(VideoQuality(720), expectedEpisodeId = "another-episode"),
        )
        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    @Test
    fun `a picture asked for the episode playing is shown`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = audio)

        assertEquals(
            VideoModeOutcome.SWAPPED,
            player.enterVideoMode(VideoQuality(720), expectedEpisodeId = "ep-1"),
        )
    }

    @Test
    fun `a feed episode cannot enter video`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = feed)

        assertEquals(VideoModeOutcome.NOT_YOUTUBE, player.enterVideoMode(VideoQuality.DEFAULT))
        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    @Test
    fun `with nothing loaded there is nothing to enter or leave`() {
        val player = playerAt(index = 0, positionMs = 0L, current = null)

        assertEquals(VideoModeOutcome.NOTHING_LOADED, player.enterVideoMode(VideoQuality.DEFAULT))
        assertEquals(VideoModeOutcome.NOTHING_LOADED, player.exitVideoMode())
    }

    @Test
    fun `leaving video swaps back to the stored sentinel`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = video720)
        val added = slot<MediaItem>()
        every { player.addMediaItem(any(), capture(added)) } returns Unit

        assertEquals(VideoModeOutcome.SWAPPED, player.exitVideoMode())
        assertEquals(youTubeAudioSentinel(VIDEO_ID), added.captured.localConfiguration?.uri.toString())
    }

    @Test
    fun `leaving video from audio touches nothing`() {
        val player = playerAt(index = 0, positionMs = 5_000L, current = audio)

        assertEquals(VideoModeOutcome.UNCHANGED, player.exitVideoMode())
        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    @Test
    fun `leaving video turns every item left in video back to sound`() {
        // The queue moved on while the screen was up: the episode before still shows a picture.
        val earlier = item("youtube://video-only/aaaaaaaaaaa?h=720", id = "ep-0")
        val player = playerAt(index = 1, positionMs = 5_000L, current = video720)
        every { player.mediaItemCount } returns 3
        every { player.getMediaItemAt(0) } returns earlier
        every { player.getMediaItemAt(1) } returns video720
        every { player.getMediaItemAt(2) } returns feed
        val replaced = slot<MediaItem>()
        every { player.replaceMediaItem(0, capture(replaced)) } returns Unit

        assertEquals(VideoModeOutcome.SWAPPED, player.exitVideoMode())

        assertEquals(youTubeAudioSentinel("aaaaaaaaaaa"), replaced.captured.localConfiguration?.uri.toString())
        assertEquals("ep-0", replaced.captured.mediaId)
        // The current item keeps its swap, for the position; the feed episode is left alone.
        verify(exactly = 0) { player.replaceMediaItem(1, any()) }
        verify(exactly = 0) { player.replaceMediaItem(2, any()) }
        verify(exactly = 1) { player.addMediaItem(2, any<MediaItem>()) }
    }

    @Test
    fun `leaving from sound still clears a video item left behind`() {
        val earlier = item("youtube://video-only/aaaaaaaaaaa?h=720", id = "ep-0")
        val player = playerAt(index = 1, positionMs = 5_000L, current = audio)
        every { player.mediaItemCount } returns 2
        every { player.getMediaItemAt(0) } returns earlier
        every { player.getMediaItemAt(1) } returns audio

        assertEquals(VideoModeOutcome.SWAPPED, player.exitVideoMode())
        verify(exactly = 1) { player.replaceMediaItem(0, any()) }
        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    /** A relaxed player standing at [index] and [positionMs], with [current] loaded. */
    private fun playerAt(
        index: Int,
        positionMs: Long,
        current: MediaItem? = audio,
        state: Int = Player.STATE_READY,
    ): Player = mockk(relaxed = true) {
        every { currentMediaItemIndex } returns index
        every { currentPosition } returns positionMs
        every { currentMediaItem } returns current
        every { playbackState } returns state
    }

    private companion object {
        const val VIDEO_ID = "niTJ2221aS8"
    }
}
