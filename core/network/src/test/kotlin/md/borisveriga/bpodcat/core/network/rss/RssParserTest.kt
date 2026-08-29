package md.borisveriga.bpodcat.core.network.rss

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [RssParser] against feed shapes taken from the library this app was built for.
 */
class RssParserTest {

    private val parser = RssParser()

    private fun parseFixture(name: String): FeedChannel {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$name")) {
            "Missing test fixture feeds/$name"
        }
        return stream.use(parser::parse)
    }

    @Test
    fun `reads channel metadata including cyrillic text`() {
        val channel = parseFixture("soundcloud_podlodka.xml")

        assertEquals("Podlodka Podcast", channel.title)
        assertEquals(
            "Егор Толстой, Стас Цыганов, Екатерина Петрова и Евгений Кателла",
            channel.author,
        )
        assertEquals("Еженедельное шоу о разработке и людях в IT.", channel.description)
    }

    @Test
    fun `prefers itunes image over the legacy channel image`() {
        val channel = parseFixture("soundcloud_podlodka.xml")

        assertEquals("https://i1.sndcdn.com/avatars-000/original.jpg", channel.artworkUrl)
    }

    @Test
    fun `reads every field of a well-formed item`() {
        val item = parseFixture("soundcloud_podlodka.xml").items.first()

        assertEquals("tag:soundcloud,2010:tracks/1111111", item.guid)
        assertEquals("Podlodka #400 – Мультиплатформа", item.title)
        assertEquals("https://feeds.soundcloud.com/stream/1111111-podlodka-400.mp3", item.audioUrl)
        assertEquals(120_586_240L, item.audioLengthBytes)
        assertEquals("https://i1.sndcdn.com/artworks-400.jpg", item.artworkUrl)
        assertEquals(Instant.parse("2026-08-24T06:00:00Z"), item.publishedAt)
    }

    @Test
    fun `prefers content encoded over description for show notes`() {
        val item = parseFixture("soundcloud_podlodka.xml").items.first()

        assertTrue(
            "Expected the richer content:encoded body, got: ${item.description}",
            item.description.contains("<b>KMP</b>"),
        )
    }

    @Test
    fun `parses hh mm ss and bare-seconds durations`() {
        val items = parseFixture("soundcloud_podlodka.xml").items

        assertEquals((1 * 3600 + 23 * 60 + 45) * 1000L, items[0].durationMs)
        assertEquals(4_980_000L, items[1].durationMs)
    }

    @Test
    fun `falls back to the enclosure url when the feed omits a guid`() {
        val item = parseFixture("sloppy_publisher.xml").items.first { it.title == "Выпуск без guid" }

        assertEquals("https://cdn.example.com/episodes/no-guid.mp3", item.guid)
    }

    @Test
    fun `falls back to managingEditor when itunes author is missing`() {
        val channel = parseFixture("sloppy_publisher.xml")

        assertEquals("hello@example.com (Breslav & Lozhechkin)", channel.author)
    }

    @Test
    fun `degrades rather than throwing on an unparseable duration or date`() {
        val item = parseFixture("sloppy_publisher.xml")
            .items
            .first { it.title == "Выпуск без длительности" }

        assertNull(item.durationMs)
        assertNull(item.publishedAt)
        assertEquals("https://cdn.example.com/episodes/no-duration.mp3", item.audioUrl)
    }

    @Test
    fun `parses an rfc822 date without a day name`() {
        val item = parseFixture("sloppy_publisher.xml").items.first { it.title == "Выпуск без guid" }

        // 2 Mar 2026 09:15:00 +0300 == 06:15:00Z
        assertEquals(Instant.parse("2026-03-02T06:15:00Z"), item.publishedAt)
    }

    @Test
    fun `parses an mm ss duration`() {
        val item = parseFixture("sloppy_publisher.xml").items.first { it.title == "Короткий выпуск" }

        assertEquals(750_000L, item.durationMs)
    }

    @Test
    fun `skips items without a playable audio enclosure`() {
        val titles = parseFixture("sloppy_publisher.xml").items.map { it.title }

        assertEquals(
            listOf("Выпуск без guid", "Выпуск без длительности", "Короткий выпуск"),
            titles,
        )
    }

    @Test
    fun `treats a zero enclosure length as unknown`() {
        val item = parseFixture("sloppy_publisher.xml").items.first { it.title == "Выпуск без guid" }

        assertNull(item.audioLengthBytes)
    }

    @Test
    fun `throws a typed error for a document that is not xml`() {
        val html = "<!DOCTYPE html><html><body>404 Not Found</body>".byteInputStream()

        try {
            parser.parse(html)
            fail("Expected RssParseException for an HTML error page")
        } catch (e: RssParseException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `throws a typed error for xml that is not a feed`() {
        val notAFeed = "<?xml version=\"1.0\"?><root><nothing/></root>".byteInputStream()

        try {
            parser.parse(notAFeed)
            fail("Expected RssParseException for XML without a <channel>")
        } catch (e: RssParseException) {
            assertNotNull(e.message)
        }
    }
    @Test
    fun `rejects a youtube atom feed`() {
        // The mirror of YouTubeAtomParserTest's "rejects an rss document". Together the two prove
        // the parsers cannot be swapped by accident: whichever one is wrong for the body fails
        // loudly instead of quietly returning an empty show.
        try {
            parseFixture("youtube_playlist.xml")
            fail("Expected RssParseException for an Atom document")
        } catch (e: RssParseException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("<channel>"))
        }
    }

    @Test
    fun `drops items whose enclosure is not an http url`() {
        // The headline security case: an <enclosure> is untrusted, and a Media3 DefaultDataSource
        // resolves far more than HTTP. An item whose only enclosure fails the allowlist has no
        // audio at all, so it is dropped exactly as an item with no enclosure would be.
        val channel = parser.parse(
            feedWithEnclosures(
                "file:///data/data/md.borisveriga.bpodcat/databases/bpodcat.db",
                "content://com.other.app.provider/secret",
                "asset:///bundled.mp3",
                "rawresource:///2131165184",
                "rtmp://stream.example.com/live",
                "javascript:alert(1)",
            ).byteInputStream(),
        )

        assertEquals(emptyList<String>(), channel.items.map { it.audioUrl })
    }

    @Test
    fun `keeps http items alongside rejected ones`() {
        // A hostile item must cost the feed that item, not the show.
        val channel = parser.parse(
            feedWithEnclosures(
                "file:///etc/hosts",
                "https://cdn.example.com/good.mp3",
            ).byteInputStream(),
        )

        assertEquals(listOf("https://cdn.example.com/good.mp3"), channel.items.map { it.audioUrl })
    }

    @Test
    fun `drops artwork whose url is not an http url`() {
        // Artwork goes to the image loader, which resolves file: and content: too. Unlike audio,
        // losing it costs a glyph rather than the item, so the item itself survives.
        val channel = parser.parse(
            """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Hostile</title>
                <itunes:image href="file:///sdcard/DCIM/private.jpg"/>
                <image><url>content://com.other.app/pictures/1</url></image>
                <item>
                  <title>One</title>
                  <itunes:image href="content://com.other.app/pictures/2"/>
                  <enclosure url="https://cdn.example.com/one.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
            """.trimIndent().byteInputStream(),
        )

        assertNull(channel.artworkUrl)
        assertEquals(1, channel.items.size)
        assertNull(channel.items.first().artworkUrl)
    }

    /**
     * Builds a minimal feed with one `<item>` per [urls] entry, each carrying that enclosure URL.
     *
     * Assembled by concatenation rather than as one indented raw string: `trimIndent` measures the
     * *interpolated* text, so an unindented `$items` would leave the XML declaration indented, and
     * a declaration that is not the first thing in the document is a parse error.
     */
    private fun feedWithEnclosures(vararg urls: String): String {
        val items = urls.joinToString(separator = "") { url ->
            "<item><title>Item</title>" +
                "<enclosure url=\"$url\" type=\"audio/mpeg\"/></item>"
        }
        return "<?xml version=\"1.0\"?>" +
            "<rss version=\"2.0\"><channel><title>Hostile</title>$items</channel></rss>"
    }
}
