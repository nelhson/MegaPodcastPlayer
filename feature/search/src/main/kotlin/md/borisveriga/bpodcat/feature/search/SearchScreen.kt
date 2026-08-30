package md.borisveriga.bpodcat.feature.search

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.data.repository.AddPodcastResult
import md.borisveriga.bpodcat.core.designsystem.component.MessageState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.PodcastSearchResult
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Add-a-podcast screen: Apple search plus pasted-link support in one field.
 *
 * @param onPodcastAdded invoked with the new show's id once an add succeeds, so the caller can
 *   navigate straight to it.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun SearchRoute(
    onPodcastAdded: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onAddLink = viewModel::addPastedLink,
        onAddResult = viewModel::addSearchResult,
        onMessageShown = viewModel::onMessageShown,
        onPodcastAdded = onPodcastAdded,
        modifier = modifier,
    )
}

/**
 * Stateless add/search screen.
 *
 * @param uiState what to render.
 * @param onQueryChange keystroke handler.
 * @param onAddLink handler for the "Add this link" button.
 * @param onAddResult handler for adding a search result.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param onPodcastAdded called with the new show's id after a successful add.
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
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current

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
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.search_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(text = stringResource(R.string.search_field_label)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )

            if (uiState.isLink) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        onAddLink()
                    },
                    enabled = uiState.addingId == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    if (uiState.addingId != null) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text(
                            // Naming the source is the clearest possible confirmation that the app
                            // recognised what was pasted, before any network call is made.
                            text = stringResource(
                                if (uiState.isYouTubeLink) {
                                    R.string.search_add_youtube_playlist
                                } else {
                                    R.string.search_add_link
                                },
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            when {
                uiState.isSearching -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.searchError != null -> MessageState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_error_title),
                    description = uiState.searchError.toText(),
                )

                uiState.results.isEmpty() && uiState.query.isNotBlank() && !uiState.isLink ->
                    MessageState(
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = addable && !isAdding, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PodcastArtwork(url = result.artworkUrl, modifier = Modifier.size(56.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (addable) {
                    listOfNotNull(
                        result.episodeCount?.let {
                            pluralStringResource(R.plurals.search_episode_count, it, it)
                        },
                        result.genres.firstOrNull(),
                    ).joinToString(stringResource(R.string.search_result_separator))
                } else {
                    stringResource(R.string.search_result_exclusive)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            isAdding -> CircularProgressIndicator(modifier = Modifier.size(20.dp))

            addable -> Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.search_add_result, result.title),
            )
        }
    }
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

@Preview
@Composable
private fun SearchScreenPreview() {
    BPodcatTheme {
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
        )
    }
}
