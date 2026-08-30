package md.borisveriga.bpodcat.feature.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.MessageState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.component.SourceBadge
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

/**
 * Library screen: every subscribed show, with a manual refresh and a route into search.
 *
 * @param onPodcastClick invoked with a podcast id when a row is tapped.
 * @param onAddClick invoked when the user wants to add a show.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun LibraryRoute(
    onPodcastClick: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreen(
        uiState = uiState,
        onPodcastClick = onPodcastClick,
        onAddClick = onAddClick,
        onRefresh = viewModel::refreshAll,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless library screen.
 *
 * @param uiState what to render.
 * @param onPodcastClick row tap handler.
 * @param onAddClick add-podcast handler.
 * @param onRefresh manual refresh handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onPodcastClick: (String) -> Unit,
    onAddClick: () -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showMessage(message)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Library") },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isRefreshing) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Check all podcasts for new episodes",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "Add a podcast")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(
                modifier = Modifier.padding(padding),
                contentDescription = "Loading your podcasts",
            )

            uiState.podcasts.isEmpty() -> MessageState(
                icon = Icons.Rounded.LibraryMusic,
                title = "No podcasts yet",
                description = "Search Apple Podcasts, or paste an RSS or YouTube playlist " +
                    "link, to add your first show.",
                actionLabel = "Add a podcast",
                onAction = onAddClick,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(items = uiState.podcasts, key = { it.podcast.id }) { entry ->
                    PodcastRow(entry = entry, onClick = { onPodcastClick(entry.podcast.id) })
                }
            }
        }
    }
}

/**
 * One library row: artwork, title, author and a "new episodes" badge.
 *
 * @param entry the show and its counts.
 * @param onClick row tap handler.
 * @param modifier layout modifier.
 */
@Composable
private fun PodcastRow(
    entry: PodcastWithCounts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PodcastArtwork(url = entry.podcast.artworkUrl, modifier = Modifier.size(64.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.podcast.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Badge first: a long author name that truncates can then never push it off the
                // row, which is exactly when knowing the source matters most.
                SourceBadge(source = entry.podcast.source)
                Text(
                    text = entry.podcast.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = buildString {
                    // "videos" rather than "episodes" for a playlist: it is what the user called
                    // them when they added it.
                    val noun = if (entry.podcast.source == PodcastSource.YOUTUBE) "videos" else "episodes"
                    append("${entry.episodeCount} $noun")
                    if (entry.downloadedCount > 0) append(" · ${entry.downloadedCount} downloaded")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (entry.newEpisodeCount > 0) {
            Badge { Text(text = entry.newEpisodeCount.toString()) }
        }
    }
}

/** Turns a [LibraryMessage] into snackbar text. */
private suspend fun SnackbarHostState.showMessage(message: LibraryMessage) {
    val text = when (message) {
        is LibraryMessage.Removed -> "Removed ${message.title}"
        is LibraryMessage.RefreshFinished -> with(message.summary) {
            when {
                failedTitles.isNotEmpty() && newEpisodeCount > 0 ->
                    "$newEpisodeCount new episodes · ${failedTitles.size} feeds failed"

                failedTitles.isNotEmpty() -> "Couldn't refresh ${failedTitles.joinToString()}"
                newEpisodeCount > 0 -> "$newEpisodeCount new episodes"
                else -> "No new episodes"
            }
        }
    }
    showSnackbar(text)
}

@Preview
@Composable
private fun LibraryScreenPreview() {
    BPodcatTheme {
        LibraryScreen(
            uiState = LibraryUiState(
                isLoading = false,
                podcasts = listOf(
                    PodcastWithCounts(
                        podcast = Podcast(
                            id = "1",
                            itunesId = 1209828744L,
                            title = "Podlodka Podcast",
                            author = "Егор Толстой и другие",
                            feedUrl = "https://example.com/feed.rss",
                            artworkUrl = null,
                            description = "",
                            addedAt = Instant.EPOCH,
                            lastRefreshAt = null,
                            etag = null,
                            lastModified = null,
                            autoRefresh = true,
                        ),
                        episodeCount = 412,
                        newEpisodeCount = 3,
                        downloadedCount = 2,
                    ),
                ),
            ),
            onPodcastClick = {},
            onAddClick = {},
            onRefresh = {},
            onMessageShown = {},
        )
    }
}
