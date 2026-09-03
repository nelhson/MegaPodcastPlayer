package md.borisveriga.megapodcastplayer.core.media.download

import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Media3 [Download] to [EpisodeDownloadStatus] translation.
 *
 * Robolectric is needed only because a [DownloadRequest] holds an `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeDownloadStatusTest {

    @Test
    fun `every media3 state maps to the app's own`() {
        assertEquals(DownloadState.QUEUED, downloadStateOf(Download.STATE_QUEUED))
        assertEquals(DownloadState.DOWNLOADING, downloadStateOf(Download.STATE_DOWNLOADING))
        assertEquals(DownloadState.COMPLETED, downloadStateOf(Download.STATE_COMPLETED))
        assertEquals(DownloadState.FAILED, downloadStateOf(Download.STATE_FAILED))
    }

    @Test
    fun `waiting for wi-fi reads as queued rather than as a state of its own`() {
        // STATE_STOPPED is what an unmet "unmetered network" requirement produces, and to the user
        // that is indistinguishable from waiting in line.
        assertEquals(DownloadState.QUEUED, downloadStateOf(Download.STATE_STOPPED))
        assertEquals(DownloadState.QUEUED, downloadStateOf(Download.STATE_RESTARTING))
    }

    @Test
    fun `a download being removed already reads as not downloaded`() {
        assertEquals(DownloadState.NOT_DOWNLOADED, downloadStateOf(Download.STATE_REMOVING))
    }

    @Test
    fun `an in-progress download carries its bytes and percentage`() {
        val status = download(
            state = Download.STATE_DOWNLOADING,
            bytesDownloaded = 5_000_000L,
            percent = 42.5f,
        ).asEpisodeDownloadStatus()

        assertEquals("episode-1", status.episodeId)
        assertEquals(DownloadState.DOWNLOADING, status.state)
        assertEquals(5_000_000L, status.downloadedBytes)
        assertEquals(42.5f, status.percent, 0.001f)
    }

    @Test
    fun `a completed download reports a full hundred percent`() {
        // Media3 leaves percentDownloaded wherever the last progress callback put it, which for a
        // short file can be just under 100 forever. A progress bar stuck at 99% on a finished
        // download is a bug report waiting to happen.
        val status = download(
            state = Download.STATE_COMPLETED,
            bytesDownloaded = 9_999_999L,
            percent = 99.4f,
        ).asEpisodeDownloadStatus()

        assertEquals(DownloadState.COMPLETED, status.state)
        assertEquals(100f, status.percent, 0.001f)
    }

    @Test
    fun `an unknown percentage becomes zero rather than NaN`() {
        // Media3 reports NaN until it knows the content length, and NaN in a progress indicator
        // renders as an empty bar at best and throws at worst.
        val status = download(
            state = Download.STATE_QUEUED,
            bytesDownloaded = 0L,
            percent = Float.NaN,
        ).asEpisodeDownloadStatus()

        assertEquals(0f, status.percent, 0.001f)
    }

    @Test
    fun `a removed episode reports the not-downloaded status`() {
        val status = EpisodeDownloadStatus.notDownloaded("episode-9")

        assertEquals("episode-9", status.episodeId)
        assertEquals(DownloadState.NOT_DOWNLOADED, status.state)
        assertEquals(0L, status.downloadedBytes)
        assertEquals(0f, status.percent, 0.001f)
    }

    /** A [Download] in the given state, with the given progress. */
    private fun download(state: Int, bytesDownloaded: Long, percent: Float): Download = Download(
        DownloadRequest.Builder("episode-1", "https://example.com/1.mp3".toUri()).build(),
        state,
        /* startTimeMs = */ 0L,
        /* updateTimeMs = */ 0L,
        /* contentLength = */ 10_000_000L,
        /* stopReason = */ Download.STOP_REASON_NONE,
        /* failureReason = */ Download.FAILURE_REASON_NONE,
        DownloadProgress().apply {
            this.bytesDownloaded = bytesDownloaded
            this.percentDownloaded = percent
        },
    )
}
