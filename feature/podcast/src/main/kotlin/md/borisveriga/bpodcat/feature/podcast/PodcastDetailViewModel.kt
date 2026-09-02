package md.borisveriga.bpodcat.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.data.repository.PlaybackRepository
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast

/**
 * State rendered by the podcast detail screen.
 *
 * @property podcast the show; null while loading or after it has been removed.
 * @property episodes its episodes, newest first.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a pull-to-refresh is re-fetching this show's feed; ends in a
 *   snackbar either way.
 * @property isAutoRefreshing true while the refresh that runs on entering the screen is in flight.
 *   Separate from [isRefreshing] because it renders as a thin progress line and says nothing when
 *   it finishes.
 * @property isRebuilding true while the episode list is being deleted and imported again. Its own
 *   flag rather than a third kind of refresh, because it is the one operation on this screen that
 *   destroys what the user is looking at, and it should say so while it runs.
 * @property message a one-off refresh outcome for the snackbar.
 */
data class PodcastDetailUiState(
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val isRebuilding: Boolean = false,
    val message: PodcastDetailMessage? = null,
)

/** A one-off outcome to show the user. */
sealed interface PodcastDetailMessage {

    /**
     * A refresh completed.
     *
     * @property newEpisodeCount episodes discovered; zero is a perfectly good answer.
     */
    data class Refreshed(val newEpisodeCount: Int) : PodcastDetailMessage

    /**
     * A refresh failed.
     *
     * @property reason short explanation for the snackbar.
     */
    data class RefreshFailed(val reason: String) : PodcastDetailMessage

    /**
     * One episode was taken off the show's list.
     *
     * Worth a snackbar where marking one played is not: the row vanishes either way, but only one
     * of the two is a decision the user might want to see confirmed in words.
     *
     * @property title the episode that went.
     */
    data class EpisodeRemoved(val title: String) : PodcastDetailMessage

    /**
     * The episode list was deleted and imported again.
     *
     * Separate from [Refreshed] because the number means something different: a refresh reports
     * what it *found*, a rebuild reports how much of the show there now is — which is the one
     * figure that says whether the rebuild fixed anything.
     *
     * @property episodeCount episodes the feed yielded.
     */
    data class Rebuilt(val episodeCount: Int) : PodcastDetailMessage

    /**
     * A rebuild failed, leaving the existing list untouched.
     *
     * @property reason short explanation for the snackbar.
     */
    data class RebuildFailed(val reason: String) : PodcastDetailMessage

    /**
     * An episode could not be played.
     *
     * The only way this happens is the show being removed between the list rendering and the tap
     * landing, so the message says that rather than blaming the network.
     */
    data object EpisodeUnavailable : PodcastDetailMessage

    /**
     * An episode was queued for download.
     *
     * Worth confirming because the download itself may not start for a while — "Wi-Fi only" is on
     * by default, so a tap on mobile data appears to do nothing at all.
     *
     * @property title the episode's title.
     * @property waitingForWifi whether the download is waiting for an unmetered network.
     */
    data class DownloadQueued(val title: String, val waitingForWifi: Boolean) :
        PodcastDetailMessage

    /**
     * A downloaded episode was removed from the device.
     *
     * @property title the episode's title.
     */
    data class DownloadRemoved(val title: String) : PodcastDetailMessage
}

/**
 * Drives the podcast detail screen.
 *
 * @property repository the single source of podcast truth.
 * @property episodePlayer starts playback and edits the queue from an episode id.
 * @property downloadRepository requests and removes downloads.
 * @param savedStateHandle carries the `podcastId` navigation argument.
 */
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val episodePlayer: EpisodePlayer,
    private val downloadRepository: DownloadRepository,
    private val playbackRepository: PlaybackRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The show being displayed, taken from the navigation route. */
    private val podcastId: String = checkNotNull(savedStateHandle[PODCAST_ID_ARG]) {
        "PodcastDetailViewModel requires a '$PODCAST_ID_ARG' navigation argument"
    }

    private val transientState = MutableStateFlow(TransientState())

    val uiState: StateFlow<PodcastDetailUiState> = combine(
        repository.observePodcast(podcastId),
        repository.observeEpisodes(podcastId),
        transientState,
    ) { podcast, episodes, transient ->
        PodcastDetailUiState(
            podcast = podcast,
            episodes = episodes,
            isLoading = false,
            isRefreshing = transient.isRefreshing,
            isAutoRefreshing = transient.isAutoRefreshing,
            isRebuilding = transient.isRebuilding,
            message = transient.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PodcastDetailUiState(),
    )

    init {
        // Opening the episode list is what "seeing" the new episodes means, so the badges clear
        // here rather than on scroll.
        //
        // Ordering against the automatic refresh matters and is deliberate: this runs first, so an
        // episode that arrives while the user is looking at the list keeps its badge and stands out
        // as the thing that just appeared. Clearing the flags afterwards would hide exactly the
        // episode the refresh was worth doing for.
        viewModelScope.launch { repository.markEpisodesSeen(podcastId) }
    }

    /**
     * Brings this show up to date on entering the screen, quietly.
     *
     * Unlike the library's equivalent this ignores the per-show background-refresh toggle. That
     * toggle declines *bulk* refreshes; opening the episode list is the user pointing at this show
     * in particular, and since there is no manual refresh button any more, honouring the toggle
     * here would leave an opted-out show with no way to ever update.
     *
     * Says nothing when it finishes, success or failure — pulling to refresh is what asks a
     * question and expects an answer.
     */
    fun refreshIfStale() {
        if (transientState.value.isBusy) return
        transientState.value = transientState.value.copy(isAutoRefreshing = true)
        viewModelScope.launch {
            repository.refresh(podcastId, staleAfter = AUTO_REFRESH_STALE_AFTER)
            transientState.value = transientState.value.copy(isAutoRefreshing = false)
        }
    }

    /**
     * Re-fetches this show's feed however recently it was last fetched, and reports what happened.
     *
     * Never downloads audio.
     */
    fun refresh() {
        if (transientState.value.isBusy) return
        transientState.value = TransientState(isRefreshing = true)
        viewModelScope.launch {
            val result = repository.refresh(podcastId)
            transientState.value = TransientState(
                isRefreshing = false,
                message = result.fold(
                    onSuccess = { discovered ->
                        // Zero covers both "the server said nothing changed" and "the feed changed
                        // but gained no episodes"; to the user those are the same answer.
                        PodcastDetailMessage.Refreshed(discovered)
                    },
                    onFailure = { error ->
                        PodcastDetailMessage.RefreshFailed(
                            error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                ),
            )
        }
    }

    /**
     * Deletes this show's episode list and imports the feed again from scratch.
     *
     * The escape hatch for a list a refresh cannot repair — a feed re-issued under new GUIDs, a
     * playlist whose stored order has drifted, an import that only half-worked — where every
     * further refresh merges into the same wrong list. It costs playback progress, played flags
     * and any hand-made order, which is the trade the user is making by choosing it.
     *
     * It also clears the show's downloads. The rows that tracked them do not survive the rebuild,
     * so leaving the audio in place would strand however many gigabytes on the device with nothing
     * left pointing at them. The ids are read *before* the rebuild, because afterwards the rows
     * that named them are gone, and the removal runs only on success — a failed rebuild leaves the
     * old list in place, and those downloads still belong to it.
     */
    fun rebuild() {
        if (transientState.value.isBusy) return
        transientState.value = TransientState(isRebuilding = true)
        val downloadedIds = uiState.value.episodes
            .filter { it.downloadState != DownloadState.NOT_DOWNLOADED }
            .map { it.id }

        viewModelScope.launch {
            val result = repository.rebuild(podcastId)
            if (result.isSuccess) {
                downloadedIds.forEach { downloadRepository.removeDownload(it) }
            }
            transientState.value = TransientState(
                isRebuilding = false,
                message = result.fold(
                    onSuccess = { PodcastDetailMessage.Rebuilt(it) },
                    onFailure = { error ->
                        PodcastDetailMessage.RebuildFailed(
                            error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                ),
            )
        }
    }

    /** Removes the show. The screen should navigate back once [PodcastDetailUiState.podcast] is null. */
    fun removePodcast() {
        viewModelScope.launch { repository.remove(podcastId) }
    }

    /** Enables or disables background refresh for this show. */
    fun setAutoRefresh(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoRefresh(podcastId, enabled) }
    }

    /**
     * Plays an episode, resuming from wherever it was left.
     *
     * @param episodeId the episode to play.
     * @param onPlaying invoked once playback has been handed to the player, so the caller can open
     *   the full player. Not called when the episode has gone.
     */
    fun playEpisode(episodeId: String, onPlaying: () -> Unit) {
        viewModelScope.launch {
            if (episodePlayer.play(episodeId)) {
                onPlaying()
            } else {
                transientState.value = transientState.value.copy(
                    message = PodcastDetailMessage.EpisodeUnavailable,
                )
            }
        }
    }

    /**
     * Applies a completed reorder gesture on a hand-ordered show.
     *
     * The screen may be showing a filtered subset, so two positions in that subset name the wrong
     * episodes in the full list. The translation is to keep the *slots*: whichever positions the
     * visible episodes occupy in the full order stay theirs, and the visible episodes are dealt
     * back into them in their new sequence. Everything hidden stays exactly where it was, which is
     * the only behaviour that does not surprise someone who later clears the filter.
     *
     * @param visibleIds the episode ids on screen, in the order they were in before the drag.
     * @param from the episode's position among those, before the drag.
     * @param to where it was dropped.
     */
    fun moveEpisode(visibleIds: List<String>, from: Int, to: Int) {
        if (from !in visibleIds.indices || to !in visibleIds.indices || from == to) return

        val reordered = visibleIds.toMutableList().apply { add(to, removeAt(from)) }.iterator()
        val visible = visibleIds.toSet()
        val merged = uiState.value.episodes.map { episode ->
            if (episode.id in visible) reordered.next() else episode.id
        }

        viewModelScope.launch { repository.reorderEpisodes(podcastId, merged) }
    }

    /**
     * Downloads an episode, or removes it if it is already on the device.
     *
     * One handler for both because the row shows one button whose meaning depends on the episode's
     * state — which is how every podcast app behaves, and what saves a row from carrying two icons
     * that are each useful half the time.
     *
     * @param episodeId the episode to download or remove.
     */
    fun toggleDownload(episodeId: String) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            when (episode.downloadState) {
                // A failed download is retried rather than cleared: the user tapping the button
                // again plainly means "try that again".
                DownloadState.NOT_DOWNLOADED, DownloadState.FAILED -> {
                    if (downloadRepository.download(episodeId)) {
                        val waitingForWifi =
                            downloadRepository.observeDownloadSettings().first().unmeteredOnly
                        transientState.value = transientState.value.copy(
                            message = PodcastDetailMessage.DownloadQueued(
                                title = episode.title,
                                waitingForWifi = waitingForWifi,
                            ),
                        )
                    } else {
                        transientState.value = transientState.value.copy(
                            message = PodcastDetailMessage.EpisodeUnavailable,
                        )
                    }
                }

                // Tapping a download in progress cancels it; tapping a finished one frees it.
                DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.COMPLETED -> {
                    downloadRepository.removeDownload(episodeId)
                    transientState.value = transientState.value.copy(
                        message = PodcastDetailMessage.DownloadRemoved(episode.title),
                    )
                }
            }
        }
    }

    /**
     * Marks one episode played, and forgets where it was left.
     *
     * @param episodeId the episode to mark.
     */
    fun markPlayed(episodeId: String) {
        viewModelScope.launch { playbackRepository.setPlayed(episodeId, isPlayed = true) }
    }

    /**
     * Takes one episode off this show's list, for good.
     *
     * Its download goes first, because the row is about to stop being reachable and a file nothing
     * can point at is the worst kind of storage to leak. The removal itself is recorded rather than
     * carried out — see `PodcastRepository.hideEpisode` — since deleting the row would only invite
     * the next refresh to insert it again.
     *
     * @param episodeId the episode to remove.
     */
    fun removeEpisode(episodeId: String) {
        val episode = uiState.value.episodes.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            if (episode.downloadState != DownloadState.NOT_DOWNLOADED) {
                downloadRepository.removeDownload(episodeId)
            }
            repository.hideEpisode(episodeId)
            transientState.value = transientState.value.copy(
                message = PodcastDetailMessage.EpisodeRemoved(episode.title),
            )
        }
    }

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    private data class TransientState(
        val isRefreshing: Boolean = false,
        val isAutoRefreshing: Boolean = false,
        val isRebuilding: Boolean = false,
        val message: PodcastDetailMessage? = null,
    ) {
        /**
         * Whether a feed operation of any kind is already running.
         *
         * One guard for all three, so the automatic refresh cannot start on top of a
         * pull-to-refresh and swallow the answer it was about to give, nor the reverse — and so
         * that neither refresh can land its episodes in a list a rebuild is halfway through
         * replacing.
         */
        val isBusy: Boolean get() = isRefreshing || isAutoRefreshing || isRebuilding
    }

    companion object {
        /** Name of the navigation argument carrying the podcast id. */
        const val PODCAST_ID_ARG = "podcastId"

        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How stale this show's feed must be before opening it re-fetches it.
         *
         * Matches the library's window; the two run against the same feeds and disagreeing about
         * what counts as recent would only produce requests neither of them wanted.
         */
        private val AUTO_REFRESH_STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}
