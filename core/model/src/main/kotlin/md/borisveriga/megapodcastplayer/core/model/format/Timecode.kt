package md.borisveriga.megapodcastplayer.core.model.format

import java.util.Locale

/**
 * Parses a timecode into milliseconds.
 *
 * Accepts the shapes chapter sources actually publish: `MM:SS`, `HH:MM:SS`, and either with a
 * fractional seconds part — `00:00:00.000` from Podlove, `12:34.5` from a hand-written description.
 *
 * `parseItunesDurationMs` is deliberately *not* reused for this. It discards zero, because a
 * duration of zero is meaningless, whereas a chapter starting at `0:00` is the normal case and the
 * one every well-formed chapter list opens with.
 *
 * @param raw the timecode as published.
 * @return the offset in milliseconds, or null when [raw] is not a timecode. Minute and second
 *   fields outside `00`–`59` are rejected rather than carried, because a value like `99:99` is a
 *   mis-parse of something else rather than a very long chapter.
 */
fun parseTimecodeMs(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val groups = TIMECODE.matchEntire(trimmed)?.groupValues ?: return null

    val minutesValue = groups[GROUP_MINUTES].toLong()
    val secondsValue = groups[GROUP_SECONDS].toLong()
    if (minutesValue >= SEXAGESIMAL || secondsValue >= SEXAGESIMAL) return null

    val hoursValue = groups[GROUP_HOURS].ifEmpty { "0" }.toLong()
    val fractionMs = groups[GROUP_FRACTION].ifEmpty { "0" }
        .padEnd(FRACTION_DIGITS, '0')
        .take(FRACTION_DIGITS)
        .toLong()

    return ((hoursValue * SEXAGESIMAL + minutesValue) * SEXAGESIMAL + secondsValue) *
        MILLIS_PER_SECOND + fractionMs
}

/**
 * `[HH:]MM:SS[.fff]`.
 *
 * The hour group is optional and the fraction may be any number of digits; both are normalised by
 * the caller rather than by the pattern.
 */
private val TIMECODE = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?""")

/** Capture group holding the optional hours field. */
private const val GROUP_HOURS = 1

/** Capture group holding the minutes field. */
private const val GROUP_MINUTES = 2

/** Capture group holding the seconds field. */
private const val GROUP_SECONDS = 3

/** Capture group holding the optional fractional-seconds field. */
private const val GROUP_FRACTION = 4

/** Minutes in an hour, and seconds in a minute. */
private const val SEXAGESIMAL = 60L

/** Milliseconds in a second. */
private const val MILLIS_PER_SECOND = 1_000L

/** Digits in the millisecond part, which shorter fractions are padded out to. */
private const val FRACTION_DIGITS = 3

/**
 * Renders a position as a timecode a person reads back.
 *
 * The inverse of [parseTimecodeMs] for the shapes that matter, and it lives here for that reason:
 * the two must agree, and the only way to guarantee that is to let one file own both. `h:mm:ss`
 * once there is an hour to show and `m:ss` below that — an episode is rarely long enough to earn a
 * leading `0:` and a moment written `0:12:23` reads as a duration rather than a place.
 *
 * Digits are [Locale.US] deliberately. These strings go into a shared note and an exported
 * document, where a reader in another locale — or the same reader six months later — has to be able
 * to type them back into a player.
 *
 * @param positionMs the offset in milliseconds; negative values clamp to zero.
 * @return e.g. `12:23` or `1:04:07`.
 */
fun formatTimecode(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L)) / MILLIS_PER_SECOND
    val hours = totalSeconds / (SEXAGESIMAL * SEXAGESIMAL)
    val minutes = (totalSeconds / SEXAGESIMAL) % SEXAGESIMAL
    val seconds = totalSeconds % SEXAGESIMAL
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Whole seconds of a position, which is the unit every timestamped link takes.
 *
 * @param positionMs the offset in milliseconds; negative values clamp to zero.
 * @return the offset in seconds, truncated.
 */
fun positionSeconds(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
