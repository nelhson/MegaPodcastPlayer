package md.borisveriga.megapodcastplayer.feature.podcast

import java.time.Instant
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the episode sheet's audio download button says, which must name everything it removes. */
class EpisodeDownloadLabelTest {

    private fun episode(state: DownloadState) = Episode(
        id = "a",
        podcastId = "p",
        guid = "g",
        title = "Episode",
        description = "",
        audioUrl = "https://example.com/a.mp3",
        artworkUrl = null,
        durationMs = 60_000L,
        publishedAt = Instant.EPOCH,
        sizeBytes = null,
        downloadState = state,
    )

    @Test
    fun `an episode that is only sound keeps the swipe's words`() {
        assertEquals(
            R.string.podcast_action_delete_download,
            episode(DownloadState.COMPLETED).downloadLabelRes(hasVideo = false, hasVideoDownload = false),
        )
    }

    @Test
    fun `beside a video button the audio button says audio`() {
        assertEquals(
            R.string.episode_download_audio,
            episode(DownloadState.NOT_DOWNLOADED).downloadLabelRes(hasVideo = true, hasVideoDownload = false),
        )
        assertEquals(
            R.string.episode_delete_audio_download,
            episode(DownloadState.COMPLETED).downloadLabelRes(hasVideo = true, hasVideoDownload = false),
        )
    }

    @Test
    fun `with a video kept, removing the audio says the video goes too`() {
        assertEquals(
            R.string.episode_delete_audio_and_video_download,
            episode(DownloadState.COMPLETED).downloadLabelRes(hasVideo = true, hasVideoDownload = true),
        )
        assertEquals(
            R.string.episode_cancel_audio_and_video_download,
            episode(DownloadState.DOWNLOADING).downloadLabelRes(hasVideo = true, hasVideoDownload = true),
        )
    }
}
