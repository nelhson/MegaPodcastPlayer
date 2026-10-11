package md.borisveriga.megapodcastplayer.feature.player.embedded

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.NetworkStatus
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackProgressRecorder
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [EmbeddedVideoViewModel].
 *
 * What matters is the contract with the rest of the app: the position reaches the episode's row,
 * the end marks it played, the app's player is paused on entry, and the screen knows when it has
 * nothing to show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedVideoViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val episode = MutableStateFlow<Episode?>(youTubeEpisode(positionMs = 0L))
    private val online = MutableStateFlow(true)

    private val podcastRepository: PodcastRepository = mockk()
    private val progressRecorder: PlaybackProgressRecorder = mockk(relaxed = true)
    private val playbackRepository: PlaybackRepository = mockk(relaxed = true)
    private val connection: PlaybackConnection = mockk(relaxed = true)
    private val networkStatus: NetworkStatus = mockk()

    @Before
    fun setUp() {
        every { podcastRepository.observeEpisode(EPISODE_ID) } returns episode
        every { podcastRepository.observePodcast(PODCAST_ID) } returns flowOf(podcast)
        every { networkStatus.observeOnline() } returns online
    }

    @Test
    fun `shows the episode, its show and its video`() = runTest {
        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals("Video", state.episode?.title)
            assertEquals("Generic", state.showTitle)
            assertEquals(VIDEO_ID, state.videoId)
            assertTrue(state.showsPlayer)
        }
    }

    @Test
    fun `entering pauses the app's player`() = runTest {
        viewModel().enter()
        runCurrent()

        coVerify { connection.pause() }
    }

    @Test
    fun `starts where the episode was left`() = runTest {
        episode.value = youTubeEpisode(positionMs = 90_000L)

        viewModel().uiState.test {
            assertEquals(90_000L, awaitItem().startMs)
        }
    }

    @Test
    fun `a finished episode starts over`() = runTest {
        episode.value = youTubeEpisode(positionMs = 600_000L, isPlayed = true)

        viewModel().uiState.test {
            assertEquals(0L, awaitItem().startMs)
        }
    }

    @Test
    fun `a start the route asked for wins over the stored position`() = runTest {
        episode.value = youTubeEpisode(positionMs = 90_000L)

        viewModel(startMs = 12_000L).uiState.test {
            assertEquals(12_000L, awaitItem().startMs)
        }
    }

    @Test
    fun `the first tick and every few seconds after are written to the episode`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 1_000L, durationMs = 600_000L))
        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 2_000L, durationMs = 600_000L))
        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 7_000L, durationMs = 600_000L))
        runCurrent()

        coVerify(exactly = 1) { progressRecorder.recordPosition(EPISODE_ID, 1_000L, 600_000L) }
        coVerify(exactly = 0) { progressRecorder.recordPosition(EPISODE_ID, 2_000L, any()) }
        coVerify(exactly = 1) { progressRecorder.recordPosition(EPISODE_ID, 7_000L, 600_000L) }
    }

    @Test
    fun `saving writes the latest position whatever the last tick wrote`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 1_000L, durationMs = 600_000L))
        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 2_500L, durationMs = 600_000L))

        viewModel.savePosition()
        runCurrent()

        coVerify { progressRecorder.recordPosition(EPISODE_ID, 2_500L, 600_000L) }
    }

    @Test
    fun `saving before the player has reported anything writes nothing`() = runTest {
        viewModel().savePosition()
        runCurrent()

        // A zero over a real position would be the screen losing the user's place by opening.
        coVerify(exactly = 0) { progressRecorder.recordPosition(any(), any(), any()) }
    }

    @Test
    fun `an unknown duration is not reported as one`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(EmbeddedPlayerEvent.Time(positionMs = 1_000L, durationMs = 0L))

        viewModel.uiState.test {
            assertNull(awaitItem().durationMs)
        }
        runCurrent()
        coVerify { progressRecorder.recordPosition(EPISODE_ID, 1_000L, null) }
    }

    @Test
    fun `the end of the video marks the episode played`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(EmbeddedPlayerEvent.StateChanged(EmbeddedPlayerState.ENDED))
        runCurrent()

        coVerify { playbackRepository.setPlayed(EPISODE_ID, true, any()) }
    }

    @Test
    fun `a failure takes the player down and says why`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(EmbeddedPlayerEvent.Failed(EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED))

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED, state.error)
            assertFalse(state.showsPlayer)
        }
    }

    @Test
    fun `offline there is no player to show`() = runTest {
        online.value = false

        viewModel().uiState.test {
            assertFalse(awaitItem().showsPlayer)
        }
    }

    @Test
    fun `an episode with no video has nothing to show`() = runTest {
        episode.value = youTubeEpisode(positionMs = 0L).copy(audioUrl = "https://cdn.example.com/a.mp3")

        viewModel().uiState.test {
            val state = awaitItem()
            assertNull(state.videoId)
            assertFalse(state.showsPlayer)
        }
    }

    private fun viewModel(startMs: Long? = null) = EmbeddedVideoViewModel(
        podcastRepository = podcastRepository,
        progressRecorder = progressRecorder,
        playbackRepository = playbackRepository,
        connection = connection,
        networkStatus = networkStatus,
        savedStateHandle = SavedStateHandle(
            buildMap {
                put(EmbeddedVideoViewModel.EPISODE_ID_ARG, EPISODE_ID)
                if (startMs != null) put(EmbeddedVideoViewModel.START_MS_ARG, startMs)
            },
        ),
    )

    private companion object {
        const val EPISODE_ID = "e1"
        const val PODCAST_ID = "p1"
        const val VIDEO_ID = "niTJ2221aS8"

        val podcast = Podcast(
            id = PODCAST_ID,
            itunesId = null,
            title = "Generic",
            author = "Boris Veriga",
            feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PL1",
            artworkUrl = null,
            description = "",
            addedAt = Instant.EPOCH,
            lastRefreshAt = null,
            etag = null,
            lastModified = null,
            autoRefresh = true,
            source = PodcastSource.YOUTUBE,
        )

        fun youTubeEpisode(positionMs: Long, isPlayed: Boolean = false) = Episode(
            id = EPISODE_ID,
            podcastId = PODCAST_ID,
            guid = "yt:video:$VIDEO_ID",
            title = "Video",
            description = "",
            audioUrl = youTubeAudioSentinel(VIDEO_ID),
            artworkUrl = null,
            durationMs = null,
            publishedAt = Instant.EPOCH,
            sizeBytes = null,
            positionMs = positionMs,
            isPlayed = isPlayed,
        )
    }
}
