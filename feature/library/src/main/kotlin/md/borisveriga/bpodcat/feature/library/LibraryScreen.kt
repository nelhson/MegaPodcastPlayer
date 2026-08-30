package md.borisveriga.bpodcat.feature.library

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    // Resolved in composition rather than inside the effect: `LaunchedEffect` runs outside the
    // composition, where `stringResource` is not available. `LocalResources` rather than
    // `LocalContext.current.resources`, so a configuration change invalidates the read.
    val resources = LocalResources.current

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isRefreshing) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.library_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.library_add_podcast),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(
                modifier = Modifier.padding(padding),
                contentDescription = stringResource(R.string.library_loading),
            )

            uiState.podcasts.isEmpty() -> MessageState(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.library_empty_title),
                description = stringResource(R.string.library_empty_description),
                actionLabel = stringResource(R.string.library_add_podcast),
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
            // "videos" rather than "episodes" for a playlist: it is what the user called them
            // when they added it.
            val countLabel = pluralStringResource(
                id = if (entry.podcast.source == PodcastSource.YOUTUBE) {
                    R.plurals.library_video_count
                } else {
                    R.plurals.library_episode_count
                },
                count = entry.episodeCount,
                entry.episodeCount,
            )
            Text(
                text = if (entry.downloadedCount > 0) {
                    stringResource(
                        R.string.library_counts_combined,
                        countLabel,
                        pluralStringResource(
                            R.plurals.library_downloaded_count,
                            entry.downloadedCount,
                            entry.downloadedCount,
                        ),
                    )
                } else {
                    countLabel
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

/**
 * Turns a [LibraryMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun LibraryMessage.toText(resources: Resources): String = when (this) {
    is LibraryMessage.Removed -> resources.getString(R.string.library_message_removed, title)

    is LibraryMessage.RefreshFinished -> with(summary) {
        val newEpisodes = resources.getQuantityString(
            R.plurals.library_message_new_episodes,
            newEpisodeCount,
            newEpisodeCount,
        )
        when {
            failedTitles.isNotEmpty() && newEpisodeCount > 0 -> resources.getString(
                R.string.library_message_new_and_failed,
                newEpisodes,
                resources.getQuantityString(
                    R.plurals.library_message_failed_feeds,
                    failedTitles.size,
                    failedTitles.size,
                ),
            )

            failedTitles.isNotEmpty() -> resources.getString(
                R.string.library_message_refresh_failed,
                failedTitles.joinToString(),
            )

            newEpisodeCount > 0 -> newEpisodes

            else -> resources.getString(R.string.library_message_no_new_episodes)
        }
    }
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
