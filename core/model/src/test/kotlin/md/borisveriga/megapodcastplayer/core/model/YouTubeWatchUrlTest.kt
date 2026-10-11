package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [youTubeWatchUrl].
 *
 * The URL is handed to another app, so the one thing to pin is that nothing but a well-formed id
 * can get into it.
 */
class YouTubeWatchUrlTest {

    @Test
    fun `builds the watch page for a video`() {
        assertEquals("https://www.youtube.com/watch?v=niTJ2221aS8", youTubeWatchUrl("niTJ2221aS8"))
    }

    @Test
    fun `the watch page round-trips through the id parser`() {
        assertEquals("aHsi-OHI_i8", youTubeVideoIdFromWatchUrlOrNull(youTubeWatchUrl("aHsi-OHI_i8")))
    }

    @Test
    fun `refuses a malformed id rather than building a url around it`() {
        try {
            youTubeWatchUrl("abc?a=b")
            fail("Expected a malformed id to be refused")
        } catch (_: IllegalArgumentException) {
            // Expected: the id would otherwise change the meaning of the URL.
        }
    }
}
