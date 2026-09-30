package md.borisveriga.megapodcastplayer.feature.player.video

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
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
        coEvery { qualitySource.qualitiesOf(any()) } returns
            listOf(VideoQuality(720), VideoQuality(1080))

        viewModel = VideoViewModel(
            connection = connection,
            playbackRepository = playbackRepository,
            qualitySource = qualitySource,
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

    private companion object {
        const val VIDEO_ID = "niTJ2221aS8"
    }
}
