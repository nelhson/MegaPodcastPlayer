package md.borisveriga.megapodcastplayer.core.model

/**
 * The height of a video stream, which is the one number a user recognises a quality by.
 *
 * Only the height is kept. YouTube publishes each rendition by height (`720p`, `1080p`), the picker
 * lists heights, and the sentinel that asks the resolver for a video stream carries a height; see
 * [youTubeVideoOnlySentinel]. Width follows from the video's own aspect ratio and codec details are
 * the resolver's business.
 *
 * @property height the frame height in pixels; always positive.
 */
data class VideoQuality(val height: Int) {

    init {
        require(height > 0) { "a video quality needs a positive height, not $height" }
    }

    companion object {
        /**
         * The quality asked for until the user picks one.
         *
         * 720p is the floor that reads as "sharp" on a phone held at arm's length, and the highest
         * rendition that every YouTube upload of the last decade offers. It is a preference, not a
         * requirement: a video that has nothing this tall plays at the best it has.
         */
        val DEFAULT: VideoQuality = VideoQuality(height = 720)

        /**
         * Below this, a rendition is not offered automatically.
         *
         * When the preferred height is missing the resolver looks first at what is at least this
         * tall, and only when a video has nothing that tall does it settle for less.
         */
        const val PREFERRED_MIN_HEIGHT: Int = 720
    }
}
