package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the moments screen's narrowing and grouping.
 *
 * Two of these are about a decision rather than a mechanism. The search matches the note and the
 * episode and *not* the show, because the show has a control of its own — so a word typed here
 * means one thing rather than two. And a group keeps the list's own order rather than sorting
 * itself alphabetically, because the list was newest first and the reason to open this screen is
 * usually the thing marked ten minutes ago.
 */
class MomentsFilterTest {

    private fun moment(
        id: Long,
        note: String? = null,
        episodeTitle: String = "Episode $id",
        showTitle: String = "Podlodka Podcast",
        feedUrl: String = "https://podlodka.io/rss",
    ) = MomentWithEpisode(
        moment = Moment(
            id = id,
            episodeId = "e$id",
            positionMs = id * 1_000L,
            note = note,
            createdAtMs = id,
        ),
        episodeTitle = episodeTitle,
        showTitle = showTitle,
        showArtworkUrl = null,
        feedUrl = feedUrl,
        audioUrl = "https://cdn.example.com/e$id.mp3",
    )

    private val radioT = "https://radio-t.com/rss"

    private val moments = listOf(
        moment(1L, note = "Определение дизайн-системы"),
        moment(2L, episodeTitle = "Podlodka #493 — Тестирование"),
        moment(3L, note = "Про радио", showTitle = "Radio-T", feedUrl = radioT),
        moment(4L, showTitle = "Radio-T", feedUrl = radioT),
    )

    @Test
    fun `an empty filter keeps everything and narrows nothing`() {
        val filter = MomentsFilter()

        assertFalse(filter.isNarrowing)
        assertEquals(moments, moments.narrowedBy(filter))
    }

    @Test
    fun `a query matches the note`() {
        val kept = moments.narrowedBy(MomentsFilter(query = "дизайн"))

        assertEquals(listOf(1L), kept.map { it.moment.id })
    }

    @Test
    fun `a query matches the episode title too, which is all a moment without a note has`() {
        val kept = moments.narrowedBy(MomentsFilter(query = "493"))

        assertEquals(listOf(2L), kept.map { it.moment.id })
    }

    @Test
    fun `a query does not match the show, which has a control of its own`() {
        // The decision this test exists for. "Radio" appears in a note and in a show title, and a
        // search that matched both would quietly return four moments when the user asked for one.
        val kept = moments.narrowedBy(MomentsFilter(query = "Radio-T"))

        assertTrue("matched a show title: ${kept.map { it.moment.id }}", kept.isEmpty())
    }

    @Test
    fun `a query is case-insensitive and ignores surrounding space`() {
        // Both matter on a phone keyboard, which capitalises the first letter and leaves a trailing
        // space behind a word more often than not.
        assertEquals(
            listOf(2L),
            moments.narrowedBy(MomentsFilter(query = "  ТЕСТИРОВАНИЕ ")).map { it.moment.id },
        )
    }

    @Test
    fun `a show keeps only that show`() {
        val kept = moments.narrowedBy(MomentsFilter(feedUrl = radioT))

        assertEquals(listOf(3L, 4L), kept.map { it.moment.id })
    }

    @Test
    fun `a show and a query narrow together rather than either or`() {
        val kept = moments.narrowedBy(MomentsFilter(query = "радио", feedUrl = radioT))

        assertEquals(listOf(3L), kept.map { it.moment.id })
    }

    @Test
    fun `grouping alone hides nothing`() {
        // It rearranges; `isNarrowing` is what the screen asks to decide whether an empty result
        // means "nothing matches" or "you have saved nothing".
        assertFalse(MomentsFilter(groupByShow = true).isNarrowing)
    }

    @Test
    fun `the shows menu is built from the moments, most first`() {
        val shows = moments.showsWithMoments()

        assertEquals(listOf("Podlodka Podcast", "Radio-T"), shows.map { it.title })
        assertEquals(listOf(2, 2), shows.map { it.momentCount })
    }

    @Test
    fun `a show with more moments comes first`() {
        val shows = (moments + moment(5L, showTitle = "Radio-T", feedUrl = radioT))
            .showsWithMoments()

        assertEquals(listOf("Radio-T", "Podlodka Podcast"), shows.map { it.title })
    }

    @Test
    fun `groups appear in the order their first moment does, keeping the list's order inside`() {
        // Not alphabetical. The list was newest first, so the show marked most recently heads the
        // page and the most recent mark inside it is still first — sorting either would move the
        // thing the user came for.
        val groups = moments.groupedByShow()

        assertEquals(listOf("Podlodka Podcast", "Radio-T"), groups.map { it.title })
        assertEquals(listOf(1L, 2L), groups.first().moments.map { it.moment.id })
        assertEquals(listOf(3L, 4L), groups.last().moments.map { it.moment.id })
    }

    @Test
    fun `an empty list groups to nothing rather than to one empty group`() {
        assertTrue(emptyList<MomentWithEpisode>().groupedByShow().isEmpty())
        assertTrue(emptyList<MomentWithEpisode>().showsWithMoments().isEmpty())
    }
}
