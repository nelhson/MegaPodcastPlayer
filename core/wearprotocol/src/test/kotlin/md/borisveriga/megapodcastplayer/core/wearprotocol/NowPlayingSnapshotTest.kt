package md.borisveriga.megapodcastplayer.core.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the watch-side clock extrapolation in [NowPlayingSnapshot]. */
class NowPlayingSnapshotTest {

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "Episode one",
        isPlaying = true,
        positionMs = 10_000L,
        durationMs = 60_000L,
        speed = 1f,
    )

    @Test
    fun `a paused snapshot does not advance`() {
        val paused = playing.copy(isPlaying = false)

        assertEquals(10_000L, paused.positionAfter(5_000L))
    }

    @Test
    fun `a playing snapshot advances with the wall clock`() {
        assertEquals(15_000L, playing.positionAfter(5_000L))
    }

    @Test
    fun `advancing is scaled by the playback speed`() {
        val fast = playing.copy(speed = 2f)

        assertEquals(20_000L, fast.positionAfter(5_000L))
    }

    @Test
    fun `advancing never runs past the duration`() {
        assertEquals(60_000L, playing.positionAfter(10 * 60_000L))
    }

    @Test
    fun `an unknown duration lets the position advance unclamped`() {
        val unknownDuration = playing.copy(durationMs = 0L)

        assertEquals(15_000L, unknownDuration.positionAfter(5_000L))
    }

    @Test
    fun `a negative elapsed time does not rewind`() {
        assertEquals(10_000L, playing.positionAfter(-5_000L))
    }

    @Test
    fun `progress is zero while the duration is unknown`() {
        val unknownDuration = playing.copy(durationMs = 0L)

        assertEquals(0f, unknownDuration.progressAfter(5_000L), 0f)
    }

    @Test
    fun `progress tracks the extrapolated position`() {
        assertEquals(0.5f, playing.progressAfter(20_000L), 0.001f)
    }

    @Test
    fun `a snapshot with no episode is idle`() {
        assertTrue(NowPlayingSnapshot().isIdle)
        assertFalse(playing.isIdle)
    }

    @Test
    fun `snapshots differing only in position and publish time compare equal without timing`() {
        val later = playing.copy(positionMs = 12_345L, publishedAtMs = 999L)

        assertEquals(playing.withoutTiming(), later.withoutTiming())
    }

    @Test
    fun `a substantive change survives blanking the timing fields`() {
        val paused = playing.copy(isPlaying = false, positionMs = 12_345L)

        assertNotEquals(playing.withoutTiming(), paused.withoutTiming())
    }
}
