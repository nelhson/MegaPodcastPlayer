package md.borisveriga.megapodcastplayer.feature.downloads

import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which badges a Downloads row draws: only for what has finished arriving. */
class DownloadedKindsTest {

    private fun video(state: DownloadState) = VideoDownload(VideoQuality(720), state, percent = 0f)

    @Test
    fun `a finished audio download is badged as audio`() {
        assertEquals(
            listOf(DownloadedKind.Audio),
            downloadedKinds(audio = DownloadState.COMPLETED, video = null),
        )
    }

    @Test
    fun `audio and video both finished carry both badges, sound first`() {
        val done = video(DownloadState.COMPLETED)
        assertEquals(
            listOf(DownloadedKind.Audio, DownloadedKind.Video(done)),
            downloadedKinds(audio = DownloadState.COMPLETED, video = done),
        )
    }

    @Test
    fun `nothing still arriving or failed is badged`() {
        assertEquals(
            emptyList<DownloadedKind>(),
            downloadedKinds(audio = DownloadState.DOWNLOADING, video = video(DownloadState.QUEUED)),
        )
        assertEquals(
            emptyList<DownloadedKind>(),
            downloadedKinds(audio = DownloadState.FAILED, video = video(DownloadState.FAILED)),
        )
    }

    @Test
    fun `a finished video is badged even while its audio is still coming`() {
        val done = video(DownloadState.COMPLETED)
        assertEquals(
            listOf(DownloadedKind.Video(done)),
            downloadedKinds(audio = DownloadState.DOWNLOADING, video = done),
        )
    }
}
