package md.borisveriga.bpodcat.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * The durable side of playback: the "up next" queue and the user's playback preferences.
 *
 * The *live* queue belongs to ExoPlayer — it is what actually plays — and this is its mirror, which
 * is what survives a process death and what the watch will read. The two are kept in step by
 * [md.borisveriga.bpodcat.core.media.PlaybackProgressRecorder], which the implementation of this
 * interface also provides.
 */
interface PlaybackRepository {

    /** Observes the durable queue in play order, ready to hand to the player. */
    fun observeQueue(): Flow<List<PlayableEpisode>>

    /** Observes the user's playback preferences. */
    fun observePlaybackSettings(): Flow<PlaybackSettings>

    /**
     * Loads one episode with its show details.
     *
     * @param episodeId the episode.
     * @return the episode, or null if it is not stored (its show was removed).
     */
    suspend fun playableEpisode(episodeId: String): PlayableEpisode?

    /** Appends an episode to the end of the queue; already-queued episodes are left where they are. */
    suspend fun enqueue(episodeId: String)

    /** Removes an episode from the queue. */
    suspend fun dequeue(episodeId: String)

    /**
     * Rewrites the queue's order.
     *
     * Takes the whole order rather than a pair of indices because that is what the caller has after
     * a drag: the list as the user now sees it. Positions in the table are an implementation
     * detail, and reassigning all of them is what keeps them contiguous.
     *
     * The service mirrors every player edit back into the same table, so this write is usually
     * redundant. It exists for the case where it is not — an edit made while the service is
     * unreachable is dropped by the player, and without this the user's reordering would vanish
     * with it.
     *
     * @param episodeIds the new order, first to play first. Ids not in the queue are queued;
     *   queued ids not listed are dropped.
     */
    suspend fun reorderQueue(episodeIds: List<String>)

    /**
     * Marks an episode played or unplayed, and says where it should resume from afterwards.
     *
     * The default resets the position, so re-opening a finished episode starts it from the
     * beginning rather than from its last second. An undo is what the parameter exists for: putting
     * back a played flag without putting back the position the user had reached would make the undo
     * of an accidental "mark as played" cost them their place, which is most of what they were
     * trying to save.
     *
     * @param episodeId the episode to mark.
     * @param isPlayed the flag to write.
     * @param positionMs where the episode should resume from.
     */
    suspend fun setPlayed(episodeId: String, isPlayed: Boolean, positionMs: Long = 0L)

    /** Sets the playback rate, clamped to [PlaybackSettings.SPEED_RANGE]. */
    suspend fun setSpeed(speed: Float)

    /** Sets how far the skip buttons jump. */
    suspend fun setSkipIntervals(forwardMs: Long, backMs: Long)

    /**
     * Enables or disables advancing to the next queued episode when one finishes.
     *
     * Stored rather than applied to the player directly: the service reads it when it decides what
     * to do at the end of an episode, and it must survive the service being killed.
     */
    suspend fun setAutoPlayNext(enabled: Boolean)
}
