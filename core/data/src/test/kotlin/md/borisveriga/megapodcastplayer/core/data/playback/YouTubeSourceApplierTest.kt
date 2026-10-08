package md.borisveriga.megapodcastplayer.core.data.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import md.borisveriga.megapodcastplayer.core.testing.InMemoryPreferencesDataStore
import org.junit.Before
import org.junit.Test

/**
 * Tests for [YouTubeSourceApplier].
 *
 * The queue is the one place a YouTube episode can be waiting with nobody looking, so the cases
 * are: the choice made while the queue holds one, the choice already made when the process starts,
 * and the RSS episodes beside it, which must not move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSourceApplierTest {

    private lateinit var preferences: UserPreferencesDataSource
    private lateinit var connection: PlaybackConnection
    private lateinit var playbackRepository: PlaybackRepository

    @Before
    fun setUp() {
        preferences = UserPreferencesDataSource(InMemoryPreferencesDataStore())
        connection = mockk(relaxed = true)
        playbackRepository = mockk(relaxed = true)

        coEvery { connection.awaitRestored() } returns true
        coEvery { connection.currentState() } returns PlaybackState(
            episodeId = "rss-1",
            queueEpisodeIds = listOf("rss-1", "yt-1", "rss-2", "yt-2"),
        )
        coEvery { playbackRepository.playableEpisode(any()) } answers {
            val id = firstArg<String>()
            if (id.startsWith("yt")) youTube(id) else rss(id)
        }
    }

    @Test
    fun `choosing the official source takes every youtube episode out of the queue`() = runTest {
        val applier = applier()
        applier.start()
        runCurrent()

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        runCurrent()

        coVerify { connection.removeFromQueue("yt-1") }
        coVerify { connection.removeFromQueue("yt-2") }
        coVerify { playbackRepository.dequeue("yt-1") }
        coVerify { playbackRepository.dequeue("yt-2") }
    }

    @Test
    fun `rss episodes stay where they were`() = runTest {
        val applier = applier()
        applier.start()

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        runCurrent()

        coVerify(exactly = 0) { connection.removeFromQueue("rss-1") }
        coVerify(exactly = 0) { connection.removeFromQueue("rss-2") }
        coVerify(exactly = 0) { playbackRepository.dequeue("rss-1") }
    }

    @Test
    fun `a choice made while the process was dead is applied on start`() = runTest {
        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        val applier = applier()

        applier.start()
        runCurrent()

        coVerify { connection.removeFromQueue("yt-1") }
    }

    @Test
    fun `the extractor leaves the queue alone`() = runTest {
        val applier = applier()
        applier.start()

        preferences.setYouTubeSource(YouTubeSource.EXTRACTOR)
        runCurrent()

        coVerify(exactly = 0) { connection.removeFromQueue(any()) }
        coVerify(exactly = 0) { playbackRepository.dequeue(any()) }
    }

    @Test
    fun `an unreachable player is left alone`() = runTest {
        coEvery { connection.awaitRestored() } returns false
        val applier = applier()
        applier.start()

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        runCurrent()

        // Nothing is playing in that case; the durable queue is trimmed next time the service is up.
        coVerify(exactly = 0) { playbackRepository.dequeue(any()) }
    }

    @Test
    fun `an episode the library no longer has is skipped, not crashed on`() = runTest {
        coEvery { playbackRepository.playableEpisode("yt-1") } returns null
        val applier = applier()
        applier.start()

        preferences.setYouTubeSource(YouTubeSource.OFFICIAL)
        runCurrent()

        coVerify(exactly = 0) { connection.removeFromQueue("yt-1") }
        coVerify { connection.removeFromQueue("yt-2") }
    }

    /**
     * Builds the applier on the test's background scope, for the reason [ShowSpeedApplierTest]
     * gives: the collector never finishes, and a child of the test body would hold `runTest` open.
     */
    private fun TestScope.applier() = YouTubeSourceApplier(
        userPreferences = preferences,
        connection = connection,
        playbackRepository = playbackRepository,
        scope = backgroundScope,
    )

    private fun rss(id: String) = playable(id, audioUrl = "https://cdn.example.com/$id.mp3")

    private fun youTube(id: String) = playable(id, audioUrl = youTubeAudioSentinel("niTJ2221aS8"))

    private fun playable(id: String, audioUrl: String) = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = "show",
            guid = id,
            title = "Episode $id",
            description = "",
            audioUrl = audioUrl,
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.EPOCH,
            sizeBytes = null,
        ),
        showTitle = "Show",
        showArtworkUrl = null,
    )
}
