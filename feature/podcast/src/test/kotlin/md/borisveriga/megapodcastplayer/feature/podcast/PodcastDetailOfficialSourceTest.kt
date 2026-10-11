package md.borisveriga.megapodcastplayer.feature.podcast

import app.cash.turbine.test
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The show page under the official YouTube source.
 *
 * One rule, asserted from every side: a YouTube episode of such a show never reaches the app's
 * player or the download stack from this screen. The route asks [PodcastDetailViewModel.opensEmbedded]
 * first, and the methods behind the other controls refuse on their own, so a stale caller cannot
 * start what the resolver below would only refuse with an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailOfficialSourceTest : PodcastDetailViewModelFixture() {

    private fun youTubeEpisode(id: String): Episode =
        episode(id, DownloadState.NOT_DOWNLOADED).copy(audioUrl = youTubeAudioSentinel("niTJ2221aS8"))

    /** Makes the show a YouTube one, read under the official source, with [episodes] in it. */
    private suspend fun officialYouTubeShow(vararg shown: Episode) {
        podcastFlow.value = podcast.copy(source = PodcastSource.YOUTUBE)
        youTubeSource.value = YouTubeSource.OFFICIAL
        episodes.value = shown.toList()
        viewModel.uiState.test { awaitItem() }
    }

    @Test
    fun `a youtube show under the official source says so`() = runTest {
        officialYouTubeShow(youTubeEpisode("a"))

        assertTrue(viewModel.uiState.value.isOfficialYouTube)
        assertTrue(viewModel.opensEmbedded("a"))
    }

    @Test
    fun `the same show under the extractor opens the app's player`() = runTest {
        podcastFlow.value = podcast.copy(source = PodcastSource.YOUTUBE)
        episodes.value = listOf(youTubeEpisode("a"))
        viewModel.uiState.test { awaitItem() }

        assertFalse(viewModel.uiState.value.isOfficialYouTube)
        assertFalse(viewModel.opensEmbedded("a"))
    }

    @Test
    fun `an rss show is untouched by the official source`() = runTest {
        youTubeSource.value = YouTubeSource.OFFICIAL
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        viewModel.uiState.test { awaitItem() }

        assertFalse(viewModel.uiState.value.isOfficialYouTube)
        assertFalse(viewModel.opensEmbedded("a"))
    }

    @Test
    fun `no way of playing reaches the app's player`() = runTest {
        officialYouTubeShow(youTubeEpisode("a"))
        var opened = false

        viewModel.togglePlay("a") { opened = true }
        viewModel.listenToEpisode("a") { opened = true }
        viewModel.watchEpisode("a") { opened = true }
        viewModel.playEpisode("a") { opened = true }
        viewModel.playFrom("a", 12_000L) { opened = true }
        runCurrent()

        assertFalse(opened)
        coVerify(exactly = 0) { episodePlayer.play(any()) }
        coVerify(exactly = 0) { episodePlayer.playFrom(any(), any()) }
        coVerify(exactly = 0) { connection.play() }
        coVerify(exactly = 0) { connection.togglePlayPause() }
    }

    @Test
    fun `play next does not touch the queue`() = runTest {
        officialYouTubeShow(youTubeEpisode("a"))

        viewModel.playNext("a")
        runCurrent()

        coVerify(exactly = 0) { episodePlayer.playNext(any()) }
    }

    @Test
    fun `no way of downloading reaches the download stack`() = runTest {
        officialYouTubeShow(youTubeEpisode("a"))

        viewModel.toggleDownload("a")
        viewModel.download("a", DownloadDestination.Unspecified)
        viewModel.swipeDownload("a")
        viewModel.downloadVideo("a", VideoQuality(720))
        viewModel.removeVideoDownload("a")
        viewModel.loadVideoQualities("a")
        viewModel.downloadAndExport("content://tree", "Commute")
        runCurrent()

        coVerify(exactly = 0) { downloadRepository.download(any(), any()) }
        coVerify(exactly = 0) { downloadRepository.downloadVideo(any(), any(), any()) }
        coVerify(exactly = 0) { downloadRepository.removeDownload(any()) }
        coVerify(exactly = 0) { downloadRepository.removeVideoDownload(any()) }
        coVerify(exactly = 0) { videoQualitySource.qualitiesOf(any()) }
        coVerify(exactly = 0) { downloadExporter.start(any(), any(), any(), any(), any()) }
    }
}
