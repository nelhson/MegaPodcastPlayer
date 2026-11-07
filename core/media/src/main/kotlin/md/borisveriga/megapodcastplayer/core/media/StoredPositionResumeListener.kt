package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching

/** One message, fixed, so the failures group into one report; see [CrashReporter]. */
private const val NON_FATAL_RESUME = "Stored position lookup on transition failed"

/**
 * Picks an episode up where it was left when the player moves onto it by itself.
 *
 * A tapped episode resumes because `EpisodePlayer.play` reads its stored position and seeks there.
 * Nothing did the same for the other ways an episode comes up: the one before it finishing, the
 * next and previous buttons in the app, the notification, a headset or the watch, or the one
 * before it being marked played or swiped out of the queue. Media3 starts a
 * media item at its default position, which for a podcast file is the first second, and so a
 * half-heard episode added to the queue began again from the top. This listener is the missing half.
 *
 * It watches [onPositionDiscontinuity] rather than `onMediaItemTransition` because the discontinuity
 * carries the exact position the player landed at, and that is the whole test: an episode that was
 * *sent* to a position (`seekTo(index, positionMs)`, which is what `playNow` does) must be left there,
 * while one that landed at the default start was given no position at all and gets its stored one.
 * The stored position is read from the database at that moment, not carried on the media item: an
 * item's metadata is built when it is queued, and an episode listened to and left since has moved
 * on from it.
 *
 * The read is asynchronous — a database lookup cannot run on the player's thread — so the first
 * fraction of a second plays from the start before the seek lands. That is also why the seek is
 * conditional: by the time the answer arrives the player must still be on the same episode, and
 * must not already be past where it is about to be sent.
 *
 * @property player the player being observed, and the one seeked.
 * @property scope where the lookup runs; the service cancels it when the player is released.
 * @property queueSource where stored positions come from.
 * @property crashReporter where a failed lookup goes, since nothing on screen is waiting for one.
 */
internal class StoredPositionResumeListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val queueSource: PlaybackQueueSource,
    private val crashReporter: CrashReporter,
) : Player.Listener {

    // Same opt-in, and the same reason, as PlaybackPersistenceListener: `PositionInfo.mediaItem` is
    // still unstable, and it is how the episode landed on is identified. Scoped to this callback.
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!isArrivalAtDefaultStart(oldPosition, newPosition, reason)) return
        val episodeId = newPosition.mediaItem?.episodeId ?: return
        scope.launch {
            suspendRunCatching { resume(episodeId) }
                .onFailure { failure -> crashReporter.recordNonFatal(NON_FATAL_RESUME, failure) }
        }
    }

    /**
     * Seeks to [episodeId]'s stored position, if it has one and the player is still at its start.
     *
     * A stored position of zero — never started, finished, or marked unplayed — is left alone: the
     * player is already there. So is a player that has moved on to another episode while the
     * database was answering, or one that is already past the stored position, which means
     * something else — the user, most likely — got there first.
     *
     * @param episodeId the episode the player landed on.
     */
    private suspend fun resume(episodeId: String) {
        val storedPositionMs = queueSource.playableEpisodes(listOf(episodeId))
            .firstOrNull()?.episode?.positionMs ?: return
        if (storedPositionMs <= 0L) return
        if (player.currentMediaItem?.episodeId != episodeId) return
        if (player.currentPosition >= storedPositionMs) return
        player.seekTo(storedPositionMs)
    }
}

/**
 * Whether a discontinuity is the player arriving at another episode's default start.
 *
 * True for an automatic transition, which always lands at the start, and for a seek or a removal
 * that changed episode and landed at zero: `seekToNextMediaItem` and its kin pass no position, and
 * so does removing the episode playing — marking it played from the sheet, or swiping it out of the
 * queue — after which the player moves on to the next one; in both the player fills in the default.
 * A change of episode that landed elsewhere was given that position on purpose, which covers
 * `seekTo(index, positionMs)` and a queue set with a start position. A discontinuity within the
 * same episode is not an arrival at all.
 *
 * @param oldPosition where playback was.
 * @param newPosition where it landed.
 * @param reason the player's reason for the discontinuity.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun isArrivalAtDefaultStart(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
): Boolean = when (reason) {
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> true

    Player.DISCONTINUITY_REASON_SEEK,
    Player.DISCONTINUITY_REASON_REMOVE,
    ->
        oldPosition.mediaItem?.episodeId != newPosition.mediaItem?.episodeId &&
            newPosition.positionMs == 0L

    else -> false
}
