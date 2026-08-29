package md.borisveriga.bpodcat.core.data.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.media.PlaybackQueueSource
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.Episode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [EpisodePlayer], the bridge from an episode id to the player.
 *
 * The interesting behaviour is the cold-start queue restore: it must happen exactly once, must not
 * trample a player that already has a queue, and must not give up when the service is not yet
 * reachable.
 */
class EpisodePlayerTest {

    private lateinit var playbackRepository: PlaybackRepository
    private lateinit var queueSource: PlaybackQueueSource
    private lateinit var connection: PlaybackConnection
    private lateinit var episodePlayer: EpisodePlayer

    private fun playable(id: String, positionMs: Long = 0L) = PlayableEpisode(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = null,
            positionMs = positionMs,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    @Before
    fun setUp() {
        playbackRepository = mockk(relaxed = true)
        queueSource = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        episodePlayer = EpisodePlayer(playbackRepository, queueSource, connection)
    }

    @Test
    fun `playing an episode resolves it and hands it to the player`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")

        assertTrue(episodePlayer.play("a"))

        coVerify { connection.playNow(playable("a"), 0L) }
    }

    @Test
    fun `playing an episode that is no longer stored reports failure`() = runTest {
        coEvery { playbackRepository.playableEpisode("gone") } returns null

        assertFalse(episodePlayer.play("gone"))

        // Both parameters are matched explicitly: `playNow` has a default argument, and matching
        // only the first makes mockk route through the synthetic defaults bridge, which then reads
        // the stub episode it was handed.
        coVerify(exactly = 0) { connection.playNow(any(), any()) }
    }

    @Test
    fun `queueing writes to the durable queue as well as the player`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")

        assertTrue(episodePlayer.addToQueue("a"))

        coVerify { connection.addToQueue(playable("a")) }
        coVerify { playbackRepository.enqueue("a") }
    }

    @Test
    fun `a cold start loads the persisted queue paused`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        coEvery { queueSource.resumableQueue() } returns
            listOf(playable("a", positionMs = 42_000L), playable("b"))

        episodePlayer.restoreQueue()

        coVerify {
            connection.setQueue(
                episodes = listOf(playable("a", positionMs = 42_000L), playable("b")),
                startIndex = 0,
                startPositionMs = 42_000L,
                playWhenReady = false,
            )
        }
    }

    @Test
    fun `restoring is skipped when the player already has a queue`() = runTest {
        // The process survived; whatever is loaded is more current than the database.
        coEvery { connection.currentState() } returns
            PlaybackState(isConnected = true, queueEpisodeIds = listOf("a"))

        episodePlayer.restoreQueue()

        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `restoring runs only once`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        coEvery { queueSource.resumableQueue() } returns listOf(playable("a"))

        episodePlayer.restoreQueue()
        episodePlayer.restoreQueue()

        coVerify(exactly = 1) { connection.setQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `an unreachable service is retried rather than written off`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = false)
        coEvery { queueSource.resumableQueue() } returns listOf(playable("a"))

        episodePlayer.restoreQueue()
        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }

        // The service finished starting; the next caller must still get its queue back.
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        episodePlayer.restoreQueue()

        coVerify(exactly = 1) { connection.setQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `nothing to resume means nothing is handed to the player`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        coEvery { queueSource.resumableQueue() } returns emptyList()

        episodePlayer.restoreQueue()

        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `removing an episode clears it from the player and from storage`() = runTest {
        episodePlayer.removeFromQueue("a")

        coVerify { connection.removeFromQueue("a") }
        coVerify { playbackRepository.dequeue("a") }
    }
}
