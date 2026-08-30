package md.borisveriga.bpodcat.feature.podcast

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
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

    // Lifecycle-tied rather than a one-shot in the view model's `init`, so that coming back from the
    // full player — or from the app having been in the background for an hour — checks the feed
    // again. The staleness window in the view model is what keeps that cheap.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
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
 * @param onRefresh pull-to-refresh handler; also the empty state's action.
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
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current
    val now = remember { Instant.now() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
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
                                contentDescription = stringResource(R.string.podcast_back),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.podcast_remove),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val podcast = uiState.podcast
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The automatic refresh's entire footprint: a line under the title, nothing that moves
            // the list the user is already reading.
            if (uiState.isAutoRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                uiState.isLoading || podcast == null -> LoadingState(
                    contentDescription = stringResource(R.string.podcast_loading),
                )

                // The empty state keeps an explicit refresh action rather than the gesture: there is
                // no list here for a pull to act on, and an empty show is exactly when someone wants
                // to press something and find out why.
                uiState.episodes.isEmpty() -> MessageState(
                    icon = Icons.Rounded.PlaylistRemove,
                    title = stringResource(R.string.podcast_empty_title),
                    description = stringResource(
                        if (podcast.source == PodcastSource.YOUTUBE) {
                            R.string.podcast_empty_description_youtube
                        } else {
                            R.string.podcast_empty_description_rss
                        },
                    ),
                    actionLabel = stringResource(R.string.podcast_empty_action),
                    onAction = onRefresh,
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item { PodcastHeader(podcast = podcast) }
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
                    contentDescription = stringResource(R.string.podcast_queue_episode, episode.title),
                )
            }
        }

        Text(
            text = listOfNotNull(
                formatPublishedDate(episode.publishedAt, now),
                formatRemaining(episode.durationMs, episode.positionMs)
                    ?.takeIf { episode.positionMs > 0 }
                    ?: formatDuration(episode.durationMs),
                stringResource(R.string.podcast_episode_played).takeIf { episode.isPlayed },
                stringResource(R.string.podcast_episode_downloaded)
                    .takeIf { episode.downloadState == DownloadState.COMPLETED },
            ).joinToString(stringResource(R.string.podcast_metadata_separator)),
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
    // Built here rather than inside `semantics`, which is not a composable scope.
    val cancelQueuedDescription =
        stringResource(R.string.podcast_cancel_queued_download, episode.title)
    val cancelDescription = pluralStringResource(
        R.plurals.podcast_cancel_download,
        episode.downloadPercent.toInt(),
        episode.title,
        episode.downloadPercent.toInt(),
    )

    IconButton(onClick = onClick, modifier = modifier) {
        when (episode.downloadState) {
            DownloadState.NOT_DOWNLOADED -> DownloadIcon(
                icon = Icons.Rounded.DownloadForOffline,
                contentDescription = stringResource(
                    R.string.podcast_download_episode,
                    episode.title,
                ),
            )

            // Queued and downloading share a spinner; what differs is whether it advances. A
            // determinate ring on a download still waiting for Wi-Fi would sit at zero and read as
            // broken, so that case gets an indeterminate one.
            DownloadState.QUEUED -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = cancelQueuedDescription
                    },
            )

            DownloadState.DOWNLOADING -> CircularProgressIndicator(
                progress = { episode.downloadPercent / 100f },
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = cancelDescription
                    },
            )

            DownloadState.COMPLETED -> DownloadIcon(
                icon = Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.podcast_remove_download, episode.title),
                tint = MaterialTheme.colorScheme.primary,
            )

            DownloadState.FAILED -> DownloadIcon(
                icon = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.podcast_retry_download, episode.title),
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

/**
 * Turns a [PodcastDetailMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun PodcastDetailMessage.toText(resources: Resources): String = when (this) {
    is PodcastDetailMessage.Refreshed -> if (newEpisodeCount == 0) {
        resources.getString(R.string.podcast_message_no_new_episodes)
    } else {
        resources.getQuantityString(
            R.plurals.podcast_message_new_episodes,
            newEpisodeCount,
            newEpisodeCount,
        )
    }

    is PodcastDetailMessage.RefreshFailed ->
        resources.getString(R.string.podcast_message_refresh_failed, reason)

    PodcastDetailMessage.EpisodeUnavailable ->
        resources.getString(R.string.podcast_message_episode_unavailable)

    is PodcastDetailMessage.Queued ->
        resources.getString(R.string.podcast_message_queued, title)

    is PodcastDetailMessage.DownloadQueued -> resources.getString(
        if (waitingForWifi) {
            R.string.podcast_message_download_waiting_for_wifi
        } else {
            R.string.podcast_message_downloading
        },
        title,
    )

    is PodcastDetailMessage.DownloadRemoved ->
        resources.getString(R.string.podcast_message_download_removed, title)
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
