package md.borisveriga.megapodcastplayer.wearsync

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand
import org.junit.Before
import org.junit.Test

/** Tests that each watch button reaches the phone's player as the right call. */
class WearCommandExecutorTest {

    private val connection = mockk<PlaybackConnection>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val momentsRepository = mockk<MomentsRepository>(relaxed = true)
    private val episodePlayer = mockk<EpisodePlayer>(relaxed = true)
    private val publisher = mockk<NowPlayingPublisher>(relaxed = true)

    private lateinit var executor: WearCommandExecutor

    @Before
    fun setUp() {
        every { playbackRepository.observePlaybackSettings() } returns flowOf(
            PlaybackSettings(speed = 1f, skipForwardMs = 45_000L, skipBackMs = 15_000L),
        )
        executor = WearCommandExecutor(
            connection,
            playbackRepository,
            momentsRepository,
            episodePlayer,
            publisher,
        )
    }

    @Test
    fun `toggle reaches the player`() = runTest {
        executor.execute(WearCommand.TogglePlayPause)

        coVerify(exactly = 1) { connection.togglePlayPause() }
    }

    @Test
    fun `skipping uses the phone's configured intervals rather than a watch default`() = runTest {
        executor.execute(WearCommand.SkipForward)
        executor.execute(WearCommand.SkipBack)

        coVerify(exactly = 1) { connection.skipForward(45_000L) }
        coVerify(exactly = 1) { connection.skipBack(15_000L) }
    }

    @Test
    fun `next and previous reach the player`() = runTest {
        executor.execute(WearCommand.SkipToNext)
        executor.execute(WearCommand.SkipToPrevious)

        coVerify(exactly = 1) { connection.skipToNext() }
        coVerify(exactly = 1) { connection.skipToPrevious() }
    }

    /**
     * The level arrives as an absolute index on the phone's own scale, and reaches the *device's*
     * volume rather than the player gain the sleep timer owns.
     */
    @Test
    fun `volume from the watch reaches the device volume`() = runTest {
        executor.execute(WearCommand.SetVolume(level = 9))

        coVerify(exactly = 1) { connection.setDeviceVolume(9) }
        coVerify(exactly = 0) { connection.setVolume(any()) }
    }

    /**
     * The one command not followed by a publish. The new level comes back to the player as a
     * broadcast, so a reading taken at once is the old one — and published after the state
     * flow's own, it would be the last thing the watch heard.
     */
    @Test
    fun `a volume command leaves the publishing to the state flow`() = runTest {
        executor.execute(WearCommand.SetVolume(level = 9))

        coVerify(exactly = 0) { publisher.publishCurrent() }
    }

    @Test
    fun `any other command publishes its outcome at once`() = runTest {
        executor.execute(WearCommand.TogglePlayPause)

        coVerify(exactly = 1) { publisher.publishCurrent() }
    }

    @Test
    fun `cycling the speed both stores and applies the new rate`() = runTest {
        executor.execute(WearCommand.CycleSpeed)

        // 1f is a step, so the next one up is 1.2f.
        coVerifyOrder {
            playbackRepository.setSpeed(1.2f)
            connection.setSpeed(1.2f)
        }
    }

    @Test
    fun `seeking passes the position through`() = runTest {
        executor.execute(WearCommand.SeekTo(positionMs = 90_000L))

        coVerify(exactly = 1) { connection.seekTo(90_000L) }
    }

    @Test
    fun `playing a queued episode goes through the id resolver`() = runTest {
        executor.execute(WearCommand.PlayEpisode(episodeId = "ep-7"))

        coVerify(exactly = 1) { episodePlayer.play("ep-7") }
    }

    /**
     * The other half of what a wrist can do with an episode, and the half that leaves the ears
     * alone: queueing must not touch the player at all.
     */
    @Test
    fun `queueing a downloaded episode enqueues it without disturbing playback`() = runTest {
        executor.execute(WearCommand.QueueEpisode(episodeId = "ep-7"))

        coVerify(exactly = 1) { playbackRepository.enqueue("ep-7") }
        coVerify(exactly = 0) { episodePlayer.play(any()) }
        coVerify(exactly = 0) { connection.togglePlayPause() }
    }

    @Test
    fun `a state request touches the player only to publish`() = runTest {
        executor.execute(WearCommand.RequestState)

        coVerify(exactly = 1) { publisher.publishCurrent() }
        coVerify(exactly = 0) { connection.togglePlayPause() }
        coVerify(exactly = 0) { connection.skipToNext() }
    }

    @Test
    fun `every command is confirmed to the watch by a publish`() = runTest {
        executor.execute(WearCommand.TogglePlayPause)

        coVerifyOrder {
            connection.togglePlayPause()
            publisher.publishCurrent()
        }
    }

    @Test
    fun `a mark is written wherever the phone's own playhead is`() = runTest {
        coEvery { connection.currentState() } returns
            PlaybackState(episodeId = "episode-2", positionMs = 61_000L)

        executor.execute(WearCommand.MarkMoment)

        coVerify(exactly = 1) { momentsRepository.mark("episode-2", 61_000L) }
    }

    @Test
    fun `a mark with nothing playing is dropped rather than guessed at`() = runTest {
        coEvery { connection.currentState() } returns PlaybackState()

        executor.execute(WearCommand.MarkMoment)

        coVerify(exactly = 0) { momentsRepository.mark(any(), any(), any()) }
    }
}
