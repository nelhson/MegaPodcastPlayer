package md.borisveriga.megapodcastplayer.core.designsystem.reorder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReorderableState].
 *
 * This began as the play queue's own test and grew two ways when the state was generalised. The
 * bookkeeping half is unchanged and is the part that would go wrong silently: that a drag is
 * reported once rather than per frame, that a drag ending where it began reports nothing, and that
 * an upstream emission is refused while a finger is down. That last one is the failure a user
 * actually feels — the list snapping back mid-drag because the database echoed the old order.
 *
 * The half that is new is the hit test. It used to need a laid-out list and was left to the
 * emulator; now that the layout is an interface, a fake can place items exactly where a test wants
 * them, which is the only practical way to pin the grid's two-dimensional case.
 */
class ReorderableStateTest {

    private val moves = mutableListOf<Pair<Int, Int>>()

    /** A layout whose items are wherever the test says they are. */
    private class FakeLayout(
        override val isLinear: Boolean,
        var items: List<ReorderableItem> = emptyList(),
    ) : ReorderableLayout {
        override fun visibleItems(): List<ReorderableItem> = items
    }

    /** Rows 100px tall, stacked from the top, as a `LazyColumn` would lay them out. */
    private fun rows(vararg keys: String) = keys.mapIndexed { index, key ->
        ReorderableItem(
            key = key,
            offset = IntOffset(0, index * ROW_HEIGHT),
            size = IntSize(WIDTH, ROW_HEIGHT),
        )
    }

    /** Tiles in a two-column grid, as a `LazyVerticalGrid` would lay them out. */
    private fun tiles(vararg keys: String) = keys.mapIndexed { index, key ->
        ReorderableItem(
            key = key,
            offset = IntOffset((index % COLUMNS) * TILE, (index / COLUMNS) * TILE),
            size = IntSize(TILE, TILE),
        )
    }

    private fun state(layout: ReorderableLayout) =
        ReorderableState<String>(
            layout = layout,
            keyOf = { it },
            onMove = { from, to -> moves += from to to },
        )

    @Before
    fun setUp() {
        moves.clear()
    }

    @Test
    fun `the drawn order starts as the upstream one`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), state.order)
    }

    @Test
    fun `a drag in progress keeps upstream emissions off the screen`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.adopt(listOf("c", "b", "a"))

        // Adopting mid-drag would yank the collection out from under the finger.
        assertEquals(listOf("a", "b", "c"), state.order)
    }

    @Test
    fun `the upstream order is picked up again once the finger lifts`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.onDragCancel()
        state.adopt(listOf("c", "b", "a"))

        assertEquals(listOf("c", "b", "a"), state.order)
    }

    @Test
    fun `a change refused mid-drag is applied the moment the finger lifts`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b"))

        state.onDragStart("b")
        // The collection shrinks under the finger — in the queue, the episode playing finished and
        // took its row with it.
        state.adopt(listOf("b"))
        state.onDragEnd()

        // Without this the refused list would be the last one the state ever heard about: the call
        // site only adopts when *its* list changes, so "a" would stay on screen indefinitely.
        assertEquals(listOf("b"), state.order)
    }

    @Test
    fun `a change refused mid-drag is applied when the drag is cancelled too`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b"))

        state.onDragStart("b")
        state.adopt(listOf("b"))
        state.onDragCancel()

        assertEquals(listOf("b"), state.order)
    }

    @Test
    fun `a collection emptied under the finger leaves nothing drawn`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a"))

        state.onDragStart("a")
        state.adopt(emptyList())
        state.onDragEnd()

        // The bug this pins: a single row left stranded in a queue the user has emptied.
        assertEquals(emptyList<String>(), state.order)
    }

    @Test
    fun `a drag whose collection changed underneath reports nothing`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.onDrag(Offset(0f, ROW_HEIGHT.toFloat()))
        state.adopt(listOf("b", "c"))
        state.onDragEnd()

        // The two positions named items in a collection that no longer exists. Applying them to
        // whatever has taken their place would reorder episodes the user never touched.
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
        assertEquals(listOf("b", "c"), state.order)
    }

    @Test
    fun `a row dragged over its neighbour trades places with it`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        // "a" is centred at 50; one row down puts its centre at 150, inside "b".
        state.onDrag(Offset(0f, ROW_HEIGHT.toFloat()))

        assertEquals(listOf("b", "a", "c"), state.order)
        // The swap re-lays the row where "b" was, so the drawn offset gives that distance back
        // rather than leaving the row a whole row height ahead of the finger.
        assertEquals(0f, state.offset.y, TOLERANCE)
    }

    @Test
    fun `a list ignores sideways movement`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.onDrag(Offset(400f, 0f))

        // A row that slid sideways under the finger would look broken, and letting the offset
        // drift off the row's own width would make the hit test miss entirely.
        assertEquals(0f, state.offset.x, TOLERANCE)
        assertEquals(listOf("a", "b", "c"), state.order)
    }

    @Test
    fun `a tile dragged sideways in a grid trades places with its neighbour`() {
        val layout = FakeLayout(isLinear = false, items = tiles("a", "b", "c", "d"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c", "d"))

        state.onDragStart("a")
        // One column right: "a" is centred at (75, 75), which lands inside "b" at (150..300, 0..150).
        state.onDrag(Offset(TILE.toFloat(), 0f))

        assertEquals(listOf("b", "a", "c", "d"), state.order)
    }

    @Test
    fun `a tile dragged down a row in a grid trades places with the tile below`() {
        val layout = FakeLayout(isLinear = false, items = tiles("a", "b", "c", "d"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c", "d"))

        state.onDragStart("a")
        // Straight down in a two-column grid is two positions along, not one: "a" lands where "c"
        // was and everything between them shuffles up.
        state.onDrag(Offset(0f, TILE.toFloat()))

        assertEquals(listOf("b", "c", "a", "d"), state.order)
    }

    @Test
    fun `a drag that stays inside its own bounds changes nothing`() {
        val layout = FakeLayout(isLinear = false, items = tiles("a", "b", "c", "d"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c", "d"))

        state.onDragStart("a")
        state.onDrag(Offset(10f, 10f))

        assertEquals(listOf("a", "b", "c", "d"), state.order)
    }

    @Test
    fun `items the collection does not own are never swapped with`() {
        // Headers and filter chips share the layout with the reorderable items; a drag over one
        // must not try to trade places with it.
        val layout = FakeLayout(isLinear = true, items = rows("header", "a", "b"))
        val state = state(layout)
        state.adopt(listOf("a", "b"))

        state.onDragStart("a")
        state.onDrag(Offset(0f, -ROW_HEIGHT.toFloat()))

        assertEquals(listOf("a", "b"), state.order)
    }

    @Test
    fun `following the finger does not report anything on its own`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.onDrag(Offset(0f, 20f))
        state.onDrag(Offset(0f, 20f))
        state.onDrag(Offset(0f, 20f))

        // One gesture is one edit. Reporting per frame would have the database renegotiating the
        // whole collection dozens of times for a single drag.
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `a completed drag reports its start and end once`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("a")
        state.onDrag(Offset(0f, ROW_HEIGHT.toFloat()))
        state.onDragEnd()

        assertEquals(listOf(0 to 1), moves)
    }

    @Test
    fun `a drag that ends where it began reports nothing`() {
        val layout = FakeLayout(isLinear = true, items = rows("a", "b", "c"))
        val state = state(layout)
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("b")
        state.onDrag(Offset(0f, 5f))
        state.onDragEnd()

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `a cancelled drag reports nothing`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("b")
        state.onDragCancel()

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `grabbing an item that is not in the collection starts nothing`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.onDragStart("gone")

        assertNull(state.draggingKey)
    }

    @Test
    fun `an accessibility move reorders the collection and reports it once`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.move(from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), state.order)
        assertEquals(listOf(0 to 2), moves)
    }

    @Test
    fun `an accessibility move off the end of the collection does nothing`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.move(from = 0, to = 3)

        assertEquals(listOf("a", "b", "c"), state.order)
        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    @Test
    fun `an accessibility move onto the same position does nothing`() {
        val state = state(FakeLayout(isLinear = true))
        state.adopt(listOf("a", "b", "c"))

        state.move(from = 1, to = 1)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    private companion object {
        const val ROW_HEIGHT = 100
        const val WIDTH = 1_000
        const val TILE = 150
        const val COLUMNS = 2
        const val TOLERANCE = 0.001f
    }
}
