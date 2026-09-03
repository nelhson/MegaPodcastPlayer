package md.borisveriga.megapodcastplayer.wear.ui

import java.util.Locale

/**
 * Formats a playback position for a watch face-sized label, as `m:ss` or `h:mm:ss`.
 *
 * The hour field is dropped below an hour rather than padded to `0:04:12`, because horizontal space
 * is the scarcest thing on this screen and a leading zero buys nothing.
 *
 * @param millis the position; negatives are treated as zero, which is what a not-yet-known position
 *   reads as.
 */
internal fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Formats a remaining duration for a corner of a watch face, as `48m` or `1h20`.
 *
 * A complication gets about four characters, so this is deliberately not [formatPlaybackTime]:
 * seconds are dropped, the hour and minute run together without a separator, and a whole hour loses
 * its minutes entirely. What survives is the only thing the number is for — whether the episode
 * outlasts the walk.
 *
 * Anything still running rounds *up* to one minute rather than down to zero: "0m" on an episode that
 * is still playing reads as a bug, and the last minute is over soon enough to forgive the rounding.
 *
 * @param millis how much is left; negatives are treated as nothing left.
 */
internal fun formatCompactRemaining(millis: Long): String {
    if (millis <= 0L) return "0m"

    val totalMinutes = ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR

    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> String.format(Locale.US, "%dh%02d", hours, minutes)
    }
}

/** Rounding up to the next whole minute needs the length of one. */
private const val MILLIS_PER_MINUTE = 60_000L

private const val MINUTES_PER_HOUR = 60L
