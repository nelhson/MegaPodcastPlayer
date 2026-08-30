package md.borisveriga.bpodcat.feature.podcast

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.common.format.formatDuration
import md.borisveriga.bpodcat.core.common.format.formatPublishedDate
import md.borisveriga.bpodcat.core.common.format.formatRemaining
import md.borisveriga.bpodcat.core.common.format.toPlainText
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.MessageState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.component.SourceBadge
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Podcast detail screen: the show's header and its episode list.
 *
 * @param onBack invoked when the user navigates back, and automatically once the show is removed.
 * @param onEpisodePlaying invoked once a tapped episode has been handed to the player, so the caller
 *   can open the full player.
 * @param modifier layout modifier.
 * @param showBackButton false when the screen is rendered as the detail pane of a two-pane layout,
 *   where the list is still on screen and a back arrow would be misleading.
 * @param viewModel injected by Hilt.
 */
@Composable
fun PodcastDetailRoute(
    onBack: () -> Unit,
    onEpisodePlaying: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The show disappearing means it was removed; leave the screen rather than render an empty one.
    LaunchedEffect(uiState.isLoading, uiState.podcast) {
        if (!uiState.isLoading && uiState.podcast == null) onBack()
    }

    PodcastDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onEpisodeClick = { episodeId -> viewModel.playEpisode(episodeId, onEpisodePlaying) },
        onEpisodeQueue = viewModel::addToQueue,
        onEpisodeDownloadToggle = viewModel::toggleDownload,
        onRefresh = viewModel::refresh,
        onRemove = viewModel::removePodcast,
        onMessageShown = viewModel::onMessageShown,
        showBackButton = showBackButton,
        modifier = modifier,
    )
}

/**
 * Stateless podcast detail screen.
 *
 * @param uiState what to render.
 * @param onBack back handler.
 * @param onEpisodeClick episode tap handler; a tap starts playback.
 * @param onEpisodeQueue add-to-queue handler.
 * @param onEpisodeDownloadToggle download/remove handler; one action, because the button's
 *   meaning follows the episode's download state.
 * @param onRefresh manual refresh handler.
 * @param onRemove remove-show handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 * @param showBackButton whether to render the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    uiState: PodcastDetailUiState,
    onBack: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onEpisodeQueue: (String) -> Unit,
    onEpisodeDownloadToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val now = remember { Instant.now() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                is PodcastDetailMessage.Refreshed -> when (message.newEpisodeCount) {
                    0 -> "No new episodes"
                    1 -> "1 new episode"
                    else -> "${message.newEpisodeCount} new episodes"
                }

                is PodcastDetailMessage.RefreshFailed -> "Couldn't refresh: ${message.reason}"

                PodcastDetailMessage.EpisodeUnavailable ->
                    "That episode is no longer in your library"

                is PodcastDetailMessage.Queued -> "Added \"${message.title}\" to the queue"

                is PodcastDetailMessage.DownloadQueued -> if (message.waitingForWifi) {
                    "\"${message.title}\" will download on Wi-Fi"
                } else {
                    "Downloading \"${message.title}\""
                }

                is PodcastDetailMessage.DownloadRemoved ->
                    "Removed \"${message.title}\" from this device"
            },
        )
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.podcast?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to the library",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isRefreshing) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Check for new episodes",
                        )
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Remove this podcast",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val podcast = uiState.podcast
        when {
            uiState.isLoading || podcast == null -> LoadingState(
                modifier = Modifier.padding(padding),
                contentDescription = "Loading episodes",
            )

            uiState.episodes.isEmpty() -> MessageState(
                icon = Icons.Rounded.PlaylistRemove,
                title = "No episodes",
                description = if (podcast.source == PodcastSource.YOUTUBE) {
                    // A playlist that reads as empty is almost always a permissions problem, not a
                    // transient one, so "try refreshing" would send the user in the wrong direction.
                    "This playlist has no public videos we can read. Check that it is set to " +
                        "Public or Unlisted."
                } else {
                    "This feed published nothing we could read. Try refreshing."
                },
                actionLabel = "Refresh",
                onAction = onRefresh,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item { PodcastHeader(podcast = podcast) }
                if (uiState.isRefreshing) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                items(items = uiState.episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        now = now,
                        onClick = { onEpisodeClick(episode.id) },
                        onQueue = { onEpisodeQueue(episode.id) },
                        onDownloadToggle = { onEpisodeDownloadToggle(episode.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Show header: artwork, author and description.
 *
 * @param podcast the show.
 * @param modifier layout modifier.
 */
@Composable
private fun PodcastHeader(podcast: Podcast, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PodcastArtwork(url = podcast.artworkUrl, modifier = Modifier.size(96.dp))
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SourceBadge(source = podcast.source)
                Text(
                    text = podcast.author,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                // Feed descriptions are HTML fragments, often double-escaped.
                text = remember(podcast.description) { podcast.description.toPlainText() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * One episode row.
 *
 * Shows publication date, duration and — once playback has started — how much is left, which is the
 * number that actually matters when picking what to listen to next.
 *
 * @param episode the episode.
 * @param now reference time for relative date formatting.
 * @param onClick tap handler; playing the episode.
 * @param onQueue add-to-queue handler.
 * @param onDownloadToggle download/remove handler.
 * @param modifier layout modifier.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    now: Instant,
    onClick: () -> Unit,
    onQueue: () -> Unit,
    onDownloadToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (episode.isNew) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DownloadButton(episode = episode, onClick = onDownloadToggle)
            IconButton(onClick = onQueue) {
                Icon(
                    imageVector = Icons.Rounded.PlaylistAdd,
                    // Naming the episode matters here: TalkBack users reach this button out of the
                    // list's reading order, without the title next to it.
                    contentDescription = "Add ${episode.title} to the queue",
                )
            }
        }

        Text(
            text = listOfNotNull(
                formatPublishedDate(episode.publishedAt, now),
                formatRemaining(episode.durationMs, episode.positionMs)
                    ?.takeIf { episode.positionMs > 0 }
                    ?: formatDuration(episode.durationMs),
                "Played".takeIf { episode.isPlayed },
                "Downloaded".takeIf { episode.downloadState == DownloadState.COMPLETED },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (episode.positionMs > 0 && !episode.isPlayed) {
            LinearProgressIndicator(
                progress = { episode.playedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}

/**
 * The per-episode download control.
 *
 * One button with four faces rather than four controls: an episode is in exactly one download state
 * at a time, and the action always follows from it — download what is absent, retry what failed,
 * cancel what is running, remove what is finished.
 *
 * @param episode the episode whose state the button reflects.
 * @param onClick invoked on tap; the caller decides what the tap means from the same state.
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadButton(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        when (episode.downloadState) {
            DownloadState.NOT_DOWNLOADED -> DownloadIcon(
                icon = Icons.Rounded.DownloadForOffline,
                contentDescription = "Download ${episode.title}",
            )

            // Queued and downloading share a spinner; what differs is whether it advances. A
            // determinate ring on a download still waiting for Wi-Fi would sit at zero and read as
            // broken, so that case gets an indeterminate one.
            DownloadState.QUEUED -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = "Cancel the queued download of ${episode.title}"
                    },
            )

            DownloadState.DOWNLOADING -> CircularProgressIndicator(
                progress = { episode.downloadPercent / 100f },
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = "Cancel the download of ${episode.title}, " +
                            "${episode.downloadPercent.toInt()} percent complete"
                    },
            )

            DownloadState.COMPLETED -> DownloadIcon(
                icon = Icons.Rounded.CheckCircle,
                contentDescription = "Remove the download of ${episode.title}",
                tint = MaterialTheme.colorScheme.primary,
            )

            DownloadState.FAILED -> DownloadIcon(
                icon = Icons.Rounded.ErrorOutline,
                contentDescription = "Download of ${episode.title} failed. Retry",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One face of [DownloadButton].
 *
 * @param icon the glyph to show.
 * @param contentDescription what TalkBack announces; always names the episode, because this button
 *   is reachable out of the list's reading order.
 * @param tint icon colour; defaults to the local content colour.
 */
@Composable
private fun DownloadIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
}

@Preview
@Composable
private fun PodcastDetailScreenPreview() {
    BPodcatTheme {
        PodcastDetailScreen(
            uiState = PodcastDetailUiState(
                isLoading = false,
                podcast = Podcast(
                    id = "1",
                    itunesId = 1209828744L,
                    title = "Podlodka Podcast",
                    author = "Егор Толстой",
                    feedUrl = "https://example.com/feed.rss",
                    artworkUrl = null,
                    description = "Еженедельное шоу о разработке и людях в IT.",
                    addedAt = Instant.EPOCH,
                    lastRefreshAt = null,
                    etag = null,
                    lastModified = null,
                    autoRefresh = true,
                ),
                episodes = listOf(
                    Episode(
                        id = "e1",
                        podcastId = "1",
                        guid = "g1",
                        title = "Podlodka #400 – Мультиплатформа",
                        description = "",
                        audioUrl = "https://example.com/1.mp3",
                        artworkUrl = null,
                        durationMs = 5_025_000L,
                        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
                        sizeBytes = null,
                        positionMs = 1_200_000L,
                        isNew = true,
                    ),
                ),
            ),
            onBack = {},
            onEpisodeClick = {},
            onEpisodeQueue = {},
            onEpisodeDownloadToggle = {},
            onRefresh = {},
            onRemove = {},
            onMessageShown = {},
        )
    }
}
