package md.borisveriga.bpodcat.core.wearprotocol

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
            artworkUrl = "https://example.com/art.jpg",
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
    fun `garbage decodes to null rather than throwing`() {
        assertNull(WearMessages.decodeCommand("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeSnapshot("not json".encodeToByteArray()))
        assertNull(WearMessages.decodeCommand(ByteArray(0)))
        assertNull(WearMessages.decodeSnapshot(ByteArray(0)))
    }

    @Test
    fun `a command variant this build does not know decodes to null`() {
        val fromANewerApp = """{"type":"start_a_fire"}""".encodeToByteArray()

        assertNull(WearMessages.decodeCommand(fromANewerApp))
    }

    @Test
    fun `unknown snapshot fields from a newer app are ignored`() {
        val fromANewerApp =
            """{"episodeId":"ep-1","title":"One","chapterCount":7}""".encodeToByteArray()

        val decoded = WearMessages.decodeSnapshot(fromANewerApp)

        assertEquals("ep-1", decoded?.episodeId)
        assertEquals("One", decoded?.title)
    }
}
