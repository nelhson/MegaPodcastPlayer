package md.borisveriga.megapodcastplayer.core.designsystem.reorder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [reorderableLongPressDrag], the gesture rather than the bookkeeping.
 *
 * `ReorderableStateTest` covers what a drag *means* — which items trade places, what is reported
 * and when — against a fake layout, and none of it needs a finger. This covers the three things
 * only a real pointer can settle, all of which are about the press being shared with something
 * else on the same item.
 *
 * A tap must still tap: every list here puts this modifier on a row that opens something, and a
 * detector that claimed the press outright would leave those lists unable to open anything.
 *
 * A press that is held and then released must *not* tap. That is the part this file was written
 * for. The item's own `clickable` sits inside this modifier, and pointer events reach the main
 * pass from the inside out, so until the gesture began consuming on the initial pass every list in
 * the app quietly opened whatever the user had picked up and put back down.
 *
 * And a press held without travelling is a second gesture, which is what the library's grid needed
 * (LIB-4/D-8) and what the distinction between the two halves of `onReleasedInPlace` rests on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReorderableGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val moves = mutableListOf<Pair<Int, Int>>()
    private val clicks = mutableListOf<String>()
    private var releasesInPlace = 0

    /**
     * Three rows, each tappable and each carrying the gesture — the shape every call site has.
     *
     * @param enabled passed through to the modifier under test.
     * @param withMenu whether a release in place is answered, as the library's grid answers it.
     */
    @Composable
    private fun Rows(enabled: Boolean = true, withMenu: Boolean = true) {
        val items = listOf("a", "b", "c")
        val listState = rememberLazyListState()
        val drag = rememberReorderableState(
            layout = rememberReorderableLayout(listState),
            items = items,
            keyOf = { it },
            onMove = { from, to -> moves += from to to },
        )

        LazyColumn(state = listState) {
            // Keyed by the item, because the hit test finds the dragged item by the key the lazy
            // layout reports — an index-keyed list simply never matches.
            items(items = drag.order, key = { it }) { key ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .semantics { contentDescription = key }
                        // Outside the click, exactly as a call site passing a modifier into a row
                        // or a tile puts it: the row applies its own `clickable` afterwards, which
                        // makes the click the *inner* of the two.
                        .reorderableLongPressDrag(
                            state = drag,
                            key = key,
                            enabled = enabled,
                            onReleasedInPlace = if (withMenu) {
                                { releasesInPlace++ }
                            } else {
                                null
                            },
                        )
                        .clickable { clicks += key },
                )
            }
        }
    }

    @Test
    fun `a tap still taps`() {
        composeRule.setContent { Rows() }

        composeRule.onNodeWithContentDescription("b").performClick()

        assertEquals(listOf("b"), clicks)
        assertEquals(0, releasesInPlace)
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `a press held and released in place is not a tap`() {
        composeRule.setContent { Rows() }

        composeRule.onNodeWithContentDescription("b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        assertEquals(1, releasesInPlace)
        assertEquals(emptyList<String>(), clicks)
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    /**
     * The other half of the same gesture. A press that travelled has rearranged the collection,
     * and answering it with a menu as well would make every drag end in one.
     */
    @Test
    fun `a press that travels reorders and reports nothing in place`() {
        composeRule.setContent { Rows() }

        composeRule.onNodeWithContentDescription("b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }

        assertEquals(listOf(1 to 0), moves)
        assertEquals(0, releasesInPlace)
        assertEquals(emptyList<String>(), clicks)
    }

    /**
     * A call site with nothing to say about a release in place still gets the press swallowed.
     * That is deliberate rather than incidental: once an item has been picked up, putting it back
     * where it came from is a cancelled rearrangement, not a tap on it.
     */
    @Test
    fun `a held press is swallowed even where nothing answers it`() {
        composeRule.setContent { Rows(withMenu = false) }

        composeRule.onNodeWithContentDescription("b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        assertEquals(emptyList<String>(), clicks)
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    /**
     * The library's grid loses its drag whenever the shows on screen are not in the order the
     * library stores, and it keeps its menu through all of it — a show can be removed whichever
     * way the grid happens to be sorted.
     */
    @Test
    fun `a disabled drag still reports a release in place`() {
        composeRule.setContent { Rows(enabled = false) }

        composeRule.onNodeWithContentDescription("b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }
        assertEquals(emptyList<Pair<Int, Int>>(), moves)

        composeRule.onNodeWithContentDescription("b").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        assertEquals(1, releasesInPlace)
    }
}

/** Tall enough that a one-row drag is unambiguous, short enough that three rows fit. */
private val ROW_HEIGHT = 100.dp

/** Comfortably past the 500ms system long-press timeout the gesture waits out. */
private const val LONG_PRESS_MS = 1_000L
