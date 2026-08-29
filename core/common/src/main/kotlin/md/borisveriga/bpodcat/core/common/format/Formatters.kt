package md.borisveriga.bpodcat.core.common.format

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Day-and-month format for episodes published in the current year, e.g. `24 Aug`. */
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/** Full format for older episodes, e.g. `24 Aug 2024`. */
private val LONG_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

/**
 * Formats an episode duration compactly.
 *
 * Podcast episodes run from a few minutes to several hours, so seconds are never shown: the shortest
 * useful unit is a minute, and anything under one minute rounds up to `1 min`.
 *
 * @param durationMs duration in milliseconds; null or non-positive returns `null`.
 * @return e.g. `1 h 23 min`, `45 min`, or null when the duration is unknown.
 */
fun formatDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}

/**
 * Formats how much of an episode is left to play.
 *
 * @param durationMs total duration, null when unknown.
 * @param positionMs current playback position.
 * @return e.g. `12 min left`, or null when the duration is unknown or the episode is finished.
 */
fun formatRemaining(durationMs: Long?, positionMs: Long): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val remaining = durationMs - positionMs
    if (remaining <= 0L) return null
    return formatDuration(remaining)?.let { "$it left" }
}

/**
 * Formats a publication date the way a person reads an episode list: recent items relatively,
 * older ones by date.
 *
 * @param instant the publication instant; null returns null.
 * @param now reference point, injected so this is testable without freezing the system clock.
 * @param zone time zone to render in; defaults to the device's.
 * @return e.g. `Today`, `Yesterday`, `3 days ago`, `24 Aug`, `24 Aug 2024`, or null.
 */
fun formatPublishedDate(
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
        daysAgo < 0L -> "Today"
        daysAgo == 0L -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo < 7L -> "$daysAgo days ago"
        date.year == today.year -> date.format(SHORT_DATE)
        else -> date.format(LONG_DATE)
    }
}

/**
 * Formats a byte count for the storage screen.
 *
 * @param bytes size in bytes.
 * @return e.g. `1.2 GB`, `340 MB`, `0 MB` for anything under a megabyte.
 */
fun formatBytes(bytes: Long): String {
    val megabytes = bytes / 1_000_000.0
    return if (megabytes >= 1000) {
        String.format(Locale.getDefault(), "%.1f GB", megabytes / 1000)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", megabytes)
    }
}

/** Formats a playback position as `h:mm:ss` or `m:ss`, for the player's scrubber labels. */
fun formatPosition(positionMs: Long): String {
    val duration = Duration.ofMillis(positionMs.coerceAtLeast(0L))
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
