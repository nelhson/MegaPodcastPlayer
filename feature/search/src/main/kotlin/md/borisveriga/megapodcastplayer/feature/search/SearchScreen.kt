package md.borisveriga.megapodcastplayer.feature.search

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.data.repository.AddPodcastResult
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowRow
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * Add-a-podcast screen: Apple search plus pasted-link support in one field.
 *
 * @param onPodcastAdded invoked with the new show's id once an add succeeds, so the caller can
 *   navigate straight to it.
 * @param onBack invoked when the user leaves without adding anything.
 * @param modifier layout modifier.
 * @param pasteFromClipboard true when the screen was opened by "Paste a link", in which case
 *   whatever is on the clipboard is put in the field for the user to check before adding.
 * @param viewModel injected by Hilt.
 */
@Composable
fun SearchRoute(
    onPodcastAdded: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pasteFromClipboard: Boolean = false,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current

    // Once, on arrival, and only when the user chose the paste route: reading the clipboard shows a
    // system toast, so it must be something they asked for rather than something every visit does.
    // The text is put in the field rather than added straight away — pasting the wrong thing is
    // easy, and one look at it is cheaper than removing a show afterwards.
    LaunchedEffect(pasteFromClipboard) {
        if (!pasteFromClipboard) return@LaunchedEffect
        val pasted = clipboard.getClipEntry()
            ?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
        if (!pasted.isNullOrEmpty()) viewModel.onQueryChange(pasted)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onAddLink = viewModel::addPastedLink,
        onAddResult = viewModel::addSearchResult,
        onMessageShown = viewModel::onMessageShown,
        onPodcastAdded = onPodcastAdded,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless add/search screen.
 *
 * The field is a Material [SearchBar] rather than a labelled text field under a title bar: this
 * screen is nothing but a search, so the search affordance is the screen's own chrome — which also
 * removes the second row of vertical space the old title bar spent saying so in words.
 *
 * @param uiState what to render.
 * @param onQueryChange keystroke handler.
 * @param onAddLink handler for the "add this link" card.
 * @param onAddResult handler for adding a search result.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param onPodcastAdded called with the new show's id after a successful add.
 * @param onBack back handler.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onAddLink: () -> Unit,
    onAddResult: (PodcastSearchResult) -> Unit,
    onMessageShown: () -> Unit,
    onPodcastAdded: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current
    val focusRequester = remember { FocusRequester() }

    // The screen exists to be typed into, and it is always reached by a deliberate tap, so it opens
    // ready for the first keystroke rather than making the user tap the field they just asked for.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toUserText(resources))
        // Both branches mean "this show is in the library now"; go look at it.
        when (message) {
            is AddPodcastResult.Added -> onPodcastAdded(message.podcast.id)
            is AddPodcastResult.AlreadyInLibrary -> onPodcastAdded(message.podcast.id)
            else -> Unit
        }
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = uiState.query,
                        onQueryChange = onQueryChange,
                        onSearch = { keyboard?.hide() },
                        expanded = false,
                        onExpandedChange = { },
                        modifier = Modifier.focusRequester(focusRequester),
                        placeholder = { Text(text = stringResource(R.string.search_field_label)) },
                        leadingIcon = {
                            // The back arrow lives in the bar itself: with no title row left, this
                            // is the only chrome the screen has, and a pushed screen still needs a
                            // way out that does not depend on the system gesture.
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.search_back),
                                )
                            }
                        },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                    )
                },
                // Never expanded: the results belong to the screen, not to an overlay that would
                // cover the very field being typed into on a phone-sized window.
                expanded = false,
                onExpandedChange = { },
                content = { },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (uiState.isLink) {
                LinkCard(
                    isYouTube = uiState.isYouTubeLink,
                    isAdding = uiState.addingId != null,
                    onAdd = {
                        keyboard?.hide()
                        onAddLink()
                    },
                )
            }

            when {
                uiState.isSearching -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MegaPodcastPlayerTheme.spacing.xl),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.searchError != null -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_error_title),
                    description = uiState.searchError.toText(),
                )

                uiState.results.isEmpty() && uiState.query.isNotBlank() && !uiState.isLink ->
                    EmptyState(
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.search_empty_title),
                        description = stringResource(R.string.search_empty_description),
                    )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = uiState.results, key = { it.itunesId }) { result ->
                        SearchResultRow(
                            result = result,
                            isAdding = uiState.addingId == result.itunesId.toString(),
                            onClick = { onAddResult(result) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "this is a link, add it" card.
 *
 * A card at the top of the results rather than a button wedged under the field: recognising a
 * pasted link is the app noticing something, and it should look like an offer rather than like a
 * control that was always there.
 *
 * @param isYouTube whether the link is a YouTube playlist, which changes only the wording.
 * @param isAdding true while an add is in flight; the button becomes a spinner.
 * @param onAdd invoked when the card's button is pressed.
 * @param modifier layout modifier.
 */
@Composable
private fun LinkCard(
    isYouTube: Boolean,
    isAdding: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                vertical = MegaPodcastPlayerTheme.spacing.sm,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MegaPodcastPlayerTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            ) {
                Icon(imageVector = Icons.Rounded.Link, contentDescription = null)
                Text(
                    text = stringResource(R.string.search_link_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Button(
                onClick = onAdd,
                enabled = !isAdding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isAdding) {
                    CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE))
                } else {
                    Text(
                        // Naming the source is the clearest possible confirmation that the app
                        // recognised what was pasted, before any network call is made.
                        text = stringResource(
                            if (isYouTube) {
                                R.string.search_add_youtube_playlist
                            } else {
                                R.string.search_add_link
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One Apple search result.
 *
 * @param result the show.
 * @param isAdding true while this specific row is being added.
 * @param onClick tap handler.
 * @param modifier layout modifier.
 */
@Composable
private fun SearchResultRow(
    result: PodcastSearchResult,
    isAdding: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Apple exclusives publish no RSS feed and therefore cannot be added; say so up front rather
    // than failing after the tap.
    val addable = !result.feedUrl.isNullOrBlank()

    ShowRow(
        title = result.title,
        modifier = modifier,
        author = result.author,
        metadata = if (addable) {
            listOfNotNull(
                result.episodeCount?.let {
                    pluralStringResource(R.plurals.search_episode_count, it, it)
                },
                result.genres.firstOrNull(),
            ).joinToString(stringResource(R.string.search_result_separator))
        } else {
            stringResource(R.string.search_result_exclusive)
        },
        artworkUrl = result.artworkUrl,
        onClick = onClick,
        enabled = addable && !isAdding,
        trailing = {
            when {
                isAdding -> CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE))

                addable -> Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.search_add_result, result.title),
                )
            }
        },
    )
}

/**
 * Wording for a [SearchError].
 *
 * @return the sentence shown under the failed-search heading.
 */
@Composable
private fun SearchError.toText(): String = when (this) {
    SearchError.NoConnection -> stringResource(R.string.search_error_no_connection)
    SearchError.Timeout -> stringResource(R.string.search_error_timeout)
    is SearchError.Unknown -> stringResource(R.string.search_error_unknown, detail)
}

/**
 * Turns an add outcome into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun AddPodcastResult.toUserText(resources: Resources): String = when (this) {
    is AddPodcastResult.Added -> resources.getString(
        R.string.search_message_added,
        podcast.title,
        resources.getQuantityString(
            // "videos" rather than "episodes" for a playlist: it is what the user called them.
            if (podcast.source == PodcastSource.YOUTUBE) {
                R.plurals.search_video_count
            } else {
                R.plurals.search_episode_count
            },
            episodeCount,
            episodeCount,
        ),
    )

    is AddPodcastResult.AlreadyInLibrary ->
        resources.getString(R.string.search_message_already_in_library, podcast.title)

    is AddPodcastResult.NoFeedAvailable ->
        resources.getString(R.string.search_message_no_feed, title)

    AddPodcastResult.NotFound -> resources.getString(R.string.search_message_not_found)

    AddPodcastResult.InvalidInput -> resources.getString(R.string.search_message_invalid_input)

    AddPodcastResult.NotAPlaylist -> resources.getString(R.string.search_message_not_a_playlist)

    is AddPodcastResult.Failed -> resources.getString(
        R.string.search_message_failed,
        cause.message ?: resources.getString(R.string.search_message_failed_unknown_cause),
    )
}

/** The spinner that replaces an add control while its request is in flight. */
private val SPINNER_SIZE = 20.dp

@Preview
@Composable
private fun SearchScreenPreview() {
    MegaPodcastPlayerTheme {
        SearchScreen(
            uiState = SearchUiState(
                query = "podlodka",
                results = listOf(
                    PodcastSearchResult(
                        itunesId = 1209828744L,
                        title = "Podlodka Podcast",
                        author = "Егор Толстой",
                        feedUrl = "https://example.com/feed.rss",
                        artworkUrl = null,
                        episodeCount = 500,
                        genres = listOf("Technology"),
                    ),
                    PodcastSearchResult(
                        itunesId = 2L,
                        title = "Exclusive Show",
                        author = "Apple",
                        feedUrl = null,
                        artworkUrl = null,
                        episodeCount = 10,
                        genres = emptyList(),
                    ),
                ),
            ),
            onQueryChange = {},
            onAddLink = {},
            onAddResult = {},
            onMessageShown = {},
            onPodcastAdded = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun SearchScreenLinkPreview() {
    MegaPodcastPlayerTheme {
        SearchScreen(
            uiState = SearchUiState(
                query = "https://podcasts.apple.com/podcast/id1209828744",
                isLink = true,
            ),
            onQueryChange = {},
            onAddLink = {},
            onAddResult = {},
            onMessageShown = {},
            onPodcastAdded = {},
            onBack = {},
        )
    }
}
