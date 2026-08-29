package md.borisveriga.bpodcat.wear.ui

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
 * Formats a playback speed as `1x`, `1.5x` or `1.75x`.
 *
 * Trailing zeros are trimmed: `1x` reads as a setting, `1.00x` reads as a measurement.
 *
 * @param speed the playback rate.
 */
internal fun formatSpeed(speed: Float): String {
    val digits = String.format(Locale.US, "%.2f", speed)
        .trimEnd('0')
        .trimEnd('.')
    return digits + "x"
}
