package md.borisveriga.megapodcastplayer.core.data.playback

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull

/**
 * Takes YouTube out of the app's player when the user chooses the official source.
 *
 * Under [YouTubeSource.OFFICIAL] the app's player may not play a YouTube video: the resolver below
 * it refuses the sentinel, so an episode left in the queue would fail the moment the queue reached
 * it, with an error message about YouTube being unavailable that is true but unhelpful. The honest
 * thing is to take such episodes out of the queue at the moment the choice is made — the one that
 * is playing included, which stops it — and leave every RSS episode where it was.
 *
 * Done again on start, because the choice can be made while the process is dead: the queue the
 * service restores may still hold a YouTube episode from before. Only the one direction is acted
 * on. Switching back to the extractor puts nothing in the queue — what left it is gone from it,
 * and is a tap away in its show.
 *
 * Application-scoped and started from the application object, alongside the other collectors that
 * have to outlive every screen — the player does.
 *
 * @property userPreferences where the choice is read from.
 * @property connection the player, whose queue is read and trimmed.
 * @property playbackRepository resolves a queued id to its episode, and holds the durable queue.
 * @property scope application scope.
 */
@Singleton
class YouTubeSourceApplier @Inject constructor(
    private val userPreferences: UserPreferencesDataSource,
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [start] so that it runs once however many times the application object calls it. */
    private val started = AtomicBoolean(false)

    /**
     * Begins watching the choice.
     *
     * Called from the application's `onCreate`. Returns immediately; the work runs on [scope].
     *
     * @return the collecting job, or null if it was already started. Production callers ignore it;
     *   tests join it.
     */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            // The flow emits its current value first, which is the "on start" pass; it is distinct
            // already, so a change of episode does not re-run this.
            userPreferences.youTubeSource
                .filter { it == YouTubeSource.OFFICIAL }
                .collect { removeYouTubeFromQueue() }
        }
    }

    /**
     * Removes every YouTube episode from the player's queue, durable copy included.
     *
     * An unreachable service leaves the queue alone: nothing is playing in that case, and the
     * durable queue is trimmed the next time this runs with the service up.
     */
    private suspend fun removeYouTubeFromQueue() {
        if (!connection.awaitRestored()) return
        val queued = connection.currentState().queueEpisodeIds
        val youTube = queued.filter { episodeId -> isYouTube(episodeId) }
        for (episodeId in youTube) {
            connection.removeFromQueue(episodeId)
            playbackRepository.dequeue(episodeId)
        }
    }

    /**
     * Whether a queued episode is a YouTube video; one the library no longer has is not.
     *
     * @param episodeId the queued episode.
     */
    private suspend fun isYouTube(episodeId: String): Boolean {
        val playable = playbackRepository.playableEpisode(episodeId) ?: return false
        return youTubeVideoIdOrNull(playable.episode.audioUrl) != null
    }
}
