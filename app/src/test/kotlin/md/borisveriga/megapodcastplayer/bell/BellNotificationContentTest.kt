package md.borisveriga.megapodcastplayer.bell

import md.borisveriga.megapodcastplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [bellNotificationContent].
 *
 * The one decision the bell's text involves — which of the two bodies to use — is made here rather
 * than inside a notification builder, so it can be pinned without a notification manager. The cases
 * that matter are the ones where a feed supplies something that is technically a title and reads as
 * nothing.
 */
class BellNotificationContentTest {

    @Test
    fun `a titled episode is named in the body`() {
        val content = bellNotificationContent("The one about ovens")

        assertEquals(R.string.bell_title, content.titleRes)
        assertEquals(R.string.bell_text, content.textRes)
        assertEquals("The one about ovens", content.episodeTitle)
    }

    @Test
    fun `no title falls back to the generic wording`() {
        val content = bellNotificationContent(null)

        assertEquals(R.string.bell_text_unknown_episode, content.textRes)
        assertNull(content.episodeTitle)
    }

    @Test
    fun `an empty title is treated as no title`() {
        // Otherwise the card reads " has ended", which is worse than saying nothing specific.
        val content = bellNotificationContent("")

        assertEquals(R.string.bell_text_unknown_episode, content.textRes)
        assertNull(content.episodeTitle)
    }

    @Test
    fun `a blank title is treated as no title`() {
        val content = bellNotificationContent("   ")

        assertEquals(R.string.bell_text_unknown_episode, content.textRes)
        assertNull(content.episodeTitle)
    }

    @Test
    fun `surrounding whitespace is trimmed off a real title`() {
        val content = bellNotificationContent("  The one about ovens\n")

        assertEquals(R.string.bell_text, content.textRes)
        assertEquals("The one about ovens", content.episodeTitle)
    }
}
