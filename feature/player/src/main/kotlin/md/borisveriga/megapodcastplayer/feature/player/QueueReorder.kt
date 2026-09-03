package md.borisveriga.megapodcastplayer.feature.player

/**
 * Moves [movedId] into the slot [targetId] currently occupies, keeping everything else in order.
 *
 * This is drag-and-drop semantics rather than a swap: the dragged episode takes the target's place
 * and the episodes it passed over shuffle up or down by one. A swap would be wrong here — dragging
 * an episode from the bottom of the queue to the top would fling the top one to the bottom.
 *
 * Stated in terms of ids rather than indices because the two lists this has to agree with — the
 * player's queue and the durable one — are indexed differently: the screen shows only what comes
 * after the episode playing, so its own indices are offset by however much of the queue is already
 * behind. Matching by id makes that offset irrelevant, and makes a queue that has drifted out of
 * step fail closed instead of moving the wrong episode.
 *
 * @param movedId the episode the user dragged.
 * @param targetId the episode whose position it was dropped on.
 * @return the new order, or null if either id is absent or the move is a no-op — in both cases
 *   there is nothing to apply.
 */
internal fun List<String>.movedTo(movedId: String, targetId: String): List<String>? {
    val from = indexOf(movedId)
    val to = indexOf(targetId)
    if (from < 0 || to < 0 || from == to) return null

    return toMutableList().apply {
        removeAt(from)
        add(to, movedId)
    }
}
