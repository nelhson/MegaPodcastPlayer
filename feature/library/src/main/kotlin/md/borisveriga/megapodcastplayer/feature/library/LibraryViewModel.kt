package md.borisveriga.megapodcastplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RefreshSummary
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts

/**
 * State rendered by the library screen.
 *
 * @property podcasts subscribed shows with their episode counts.
 * @property layout whether the shows are drawn as cover tiles or as rows. Part of the state rather
 *   than remembered in the composition, because it is a stored preference: a layout that reset on
 *   process death would be one the user has to keep re-choosing.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a pull-to-refresh is in flight; drives the gesture's own
 *   spinner and ends in a snackbar.
 * @property isAutoRefreshing true while the refresh that runs on entering the screen is in flight.
 *   Kept apart from [isRefreshing] because the two are deliberately different in tone: one is
 *   something the user asked for and gets an answer to, the other is housekeeping and shows only a
 *   thin progress line.
 * @property message a one-off result to surface in a snackbar; cleared via
 *   [LibraryViewModel.onMessageShown].
 */
data class LibraryUiState(
    val podcasts: List<PodcastWithCounts> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.DEFAULT,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val message: LibraryMessage? = null,
)

/**
 * A one-off outcome to show the user.
 *
 * Modelled as state rather than an event channel so it survives configuration changes and the
 * unfold/fold transition on the Fold 7.
 */
sealed interface LibraryMessage {

    /**
     * A manual refresh finished.
     *
     * @property summary what the run found; the UI turns it into "3 new episodes".
     */
    data class RefreshFinished(val summary: RefreshSummary) : LibraryMessage

    /**
     * A show was removed.
     *
     * @property title the removed show's title.
     */
    data class Removed(val title: String) : LibraryMessage

    /**
     * A full swipe put a show's next episode in the queue.
     *
     * Names the episode rather than the show: the row said which show it was, and what the user
     * cannot see from the gesture is *which* episode they just got.
     *
     * @property episodeTitle the queued episode.
     */
    data class Queued(val episodeTitle: String) : LibraryMessage

    /**
     * A full swipe found nothing to queue, because the show is finished.
     *
     * A distinct outcome rather than silence: a gesture that does nothing and says nothing is
     * indistinguishable from one that did not register.
     *
     * @property showTitle the show that had nothing unplayed left in it.
     */
    data class NothingToQueue(val showTitle: String) : LibraryMessage

    /**
     * A whole show was marked played, and can be put back.
     *
     * @property showTitle the show.
     * @property count how many episodes actually changed, which is what an undo would restore —
     *   never the show's whole episode count.
     */
    data class MarkedAllPlayed(val showTitle: String, val count: Int) : LibraryMessage
}

/**
 * Drives the library screen.
 *
 * @property repository the single source of podcast truth.
 * @property uiPreferences the stored grid-or-list choice.
 * @property episodePlayer what turns "queue the next one from this show" into a queue entry; the
 *   library knows shows, and this is the only thing here that touches an episode.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val uiPreferences: UiPreferencesRepository,
    private val episodePlayer: EpisodePlayer,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    /**
     * The episodes the last "mark all played" changed, or null.
     *
     * The undo restores exactly these rather than un-playing the whole show, which would also
     * reopen episodes the user had finished months ago.
     */
    private var pendingUnplay: List<String>? = null

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeLibrary(),
        uiPreferences.observeLibraryLayout(),
        transientState,
    ) { podcasts, layout, transient ->
        LibraryUiState(
            podcasts = podcasts,
            layout = layout,
            isLoading = false,
            isRefreshing = transient.isRefreshing,
            isAutoRefreshing = transient.isAutoRefreshing,
            message = transient.message,
        )
    }.stateIn(
        scope = viewModelScope,
        // Keep collecting for a moment after the screen goes away so a fold/unfold or a quick
        // navigation round trip does not re-query the database.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = LibraryUiState(),
    )

    /**
     * Re-fetches every feed, including shows with background refresh switched off and shows fetched
     * moments ago — pulling the list down means "check everything now", and a gesture that answers
     * "nothing to do" would be indistinguishable from one that did not register.
     *
     * Never downloads audio.
     */
    fun refreshAll() {
        if (transientState.value.isBusy) return
        transientState.value = transientState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            val summary = repository.refreshAll(onlyAutoRefreshable = false)
            transientState.value = TransientState(
                isRefreshing = false,
                message = LibraryMessage.RefreshFinished(summary),
            )
        }
    }

    /**
     * Applies a completed reorder gesture.
     *
     * The screen reports the move as two positions; what the database wants is the whole
     * arrangement, so it is rebuilt here from the order the view model last published. Doing it
     * this way rather than taking a list of ids from the UI keeps the source of truth for "what the
     * library contains" in one place — the UI's copy can be a frame or two behind during a drag,
     * which is the point of the local ordering it draws with.
     *
     * Out-of-range positions are ignored rather than clamped: they mean the library changed under
     * the gesture, and guessing what the user meant would be worse than doing nothing.
     *
     * @param from the show's position before the drag.
     * @param to where it was dropped.
     */
    fun move(from: Int, to: Int) {
        val current = uiState.value.podcasts
        if (from !in current.indices || to !in current.indices || from == to) return

        val ids = current
            .toMutableList()
            .apply { add(to, removeAt(from)) }
            .map { it.podcast.id }

        viewModelScope.launch { repository.reorderLibrary(ids) }
    }

    /**
     * Brings the library up to date on entering the screen, quietly.
     *
     * Two things keep this from being a nuisance. Feeds fetched within [AUTO_REFRESH_STALE_AFTER]
     * are skipped in the repository, so returning from a show or flicking between tabs costs
     * nothing; and shows the user switched background refresh off for are left out, because a bulk
     * refresh is exactly what that toggle declines. Opening such a show directly still refreshes it
     * — see `PodcastDetailViewModel`.
     *
     * Reports nothing: an update the user did not ask for should not interrupt them, and a snackbar
     * on every entry into the library would be constant. Failures are equally silent, which is the
     * price of that; pulling to refresh is what surfaces them.
     */
    fun refreshStale() {
        if (transientState.value.isBusy) return
        transientState.value = transientState.value.copy(isAutoRefreshing = true)
        viewModelScope.launch {
            repository.refreshAll(
                onlyAutoRefreshable = true,
                staleAfter = AUTO_REFRESH_STALE_AFTER,
            )
            transientState.value = transientState.value.copy(isAutoRefreshing = false)
        }
    }

    /**
     * Removes a show and everything stored for it.
     *
     * Not offered back the way the other two gestures are, and deliberately not: putting a show
     * back means re-fetching its feed, and what would come back is a subscription, not the one that
     * was removed — every played flag, every position and every downloaded file went with it. The
     * screen asks first instead, which is the honest place to put the friction.
     *
     * @param podcast the show to remove; its title is echoed back in the confirmation message.
     */
    fun remove(podcast: PodcastWithCounts) {
        viewModelScope.launch {
            repository.remove(podcast.podcast.id)
            transientState.value = transientState.value.copy(
                message = LibraryMessage.Removed(podcast.podcast.title),
            )
        }
    }

    /**
     * Queues the show's newest unplayed episode.
     *
     * What a full right-to-left swipe on a library row commits. "Newest unplayed" rather than
     * "newest" because a row swiped twice should queue two different episodes, and because the
     * episode a finished show would otherwise offer is one the user has already heard.
     *
     * @param podcast the show to take an episode from.
     */
    fun queueNewest(podcast: PodcastWithCounts) {
        viewModelScope.launch {
            val episode = repository.newestUnplayedEpisode(podcast.podcast.id)
            val message = when {
                episode == null -> LibraryMessage.NothingToQueue(podcast.podcast.title)

                // The player refuses an episode it cannot resolve — one whose show has just been
                // removed under the gesture. Reporting that as "nothing to queue" is wrong but
                // harmless; claiming it was queued would be a lie the queue then contradicts.
                !episodePlayer.addToQueue(episode.id) ->
                    LibraryMessage.NothingToQueue(podcast.podcast.title)

                else -> LibraryMessage.Queued(episode.title)
            }
            transientState.value = transientState.value.copy(message = message)
        }
    }

    /**
     * Marks every unplayed episode of a show played, and offers it back.
     *
     * @param podcast the show to mark off.
     */
    fun markAllPlayed(podcast: PodcastWithCounts) {
        viewModelScope.launch {
            val changed = repository.markPodcastPlayed(podcast.podcast.id)
            pendingUnplay = changed.takeIf { it.isNotEmpty() }
            transientState.value = transientState.value.copy(
                message = LibraryMessage.MarkedAllPlayed(podcast.podcast.title, changed.size),
            )
        }
    }

    /**
     * Un-plays whatever the last [markAllPlayed] marked.
     *
     * Consumed rather than kept, so an undo cannot be replayed against a library that has moved on.
     */
    fun undoMarkAllPlayed() {
        val ids = pendingUnplay ?: return
        pendingUnplay = null
        transientState.value = transientState.value.copy(message = null)
        viewModelScope.launch { repository.setEpisodesPlayed(ids, isPlayed = false) }
    }

    /**
     * Switches between the cover grid and the row list, and remembers the choice.
     *
     * Takes the layout to move to rather than toggling from the current state, so a double tap on
     * a slow write cannot land the screen on the layout the user just left.
     *
     * @param layout the layout to show from now on.
     */
    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { uiPreferences.setLibraryLayout(layout) }
    }

    /** Clears the current [LibraryUiState.message] once the snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
        // The undo goes with the snackbar that offered it. Left armed, it would fire against the
        // *next* message, un-playing a show the user never asked about.
        pendingUnplay = null
    }

    /** State owned by the view model rather than the database. */
    private data class TransientState(
        val isRefreshing: Boolean = false,
        val isAutoRefreshing: Boolean = false,
        val message: LibraryMessage? = null,
    ) {
        /**
         * Whether a refresh of either kind is already running.
         *
         * One guard for both, so an automatic refresh cannot start on top of a pull-to-refresh and
         * quietly cancel out its snackbar, nor the reverse.
         */
        val isBusy: Boolean get() = isRefreshing || isAutoRefreshing
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How stale a feed must be before entering the library re-fetches it.
         *
         * Fifteen minutes sits well inside the six-hour background cycle — the gap this is here to
         * cover — while being long enough that moving around the app is free.
         */
        val AUTO_REFRESH_STALE_AFTER: Duration = Duration.ofMinutes(15)
    }
}
