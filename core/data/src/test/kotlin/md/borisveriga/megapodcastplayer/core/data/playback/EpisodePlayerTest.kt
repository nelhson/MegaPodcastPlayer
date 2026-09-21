package md.borisveriga.megapodcastplayer.core.data.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackQueueSource
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.QueueAddResult
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import org.junit.Assert.assertEquals
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

    private lateinit var showSettings: ShowSettingsRepository

    @Before
    fun setUp() {
        playbackRepository = mockk(relaxed = true)
        queueSource = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        showSettings = mockk(relaxed = true)
        // No show has an intro until a test gives it one; every start then resumes as before.
        every { showSettings.observeSettings(any()) } returns flowOf(ShowSettings.DEFAULT)
        episodePlayer = EpisodePlayer(playbackRepository, queueSource, showSettings, connection)
    }

    @Test
    fun `playing an episode resolves it and hands it to the player`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")

        assertTrue(episodePlayer.play("a"))

        coVerify { connection.playNow(playable("a"), 0L) }
    }

    @Test
    fun `an unstarted episode of a show with an intro begins after it`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")
        every { showSettings.observeSettings(any()) } returns
            flowOf(ShowSettings(skipIntroMs = 40_000L))

        assertTrue(episodePlayer.play("a"))

        coVerify { connection.playNow(playable("a"), 40_000L) }
    }

    @Test
    fun `an episode already in progress resumes rather than jumping past the intro`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a", positionMs = 5_000L)
        every { showSettings.observeSettings(any()) } returns
            flowOf(ShowSettings(skipIntroMs = 40_000L))

        assertTrue(episodePlayer.play("a"))

        // Skipping ahead here would be the app losing the user's place rather than saving them a
        // tap: they are already past the intro, or deliberately inside it.
        coVerify { connection.playNow(playable("a", positionMs = 5_000L), 5_000L) }
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
        coEvery { connection.addToQueue(playable("a")) } returns QueueAddResult.ADDED

        assertEquals(QueueAddResult.ADDED, episodePlayer.addToQueue("a"))

        coVerify { connection.addToQueue(playable("a")) }
        coVerify { playbackRepository.enqueue("a") }
    }

    /** What the player did is what the user will see, so it is what the caller is told. */
    @Test
    fun `queueing an episode left behind the one playing reports the move`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")
        coEvery { connection.addToQueue(playable("a")) } returns QueueAddResult.MOVED_TO_END

        assertEquals(QueueAddResult.MOVED_TO_END, episodePlayer.addToQueue("a"))
    }

    /** The durable queue is the whole point of the double write: the intent must not vanish. */
    @Test
    fun `queueing with the player unreachable still writes the queue, and counts as added`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")
        coEvery { connection.addToQueue(playable("a")) } returns QueueAddResult.UNREACHABLE

        assertEquals(QueueAddResult.ADDED, episodePlayer.addToQueue("a"))

        coVerify { playbackRepository.enqueue("a") }
    }

    @Test
    fun `an episode the player refuses is not written to the queue either`() = runTest {
        coEvery { playbackRepository.playableEpisode("a") } returns playable("a")
        coEvery { connection.addToQueue(playable("a")) } returns QueueAddResult.UNPLAYABLE

        assertEquals(QueueAddResult.UNPLAYABLE, episodePlayer.addToQueue("a"))

        coVerify(exactly = 0) { playbackRepository.enqueue(any()) }
    }

    @Test
    fun `queueing an episode that is no longer stored is refused`() = runTest {
        coEvery { playbackRepository.playableEpisode("gone") } returns null

        assertEquals(QueueAddResult.UNPLAYABLE, episodePlayer.addToQueue("gone"))
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
    fun `two restores that begin together still load the queue once`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        coEvery { queueSource.resumableQueue() } returns listOf(playable("a"))

        // What a cold start actually does: the player screen and the Resume shortcut both ask, and
        // neither has finished by the time the other begins. A second load landing after the
        // shortcut's play would pause the episode the user asked to carry on with.
        val first = launch { episodePlayer.restoreQueue() }
        val second = launch { episodePlayer.restoreQueue() }
        first.join()
        second.join()

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
    fun `resuming from a cold start loads the persisted queue and plays it`() = runTest {
        // Empty when the restore looks, loaded by the time the play does: the two readings a cold
        // start actually produces, in order.
        coEvery { connection.currentState() } returnsMany listOf(
            PlaybackState(isConnected = true),
            PlaybackState(isConnected = true, episodeId = "a", queueEpisodeIds = listOf("a", "b")),
        )
        coEvery { queueSource.resumableQueue() } returns
            listOf(playable("a", positionMs = 42_000L), playable("b"))

        assertTrue(episodePlayer.resume())

        // Loaded exactly as an ordinary cold start loads it — paused, at the stored position —
        // and then started, rather than by a second route that could resume a different episode.
        coVerify {
            connection.setQueue(
                episodes = listOf(playable("a", positionMs = 42_000L), playable("b")),
                startIndex = 0,
                startPositionMs = 42_000L,
                playWhenReady = false,
            )
        }
        coVerify { connection.play() }
    }

    @Test
    fun `resuming a player that already has an episode just presses play`() = runTest {
        coEvery { connection.currentState() } returns
            PlaybackState(isConnected = true, episodeId = "a", queueEpisodeIds = listOf("a"))

        assertTrue(episodePlayer.resume())

        // Nothing is reloaded: the queue is already the user's, and setting it again would throw
        // away the position the player has moved on to since it was stored.
        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }
        coVerify { connection.play() }
    }

    @Test
    fun `resuming with nothing to resume plays nothing and says so`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)
        coEvery { queueSource.resumableQueue() } returns emptyList()

        // The caller's cue to open the app rather than an empty player over it.
        assertFalse(episodePlayer.resume())

        coVerify(exactly = 0) { connection.play() }
    }

    @Test
    fun `removing an episode clears it from the player and from storage`() = runTest {
        episodePlayer.removeFromQueue("a")

        coVerify { connection.removeFromQueue("a") }
        coVerify { playbackRepository.dequeue("a") }
    }

    @Test
    fun `a reorder is applied to the player and persisted whole`() = runTest {
        episodePlayer.moveInQueue(fromIndex = 3, toIndex = 1, orderedIds = listOf("a", "d", "b", "c"))

        coVerify { connection.moveInQueue(3, 1) }
        // The durable write is the whole order rather than the same two indices: the table's
        // positions are what the queue is, and a pair of indices could only be applied to whatever
        // the table happens to hold, which is not necessarily what the user was looking at.
        coVerify { playbackRepository.reorderQueue(listOf("a", "d", "b", "c")) }
    }
}
