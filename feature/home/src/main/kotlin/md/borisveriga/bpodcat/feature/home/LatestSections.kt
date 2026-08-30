package md.borisveriga.bpodcat.feature.home

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * The buckets the Latest feed groups episodes into.
 *
 * Coarse on purpose. A feed that labels every day separately turns into a list of headers, and the
 * question the screen answers is "what is new", not "what came out on the 14th".
 */
enum class LatestSection {
    /** Published today, or with a slightly-future timestamp, which feeds do produce. */
    TODAY,

    /** Yesterday. Kept separate because "yesterday" is the other date people reason about. */
    YESTERDAY,

    /** Within the last week. */
    THIS_WEEK,

    /** Everything older. */
    EARLIER,
}

/**
 * One section of the feed.
 *
 * @property section which bucket this is.
 * @property episodes the episodes in it, newest first.
 */
data class LatestGroup(
    val section: LatestSection,
    val episodes: List<EpisodeWithShow>,
)

/**
 * Groups episodes into date sections, preserving the newest-first order within each.
 *
 * Pure, and deliberately not a composable or a ViewModel method: date bucketing is the one piece of
 * logic on this screen that can be wrong in a way nobody notices until a specific hour of a
 * specific day, so it is written where it can be tested against a fixed clock.
 *
 * The day boundaries match `formatPublishedDate` in `:core:common` exactly — same
 * [ChronoUnit.DAYS] comparison of local dates, same treatment of future timestamps as today. If
 * they drifted apart, a row labelled "Yesterday" could sit under a "Today" header, which reads as a
 * bug even though both halves are individually defensible.
 *
 * Episodes with no publication date are dropped rather than bucketed. The query that feeds this
 * already excludes them; the guard here means the function is total for any input.
 *
 * @param episodes the feed, already ordered newest first.
 * @param now the current instant, injected so the grouping is testable.
 * @param zone the time zone whose calendar days define the buckets.
 * @return one group per non-empty section, in [LatestSection] order.
 */
fun groupByRecency(
    episodes: List<EpisodeWithShow>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LatestGroup> {
    val today = now.atZone(zone).toLocalDate()

    val bucketed = episodes
        .mapNotNull { entry ->
            val publishedAt = entry.episode.publishedAt ?: return@mapNotNull null
            val daysAgo = ChronoUnit.DAYS.between(publishedAt.atZone(zone).toLocalDate(), today)
            sectionFor(daysAgo) to entry
        }
        .groupBy({ it.first }, { it.second })

    // Iterate the enum rather than the map so section order is the enum's, not insertion order —
    // a feed that happens to start with a week-old episode must not put THIS_WEEK first.
    return LatestSection.entries.mapNotNull { section ->
        bucketed[section]?.let { LatestGroup(section, it) }
    }
}

/**
 * Maps a whole-day offset onto a section.
 *
 * A negative offset means the feed published a timestamp in the future, which is common enough to
 * be worth handling explicitly: those episodes are the newest thing there is, so they belong at the
 * top under Today, not in a bucket of their own.
 */
private fun sectionFor(daysAgo: Long): LatestSection = when {
    daysAgo <= 0L -> LatestSection.TODAY
    daysAgo == 1L -> LatestSection.YESTERDAY
    daysAgo < DAYS_IN_WEEK -> LatestSection.THIS_WEEK
    else -> LatestSection.EARLIER
}

private const val DAYS_IN_WEEK = 7L
