package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [expandedPlayerLayout].
 *
 * The expanded player has two shapes and one piece of artwork that has to land in the right place
 * in both, and every window this app meets decides which. A screenshot answers that question for
 * one window at a time and costs three images to do it; this answers it for the six that matter —
 * the Fold folded and open, a phone on its side, a short window, and each side of the breakpoint —
 * for the price of a number.
 *
 * The one number worth stating plainly: the hero is capped by *both* sides of the window, and which
 * cap bites is the whole of PL-11. Width alone is right only on a window taller than it is wide.
 */
class ExpandedPlayerLayoutTest {

    /** The Fold 7 closed: the shape this app is used in most. */
    private val folded = expandedPlayerLayout(windowWidth = 411.dp, contentHeight = 891.dp)

    /** The Fold 7 opened out: near enough square, and over the breakpoint. */
    private val unfolded = expandedPlayerLayout(windowWidth = 882.dp, contentHeight = 830.dp)

    @Test
    fun `a folded phone stacks the artwork above the controls`() {
        assertFalse(folded.sideBySide)
    }

    @Test
    fun `stacked, the artwork is most of the width and hangs from the header`() {
        // 72 % of the whole window, because stacked it has the whole window.
        assertDp(411f * HERO_ARTWORK_WIDTH_FRACTION, folded.heroSize)
        // Centred in it.
        assertDp((411f - folded.heroSize.value) / 2f, folded.heroLeft)
        // And directly under the header strip, with the titles flowing beneath it.
        assertDp(
            (expandedHeaderHeight + expandedArtworkTopGap).value,
            folded.heroTop,
        )
    }

    @Test
    fun `an unfolded phone sets the artwork beside the controls`() {
        assertTrue(unfolded.sideBySide)
    }

    @Test
    fun `side by side, the artwork is measured against its half rather than the window`() {
        // The regression PL-11 exists for: 72 % of 882 dp is a square two thirds the height of the
        // screen. 72 % of the half it is actually given is a cover.
        assertDp(441f * HERO_ARTWORK_WIDTH_FRACTION, unfolded.heroSize)
        assertDp((441f - unfolded.heroSize.value) / 2f, unfolded.heroLeft)
    }

    @Test
    fun `side by side, the artwork is centred in the half it has to itself`() {
        val paneHeight = 830f - expandedHeaderHeight.value
        assertDp(
            expandedHeaderHeight.value + (paneHeight - unfolded.heroSize.value) / 2f,
            unfolded.heroTop,
        )
    }

    @Test
    fun `a phone on its side is capped by its height, not its width`() {
        // Wide enough for two panes and far too short for a square that size: 72 % of 445 dp is
        // 320 dp of artwork in a 411 dp window, which is the whole player and none of the buttons.
        val landscape = expandedPlayerLayout(windowWidth = 891.dp, contentHeight = 411.dp)

        assertTrue(landscape.sideBySide)
        assertDp((411f - expandedHeaderHeight.value) * HERO_ARTWORK_HEIGHT_FRACTION, landscape.heroSize)
    }

    @Test
    fun `a short narrow window is capped by its height too`() {
        // A small phone, or a freeform window pulled short. Stacked, so the artwork keeps the whole
        // width — and is still not allowed to eat the column below it.
        val short = expandedPlayerLayout(windowWidth = 411.dp, contentHeight = 520.dp)

        assertFalse(short.sideBySide)
        assertDp((520f - expandedHeaderHeight.value) * HERO_ARTWORK_HEIGHT_FRACTION, short.heroSize)
    }

    @Test
    fun `the breakpoint is inclusive, and a dp under it is still one column`() {
        assertTrue(expandedPlayerLayout(840.dp, 891.dp).sideBySide)
        assertFalse(expandedPlayerLayout(839.dp, 891.dp).sideBySide)
    }

    @Test
    fun `a window shorter than its own header still asks for a size a layout can take`() {
        // Not a real window, but `Modifier.size` refuses a negative number and a split-screen drag
        // passes through every height on its way down.
        val sliver = expandedPlayerLayout(windowWidth = 411.dp, contentHeight = 40.dp)

        assertDp(0f, sliver.heroSize)
        assertTrue(sliver.heroLeft.value > 0f)
    }

    /** Dp comparison with a tolerance, since every one of these is a product of two floats. */
    private fun assertDp(expected: Float, actual: Dp) =
        assertEquals(expected, actual.value, TOLERANCE)

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
