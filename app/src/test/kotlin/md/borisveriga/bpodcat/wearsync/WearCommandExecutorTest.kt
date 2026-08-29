package md.borisveriga.bpodcat.wearsync

import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.model.PlaybackSettings
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import org.junit.Before
import org.junit.Test

/** Tests that each watch button reaches the phone's player as the right call. */
class WearCommandExecutorTest {

    private val connection = mockk<PlaybackConnection>(relaxed = true)
    private val playbackRepository = mockk<PlaybackRepository>(relaxed = true)
    private val episodePlayer = mockk<EpisodePlayer>(relaxed = true)
    private val publisher = mockk<NowPlayingPublisher>(relaxed = true)

    private lateinit var executor: WearCommandExecutor

    @Before
    fun setUp() {
        every { playbackRepository.observePlaybackSettings() } returns flowOf(
            PlaybackSettings(speed = 1f, skipForwardMs = 45_000L, skipBackMs = 15_000L),
        )
        executor = WearCommandExecutor(connection, playbackRepository, episodePlayer, publisher)
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
}
