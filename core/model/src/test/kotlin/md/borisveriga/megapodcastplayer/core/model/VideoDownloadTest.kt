package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [VideoDownload]. */
class VideoDownloadTest {

    @Test
    fun `only a completed picture download counts as complete`() {
        DownloadState.entries.forEach { state ->
            val download = VideoDownload(VideoQuality(720), state, percent = 50f)
            if (state == DownloadState.COMPLETED) {
                assertTrue(download.isComplete)
            } else {
                assertFalse("$state", download.isComplete)
            }
        }
    }
}
