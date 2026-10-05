package md.borisveriga.megapodcastplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [resolveOnVideo]: what the shell believes about the video screen on a frame where the
 * back stack has not answered yet.
 *
 * That frame is the first after a rotation. Believing "not on video" there drew the collapsed bar,
 * with a picture of its own, under the video screen for one frame — long enough to take the
 * player's output and leave the screen black.
 */
class ResolveOnVideoTest {

    @Test
    fun `before the back stack answers, the last answer stands`() {
        assertTrue(resolveOnVideo(known = null, remembered = true))
        assertFalse(resolveOnVideo(known = null, remembered = false))
    }

    @Test
    fun `once the back stack answers, it is believed over what was remembered`() {
        assertFalse(resolveOnVideo(known = false, remembered = true))
        assertTrue(resolveOnVideo(known = true, remembered = false))
    }
}
