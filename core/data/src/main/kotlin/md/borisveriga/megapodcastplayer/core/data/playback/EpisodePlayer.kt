package md.borisveriga.megapodcastplayer.core.data.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.QueueAddResult
import md.borisveriga.megapodcastplayer.core.model.Episode

/**
 * Starts playback from an episode id.
 *
 * Every feature that can begin playback — an episode row in a show, the queue, later the watch —
 * has the id and nothing else, while [PlaybackConnection] needs a fully resolved
 * [md.borisveriga.megapodcastplayer.core.media.PlayableEpisode]. This is the one place that bridges the two,
 * so no screen has to know how to do the lookup.
 *
 * @property playbackRepository resolves ids and owns the durable queue.
 * @property showSettings the per-show intro to skip; see [play].
 * @property connection the player itself.
 */
@Singleton
class EpisodePlayer @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val showSettings: ShowSettingsRepository,
    private val connection: PlaybackConnection,
) {

    /**
     * Plays an episode now, resuming from its stored position.
     *
     * An episode that has never been started begins after the show's intro, when it has one: a show
     * with a forty-second musical opening is forty seconds of the same music every episode, and
     * skipping it by hand each time is the kind of chore an app should absorb. An episode already
     * in progress resumes where it was left, intro or no intro — the user is past it, and moving
     * their position forward would be the app losing their place rather than saving them a tap.
     *
     * @param episodeId the episode to play.
     * @return true if the episode was found and handed to the player.
     */
    suspend fun play(episodeId: String): Boolean {
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        val startPositionMs = if (episode.episode.positionMs > 0L) {
            episode.episode.positionMs
        } else {
            introEndOf(episode.episode.podcastId)
        }
        connection.playNow(episode, startPositionMs = startPositionMs)
        return true
    }

    /**
     * How far into an episode of this show the content starts.
     *
     * @param podcastId the show.
     * @return the intro length in milliseconds; zero when the show has none, which is the default.
     */
    private suspend fun introEndOf(podcastId: String): Long =
        showSettings.observeSettings(podcastId).first().skipIntroMs

    /**
     * Plays an episode of a show that is not in the library.
     *
     * The one entry point here that takes an episode rather than an id, and it has to: the episode
     * has no row to look up. It is what lets someone hear a show before subscribing to it, which is
     * the only honest way to answer "is this worth following".
     *
     * Nothing is written. The episode is not queued, its position is not kept — the mirror that
     * saves positions updates a row by id and there is no such row, so the write finds nothing and
     * changes nothing — and the show is not added. Play it, decide, and either subscribe or leave;
     * that is the whole of it.
     *
     * The ids on [episode] are the ones a subscription would produce (see `PodcastPreview`), so
     * subscribing afterwards does not orphan the audio Media3 has already cached for it.
     *
     * @param episode the episode to play, built from a feed rather than read from the database.
     * @param showTitle the show it belongs to, for the notification and the player.
     * @param showArtworkUrl the show's cover, used when the episode carries none.
     */
    suspend fun playUnsubscribed(
        episode: Episode,
        showTitle: String,
        showArtworkUrl: String?,
    ) {
        connection.playNow(
            PlayableEpisode(
                episode = episode,
                showTitle = showTitle,
                showArtworkUrl = showArtworkUrl,
            ),
            // From the beginning: there is no stored position to resume, and no show settings to
            // read an intro length from — the show is not in the library yet.
            startPositionMs = 0L,
        )
    }

    /**
     * Plays an episode from a given position rather than from where it was left.
     *
     * What tapping a saved moment does. Separate from [play] because the two disagree on purpose:
     * [play] resumes, and resuming is exactly wrong here — the user asked for one particular second
     * of an episode they may well have finished.
     *
     * @param episodeId the episode to play.
     * @param positionMs where to start.
     * @return true if the episode was found and handed to the player.
     */
    suspend fun playFrom(episodeId: String, positionMs: Long): Boolean {
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        connection.playNow(episode, startPositionMs = positionMs)
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

    /**
     * Appends an episode to the end of the queue; see [playNext] on the double write.
     *
     * The answer is what the *player* did, because that is what the user will see: an episode left
     * behind the one playing is moved to the end, and one already waiting or already playing is
     * reported as such rather than as "queued" — which it was, to a list that did not change.
     *
     * @param episodeId the episode to queue.
     * @return what was done. [QueueAddResult.UNPLAYABLE] also covers an episode that is not stored,
     *   there being nothing a caller would do differently; an unreachable player is reported as
     *   [QueueAddResult.ADDED], because the durable queue took the episode and the player is
     *   rebuilt from it.
     */
    suspend fun addToQueue(episodeId: String): QueueAddResult {
        val episode = playbackRepository.playableEpisode(episodeId)
            ?: return QueueAddResult.UNPLAYABLE
        val result = connection.addToQueue(episode)
        // Not written for an episode the player refused: the queue would hold a row that can never
        // play, until the next timeline change silently took it out again.
        if (result == QueueAddResult.UNPLAYABLE) return result
        playbackRepository.enqueue(episodeId)
        return if (result == QueueAddResult.UNREACHABLE) QueueAddResult.ADDED else result
    }

    /**
     * Marks an episode played or unplayed.
     *
     * A played episode also leaves the queue. The two go together everywhere the app offers this —
     * from a list row, from the episode sheet, from the player's own "mark played" — because an
     * episode you have declared finished has no business still being queued to play, and leaving it
     * there is how a queue fills up with things the user has already dismissed. When the episode is
     * the one loaded, dropping it from the queue is also what advances to the next.
     *
     * The position is reset either way, which is what makes *unplayed* mean "never started" rather
     * than "finished, but pretend otherwise": the show page's filter, the library's badge and the
     * progress hairline all read it, and all three would disagree with the mark otherwise.
     *
     * @param episodeId the episode to mark.
     * @param isPlayed true to mark it finished, false to put it back to the start.
     */
    suspend fun setPlayed(episodeId: String, isPlayed: Boolean) {
        playbackRepository.setPlayed(episodeId, isPlayed)
        if (isPlayed) removeFromQueue(episodeId)
    }

    /** Removes an episode from both the live player queue and the durable one. */
    suspend fun removeFromQueue(episodeId: String) {
        connection.removeFromQueue(episodeId)
        playbackRepository.dequeue(episodeId)
    }

    /**
     * Puts a removed episode back where it was — the undo of [removeFromQueue].
     *
     * [orderedIds] is the whole queue as it stood *before* the removal, which serves as both the
     * position to insert at and the durable order to write back. Taking the arrangement rather than
     * an index is what makes the undo survive the queue having moved on in the meantime: the player
     * clamps an index it can no longer honour, and the durable write is the arrangement the user is
     * asking to get back either way.
     *
     * @param episodeId the episode to restore.
     * @param orderedIds the queue as it was, first to play first; must contain [episodeId].
     * @return true if the episode was found and handed to the player.
     */
    suspend fun restoreToQueue(episodeId: String, orderedIds: List<String>): Boolean {
        val index = orderedIds.indexOf(episodeId)
        if (index < 0) return false
        val episode = playbackRepository.playableEpisode(episodeId) ?: return false
        connection.insertInQueue(episode, index)
        playbackRepository.reorderQueue(orderedIds)
        return true
    }

    /**
     * Puts back a run of episodes taken out of the queue — the undo of a swipe.
     *
     * Restored oldest-position-first, because [restoreToQueue] inserts each one at its index in
     * [orderedIds] and an index is only correct once everything before it is already back.
     *
     * @param episodeIds the entries to restore, in queue order.
     * @param orderedIds the whole queue as it stood before the clear.
     */
    suspend fun restoreAllToQueue(episodeIds: List<String>, orderedIds: List<String>) {
        episodeIds.forEach { restoreToQueue(it, orderedIds) }
    }

    /**
     * Applies a drag-to-reorder to both the live player queue and the durable one.
     *
     * The two indices are the player's, not a list position on screen: what the queue screen shows
     * is the episodes *after* the one playing, so its own indices are offset, and translating them
     * is the caller's job — see `PlayerViewModel.moveInUpNext`, which does it by episode id rather
     * than by arithmetic on the offset.
     *
     * [orderedIds] is the whole queue as it should end up, which is what makes the durable write
     * independent of whether the player accepted the move; see [playNext] on why both are written.
     *
     * @param fromIndex the moved episode's current index in the player's queue.
     * @param toIndex the index it should occupy afterwards.
     * @param orderedIds the queue's new order, first to play first.
     */
    suspend fun moveInQueue(fromIndex: Int, toIndex: Int, orderedIds: List<String>) {
        connection.moveInQueue(fromIndex, toIndex)
        playbackRepository.reorderQueue(orderedIds)
    }

    /**
     * Stops playback and empties the queue — what dismissing the player bar and the queue screen's
     * *Clear queue* both do.
     *
     * The durable queue is emptied here as well as by the service's own listener, which mirrors the
     * player's timeline into storage. The listener alone is not enough: it only writes when the
     * timeline *changes*, and the queue screen can offer *Clear queue* while the player holds
     * nothing — the service was stopped and the process kept, or the controller has not bound yet.
     * Clearing an empty player changes no timeline, so without this write the screen would announce
     * a cleared queue and go on listing every episode in it. Both writes are "empty", so they cannot
     * disagree about order.
     */
    suspend fun dismiss() {
        connection.stop()
        playbackRepository.reorderQueue(emptyList())
    }

    /**
     * Puts back a queue that [dismiss] emptied — the undo of clearing the queue. Closing the player
     * empties it the same way and offers nothing back.
     *
     * Takes the whole arrangement rather than an index for the same reason [restoreToQueue] does:
     * it is the only description of the queue that survives it having been thrown away. Episodes
     * the database no longer holds are dropped rather than failing the restore, so an undo tapped
     * after a show was removed still brings back everything that is left.
     *
     * Restored paused, at the position the user dismissed at. Undoing is "put that back", not
     * "start playing again"; a snackbar action that made noise would be a surprise.
     *
     * @param orderedIds the queue as it stood, first to play first.
     * @param startEpisodeId the episode that was loaded, which is where the player resumes.
     * @param positionMs how far into it playback had reached.
     * @return true if anything was restored.
     */
    suspend fun restoreDismissed(
        orderedIds: List<String>,
        startEpisodeId: String?,
        positionMs: Long,
    ): Boolean {
        val episodes = orderedIds.mapNotNull { playbackRepository.playableEpisode(it) }
        if (episodes.isEmpty()) return false

        val startIndex = episodes
            .indexOfFirst { it.episode.id == startEpisodeId }
            .coerceAtLeast(0)

        connection.setQueue(
            episodes = episodes,
            startIndex = startIndex,
            startPositionMs = positionMs,
            playWhenReady = false,
        )
        playbackRepository.reorderQueue(episodes.map { it.episode.id })
        return true
    }

    /**
     * Carries on with whatever the app would carry on with — what the launcher's *Resume* shortcut
     * and the widget's button ask for.
     *
     * The service puts the persisted queue back by itself when it starts, so there is nothing to
     * restore here and no second answer to "the last episode": this waits for that restore, and then
     * the player is simply told to play. The wait is what matters on a cold start, where the player
     * is empty for as long as the database read takes and a play sent into it would do nothing.
     *
     * @return true if there was something to resume; false when the queue is empty and nothing is
     *   loaded, which is the caller's cue to open the app rather than an empty player.
     */
    suspend fun resume(): Boolean {
        connection.awaitRestored()
        if (connection.currentState().episodeId == null) return false
        connection.play()
        return true
    }
}
