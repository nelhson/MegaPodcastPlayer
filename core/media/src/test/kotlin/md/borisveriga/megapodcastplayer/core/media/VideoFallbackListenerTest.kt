package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [VideoFallbackListener]: a failed picture hands back to sound, a failed sound is left
 * alone. Robolectric only because [MediaItem] holds its URI as an `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoFallbackListenerTest {

    private val crashReporter: CrashReporter = mockk(relaxed = true)
    private val error = PlaybackException("no picture", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun item(uri: String): MediaItem = MediaItem.Builder().setMediaId("ep-1").setUri(uri).build()

    /** A relaxed, idle player on one item, [current]. */
    private fun playerOn(current: MediaItem): Player = mockk(relaxed = true) {
        every { currentMediaItem } returns current
        every { currentMediaItemIndex } returns 0
        every { mediaItemCount } returns 1
        every { getMediaItemAt(0) } returns current
        every { playbackState } returns Player.STATE_IDLE
    }

    @Test
    fun `a failed picture goes back to sound and is recorded`() {
        val player = playerOn(item("youtube://video-only/$VIDEO_ID?h=720"))
        val added = slot<MediaItem>()
        every { player.addMediaItem(any(), capture(added)) } returns Unit

        VideoFallbackListener(player, crashReporter).onPlayerError(error)

        assertEquals(youTubeAudioSentinel(VIDEO_ID), added.captured.localConfiguration?.uri.toString())
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), error) }
    }

    @Test
    fun `a failed sound is left alone`() {
        val player = playerOn(item(youTubeAudioSentinel(VIDEO_ID)))

        VideoFallbackListener(player, crashReporter).onPlayerError(error)

        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
        verify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    // --- a picture that loads and cannot be decoded -------------------------

    /**
     * The tracks of an item with one video track, which this device can or cannot decode.
     *
     * @param supported whether the device has a decoder for it.
     */
    private fun videoTracks(supported: Boolean): Tracks {
        val format = Format.Builder().setSampleMimeType("video/av01").build()
        val support = if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
        return Tracks(
            listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(support), booleanArrayOf(supported))),
        )
    }

    @Test
    fun `a picture the phone cannot decode goes back to sound and is recorded`() {
        // No error is raised for this: the track is left unselected and the sound plays on under
        // a frame that never arrives.
        val player = playerOn(item("youtube://video-only/$VIDEO_ID?h=720"))
        val added = slot<MediaItem>()
        every { player.addMediaItem(any(), capture(added)) } returns Unit

        VideoFallbackListener(player, crashReporter).onTracksChanged(videoTracks(supported = false))

        assertEquals(youTubeAudioSentinel(VIDEO_ID), added.captured.localConfiguration?.uri.toString())
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a picture the phone can decode is left playing`() {
        val player = playerOn(item("youtube://video-only/$VIDEO_ID?h=720"))

        VideoFallbackListener(player, crashReporter).onTracksChanged(videoTracks(supported = true))

        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
    }

    @Test
    fun `tracks not read yet are not a verdict`() {
        // The list is empty between the swap to video and the source being read.
        val player = playerOn(item("youtube://video-only/$VIDEO_ID?h=720"))

        VideoFallbackListener(player, crashReporter).onTracksChanged(Tracks.EMPTY)

        verify(exactly = 0) { player.addMediaItem(any(), any<MediaItem>()) }
        verify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    private companion object {
        const val VIDEO_ID = "niTJ2221aS8"
    }
}
