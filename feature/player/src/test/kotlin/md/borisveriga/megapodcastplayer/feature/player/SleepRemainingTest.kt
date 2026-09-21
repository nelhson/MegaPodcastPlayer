package md.borisveriga.megapodcastplayer.feature.player

import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [PlayerUiState.sleepRemainingMs]: how long the sleep button says is left, for each kind of stop. */
class SleepRemainingTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 600_000L, title = "The interview"),
        Chapter(startMs = 1_500_000L, title = "Listener mail"),
    )

    /** Twelve minutes into a thirty-minute episode, inside the second chapter. */
    private fun state(sleep: SleepTimerState, speed: Float = 1f, durationMs: Long = 1_800_000L) =
        PlayerUiState(
            playback = PlaybackState(
                episodeId = "a",
                positionMs = 720_000L,
                durationMs = durationMs,
                speed = speed,
            ),
            sleep = sleep,
            chapters = chapters,
        )

    @Test
    fun `a timer that is off has nothing left`() {
        assertNull(state(SleepTimerState()).sleepRemainingMs)
    }

    @Test
    fun `a countdown says its own time`() {
        assertEquals(90_000L, state(SleepTimerState(remainingMs = 90_000L)).sleepRemainingMs)
    }

    @Test
    fun `the end of a chapter is as far away as the next one's start`() {
        val remaining = state(SleepTimerState(endOfChapterIndex = 1)).sleepRemainingMs

        assertEquals(1_500_000L - 720_000L, remaining)
    }

    /** Time on the clock, not in the episode: at double speed the chapter ends in half the time. */
    @Test
    fun `a faster speed brings the end of the chapter nearer`() {
        val remaining = state(SleepTimerState(endOfChapterIndex = 1), speed = 2f).sleepRemainingMs

        assertEquals((1_500_000L - 720_000L) / 2, remaining)
    }

    @Test
    fun `the last chapter ends with the episode`() {
        val remaining = state(SleepTimerState(endOfChapterIndex = 2)).sleepRemainingMs

        assertEquals(1_800_000L - 720_000L, remaining)
    }

    @Test
    fun `the end of the episode is what is left of it`() {
        val remaining = state(SleepTimerState(isEndOfEpisode = true)).sleepRemainingMs

        assertEquals(1_800_000L - 720_000L, remaining)
    }

    /** Better no number than a wrong one: the button falls back to saying only that it is armed. */
    @Test
    fun `an episode of unknown length cannot say when it ends`() {
        val remaining = state(SleepTimerState(isEndOfEpisode = true), durationMs = 0L).sleepRemainingMs

        assertNull(remaining)
    }
}
