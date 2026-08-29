package md.borisveriga.bpodcat.feature.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * @property isRefreshing true while this show's feed is being re-fetched.
 * @property message a one-off refresh outcome for the snackbar.
 */
data class PodcastDetailUiState(
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
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
     * An episode could not be played.
     *
     * The only way this happens is the show being removed between the list rendering and the tap
     * landing, so the message says that rather than blaming the network.
     */
    data object EpisodeUnavailable : PodcastDetailMessage

    /**
     * An episode was added to the end of the queue.
     *
     * @property title the episode's title, so the confirmation names what was queued.
     */
    data class Queued(val title: String) : PodcastDetailMessage

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
        viewModelScope.launch { repository.markEpisodesSeen(podcastId) }
    }

    /** Re-fetches this show's feed. Never downloads audio. */
    fun refresh() {
        if (transientState.value.isRefreshing) return
        transientState.value = TransientState(isRefreshing = true)
        viewModelScope.launch {
            val result = repository.refresh(podcastId)
            transientState.value = TransientState(
                isRefreshing = false,
                message = result.fold(
                    onSuccess = { discovered ->
                        // A 304 is reported as a negative sentinel by the repository; from the
                        // user's point of view it simply means nothing new.
                        PodcastDetailMessage.Refreshed(discovered.coerceAtLeast(0))
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
     * Appends an episode to the end of the queue without interrupting what is playing.
     *
     * @param episodeId the episode to queue.
     */
    fun addToQueue(episodeId: String) {
        viewModelScope.launch {
            val title = uiState.value.episodes.firstOrNull { it.id == episodeId }?.title
            val queued = episodePlayer.addToQueue(episodeId)
            transientState.value = transientState.value.copy(
                message = if (queued && title != null) {
                    PodcastDetailMessage.Queued(title)
                } else {
                    PodcastDetailMessage.EpisodeUnavailable
                },
            )
        }
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

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    private data class TransientState(
        val isRefreshing: Boolean = false,
        val message: PodcastDetailMessage? = null,
    )

    companion object {
        /** Name of the navigation argument carrying the podcast id. */
        const val PODCAST_ID_ARG = "podcastId"

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
