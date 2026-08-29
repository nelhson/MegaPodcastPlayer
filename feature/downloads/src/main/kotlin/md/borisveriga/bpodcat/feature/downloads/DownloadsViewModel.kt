package md.borisveriga.bpodcat.feature.downloads

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
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.model.DownloadedEpisode

/**
 * State rendered by the downloads screen.
 *
 * @property downloads every episode stored on the device, newest first.
 * @property totalBytes what those episodes occupy, summed from the per-episode counters Media3
 *   writes back — so the figure needs no separate read and can never lag the list it labels.
 * @property isLoading true until the first database emission arrives.
 * @property message a one-off outcome for the snackbar; cleared via
 *   [DownloadsViewModel.onMessageShown].
 */
data class DownloadsUiState(
    val downloads: List<DownloadedEpisode> = emptyList(),
    val totalBytes: Long = 0L,
    val isLoading: Boolean = true,
    val message: DownloadsMessage? = null,
)

/**
 * A one-off outcome to show the user.
 *
 * State rather than an event channel, for the same reason the library screen does it: it has to
 * survive the fold/unfold transition that recreates the activity.
 */
sealed interface DownloadsMessage {

    /**
     * One episode's audio was deleted.
     *
     * @property title the episode's title.
     */
    data class Removed(val title: String) : DownloadsMessage

    /**
     * Every download was deleted.
     *
     * @property count how many episodes went, so the confirmation is specific about what the tap
     *   actually did.
     */
    data class RemovedAll(val count: Int) : DownloadsMessage

    /**
     * An episode was added to the end of the queue.
     *
     * @property title the episode's title.
     */
    data class Queued(val title: String) : DownloadsMessage

    /**
     * An episode could not be played or queued.
     *
     * Only reachable when the show is removed between the list rendering and the tap landing.
     */
    data object EpisodeUnavailable : DownloadsMessage
}

/**
 * Drives the downloads screen.
 *
 * Owns no list of its own: the downloaded set is a query over the episode table, which Media3's
 * download events are mirrored into, so removing audio here shows up without this class being told.
 *
 * @property downloadRepository the downloaded episodes and the operations that remove them.
 * @property episodePlayer starts playback and edits the queue from an episode id.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val episodePlayer: EpisodePlayer,
) : ViewModel() {

    private val transientState = MutableStateFlow<DownloadsMessage?>(null)

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.observeDownloads(),
        transientState,
    ) { downloads, message ->
        DownloadsUiState(
            downloads = downloads,
            totalBytes = downloads.sumOf { it.episode.downloadedBytes },
            isLoading = false,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        // Matches the other tabs: keep collecting briefly so switching tabs and back does not
        // re-query the database.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DownloadsUiState(),
    )

    /**
     * Plays a downloaded episode, resuming from wherever it was left.
     *
     * @param episodeId the episode to play.
     * @param onPlaying invoked once playback has been handed to the player, so the caller can open
     *   the full player. Not called when the episode has gone.
     */
    fun play(episodeId: String, onPlaying: () -> Unit) {
        viewModelScope.launch {
            if (episodePlayer.play(episodeId)) {
                onPlaying()
            } else {
                transientState.value = DownloadsMessage.EpisodeUnavailable
            }
        }
    }

    /**
     * Appends an episode to the end of the queue without interrupting what is playing.
     *
     * @param episodeId the episode to queue.
     */
    fun addToQueue(episodeId: String) {
        viewModelScope.launch {
            val title = titleOf(episodeId)
            val queued = episodePlayer.addToQueue(episodeId)
            transientState.value = if (queued && title != null) {
                DownloadsMessage.Queued(title)
            } else {
                DownloadsMessage.EpisodeUnavailable
            }
        }
    }

    /**
     * Deletes one episode's audio, freeing its storage.
     *
     * The row disappears from the list on its own: the removal writes the episode's download state
     * back to Room, which the observed query re-runs against.
     *
     * @param episodeId the episode to remove.
     */
    fun remove(episodeId: String) {
        val title = titleOf(episodeId) ?: return
        viewModelScope.launch {
            downloadRepository.removeDownload(episodeId)
            transientState.value = DownloadsMessage.Removed(title)
        }
    }

    /**
     * Deletes every download.
     *
     * The count is read before the removal, because afterwards there is nothing left to count.
     */
    fun removeAll() {
        val count = uiState.value.downloads.size
        if (count == 0) return
        viewModelScope.launch {
            downloadRepository.removeAllDownloads()
            transientState.value = DownloadsMessage.RemovedAll(count)
        }
    }

    /** Clears the current [DownloadsUiState.message] once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = null
    }

    /** The title of a listed episode, or null if it is no longer in the list. */
    private fun titleOf(episodeId: String): String? =
        uiState.value.downloads.firstOrNull { it.episode.id == episodeId }?.episode?.title

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
