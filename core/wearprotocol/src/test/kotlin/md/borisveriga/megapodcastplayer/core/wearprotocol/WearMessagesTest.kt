package md.borisveriga.megapodcastplayer.core.wearprotocol

import org.junit.Assert.assertEquals
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
            WearCommand.MarkMoment,
        )

        commands.forEach { command ->
            assertEquals(command, WearMessages.decodeCommand(WearMessages.encodeCommand(command)))
        }
    }

    @Test
    fun `commands with arguments survive a round trip`() {
        val seek = WearCommand.SeekTo(positionMs = 42_000L)
        val play = WearCommand.PlayEpisode(episodeId = "podcast-1:guid-9")
        val volume = WearCommand.SetVolume(level = 9)

        assertEquals(seek, WearMessages.decodeCommand(WearMessages.encodeCommand(seek)))
        assertEquals(play, WearMessages.decodeCommand(WearMessages.encodeCommand(play)))
        assertEquals(volume, WearMessages.decodeCommand(WearMessages.encodeCommand(volume)))
    }

    /**
     * Zero is a level like any other, and the one a relative protocol would be most likely to lose
     * track of. It has to survive the encoding as itself rather than as an absent field.
     */
    @Test
    fun `silence is a volume like any other`() {
        val silent = WearCommand.SetVolume(level = 0)

        assertEquals(silent, WearMessages.decodeCommand(WearMessages.encodeCommand(silent)))
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
            volume = 9,
            maxVolume = 15,
            upNext = listOf(WatchEpisode(id = "ep-2", title = "Two", showTitle = "The Show")),
            publishedAtMs = 12345L,
        )

        assertEquals(snapshot, WearMessages.decodeSnapshot(WearMessages.encodeSnapshot(snapshot)))
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(WearMessages.decodeCommand("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeSnapshot("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeCommand(ByteArray(0)))
        assertNull(WearMessages.decodeSnapshot(ByteArray(0)))
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
