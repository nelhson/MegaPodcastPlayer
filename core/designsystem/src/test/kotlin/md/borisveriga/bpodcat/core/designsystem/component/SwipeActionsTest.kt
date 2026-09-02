package md.borisveriga.bpodcat.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the one decision [SwipeActionsRow] makes on its own: what a released row does.
 *
 * The rest of the component is layout and gesture plumbing that a screen test exercises. This is
 * the part with a rule in it, and the rule is easy to get subtly wrong in three separate ways — a
 * reveal threshold that ignored velocity would make a quick flick feel dead, one that ignored
 * position would snap a row shut that had been dragged nearly all the way open, and a commit tier
 * that *listened* to velocity would fire a destructive action on a flick that never travelled.
 */
class SwipeActionsTest {

    @Test
    fun `a row dragged past halfway settles open`() {
        assertEquals(
            SwipeRelease.OPEN,
            releaseOutcome(-PAST_HALF, velocity = 0f, revealWidth = REVEAL, commitThreshold = 0f),
        )
    }

    @Test
    fun `a row dragged less than halfway settles shut`() {
        assertEquals(
            SwipeRelease.SHUT,
            releaseOutcome(
                offset = -SHORT_OF_HALF,
                velocity = 0f,
                revealWidth = REVEAL,
                commitThreshold = 0f,
            ),
        )
    }

    @Test
    fun `a quick flick left opens a row it barely moved`() {
        // Velocity beats position in the reveal tier: the gesture was a throw, and the distance it
        // happened to cover before the finger left says nothing about what was meant.
        assertEquals(
            SwipeRelease.OPEN,
            releaseOutcome(-1f, velocity = -FLING, revealWidth = REVEAL, commitThreshold = 0f),
        )
    }

    @Test
    fun `a quick flick right shuts a row that was nearly all the way open`() {
        assertEquals(
            SwipeRelease.SHUT,
            releaseOutcome(
                offset = -REVEAL + 1f,
                velocity = FLING,
                revealWidth = REVEAL,
                commitThreshold = 0f,
            ),
        )
    }

    @Test
    fun `a row with nothing behind it stays shut`() {
        // The buttons have not been measured yet, or there are none, and there is no full swipe
        // either. There is no position to settle into.
        assertEquals(
            SwipeRelease.SHUT,
            releaseOutcome(-50f, velocity = -FLING, revealWidth = 0f, commitThreshold = 0f),
        )
    }

    @Test
    fun `a row dragged past the commit threshold commits`() {
        assertEquals(
            SwipeRelease.COMMIT,
            releaseOutcome(
                offset = -COMMIT - 1f,
                velocity = 0f,
                revealWidth = REVEAL,
                commitThreshold = COMMIT,
            ),
        )
    }

    @Test
    fun `a flick that never reached the threshold does not commit`() {
        // The whole reason the commit tier ignores velocity. Both screens using it put a removal on
        // the other side, and a flick is exactly the gesture someone makes while scrolling past.
        assertEquals(
            SwipeRelease.OPEN,
            releaseOutcome(
                offset = -1f,
                velocity = -FLING,
                revealWidth = REVEAL,
                commitThreshold = COMMIT,
            ),
        )
    }

    @Test
    fun `a row pulled past the buttons but short of the threshold falls back to open`() {
        // The gap between the two tiers. It has to resolve to *something*, and the buttons are the
        // half of the gesture the user has already uncovered.
        assertEquals(
            SwipeRelease.OPEN,
            releaseOutcome(
                offset = -(REVEAL + COMMIT) / 2f,
                velocity = 0f,
                revealWidth = REVEAL,
                commitThreshold = COMMIT,
            ),
        )
    }

    @Test
    fun `a row with only a full swipe commits without any buttons to rest against`() {
        assertEquals(
            SwipeRelease.COMMIT,
            releaseOutcome(
                offset = -COMMIT,
                velocity = 0f,
                revealWidth = 0f,
                commitThreshold = COMMIT,
            ),
        )
    }

    private companion object {
        /** A plausible width for two revealed buttons, in pixels. */
        const val REVEAL = 240f

        /** Half a phone-width row, which is where the commit threshold sits. */
        const val COMMIT = 600f

        const val PAST_HALF = 130f
        const val SHORT_OF_HALF = 110f

        /** Comfortably above the fling threshold, in pixels per second. */
        const val FLING = 2_000f
    }
}
