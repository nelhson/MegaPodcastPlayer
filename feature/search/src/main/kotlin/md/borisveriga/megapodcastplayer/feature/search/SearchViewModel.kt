package md.borisveriga.megapodcastplayer.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.repository.AddPodcastResult
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastLink
import md.borisveriga.megapodcastplayer.core.model.PodcastLinkParser
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.model.podcastIdOf

/**
 * State rendered by the add/search screen.
 *
 * @property query the current text.
 * @property isLink true when [query] parses as an Apple Podcasts link, a bare Apple id, a YouTube
 *   playlist link or an RSS URL. The screen then offers to add it instead of running a text search.
 * @property isYouTubeLink true when [query] is specifically a YouTube playlist. Only used to label
 *   the button, which is the earliest point at which the app can show the user it understood what
 *   they pasted.
 * @property results Apple search results for [query].
 * @property isSearching true while a search is in flight.
 * @property searchError why the search itself failed (offline, rate limited), or null. A closed
 *   set rather than a message, so the wording lives with the screen that shows it.
 * @property addingId Apple id (or feed URL) currently being added, so exactly one row shows a
 *   spinner.
 * @property addedPodcastIds local podcast id for every result already in the library, keyed by
 *   Apple id. A row in here is not an add button but a way back to a show the user already has —
 *   which is why it carries the local id rather than a bare "yes".
 * @property message the outcome of the last add attempt.
 * @property navigateToPodcastId a show to open, set only by the paste-a-link path. Adding from a
 *   result deliberately leaves the user in the list they were reading, so it never sets this.
 */
data class SearchUiState(
    val query: String = "",
    val isLink: Boolean = false,
    val isYouTubeLink: Boolean = false,
    val results: List<PodcastSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: SearchError? = null,
    val addingId: String? = null,
    val addedPodcastIds: Map<Long, String> = emptyMap(),
    val message: AddPodcastResult? = null,
    val navigateToPodcastId: String? = null,
)

/**
 * Why a search failed.
 *
 * Modelled rather than pre-worded because a view model has no `Context` and should not be choosing
 * copy; the screen turns each of these into a sentence.
 */
sealed interface SearchError {

    /** The device could not resolve Apple's host — almost always no connection at all. */
    data object NoConnection : SearchError

    /** Apple accepted the connection but did not answer in time. */
    data object Timeout : SearchError

    /**
     * Anything else.
     *
     * @property detail the failure's own words, for the message; never null, never empty.
     */
    data class Unknown(val detail: String) : SearchError
}

/**
 * Drives the add/search screen.
 *
 * A single text field serves both entry points from the plan: typing a show name searches Apple,
 * while pasting one of the `podcasts.apple.com/...id123` links switches the screen to a one-tap
 * "add this link" action.
 *
 * @property repository the single source of podcast truth.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val addState = MutableStateFlow(AddState())

    /**
     * Apple results for the debounced query.
     *
     * Debounced because the iTunes API is rate limited at roughly 20 requests per minute; without
     * this, typing "podlodka" alone would spend half the budget.
     */
    private val searchResults: StateFlow<SearchState> = query
        .debounce { text -> if (text.isBlank()) 0L else DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { text ->
            flow {
                if (text.isBlank() || PodcastLinkParser.parse(text) != null) {
                    // Nothing to search: either the field is empty, or it holds a link that the
                    // user will add directly.
                    emit(SearchState())
                    return@flow
                }
                emit(SearchState(isSearching = true))
                val result = repository.search(text)
                emit(
                    result.fold(
                        onSuccess = { SearchState(results = it) },
                        onFailure = { SearchState(error = it.toSearchError()) },
                    ),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SearchState(),
        )

    val uiState: StateFlow<SearchUiState> =
        combine(
            query,
            searchResults,
            addState,
            repository.observeLibrary(),
        ) { text, search, adding, library ->
            val link = PodcastLinkParser.parse(text)
            SearchUiState(
                query = text,
                isLink = link != null,
                isYouTubeLink = link is PodcastLink.YouTubePlaylist,
                results = search.results,
                isSearching = search.isSearching,
                searchError = search.error,
                addingId = adding.inFlightId,
                addedPodcastIds = search.results.addedIds(library.map { it.podcast }),
                message = adding.message,
                navigateToPodcastId = adding.navigateToPodcastId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SearchUiState(),
        )

    /** Called on every keystroke. */
    fun onQueryChange(value: String) {
        query.value = value
    }

    /**
     * Adds whatever is currently in the text field, treating it as a link.
     *
     * This is the one path that navigates on success: a pasted link names one specific show, so
     * opening it is what the user asked for, and there is no list left behind worth returning to.
     */
    fun addPastedLink() {
        val input = query.value
        add(id = input, navigateOnSuccess = true) { repository.addFromInput(input) }
    }

    /**
     * Adds a show the user picked from the results.
     *
     * Deliberately does not navigate. The user is reading a list of candidates and may well want
     * several of them; the row turning into a tick is the whole confirmation, and the list stays
     * where it was.
     *
     * @param result the chosen show.
     */
    fun addSearchResult(result: PodcastSearchResult) {
        add(id = result.itunesId.toString(), navigateOnSuccess = false) {
            repository.addFromSearchResult(result)
        }
    }

    /** Clears the last add outcome once its snackbar has been shown. */
    fun onMessageShown() {
        addState.value = addState.value.copy(message = null)
    }

    /** Clears the pending navigation once the screen has acted on it. */
    fun onNavigationHandled() {
        addState.value = addState.value.copy(navigateToPodcastId = null)
    }

    private fun add(
        id: String,
        navigateOnSuccess: Boolean,
        block: suspend () -> AddPodcastResult,
    ) {
        if (addState.value.inFlightId != null) return
        addState.value = AddState(inFlightId = id)
        viewModelScope.launch {
            val result = block()
            addState.value = AddState(
                inFlightId = null,
                message = result,
                // Both branches mean "this show is in the library now".
                navigateToPodcastId = if (navigateOnSuccess) result.podcastIdOrNull() else null,
            )
            // A successful add clears the field so the next paste starts clean.
            if (result is AddPodcastResult.Added) query.value = ""
        }
    }

    /** Search-pipeline state, kept separate from the add-in-progress state. */
    private data class SearchState(
        val results: List<PodcastSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val error: SearchError? = null,
    )

    /** Add-in-progress state. */
    private data class AddState(
        val inFlightId: String? = null,
        val message: AddPodcastResult? = null,
        val navigateToPodcastId: String? = null,
    )

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Matches search results against the library.
 *
 * Two keys, because either alone misses cases. Apple's `collectionId` is the direct match but is
 * null on a show added from a pasted RSS link, and the feed URL is the app's real uniqueness key —
 * [podcastIdOf] of it is literally how the stored id is derived — but a publisher's feed URL and
 * Apple's copy of it can differ by a redirect. A result is "already added" if either agrees.
 *
 * @param library every stored show.
 * @return the local podcast id for each matched result, keyed by Apple id.
 */
private fun List<PodcastSearchResult>.addedIds(library: List<Podcast>): Map<Long, String> {
    if (isEmpty() || library.isEmpty()) return emptyMap()
    val byLocalId = library.associateBy { it.id }
    val byItunesId = library.filter { it.itunesId != null }.associateBy { it.itunesId }
    return mapNotNull { result ->
        val match = byItunesId[result.itunesId]
            ?: result.feedUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { byLocalId[podcastIdOf(it)] }
        match?.let { result.itunesId to it.id }
    }.toMap()
}

/**
 * The show an add outcome landed on, if it landed on one.
 *
 * @return the local podcast id for the two outcomes that mean "it is in the library now", else null.
 */
private fun AddPodcastResult.podcastIdOrNull(): String? = when (this) {
    is AddPodcastResult.Added -> podcast.id
    is AddPodcastResult.AlreadyInLibrary -> podcast.id
    else -> null
}

/**
 * Classifies a search failure.
 *
 * @return the closed-set reason the screen can word for itself.
 */
private fun Throwable.toSearchError(): SearchError = when (this) {
    is UnknownHostException -> SearchError.NoConnection
    is SocketTimeoutException -> SearchError.Timeout
    else -> SearchError.Unknown(message ?: this::class.simpleName.orEmpty())
}
