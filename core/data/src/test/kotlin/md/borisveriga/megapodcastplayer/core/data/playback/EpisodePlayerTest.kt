package md.borisveriga.megapodcastplayer.core.data.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
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
 * The cold-start queue restore is not here: the service does it, and `QueueRestoreTest` in
 * `:core:media` covers it. What is left of it on this side is that *Resume* waits for it.
 */
class EpisodePlayerTest {

    private lateinit var playbackRepository: PlaybackRepository
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
        connection = mockk(relaxed = true)
        showSettings = mockk(relaxed = true)
        // No show has an intro until a test gives it one; every start then resumes as before.
        every { showSettings.observeSettings(any()) } returns flowOf(ShowSettings.DEFAULT)
        episodePlayer = EpisodePlayer(playbackRepository, showSettings, connection)
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
    fun `resuming from a cold start waits for the service's restore and then plays`() = runTest {
        coEvery { connection.awaitRestored() } returns true
        coEvery { connection.currentState() } returns
            PlaybackState(isConnected = true, episodeId = "a", queueEpisodeIds = listOf("a", "b"))

        assertTrue(episodePlayer.resume())

        // In this order: a play sent before the restore has landed reaches an empty player.
        coVerifyOrder {
            connection.awaitRestored()
            connection.currentState()
            connection.play()
        }
        // The service loaded the queue; loading it again from here is the race this replaced.
        coVerify(exactly = 0) { connection.setQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `resuming with nothing to resume plays nothing and says so`() = runTest {
        coEvery { connection.awaitRestored() } returns true
        coEvery { connection.currentState() } returns PlaybackState(isConnected = true)

        // The caller's cue to open the app rather than an empty player over it.
        assertFalse(episodePlayer.resume())

        coVerify(exactly = 0) { connection.play() }
    }

    @Test
    fun `resuming with the service unreachable plays nothing and says so`() = runTest {
        coEvery { connection.awaitRestored() } returns false
        coEvery { connection.currentState() } returns PlaybackState()

        assertFalse(episodePlayer.resume())

        coVerify(exactly = 0) { connection.play() }
    }

    @Test
    fun `removing an episode clears it from the player and from storage`() = runTest {
        episodePlayer.removeFromQueue("a")

        coVerify { connection.removeFromQueue("a") }
        coVerify { playbackRepository.dequeue("a") }
    }

    /**
     * The queue screen lists the stored queue, and can offer to clear it while the player holds
     * nothing. Stopping an empty player changes no timeline, so the service's listener writes
     * nothing — the stored queue has to be emptied here or it outlives the clear.
     */
    @Test
    fun `dismissing stops the player and empties the stored queue`() = runTest {
        episodePlayer.dismiss()

        coVerifyOrder {
            connection.stop()
            playbackRepository.reorderQueue(emptyList())
        }
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
