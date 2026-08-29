package md.borisveriga.bpodcat.core.data.playback

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.media.PlaybackQueueSource

/**
 * Starts playback from an episode id.
 *
 * Every feature that can begin playback — an episode row in a show, the queue, later the watch —
 * has the id and nothing else, while [PlaybackConnection] needs a fully resolved
 * [md.borisveriga.bpodcat.core.media.PlayableEpisode]. This is the one place that bridges the two,
 * so no screen has to know how to do the lookup.
 *
 * @property playbackRepository resolves ids and owns the durable queue.
 * @property queueSource the same object seen through the service's read interface, which is where
 *   the "resume the last played episode when nothing is queued" rule lives.
 * @property connection the player itself.
 */
@Singleton
class EpisodePlayer @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val queueSource: PlaybackQueueSource,
    private val connection: PlaybackConnection,
) {

    /**
     * Guards [restoreQueue] so that the first screen to appear restores the queue and every
     * subsequent one is a no-op, however many view models ask.
     */
    private val queueRestored = AtomicBoolean(false)

    /**
     * Plays an episode now, resuming from its stored position.
     *
     * @param episodeId the episode to play.
     * @return true if the episode was found and handed to the player.
     */
    suspend fun play(episodeId: String): Boolean {
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        connection.playNow(episode)
        return true
    }

    /**
     * Queues an episode to play right after the current one.
     *
     * The durable queue is written here as well as by the service's timeline listener. That looks
     * redundant, and usually is — but if the service is unreachable the player edit is dropped
     * silently, and this is what keeps the user's intent from vanishing with it. Writing twice is
     * harmless: enqueueing an already-queued episode is a no-op, and the listener's mirror is
     * authoritative when both land.
     */
    suspend fun playNext(episodeId: String): Boolean {
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        connection.playNext(episode)
        playbackRepository.enqueue(episodeId)
        return true
    }

    /** Appends an episode to the end of the queue; see [playNext] on the double write. */
    suspend fun addToQueue(episodeId: String): Boolean {
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        connection.addToQueue(episode)
        playbackRepository.enqueue(episodeId)
        return true
    }

    /** Removes an episode from both the live player queue and the durable one. */
    suspend fun removeFromQueue(episodeId: String) {
        connection.removeFromQueue(episodeId)
        playbackRepository.dequeue(episodeId)
    }

    /**
     * Loads the persisted queue into a player that has none — a cold start.
     *
     * Loaded paused: the mini player appears where the user left it, but launching the app does not
     * start making noise. Runs at most once per process.
     */
    suspend fun restoreQueue() {
        if (queueRestored.get()) return

        val state = connection.currentState()
        // Not reachable yet — the service is still starting. Leave the flag alone so the next
        // caller tries again; giving up here would leave the player permanently empty while the
        // database still holds a queue, and the first edit would then overwrite it.
        if (!state.isConnected) return

        queueRestored.set(true)
        if (state.queueEpisodeIds.isNotEmpty()) return

        val queue = queueSource.resumableQueue()
        if (queue.isEmpty()) return

        connection.setQueue(
            episodes = queue,
            startIndex = 0,
            startPositionMs = queue.first().episode.positionMs,
            playWhenReady = false,
        )
    }
}
