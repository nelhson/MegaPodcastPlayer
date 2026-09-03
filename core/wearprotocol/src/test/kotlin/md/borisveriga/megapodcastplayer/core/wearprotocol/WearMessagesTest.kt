package md.borisveriga.megapodcastplayer.core.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the byte encoding both apps share, including its tolerance of bad input. */
class WearMessagesTest {

    @Test
    fun `commands without arguments survive a round trip`() {
        val commands = listOf(
            WearCommand.TogglePlayPause,
            WearCommand.SkipForward,
            WearCommand.SkipBack,
            WearCommand.SkipToNext,
            WearCommand.SkipToPrevious,
            WearCommand.CycleSpeed,
            WearCommand.RequestState,
        )

        commands.forEach { command ->
            assertEquals(command, WearMessages.decodeCommand(WearMessages.encodeCommand(command)))
        }
    }

    @Test
    fun `commands with arguments survive a round trip`() {
        val seek = WearCommand.SeekTo(positionMs = 42_000L)
        val play = WearCommand.PlayEpisode(episodeId = "podcast-1:guid-9")

        assertEquals(seek, WearMessages.decodeCommand(WearMessages.encodeCommand(seek)))
        assertEquals(play, WearMessages.decodeCommand(WearMessages.encodeCommand(play)))
    }

    @Test
    fun `a snapshot survives a round trip with its queue`() {
        val snapshot = NowPlayingSnapshot(
            episodeId = "ep-1",
            title = "Episode one",
            showTitle = "The Show",
            isPlaying = true,
            positionMs = 1_000L,
            durationMs = 2_000L,
            speed = 1.5f,
            hasNext = true,
            upNext = listOf(QueuedEpisode(id = "ep-2", title = "Two", showTitle = "The Show")),
            publishedAtMs = 12345L,
        )

        assertEquals(snapshot, WearMessages.decodeSnapshot(WearMessages.encodeSnapshot(snapshot)))
    }

    @Test
    fun `the offline library survives a round trip`() {
        val library = OfflineLibrary(
            episodes = listOf(
                OfflineEpisode(
                    id = "ep-1",
                    title = "The one about batteries",
                    showTitle = "Radio Hardware",
                    durationMs = 3_600_000L,
                    sizeBytes = 28_000_000L,
                ),
                OfflineEpisode(id = "ep-2", title = "The one about antennas"),
            ),
        )

        assertEquals(library, WearMessages.decodeLibrary(WearMessages.encodeLibrary(library)))
    }

    @Test
    fun `the commands that carry an episode between the devices survive a round trip`() {
        val copy = WearCommand.CopyToWatch(episodeId = "ep-1")
        val cancel = WearCommand.CancelCopyToWatch(episodeId = "ep-1")
        val report = WearCommand.ReportPosition(
            episodeId = "ep-1",
            positionMs = 900_000L,
            isPlayed = true,
        )

        assertEquals(copy, WearMessages.decodeCommand(WearMessages.encodeCommand(copy)))
        assertEquals(cancel, WearMessages.decodeCommand(WearMessages.encodeCommand(cancel)))
        assertEquals(report, WearMessages.decodeCommand(WearMessages.encodeCommand(report)))
    }

    /**
     * Starting a copy and stopping one carry the same argument, so a mixed-up [SerialName] would
     * still decode — into the opposite instruction. This is the assertion that catches that.
     */
    @Test
    fun `starting a copy and cancelling one are told apart on the wire`() {
        val start = WearMessages.encodeCommand(WearCommand.CopyToWatch(episodeId = "ep-1"))
        val stop = WearMessages.encodeCommand(WearCommand.CancelCopyToWatch(episodeId = "ep-1"))

        assertNotEquals(start.decodeToString(), stop.decodeToString())
    }

    /**
     * The id travels in the channel path, so the two halves of that spelling have to agree — this is
     * the only place they can be checked together.
     */
    @Test
    fun `an episode id survives the channel path it travels in`() {
        val id = "9f2c1a7b3d4e5f60718293a4b5c6d7e8f9012345"

        assertEquals(id, WearPaths.episodeIdFromAudioPath(WearPaths.episodeAudioPath(id)))
    }

    @Test
    fun `a path that is not an audio channel yields no episode`() {
        assertNull(WearPaths.episodeIdFromAudioPath(WearPaths.COMMAND))
        assertNull(WearPaths.episodeIdFromAudioPath(WearPaths.EPISODE_AUDIO))
        assertNull(WearPaths.episodeIdFromAudioPath("/somebody/else/channel"))
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(WearMessages.decodeCommand("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeSnapshot("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeLibrary("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeCommand(ByteArray(0)))
        assertNull(WearMessages.decodeSnapshot(ByteArray(0)))
        assertNull(WearMessages.decodeLibrary(ByteArray(0)))
    }

    @Test
    fun `a command variant that is not one of ours decodes to null`() {
        val corrupt = """{"type":"start_a_fire"}""".encodeToByteArray()

        assertNull(WearMessages.decodeCommand(corrupt))
    }

    /**
     * Both sides ship from one build, so a field these classes do not declare can only be damage.
     * Decoding is strict about it rather than quietly dropping it.
     */
    @Test
    fun `a field the snapshot does not declare decodes to null`() {
        val corrupt =
            """{"episodeId":"ep-1","title":"One","chapterCount":7}""".encodeToByteArray()

        assertNull(WearMessages.decodeSnapshot(corrupt))
    }
}
