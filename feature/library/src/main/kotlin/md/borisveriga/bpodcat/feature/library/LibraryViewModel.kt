package md.borisveriga.bpodcat.feature.library

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
import md.borisveriga.bpodcat.core.data.repository.PodcastRepository
import md.borisveriga.bpodcat.core.data.repository.RefreshSummary
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

/**
 * State rendered by the library screen.
 *
 * @property podcasts subscribed shows with their episode counts.
 * @property isLoading true until the first database emission arrives.
 * @property isRefreshing true while a manual "refresh all" is in flight.
 * @property message a one-off result to surface in a snackbar; cleared via
 *   [LibraryViewModel.onMessageShown].
 */
data class LibraryUiState(
    val podcasts: List<PodcastWithCounts> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
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
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeLibrary(),
        transientState,
    ) { podcasts, transient ->
        LibraryUiState(
            podcasts = podcasts,
            isLoading = false,
            isRefreshing = transient.isRefreshing,
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
     * Re-fetches every feed, including shows with background refresh switched off — an explicit tap
     * means "check everything now".
     *
     * Never downloads audio.
     */
    fun refreshAll() {
        if (transientState.value.isRefreshing) return
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

    /** Clears the current [LibraryUiState.message] once the snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    /** State owned by the view model rather than the database. */
    private data class TransientState(
        val isRefreshing: Boolean = false,
        val message: LibraryMessage? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
