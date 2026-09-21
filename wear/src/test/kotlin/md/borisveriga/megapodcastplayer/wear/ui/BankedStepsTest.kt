package md.borisveriga.megapodcastplayer.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** [bankVolumeSteps]: how a distance, from the bezel or from a finger, becomes volume steps. */
class BankedStepsTest {

    @Test
    fun `a distance short of a step moves nothing and is kept`() {
        val banked = bankVolumeSteps(bankedPx = 30f, pixelsPerStep = 48f)

        assertEquals(BankedSteps(steps = 0, remainderPx = 30f), banked)
    }

    /** A slow drag is a stream of small deltas; they must add up rather than each round to nothing. */
    @Test
    fun `small movements add up to a step`() {
        var bank = 0f
        var moved = 0
        repeat(5) {
            val banked = bankVolumeSteps(bankedPx = bank + 10f, pixelsPerStep = 48f)
            bank = banked.remainderPx
            moved += banked.steps
        }

        assertEquals(1, moved)
        assertEquals(2f, bank, 0.001f)
    }

    @Test
    fun `a long movement is several steps at once`() {
        val banked = bankVolumeSteps(bankedPx = 100f, pixelsPerStep = 48f)

        assertEquals(2, banked.steps)
        assertEquals(4f, banked.remainderPx, 0.001f)
    }

    @Test
    fun `backwards is quieter, and the remainder keeps its sign`() {
        val banked = bankVolumeSteps(bankedPx = -100f, pixelsPerStep = 48f)

        assertEquals(-2, banked.steps)
        assertEquals(-4f, banked.remainderPx, 0.001f)
    }

    /** Half a step quieter is no step. Flooring would make it one. */
    @Test
    fun `half a step backwards is not a step`() {
        val banked = bankVolumeSteps(bankedPx = -24f, pixelsPerStep = 48f)

        assertEquals(BankedSteps(steps = 0, remainderPx = -24f), banked)
    }

    /** An unmeasured bar has no scale, and a distance banked against none must not land later. */
    @Test
    fun `with no scale nothing moves and nothing is kept`() {
        val banked = bankVolumeSteps(bankedPx = 500f, pixelsPerStep = 0f)

        assertEquals(BankedSteps(steps = 0, remainderPx = 0f), banked)
    }
}
