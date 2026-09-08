package md.borisveriga.megapodcastplayer.feature.search

import androidx.lifecycle.SavedStateHandle
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
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.AddPodcastResult
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastPreviewResult
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastLink
import md.borisveriga.megapodcastplayer.core.model.PodcastLinkParser
import md.borisveriga.megapodcastplayer.core.model.PodcastPreview
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
 * @property preview the show the user is looking at without having added it, or null when no sheet
 *   is open.
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
    val preview: PreviewUiState? = null,
)

/**
 * The preview sheet's own state.
 *
 * Carries the result it was opened from as well as whatever has been loaded, so the sheet can name
 * the show — and draw its cover — while the feed is still being fetched. A sheet that opened blank
 * and filled in a second later would read as a mis-tap.
 *
 * @property result the search result the sheet was opened from.
 * @property isLoading true while the feed is being fetched.
 * @property preview what the feed said, once it has said it.
 * @property error why the feed could not be read, or null. The two failures are told apart because
 *   one of them — a show Apple lists but publishes no feed for — is not a network problem and
 *   retrying it will never help.
 * @property isSubscribing true while the show is being added from the sheet.
 */
data class PreviewUiState(
    val result: PodcastSearchResult,
    val isLoading: Boolean = true,
    val preview: PodcastPreview? = null,
    val error: PreviewError? = null,
    val isSubscribing: Boolean = false,
)

/** Why a preview could not be shown. */
sealed interface PreviewError {

    /** Apple lists the show but publishes no feed for it; there is nothing to fetch, ever. */
    data object NoFeed : PreviewError

    /** The feed could not be fetched or parsed. Worth offering again. */
    data object Unreachable : PreviewError
}

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
 * A third entry point arrives with the field already filled: a link shared or tapped in another
 * app, carried here as the route's `link` argument. It seeds the query and nothing more — the
 * screen it produces is the one a paste produces, down to the tap that adds the show. An intent
 * any app on the device can send is allowed to *offer* a show, never to add one.
 *
 * @property repository the single source of podcast truth.
 * @property episodePlayer plays an episode of a show the user has not added; the preview sheet's
 *   own reason for existing beyond the description it shows.
 * @param savedStateHandle carries the route's `link` argument.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val episodePlayer: EpisodePlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedStateHandle.get<String>(LINK_ARG).orEmpty())
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
                preview = adding.preview,
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

    /**
     * Opens the preview sheet for a result and starts fetching its feed.
     *
     * Tapping a result used to subscribe on the spot, which made looking at an unfamiliar show a
     * subscription followed by an unsubscription — and unsubscribing is the one action in this app
     * that takes everything with it and cannot be undone.
     *
     * @param result the show to look at.
     */
    fun openPreview(result: PodcastSearchResult) {
        addState.value = addState.value.copy(preview = PreviewUiState(result = result))
        viewModelScope.launch {
            val outcome = repository.preview(result)
            // The sheet may have been dismissed, or another result opened, while the feed was in
            // flight. Answering the wrong question is worse than not answering.
            val current = addState.value.preview ?: return@launch
            if (current.result.itunesId != result.itunesId) return@launch
            addState.value = addState.value.copy(preview = current.applying(outcome))
        }
    }

    /** Closes the preview sheet. */
    fun dismissPreview() {
        addState.value = addState.value.copy(preview = null)
    }

    /**
     * Adds the show the preview sheet is showing.
     *
     * The sheet closes when the add finishes rather than on the tap, so a failure is reported over
     * the sheet that asked for it rather than over a list the user has already been returned to.
     */
    fun subscribeFromPreview() {
        val open = addState.value.preview ?: return
        addState.value = addState.value.copy(preview = open.copy(isSubscribing = true))
        // The same path a result row took before the sheet existed, rather than a second one: the
        // sheet decides *when* a show is added, not what adding one means.
        addSearchResult(open.result)
    }

    /**
     * Plays one episode of a show that has not been added.
     *
     * The other half of looking before subscribing: a description says what a show is about, and
     * two minutes of it says whether it is bearable. Nothing is stored — see
     * [EpisodePlayer.playUnsubscribed].
     *
     * @param episode the episode to play, as the preview carried it.
     */
    fun playPreviewEpisode(episode: Episode) {
        val open = addState.value.preview ?: return
        val show = open.preview?.podcast ?: return
        viewModelScope.launch {
            episodePlayer.playUnsubscribed(
                episode = episode,
                showTitle = show.title,
                showArtworkUrl = show.artworkUrl,
            )
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
        addState.value = addState.value.copy(inFlightId = id)
        viewModelScope.launch {
            val result = block()
            addState.value = addState.value.copy(
                inFlightId = null,
                message = result,
                // Both branches mean "this show is in the library now".
                navigateToPodcastId = if (navigateOnSuccess) result.podcastIdOrNull() else null,
                // The question the sheet was open to answer has been answered, whichever way it
                // went; the list underneath, where the row now carries a tick, is what comes next.
                preview = null,
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

    /** Add-in-progress state, plus the preview sheet that usually precedes an add. */
    private data class AddState(
        val inFlightId: String? = null,
        val message: AddPodcastResult? = null,
        val navigateToPodcastId: String? = null,
        val preview: PreviewUiState? = null,
    )

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * The route argument carrying a shared or tapped link.
         *
         * Spelled here as a string because that is how a `SavedStateHandle` is keyed; it has to
         * match the property name on `Route.Search`, and nothing but a test can check that it does.
         */
        const val LINK_ARG = "link"
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
 * Folds a preview outcome into the sheet's state.
 *
 * A method on the state rather than a `when` inside the view model, so that "what the sheet shows
 * for each outcome" is one expression and cannot be half-updated when a fourth outcome appears.
 *
 * @param outcome what the repository came back with.
 * @return the state to render.
 */
private fun PreviewUiState.applying(outcome: PodcastPreviewResult): PreviewUiState = when (outcome) {
    is PodcastPreviewResult.Loaded ->
        copy(isLoading = false, preview = outcome.preview, error = null)

    is PodcastPreviewResult.NoFeedAvailable ->
        copy(isLoading = false, error = PreviewError.NoFeed)

    is PodcastPreviewResult.Failed ->
        copy(isLoading = false, error = PreviewError.Unreachable)
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
