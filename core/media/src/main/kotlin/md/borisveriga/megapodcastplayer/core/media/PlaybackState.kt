package md.borisveriga.megapodcastplayer.core.media

/**
 * Everything the UI needs to render the player, flattened out of Media3's callback API.
 *
 * This is a snapshot: [positionMs] is only correct at the moment it was produced, which is why
 * [PlaybackConnection] re-emits it on a timer while playing rather than expecting the UI to
 * extrapolate.
 *
 * @property isConnected whether a [androidx.media3.session.MediaController] is attached to the
 *   playback service. False for the first frame after a cold start.
 * @property episodeId the episode currently loaded, or null when nothing is.
 * @property title the loaded episode's title, cached here so the mini player renders before the
 *   database read completes.
 * @property showTitle the owning show's title.
 * @property artworkUrl artwork for the loaded episode.
 * @property isPlaying true only while audio is actually coming out; false while buffering or paused.
 * @property isBuffering true while the player is loading and cannot produce audio yet.
 * @property positionMs playback position at the time of the snapshot.
 * @property durationMs total duration in milliseconds, or `0` until the player has read it. Media3's
 *   own `C.TIME_UNSET` is normalised away here so that nothing outside this module has to know it.
 * @property bufferedPositionMs how far ahead the buffer reaches, for the secondary scrubber track.
 * @property speed the current playback rate.
 * @property queueEpisodeIds every episode in the player's queue, in play order, including the
 *   currently loaded one.
 * @property queueIndex index of [episodeId] within [queueEpisodeIds].
 * @property errorMessage set when playback stopped because of an error.
 */
data class PlaybackState(
    val isConnected: Boolean = false,
    val episodeId: String? = null,
    val title: String = "",
    val showTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val queueEpisodeIds: List<String> = emptyList(),
    val queueIndex: Int = 0,
    val errorMessage: String? = null,
) {

    /** True when there is nothing loaded, i.e. the mini player should be hidden. */
    val isIdle: Boolean get() = episodeId == null

    /** The duration if the player has read it, otherwise null. */
    val knownDurationMs: Long? get() = durationMs.takeIf { it > 0L }

    /**
     * Fraction played, in `0f..1f`.
     *
     * Returns `0f` while the duration is unknown, which keeps the scrubber pinned to the left rather
     * than jumping when the duration finally arrives.
     */
    val progress: Float
        get() = knownDurationMs?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    /** True when another episode follows in the queue. */
    val hasNext: Boolean get() = queueIndex < queueEpisodeIds.lastIndex

    /** The episodes queued after the current one, in play order. */
    val upNextEpisodeIds: List<String>
        get() = queueEpisodeIds.drop(queueIndex + 1)
}
