package md.borisveriga.bpodcat.feature.search

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toUserText())
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
        topBar = { TopAppBar(title = { Text(text = "Add a podcast") }) },
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
                label = { Text(text = "Show name, Apple / RSS / YouTube playlist link") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear the search field")
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
                            text = if (uiState.isYouTubeLink) {
                                "Add this YouTube playlist"
                            } else {
                                "Add this link"
                            },
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
                    title = "Couldn't search",
                    description = uiState.searchError,
                )

                uiState.results.isEmpty() && uiState.query.isNotBlank() && !uiState.isLink ->
                    MessageState(
                        icon = Icons.Rounded.Search,
                        title = "Nothing found",
                        description = "Try a different spelling, or paste the show's Apple " +
                            "Podcasts link instead.",
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
                        result.episodeCount?.let { "$it episodes" },
                        result.genres.firstOrNull(),
                    ).joinToString(" · ")
                } else {
                    "Apple Podcasts exclusive — no RSS feed to download"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            isAdding -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
            addable -> Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Add ${result.title}",
            )
        }
    }
}

/** Turns an add outcome into snackbar text. */
private fun AddPodcastResult.toUserText(): String = when (this) {
    is AddPodcastResult.Added -> {
        val noun = if (podcast.source == PodcastSource.YOUTUBE) "videos" else "episodes"
        "Added ${podcast.title} · $episodeCount $noun"
    }

    is AddPodcastResult.AlreadyInLibrary -> "${podcast.title} is already in your library"
    is AddPodcastResult.NoFeedAvailable -> "$title has no RSS feed and can't be downloaded"
    AddPodcastResult.NotFound -> "Apple doesn't know that podcast id"
    AddPodcastResult.InvalidInput -> "That doesn't look like a podcast link"
    // Almost always a single video pasted instead of the playlist, so the message says what to do
    // rather than just what went wrong.
    AddPodcastResult.NotAPlaylist ->
        "That's a YouTube link, but not a playlist — open the playlist and copy its link"

    is AddPodcastResult.Failed -> "Couldn't read that feed: ${cause.message ?: "unknown error"}"
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
