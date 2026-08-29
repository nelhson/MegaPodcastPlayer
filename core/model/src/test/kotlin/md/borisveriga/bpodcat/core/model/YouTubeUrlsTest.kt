package md.borisveriga.bpodcat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
