package md.borisveriga.bpodcat.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the one decision [SwipeActionsRow] makes on its own: where a released row comes to
 * rest.
 *
 * The rest of the component is layout and gesture plumbing that a screen test exercises. This is
 * the part with a rule in it, and the rule is easy to get subtly wrong — a threshold that ignored
 * velocity would make a quick flick feel dead, and one that ignored position would leave a row the
 * user dragged nearly all the way open snapping shut.
 */
class SwipeActionsTest {

    @Test
    fun `a row dragged past halfway settles open`() {
        assertEquals(-REVEAL, settleTarget(offset = -PAST_HALF, velocity = 0f, revealWidth = REVEAL))
    }

    @Test
    fun `a row dragged less than halfway settles shut`() {
        assertEquals(0f, settleTarget(offset = -SHORT_OF_HALF, velocity = 0f, revealWidth = REVEAL))
    }

    @Test
    fun `a quick flick left opens a row it barely moved`() {
        // Velocity beats position: the gesture was a throw, and the distance it happened to cover
        // before the finger left says nothing about what was meant.
        assertEquals(
            -REVEAL,
            settleTarget(offset = -1f, velocity = -FLING, revealWidth = REVEAL),
        )
    }

    @Test
    fun `a quick flick right shuts a row that was nearly all the way open`() {
        assertEquals(
            0f,
            settleTarget(offset = -REVEAL + 1f, velocity = FLING, revealWidth = REVEAL),
        )
    }

    @Test
    fun `a row with nothing behind it stays shut`() {
        // The buttons have not been measured yet, or there are none. Either way there is no open
        // position to settle into, and -0f is not one.
        assertEquals(0f, settleTarget(offset = -50f, velocity = -FLING, revealWidth = 0f))
    }

    /** A plausible width for three revealed buttons, in pixels. */
    private companion object {
        const val REVEAL = 240f
        const val PAST_HALF = 130f
        const val SHORT_OF_HALF = 110f

        /** Comfortably above the fling threshold, in pixels per second. */
        const val FLING = 2_000f
    }
}
