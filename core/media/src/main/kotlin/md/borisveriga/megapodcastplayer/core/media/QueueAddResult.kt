package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * What asking for an episode to be queued actually did.
 *
 * A result rather than a `Boolean` because "it is in the queue now" has more than one way of being
 * true, and the screens used to say "Queued" for all of them — including the ones where nothing the
 * user can see had changed. The queue keeps the episodes *before* the one playing (a tapped row, or
 * "next", leaves them there), and the queue screen lists only what comes after it; so an episode
 * can be queued, invisible, and impossible to queue again.
 */
enum class QueueAddResult {
    /** The episode was not queued, and now is: at the end. */
    ADDED,

    /**
     * The episode was queued *behind* the one playing, where nothing lists it, and was moved to the
     * end. To the user this is an add, and is reported as one.
     */
    MOVED_TO_END,

    /** The episode is already waiting to play; nothing was changed. */
    ALREADY_QUEUED,

    /** The episode is the one playing; nothing was changed. */
    ALREADY_PLAYING,

    /** The episode's audio URL is not one the player may be handed; see [toMediaItemOrNull]. */
    UNPLAYABLE,

    /** The player could not be reached, so nothing is known about what it holds. */
    UNREACHABLE,
}

/**
 * Appends an episode to this player's queue, or brings it back into view if it is already there.
 *
 * Apart from [PlaybackConnection.addToQueue] so that every branch can be tested without a bound
 * service, as [applyDeviceVolume] is.
 *
 * @param episodeId the episode's id, which is its media id.
 * @param item builds the media item; only called when one has to be added, and null when the
 *   episode may not be played.
 * @return what was done.
 */
internal fun Player.enqueueEpisode(episodeId: String, item: () -> MediaItem?): QueueAddResult {
    val index = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == episodeId }
    if (index == null) {
        addMediaItem(item() ?: return QueueAddResult.UNPLAYABLE)
        // A queue added to while the player is empty should be ready to play on the first tap.
        if (mediaItemCount == 1) prepare()
        return QueueAddResult.ADDED
    }
    val current = currentMediaItemIndex
    return when {
        index > current -> QueueAddResult.ALREADY_QUEUED

        // Loaded but never started — which is what queueing into an empty player leaves behind —
        // is an episode waiting to play, and the queue screen lists it as one.
        index == current -> if (isPlaying || currentPosition > 0L) {
            QueueAddResult.ALREADY_PLAYING
        } else {
            QueueAddResult.ALREADY_QUEUED
        }

        else -> {
            // Behind the playing episode: still queued, listed nowhere. The service mirrors the
            // move into the database, exactly as it does a drag.
            moveMediaItem(index, mediaItemCount - 1)
            QueueAddResult.MOVED_TO_END
        }
    }
}
