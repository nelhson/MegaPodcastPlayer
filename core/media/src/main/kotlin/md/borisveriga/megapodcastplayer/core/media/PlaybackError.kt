package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.PlaybackException

/**
 * Why playback stopped, in terms a screen can turn into a sentence.
 *
 * The player used to show *Playback problem: <exception message>*, which is a string written by
 * ExoPlayer for a developer reading a bug report — "Source error", "Response code: 404",
 * "androidx.media3.exoplayer.ExoPlaybackException". None of it says what happened or what to do,
 * and three of the four common causes have something the user can actually do about them.
 *
 * A closed set rather than pre-worded text, for the same reason `SearchError` is one: a module with
 * no `Context` should not be choosing copy, and the same failure is worded differently on a phone
 * and on a watch.
 */
enum class PlaybackError {

    /** The device could not reach the host at all — almost always no connection. */
    NO_CONNECTION,

    /** The host answered, and said the file is not there. A feed that moved its audio. */
    EPISODE_GONE,

    /** The bytes arrived and nothing on this device can play them. */
    UNSUPPORTED_FORMAT,

    /** A YouTube-sourced episode whose audio URL could not be resolved or has expired. */
    YOUTUBE_UNAVAILABLE,

    /** Everything else; the screen shows the player's own words alongside. */
    UNKNOWN,
}

/**
 * Classifies a playback failure.
 *
 * By error code rather than by message, because the codes are the part of `PlaybackException` that
 * is API: the messages are free text and change between Media3 releases.
 *
 * YouTube is checked first and separately, because a YouTube episode fails through the *same* codes
 * as everything else — its audio URL is resolved at play time and expires, so a stale one comes back
 * as a bad HTTP status like any other dead link. What the user can do about it is different, though:
 * there is nothing wrong with their connection and nothing wrong with the show, and the app can
 * simply try again.
 *
 * @param exception what the player reported, or null when nothing failed.
 * @param isYouTube whether the episode playing is YouTube-sourced.
 * @return the classified reason, or null when [exception] is null.
 */
fun playbackErrorOf(exception: PlaybackException?, isYouTube: Boolean): PlaybackError? {
    if (exception == null) return null
    if (isYouTube) return PlaybackError.YOUTUBE_UNAVAILABLE

    return when (exception.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> PlaybackError.NO_CONNECTION

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> PlaybackError.EPISODE_GONE

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> PlaybackError.UNSUPPORTED_FORMAT

        else -> PlaybackError.UNKNOWN
    }
}
