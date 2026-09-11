package md.borisveriga.megapodcastplayer.feature.settings

import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlDecodeResult
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [asBackupFile], the one function that makes an OPML import a restore.
 *
 * What is worth pinning is the round trip through this app. A YouTube show is exported as
 * `type="rss"`,
 * because its Atom feed genuinely is an XML document at an `http` URL and a foreign app should be
 * able to read it — so on the way back in the *URL* is asked rather than the attribute, and a
 * library that leaves here as OPML comes home as what it was rather than as twenty RSS shows that
 * will not play.
 */
class OpmlImportTest {

    private fun decoded(vararg feeds: OpmlFeed) =
        OpmlDecodeResult.Decoded(feeds = feeds.toList(), skipped = 0)

    @Test
    fun `an import carries every subscription the document named`() {
        val file = decoded(
            OpmlFeed("https://a.example/feed", "A"),
            OpmlFeed("https://b.example/feed", "B"),
        ).asBackupFile(importedAtMs = 1_700_000_000_000L)

        assertEquals(listOf("A", "B"), file.podcasts.map { it.title })
        assertEquals(
            listOf("https://a.example/feed", "https://b.example/feed"),
            file.podcasts.map { it.feedUrl },
        )
    }

    @Test
    fun `a youtube playlist feed comes back as a youtube show`() {
        // The URL, not the `type` attribute: `youTubePlaylistIdOrNull` is the exact inverse of the
        // feed URL this app mints when a playlist is added, so the two cannot disagree.
        val file = decoded(
            OpmlFeed(
                feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PLabcdefghijk",
                title = "A playlist",
            ),
            OpmlFeed("https://podlodka.io/rss", "An ordinary show"),
        ).asBackupFile(importedAtMs = 0L)

        assertEquals(
            listOf(PodcastSource.YOUTUBE, PodcastSource.RSS),
            file.podcasts.map { it.source },
        )
    }

    @Test
    fun `the document's order becomes the library's order`() {
        // The only ordering information OPML has, and this app has a hand-ordered library to put it
        // in. Losing it would shuffle a library that arrived in the order its owner arranged it.
        val file = decoded(
            OpmlFeed("https://c.example/feed", "C"),
            OpmlFeed("https://a.example/feed", "A"),
            OpmlFeed("https://b.example/feed", "B"),
        ).asBackupFile(importedAtMs = 0L)

        assertEquals(listOf(0, 1, 2), file.podcasts.map { it.sortOrder })
        assertEquals(listOf("C", "A", "B"), file.podcasts.map { it.title })
    }

    @Test
    fun `the handover is dated by the import`() {
        val importedAt = 1_700_000_000_000L

        val file = decoded(OpmlFeed("https://a.example/feed", "A")).asBackupFile(importedAt)

        assertEquals(importedAt, file.exportedAtMs)
    }

    @Test
    fun `a document with nothing in it makes a file with nothing in it`() {
        val file = decoded().asBackupFile(importedAtMs = 0L)

        assertTrue(file.podcasts.isEmpty())
    }
}
