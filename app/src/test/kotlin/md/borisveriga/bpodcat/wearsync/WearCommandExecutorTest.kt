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
    private val libraryPublisher = mockk<OfflineLibraryPublisher>(relaxed = true)
    private val audioSender = mockk<EpisodeAudioSender>(relaxed = true)

    private lateinit var executor: WearCommandExecutor

    @Before
    fun setUp() {
        every { playbackRepository.observePlaybackSettings() } returns flowOf(
            PlaybackSettings(speed = 1f, skipForwardMs = 45_000L, skipBackMs = 15_000L),
        )
        executor = WearCommandExecutor(
            connection,
            playbackRepository,
            episodePlayer,
            publisher,
            libraryPublisher,
            audioSender,
        )
    }

    @Test
    fun `toggle reaches the player`() = runTest {
        executor.execute(WearCommand.TogglePlayPause, WATCH_NODE)

        coVerify(exactly = 1) { connection.togglePlayPause() }
    }

    @Test
    fun `skipping uses the phone's configured intervals rather than a watch default`() = runTest {
        executor.execute(WearCommand.SkipForward, WATCH_NODE)
        executor.execute(WearCommand.SkipBack, WATCH_NODE)

        coVerify(exactly = 1) { connection.skipForward(45_000L) }
        coVerify(exactly = 1) { connection.skipBack(15_000L) }
    }

    @Test
    fun `next and previous reach the player`() = runTest {
        executor.execute(WearCommand.SkipToNext, WATCH_NODE)
        executor.execute(WearCommand.SkipToPrevious, WATCH_NODE)

        coVerify(exactly = 1) { connection.skipToNext() }
        coVerify(exactly = 1) { connection.skipToPrevious() }
    }

    @Test
    fun `cycling the speed both stores and applies the new rate`() = runTest {
        executor.execute(WearCommand.CycleSpeed, WATCH_NODE)

        // 1f is a step, so the next one up is 1.2f.
        coVerifyOrder {
            playbackRepository.setSpeed(1.2f)
            connection.setSpeed(1.2f)
        }
    }

    @Test
    fun `seeking passes the position through`() = runTest {
        executor.execute(WearCommand.SeekTo(positionMs = 90_000L), WATCH_NODE)

        coVerify(exactly = 1) { connection.seekTo(90_000L) }
    }

    @Test
    fun `playing a queued episode goes through the id resolver`() = runTest {
        executor.execute(WearCommand.PlayEpisode(episodeId = "ep-7"), WATCH_NODE)

        coVerify(exactly = 1) { episodePlayer.play("ep-7") }
    }

    @Test
    fun `a state request touches the player only to publish`() = runTest {
        executor.execute(WearCommand.RequestState, WATCH_NODE)

        coVerify(exactly = 1) { publisher.publishCurrent() }
        coVerify(exactly = 0) { connection.togglePlayPause() }
        coVerify(exactly = 0) { connection.skipToNext() }
    }

    @Test
    fun `every command is confirmed to the watch by a publish`() = runTest {
        executor.execute(WearCommand.TogglePlayPause, WATCH_NODE)

        coVerifyOrder {
            connection.togglePlayPause()
            publisher.publishCurrent()
        }
    }

    /**
     * An opening watch asks for state once and needs two answers: what is playing, and what it could
     * take with it. Publishing only the first is what left the "copy to watch" list a day stale.
     */
    @Test
    fun `a state request also republishes what the phone holds offline`() = runTest {
        executor.execute(WearCommand.RequestState, WATCH_NODE)

        coVerify(exactly = 1) { libraryPublisher.publishCurrent() }
    }

    @Test
    fun `a copy request is addressed to the watch that asked`() = runTest {
        executor.execute(WearCommand.CopyToWatch(episodeId = "ep-7"), WATCH_NODE)

        coVerify(exactly = 1) { audioSender.send(WATCH_NODE, "ep-7") }
    }

    @Test
    fun `a position played on the watch is written to the phone`() = runTest {
        executor.execute(
            WearCommand.ReportPosition(episodeId = "ep-7", positionMs = 900_000L),
            WATCH_NODE,
        )

        coVerify(exactly = 1) {
            playbackRepository.setPlayed(episodeId = "ep-7", isPlayed = false, positionMs = 900_000L)
        }
    }

    /** Finishing an episode on the watch has to leave it exactly as finishing it on the phone does. */
    @Test
    fun `an episode finished on the watch goes back to the start`() = runTest {
        executor.execute(
            WearCommand.ReportPosition(
                episodeId = "ep-7",
                positionMs = 3_599_000L,
                isPlayed = true,
            ),
            WATCH_NODE,
        )

        coVerify(exactly = 1) {
            playbackRepository.setPlayed(episodeId = "ep-7", isPlayed = true, positionMs = 0L)
        }
    }

    private companion object {
        /** The node a command arrived from; only the commands that answer one care which. */
        const val WATCH_NODE = "watch-node-1"
    }
}
