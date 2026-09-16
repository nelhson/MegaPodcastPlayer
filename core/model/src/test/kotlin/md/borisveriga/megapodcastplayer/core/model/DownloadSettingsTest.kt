package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [DownloadSettings]'s auto-download bound. */
class DownloadSettingsTest {

    @Test
    fun `keep-all bounds nothing`() {
        val settings = DownloadSettings(keepLimitPerPodcast = DownloadSettings.KEEP_ALL)

        assertFalse(settings.enforcesKeepLimit)
    }

    @Test
    fun `a positive limit is enforced`() {
        val settings = DownloadSettings(keepLimitPerPodcast = 3)

        assertTrue(settings.enforcesKeepLimit)
    }
}
