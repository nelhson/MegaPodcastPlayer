package md.borisveriga.megapodcastplayer.feature.podcast

import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the quality dialog's *Download* button has something to do. */
class VideoDownloadDialogTest {

    private fun download(height: Int, state: DownloadState) =
        VideoDownload(VideoQuality(height), state, percent = 0f)

    @Test
    fun `nothing picked is nothing to download`() {
        assertFalse(canDownload(picked = null, download = null))
    }

    @Test
    fun `any pick downloads when there is no video yet`() {
        assertTrue(canDownload(picked = VideoQuality(720), download = null))
    }

    @Test
    fun `the rendition already kept or on its way is not asked for again`() {
        assertFalse(canDownload(VideoQuality(720), download(720, DownloadState.COMPLETED)))
        assertFalse(canDownload(VideoQuality(720), download(720, DownloadState.DOWNLOADING)))
    }

    @Test
    fun `another rendition replaces the one kept`() {
        assertTrue(canDownload(VideoQuality(1080), download(720, DownloadState.COMPLETED)))
    }

    @Test
    fun `a failed download at the same rendition can be retried`() {
        assertTrue(canDownload(VideoQuality(720), download(720, DownloadState.FAILED)))
    }
}
