package md.borisveriga.megapodcastplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * State rendered by the mini player, the now-playing screen and the queue.
 *
 * @property playback what the player is doing right now.
 * @property settings the user's speed and skip preferences.
 * @property queue the durable "up next" queue, in play order, including the episode playing.
 * @property lastPlayedEpisodeId the episode the player last loaded, as stored. Only read while
 *   [playback] has no episode of its own; see [currentEpisodeId].
 * @property message a one-off outcome for the queue screen's snackbar; cleared via
 *   [PlayerViewModel.onQueueMessageShown]. The player surfaces do not read it — they share this
 *   view model because they share the queue, not because they share every field of it.
 */
data class PlayerUiState(
    val playback: PlaybackState = PlaybackState(),
    val settings: PlaybackSettings = PlaybackSettings(),
    val queue: List<PlayableEpisode> = emptyList(),
    val lastPlayedEpisodeId: String? = null,
    val message: QueueMessage? = null,
) {
    /** True when there is nothing to show — the mini player should not be on screen at all. */
    val isIdle: Boolean get() = playback.isIdle

    /**
     * The episode the queue's head belongs to: what the player has loaded, or failing that what it
     * loaded last.
     *
     * The fallback is not cosmetic. The durable queue *includes* the episode playing — the service
     * mirrors its whole timeline into it — so telling that entry apart from the ones waiting behind
     * it is the only thing that keeps it out of "up next". The player's own answer is null for as
     * long as it takes a `MediaController` to bind, and null again after the service has been
     * killed, and in both of those windows the queue screen would otherwise list the loaded episode
     * as though it were queued: one row, in a queue the user has emptied.
     */
    val currentEpisodeId: String? get() = playback.episodeId ?: lastPlayedEpisodeId

    /** The queue entries after the one playing, which is what "Up next" lists. */
    val upNext: List<PlayableEpisode>
        get() {
            val currentIndex = queue.indexOfFirst { it.episode.id == currentEpisodeId }
            return if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue
        }
}

/**
 * Something a queue gesture did, to be shown once in a snackbar.
 *
 * Modelled as state rather than an event channel so it survives configuration changes and the
 * unfold/fold transition on the Fold 7. Both cases are reversible, and both say so: the message
 * names what happened, and [PlayerViewModel.undoQueueChange] is what puts it back. The undo payload
 * itself is not here — it is the view model's, so that the UI state stays data a test can compare.
 *
 * @property episodeTitle the affected episode, named back to the user so a snackbar arriving after
 *   two quick swipes is not ambiguous.
 */
sealed interface QueueMessage {

    val episodeTitle: String

    /** An episode was taken out of the queue by a full swipe. */
    data class Removed(override val episodeTitle: String) : QueueMessage

    /** An episode was marked played, which also took it out of the queue. */
    data class MarkedPlayed(override val episodeTitle: String) : QueueMessage
}

/**
 * Drives both the mini player and the full now-playing screen.
 *
 * Both surfaces show the same player, so they share one view model rather than two that would have
 * to be kept in step. Nothing here holds playback state of its own: the single source of truth is
 * the [PlaybackConnection], which reflects the service.
 *
 * @property connection the handle on the playback service.
 * @property playbackRepository the durable queue and playback preferences.
 * @property episodePlayer resolves episode ids into something the player can accept.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val episodePlayer: EpisodePlayer,
) : ViewModel() {

    /**
     * The undo the last queue gesture left behind, or null.
     *
     * Held here rather than in [PlayerUiState] so the state stays comparable data. Overwritten
     * rather than stacked: a snackbar shows one message at a time, so only the newest gesture is
     * ever reachable, and keeping the older ones would only let an undo fire for a message that has
     * already gone.
     */
    private var pendingUndo: QueueUndo? = null

    private val messageState = MutableStateFlow<QueueMessage?>(null)

    val uiState: StateFlow<PlayerUiState> = combine(
        connection.playbackState,
        playbackRepository.observePlaybackSettings(),
        playbackRepository.observeQueue(),
        playbackRepository.observeLastPlayedEpisodeId(),
        messageState,
    ) { playback, settings, queue, lastPlayedEpisodeId, message ->
        PlayerUiState(
            playback = playback,
            settings = settings,
            queue = queue,
            lastPlayedEpisodeId = lastPlayedEpisodeId,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlayerUiState(),
    )

    init {
        // A cold start finds an empty player; put the user's queue back so the mini player shows
        // what they were listening to. Idempotent, so several screens asking costs nothing.
        viewModelScope.launch { episodePlayer.restoreQueue() }
    }

    /** Starts or pauses playback. */
    fun togglePlayPause() {
        viewModelScope.launch { connection.togglePlayPause() }
    }

    /**
     * Seeks within the current episode.
     *
     * @param positionMs the absolute position to seek to.
     */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch { connection.seekTo(positionMs) }
    }

    /** Jumps forward by the user's configured interval. */
    fun skipForward() {
        viewModelScope.launch { connection.skipForward(uiState.value.settings.skipForwardMs) }
    }

    /** Jumps back by the user's configured interval. */
    fun skipBack() {
        viewModelScope.launch { connection.skipBack(uiState.value.settings.skipBackMs) }
    }

    /** Moves to the next queued episode. */
    fun skipToNext() {
        viewModelScope.launch { connection.skipToNext() }
    }

    /** Restarts the episode, or moves to the previous one if already at the start. */
    fun skipToPrevious() {
        viewModelScope.launch { connection.skipToPrevious() }
    }

    /**
     * Advances to the next playback speed and remembers it.
     *
     * The preference is written as well as applied so the speed survives the service being killed.
     */
    fun cycleSpeed() {
        val next = uiState.value.settings.nextSpeed()
        viewModelScope.launch {
            playbackRepository.setSpeed(next)
            connection.setSpeed(next)
        }
    }

    /** Plays a queued episode immediately. */
    fun playQueued(episodeId: String) {
        viewModelScope.launch { episodePlayer.play(episodeId) }
    }

    /**
     * Removes an episode from the queue, in the player and in storage, and offers it back.
     *
     * What a full right-to-left swipe on a queue row commits, and what the row's "remove"
     * accessibility action does. The queue is captured *before* the removal, because that
     * arrangement is the only description of where the episode belongs that survives it.
     *
     * @param episodeId the episode to drop.
     */
    fun removeFromQueue(episodeId: String) {
        val entry = uiState.value.queue.firstOrNull { it.episode.id == episodeId } ?: return
        val orderedIds = uiState.value.queue.map { it.episode.id }

        viewModelScope.launch {
            episodePlayer.removeFromQueue(episodeId)
            pendingUndo = QueueUndo(episodeId = episodeId, orderedIds = orderedIds)
            messageState.value = QueueMessage.Removed(entry.episode.title)
        }
    }

    /**
     * Marks a queued episode played, which also takes it out of the queue.
     *
     * What a short swipe's "mark as played" button does. The removal is not a side effect worth
     * hiding: a finished episode has no business sitting in "up next", and leaving it there would
     * mean the gesture had to be followed by a second one every time.
     *
     * The position is read before the mark so an undo can put the user back where they were; see
     * [PlaybackRepository.setPlayed].
     *
     * @param episodeId the episode to mark.
     */
    fun markQueuedPlayed(episodeId: String) {
        val entry = uiState.value.queue.firstOrNull { it.episode.id == episodeId } ?: return
        val orderedIds = uiState.value.queue.map { it.episode.id }

        viewModelScope.launch {
            playbackRepository.setPlayed(episodeId, isPlayed = true)
            episodePlayer.removeFromQueue(episodeId)
            pendingUndo = QueueUndo(
                episodeId = episodeId,
                orderedIds = orderedIds,
                restorePositionMs = entry.episode.positionMs,
                wasMarkedPlayed = true,
            )
            messageState.value = QueueMessage.MarkedPlayed(entry.episode.title)
        }
    }

    /**
     * Reverses the last queue gesture.
     *
     * Consumed rather than kept: an undo that could be tapped twice would insert the episode once
     * and then attempt it again against a queue that already holds it.
     */
    fun undoQueueChange() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        messageState.value = null

        viewModelScope.launch {
            if (undo.wasMarkedPlayed) {
                playbackRepository.setPlayed(
                    episodeId = undo.episodeId,
                    isPlayed = false,
                    positionMs = undo.restorePositionMs,
                )
            }
            episodePlayer.restoreToQueue(undo.episodeId, undo.orderedIds)
        }
    }

    /** Clears the current [PlayerUiState.message] once its snackbar has been shown. */
    fun onQueueMessageShown() {
        messageState.value = null
        // The message and its undo go together: an undo left armed past the snackbar that offered
        // it would fire on the *next* one, restoring an episode the user never asked about.
        pendingUndo = null
    }

    /**
     * Applies a drag-to-reorder of the "up next" list.
     *
     * Both arguments are positions in [PlayerUiState.upNext] — what the queue screen actually
     * draws — and both are translated to the player's own indices here rather than in the
     * composable. That translation is the whole reason this method exists: `upNext` is the queue
     * *after* the episode playing, so a list index is never a player index, and handing a player a
     * `LazyColumn` index would reorder an episode the user never touched. Matching by id also means
     * a queue that has drifted out of step with the player does nothing rather than something
     * wrong.
     *
     * @param fromIndex the dragged episode's position in `upNext`.
     * @param toIndex the position it was dropped on.
     */
    fun moveInUpNext(fromIndex: Int, toIndex: Int) {
        val state = uiState.value
        val upNext = state.upNext
        val movedId = upNext.getOrNull(fromIndex)?.episode?.id ?: return
        val targetId = upNext.getOrNull(toIndex)?.episode?.id ?: return

        val playerQueue = state.playback.queueEpisodeIds
        val playerFrom = playerQueue.indexOf(movedId)
        val playerTo = playerQueue.indexOf(targetId)
        if (playerFrom < 0 || playerTo < 0) return

        val orderedIds = state.queue.map { it.episode.id }.movedTo(movedId, targetId) ?: return

        viewModelScope.launch { episodePlayer.moveInQueue(playerFrom, playerTo, orderedIds) }
    }

    /** Marks the current episode played, which also drops it from the queue and skips on. */
    fun markCurrentPlayed() {
        val episodeId = uiState.value.playback.episodeId ?: return
        viewModelScope.launch {
            playbackRepository.setPlayed(episodeId, isPlayed = true)
            episodePlayer.removeFromQueue(episodeId)
        }
    }

    /** Clears the playback error once its snackbar has been shown. */
    fun onErrorShown() {
        connection.clearError()
    }

    /**
     * Everything needed to reverse one queue gesture.
     *
     * @property episodeId the episode that left the queue.
     * @property orderedIds the queue as it stood before it did, which is where it goes back.
     * @property restorePositionMs the position to resume from; only read when [wasMarkedPlayed].
     * @property wasMarkedPlayed true when the gesture also set the played flag, and the undo has to
     *   clear it again. False for a plain removal, which never touched it.
     */
    private data class QueueUndo(
        val episodeId: String,
        val orderedIds: List<String>,
        val restorePositionMs: Long = 0L,
        val wasMarkedPlayed: Boolean = false,
    )

    private companion object {
        /** Keeps the controller attached across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
