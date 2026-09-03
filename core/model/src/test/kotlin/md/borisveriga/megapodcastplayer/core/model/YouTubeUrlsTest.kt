package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the YouTube URL helpers.
 *
 * The sentinel round trip is the load-bearing case: the same string is stored in
 * `episodes.audio_url` and used as the Media3 cache key for both streaming and downloads, so if
 * minting and parsing ever disagreed a downloaded episode would silently miss its own cache entry
 * and fail to play offline.
 */
class YouTubeUrlsTest {

    @Test
    fun `a minted sentinel parses back to the same video id`() {
        val id = "niTJ2221aS8"

        assertEquals(id, youTubeVideoIdOrNull(youTubeAudioSentinel(id)))
    }

    @Test
    fun `the sentinel preserves case and the id alphabet`() {
        // Video ids are case-sensitive and use the URL-safe base64 alphabet. Putting the id in the
        // authority position instead of a path segment would let a URI parser lowercase it.
        val ids = listOf("niTJ2221aS8", "dQw4w9WgXcQ", "a-B_c-D_e1F", "AAAAAAAAAAA")

        for (id in ids) {
            assertEquals(id, youTubeVideoIdOrNull(youTubeAudioSentinel(id)))
        }
    }

    @Test
    fun `the sentinel is exactly the documented shape`() {
        assertEquals("youtube://video/niTJ2221aS8", youTubeAudioSentinel("niTJ2221aS8"))
    }

    @Test
    fun `an ordinary audio url is not a sentinel`() {
        // This is the pass-through that keeps every existing podcast playing: the resolver sits on
        // the data source chain for every URL, not just YouTube ones.
        assertNull(youTubeVideoIdOrNull("https://cdn.example.com/episode-42.mp3"))
        assertNull(youTubeVideoIdOrNull("https://www.youtube.com/watch?v=niTJ2221aS8"))
        assertNull(youTubeVideoIdOrNull("file:///data/user/0/app/files/a.m4a"))
        assertNull(youTubeVideoIdOrNull(""))
    }

    @Test
    fun `a sentinel with no video id is not a sentinel`() {
        assertNull(youTubeVideoIdOrNull("youtube://video/"))
    }

    @Test
    fun `builds the canonical playlist feed url`() {
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0",
            youTubePlaylistFeedUrl("PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"),
        )
    }

    @Test
    fun `the feed url is stable enough to be an identity`() {
        // podcastIdOf hashes the feed URL, and the database has a unique index on it. Two calls for
        // the same playlist must produce the same id or duplicate detection breaks.
        val a = youTubePlaylistFeedUrl("PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0")
        val b = youTubePlaylistFeedUrl("PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0")

        assertEquals(podcastIdOf(a), podcastIdOf(b))
    }

    @Test
    fun `builds a 16 by 9 thumbnail url`() {
        // mqdefault, not hqdefault: hqdefault is 4:3 with the frame letterboxed inside it, so a
        // square centre-crop would keep the black bars.
        val url = youTubeThumbnailUrl("niTJ2221aS8")

        assertEquals("https://i.ytimg.com/vi/niTJ2221aS8/mqdefault.jpg", url)
        assertFalse(url.contains("hqdefault"))
    }

    // --- playlist id round trip -------------------------------------------

    @Test
    fun `a minted feed url parses back to the same playlist id`() {
        // The other load-bearing round trip. The feed URL is the show's stored identity, and the
        // extractor is addressed by id, so refreshing a playlist has to undo the minting exactly.
        val id = "PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0"

        assertEquals(id, youTubePlaylistIdOrNull(youTubePlaylistFeedUrl(id)))
    }

    @Test
    fun `an rss feed url names no playlist`() {
        assertNull(youTubePlaylistIdOrNull("https://example.com/feed.rss"))
    }

    @Test
    fun `a feed url with no id after the prefix names no playlist`() {
        assertNull(youTubePlaylistIdOrNull(youTubePlaylistFeedUrl("")))
    }

    @Test
    fun `the playlist page url is not the feed url`() {
        // Two different strings on purpose: one is the identity that must never move, the other is
        // an address handed to the extractor. Collapsing them would re-key every stored show.
        val id = "PLAA9qRhhXQ2c"

        assertEquals("https://www.youtube.com/playlist?list=$id", youTubePlaylistUrl(id))
        assertNotEquals(youTubePlaylistFeedUrl(id), youTubePlaylistUrl(id))
    }

    // --- watch url video ids ----------------------------------------------

    @Test
    fun `reads the video id out of a watch url`() {
        assertEquals(
            "niTJ2221aS8",
            youTubeVideoIdFromWatchUrlOrNull("https://www.youtube.com/watch?v=niTJ2221aS8"),
        )
    }

    @Test
    fun `reads the video id past the list and index a playlist entry carries`() {
        // The shape the extractor actually reports for a video reached through a playlist.
        assertEquals(
            "niTJ2221aS8",
            youTubeVideoIdFromWatchUrlOrNull(
                "https://www.youtube.com/watch?v=niTJ2221aS8&list=PLAA9qRhhXQ2c&index=3",
            ),
        )
    }

    @Test
    fun `finds the video id when it is not the first parameter`() {
        assertEquals(
            "niTJ2221aS8",
            youTubeVideoIdFromWatchUrlOrNull(
                "https://www.youtube.com/watch?list=PLAA9qRhhXQ2c&v=niTJ2221aS8",
            ),
        )
    }

    @Test
    fun `rejects a url with no video id at all`() {
        assertNull(
            youTubeVideoIdFromWatchUrlOrNull("https://www.youtube.com/playlist?list=PLAA9qRhhXQ2c"),
        )
        assertNull(youTubeVideoIdFromWatchUrlOrNull("https://www.youtube.com/watch"))
    }

    @Test
    fun `rejects an id that is not drawn from the video id alphabet`() {
        // The id becomes a sentinel URI, a Media3 cache key and an extractor argument, so a stray
        // slash or dot would change the meaning of all three. Validated where it first arrives.
        assertNull(youTubeVideoIdFromWatchUrlOrNull("https://www.youtube.com/watch?v=../../etc"))
        assertNull(youTubeVideoIdFromWatchUrlOrNull("https://www.youtube.com/watch?v="))
    }
}
