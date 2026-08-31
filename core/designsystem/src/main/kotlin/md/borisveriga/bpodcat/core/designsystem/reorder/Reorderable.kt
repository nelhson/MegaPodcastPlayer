package md.borisveriga.bpodcat.core.designsystem.reorder

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Drag-to-reorder for any keyed lazy layout.
 *
 * This started as the play queue's own reorder and was generalised when the library and a YouTube
 * show's episode list needed the same gesture. Two things had to widen: the hit test, which was a
 * scalar comparison against [LazyListState]'s one-dimensional offsets and is now a rectangle test
 * so a grid works too, and the way a drag is started, since a grid tile has no room for a handle.
 *
 * Everything else is unchanged, and all of it is load-bearing — see [ReorderableState].
 */

/**
 * One laid-out item, as the hit test needs it.
 *
 * @property key the item's key in the lazy layout; the same value the call site passes to `items`.
 * @property offset the item's top-left corner relative to the viewport, in pixels.
 * @property size the item's size in pixels.
 */
data class ReorderableItem(
    val key: Any,
    val offset: IntOffset,
    val size: IntSize,
)

/**
 * Reads whichever items a lazy layout currently has laid out.
 *
 * An interface rather than a direct dependency on a state type because [LazyListState] and
 * [LazyGridState] report their layout through unrelated classes, and a reorder that only worked in
 * one of them would have meant two copies of the interesting code.
 */
interface ReorderableLayout {

    /**
     * True when items are laid out along one axis only.
     *
     * A list ignores sideways movement: a row that follows the finger horizontally looks broken,
     * and letting the accumulated offset drift off the row's own width would make the hit test
     * miss. A grid needs both axes.
     */
    val isLinear: Boolean

    /** The items on screen right now. Read afresh on every drag event; do not cache. */
    fun visibleItems(): List<ReorderableItem>
}

/**
 * Adapts a [LazyListState] to [ReorderableLayout].
 *
 * Items are given the full viewport width so the rectangle test degenerates to the vertical
 * comparison a list actually wants.
 *
 * @param listState the list being reordered.
 */
@Composable
fun rememberReorderableLayout(listState: LazyListState): ReorderableLayout =
    remember(listState) {
        object : ReorderableLayout {
            override val isLinear: Boolean = true

            override fun visibleItems(): List<ReorderableItem> {
                val width = listState.layoutInfo.viewportSize.width
                return listState.layoutInfo.visibleItemsInfo.map { info ->
                    ReorderableItem(
                        key = info.key,
                        offset = IntOffset(0, info.offset),
                        size = IntSize(width, info.size),
                    )
                }
            }
        }
    }

/**
 * Adapts a [LazyGridState] to [ReorderableLayout].
 *
 * @param gridState the grid being reordered.
 */
@Composable
fun rememberReorderableLayout(gridState: LazyGridState): ReorderableLayout =
    remember(gridState) {
        object : ReorderableLayout {
            override val isLinear: Boolean = false

            override fun visibleItems(): List<ReorderableItem> =
                gridState.layoutInfo.visibleItemsInfo.map { info ->
                    ReorderableItem(key = info.key, offset = info.offset, size = info.size)
                }
        }
    }

/**
 * Remembers reorder state for a keyed lazy layout.
 *
 * @param layout the layout being reordered, from one of the [rememberReorderableLayout] overloads.
 * @param items the upstream order; adopted whenever no drag is in progress.
 * @param keyOf the item's key, which must match the key given to the lazy layout's `items`.
 * @param onMove invoked once per completed gesture, with positions in [items].
 * @return the state, which the call site both reads and drives.
 */
@Composable
fun <T> rememberReorderableState(
    layout: ReorderableLayout,
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (Int, Int) -> Unit,
): ReorderableState<T> {
    // The callback is captured once but read late, so a recomposition with a new lambda does not
    // leave a drag in progress calling the previous one.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentKeyOf by rememberUpdatedState(keyOf)
    val state = remember(layout) {
        ReorderableState<T>(
            layout = layout,
            keyOf = { currentKeyOf(it) },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }

    LaunchedEffect(items) { state.adopt(items) }

    return state
}

/**
 * The order a collection is drawn in while a drag is in flight, and the move it will commit.
 *
 * Reordering is applied locally first and reported once, on release. Both halves of that matter.
 * The local order is what makes the gap follow the finger: the real one round-trips through the
 * database and back, which is far too slow to animate against. Reporting once is what keeps the
 * write side from renegotiating the whole collection on every frame — a drag past five rows is one
 * edit, not five.
 *
 * The consequence is that [order] and the upstream order disagree for as long as a drag lasts, and
 * that is deliberate: [adopt] refuses to overwrite the collection under the user's finger, and
 * picks the upstream version back up the moment the gesture ends.
 *
 * @property layout the lazy layout, read to work out which item the finger is over.
 * @property keyOf the item's key; what lets this find the item under the finger without knowing how
 *   many headers or filter chips precede the collection.
 * @property onMove reports a finished gesture as positions in the list this was last given.
 */
@Stable
class ReorderableState<T> internal constructor(
    private val layout: ReorderableLayout,
    private val keyOf: (T) -> Any,
    private val onMove: (Int, Int) -> Unit,
) {

    /** The collection as it should be drawn right now. */
    var order: List<T> by mutableStateOf(emptyList())
        private set

    /** The key of the item being dragged, or null. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    /** How far the dragged item is drawn from where the layout would otherwise put it, in pixels. */
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Where the dragged item sat when the gesture began, in the upstream ordering. */
    private var startIndex: Int = NO_INDEX

    /** True while a drag is in flight, which is what suspends [adopt]. */
    val isDragging: Boolean get() = draggingKey != null

    /**
     * Takes the upstream ordering, unless a drag is in progress.
     *
     * @param items the collection as its source of truth reports it.
     */
    fun adopt(items: List<T>) {
        if (draggingKey == null) order = items
    }

    /**
     * Begins a drag.
     *
     * @param key the key of the item whose handle was grabbed, or that was long-pressed.
     */
    fun onDragStart(key: Any) {
        startIndex = order.indexOfFirst { keyOf(it) == key }
        if (startIndex == NO_INDEX) return
        draggingKey = key
        offset = Offset.Zero
    }

    /**
     * Follows the finger, reordering [order] as the dragged item passes over its neighbours.
     *
     * @param delta movement since the last event, in pixels.
     */
    fun onDrag(delta: Offset) {
        val key = draggingKey ?: return
        offset += if (layout.isLinear) Offset(0f, delta.y) else delta

        val visible = layout.visibleItems()
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val centre = Offset(
            x = dragged.offset.x + offset.x + dragged.size.width / 2f,
            y = dragged.offset.y + offset.y + dragged.size.height / 2f,
        )

        val movableKeys = order.mapTo(mutableSetOf()) { keyOf(it) }
        val hovered = visible.firstOrNull { candidate ->
            candidate.key != key &&
                candidate.key in movableKeys &&
                candidate.contains(centre)
        } ?: return

        val from = order.indexOfFirst { keyOf(it) == key }
        val to = order.indexOfFirst { keyOf(it) == hovered.key }
        if (from == NO_INDEX || to == NO_INDEX) return

        order = order.toMutableList().apply { add(to, removeAt(from)) }
        // The two items have traded places, so the dragged one is now laid out where the hovered
        // one was. Cancelling that much of the accumulated offset is what stops it jumping by a
        // whole item at the moment of the swap.
        offset -= Offset(
            x = (hovered.offset.x - dragged.offset.x).toFloat(),
            y = (hovered.offset.y - dragged.offset.y).toFloat(),
        )
    }

    /** Commits the gesture, if it ended somewhere other than where it started. */
    fun onDragEnd() {
        val key = draggingKey
        val endIndex = order.indexOfFirst { keyOf(it) == key }
        val from = startIndex
        reset()

        // Either end of the gesture may have gone missing: the drag never took hold, or the item
        // left the collection while it was in flight.
        if (key == null || from == NO_INDEX || endIndex == NO_INDEX) return
        if (endIndex == from) return
        onMove(from, endIndex)
    }

    /** Abandons the gesture; the next upstream emission restores the drawn order. */
    fun onDragCancel() = reset()

    /**
     * Moves an item without a gesture, which is how the accessibility actions work.
     *
     * @param from the item's current position.
     * @param to where it should end up.
     */
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        onMove(from, to)
    }

    private fun reset() {
        draggingKey = null
        offset = Offset.Zero
    }

    private companion object {
        /** `indexOfFirst`'s "not found", named so the guards read as guards. */
        const val NO_INDEX = -1
    }
}

/** True when [point] falls inside this item's bounds. */
private fun ReorderableItem.contains(point: Offset): Boolean =
    point.x >= offset.x &&
        point.x < offset.x + size.width &&
        point.y >= offset.y &&
        point.y < offset.y + size.height

/**
 * Starts a reorder drag from a dedicated handle.
 *
 * Preferred wherever there is room for one: a handle gives the gesture a visible target, and it
 * leaves the item itself free to be a button. See [reorderableLongPressDrag] for the case where
 * there is no room.
 *
 * @param state the reorder state to drive.
 * @param key the dragged item's key.
 */
fun <T> Modifier.reorderableHandle(state: ReorderableState<T>, key: Any): Modifier =
    pointerInput(key) {
        detectDragGestures(
            onDragStart = { state.onDragStart(key) },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragCancel() },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount)
            },
        )
    }

/**
 * Starts a reorder drag from a long press on the item itself.
 *
 * For items with nowhere to put a handle — a grid tile is artwork edge to edge, and carving a grip
 * out of it would cost the cover the space it exists to show. The long press is what keeps the
 * gesture from fighting the grid's own scrolling.
 *
 * @param state the reorder state to drive.
 * @param key the dragged item's key.
 */
fun <T> Modifier.reorderableLongPressDrag(state: ReorderableState<T>, key: Any): Modifier =
    pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(key) },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragCancel() },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount)
            },
        )
    }

/**
 * The reorder gesture, published as accessibility actions.
 *
 * Not a nicety: a drag is invisible to a screen reader, so without these the collection would be
 * readable and completely uneditable with TalkBack on. Returned as a list rather than applied as a
 * modifier so a call site with actions of its own — the queue also offers "remove" — can append to
 * them instead of choosing between them.
 *
 * The actions belong on whichever node merges the item's children, since that merged node is what a
 * screen reader lands on.
 *
 * @param index the item's position in [order].
 * @param moveUpLabel spoken label for moving one position earlier.
 * @param moveDownLabel spoken label for moving one position later.
 * @return the applicable actions; empty ends of the collection simply offer fewer.
 */
fun <T> ReorderableState<T>.moveActions(
    index: Int,
    moveUpLabel: String,
    moveDownLabel: String,
): List<CustomAccessibilityAction> = buildList {
    if (index > 0) {
        add(
            CustomAccessibilityAction(moveUpLabel) {
                move(index, index - 1)
                true
            },
        )
    }
    if (index < order.lastIndex) {
        add(
            CustomAccessibilityAction(moveDownLabel) {
                move(index, index + 1)
                true
            },
        )
    }
}
