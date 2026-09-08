package md.borisveriga.megapodcastplayer.core.model.chapters

/**
 * Which chapter a position falls in.
 *
 * @param positionMs the current playback position.
 * @return the index of the chapter containing [positionMs], or `-1` when the position is before the
 *   first chapter starts — which happens whenever a publisher's list opens at something other than
 *   zero, and is a real state rather than an error.
 */
fun List<Chapter>.indexOfCurrent(positionMs: Long): Int =
    indexOfLast { it.startMs <= positionMs }

/**
 * Where the next chapter begins.
 *
 * @param positionMs the current playback position.
 * @return the next chapter's start, or null when the last chapter is already playing.
 */
fun List<Chapter>.nextStartAfter(positionMs: Long): Long? =
    firstOrNull { it.startMs > positionMs }?.startMs

/**
 * Where "previous chapter" should seek to.
 *
 * Restarts the current chapter when more than [CHAPTER_RESTART_THRESHOLD_MS] into it, and steps to
 * the one before only when pressed near a boundary. That is how the player's own
 * previous-*episode* button already behaves, and the expectation transfers: the first press of
 * "back" means "start this again", not "skip what I am listening to".
 *
 * @param positionMs the current playback position.
 * @return where to seek, or null when there is nothing before the position.
 */
fun List<Chapter>.previousStartBefore(positionMs: Long): Long? {
    val current = indexOfCurrent(positionMs)
    if (current < 0) return null

    val currentStart = this[current].startMs
    if (positionMs - currentStart > CHAPTER_RESTART_THRESHOLD_MS) return currentStart

    return if (current == 0) currentStart else this[current - 1].startMs
}

/**
 * How far into a chapter "previous" stops meaning "the one before".
 *
 * Three seconds, matching the player's own threshold for skipping back to the previous episode.
 */
const val CHAPTER_RESTART_THRESHOLD_MS: Long = 3_000L
