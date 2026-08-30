package md.borisveriga.bpodcat.feature.downloads

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import kotlin.math.roundToInt
import md.borisveriga.bpodcat.core.common.format.formatBytes
import md.borisveriga.bpodcat.core.common.format.formatDuration
import md.borisveriga.bpodcat.core.common.format.formatPublishedDate
import md.borisveriga.bpodcat.core.common.format.formatRemaining
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * Downloads screen: everything the download stack is tracking, across all shows — finished
 * episodes, transfers in progress, downloads waiting their turn, and failures.
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
        onEpisodeRetry = viewModel::retry,
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
 * @param onEpisodeClick episode tap handler; a tap plays a finished episode. Called only for
 *   rows that are actually on the device.
 * @param onEpisodeQueue add-to-queue handler.
 * @param onEpisodeRetry retry handler for a failed download.
 * @param onEpisodeRemove delete-this-download handler; cancels the transfer when it has not
 *   finished.
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
    onEpisodeRetry: (String) -> Unit,
    onEpisodeRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
    onBrowseLibrary: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current
    val now = remember { Instant.now() }
    // Saveable so the confirmation does not vanish when the Fold 7 is opened mid-decision.
    var confirmingRemoveAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    if (confirmingRemoveAll) {
        // Deleting every download is the one action here a second tap cannot undo, so it asks first.
        AlertDialog(
            onDismissRequest = { confirmingRemoveAll = false },
            title = { Text(text = stringResource(R.string.downloads_remove_all_dialog_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.downloads_remove_all_dialog_text,
                        formatBytes(uiState.totalBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemoveAll = false
                        onRemoveAll()
                    },
                ) {
                    Text(text = stringResource(R.string.downloads_remove_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoveAll = false }) {
                    Text(text = stringResource(R.string.downloads_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.downloads_title)) },
                actions = {
                    if (uiState.downloads.isNotEmpty()) {
                        IconButton(onClick = { confirmingRemoveAll = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.downloads_remove_all),
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
                contentDescription = stringResource(R.string.downloads_loading),
            )

            uiState.downloads.isEmpty() -> EmptyState(
                icon = Icons.Rounded.DownloadDone,
                title = stringResource(R.string.downloads_empty_title),
                description = stringResource(R.string.downloads_empty_description),
                actionLabel = stringResource(R.string.downloads_empty_action),
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
                        episodeCount = uiState.completedCount,
                        totalBytes = uiState.totalBytes,
                    )
                    HorizontalDivider()
                }
                items(items = uiState.downloads, key = { it.episode.id }) { download ->
                    DownloadRow(
                        download = download,
                        now = now,
                        unmeteredOnly = uiState.unmeteredOnly,
                        onClick = { onEpisodeClick(download.episode.id) },
                        onRetry = { onEpisodeRetry(download.episode.id) },
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
 * Counts only what has finished downloading, even though the list below it also shows transfers and
 * failures: this line is about disk, and a half-finished file is not something the user can free by
 * deleting a finished episode.
 *
 * @param episodeCount how many episodes are stored on the device.
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
        text = stringResource(
            R.string.downloads_storage_summary,
            pluralStringResource(R.plurals.downloads_episode_count, episodeCount, episodeCount),
            formatBytes(totalBytes),
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * One row of the download list, in whichever state the episode is in.
 *
 * Carries the show's name and artwork, which the podcast detail screen's row can leave out: here
 * the episodes come from every show at once, so the episode title alone does not say what you are
 * looking at.
 *
 * What a tap does follows the state, because that is the only thing a tap could sensibly mean.
 * A finished episode plays. A failed one retries — it is the action the row exists to offer. A
 * transfer in progress or one waiting its turn does nothing at all: there is no local audio to play
 * and starting a stream instead would spend mobile data the user never asked to spend. Cancelling
 * stays where it always was, on the trailing button.
 *
 * @param download the episode and its show.
 * @param now reference time for relative date formatting.
 * @param unmeteredOnly whether downloads wait for Wi-Fi, so a waiting row can say which kind of
 *   waiting it is doing.
 * @param onClick tap handler for a finished episode; playing it.
 * @param onRetry tap handler for a failed download.
 * @param onQueue add-to-queue handler.
 * @param onRemove delete-or-cancel handler.
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadRow(
    download: EpisodeWithShow,
    now: Instant,
    unmeteredOnly: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onQueue: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = download.episode
    val isCompleted = episode.downloadState == DownloadState.COMPLETED
    val isFailed = episode.downloadState == DownloadState.FAILED

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    isCompleted -> Modifier.clickable(onClick = onClick)

                    isFailed -> Modifier.clickable(onClick = onRetry)

                    // No `clickable` at all rather than one that does nothing: an inert row should
                    // not ripple, and TalkBack should not announce it as a button.
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PodcastArtwork(url = download.artworkUrl, size = ArtworkSize.Row)

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
                    // Only meaningful once the file is whole; mid-transfer it would read as a size
                    // that keeps changing, next to a percentage that already says the same thing.
                    episode.downloadedBytes.takeIf { isCompleted && it > 0L }?.let(::formatBytes),
                    stringResource(R.string.downloads_played).takeIf { isCompleted && episode.isPlayed },
                ).joinToString(stringResource(R.string.downloads_metadata_separator)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            DownloadStateLabel(
                state = episode.downloadState,
                downloadPercent = episode.downloadPercent,
                unmeteredOnly = unmeteredOnly,
            )

            // The playback position bar, not the download one — only a finished episode has a
            // position to be part-way through.
            if (isCompleted && episode.positionMs > 0 && !episode.isPlayed) {
                LinearProgressIndicator(
                    progress = { episode.playedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Nothing to queue until the audio is on the device.
            if (isCompleted) {
                IconButton(onClick = onQueue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        // These buttons are reached out of the list's reading order, so each one
                        // names the episode it acts on.
                        contentDescription = stringResource(
                            R.string.downloads_queue_episode,
                            episode.title,
                        ),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    // Same button, two honest descriptions: deleting audio and calling off a
                    // transfer are not the same thing to someone who cannot see the row.
                    contentDescription = stringResource(
                        if (isCompleted) {
                            R.string.downloads_remove_episode
                        } else {
                            R.string.downloads_cancel_episode
                        },
                        episode.title,
                    ),
                )
            }
        }
    }
}

/**
 * The line that says what is happening to a download, for every state except a finished one.
 *
 * A completed episode says nothing here: the row above it — its size, its position, whether it has
 * been played — already describes it, and "Downloaded" on every row of a screen called Downloads is
 * noise. Everything else earns a line, because until now none of these states appeared anywhere in
 * the app at all.
 *
 * @param state the episode's download state.
 * @param downloadPercent progress in `0f..100f`, meaningful while [DownloadState.DOWNLOADING].
 * @param unmeteredOnly whether downloads wait for Wi-Fi, which is usually the answer to "why is
 *   this still waiting".
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadStateLabel(
    state: DownloadState,
    downloadPercent: Float,
    unmeteredOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DownloadState.COMPLETED, DownloadState.NOT_DOWNLOADED -> Unit

        DownloadState.DOWNLOADING -> Column(modifier = modifier.padding(top = 6.dp)) {
            Text(
                text = stringResource(
                    R.string.downloads_state_downloading,
                    downloadPercent.roundToInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                // Media3 reports 0..100; the indicator wants 0..1. Coerced because a feed that
                // over-reports its length can push the figure past 100 and a bar wider than its
                // track looks like a rendering bug rather than a rounding one.
                progress = { (downloadPercent / PERCENT).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }

        DownloadState.QUEUED -> Text(
            text = stringResource(
                if (unmeteredOnly) {
                    R.string.downloads_state_queued_wifi
                } else {
                    R.string.downloads_state_queued
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 4.dp),
        )

        // The error colour rather than a plain label: this is the one row on the screen that is
        // asking to be dealt with, and the text says what tapping it will do.
        DownloadState.FAILED -> Text(
            text = stringResource(R.string.downloads_state_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Turns a [DownloadsMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun DownloadsMessage.toText(resources: Resources): String = when (this) {
    is DownloadsMessage.Removed ->
        resources.getString(R.string.downloads_message_removed, title)

    is DownloadsMessage.RemovedAll ->
        resources.getQuantityString(R.plurals.downloads_message_removed_all, count, count)

    is DownloadsMessage.Queued ->
        resources.getString(R.string.downloads_message_queued, title)

    is DownloadsMessage.RetryQueued -> resources.getString(
        if (waitingForWifi) {
            R.string.downloads_message_retry_queued_wifi
        } else {
            R.string.downloads_message_retry_queued
        },
        title,
    )

    DownloadsMessage.EpisodeUnavailable ->
        resources.getString(R.string.downloads_message_unavailable)
}

@Preview
@Composable
private fun DownloadsScreenPreview() {
    BPodcatTheme {
        DownloadsScreen(
            uiState = DownloadsUiState(
                isLoading = false,
                completedCount = 1,
                totalBytes = 90_000_000L,
                unmeteredOnly = true,
                // One row per state, in the order the query returns them, because the states are
                // the whole point of this screen and only a preview shows all four at once.
                downloads = listOf(
                    previewDownload(
                        id = "e1",
                        title = "Podlodka #402 – Сети",
                        state = DownloadState.FAILED,
                    ),
                    previewDownload(
                        id = "e2",
                        title = "Podlodka #401 – Архитектура",
                        state = DownloadState.DOWNLOADING,
                        downloadPercent = 42f,
                    ),
                    previewDownload(
                        id = "e3",
                        title = "Podlodka #400.5 – Вопросы",
                        state = DownloadState.QUEUED,
                    ),
                    previewDownload(
                        id = "e4",
                        title = "Podlodka #400 – Мультиплатформа",
                        state = DownloadState.COMPLETED,
                        downloadedBytes = 90_000_000L,
                        downloadPercent = 100f,
                        positionMs = 1_200_000L,
                    ),
                ),
            ),
            onEpisodeClick = {},
            onEpisodeQueue = {},
            onEpisodeRetry = {},
            onEpisodeRemove = {},
            onRemoveAll = {},
            onBrowseLibrary = {},
            onMessageShown = {},
        )
    }
}

/**
 * One preview row in a given download state.
 *
 * A builder rather than four spelled-out literals: the fields that differ between the states are
 * three, and the twenty that do not would otherwise bury them.
 *
 * @param id the episode id, which also keys the list.
 * @param title the episode title.
 * @param state the download state to render.
 * @param downloadedBytes bytes written so far.
 * @param downloadPercent progress in `0f..100f`.
 * @param positionMs playback position, for the part-played bar on a finished episode.
 */
private fun previewDownload(
    id: String,
    title: String,
    state: DownloadState,
    downloadedBytes: Long = 0L,
    downloadPercent: Float = 0f,
    positionMs: Long = 0L,
) = EpisodeWithShow(
    episode = Episode(
        id = id,
        podcastId = "1",
        guid = id,
        title = title,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 5_025_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = 90_000_000L,
        positionMs = positionMs,
        downloadState = state,
        downloadedBytes = downloadedBytes,
        downloadPercent = downloadPercent,
    ),
    showTitle = "Podlodka Podcast",
    showArtworkUrl = null,
)

/** Media3 reports download progress on a 0..100 scale; the progress indicator wants 0..1. */
private const val PERCENT = 100f
