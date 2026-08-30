package md.borisveriga.bpodcat.feature.home

import java.time.Instant
import java.time.ZoneId
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [groupByRecency].
 *
 * Date bucketing is the piece of this screen most able to be quietly wrong: an off-by-one at a day
 * boundary is invisible except at a particular hour, and a time zone assumption is invisible except
 * to someone in a different one. Both are pinned here against a fixed instant rather than left to be
 * noticed in use.
 *
 * All times are expressed in a fixed zone so the assertions mean the same thing wherever the test
 * runs — with `ZoneId.systemDefault()` the day boundaries would move with the machine.
 */
class LatestSectionsTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    /** Mid-afternoon, so "today" has hours on either side of it to test against. */
    private val now: Instant = Instant.parse("2026-08-30T14:00:00Z")

    @Test
    fun `an episode from earlier today lands under today`() {
        val groups = group("2026-08-30T02:00:00Z")

        assertEquals(listOf(LatestSection.TODAY), groups.map { it.section })
    }

    /**
     * Feeds do publish timestamps a little in the future — a scheduled post, or a publisher in a
     * zone ahead of the reader. Those episodes are the newest thing there is, so they belong at the
     * top under Today rather than in a bucket of their own or dropped.
     */
    @Test
    fun `a future-dated episode lands under today`() {
        val groups = group("2026-08-31T09:00:00Z")

        assertEquals(listOf(LatestSection.TODAY), groups.map { it.section })
    }

    @Test
    fun `the boundary between today and yesterday is the calendar day, not 24 hours`() {
        // 23:30 the previous evening is 14.5 hours ago, but it is a different calendar day.
        val groups = group("2026-08-29T23:30:00Z")

        assertEquals(listOf(LatestSection.YESTERDAY), groups.map { it.section })
    }

    @Test
    fun `two days ago is this week`() {
        val groups = group("2026-08-28T14:00:00Z")

        assertEquals(listOf(LatestSection.THIS_WEEK), groups.map { it.section })
    }

    @Test
    fun `six days ago is still this week`() {
        val groups = group("2026-08-24T14:00:00Z")

        assertEquals(listOf(LatestSection.THIS_WEEK), groups.map { it.section })
    }

    @Test
    fun `seven days ago is earlier`() {
        val groups = group("2026-08-23T14:00:00Z")

        assertEquals(listOf(LatestSection.EARLIER), groups.map { it.section })
    }

    /**
     * Sections come out in enum order regardless of what the feed happens to start with. A feed
     * whose newest episode is a week old must not put "This week" above "Today" once something
     * newer arrives.
     */
    @Test
    fun `sections are ordered newest first regardless of input order`() {
        val groups = groupByRecency(
            episodes = listOf(
                entry("old", "2026-08-01T14:00:00Z"),
                entry("today", "2026-08-30T10:00:00Z"),
                entry("week", "2026-08-27T10:00:00Z"),
                entry("yesterday", "2026-08-29T10:00:00Z"),
            ),
            now = now,
            zone = zone,
        )

        assertEquals(
            listOf(
                LatestSection.TODAY,
                LatestSection.YESTERDAY,
                LatestSection.THIS_WEEK,
                LatestSection.EARLIER,
            ),
            groups.map { it.section },
        )
    }

    @Test
    fun `episodes keep their order within a section`() {
        val groups = groupByRecency(
            episodes = listOf(
                entry("newer", "2026-08-30T12:00:00Z"),
                entry("older", "2026-08-30T06:00:00Z"),
            ),
            now = now,
            zone = zone,
        )

        assertEquals(listOf("newer", "older"), groups.single().episodes.map { it.episode.id })
    }

    /**
     * The query behind the feed already excludes undated episodes; the guard in the function means
     * it is total for any input, including a list assembled some other way.
     */
    @Test
    fun `episodes with no publication date are dropped`() {
        val groups = groupByRecency(
            episodes = listOf(entry("dated", "2026-08-30T10:00:00Z"), entry("undated", null)),
            now = now,
            zone = zone,
        )

        assertEquals(listOf("dated"), groups.single().episodes.map { it.episode.id })
    }

    @Test
    fun `an empty feed produces no sections`() {
        assertEquals(emptyList<LatestGroup>(), groupByRecency(emptyList(), now, zone))
    }

    /** Groups a single episode published at [publishedAt]. */
    private fun group(publishedAt: String): List<LatestGroup> =
        groupByRecency(listOf(entry("e", publishedAt)), now, zone)

    private fun entry(id: String, publishedAt: String?) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "p",
            guid = id,
            title = "Episode $id",
            description = "",
            audioUrl = "https://example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 1_000L,
            publishedAt = publishedAt?.let(Instant::parse),
            sizeBytes = null,
        ),
        showTitle = "Show",
        showArtworkUrl = null,
    )
}
