package md.borisveriga.bpodcat.feature.library

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
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import md.borisveriga.bpodcat.core.data.repository.UiPreferencesRepository
import md.borisveriga.bpodcat.core.model.LibraryLayout
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

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
     * A show was removed, with the option to add it back.
     *
     * @property title the removed show's title.
     */
    data class Removed(val title: String) : LibraryMessage
}

/**
 * Drives the library screen.
 *
 * @property repository the single source of podcast truth.
 * @property uiPreferences the stored grid-or-list choice.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val uiPreferences: UiPreferencesRepository,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

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
