package md.borisveriga.bpodcat.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.data.playback.EpisodePlayer
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * State rendered by the Latest feed.
 *
 * @property groups the feed, bucketed by recency, newest section first.
 * @property continueListening episodes that were started and not finished, most recently published
 *   first. Surfaced separately because resuming something is a different intent from browsing what
 *   is new, and burying a half-heard episode among today's arrivals loses it.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a pull-to-refresh the user asked for is running.
 * @property isAutoRefreshing true while the quiet on-entry refresh is running; drawn as a hairline
 *   rather than a spinner, because the user did not ask for it.
 * @property message a one-off outcome for the snackbar; cleared via [HomeViewModel.onMessageShown].
 */
data class HomeUiState(
    val groups: List<LatestGroup> = emptyList(),
    val continueListening: List<EpisodeWithShow> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val message: HomeMessage? = null,
) {
    /** True when there is nothing at all to show, which is a different screen from a loading one. */
    val isEmpty: Boolean get() = groups.isEmpty() && continueListening.isEmpty()
}

/**
 * A one-off outcome to show the user.
 *
 * Modelled as state rather than an event channel, matching every other screen here: it has to
 * survive the fold/unfold transition that recreates the activity.
 */
sealed interface HomeMessage {

    /**
     * An episode was appended to the queue.
     *
     * @property title the episode's title.
     */
    data class Queued(val title: String) : HomeMessage

    /** A pull-to-refresh finished. */
    data class RefreshFinished(val summary: RefreshSummary) : HomeMessage

    /** The episode was removed from the library between the list rendering and the tap landing. */
    data object EpisodeUnavailable : HomeMessage
}

/**
 * Backs the Latest feed.
 *
 * This screen is the app's new front door, so it inherits the library's job of quietly bringing
 * feeds up to date on entry — the same [AUTO_REFRESH_STALE_AFTER] window and the same
 * `onlyAutoRefreshable` contract, so a show the user switched background refresh off for is still
 * left alone.
 *
 * [Clock] is injected rather than calling `Instant.now()` inside the mapping so that
 * [groupByRecency]'s day boundaries are exercisable from a test at a fixed instant. Everything else
 * follows the house pattern: `combine` of repository flows with a transient state holder, kept warm
 * briefly after the last collector so switching tabs and back does not re-query.
 *
 * @property podcastRepository the feed query and the refresh operations behind it.
 * @property downloadRepository starts and removes an episode's offline copy.
 * @property episodePlayer starts playback and edits the queue from an episode id.
 * @property clock the source of "now" for the date bucketing.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val downloadRepository: DownloadRepository,
    private val episodePlayer: EpisodePlayer,
    private val clock: Clock,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    val uiState: StateFlow<HomeUiState> = combine(
        podcastRepository.observeLatestEpisodes(),
        transientState,
    ) { latest, transient ->
        HomeUiState(
            groups = groupByRecency(latest, clock.instant()),
            continueListening = latest.filter { it.episode.isInProgress },
            isLoading = false,
            isRefreshing = transient.isRefreshing,
            isAutoRefreshing = transient.isAutoRefreshing,
            message = transient.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /**
     * Plays an episode, resuming from wherever it was left.
     *
     * @param episodeId the episode to play.
     * @param onPlaying invoked once playback has been handed to the player, so the caller can open
     *   the player. Not called when the episode has gone.
     */
    fun play(episodeId: String, onPlaying: () -> Unit) {
        viewModelScope.launch {
            if (episodePlayer.play(episodeId)) {
                onPlaying()
            } else {
                transientState.update { it.copy(message = HomeMessage.EpisodeUnavailable) }
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
            transientState.update {
                it.copy(
                    message = if (queued && title != null) {
                        HomeMessage.Queued(title)
                    } else {
                        HomeMessage.EpisodeUnavailable
                    },
                )
            }
        }
    }

    /**
     * Starts or removes an episode's offline copy, depending on which it currently has.
     *
     * One entry point rather than two, because the row has one button whose meaning follows the
     * state it is drawn in.
     *
     * @param episodeId the episode to toggle.
     */
    fun toggleDownload(episodeId: String) {
        viewModelScope.launch {
            val current = currentEntry(episodeId)?.episode?.downloadState
            if (current == DownloadState.COMPLETED || current == DownloadState.DOWNLOADING ||
                current == DownloadState.QUEUED
            ) {
                downloadRepository.removeDownload(episodeId)
            } else {
                downloadRepository.download(episodeId)
            }
        }
    }

    /**
     * Re-fetches every feed because the user pulled the list down.
     *
     * Includes shows with background refresh switched off and shows fetched moments ago: the
     * gesture means "check everything now", and answering "nothing to do" would be
     * indistinguishable from the gesture not registering.
     */
    fun refresh() {
        if (transientState.value.isBusy) return
        transientState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val summary = podcastRepository.refreshAll(onlyAutoRefreshable = false)
            transientState.value = TransientState(message = HomeMessage.RefreshFinished(summary))
        }
    }

    /**
     * Brings feeds up to date on entering the screen, quietly.
     *
     * Reports nothing, including failures: an update the user did not ask for should not interrupt
     * them. Pulling to refresh is what surfaces problems.
     */
    fun refreshStale() {
        if (transientState.value.isBusy) return
        transientState.update { it.copy(isAutoRefreshing = true) }
        viewModelScope.launch {
            podcastRepository.refreshAll(
                onlyAutoRefreshable = true,
                staleAfter = AUTO_REFRESH_STALE_AFTER,
            )
            transientState.update { it.copy(isAutoRefreshing = false) }
        }
    }

    /** Clears the snackbar message once it has been shown. */
    fun onMessageShown() {
        transientState.update { it.copy(message = null) }
    }

    private fun currentEntry(episodeId: String): EpisodeWithShow? =
        uiState.value.groups.asSequence()
            .flatMap { it.episodes }
            .firstOrNull { it.episode.id == episodeId }

    private fun titleOf(episodeId: String): String? = currentEntry(episodeId)?.episode?.title

    /**
     * The parts of the state this screen owns rather than reads.
     *
     * @property isRefreshing a refresh the user asked for is running.
     * @property isAutoRefreshing the quiet on-entry refresh is running.
     * @property message the pending snackbar.
     */
    private data class TransientState(
        val isRefreshing: Boolean = false,
        val isAutoRefreshing: Boolean = false,
        val message: HomeMessage? = null,
    ) {
        /** True while either refresh is in flight; a second request while busy is dropped. */
        val isBusy: Boolean get() = isRefreshing || isAutoRefreshing
    }
}

/**
 * How long the state flow keeps collecting after the last collector goes away.
 *
 * Long enough to cover a tab switch and back, so returning to the feed does not re-query.
 */
private const val STOP_TIMEOUT_MS = 5_000L

/** Feeds fetched more recently than this are skipped by the quiet on-entry refresh. */
private val AUTO_REFRESH_STALE_AFTER: Duration = Duration.ofMinutes(15)
