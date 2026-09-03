package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [PodcastLinkParser], driven by the exact links the app has to accept.
 */
class PodcastLinkParserTest {

    @Test
    fun `parses a plain latin apple url`() {
        val link = PodcastLinkParser.parse(
            "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744",
        )

        assertEquals(PodcastLink.Apple(1209828744L), link)
    }

    @Test
    fun `parses an apple url with a percent-encoded cyrillic slug`() {
        // https://podcasts.apple.com/us/podcast/радио-т/id256504435
        val link = PodcastLinkParser.parse(
            "https://podcasts.apple.com/us/podcast/%D1%80%D0%B0%D0%B4%D0%B8%D0%BE-%D1%82/id256504435",
        )

        assertEquals(PodcastLink.Apple(256504435L), link)
    }

    @Test
    fun `parses a second percent-encoded cyrillic slug`() {
        // https://podcasts.apple.com/us/podcast/бреслав-и-ложечкин/id1686567034
        val link = PodcastLinkParser.parse(
            "https://podcasts.apple.com/us/podcast/" +
                "%D0%B1%D1%80%D0%B5%D1%81%D0%BB%D0%B0%D0%B2-%D0%B8-" +
                "%D0%BB%D0%BE%D0%B6%D0%B5%D1%87%D0%BA%D0%B8%D0%BD/id1686567034",
        )

        assertEquals(PodcastLink.Apple(1686567034L), link)
    }

    @Test
    fun `ignores an episode query parameter and keeps the show id`() {
        val link = PodcastLinkParser.parse(
            "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744?i=1000654321000",
        )

        assertEquals(PodcastLink.Apple(1209828744L), link)
    }

    @Test
    fun `trims surrounding whitespace pasted along with the link`() {
        val link = PodcastLinkParser.parse(
            "  https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744\n",
        )

        assertEquals(PodcastLink.Apple(1209828744L), link)
    }

    @Test
    fun `accepts a bare collection id`() {
        assertEquals(PodcastLink.Apple(1209828744L), PodcastLinkParser.parse("1209828744"))
    }

    @Test
    fun `treats a non-apple http url as a raw rss feed`() {
        val feed = "https://feeds.soundcloud.com/users/soundcloud:users:291337106/sounds.rss"

        assertEquals(PodcastLink.Rss(feed), PodcastLinkParser.parse(feed))
    }

    @Test
    fun `rejects an apple url that carries no show id`() {
        assertNull(PodcastLinkParser.parse("https://podcasts.apple.com/us/browse"))
    }

    @Test
    fun `rejects blank and non-url input`() {
        assertNull(PodcastLinkParser.parse(""))
        assertNull(PodcastLinkParser.parse("   "))
        assertNull(PodcastLinkParser.parse("podlodka"))
        assertNull(PodcastLinkParser.parse("ftp://example.com/feed.xml"))
    }

    // --- YouTube ----------------------------------------------------------
    //
    // Every accepted spelling below must yield the *same* playlist id. That id becomes the feed
    // URL, which becomes the podcast id and the uniqueness key, so a spelling that escaped
    // canonicalisation would let one playlist be added twice as two separate shows.

    @Test
    fun `parses a watch url that carries a playlist`() {
        val link = PodcastLinkParser.parse(
            "https://www.youtube.com/watch?v=niTJ2221aS8&list=$PLAYLIST_ID",
        )

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `parses the playlist page itself`() {
        val link = PodcastLinkParser.parse("https://www.youtube.com/playlist?list=$PLAYLIST_ID")

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `parses the mobile bare and plain-http hosts identically`() {
        val expected = PodcastLink.YouTubePlaylist(PLAYLIST_ID)

        assertEquals(expected, PodcastLinkParser.parse("https://m.youtube.com/playlist?list=$PLAYLIST_ID"))
        assertEquals(expected, PodcastLinkParser.parse("https://youtube.com/playlist?list=$PLAYLIST_ID"))
        assertEquals(expected, PodcastLinkParser.parse("http://www.youtube.com/playlist?list=$PLAYLIST_ID"))
    }

    @Test
    fun `parses a youtu-be short link`() {
        val link = PodcastLinkParser.parse("https://youtu.be/niTJ2221aS8?list=$PLAYLIST_ID")

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `strips the VL prefix that youtube music adds`() {
        val link = PodcastLinkParser.parse(
            "https://music.youtube.com/playlist?list=VL$PLAYLIST_ID",
        )

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `ignores share tracking and position parameters and fragments`() {
        val link = PodcastLinkParser.parse(
            "https://www.youtube.com/watch?v=niTJ2221aS8&list=$PLAYLIST_ID" +
                "&index=3&t=42s&si=aBcDeF&pp=xyz#anchor",
        )

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `round-trips the canonical atom feed url`() {
        // The stored feedUrl is itself a YouTube link; re-pasting it must resolve to the same show
        // rather than being fetched twice under two ids.
        val link = PodcastLinkParser.parse(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=$PLAYLIST_ID",
        )

        assertEquals(PodcastLink.YouTubePlaylist(PLAYLIST_ID), link)
    }

    @Test
    fun `accepts a bare playlist id`() {
        assertEquals(
            PodcastLink.YouTubePlaylist(PLAYLIST_ID),
            PodcastLinkParser.parse(PLAYLIST_ID),
        )
    }

    @Test
    fun `accepts uploads favourites and album playlists`() {
        assertEquals(
            PodcastLink.YouTubePlaylist("UUBa659QWEk1AI4Tg--mrJ2A"),
            PodcastLinkParser.parse("https://www.youtube.com/playlist?list=UUBa659QWEk1AI4Tg--mrJ2A"),
        )
        assertEquals(
            PodcastLink.YouTubePlaylist("OLAK5uy_kZWaGjq6R2gWqfHYcXn1sK4DEfLPQlBmA"),
            PodcastLinkParser.parse(
                "https://music.youtube.com/playlist?list=OLAK5uy_kZWaGjq6R2gWqfHYcXn1sK4DEfLPQlBmA",
            ),
        )
    }

    @Test
    fun `preserves playlist id case`() {
        // Playlist ids are case-sensitive; lowercasing one silently points at a different playlist.
        val link = PodcastLinkParser.parse("https://www.youtube.com/playlist?list=$PLAYLIST_ID")

        assertEquals(PLAYLIST_ID, (link as PodcastLink.YouTubePlaylist).playlistId)
    }

    @Test
    fun `rejects private and autogenerated playlists`() {
        // Watch Later and Liked are per-account; RD mixes are regenerated per viewer. All three
        // have no durable Atom feed, so they are rejected before a fetch is wasted on them.
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/playlist?list=WL"))
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/playlist?list=LL"))
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/watch?v=X&list=RDniTJ2221aS8"))
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/watch?v=X&list=RDMMniTJ2221aS8"))
    }

    @Test
    fun `rejects a youtube link that names no playlist`() {
        // Crucially null rather than Rss: falling through would fetch a watch page and fail with an
        // RSS parse error, which is a useless message for the most likely user mistake.
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/watch?v=niTJ2221aS8"))
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/@somehandle"))
        assertNull(PodcastLinkParser.parse("https://www.youtube.com/channel/UCBa659QWEk1AI4Tg--mrJ2A"))
    }

    @Test
    fun `does not treat a lookalike host as youtube`() {
        val lookalike = "https://evil.example/redirect?u=youtube.com&list=$PLAYLIST_ID"

        assertEquals(PodcastLink.Rss(lookalike), PodcastLinkParser.parse(lookalike))
    }

    @Test
    fun `does not confuse apple music with youtube music`() {
        assertEquals(
            PodcastLink.Apple(1209828744L),
            PodcastLinkParser.parse("https://music.apple.com/us/podcast/podlodka/id1209828744"),
        )
    }

    private companion object {
        /** The playlist from the feature request, used verbatim so the tests match reality. */
        const val PLAYLIST_ID = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"
    }
}
