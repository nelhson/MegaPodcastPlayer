package md.borisveriga.bpodcat.core.media

/**
 * Supplies the playback service with episodes to play.
 *
 * `:core:data` depends on `:core:media`, not the other way round, so the service cannot read the
 * database directly. This interface inverts that: the data layer implements it and Hilt binds it in,
 * which keeps the module graph acyclic and makes the service testable with a stub.
 */
interface PlaybackQueueSource {

    /**
     * Rebuilds the queue after the process was killed, for a system-initiated resumption — the user
     * pressing play on a headset or on the Android 13+ resumption tile.
     *
     * @return the durable queue in play order. The first entry is the one to resume; an empty list
     *   tells the framework there is nothing to resume.
     */
    suspend fun resumableQueue(): List<PlayableEpisode>

    /**
     * Loads episodes by id, preserving the order of [episodeIds].
     *
     * Ids that no longer exist (the show was removed while the queue still referenced it) are
     * skipped rather than reported, because a stale queue entry is not an error the user can act on.
     */
    suspend fun playableEpisodes(episodeIds: List<String>): List<PlayableEpisode>
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
