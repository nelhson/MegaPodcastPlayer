package md.borisveriga.megapodcastplayer.core.model.backup

import java.io.File
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [OpmlCodec].
 *
 * OPML is the one format this app reads that it did not write, so most of what is pinned here is
 * about *other people's files*: folders, missing titles, duplicate rows, namespace prefixes, and
 * the two shapes of hostile input a subscription list can take — an external entity, and a hundred
 * thousand feeds.
 *
 * The round trip is asserted too, because the export exists to be read by something else and the
 * only reader available to a test is this one.
 */
class OpmlCodecTest {

    private fun podcast(feedUrl: String, title: String) = BackupPodcast(
        feedUrl = feedUrl,
        source = PodcastSource.RSS,
        title = title,
    )

    private fun decoded(text: String): OpmlDecodeResult.Decoded {
        val result = OpmlCodec.decode(text)
        assertTrue("expected a readable document, got $result", result is OpmlDecodeResult.Decoded)
        return result as OpmlDecodeResult.Decoded
    }

    @Test
    fun `an exported document reads back as the library that was exported`() {
        val library = listOf(
            podcast("https://podlodka.io/rss", "Podlodka Podcast"),
            podcast("https://example.com/feed.xml", "Another Show"),
        )

        val result = decoded(OpmlCodec.encode(library, title = "MegaPodcastPlayer", 1_700_000_000_000L))

        assertEquals(
            listOf("https://podlodka.io/rss", "https://example.com/feed.xml"),
            result.feeds.map { it.feedUrl },
        )
        assertEquals(listOf("Podlodka Podcast", "Another Show"), result.feeds.map { it.title })
    }

    @Test
    fun `a title with an ampersand survives the round trip`() {
        // The one thing hand-written XML gets wrong. A show called "Rock & Roll" writes an
        // unescaped `&`, which makes the whole document unparseable — not just that row.
        val library = listOf(podcast("https://example.com/feed", """Rock & Roll <"quoted">"""))

        val result = decoded(OpmlCodec.encode(library, title = "Library & Co", 0L))

        assertEquals("""Rock & Roll <"quoted">""", result.feeds.single().title)
    }

    @Test
    fun `an empty library exports a document that still parses`() {
        // A user with no shows can still press the button, and what comes out has to be OPML rather
        // than a file the next app refuses.
        val text = OpmlCodec.encode(emptyList(), title = "MegaPodcastPlayer", 0L)

        assertEquals(OpmlDecodeResult.NoFeeds, OpmlCodec.decode(text))
    }

    @Test
    fun `folders are flattened rather than lost`() {
        // How every other app exports: subscriptions grouped under category outlines, which have
        // children and no xmlUrl. This app has nowhere to put a folder, so the shows come out flat.
        val result = decoded(
            """
            <opml version="2.0"><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.example/feed" />
                <outline type="rss" text="B" xmlUrl="https://b.example/feed" />
              </outline>
              <outline type="rss" text="C" xmlUrl="https://c.example/feed" />
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(listOf("A", "B", "C"), result.feeds.map { it.title })
        // The folder itself: counted as skipped, because the user's question is "did everything
        // arrive" and a silent drop is what makes that unanswerable.
        assertEquals(1, result.skipped)
    }

    @Test
    fun `the same feed under two folders is imported once`() {
        val result = decoded(
            """
            <opml version="2.0"><body>
              <outline text="Tech"><outline xmlUrl="https://a.example/feed" text="A" /></outline>
              <outline text="Favourites"><outline xmlUrl="https://a.example/feed" text="A" /></outline>
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(1, result.feeds.size)
    }

    @Test
    fun `an outline with no name falls back to its feed url`() {
        // Better than an empty row: the URL is at least something the user can recognise, and the
        // real title arrives the moment the feed is fetched.
        val result = decoded(
            """<opml version="2.0"><body><outline xmlUrl="https://a.example/feed" /></body></opml>""",
        )

        assertEquals("https://a.example/feed", result.feeds.single().title)
    }

    @Test
    fun `title is used when text is missing, and text wins when both are present`() {
        val result = decoded(
            """
            <opml version="2.0"><body>
              <outline title="From title" xmlUrl="https://a.example/feed" />
              <outline text="From text" title="From title" xmlUrl="https://b.example/feed" />
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(listOf("From title", "From text"), result.feeds.map { it.title })
    }

    @Test
    fun `a feed url that is not http is refused`() {
        // The reason this check exists: an OPML file is picked out of a document provider and its
        // URLs are handed to this app's own fetcher. A `file://` row would make an import read the
        // device's storage on the say-so of a file the user merely opened.
        val result = decoded(
            """
            <opml version="2.0"><body>
              <outline xmlUrl="file:///data/data/md.borisveriga.megapodcastplayer/databases/app.db" />
              <outline xmlUrl="content://com.other.app/private" />
              <outline xmlUrl="javascript:alert(1)" />
              <outline xmlUrl="https://ok.example/feed" text="Fine" />
            </body></opml>
            """.trimIndent(),
        )

        assertEquals(listOf("https://ok.example/feed"), result.feeds.map { it.feedUrl })
        assertEquals(3, result.skipped)
    }

    @Test
    fun `an external entity is not resolved`() {
        // XXE, against a file this test writes so the assertion means the same thing on every
        // platform. A parser that resolved the entity would paste the file's contents into the
        // import; either refusing the document or leaving the entity unexpanded is fine, and the
        // one thing that must not happen is the secret arriving as a show title.
        val secret = File.createTempFile("opml-xxe", ".txt").apply {
            writeText(SECRET)
            deleteOnExit()
        }
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE opml [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
            <opml version="2.0"><body>
              <outline text="&xxe;" xmlUrl="https://a.example/feed" />
            </body></opml>
        """.trimIndent()

        val titles = when (val result = OpmlCodec.decode(hostile)) {
            is OpmlDecodeResult.Decoded -> result.feeds.map { it.title }
            else -> emptyList()
        }

        assertTrue("an entity was expanded: $titles", titles.none { it.contains(SECRET) })
    }

    @Test
    fun `a document with more feeds than the cap contributes only the cap`() {
        val many = (0..OpmlCodec.MAX_FEEDS + 50).joinToString("\n") { index ->
            """<outline xmlUrl="https://example.com/$index" text="Show $index" />"""
        }

        val result = decoded("""<opml version="2.0"><body>$many</body></opml>""")

        assertEquals(OpmlCodec.MAX_FEEDS, result.feeds.size)
    }

    @Test
    fun `something that is not xml at all is refused`() {
        assertEquals(OpmlDecodeResult.NotOpml, OpmlCodec.decode("this is not a document"))
    }

    @Test
    fun `xml that is not opml is refused`() {
        // The one that matters in practice: the user picks the JSON backup, or a feed, by mistake.
        assertEquals(
            OpmlDecodeResult.NotOpml,
            OpmlCodec.decode("""<rss version="2.0"><channel><title>A feed</title></channel></rss>"""),
        )
    }

    private companion object {
        /** Contents of the file the XXE case tries to smuggle into a show title. */
        const val SECRET = "megapodcastplayer-xxe-canary"
    }

    @Test
    fun `a namespaced document still reads`() {
        // Some exporters write OPML in a namespace. Elements are matched on local name for the
        // same reason RssParser matches feeds that way: publishers use whichever prefix they like.
        val result = decoded(
            """
            <o:opml xmlns:o="http://opml.org/spec2" version="2.0">
              <o:body><o:outline text="A" xmlUrl="https://a.example/feed" /></o:body>
            </o:opml>
            """.trimIndent(),
        )

        assertEquals("https://a.example/feed", result.feeds.single().feedUrl)
    }
}
