package md.borisveriga.megapodcastplayer.core.common.format

import android.content.res.Resources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import md.borisveriga.megapodcastplayer.core.common.R
import md.borisveriga.megapodcastplayer.core.model.format.formatTimecode

// The words these formatters say are resources, and every one of them takes a Resources to say them
// with. They used to be Kotlin literals — "1 h 23 min", "12 min left", "Today", "3 days ago",
// "340 MB" — while every other word in the app was a resource, and those literals sit on the same
// line as words that are: an episode row reads "24 Aug · 1 h 23 min · 90 MB". A translation would
// have produced a screen half in one language and half in English.
//
// Resources rather than a Context, because that is what a caller actually has: a composable reads
// LocalResources.current, which invalidates on a configuration change, so a locale switch redraws
// these along with everything else. The locale is read from it per call for the same reason — a
// formatter initialised at class load freezes whichever locale the process started in.

/**
 * The locale to format numbers and dates in.
 *
 * Taken from the [Resources] rather than from [Locale.getDefault], so it follows the same
 * configuration the strings beside it come from. Per call, never cached.
 */
private val Resources.locale: Locale get() = configuration.locales[0]

/**
 * Formats an episode duration compactly.
 *
 * Podcast episodes run from a few minutes to several hours, so seconds are never shown: the shortest
 * useful unit is a minute, and anything under one minute rounds up to `1 min`.
 *
 * @param resources where the unit abbreviations come from.
 * @param durationMs duration in milliseconds; null or non-positive returns `null`.
 * @return e.g. `1 h 23 min`, `45 min`, or null when the duration is unknown.
 */
fun formatDuration(resources: Resources, durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    val hoursText = resources.getQuantityString(R.plurals.common_duration_hours, hours, hours)
    val minutesText = resources.getQuantityString(R.plurals.common_duration_minutes, minutes, minutes)
    return when {
        hours > 0 && minutes > 0 ->
            resources.getString(R.string.common_duration_hours_minutes, hoursText, minutesText)

        hours > 0 -> hoursText

        else -> minutesText
    }
}

/**
 * Formats how much of an episode is left to play.
 *
 * @param resources where the wording comes from.
 * @param durationMs total duration, null when unknown.
 * @param positionMs current playback position.
 * @return e.g. `12 min left`, or null when the duration is unknown or the episode is finished.
 */
fun formatRemaining(resources: Resources, durationMs: Long?, positionMs: Long): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val remaining = durationMs - positionMs
    if (remaining <= 0L) return null
    return formatDuration(resources, remaining)
        ?.let { resources.getString(R.string.common_duration_remaining, it) }
}

/**
 * Formats a publication date the way a person reads an episode list: recent items relatively,
 * older ones by date.
 *
 * The relative half is a set of strings and a plural, so a language with more than two plural forms
 * can say "3 days ago" properly. The absolute half is left to [DateTimeFormatter], which already
 * knows every locale's month names and field order — `24 Aug` is `24 авг.` without anyone writing
 * it down.
 *
 * @param resources where the relative wording and the locale come from.
 * @param instant the publication instant; null returns null.
 * @param now reference point, injected so this is testable without freezing the system clock.
 * @param zone time zone to render in; defaults to the device's.
 * @return e.g. `Today`, `Yesterday`, `3 days ago`, `24 Aug`, `24 Aug 2024`, or null.
 */
fun formatPublishedDate(
    resources: Resources,
    instant: Instant?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (instant == null) return null

    val date = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(date, today)

    return when {
        // A feed with a slightly-future timestamp is common; treat it as "just published".
        daysAgo <= 0L -> resources.getString(R.string.common_date_today)

        daysAgo == 1L -> resources.getString(R.string.common_date_yesterday)

        daysAgo < DAYS_SHOWN_RELATIVELY -> resources.getQuantityString(
            R.plurals.common_date_days_ago,
            daysAgo.toInt(),
            daysAgo.toInt(),
        )

        date.year == today.year ->
            date.format(DateTimeFormatter.ofPattern(SHORT_DATE_PATTERN, resources.locale))

        else -> date.format(DateTimeFormatter.ofPattern(LONG_DATE_PATTERN, resources.locale))
    }
}

/** Beyond a week, "6 days ago" stops being easier to read than the date itself. */
private const val DAYS_SHOWN_RELATIVELY = 7L

/** Day-and-month pattern for episodes published in the current year, e.g. `24 Aug`. */
private const val SHORT_DATE_PATTERN = "d MMM"

/** Full pattern for older episodes, e.g. `24 Aug 2024`. */
private const val LONG_DATE_PATTERN = "d MMM yyyy"

/**
 * Formats a byte count for the storage screen.
 *
 * @param resources where the unit and the locale come from.
 * @param bytes size in bytes.
 * @return e.g. `1.2 GB`, `340 MB`, `0 MB` for anything under a megabyte.
 */
fun formatBytes(resources: Resources, bytes: Long): String {
    val megabytes = bytes / 1_000_000.0
    return if (megabytes >= 1000) {
        resources.getString(
            R.string.common_size_gigabytes,
            String.format(resources.locale, "%.1f", megabytes / 1000),
        )
    } else {
        resources.getString(
            R.string.common_size_megabytes,
            String.format(resources.locale, "%.0f", megabytes),
        )
    }
}

/**
 * Formats a playback position as `h:mm:ss` or `m:ss`, for the player's scrubber labels.
 *
 * Delegates to `:core:model` rather than repeating the arithmetic: the same string is written into
 * a shared moment and an exported document, from a pure module the sharing code can reach, and two
 * implementations of it would drift the way [formatSpeed]'s three copies did.
 */
fun formatPosition(positionMs: Long): String = formatTimecode(positionMs)

/**
 * Formats what is left of an episode as a counting-down timecode, e.g. `-30:06`.
 *
 * Distinct from [formatRemaining], which rounds to whole minutes and reads as prose (`12 min left`)
 * — right for a list row, wrong beside a scrubber, where the number ticks once a second and has to
 * line up with the elapsed timecode on the other side of the bar. Both are kept: they answer the
 * same question at two different resolutions.
 *
 * A finished episode returns `-0:00` rather than null. The label is one half of a pair whose widths
 * hold the layout apart, so it has to say something at every position; the elapsed side is showing
 * the full duration at that moment, which is what makes the zero read correctly.
 *
 * @param durationMs total duration, null when the player has not read it yet.
 * @param positionMs current playback position.
 * @return e.g. `-30:06`, or null when the duration is unknown.
 */
fun formatCountdown(durationMs: Long?, positionMs: Long): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return COUNTDOWN_SIGN + formatTimecode(remaining)
}

/** The minus in front of a countdown; ASCII, so it keeps the numeric style's tabular width. */
private const val COUNTDOWN_SIGN = "-"

/**
 * The wall-clock time an episode will finish at, given the speed it is playing at.
 *
 * The one number about an episode that answers a question about the *evening* rather than about the
 * episode: whether it finishes before the train arrives, or before you have to leave. Every other
 * label on the player says how long is left, which is the same fact in a unit nobody plans in.
 *
 * The speed is not optional to the arithmetic and is easy to forget: forty minutes left at 1.75x is
 * twenty-three minutes of clock, and an "ends at" that ignored the speed would be wrong for every
 * user of this app, most of whom do not listen at 1x.
 *
 * Rendered in the device's own 12- or 24-hour convention, unlike the timecodes, which are numeric
 * formats rather than copy.
 *
 * @param remainingMs how much of the episode is left, or null when the duration is unknown.
 * @param speed the playback rate; a non-positive value is treated as 1x rather than dividing by zero.
 * @param now the reference point, injected so this is testable without freezing the system clock.
 * @param zone the time zone to render in; defaults to the device's.
 * @param locale the clock convention to render in — 12- or 24-hour — likewise injected, because a
 *   test that took the machine's own would pass in London and fail in New York.
 * @return e.g. `22:41`, or null when there is nothing to project from.
 */
fun formatEndsAt(
    remainingMs: Long?,
    speed: Float,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    if (remainingMs == null || remainingMs <= 0L) return null
    val rate = speed.takeIf { it > 0f } ?: 1f
    val wallClockMs = (remainingMs / rate).toLong()
    // Built per call rather than held as a file-level value: this is the one formatter here whose
    // convention is the *user's* rather than the app's, and a value initialised at class-load
    // freezes whichever locale the process started in.
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(now.plusMillis(wallClockMs).atZone(zone))
}

/**
 * Formats a playback rate as `1x`, `1.5x` or `1.75x`.
 *
 * Trailing zeros are trimmed: `1x` reads as a setting, `1.00x` reads as a measurement.
 *
 * The digits use [Locale.US] rather than the device locale, deliberately: these captions sit in
 * fixed-width controls, and a comma decimal separator on one screen next to a point on another is
 * how the same speed comes to look like two different settings.
 *
 * There were three copies of this before it lived here — the player's, the settings screen's and
 * the watch's — and they did not agree: one rounded before formatting and one did not, so 1.005
 * came out as `1.01x` in one place and `1x` in another.
 *
 * @param speed the playback rate.
 * @return e.g. `1x`, `1.5x`, `1.75x`.
 */
fun formatSpeed(speed: Float): String {
    val digits = String.format(Locale.US, "%.2f", speed)
        .trimEnd('0')
        .trimEnd('.')
    return digits + SPEED_SUFFIX
}

/** The multiplier mark after a playback rate; not translated, like `x` in `1.5x`. */
private const val SPEED_SUFFIX = "x"
