package md.borisveriga.megapodcastplayer.core.model

/**
 * What a download swipe on a show's episode list fetches.
 *
 * Two answers rather than three. A video on its own is not a thing this app can keep: the picture
 * plays merged with the episode's sound, so a downloaded video with its audio still on the network
 * does not play offline, and the video download fetches the audio with it for exactly that reason.
 * "Video" and "audio and video" would put the same files on the phone under two names.
 *
 * Only a YouTube episode has a picture. Any other episode is downloaded as audio whichever is set.
 */
enum class SwipeDownload {

    /** The episode's sound only: the small file, and what an RSS episode always is. */
    AUDIO,

    /** The sound and, on a YouTube episode, the picture at the user's chosen quality. */
    AUDIO_AND_VIDEO,

    ;

    companion object {
        /** Audio, which is what the swipe did before there was a choice, and the cheaper of the two. */
        val DEFAULT: SwipeDownload = AUDIO
    }
}
