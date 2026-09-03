package md.borisveriga.bpodcat.core.wearprotocol

import kotlinx.serialization.Serializable
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * One entry of the queue as the watch sees it.
 *
 * Only what a 45 mm screen can show is sent: no description, no audio URL, no download state. The
 * watch never plays anything itself, so [id] exists solely to be echoed back in
 * [WearCommand.PlayEpisode].
 *
 * @property id the episode id, echoed back when the user taps the row.
 * @property title the episode title.
 * @property showTitle the owning podcast's title.
 */
@Serializable
data class QueuedEpisode(
    val id: String,
    val title: String,
    val showTitle: String,
)

/**
 * Everything the watch needs to render the remote control, published by the phone.
 *
 * This is a *snapshot*: [positionMs] was true at the instant the phone captured it. The phone does
 * not re-publish while the position merely advances — that would put a Bluetooth write on a timer
 * and flatten both batteries — so the watch advances the clock itself with [positionAfter].
 *
 * @property episodeId the episode loaded on the phone, or null when nothing is.
 * @property title the episode title.
 * @property showTitle the owning podcast's title.
 * @property isPlaying true only while audio is actually coming out of the phone.
 * @property isBuffering true while the phone is loading and cannot produce audio yet.
 * @property positionMs playback position when the phone captured this snapshot.
 * @property durationMs total duration, or `0` while the phone does not know it.
 * @property speed the playback rate, which is also what [positionAfter] extrapolates with.
 * @property skipForwardMs the user's configured forward jump, so the watch can label its button
 *   with the same number the phone would use.
 * @property skipBackMs the user's configured back jump.
 * @property hasNext whether another episode follows in the queue.
 * @property hasPrevious whether an episode precedes the current one in the queue.
 * @property upNext the queue after the current episode, in play order.
 * @property publishedAtMs the phone's wall clock when it published, in epoch milliseconds.
 *   Its first job is to make every publish unique, which matters because the Data Layer silently
 *   drops a data item whose bytes are unchanged. The watch app's screen does not time anything with
 *   it — it stamps arrivals with its own clock instead, which needs no agreement between the two
 *   devices. The watch-face surfaces have no arrival to stamp and do use it; see
 *   `extrapolatedPositionMs` in `:wear`, and the cap that keeps a day-old reading from becoming a
 *   confident lie.
 */
@Serializable
data class NowPlayingSnapshot(
    val episodeId: String? = null,
    val title: String = "",
    val showTitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = PlaybackSettings.DEFAULT_SPEED,
    val skipForwardMs: Long = PlaybackSettings.DEFAULT_SKIP_FORWARD_MS,
    val skipBackMs: Long = PlaybackSettings.DEFAULT_SKIP_BACK_MS,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val upNext: List<QueuedEpisode> = emptyList(),
    val publishedAtMs: Long = 0L,
) {

    /** True when the phone has nothing loaded, i.e. there is nothing to remote-control. */
    val isIdle: Boolean get() = episodeId == null

    /** The duration once the phone knows it, otherwise null. */
    val knownDurationMs: Long? get() = durationMs.takeIf { it > 0L }

    /**
     * Where playback has reached [elapsedMs] milliseconds after this snapshot arrived.
     *
     * A paused phone does not move, so the position is returned unchanged; a playing one advances
     * by the elapsed time scaled by [speed], because a 2x listener covers two seconds of episode
     * per second of wall clock. The result never runs past [durationMs]: the alternative is a
     * progress ring that keeps filling after the episode ended, which reads as a bug.
     *
     * @param elapsedMs milliseconds since the watch received this snapshot. Negative values are
     *   treated as zero rather than rewinding.
     */
    fun positionAfter(elapsedMs: Long): Long {
        if (!isPlaying) return positionMs
        val advanced = positionMs + (elapsedMs.coerceAtLeast(0L) * speed).toLong()
        return advanced.coerceIn(0L, knownDurationMs ?: advanced)
    }

    /**
     * Fraction played [elapsedMs] after this snapshot arrived, in `0f..1f`.
     *
     * Returns `0f` while the duration is unknown, which pins the progress ring to empty rather than
     * letting it jump once the duration finally arrives.
     */
    fun progressAfter(elapsedMs: Long): Float {
        val duration = knownDurationMs ?: return 0f
        return (positionAfter(elapsedMs).toFloat() / duration).coerceIn(0f, 1f)
    }

    /**
     * The same snapshot with the two time-varying fields blanked, so that two of them can be
     * compared for a *substantive* change.
     *
     * [positionMs] and [publishedAtMs] move constantly while an episode plays, so comparing whole
     * snapshots would report a change several times a second and put a Bluetooth write behind each
     * one. Everything else — what is loaded, whether it is playing, the speed, the queue — only
     * changes when something really happened, which is exactly when the watch needs telling.
     *
     * The position is not thereby ignored: the publisher checks it separately against what the
     * previous snapshot predicts, which is how a seek is told apart from ordinary progress.
     */
    fun withoutTiming(): NowPlayingSnapshot = copy(positionMs = 0L, publishedAtMs = 0L)
}
