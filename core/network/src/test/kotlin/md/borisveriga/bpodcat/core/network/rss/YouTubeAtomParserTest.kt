package md.borisveriga.bpodcat.core.network.rss

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [YouTubeAtomParser].
 *
 * The fixture is the live response for the playlist this feature was built for, trimmed to three
 * entries, with the second entry's `yt:videoId` and the third entry's whole `<media:group>` removed
 * so the degradation paths are exercised by real markup rather than by something hand-written.
 */
class YouTubeAtomParserTest {

    private val parser = YouTubeAtomParser()

    private fun parseFixture(name: String): FeedChannel {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$name")) {
            "Missing test fixture feeds/$name"
        }
        return stream.use(parser::parse)
    }

    private val channel: FeedChannel get() = parseFixture("youtube_playlist.xml")

    @Test
    fun `reads the playlist title and owner`() {
        assertEquals("Generic", channel.title)
        assertEquals("Boris Veriga", channel.author)
    }

    @Test
    fun `the playlist owner is not overwritten by a video uploader`() {
        // <author><name> appears once for the playlist and again inside every entry. Without the
        // entry-depth guard the show's author would end up as whoever uploaded the last video.
        assertEquals("Boris Veriga", channel.author)
    }

    @Test
    fun `the playlist has no description because the feed publishes none`() {
        assertEquals("", channel.description)
    }

    @Test
    fun `derives show artwork from the newest video`() {
        // The playlist feed carries no image of its own at any level, so the first entry's
        // thumbnail is the only artwork there is.
        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", channel.artworkUrl)
    }

    @Test
    fun `reads every field of a well-formed entry`() {
        val item = channel.items.first()

        assertEquals("yt:video:niTJ2221aS8", item.guid)
        assertEquals("Оправдан ли запрет на соцсети для детей?", item.title)
        assertEquals("youtube://video/niTJ2221aS8", item.audioUrl)
        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", item.artworkUrl)
        assertEquals(Instant.parse("2026-08-26T18:24:30Z"), item.publishedAt)
        // Neither is published by this endpoint. The duration fills itself in on first play.
        assertNull(item.durationMs)
        assertNull(item.audioLengthBytes)
    }

    @Test
    fun `keeps a multi-line cyrillic description intact`() {
        // SAX delivers long text in several characters() callbacks; the buffer is what stops this
        // arriving truncated.
        val description = channel.items.first().description

        assertTrue(description, description.startsWith("Продолжение разговора"))
        assertTrue(description, description.contains("Вторая строка описания."))
    }

    @Test
    fun `prefers the media title but falls back to the entry title`() {
        val items = channel.items

        assertEquals("Оправдан ли запрет на соцсети для детей?", items[0].title)
        // The third entry has no <media:group> at all.
        assertEquals(
            "КАК пройти Систем Дизайн на 30,000$? Гайд по Систем Дизайн с @vladimir_balun_programming",
            items[1].title,
        )
        assertEquals("", items[1].description)
    }

    @Test
    fun `drops an entry with no video id`() {
        // Without a video id there is nothing to resolve audio from, so the entry could never be
        // played. Dropping it silently mirrors how RssParser treats an item with no enclosure.
        val items = channel.items

        assertEquals(2, items.size)
        assertTrue(items.none { it.guid.contains("Eplxom-e1C4") })
    }

    @Test
    fun `the audio url is a sentinel, never a youtube page`() {
        // Storing a real stream URL would be wrong twice over: it expires within hours, and it
        // doubles as the Media3 cache key, so a downloaded episode would stop matching its own
        // cache entry.
        for (item in channel.items) {
            assertTrue(item.audioUrl, item.audioUrl.startsWith("youtube://video/"))
        }
    }

    @Test
    fun `ignores the flash player url in media content`() {
        // <media:content> is a Flash player page, not audio. Treating it as playable would produce
        // episodes that fail at the first byte.
        for (item in channel.items) {
            assertTrue(item.audioUrl, !item.audioUrl.contains("shockwave"))
            assertTrue(item.audioUrl, !item.audioUrl.contains("/v/"))
        }
    }

    @Test
    fun `rejects an rss document`() {
        // The two parsers must never be silently interchangeable: handing RSS to this one has to
        // fail loudly rather than yield an empty playlist.
        try {
            parseFixture("soundcloud_podlodka.xml")
            fail("Expected RssParseException for an RSS 2.0 document")
        } catch (e: RssParseException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("<feed>"))
        }
    }

    @Test
    fun `rejects an html error page served with a 200`() {
        val html = "<html><body>Playlist unavailable</body></html>".byteInputStream()

        try {
            parser.parse(html)
            fail("Expected RssParseException for an HTML body")
        } catch (e: RssParseException) {
            // Expected: a private or deleted playlist looks exactly like this from here.
        }
    }
}
