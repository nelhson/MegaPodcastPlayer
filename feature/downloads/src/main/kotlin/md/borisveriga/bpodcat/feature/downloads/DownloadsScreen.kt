package md.borisveriga.bpodcat.feature.downloads

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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.common.format.formatBytes
import md.borisveriga.bpodcat.core.common.format.formatDuration
import md.borisveriga.bpodcat.core.common.format.formatPublishedDate
import md.borisveriga.bpodcat.core.common.format.formatRemaining
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.MessageState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.DownloadedEpisode
import md.borisveriga.bpodcat.core.model.Episode

/**
 * Downloads screen: every episode stored on the device, across all shows.
 *
 * @param onEpisodePlaying invoked once a tapped episode has been handed to the player, so the
 *   caller can open the full player.
 * @param onBrowseLibrary invoked from the empty state, to send the user somewhere they can download
 *   something.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun DownloadsRoute(
    onEpisodePlaying: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DownloadsScreen(
        uiState = uiState,
        onEpisodeClick = { episodeId -> viewModel.play(episodeId, onEpisodePlaying) },
        onEpisodeQueue = viewModel::addToQueue,
        onEpisodeRemove = viewModel::remove,
        onRemoveAll = viewModel::removeAll,
        onBrowseLibrary = onBrowseLibrary,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless downloads screen.
 *
 * @param uiState what to render.
 * @param onEpisodeClick episode tap handler; a tap starts playback.
 * @param onEpisodeQueue add-to-queue handler.
 * @param onEpisodeRemove delete-this-download handler.
 * @param onRemoveAll delete-everything handler; the screen confirms before calling it.
 * @param onBrowseLibrary empty-state action handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    uiState: DownloadsUiState,
    onEpisodeClick: (String) -> Unit,
    onEpisodeQueue: (String) -> Unit,
    onEpisodeRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onBrowseLibrary: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val now = remember { Instant.now() }
    // Saveable so the confirmation does not vanish when the Fold 7 is opened mid-decision.
    var confirmingRemoveAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                is DownloadsMessage.Removed -> "Removed \"${message.title}\" from this device"

                is DownloadsMessage.RemovedAll -> when (message.count) {
                    1 -> "Removed 1 download"
                    else -> "Removed ${message.count} downloads"
                }

                is DownloadsMessage.Queued -> "Added \"${message.title}\" to the queue"

                DownloadsMessage.EpisodeUnavailable ->
                    "That episode is no longer in your library"
            },
        )
        onMessageShown()
    }

    if (confirmingRemoveAll) {
        // Deleting every download is the one action here a second tap cannot undo, so it asks first.
        AlertDialog(
            onDismissRequest = { confirmingRemoveAll = false },
            title = { Text(text = "Remove all downloads?") },
            text = {
                Text(
                    text = "This frees ${formatBytes(uiState.totalBytes)}. " +
                        "The episodes stay in your library and can be downloaded again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemoveAll = false
                        onRemoveAll()
                    },
                ) {
                    Text(text = "Remove all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoveAll = false }) { Text(text = "Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = "Downloads") },
                actions = {
                    if (uiState.downloads.isNotEmpty()) {
                        IconButton(onClick = { confirmingRemoveAll = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Remove all downloads",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(
                modifier = Modifier.padding(padding),
                contentDescription = "Loading your downloads",
            )

            uiState.downloads.isEmpty() -> MessageState(
                icon = Icons.Rounded.DownloadDone,
                title = "Nothing downloaded",
                description = "Episodes you download appear here and play without a connection.",
                actionLabel = "Go to your library",
                onAction = onBrowseLibrary,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item {
                    StorageSummary(
                        episodeCount = uiState.downloads.size,
                        totalBytes = uiState.totalBytes,
                    )
                    HorizontalDivider()
                }
                items(items = uiState.downloads, key = { it.episode.id }) { download ->
                    DownloadRow(
                        download = download,
                        now = now,
                        onClick = { onEpisodeClick(download.episode.id) },
                        onQueue = { onEpisodeQueue(download.episode.id) },
                        onRemove = { onEpisodeRemove(download.episode.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * How much of the device the downloads occupy.
 *
 * Shown here as well as in Settings' storage section: this number is what makes someone decide to
 * delete something, so it belongs next to the things they would delete.
 *
 * @param episodeCount how many episodes are stored.
 * @param totalBytes what they occupy.
 * @param modifier layout modifier.
 */
@Composable
private fun StorageSummary(
    episodeCount: Int,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildString {
            append(if (episodeCount == 1) "1 episode" else "$episodeCount episodes")
            append(" · ")
            append(formatBytes(totalBytes))
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * One downloaded episode.
 *
 * Carries the show's name and artwork, which the podcast detail screen's row can leave out: here
 * the episodes come from every show at once, so the episode title alone does not say what you are
 * looking at.
 *
 * @param download the episode and its show.
 * @param now reference time for relative date formatting.
 * @param onClick tap handler; playing the episode.
 * @param onQueue add-to-queue handler.
 * @param onRemove delete-this-download handler.
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadRow(
    download: DownloadedEpisode,
    now: Instant,
    onClick: () -> Unit,
    onQueue: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = download.episode

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PodcastArtwork(url = download.artworkUrl, modifier = Modifier.size(56.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.showTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    formatPublishedDate(episode.publishedAt, now),
                    formatRemaining(episode.durationMs, episode.positionMs)
                        ?.takeIf { episode.positionMs > 0 }
                        ?: formatDuration(episode.durationMs),
                    episode.downloadedBytes.takeIf { it > 0L }?.let(::formatBytes),
                    "Played".takeIf { episode.isPlayed },
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onQueue) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    // These buttons are reached out of the list's reading order, so each one names
                    // the episode it acts on.
                    contentDescription = "Add ${episode.title} to the queue",
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Remove the download of ${episode.title}",
                )
            }
        }
    }
}

@Preview
@Composable
private fun DownloadsScreenPreview() {
    BPodcatTheme {
        DownloadsScreen(
            uiState = DownloadsUiState(
                isLoading = false,
                totalBytes = 148_000_000L,
                downloads = listOf(
                    DownloadedEpisode(
                        episode = Episode(
                            id = "e1",
                            podcastId = "1",
                            guid = "g1",
                            title = "Podlodka #400 – Мультиплатформа",
                            description = "",
                            audioUrl = "https://example.com/1.mp3",
                            artworkUrl = null,
                            durationMs = 5_025_000L,
                            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
                            sizeBytes = 90_000_000L,
                            positionMs = 1_200_000L,
                            downloadState = DownloadState.COMPLETED,
                            downloadedBytes = 90_000_000L,
                            downloadPercent = 100f,
                        ),
                        showTitle = "Podlodka Podcast",
                        showArtworkUrl = null,
                    ),
                ),
            ),
            onEpisodeClick = {},
            onEpisodeQueue = {},
            onEpisodeRemove = {},
            onRemoveAll = {},
            onBrowseLibrary = {},
            onMessageShown = {},
        )
    }
}
