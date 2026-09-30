package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeAnyVideoIdOrNull
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlyRefOrNull
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlySentinel

/**
 * The two flavours of a YouTube episode — sound, or sound and picture — and how the player moves
 * between them.
 *
 * An episode is one `mediaId` whatever it looks like. What changes is the URI on its [MediaItem]:
 * the stored `youtube://video/<id>` sentinel plays as audio, and the in-flight
 * `youtube://video-only/<id>?h=<height>` sentinel plays as a picture merged with that same audio
 * (see `YouTubeMediaSourceFactory`). Everything that persists — positions, the queue, the last
 * played episode — keys off the `mediaId`, which is why a swap is invisible to all of it.
 */

/** Session command asking the service to show the picture of the episode playing. */
internal const val SESSION_COMMAND_ENTER_VIDEO =
    "md.borisveriga.megapodcastplayer.command.ENTER_VIDEO"

/** Session command asking the service to go back to sound only. */
internal const val SESSION_COMMAND_EXIT_VIDEO =
    "md.borisveriga.megapodcastplayer.command.EXIT_VIDEO"

/** Integer argument of [SESSION_COMMAND_ENTER_VIDEO]: the rendition height wanted. */
internal const val EXTRA_VIDEO_HEIGHT = "height"

/** The YouTube video id behind this item, whichever flavour it is in, or null for a feed episode. */
val MediaItem.youTubeVideoId: String?
    get() = localConfiguration?.uri?.toString()?.let(::youTubeAnyVideoIdOrNull)

/** The rendition this item shows, or null when it plays as sound only. */
val MediaItem.videoQualityOrNull: VideoQuality?
    get() = localConfiguration?.uri?.toString()?.let(::youTubeVideoOnlyRefOrNull)?.quality

/** Whether this item plays sound and picture rather than sound alone. */
val MediaItem.isVideoFlavour: Boolean
    get() = videoQualityOrNull != null

/**
 * This episode as sound and picture at [quality].
 *
 * Same `mediaId`, same metadata; only the URI changes, so the notification, the lock screen and
 * every persistence listener see the same episode before and after.
 *
 * @param quality the rendition height to ask the resolver for.
 * @return the video flavour, or null when this is not a YouTube episode and has no picture to show.
 */
fun MediaItem.toVideoFlavour(quality: VideoQuality): MediaItem? {
    val videoId = youTubeVideoId ?: return null
    return buildUpon().setUri(youTubeVideoOnlySentinel(videoId, quality)).build()
}

/**
 * This episode as sound only: the flavour that is stored, downloaded and resumed.
 *
 * @return the audio flavour; a feed episode, which has only one flavour, is returned unchanged.
 */
fun MediaItem.toAudioFlavour(): MediaItem {
    val videoId = youTubeVideoId ?: return this
    return buildUpon().setUri(youTubeAudioSentinel(videoId)).build()
}

/** What a request to change flavour did. */
enum class VideoModeOutcome {
    /** The current item was replaced by the requested flavour at the same position. */
    SWAPPED,

    /** The current item was already in the requested flavour; nothing was touched. */
    UNCHANGED,

    /** The current item is a feed episode with no picture to show. */
    NOT_YOUTUBE,

    /** Nothing is loaded. */
    NOTHING_LOADED,
}

/**
 * Shows the picture of the episode playing, at [quality].
 *
 * Idempotent on purpose: a rotation recreates the screen that asks, and asking twice for the same
 * rendition must not cost a second re-buffer.
 *
 * @param quality the rendition height.
 * @return what was done.
 */
internal fun Player.enterVideoMode(quality: VideoQuality): VideoModeOutcome {
    val current = currentMediaItem ?: return VideoModeOutcome.NOTHING_LOADED
    if (current.videoQualityOrNull == quality) return VideoModeOutcome.UNCHANGED
    val flavour = current.toVideoFlavour(quality) ?: return VideoModeOutcome.NOT_YOUTUBE
    swapCurrentItem(flavour)
    return VideoModeOutcome.SWAPPED
}

/**
 * Goes back to sound only, which is where every episode starts and where playback continues once
 * the video screen is gone.
 *
 * Every item in the playlist, not only the current one. While the screen is up the queue moves on
 * — an episode ends, the user presses next — and the item left behind stays in video flavour.
 * Played again later from the mini player it would stream a picture nobody sees, and a downloaded
 * episode would fail offline unless its picture had been downloaded too. Items other than the current
 * one are replaced in place: the playhead is not on them, so there is no position to keep.
 *
 * @return what was done; a playlist already all sound reads as [VideoModeOutcome.UNCHANGED].
 */
internal fun Player.exitVideoMode(): VideoModeOutcome {
    val current = currentMediaItem ?: return VideoModeOutcome.NOTHING_LOADED
    val currentIndex = currentMediaItemIndex
    var changed = false
    for (index in 0 until mediaItemCount) {
        val item = getMediaItemAt(index)
        if (index != currentIndex && item.isVideoFlavour) {
            replaceMediaItem(index, item.toAudioFlavour())
            changed = true
        }
    }
    if (current.isVideoFlavour) {
        swapCurrentItem(current.toAudioFlavour())
        changed = true
    }
    return if (changed) VideoModeOutcome.SWAPPED else VideoModeOutcome.UNCHANGED
}

/**
 * Replaces the item playing with [replacement], keeping the position and the rest of the queue.
 *
 * Add after, seek across, remove behind — in that order, and never `replaceMediaItem`. When the
 * item under the playhead is removed ExoPlayer moves to the *default* position of whatever follows,
 * which is the start; done this way the playhead has already crossed to the replacement, at its
 * own position, before anything is removed, so nothing is lost and no listener sees a jump to zero.
 * The two timeline changes it causes both list the same episode, which is why
 * [PlaybackPersistenceListener] de-duplicates the queue it mirrors.
 *
 * @param replacement the item to put in the current one's place.
 */
internal fun Player.swapCurrentItem(replacement: MediaItem) {
    val index = currentMediaItemIndex
    val position = currentPosition.coerceAtLeast(0L)
    addMediaItem(index + 1, replacement)
    seekTo(index + 1, position)
    removeMediaItem(index)
    // A player that had stopped on an error needs preparing again before the replacement loads.
    if (playbackState == Player.STATE_IDLE) prepare()
}
