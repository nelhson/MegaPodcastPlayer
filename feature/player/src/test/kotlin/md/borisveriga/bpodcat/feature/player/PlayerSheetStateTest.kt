package md.borisveriga.bpodcat.feature.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PlayerSheetState].
 *
 * The sheet's whole design rests on one number being meaningful at every value, not just at its
 * ends, so what is pinned here is that the number behaves: a drag maps a finger distance onto a
 * fraction and cannot leave `0f..1f`, and a release lands wherever the gesture actually asked for.
 * The settle rule is the one a user would notice being wrong — a quick flick up from the bar has to
 * open the player even though the finger barely moved, which position alone would refuse.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerSheetStateTest {

    /** A tall phone: the sheet travels 2000px between its two rest positions. */
    private val travelPx = 2_000f

    /** Above this speed the direction of the flick decides rather than the position. */
    private val flingPx = 600f

    private fun state(initial: PlayerSheetValue = PlayerSheetValue.Collapsed) =
        PlayerSheetState(initial)

    @Test
    fun `a new sheet rests where it was told to`() {
        assertEquals(0f, state().progress, TOLERANCE)
        assertEquals(1f, state(PlayerSheetValue.Expanded).progress, TOLERANCE)
        assertTrue(state(PlayerSheetValue.Expanded).isExpanded)
        assertFalse(state().isExpanded)
    }

    @Test
    fun `dragging upward opens the sheet by the fraction of its travel`() = runTest {
        val sheet = state()

        sheet.dragBy(deltaPx = -500f, sheetTravelPx = travelPx)

        assertEquals(0.25f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `dragging downward from open closes it by the same measure`() = runTest {
        val sheet = state(PlayerSheetValue.Expanded)

        sheet.dragBy(deltaPx = 500f, sheetTravelPx = travelPx)

        assertEquals(0.75f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `a drag past either end stops there`() = runTest {
        val sheet = state()

        sheet.dragBy(deltaPx = -100_000f, sheetTravelPx = travelPx)
        assertEquals(1f, sheet.progress, TOLERANCE)

        sheet.dragBy(deltaPx = 100_000f, sheetTravelPx = travelPx)
        assertEquals(0f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `a sheet with nowhere to travel ignores the drag rather than dividing by zero`() = runTest {
        // Happens for one frame on first composition, before the sheet has been measured.
        val sheet = state()

        sheet.dragBy(deltaPx = -500f, sheetTravelPx = 0f)

        assertEquals(0f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `a slow release goes wherever the sheet is nearer to`() = runAnimating {
        val nearlyOpen = state()
        nearlyOpen.dragBy(deltaPx = -1_400f, sheetTravelPx = travelPx)
        nearlyOpen.settle(velocityPxPerSecond = 0f, flingThresholdPxPerSecond = flingPx)
        assertTrue(nearlyOpen.isExpanded)
        assertEquals(1f, nearlyOpen.progress, TOLERANCE)

        val barelyLifted = state()
        barelyLifted.dragBy(deltaPx = -200f, sheetTravelPx = travelPx)
        barelyLifted.settle(velocityPxPerSecond = 0f, flingThresholdPxPerSecond = flingPx)
        assertFalse(barelyLifted.isExpanded)
        assertEquals(0f, barelyLifted.progress, TOLERANCE)
    }

    @Test
    fun `a flick upward opens the sheet even from barely lifted`() = runAnimating {
        // The gesture people actually make: a short sharp swipe off the bar. Deciding on position
        // alone would drop it straight back down and feel like the app ignored them.
        val sheet = state()
        sheet.dragBy(deltaPx = -120f, sheetTravelPx = travelPx)

        sheet.settle(velocityPxPerSecond = -2_500f, flingThresholdPxPerSecond = flingPx)

        assertTrue(sheet.isExpanded)
        assertEquals(1f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `a flick downward closes the sheet even from nearly open`() = runAnimating {
        val sheet = state(PlayerSheetValue.Expanded)
        sheet.dragBy(deltaPx = 100f, sheetTravelPx = travelPx)

        sheet.settle(velocityPxPerSecond = 2_500f, flingThresholdPxPerSecond = flingPx)

        assertFalse(sheet.isExpanded)
        assertEquals(0f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `the back gesture can put the sheet anywhere between the two ends`() = runTest {
        val sheet = state(PlayerSheetValue.Expanded)

        sheet.seekTo(0.3f)

        assertEquals(0.3f, sheet.progress, TOLERANCE)
        // Still expanded: the gesture has not been let go of, and the navigation bar must not
        // come back only to disappear again if the user changes their mind.
        assertTrue(sheet.isExpanded)
    }

    @Test
    fun `the back gesture cannot push the sheet outside its range`() = runTest {
        val sheet = state(PlayerSheetValue.Expanded)

        sheet.seekTo(-2f)
        assertEquals(0f, sheet.progress, TOLERANCE)

        sheet.seekTo(4f)
        assertEquals(1f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `only which end the sheet rested at survives a configuration change`() {
        val expanded = state(PlayerSheetValue.Expanded)

        val saved = with(PlayerSheetState.Saver) {
            requireNotNull(TestSaverScope.save(expanded))
        }
        val restored = PlayerSheetState.Saver.restore(saved)

        assertEquals(PlayerSheetValue.Expanded, requireNotNull(restored).targetValue)
        assertEquals(1f, restored.progress, TOLERANCE)
    }

    @Test
    fun `a delta arriving after the release is ignored`() = runAnimating {
        // The bug this pins: drag deltas and the settle run on different coroutines, so the last
        // deltas of a gesture can land after the settle has committed. Applying one would snap the
        // sheet back to a fraction and cancel the settle's animation, leaving it half open.
        val sheet = state()
        sheet.dragBy(deltaPx = -1_400f, sheetTravelPx = travelPx)
        sheet.settle(velocityPxPerSecond = 0f, flingThresholdPxPerSecond = flingPx)

        sheet.dragBy(deltaPx = 900f, sheetTravelPx = travelPx)

        assertTrue(sheet.isExpanded)
        assertEquals(1f, sheet.progress, TOLERANCE)
    }

    @Test
    fun `the next gesture can drag again after a settle`() = runAnimating {
        // The other half of the guard: dropping late deltas must not deafen the sheet to the next
        // real gesture, which is exactly how someone grabs a sheet that is still animating.
        val sheet = state()
        sheet.settle(velocityPxPerSecond = -flingPx * 2, flingThresholdPxPerSecond = flingPx)
        assertEquals(1f, sheet.progress, TOLERANCE)

        sheet.onDragStarted()
        sheet.dragBy(deltaPx = 1_000f, sheetTravelPx = travelPx)

        assertEquals(0.5f, sheet.progress, TOLERANCE)
    }

    /**
     * Runs a test that lets the sheet settle.
     *
     * Settling animates, and an animation needs a frame clock, which a plain JVM test has no
     * source of. [TestMonotonicFrameClock] provides one that the test scheduler drives, so the
     * spring runs to completion in virtual time rather than in a second and a half of real time.
     */
    private fun runAnimating(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(TestMonotonicFrameClock(this)) { body() }
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}

/** The one thing a [androidx.compose.runtime.saveable.Saver] needs, with nothing rejected. */
private object TestSaverScope : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
