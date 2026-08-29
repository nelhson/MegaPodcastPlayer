package md.borisveriga.bpodcat.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.media.PlayableEpisode
import md.borisveriga.bpodcat.core.media.PlaybackConnection
import md.borisveriga.bpodcat.core.media.PlaybackState
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * State rendered by the mini player and the now-playing screen.
 *
 * @property playback what the player is doing right now.
 * @property settings the user's speed and skip preferences.
 * @property queue the durable "up next" queue, in play order, including the episode playing.
 */
data class PlayerUiState(
    val playback: PlaybackState = PlaybackState(),
    val settings: PlaybackSettings = PlaybackSettings(),
    val queue: List<PlayableEpisode> = emptyList(),
) {
    /** True when there is nothing to show — the mini player should not be on screen at all. */
    val isIdle: Boolean get() = playback.isIdle

    /** The queue entries after the one playing, which is what "Up next" lists. */
    val upNext: List<PlayableEpisode>
        get() {
            val currentIndex = queue.indexOfFirst { it.episode.id == playback.episodeId }
            return if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue
        }
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

    val uiState: StateFlow<PlayerUiState> = combine(
        connection.playbackState,
        playbackRepository.observePlaybackSettings(),
        playbackRepository.observeQueue(),
    ) { playback, settings, queue ->
        PlayerUiState(playback = playback, settings = settings, queue = queue)
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

    /** Removes an episode from the queue, in the player and in storage. */
    fun removeFromQueue(episodeId: String) {
        viewModelScope.launch { episodePlayer.removeFromQueue(episodeId) }
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

    private companion object {
        /** Keeps the controller attached across a rotation or a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
