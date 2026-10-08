package md.borisveriga.megapodcastplayer.feature.player.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the embedded player's page and its two code tables.
 *
 * A `WebView` cannot be run under Robolectric, so what is tested is everything up to it: that the
 * page names the right video and start, that nothing but a well-formed id can be written into the
 * script, and that YouTube's codes are read as the API documents them.
 */
class EmbeddedPlayerHtmlTest {

    @Test
    fun `the page embeds the video from the given second`() {
        val html = embeddedPlayerHtml("niTJ2221aS8", startSeconds = 754)

        assertTrue(html, html.contains("videoId: 'niTJ2221aS8'"))
        assertTrue(html, html.contains("start: 754"))
        assertTrue(html, html.contains("https://www.youtube.com/iframe_api"))
    }

    @Test
    fun `the page calls back under the bridge's name`() {
        val html = embeddedPlayerHtml("niTJ2221aS8", startSeconds = 0)

        for (callback in listOf("onReady", "onStateChange", "onError", "onTime")) {
            assertTrue(callback, html.contains("$EMBEDDED_BRIDGE_NAME.$callback("))
        }
    }

    @Test
    fun `the page asks for the picture inline, without the player's own fullscreen`() {
        // Inline keeps the picture in the app's frame; fullscreen would need a custom view the
        // WebView is not given, so the button that asks for it is not drawn.
        val html = embeddedPlayerHtml("niTJ2221aS8", startSeconds = 0)

        assertTrue(html, html.contains("playsinline: 1"))
        assertTrue(html, html.contains("fs: 0"))
    }

    @Test
    fun `a malformed id is refused rather than written into the script`() {
        // The id lands inside a quoted string in a script; a quote or a slash in it would be an
        // injection, so the shape is checked before anything is built.
        for (bad in listOf("abc' + alert(1) + '", "../../evil", "abc def", "")) {
            try {
                embeddedPlayerHtml(bad, startSeconds = 0)
                fail("Expected '$bad' to be refused")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `a negative start is refused`() {
        try {
            embeddedPlayerHtml("niTJ2221aS8", startSeconds = -1)
            fail("Expected a negative start to be refused")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `the pause script guards against a player that has not loaded`() {
        assertTrue(EMBEDDED_PAUSE_SCRIPT.contains("window.player"))
        assertFalse(EMBEDDED_PAUSE_SCRIPT.startsWith("player."))
    }

    // --- the code tables -----------------------------------------------------

    @Test
    fun `player states are read as the api documents them`() {
        assertEquals(EmbeddedPlayerState.UNSTARTED, embeddedPlayerStateOf(-1))
        assertEquals(EmbeddedPlayerState.ENDED, embeddedPlayerStateOf(0))
        assertEquals(EmbeddedPlayerState.PLAYING, embeddedPlayerStateOf(1))
        assertEquals(EmbeddedPlayerState.PAUSED, embeddedPlayerStateOf(2))
        assertEquals(EmbeddedPlayerState.BUFFERING, embeddedPlayerStateOf(3))
        assertEquals(EmbeddedPlayerState.CUED, embeddedPlayerStateOf(5))
    }

    @Test
    fun `an undocumented state code is nothing, not a guess`() {
        assertNull(embeddedPlayerStateOf(4))
        assertNull(embeddedPlayerStateOf(42))
    }

    @Test
    fun `error codes are read as the api documents them`() {
        assertEquals(EmbeddedPlayerError.INVALID_VIDEO, embeddedPlayerErrorOf(2))
        assertEquals(EmbeddedPlayerError.NOT_FOUND, embeddedPlayerErrorOf(100))
        // The same failure under two numbers, which the API's own documentation says.
        assertEquals(EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED, embeddedPlayerErrorOf(101))
        assertEquals(EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED, embeddedPlayerErrorOf(150))
        assertEquals(EmbeddedPlayerError.PLAYER_FAILED, embeddedPlayerErrorOf(5))
    }

    @Test
    fun `an undocumented error code reads as the player having failed`() {
        assertEquals(EmbeddedPlayerError.PLAYER_FAILED, embeddedPlayerErrorOf(999))
    }

    // --- the bridge -----------------------------------------------------------

    @Test
    fun `the bridge turns seconds into whole milliseconds`() {
        val events = mutableListOf<EmbeddedPlayerEvent>()
        val bridge = EmbeddedPlayerBridge(events::add)

        bridge.onTime(12.345, 600.0)

        assertEquals(listOf(EmbeddedPlayerEvent.Time(positionMs = 12_345L, durationMs = 600_000L)), events)
    }

    @Test
    fun `the bridge reads an unknown duration as zero`() {
        // The API hands back NaN for a duration it does not know yet, and Long has no NaN.
        assertEquals(0L, Double.NaN.toWholeMillis())
        assertEquals(0L, (-3.0).toWholeMillis())
    }

    @Test
    fun `the bridge drops an unknown state and maps the rest`() {
        val events = mutableListOf<EmbeddedPlayerEvent>()
        val bridge = EmbeddedPlayerBridge(events::add)

        bridge.onStateChange(4)
        bridge.onStateChange(1)
        bridge.onError(150)
        bridge.onReady()

        assertEquals(
            listOf(
                EmbeddedPlayerEvent.StateChanged(EmbeddedPlayerState.PLAYING),
                EmbeddedPlayerEvent.Failed(EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED),
                EmbeddedPlayerEvent.Ready,
            ),
            events,
        )
    }
}
