package md.borisveriga.megapodcastplayer.core.media

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource

/** Tag for the one thing this file logs: a playback failure, with its cause. */
private const val TAG = "PlaybackPersistence"

/**
 * Mirrors what the player does into durable storage.
 *
 * Installed on the [androidx.media3.exoplayer.ExoPlayer] that [PlaybackService] owns. It lives
 * outside the service so that the rules it encodes — which discontinuity counts as "finished",
 * when a position is worth flushing, which timeline change is a queue edit — can be tested against
 * a stubbed [Player] without standing up a Hilt-injected service.
 *
 * Every callback arrives on the player's thread, and every write is launched into [scope] rather
 * than performed inline, because the recorder touches the database and the callback must return
 * promptly.
 *
 * @property player the player being observed; callbacks that need more than their arguments (the
 *   queue, the current position) read it directly.
 * @property scope where the writes run; the service cancels it when the player is released.
 * @property progressRecorder receives positions, completions and the queue.
 * @property userPreferences receives the id of the episode most recently loaded.
 */
internal class PlaybackPersistenceListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val progressRecorder: PlaybackProgressRecorder,
    private val userPreferences: UserPreferencesDataSource,
) : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        scope.launch { userPreferences.setLastPlayedEpisodeId(mediaItem?.episodeId) }
    }

    // [Player.PositionInfo.mediaItem] is still marked unstable, and it is the only way to learn
    // which item was playing *before* an automatic transition; the opt-in is scoped to this one
    // callback so the rest of the listener stays on the stable surface. It has to be the androidx
    // `OptIn`: `UnstableApi` is an `androidx.annotation.RequiresOptIn` marker, which the compiler
    // ignores and lint enforces, and lint does not recognise Kotlin's own `@OptIn` for it.
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // An automatic transition is the only discontinuity that means "the previous episode
        // finished". A seek, or a user tapping "next", must not mark anything played.
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        val finishedId = oldPosition.mediaItem?.episodeId ?: return
        scope.launch { progressRecorder.recordCompleted(finishedId) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // STATE_ENDED is the last episode in the queue finishing; earlier ones arrive as an
        // automatic transition instead.
        if (playbackState != Player.STATE_ENDED) return
        val episodeId = player.currentMediaItem?.episodeId ?: return
        scope.launch { progressRecorder.recordCompleted(episodeId) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Pausing is the moment the user is most likely to close the app, so flush now instead of
        // waiting up to five seconds for the service's ticker.
        if (isPlaying) return
        val reading = player.positionReading() ?: return
        scope.launch { reading.recordInto(progressRecorder) }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val episodeIds = (0 until player.mediaItemCount)
            .mapNotNull { index -> player.getMediaItemAt(index).episodeId }
        scope.launch { progressRecorder.recordQueue(episodeIds) }
    }

    override fun onPlayerError(error: PlaybackException) {
        // The controller surfaces this to the user; log it where the cause is readable.
        Log.w(TAG, "Playback failed for ${player.currentMediaItem?.episodeId}", error)
    }
}

/**
 * One position reading, detached from the player it came from.
 *
 * Detached so that [PlaybackService.onDestroy] can read *before* releasing the player and write
 * afterwards, on a scope that survives the service.
 *
 * @property episodeId the episode the position belongs to.
 * @property positionMs how far into it playback had reached.
 * @property durationMs the decoder's measured duration, or null before it knows one.
 */
internal data class PositionReading(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
) {
    /**
     * Writes this reading through [recorder].
     *
     * @param recorder the persistence sink, normally the data layer's implementation.
     */
    suspend fun recordInto(recorder: PlaybackProgressRecorder) {
        recorder.recordPosition(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }
}

/**
 * Takes a position reading off this player, without writing it.
 *
 * @return the reading, or null when no episode is loaded, which is not an error.
 */
internal fun Player.positionReading(): PositionReading? {
    val episodeId = currentMediaItem?.episodeId ?: return null
    return PositionReading(
        episodeId = episodeId,
        positionMs = currentPosition.coerceAtLeast(0L),
        // Feeds routinely misreport itunes:duration, so prefer what the decoder measured — but
        // only once it knows.
        durationMs = duration.takeIf { it != C.TIME_UNSET && it > 0L },
    )
}
