package md.borisveriga.megapodcastplayer.feature.podcast

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The episode sheet's video half: playing an episode as video, and keeping its video on the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailVideoTest : PodcastDetailViewModelFixture() {

    /** A YouTube episode, the only kind with a picture. */
    private fun youTubeEpisode(id: String): Episode =
        episode(id, DownloadState.NOT_DOWNLOADED).copy(audioUrl = youTubeAudioSentinel(VIDEO_ID))

    @Test
    fun `play video opens the video screen only once the player holds the episode`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        coEvery { episodePlayer.play("a") } returns true
        var watching = false
        viewModel.uiState.test { awaitItem() }

        viewModel.watchEpisode("a") { watching = true }
        runCurrent()
        // Still the previous episode: the video screen would leave on sight of it.
        assertFalse(watching)

        playbackState.value = PlaybackState(episodeId = "a")
        runCurrent()
        assertTrue(watching)
    }

    @Test
    fun `play video opens nothing when the player never loads the episode`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        coEvery { episodePlayer.play("a") } returns true
        var watching = false
        viewModel.uiState.test { awaitItem() }

        viewModel.watchEpisode("a") { watching = true }
        advanceTimeBy(WAIT_PAST_TIMEOUT_MS)
        runCurrent()
        // Too late: the wait is over, and the screen would have shown another episode's picture.
        playbackState.value = PlaybackState(episodeId = "a")
        runCurrent()

        assertFalse(watching)
    }

    @Test
    fun `play video on an episode that has gone says so and opens nothing`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        coEvery { episodePlayer.play("a") } returns false
        var watching = false
        viewModel.uiState.test { awaitItem() }

        viewModel.watchEpisode("a") { watching = true }
        runCurrent()

        assertFalse(watching)
        viewModel.uiState.test {
            assertEquals(PodcastDetailMessage.EpisodeUnavailable, awaitItem().message)
        }
    }

    @Test
    fun `the qualities are asked of the extractor for the open episode`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        val offered = listOf(VideoQuality(360), VideoQuality(720))
        coEvery { videoQualitySource.qualitiesOf(VIDEO_ID) } returns offered
        viewModel.uiState.test { awaitItem() }
        viewModel.openEpisode("a")

        viewModel.loadVideoQualities("a")
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(offered, state.videoQualities)
            assertFalse(state.videoQualitiesFailed)
        }
    }

    @Test
    fun `a failed quality lookup is reported and shown, not thrown`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        val failure = IOException("offline")
        coEvery { videoQualitySource.qualitiesOf(any()) } throws failure
        viewModel.uiState.test { awaitItem() }
        viewModel.openEpisode("a")

        viewModel.loadVideoQualities("a")
        runCurrent()

        verify(exactly = 1) { crashReporter.recordNonFatal(any(), failure) }
        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.videoQualities)
            assertTrue(state.videoQualitiesFailed)
        }
    }

    @Test
    fun `an episode that is only sound is never asked about qualities`() = runTest {
        episodes.value = listOf(episode("a", DownloadState.NOT_DOWNLOADED))
        viewModel.uiState.test { awaitItem() }
        viewModel.openEpisode("a")

        viewModel.loadVideoQualities("a")
        runCurrent()

        coVerify(exactly = 0) { videoQualitySource.qualitiesOf(any()) }
    }

    @Test
    fun `downloading a video asks for the chosen rendition and names it`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        coEvery { downloadRepository.downloadVideo("a", VideoQuality(720)) } returns true
        viewModel.uiState.test { awaitItem() }

        viewModel.downloadVideo("a", VideoQuality(720))
        runCurrent()

        coVerify(exactly = 1) { downloadRepository.downloadVideo("a", VideoQuality(720)) }
        viewModel.uiState.test {
            assertEquals(
                PodcastDetailMessage.VideoDownloadQueued("Episode a", VideoQuality(720)),
                awaitItem().message,
            )
        }
    }

    @Test
    fun `a video download the repository refuses reports the episode as unavailable`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        coEvery { downloadRepository.downloadVideo(any(), any()) } returns false
        viewModel.uiState.test { awaitItem() }

        viewModel.downloadVideo("a", VideoQuality(720))
        runCurrent()

        viewModel.uiState.test {
            assertEquals(PodcastDetailMessage.EpisodeUnavailable, awaitItem().message)
        }
    }

    @Test
    fun `removing a finished video says deleted, removing one on its way says cancelled`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"))
        videoDownloads.value = mapOf(
            "a" to VideoDownload(VideoQuality(720), DownloadState.COMPLETED, percent = 100f),
        )
        viewModel.uiState.test { awaitItem() }

        viewModel.removeVideoDownload("a")
        runCurrent()
        coVerify(exactly = 1) { downloadRepository.removeVideoDownload("a") }
        viewModel.uiState.test {
            assertEquals(
                PodcastDetailMessage.VideoDownloadRemoved("Episode a", wasComplete = true),
                awaitItem().message,
            )
        }

        videoDownloads.value = mapOf(
            "a" to VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, percent = 10f),
        )
        viewModel.uiState.test { awaitItem() }
        viewModel.removeVideoDownload("a")
        runCurrent()
        viewModel.uiState.test {
            assertEquals(
                PodcastDetailMessage.VideoDownloadRemoved("Episode a", wasComplete = false),
                awaitItem().message,
            )
        }
    }

    @Test
    fun `the open episode's video download is the one the state names`() = runTest {
        episodes.value = listOf(youTubeEpisode("a"), youTubeEpisode("b"))
        val download = VideoDownload(VideoQuality(480), DownloadState.COMPLETED, percent = 100f)
        videoDownloads.value = mapOf("b" to download)
        viewModel.uiState.test { awaitItem() }

        viewModel.openEpisode("a")
        viewModel.uiState.test { assertNull(awaitItem().openVideoDownload) }

        viewModel.openEpisode("b")
        viewModel.uiState.test { assertEquals(download, awaitItem().openVideoDownload) }
    }

    private companion object {
        /** An eleven-character id of the shape YouTube issues. */
        const val VIDEO_ID = "dQw4w9WgXcQ"

        /** Past the view model's wait for the player, which is three seconds. */
        const val WAIT_PAST_TIMEOUT_MS = 3_500L
    }
}
