package md.borisveriga.megapodcastplayer.core.media

import android.media.MediaCodecList
import android.media.MediaFormat
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.youtube.VideoDecoderCheck

/**
 * Answers [VideoDecoderCheck] from the platform's own list of codecs.
 *
 * Lives here rather than beside the resolver that asks, because what the device can decode is the
 * player's concern; `:core:youtube` only knows what a video is offered in.
 */
@Singleton
internal class DeviceVideoDecoders @Inject constructor() : VideoDecoderCheck {

    /**
     * The codecs an app is expected to use, read once: the list does not change while the process
     * lives, and building it walks every codec on the device.
     */
    private val codecs: MediaCodecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    /**
     * Whether the device lists a decoder for a 16:9 picture of this type and height.
     *
     * The width is derived rather than asked for, because every YouTube rendition this app plays
     * is 16:9 and a decoder's limits are stated as a frame size, not a height.
     *
     * @param mimeType the picture's MIME type.
     * @param height the frame height in pixels.
     * @return true when a decoder is listed for it.
     */
    override fun canDecode(mimeType: String, height: Int): Boolean {
        val width = height * WIDE_NUMERATOR / WIDE_DENOMINATOR
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        return codecs.findDecoderForFormat(format) != null
    }

    private companion object {
        /** Sixteen… */
        const val WIDE_NUMERATOR = 16

        /** …by nine. */
        const val WIDE_DENOMINATOR = 9
    }
}
