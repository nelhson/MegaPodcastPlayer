package md.borisveriga.megapodcastplayer.core.model

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Markdown list of downloaded episodes.
 *
 * The assertions cover what a reader without the app needs: every show can be found again by its
 * feed URL, and every episode has a working link.
 */
class DownloadListExportTest {

    /** 2026-09-16T00:00:00Z. */
    private val exportedAt = 1_789_516_800_000L

    private fun entry(
        episodeTitle: String = "Episode 42",
        showTitle: String = "Podlodka Podcast",
        feedUrl: String = "https://feeds.example.com/podlodka",
        audioUrl: String = "https://cdn.example.com/42.mp3",
        publishedAtMs: Long? = null,
        durationMs: Long? = null,
        sizeBytes: Long? = null,
    ) = DownloadListEntry(
        showTitle = showTitle,
        feedUrl = feedUrl,
        episodeTitle = episodeTitle,
        audioUrl = audioUrl,
        publishedAtMs = publishedAtMs,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )

    private fun markdown(entries: List<DownloadListEntry>) =
        downloadListMarkdown(entries, exportedAt, ZoneOffset.UTC)

    @Test
    fun `an episode without a position links to the file itself`() {
        assertEquals("https://cdn.example.com/42.mp3", episodeLink("https://cdn.example.com/42.mp3"))
    }

    @Test
    fun `a video links to its watch page with no offset`() {
        assertEquals(
            "https://www.youtube.com/watch?v=niTJ2221aS8",
            episodeLink(youTubeAudioSentinel("niTJ2221aS8")),
        )
    }

    @Test
    fun `the header counts episodes and their total size`() {
        val document = markdown(
            listOf(
                entry(sizeBytes = 1_000_000_000L),
                entry(episodeTitle = "b", sizeBytes = 400_000_000L),
            ),
        )

        val lines = document.lines()
        assertEquals("# MegaPodcastPlayer downloads", lines[0])
        assertTrue(lines[2], lines[2].startsWith("2 episodes, 1.4 GB, exported "))
    }

    @Test
    fun `each show is headed with the feed url that re-adds it, shows sorted by title`() {
        val document = markdown(
            listOf(
                entry(showTitle = "zebra", feedUrl = "https://z.example.com/feed"),
                entry(showTitle = "Apple", feedUrl = "https://a.example.com/feed"),
            ),
        )

        assertTrue(document.indexOf("## Apple") < document.indexOf("## zebra"))
        assertTrue(document.contains("## Apple\n\nFeed: <https://a.example.com/feed>\n"))
    }

    @Test
    fun `episodes are newest first with undated ones last`() {
        val document = markdown(
            listOf(
                entry(episodeTitle = "old", publishedAtMs = 1_000L),
                entry(episodeTitle = "undated"),
                entry(episodeTitle = "new", publishedAtMs = 2_000L),
            ),
        )

        val titles = document.lines()
            .filter { it.startsWith("- ") }
            .map { it.substringAfter("**").substringBefore("**") }
        assertEquals(listOf("new", "old", "undated"), titles)
    }

    @Test
    fun `a bullet lists date, duration, size and link`() {
        val document = markdown(
            listOf(
                entry(
                    publishedAtMs = exportedAt,
                    durationMs = 4_325_000L,
                    sizeBytes = 84_000_000L,
                ),
            ),
        )

        val bullet = document.lines().single { it.startsWith("- ") }
        assertTrue(bullet, bullet.startsWith("- **Episode 42** — "))
        assertTrue(bullet, bullet.endsWith(" · 1:12:05 · 84 MB — <https://cdn.example.com/42.mp3>"))
    }

    @Test
    fun `unknown facts are left out rather than written as blanks`() {
        val bullet = markdown(listOf(entry())).lines().single { it.startsWith("- ") }

        assertEquals("- **Episode 42** — <https://cdn.example.com/42.mp3>", bullet)
    }

    @Test
    fun `a url the media stack would refuse gets no link`() {
        val document = markdown(listOf(entry(audioUrl = "javascript:alert(1)")))

        assertEquals("- **Episode 42**", document.lines().single { it.startsWith("- ") })
        assertFalse(document.contains("javascript"))
    }

    @Test
    fun `a title spread over lines stays on its bullet`() {
        val bullet = markdown(listOf(entry(episodeTitle = "Part one\n  and two")))
            .lines().single { it.startsWith("- ") }

        assertTrue(bullet, bullet.startsWith("- **Part one and two**"))
    }

    @Test
    fun `sizes are written in decimal units`() {
        assertEquals("512 B", formatSize(512L))
        assertEquals("3 KB", formatSize(3_200L))
        assertEquals("84 MB", formatSize(84_000_000L))
        assertEquals("1.4 GB", formatSize(1_400_000_000L))
    }
}
