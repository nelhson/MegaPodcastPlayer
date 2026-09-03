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
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * State rendered by the downloads screen.
 *
 * @property downloads every episode the download stack is tracking: completed, transferring,
 *   waiting and failed. In the order the user dragged them into, and failures first before they
 *   have dragged anything; see [DownloadsViewModel.move].
 * @property completedCount how many of [downloads] are actually on the device. Counted separately
 *   because the storage summary answers "what is this costing me", and a transfer that is half done
 *   or has failed is not yet costing anything worth reporting.
 * @property totalBytes what the completed episodes occupy, summed from the per-episode counters
 *   Media3 writes back — so the figure needs no separate read and can never lag the list it labels.
 * @property freeBytes what is left on the volume the downloads are written to, so the screen can
 *   draw what is stored against what is still available. Zero until the first read comes back, and
 *   zero if the read fails, in which case the bar shows only the stored share.
 * @property unmeteredOnly whether downloads wait for Wi-Fi, which is what lets a waiting row say
 *   why it is waiting rather than just that it is.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a pull-to-refresh is re-reading the storage figures; drives
 *   the gesture's own spinner, which is the only feedback it has.
 * @property message a one-off outcome for the snackbar; cleared via
 *   [DownloadsViewModel.onMessageShown].
 */
data class DownloadsUiState(
    val downloads: List<EpisodeWithShow> = emptyList(),
    val completedCount: Int = 0,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val unmeteredOnly: Boolean = false,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
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
     * A failed download was asked for again.
     *
     * Confirmed rather than left silent because the row it came from does not visibly change on the
     * spot — the retry may sit waiting for Wi-Fi, in which case a tap with no feedback reads as a
     * tap that missed.
     *
     * @property title the episode's title.
     * @property waitingForWifi whether the retry is waiting for an unmetered network.
     */
    data class RetryQueued(val title: String, val waitingForWifi: Boolean) : DownloadsMessage

    /**
     * One episode was added to the end of the queue.
     *
     * Confirmed because the row it came from does not change: the queue is a different tab, so
     * without a word here a tap on the queue button is a tap with no visible effect at all.
     *
     * @property title the episode's title.
     */
    data class Queued(val title: String) : DownloadsMessage

    /**
     * An episode could not be played.
     *
     * Only reachable when the show is removed between the list rendering and the tap landing.
     */
    data object EpisodeUnavailable : DownloadsMessage
}

/**
 * Drives the downloads screen.
 *
 * Owns no list of its own: the tracked set is a query over the episode table, which Media3's
 * download events are mirrored into. That is also what makes live progress free — a transfer
 * advancing writes its percentage back to Room and the row redraws, with nothing to poll here.
 *
 * @property downloadRepository the tracked episodes and the operations that retry and remove them.
 * @property episodePlayer starts playback and edits the queue from an episode id.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val episodePlayer: EpisodePlayer,
) : ViewModel() {

    private val transientState = MutableStateFlow<DownloadsMessage?>(null)

    /**
     * Free space, sampled rather than observed.
     *
     * There is no flow to watch, and re-reading it on every emission of the download list would
     * mean a disk stat several times a second while a transfer runs, for a figure that moves by a
     * megabyte at a time. Read once on arrival and again after anything that frees space.
     */
    private val freeBytes = MutableStateFlow(0L)

    /** True while a pull-to-refresh is in flight; see [refresh]. */
    private val refreshing = MutableStateFlow(false)

    init {
        refreshFreeBytes()
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadRepository.observeDownloads(),
        downloadRepository.observeDownloadSettings(),
        freeBytes,
        refreshing,
        transientState,
    ) { downloads, settings, free, isRefreshing, message ->
        val completed = downloads.filter { it.episode.downloadState == DownloadState.COMPLETED }
        DownloadsUiState(
            downloads = downloads,
            completedCount = completed.size,
            // Only the finished episodes: a partial transfer's bytes are on disk but are not
            // storage the user can act on, and counting them would make the figure jump about
            // while a download runs.
            totalBytes = completed.sumOf { it.episode.downloadedBytes },
            freeBytes = free,
            unmeteredOnly = settings.unmeteredOnly,
            isLoading = false,
            isRefreshing = isRefreshing,
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
     * Re-reads the storage figures, as the pull-to-refresh gesture.
     *
     * There is nothing to fetch here — the list is a live query, and a transfer's progress writes
     * itself back — so what the gesture actually answers is the question the card at the top asks:
     * how much room is left. That figure is sampled rather than observed (see [freeBytes]), so it
     * is the one thing on this screen that can be stale, and the one thing worth a gesture.
     *
     * A second pull while one is running is ignored rather than queued: it would read the same
     * number again.
     */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                freeBytes.value = downloadRepository.freeBytes()
            } finally {
                // Also on cancellation: the view model dying takes the spinner with it, but a
                // flag left true would otherwise outlive a cancelled read within the same screen.
                refreshing.value = false
            }
        }
    }

    /**
     * Asks for a failed download again.
     *
     * [DownloadRepository.download] is documented as safe to call for an episode that previously
     * failed, so a retry is the same request as the first attempt — there is nothing to clean up
     * first.
     *
     * @param episodeId the episode to try again.
     */
    fun retry(episodeId: String) {
        val title = titleOf(episodeId) ?: return
        viewModelScope.launch {
            transientState.value = if (downloadRepository.download(episodeId)) {
                DownloadsMessage.RetryQueued(
                    title = title,
                    waitingForWifi = uiState.value.unmeteredOnly,
                )
            } else {
                DownloadsMessage.EpisodeUnavailable
            }
        }
    }

    /**
     * Deletes one episode's audio, or cancels the transfer if it has not finished.
     *
     * One handler for both, because `removeDownload` already cancels an in-flight download
     * before clearing it — the button means "stop holding this" whichever state the row is in.
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
            refreshFreeBytes()
        }
    }

    /**
     * Adds an episode to the end of the play queue.
     *
     * What the queue button on every row does, whatever state the download is in. An episode still
     * transferring is deliberately allowed: the queue is a list of what to listen to next, and by
     * the time it comes round the file will be there — refusing it would mean the user had to come
     * back to this screen and remember.
     *
     * @param episodeId the episode to queue.
     */
    fun addToQueue(episodeId: String) {
        val title = titleOf(episodeId) ?: return
        viewModelScope.launch {
            transientState.value = if (episodePlayer.addToQueue(episodeId)) {
                DownloadsMessage.Queued(title)
            } else {
                DownloadsMessage.EpisodeUnavailable
            }
        }
    }

    /**
     * Applies a completed drag-to-reorder.
     *
     * The whole arrangement is written rather than the two positions, because that is what the
     * stored order is — see [DownloadRepository.reorderDownloads]. [visibleIds] is the list as it
     * stood *before* the gesture, taken from the screen rather than re-read here: a transfer that
     * finished mid-drag would otherwise re-sort the list under the indices and move the wrong row.
     *
     * @param visibleIds the downloads on screen, in the order they were in before the drag.
     * @param from the row's position among those, before the drag.
     * @param to where it was dropped.
     */
    fun move(visibleIds: List<String>, from: Int, to: Int) {
        if (from !in visibleIds.indices || to !in visibleIds.indices || from == to) return
        val reordered = visibleIds.toMutableList().apply { add(to, removeAt(from)) }
        viewModelScope.launch { downloadRepository.reorderDownloads(reordered) }
    }

    /** Clears the current [DownloadsUiState.message] once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = null
    }

    /** Re-reads free space; called on arrival and after anything that gives space back. */
    private fun refreshFreeBytes() {
        viewModelScope.launch { freeBytes.value = downloadRepository.freeBytes() }
    }

    /** The title of a listed episode, or null if it is no longer in the list. */
    private fun titleOf(episodeId: String): String? =
        uiState.value.downloads.firstOrNull { it.episode.id == episodeId }?.episode?.title

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
