package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import md.borisveriga.bpodcat.core.media.PlayableEpisode

/**
 * Remembers the drag-to-reorder state for the queue.
 *
 * @param listState the queue's list state, read to work out which row the finger is over.
 * @param upNext the queue as the player reports it; adopted whenever no drag is in progress.
 * @param onMove invoked once per completed gesture, with positions in [upNext].
 * @return the state, which [QueueScreen] both reads and drives.
 */
@Composable
internal fun rememberQueueDragState(
    listState: LazyListState,
    upNext: List<PlayableEpisode>,
    onMove: (Int, Int) -> Unit,
): QueueDragState {
    // The callback is captured once but read late, so a recomposition with a new lambda does not
    // leave a drag in progress calling the previous one.
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState) {
        QueueDragState(listState) { from, to -> currentOnMove(from, to) }
    }

    LaunchedEffect(upNext) { state.adopt(upNext) }

    return state
}

/**
 * The order the queue is drawn in while a drag is in flight, and the move it will commit.
 *
 * Reordering is applied locally first and reported once, on release. Both halves of that matter.
 * The local order is what makes the gap follow the finger: the real one round-trips through the
 * media session and back out of the database, which is far too slow to animate against. Reporting
 * once is what keeps the player and the database from renegotiating the queue on every frame — a
 * drag past five rows is one edit, not five.
 *
 * The consequence is that [order] and the player's queue disagree for as long as a drag lasts, and
 * that is deliberate: [adopt] refuses to overwrite the list under the user's finger, and picks the
 * player's version back up the moment the gesture ends.
 *
 * @property listState the queue's list state; item keys are episode ids, which is what lets this
 *   find the row under the finger without knowing how many headers precede the list.
 * @property onMove reports a finished gesture as positions in the list this was last given.
 */
@Stable
internal class QueueDragState(
    private val listState: LazyListState,
    private val onMove: (Int, Int) -> Unit,
) {

    /** The queue as it should be drawn right now. */
    var order: List<PlayableEpisode> by mutableStateOf(emptyList())
        private set

    /** The episode being dragged, or null. */
    var draggingId: String? by mutableStateOf(null)
        private set

    /** How far the dragged row is drawn from where the list would otherwise put it, in pixels. */
    var offsetY: Float by mutableFloatStateOf(0f)
        private set

    /** Where the dragged episode sat when the gesture began, in the player's own ordering. */
    private var startIndex: Int = NO_INDEX

    /**
     * Takes the player's ordering, unless a drag is in progress.
     *
     * @param upNext the queue after the episode playing.
     */
    fun adopt(upNext: List<PlayableEpisode>) {
        if (draggingId == null) order = upNext
    }

    /**
     * Begins a drag.
     *
     * @param episodeId the episode whose handle was grabbed.
     */
    fun onDragStart(episodeId: String) {
        startIndex = order.indexOfFirst { it.episode.id == episodeId }
        if (startIndex == NO_INDEX) return
        draggingId = episodeId
        offsetY = 0f
    }

    /**
     * Follows the finger, reordering [order] as the dragged row passes over its neighbours.
     *
     * @param deltaY vertical movement since the last event, in pixels.
     */
    fun onDrag(deltaY: Float) {
        val id = draggingId ?: return
        offsetY += deltaY

        val dragged = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == id } ?: return
        val centre = dragged.offset + offsetY + dragged.size / 2f

        val queuedKeys = order.mapTo(mutableSetOf()) { it.episode.id }
        val hovered = listState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
            candidate.key != id &&
                candidate.key in queuedKeys &&
                centre >= candidate.offset &&
                centre < candidate.offset + candidate.size
        } ?: return

        val from = order.indexOfFirst { it.episode.id == id }
        val to = order.indexOfFirst { it.episode.id == hovered.key }
        if (from == NO_INDEX || to == NO_INDEX) return

        order = order.toMutableList().apply { add(to, removeAt(from)) }
        // The two rows have traded places, so the row is now laid out where the hovered one was.
        // Cancelling that much of the accumulated offset is what stops the row jumping by a row
        // height at the moment of the swap.
        offsetY -= (hovered.offset - dragged.offset)
    }

    /** Commits the gesture, if it ended somewhere other than where it started. */
    fun onDragEnd() {
        val id = draggingId
        val endIndex = order.indexOfFirst { it.episode.id == id }
        val from = startIndex
        reset()

        // Either end of the gesture may have gone missing: the drag never took hold, or the
        // episode left the queue while it was in flight.
        if (id == null || from == NO_INDEX || endIndex == NO_INDEX) return
        if (endIndex == from) return
        onMove(from, endIndex)
    }

    /** Abandons the gesture; the next emission from the player restores the drawn order. */
    fun onDragCancel() = reset()

    /**
     * Moves an episode without a gesture, which is how the row's accessibility actions work.
     *
     * @param from the episode's current position.
     * @param to where it should end up.
     */
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        onMove(from, to)
    }

    private fun reset() {
        draggingId = null
        offsetY = 0f
    }

    private companion object {
        /** `indexOfFirst`'s "not found", named so the guards read as guards. */
        const val NO_INDEX = -1
    }
}
