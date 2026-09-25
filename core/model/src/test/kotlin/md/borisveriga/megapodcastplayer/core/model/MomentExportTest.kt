package md.borisveriga.megapodcastplayer.core.model

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the text a moment turns into.
 *
 * This is the half of the feature that survives the app being deleted, so the assertions are about
 * the parts a stranger's machine has to be able to act on: a link that opens at the right second,
 * and a feed URL that can be pasted back into an empty library.
 */
class MomentExportTest {

    private val feedUrl = "https://feeds.simplecast.com/podlodka"

    private fun moment(
        id: Long = 1L,
        positionMs: Long = 743_000L,
        note: String? = null,
        episodeTitle: String = "Episode 42",
        showTitle: String = "Podlodka Podcast",
        audioUrl: String = "https://cdn.example.com/42.mp3",
        feed: String = feedUrl,
    ) = MomentWithEpisode(
        moment = Moment(
            id = id,
            episodeId = "episode-$id",
            positionMs = positionMs,
            note = note,
            createdAtMs = 1_000L,
        ),
        episodeTitle = episodeTitle,
        showTitle = showTitle,
        showArtworkUrl = null,
        feedUrl = feed,
        audioUrl = audioUrl,
    )

    @Test
    fun `a video becomes a watch url that opens at the mark`() {
        assertEquals(
            "https://www.youtube.com/watch?v=niTJ2221aS8&t=743s",
            momentLink(youTubeAudioSentinel("niTJ2221aS8"), 743_000L),
        )
    }

    @Test
    fun `an enclosure gains a media fragment`() {
        assertEquals(
            "https://cdn.example.com/42.mp3#t=743",
            momentLink("https://cdn.example.com/42.mp3", 743_400L),
        )
    }

    @Test
    fun `an enclosure that already carries a fragment keeps the publisher's`() {
        assertEquals(
            "https://cdn.example.com/42.mp3#chapter-3",
            momentLink("https://cdn.example.com/42.mp3#chapter-3", 743_000L),
        )
    }

    @Test
    fun `a url the media stack would refuse gets no link at all`() {
        assertNull(momentLink("file:///data/data/md.borisveriga.megapodcastplayer/databases/x.db", 1L))
        assertNull(momentLink("", 1L))
    }

    @Test
    fun `a negative position is treated as the start`() {
        assertEquals("https://cdn.example.com/42.mp3#t=0", momentLink("https://cdn.example.com/42.mp3", -5L))
    }

    @Test
    fun `the document names every show's feed so an empty library can be rebuilt from it`() {
        val other = "https://feeds.example.com/other"
        val document = momentsMarkdown(
            moments = listOf(
                moment(id = 1L),
                moment(id = 2L, showTitle = "Another Show", feed = other, audioUrl = "https://cdn.example.com/b.mp3"),
            ),
            exportedAtMs = 0L,
            zone = ZoneOffset.UTC,
        )

        assertTrue(document, document.contains("Feed: <$feedUrl>"))
        assertTrue(document, document.contains("Feed: <$other>"))
    }

    @Test
    fun `a show's moments are grouped together and ordered by position`() {
        val document = momentsMarkdown(
            moments = listOf(
                moment(id = 1L, positionMs = 743_000L, note = "second"),
                moment(id = 2L, positionMs = 60_000L, note = "first"),
            ),
            exportedAtMs = 0L,
            zone = ZoneOffset.UTC,
        )

        assertTrue(document, document.indexOf("first") < document.indexOf("second"))
        // One show heading and one episode heading, however many moments they hold.
        assertEquals(1, document.lines().count { it.startsWith("## ") })
        assertEquals(1, document.lines().count { it.startsWith("### ") })
    }

    @Test
    fun `shows are ordered by title rather than by when they were saved`() {
        val document = momentsMarkdown(
            moments = listOf(
                moment(id = 1L, showTitle = "Zed"),
                moment(id = 2L, showTitle = "alpha", feed = "https://feeds.example.com/a"),
            ),
            exportedAtMs = 0L,
            zone = ZoneOffset.UTC,
        )

        assertTrue(document, document.indexOf("## alpha") < document.indexOf("## Zed"))
    }

    @Test
    fun `a note typed over several lines stays on one bullet`() {
        val document = momentsMarkdown(
            moments = listOf(moment(note = "first line\nsecond line")),
            exportedAtMs = 0L,
            zone = ZoneOffset.UTC,
        )

        val bullet = document.lines().single { it.startsWith("- ") }
        assertTrue(bullet, bullet.contains("first line second line"))
    }

    @Test
    fun `the header counts what is in the document`() {
        val one = momentsMarkdown(listOf(moment()), exportedAtMs = 0L, zone = ZoneOffset.UTC)
        val two = momentsMarkdown(
            listOf(moment(id = 1L), moment(id = 2L, positionMs = 10_000L)),
            exportedAtMs = 0L,
            zone = ZoneOffset.UTC,
        )

        assertTrue(one, one.contains("1 moment, exported "))
        assertTrue(two, two.contains("2 moments, exported "))
    }

    @Test
    fun `an empty export is still a readable document`() {
        val document = momentsMarkdown(emptyList(), exportedAtMs = 0L, zone = ZoneOffset.UTC)

        assertTrue(document, document.startsWith("# MegaPodcastPlayer moments"))
        assertTrue(document, document.contains("0 moments"))
    }
}
