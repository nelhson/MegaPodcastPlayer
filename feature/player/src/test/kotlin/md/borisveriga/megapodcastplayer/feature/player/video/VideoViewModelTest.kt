package md.borisveriga.megapodcastplayer.feature.player.video

import android.view.TextureView
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [VideoViewModel].
 *
 * What is worth pinning is the bracket — show the picture on the way in, hand back to sound on the
 * way out — and the two things that happen inside it without the user asking: the screen following
 * the queue to the next episode with a picture, and the renditions being asked for once per video
 * rather than once per position tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playbackState = MutableStateFlow(PlaybackState())
    private val settings = MutableStateFlow(PlaybackSettings())
    private val preferredQuality = MutableStateFlow(VideoQuality.DEFAULT)

    private val connection: PlaybackConnection = mockk(relaxed = true)
    private val playbackRepository: PlaybackRepository = mockk(relaxed = true)
    private val qualitySource: VideoQualitySource = mockk()
    private val crashReporter: CrashReporter = mockk(relaxed = true)
    private val downloadRepository: DownloadRepository = mockk(relaxed = true)
    private val videoDownloads = MutableStateFlow<Map<String, VideoDownload>>(emptyMap())

    /** Unconfined, so a launched command has run by the time the call returns. */
    private val applicationScope = CoroutineScope(UnconfinedTestDispatcher())

    private lateinit var viewModel: VideoViewModel

    @Before
    fun setUp() {
        every { connection.playbackState } returns playbackState
        coEvery { connection.enterVideo(any()) } returns true
        coEvery { connection.exitVideo() } returns true
        every { playbackRepository.observePlaybackSettings() } returns settings
        every { playbackRepository.observeVideoQuality() } returns preferredQuality
        every { downloadRepository.observeVideoDownloads() } returns videoDownloads
        coEvery { downloadRepository.downloadVideo(any(), any()) } returns true
        coEvery { qualitySource.qualitiesOf(any()) } returns
            listOf(VideoQuality(720), VideoQuality(1080))

        viewModel = VideoViewModel(
            connection = connection,
            playbackRepository = playbackRepository,
            qualitySource = qualitySource,
            downloadRepository = downloadRepository,
            crashReporter = crashReporter,
            applicationScope = applicationScope,
        )
    }

    /** A connected player on a YouTube episode, [positionMs] in. */
    private fun watching(videoId: String = VIDEO_ID, positionMs: Long = 1_000L) = PlaybackState(
        isConnected = true,
        episodeId = "ep-$videoId",
        youTubeVideoId = videoId,
        positionMs = positionMs,
    )

    // --- the bracket --------------------------------------------------------

    @Test
    fun `entering shows the picture at the remembered rendition`() = runTest {
        preferredQuality.value = VideoQuality(1080)
        playbackState.value = watching()

        viewModel.enter()

        coVerify(exactly = 1) { connection.enterVideo(VideoQuality(1080)) }
    }

    @Test
    fun `leaving hands back to sound`() = runTest {
        playbackState.value = watching()
        viewModel.enter()

        viewModel.exit()

        coVerify(exactly = 1) { connection.exitVideo() }
    }

    @Test
    fun `a refusal is shown once`() = runTest {
        coEvery { connection.enterVideo(any()) } returns false
        playbackState.value = watching()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        viewModel.enter()
        assertTrue(viewModel.uiState.value.refused)

        viewModel.onRefusalShown()
        assertFalse(viewModel.uiState.value.refused)
    }

    @Test
    fun `asking again while the picture is wanted is the retry for a refusal`() = runTest {
        // The shell asks when the player is put in video and the screen asks each time it starts,
        // so two asks in a row are the ordinary case. The service answers a repeat by doing nothing.
        coEvery { connection.enterVideo(any()) } returns false
        playbackState.value = watching()
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        viewModel.enter()
        assertTrue(viewModel.uiState.value.refused)

        coEvery { connection.enterVideo(any()) } returns true
        viewModel.enter()

        // The refusal belonged to the ask this one replaced; saying it over a picture that is now
        // showing would be saying something no longer true.
        assertFalse(viewModel.uiState.value.refused)
        coVerify(exactly = 2) { connection.enterVideo(VideoQuality.DEFAULT) }
    }

    @Test
    fun `the bar's texture is handed to the player and taken back`() = runTest {
        val texture: TextureView = mockk()

        viewModel.attachTexture(texture)
        viewModel.detachTexture(texture)

        coVerifyOrder {
            connection.attachVideoTexture(texture)
            connection.detachVideoTexture(texture)
        }
    }

    @Test
    fun `a visit that ended does not leave its state behind for the next one`() = runTest {
        // One view model serves every visit to the screen. The last visit ended on an episode with
        // nothing to show, which is the state that sends the screen away.
        playbackState.value = PlaybackState(isConnected = true, episodeId = "feed-episode")
        val visit = launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        assertTrue(viewModel.uiState.value.playback.isConnected)
        visit.cancel()

        advanceTimeBy(STATE_KEPT_MS + 1)

        // Read by the next visit before anything fresh arrives: it must say "not connected yet",
        // which the screen waits on, not "connected, nothing to watch", which it leaves on.
        assertEquals(VideoUiState(), viewModel.uiState.value)
    }

    @Test
    fun `leaving before the rendition is read never shows the picture`() = runTest {
        // The rendition comes off disk; a Home press inside that wait must not be overtaken by an
        // enter that lands after the exit, with no screen left to show the picture on.
        val slowDisk = MutableSharedFlow<VideoQuality>()
        every { playbackRepository.observeVideoQuality() } returns slowDisk
        playbackState.value = watching()

        viewModel.enter()
        viewModel.exit()
        slowDisk.emit(VideoQuality(1080))

        coVerify(exactly = 0) { connection.enterVideo(any()) }
        coVerify(exactly = 1) { connection.exitVideo() }
    }

    @Test
    fun `a picture that fell back to sound is said so`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching().copy(videoQuality = VideoQuality(720))
        viewModel.enter()

        // The service hands the same video back to sound after the picture failed.
        playbackState.value = watching()

        assertTrue(viewModel.uiState.value.refused)
    }

    @Test
    fun `leaving the screen is not mistaken for a fall back`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching().copy(videoQuality = VideoQuality(720))
        viewModel.enter()

        viewModel.exit()
        playbackState.value = watching()

        assertFalse(viewModel.uiState.value.refused)
    }

    @Test
    fun `moving on to another video in sound is not a fall back`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching(videoId = "aaaaaaaaaaa").copy(videoQuality = VideoQuality(720))
        viewModel.enter()

        playbackState.value = watching(videoId = "bbbbbbbbbbb")

        assertFalse(viewModel.uiState.value.refused)
    }

    @Test
    fun `changing the rendition remembers it and then asks for it`() = runTest {
        playbackState.value = watching()

        viewModel.setQuality(VideoQuality(1080))

        // Remembered first: a swap that failed must not leave the next video opening at the old
        // rendition the user just turned away from.
        coVerifyOrder {
            playbackRepository.setVideoQuality(VideoQuality(1080))
            connection.enterVideo(VideoQuality(1080))
        }
    }

    // --- following the queue ------------------------------------------------

    @Test
    fun `the screen follows the queue to the next episode with a picture`() = runTest {
        playbackState.value = watching(videoId = "aaaaaaaaaaa")
        viewModel.enter()

        playbackState.value = watching(videoId = "bbbbbbbbbbb")

        coVerify(exactly = 2) { connection.enterVideo(any()) }
    }

    @Test
    fun `the queue moving on to a feed episode is not followed`() = runTest {
        playbackState.value = watching()
        viewModel.enter()

        playbackState.value = PlaybackState(isConnected = true, episodeId = "feed-1")

        coVerify(exactly = 1) { connection.enterVideo(any()) }
    }

    @Test
    fun `once left, the screen no longer follows`() = runTest {
        playbackState.value = watching(videoId = "aaaaaaaaaaa")
        viewModel.enter()
        viewModel.exit()

        playbackState.value = watching(videoId = "bbbbbbbbbbb")

        coVerify(exactly = 1) { connection.enterVideo(any()) }
    }

    @Test
    fun `a position tick is not an episode change`() = runTest {
        playbackState.value = watching(positionMs = 1_000L)
        viewModel.enter()

        playbackState.value = watching(positionMs = 1_500L)

        coVerify(exactly = 1) { connection.enterVideo(any()) }
    }

    // --- the renditions -----------------------------------------------------

    @Test
    fun `the renditions are asked for once per video, not once per tick`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        playbackState.value = watching(positionMs = 1_000L)
        playbackState.value = watching(positionMs = 1_500L)
        playbackState.value = watching(positionMs = 2_000L)

        assertEquals(listOf(VideoQuality(720), VideoQuality(1080)), viewModel.uiState.value.qualities)
        coVerify(exactly = 1) { qualitySource.qualitiesOf(VIDEO_ID) }
    }

    @Test
    fun `with nothing loaded there are no renditions to list`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        assertNull(viewModel.uiState.value.qualities)
        coVerify(exactly = 0) { qualitySource.qualitiesOf(any()) }
    }

    @Test
    fun `a lookup that fails is reported and shown, not thrown`() = runTest {
        val failure = IOException("YouTube changed something")
        coEvery { qualitySource.qualitiesOf(any()) } throws failure
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        playbackState.value = watching()

        assertTrue(viewModel.uiState.value.qualitiesFailed)
        assertNull(viewModel.uiState.value.qualities)
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), failure) }
    }

    @Test
    fun `the quality button names what is showing, else what would be`() = runTest {
        preferredQuality.value = VideoQuality(1080)
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        playbackState.value = watching()
        assertEquals(VideoQuality(1080), viewModel.uiState.value.qualityShown)

        playbackState.value = watching().copy(videoQuality = VideoQuality(720))
        assertEquals(VideoQuality(720), viewModel.uiState.value.qualityShown)
    }

    // --- downloads ------------------------------------------------------------

    @Test
    fun `a downloaded video is shown at its own rendition, not the remembered one`() = runTest {
        // Asking for any other height would stream what is already on disk, or fail offline.
        preferredQuality.value = VideoQuality(1080)
        videoDownloads.value = mapOf("ep-$VIDEO_ID" to downloaded(VideoQuality(480)))
        playbackState.value = watching()

        viewModel.enter()

        coVerify(exactly = 1) { connection.enterVideo(VideoQuality(480)) }
    }

    @Test
    fun `a video still downloading does not decide the rendition`() = runTest {
        preferredQuality.value = VideoQuality(1080)
        videoDownloads.value = mapOf(
            "ep-$VIDEO_ID" to VideoDownload(VideoQuality(480), DownloadState.DOWNLOADING, 10f),
        )
        playbackState.value = watching()

        viewModel.enter()

        coVerify(exactly = 1) { connection.enterVideo(VideoQuality(1080)) }
    }

    @Test
    fun `the state carries the loaded episode's video download and no other`() = runTest {
        val mine = downloaded(VideoQuality(720))
        videoDownloads.value = mapOf("ep-$VIDEO_ID" to mine, "ep-other" to downloaded(VideoQuality(360)))
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        playbackState.value = watching()
        assertEquals(mine, viewModel.uiState.value.videoDownload)

        playbackState.value = watching(videoId = "unsavedVid1")
        assertNull(viewModel.uiState.value.videoDownload)
    }

    @Test
    fun `offline, a downloaded video still offers its own rendition`() = runTest {
        coEvery { qualitySource.qualitiesOf(any()) } throws IOException("offline")
        videoDownloads.value = mapOf("ep-$VIDEO_ID" to downloaded(VideoQuality(720)))
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }

        playbackState.value = watching()

        assertFalse(viewModel.uiState.value.qualitiesFailed)
        assertEquals(listOf(VideoQuality(720)), viewModel.uiState.value.qualities)
    }

    @Test
    fun `downloading asks for the loaded episode at the chosen rendition and says so`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching()

        viewModel.downloadVideo(VideoQuality(1080))

        coVerify(exactly = 1) { downloadRepository.downloadVideo("ep-$VIDEO_ID", VideoQuality(1080)) }
        assertEquals(
            VideoDownloadMessage.Queued(VideoQuality(1080)),
            viewModel.uiState.value.downloadMessage,
        )
        viewModel.onDownloadMessageShown()
        assertNull(viewModel.uiState.value.downloadMessage)
    }

    @Test
    fun `a download the repository refuses is said to have failed`() = runTest {
        coEvery { downloadRepository.downloadVideo(any(), any()) } returns false
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching()

        viewModel.downloadVideo(VideoQuality(720))

        assertEquals(VideoDownloadMessage.Failed, viewModel.uiState.value.downloadMessage)
    }

    @Test
    fun `a finished video is deleted and a transfer is cancelled`() = runTest {
        backgroundScope.launch(mainDispatcherRule.dispatcher) { viewModel.uiState.collect {} }
        playbackState.value = watching()

        videoDownloads.value = mapOf("ep-$VIDEO_ID" to downloaded(VideoQuality(720)))
        viewModel.deleteVideoDownload()
        assertEquals(VideoDownloadMessage.Deleted, viewModel.uiState.value.downloadMessage)

        videoDownloads.value = mapOf(
            "ep-$VIDEO_ID" to VideoDownload(VideoQuality(720), DownloadState.QUEUED, 0f),
        )
        viewModel.deleteVideoDownload()
        assertEquals(VideoDownloadMessage.Cancelled, viewModel.uiState.value.downloadMessage)

        coVerify(exactly = 2) { downloadRepository.removeVideoDownload("ep-$VIDEO_ID") }
    }

    /** A finished video download at [quality]. */
    private fun downloaded(quality: VideoQuality) =
        VideoDownload(quality, DownloadState.COMPLETED, percent = 100f)

    private companion object {
        const val VIDEO_ID = "niTJ2221aS8"

        /** How long the view model keeps its state after the last subscriber leaves. */
        const val STATE_KEPT_MS = 5_000L
    }
}
