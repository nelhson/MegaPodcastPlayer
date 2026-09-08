package md.borisveriga.megapodcastplayer.core.network.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the chapter elements [RssParser] reads.
 *
 * Two things are worth pinning. A chapters URL is about to be *fetched*, so it goes through the
 * same scheme gate an enclosure does. And a malformed chapter must cost the feed nothing more than
 * that chapter — the parser's "skip the item, never lose the show" discipline, one level down.
 */
class RssParserChaptersTest {

    private val parser = RssParser()

    /**
     * Wraps [items] in a minimal feed and parses it.
     *
     * Concatenated rather than written as one indented raw string: interpolating multi-line content
     * at column zero makes `trimIndent` a no-op, which would leave whitespace in front of the XML
     * declaration and make every document here unparseable for a reason that has nothing to do with
     * chapters.
     */
    private fun parse(items: String): List<FeedItem> {
        val document = FEED_HEADER + items + FEED_FOOTER
        return parser.parse(document.byteInputStream()).items
    }

    private fun item(extra: String, guid: String = "guid-1") =
        "<item><guid>" + guid + "</guid><title>Episode</title>" +
            """<enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg" length="100"/>""" +
            extra + "</item>"

    @Test
    fun `reads a podcast chapters url`() {
        val items = parse(
            item("""<podcast:chapters url="https://example.com/ch.json" type="application/json+chapters"/>"""),
        )

        assertEquals("https://example.com/ch.json", items.single().chaptersUrl)
    }

    @Test
    fun `accepts a chapters element that declares no type`() {
        val items = parse(item("""<podcast:chapters url="https://example.com/ch.json"/>"""))

        assertEquals("https://example.com/ch.json", items.single().chaptersUrl)
    }

    @Test
    fun `ignores a chapters document in a format it cannot read`() {
        val items = parse(
            item("""<podcast:chapters url="https://example.com/ch.xml" type="application/xml"/>"""),
        )

        // Keeping it would win the priority order against a description that might actually work.
        assertNull(items.single().chaptersUrl)
    }

    @Test
    fun `refuses a chapters url that is not fetchable`() {
        val items = parse(item("""<podcast:chapters url="file:///etc/passwd"/>"""))

        assertNull(items.single().chaptersUrl)
    }

    @Test
    fun `reads an inline podlove chapter list in order`() {
        val items = parse(
            item(
                """
                <psc:chapters version="1.2">
                  <psc:chapter start="00:04:32.000" title="The interview"/>
                  <psc:chapter start="00:00:00.000" title="Cold open"/>
                  <psc:chapter start="00:58:10.000" title="Wrap up"/>
                </psc:chapters>
                """.trimIndent(),
            ),
        )

        val chapters = items.single().chapters
        assertEquals(listOf("Cold open", "The interview", "Wrap up"), chapters.map { it.title })
        assertEquals(listOf(0L, 272_000L, 3_490_000L), chapters.map { it.startMs })
    }

    @Test
    fun `reads the image and link a chapter carries`() {
        val items = parse(
            item(
                """
                <psc:chapters version="1.2">
                  <psc:chapter start="00:00:00.000" title="Cold open"
                               image="https://example.com/a.jpg" href="https://example.com/a"/>
                </psc:chapters>
                """.trimIndent(),
            ),
        )

        val chapter = items.single().chapters.single()
        assertEquals("https://example.com/a.jpg", chapter.imageUrl)
        assertEquals("https://example.com/a", chapter.url)
    }

    @Test
    fun `drops a chapter with an unreadable start and keeps the item`() {
        val items = parse(
            item(
                """
                <psc:chapters version="1.2">
                  <psc:chapter start="00:00:00.000" title="Cold open"/>
                  <psc:chapter start="not a time" title="Broken"/>
                  <psc:chapter start="00:04:32.000" title="The interview"/>
                </psc:chapters>
                """.trimIndent(),
            ),
        )

        val episode = items.single()
        assertEquals(listOf("Cold open", "The interview"), episode.chapters.map { it.title })
    }

    @Test
    fun `drops a chapter with no title`() {
        val items = parse(
            item(
                """
                <psc:chapters version="1.2">
                  <psc:chapter start="00:00:00.000" title=""/>
                  <psc:chapter start="00:04:32.000" title="The interview"/>
                </psc:chapters>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("The interview"), items.single().chapters.map { it.title })
    }

    @Test
    fun `keeps both forms when a feed publishes both`() {
        val items = parse(
            item(
                """
                <podcast:chapters url="https://example.com/ch.json"/>
                <psc:chapters version="1.2">
                  <psc:chapter start="00:00:00.000" title="Cold open"/>
                </psc:chapters>
                """.trimIndent(),
            ),
        )

        val episode = items.single()
        assertEquals("https://example.com/ch.json", episode.chaptersUrl)
        assertEquals(1, episode.chapters.size)
    }

    @Test
    fun `an item with no chapter elements carries none`() {
        val items = parse(item(""))

        assertNull(items.single().chaptersUrl)
        assertTrue(items.single().chapters.isEmpty())
    }

    @Test
    fun `one item's chapters do not leak into the next`() {
        val items = parse(
            item(
                """
                <psc:chapters version="1.2">
                  <psc:chapter start="00:00:00.000" title="Cold open"/>
                </psc:chapters>
                """.trimIndent(),
                guid = "guid-1",
            ) + item("", guid = "guid-2"),
        )

        assertEquals(2, items.size)
        assertEquals(1, items[0].chapters.size)
        assertTrue(items[1].chapters.isEmpty())
    }

    private companion object {
        const val FEED_HEADER = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<rss version="2.0" """ +
            """xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" """ +
            """xmlns:podcast="https://podcastindex.org/namespace/1.0" """ +
            """xmlns:psc="http://podlove.org/simple-chapters">""" +
            "<channel><title>Show</title>"

        const val FEED_FOOTER = "</channel></rss>"
    }
}
