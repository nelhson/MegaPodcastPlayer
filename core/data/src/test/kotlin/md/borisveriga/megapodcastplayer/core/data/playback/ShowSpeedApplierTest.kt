package md.borisveriga.megapodcastplayer.core.data.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ShowSpeedApplier].
 *
 * The case that matters is the one nobody presses a button for: the queue advancing from a show
 * with its own rate to a show without one. Applying the rate where playback is *requested* would
 * leave the second show running at the first one's speed, which is precisely the "changes for no
 * visible reason" failure a per-show speed is supposed to prevent — so both directions are
 * asserted, including the reset back to the app's rate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowSpeedApplierTest {

    private lateinit var connection: PlaybackConnection
    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var showSettings: ShowSettingsRepository

    private val playbackState = MutableStateFlow(PlaybackState())
    private val settingsByPodcast = mutableMapOf<String, ShowSettings>()

    @Before
    fun setUp() {
        connection = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)
        showSettings = mockk(relaxed = true)

        every { connection.playbackState } returns playbackState
        every { playbackRepository.observePlaybackSettings() } returns
            flowOf(PlaybackSettings(speed = 1.2f))
        every { showSettings.observeSettings(any()) } answers {
            flowOf(settingsByPodcast[firstArg<String>()] ?: ShowSettings.DEFAULT)
        }
        coEvery { playbackRepository.playableEpisode(any()) } answers {
            playable(firstArg(), podcastId = firstArg<String>().substringBefore('-'))
        }
    }

    @Test
    fun `an episode of a show with its own rate plays at that rate`() = runTest {
        settingsByPodcast["fast"] = ShowSettings(speed = 2f)
        val applier = applier()

        applier.start()
        playbackState.value = PlaybackState(episodeId = "fast-1")
        runCurrent()

        coVerify { connection.setSpeed(2f) }
    }

    @Test
    fun `advancing to a show with no rate goes back to the app's`() = runTest {
        settingsByPodcast["fast"] = ShowSettings(speed = 2f)
        val applier = applier()

        applier.start()
        playbackState.value = PlaybackState(episodeId = "fast-1")
        runCurrent()
        playbackState.value = PlaybackState(episodeId = "plain-1")
        runCurrent()

        // Leaving 2× running after the show that asked for it would leak the setting into every
        // show played afterwards, which is the same bug seen from the other side.
        coVerify { connection.setSpeed(1.2f) }
    }

    @Test
    fun `an episode the library no longer has is left alone`() = runTest {
        coEvery { playbackRepository.playableEpisode("gone") } returns null
        val applier = applier()

        applier.start()
        playbackState.value = PlaybackState(episodeId = "gone")
        runCurrent()

        coVerify(exactly = 0) { connection.setSpeed(any()) }
    }

    /**
     * Builds the applier on the test's background scope.
     *
     * The background scope, not the test's own: this collector never finishes — in production it
     * ends with the process — and launching it as a child of the test body would leave `runTest`
     * waiting a minute for it before failing.
     *
     * @return the applier, not yet started.
     */
    private fun TestScope.applier() = ShowSpeedApplier(
        connection = connection,
        playbackRepository = playbackRepository,
        showSettings = showSettings,
        scope = backgroundScope,
    )

    /**
     * A playable episode belonging to a show.
     *
     * @param id the episode id.
     * @param podcastId the show it belongs to.
     * @return the playable episode.
     */
    private fun playable(id: String, podcastId: String) = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = podcastId,
            guid = id,
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = null,
        ),
        showTitle = "Show $podcastId",
        showArtworkUrl = null,
    )
}
