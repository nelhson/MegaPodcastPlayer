package md.borisveriga.megapodcastplayer.core.media

/**
 * Supplies the playback service with episodes to play.
 *
 * `:core:data` depends on `:core:media`, not the other way round, so the service cannot read the
 * database directly. This interface inverts that: the data layer implements it and Hilt binds it in,
 * which keeps the module graph acyclic and makes the service testable with a stub.
 */
interface PlaybackQueueSource {

    /**
     * Says where playback was left, for whoever rebuilds the player after the process was killed:
     * the app's own cold start, a system-initiated resumption — the user pressing play on a headset
     * or on the Android 13+ resumption tile — and the widget, which draws the same episode.
     *
     * One answer for all of them, so they cannot disagree about which episode comes back.
     *
     * @return the durable queue and the place in it; [ResumePoint.EMPTY] when there is nothing to
     *   resume.
     */
    suspend fun resumePoint(): ResumePoint

    /**
     * Loads episodes by id, preserving the order of [episodeIds].
     *
     * Ids that no longer exist (the show was removed while the queue still referenced it) are
     * skipped rather than reported, because a stale queue entry is not an error the user can act on.
     */
    suspend fun playableEpisodes(episodeIds: List<String>): List<PlayableEpisode>
}

/**
 * Where playback was left: the queue, which entry of it was loaded, and how far in.
 *
 * The index is part of the answer because the queue keeps the episodes *before* the one playing —
 * they are what "previous" goes back to — so the head of the queue is not, in general, the episode
 * the user was listening to.
 *
 * @property queue the durable queue in play order; empty when there is nothing to resume.
 * @property index which entry of [queue] to resume. Zero when the queue is empty.
 * @property positionMs how far into that entry playback had reached.
 */
data class ResumePoint(
    val queue: List<PlayableEpisode>,
    val index: Int,
    val positionMs: Long,
) {

    /** The episode to resume, or null when there is nothing to resume. */
    val episode: PlayableEpisode? get() = queue.getOrNull(index)

    /**
     * Drops the entries that fail [predicate] without losing the place.
     *
     * The index is re-resolved by episode id, so a dropped entry ahead of the current one does not
     * shift the user onto its neighbour. When the current entry is itself dropped the point falls
     * back to the head of what is left, at that episode's own stored position.
     *
     * @param predicate true for the entries to keep.
     * @return the same point over the filtered queue.
     */
    fun keeping(predicate: (PlayableEpisode) -> Boolean): ResumePoint {
        val kept = queue.filter(predicate)
        val currentId = episode?.episode?.id
        val keptIndex = kept.indexOfFirst { it.episode.id == currentId }
        return if (keptIndex >= 0) {
            ResumePoint(queue = kept, index = keptIndex, positionMs = positionMs)
        } else {
            ResumePoint(
                queue = kept,
                index = 0,
                positionMs = kept.firstOrNull()?.episode?.positionMs ?: 0L,
            )
        }
    }

    companion object {
        /** Nothing queued and nothing played. */
        val EMPTY = ResumePoint(queue = emptyList(), index = 0, positionMs = 0L)
    }
}

/**
 * Receives the playback facts worth surviving the process: how far through each episode the user
 * got, which ones they finished, and what is queued.
 *
 * Implemented by `:core:data`; see [PlaybackQueueSource] for why the dependency is inverted.
 */
interface PlaybackProgressRecorder {

    /**
     * Records the current position of an episode.
     *
     * Called every few seconds while playing, so implementations must be cheap and must not block.
     *
     * @param episodeId the episode being played.
     * @param positionMs the position to store.
     * @param durationMs total duration as the player measured it, or null if not yet known. Feeds
     *   routinely lie about `itunes:duration`, so the player's value is the better one to keep.
     */
    suspend fun recordPosition(episodeId: String, positionMs: Long, durationMs: Long?)

    /**
     * Records that an episode played to the end.
     *
     * Implementations should reset the stored position: a finished episode that is opened again
     * should start from the beginning, not from the last second.
     */
    suspend fun recordCompleted(episodeId: String)

    /**
     * Mirrors the player's live queue into durable storage.
     *
     * @param episodeIds the queue in play order, including the episode currently loaded.
     */
    suspend fun recordQueue(episodeIds: List<String>)
}
