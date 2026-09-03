package md.borisveriga.megapodcastplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the values [PlaybackState] derives for the player UI. */
class PlaybackStateTest {

    @Test
    fun `nothing loaded reads as idle`() {
        assertTrue(PlaybackState().isIdle)
        assertFalse(PlaybackState(episodeId = "e1").isIdle)
    }

    @Test
    fun `progress is zero while the duration is unknown`() {
        val state = PlaybackState(episodeId = "e1", positionMs = 60_000L, durationMs = 0L)

        assertEquals(0f, state.progress, 0.0001f)
        assertEquals(null, state.knownDurationMs)
    }

    @Test
    fun `progress is the played fraction`() {
        val state = PlaybackState(episodeId = "e1", positionMs = 30_000L, durationMs = 120_000L)

        assertEquals(0.25f, state.progress, 0.0001f)
    }

    @Test
    fun `progress is clamped when the position overshoots the duration`() {
        // ExoPlayer can report a position a few milliseconds past a duration it rounded down.
        val state = PlaybackState(episodeId = "e1", positionMs = 120_500L, durationMs = 120_000L)

        assertEquals(1f, state.progress, 0.0001f)
    }

    @Test
    fun `up next is everything after the current index`() {
        val state = PlaybackState(
            episodeId = "b",
            queueEpisodeIds = listOf("a", "b", "c", "d"),
            queueIndex = 1,
        )

        assertEquals(listOf("c", "d"), state.upNextEpisodeIds)
        assertTrue(state.hasNext)
    }

    @Test
    fun `the last entry in the queue has nothing next`() {
        val state = PlaybackState(
            episodeId = "d",
            queueEpisodeIds = listOf("a", "b", "c", "d"),
            queueIndex = 3,
        )

        assertEquals(emptyList<String>(), state.upNextEpisodeIds)
        assertFalse(state.hasNext)
    }
}
