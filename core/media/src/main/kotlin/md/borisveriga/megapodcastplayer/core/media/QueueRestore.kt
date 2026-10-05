package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.Player

/**
 * Putting the queue back into a player that has none — what a cold start does.
 *
 * The service does this itself, once, when it is created. It used to be the UI's job, done through
 * the controller: read the player, read the database, set the queue paused. Those three steps were
 * not one, and anything that started playback between the first and the last — the widget, a
 * headset, the watch — had its episode replaced by a paused queue. Here the check and the load are
 * one call on the player's own thread, so there is nothing to land in between.
 */

/**
 * Session command that answers once the service has finished restoring the queue, whether or not
 * there was anything to restore. It is how a caller that needs the restored player — the launcher's
 * *Resume* — waits for it instead of racing it.
 */
internal const val SESSION_COMMAND_AWAIT_RESTORE =
    "md.borisveriga.megapodcastplayer.command.AWAIT_RESTORE"

/**
 * Loads [point] into this player if the player holds nothing.
 *
 * A player that already has a queue is left alone: whatever put it there — a system resumption, a
 * command that arrived while the database was being read — is more current than what was stored.
 *
 * Whether the player plays is not touched. Launching the app does not start making noise, and a
 * play request that is already waiting on the player is not cancelled either.
 *
 * The player is not prepared. Preparing opens the stream, and opening the app is not a reason to
 * touch the network: offline, it turned every cold start into an error about an episode nobody had
 * asked to hear. The queue and the position are all the bar needs to draw, the duration comes from
 * the feed until the player has measured one (see [durationToShowMs]), and whatever presses play
 * prepares first — [PlaybackConnection.play] does, and Media3 does for a headset or the
 * notification.
 *
 * Episodes whose stored audio URL fails the scheme allowlist are dropped, and the place in the
 * queue is kept across the drop; see [ResumePoint.keeping].
 *
 * @param point where playback was left.
 * @return true if the queue was loaded.
 */
internal fun Player.restoreIfEmpty(point: ResumePoint): Boolean {
    if (mediaItemCount > 0) return false
    val playable = point.keeping { it.hasPlayableAudio }
    val items = playable.queue.mapNotNull { it.toMediaItemOrNull() }
    if (items.isEmpty()) return false
    setMediaItems(items, playable.index, playable.positionMs.coerceAtLeast(0L))
    return true
}

/**
 * The duration to draw for the loaded episode.
 *
 * The player's own measurement once it has one, since feeds routinely misreport theirs. Until then
 * — a restored episode that has not been prepared, or one still opening its stream — the feed's
 * figure, so the scrubber shows where the user is instead of sitting empty and disabled.
 *
 * @param measuredMs what the player measured, or null before it has.
 * @param publishedMs what the feed said, carried on the item's metadata; null when it said nothing.
 * @return the duration in milliseconds, or `0` when neither is known.
 */
internal fun durationToShowMs(measuredMs: Long?, publishedMs: Long?): Long =
    measuredMs ?: publishedMs?.takeIf { it > 0L } ?: 0L
