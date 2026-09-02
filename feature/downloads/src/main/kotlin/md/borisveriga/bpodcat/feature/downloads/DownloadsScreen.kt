package md.borisveriga.bpodcat.feature.downloads

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import md.borisveriga.bpodcat.core.designsystem.component.BPodcatLargeTopAppBar
import md.borisveriga.bpodcat.core.designsystem.component.DownloadButton
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.SelectionToolbar
import md.borisveriga.bpodcat.core.designsystem.component.SwipeAction
import md.borisveriga.bpodcat.core.designsystem.component.SwipeActionsRow
import md.borisveriga.bpodcat.core.designsystem.component.asAccessibilityActions
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
        onEpisodeMarkPlayed = viewModel::markPlayed,
        onRemoveSelected = viewModel::removeSelected,
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
 * @param onEpisodeMarkPlayed marks one episode played, from its swipe actions.
 * @param onRemoveSelected delete-these-downloads handler; the screen confirms first.
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
    onEpisodeMarkPlayed: (String) -> Unit,
    onRemoveSelected: (Set<String>) -> Unit,
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Saveable so neither the selection nor the confirmation vanishes when the Fold 7 is opened
    // mid-decision.
    var selection by rememberSaveable(saver = selectionSaver) { mutableStateOf(emptySet<String>()) }
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }

    // Rows can leave the list under the selection — a transfer finishing does not change an id, but
    // a keep-limit sweep can remove one — so the selection is pruned to what is actually on screen.
    // Without this, "Remove 3" could act on an episode the user can no longer see.
    val selectedIds = selection intersect uiState.downloads.map { it.episode.id }.toSet()

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    // The selection is the innermost thing on screen while it exists, so back clears it rather than
    // leaving the tab with rows still highlighted.
    BackHandler(enabled = selectedIds.isNotEmpty()) { selection = emptySet() }

    if (confirmingRemoval) {
        RemovalDialog(
            count = selectedIds.size,
            freedBytes = uiState.downloads
                .filter { it.episode.id in selectedIds }
                .sumOf { it.episode.downloadedBytes },
            onConfirm = {
                confirmingRemoval = false
                onRemoveSelected(selectedIds)
                selection = emptySet()
            },
            onDismiss = { confirmingRemoval = false },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BPodcatLargeTopAppBar(
                title = stringResource(R.string.downloads_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> LoadingState(
                    contentDescription = stringResource(R.string.downloads_loading),
                )

                uiState.downloads.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.DownloadDone,
                    title = stringResource(R.string.downloads_empty_title),
                    description = stringResource(R.string.downloads_empty_description),
                    actionLabel = stringResource(R.string.downloads_empty_action),
                    onAction = onBrowseLibrary,
                )

                else -> DownloadList(
                    uiState = uiState,
                    now = now,
                    selectedIds = selectedIds,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodeRetry = onEpisodeRetry,
                    onEpisodeQueue = onEpisodeQueue,
                    onEpisodeRemove = onEpisodeRemove,
                    onEpisodeMarkPlayed = onEpisodeMarkPlayed,
                    onToggleSelected = { id ->
                        selection = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                )
            }

            SelectionToolbar(
                visible = selectedIds.isNotEmpty(),
                label = pluralStringResource(
                    R.plurals.downloads_selected,
                    selectedIds.size,
                    selectedIds.size,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(BPodcatTheme.spacing.lg),
            ) {
                IconButton(
                    onClick = { selection = uiState.downloads.map { it.episode.id }.toSet() },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SelectAll,
                        contentDescription = stringResource(R.string.downloads_select_all),
                    )
                }
                IconButton(onClick = { confirmingRemoval = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.downloads_remove_selected),
                    )
                }
                IconButton(onClick = { selection = emptySet() }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.downloads_clear_selection),
                    )
                }
            }
        }
    }
}

/**
 * The scrolling body: the storage card, then a row per tracked episode.
 *
 * @param uiState what to render.
 * @param now reference time for relative date formatting.
 * @param selectedIds the current selection.
 * @param onEpisodeClick tap handler for a finished episode.
 * @param onEpisodeRetry tap handler for a failed download.
 * @param onEpisodeQueue add-to-queue handler.
 * @param onEpisodeRemove delete-or-cancel handler.
 * @param onEpisodeMarkPlayed marks one episode played.
 * @param onToggleSelected adds or removes one row from the selection.
 */
@Composable
private fun DownloadList(
    uiState: DownloadsUiState,
    now: Instant,
    selectedIds: Set<String>,
    onEpisodeClick: (String) -> Unit,
    onEpisodeRetry: (String) -> Unit,
    onEpisodeQueue: (String) -> Unit,
    onEpisodeRemove: (String) -> Unit,
    onEpisodeMarkPlayed: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
) {
    val resources = LocalResources.current
    val selecting = selectedIds.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LIST_BOTTOM_PADDING),
    ) {
        item(key = STORAGE_CARD_KEY) {
            StorageCard(
                episodeCount = uiState.completedCount,
                totalBytes = uiState.totalBytes,
                freeBytes = uiState.freeBytes,
            )
        }

        items(items = uiState.downloads, key = { it.episode.id }) { download ->
            val episode = download.episode
            val isCompleted = episode.downloadState == DownloadState.COMPLETED
            val isFailed = episode.downloadState == DownloadState.FAILED

            // "Delete" rather than "remove from list": this screen is a view of what is on disk,
            // so what a swipe offers to throw away is the file, and the episode stays in its
            // show. Marking played is offered only where it means something — an episode still
            // downloading has not been listened to.
            val actions = listOfNotNull(
                SwipeAction(
                    icon = Icons.Rounded.DoneAll,
                    label = stringResource(R.string.downloads_action_mark_played),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { onEpisodeMarkPlayed(episode.id) },
                ).takeIf { isCompleted },
                SwipeAction(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.downloads_action_delete),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onEpisodeRemove(episode.id) },
                ),
            )

            SwipeActionsRow(
                actions = actions,
                // A selection owns every row while it exists: a swipe during one would be a
                // gesture aimed at a single episode in a mode that is about several.
                enabled = !selecting,
            ) {
                EpisodeRow(
                    modifier = Modifier.semantics {
                        customActions = actions.asAccessibilityActions()
                    },
                    title = episode.title,
                    showTitle = download.showTitle,
                    metadata = download.metadataLine(now, resources, uiState.unmeteredOnly),
                    artworkUrl = download.artworkUrl,
                    isPlayed = isCompleted && episode.isPlayed,
                    playedFraction = if (isCompleted) episode.playedFraction else 0f,
                    isSelected = episode.id in selectedIds,
                    // What a tap does follows the state, because that is the only thing a tap could
                    // sensibly mean: a finished episode plays, a failed one retries, and a transfer in
                    // progress does nothing at all — there is no local audio to play, and streaming
                    // instead would spend mobile data nobody asked to spend. While a selection exists,
                    // every row means "add me to it" instead; that is what selection mode is.
                    onClick = when {
                        selecting -> ({ onToggleSelected(episode.id) })
                        isCompleted -> ({ onEpisodeClick(episode.id) })
                        isFailed -> ({ onEpisodeRetry(episode.id) })
                        else -> null
                    },
                    onLongClick = { onToggleSelected(episode.id) },
                    longClickLabel = stringResource(R.string.downloads_select),
                    trailing = {
                        // Nothing to queue until the audio is on the device, and nothing to press at
                        // all while the row is standing in for a selection.
                        if (isCompleted && !selecting) {
                            IconButton(onClick = { onEpisodeQueue(episode.id) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                    // These buttons are reached out of the list's reading order, so
                                    // each one names the episode it acts on.
                                    contentDescription = stringResource(
                                        R.string.downloads_queue_episode,
                                        episode.title,
                                    ),
                                )
                            }
                        }
                        if (!selecting) {
                            DownloadButton(
                                state = episode.downloadState,
                                progressPercent = episode.downloadPercent,
                                // The button means what its own label says, which is not the same
                                // action in every state: a failure asks to be tried again, and
                                // anything else asks to be given back. Removing a failed download —
                                // the one case neither control covers — is what the selection is for.
                                onClick = {
                                    if (isFailed) onEpisodeRetry(episode.id) else onEpisodeRemove(episode.id)
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * What the downloads cost, drawn against what is left.
 *
 * The figure used to be one line of text. A number on its own does not answer the question someone
 * opens this screen with — "can I keep doing this?" — and the bar does, at a glance, without the
 * user having to know how big their phone is.
 *
 * Counts only what has finished downloading, even though the list below also shows transfers and
 * failures: this card is about disk, and a half-finished file is not space a user can free by
 * deleting a finished episode.
 *
 * @param episodeCount how many episodes are stored on the device.
 * @param totalBytes what they occupy.
 * @param freeBytes what is left on the volume; zero when it could not be read, which draws the
 *   figures without the bar rather than a bar that is a guess.
 * @param modifier layout modifier.
 */
@Composable
private fun StorageCard(
    episodeCount: Int,
    totalBytes: Long,
    freeBytes: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = BPodcatTheme.spacing.screenHorizontal,
                vertical = BPodcatTheme.spacing.sm,
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(BPodcatTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(
                    R.string.downloads_storage_summary,
                    pluralStringResource(
                        R.plurals.downloads_episode_count,
                        episodeCount,
                        episodeCount,
                    ),
                    formatBytes(totalBytes),
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            if (freeBytes > 0L) {
                val usedFraction =
                    (totalBytes.toFloat() / (totalBytes + freeBytes).toFloat()).coerceIn(0f, 1f)
                val barDescription = stringResource(
                    R.string.downloads_storage_bar_description,
                    formatBytes(totalBytes),
                    formatBytes(freeBytes),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .clip(BPodcatTheme.shapes.pill)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        // The bar is a picture of the two figures under it; announcing it as a
                        // third, unlabelled thing would only get in the way.
                        .clearAndSetSemantics { contentDescription = barDescription },
                ) {
                    if (usedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(usedFraction)
                                // A minimum width, because the honest fraction is often a rounding
                                // error against a 128 GB phone: a bar drawn with nothing in it
                                // says "nothing stored", when what is true is "not much".
                                .widthIn(min = MIN_SEGMENT_WIDTH)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            R.string.downloads_storage_used,
                            formatBytes(totalBytes),
                        ),
                        style = BPodcatTheme.type.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.downloads_storage_free,
                            formatBytes(freeBytes),
                        ),
                        style = BPodcatTheme.type.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The confirmation shown before a selection is deleted.
 *
 * Deleting several episodes is the one action here that no second tap undoes, so it asks first —
 * and says how much space it will actually free, which is usually why it is being done.
 *
 * @param count how many episodes are selected.
 * @param freedBytes what deleting them gives back.
 * @param onConfirm proceed.
 * @param onDismiss cancel.
 */
@Composable
private fun RemovalDialog(
    count: Int,
    freedBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = pluralStringResource(R.plurals.downloads_remove_dialog_title, count, count))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.downloads_remove_dialog_text,
                    formatBytes(freedBytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.downloads_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.downloads_cancel))
            }
        },
    )
}

/**
 * The metadata line under a download's title.
 *
 * A finished episode describes itself — when it came out, how long it is, what it takes up, whether
 * it has been played. Everything else describes what is *happening* to it instead, because until
 * the transfer is done that is the only fact about the row worth the space, and a state that
 * appears nowhere is a download the user cannot tell has stalled.
 *
 * @param now reference time for relative date formatting.
 * @param resources for the strings and plurals.
 * @param unmeteredOnly whether downloads wait for Wi-Fi, which is usually the answer to "why is
 *   this still waiting".
 * @return the line to show.
 */
private fun EpisodeWithShow.metadataLine(
    now: Instant,
    resources: Resources,
    unmeteredOnly: Boolean,
): String = when (episode.downloadState) {
    DownloadState.DOWNLOADING -> resources.getString(
        R.string.downloads_state_downloading,
        episode.downloadPercent.roundToInt(),
    )

    DownloadState.QUEUED -> resources.getString(
        if (unmeteredOnly) {
            R.string.downloads_state_queued_wifi
        } else {
            R.string.downloads_state_queued
        },
    )

    DownloadState.FAILED -> resources.getString(R.string.downloads_state_failed)

    DownloadState.COMPLETED, DownloadState.NOT_DOWNLOADED -> listOfNotNull(
        formatPublishedDate(episode.publishedAt, now),
        formatRemaining(episode.durationMs, episode.positionMs)
            ?.takeIf { episode.positionMs > 0 }
            ?: formatDuration(episode.durationMs),
        // Only meaningful once the file is whole; mid-transfer it would read as a size that keeps
        // changing, next to a percentage that already says the same thing.
        episode.downloadedBytes.takeIf { it > 0L }?.let(::formatBytes),
        resources.getString(R.string.downloads_played).takeIf { episode.isPlayed },
    ).joinToString(resources.getString(R.string.downloads_metadata_separator))
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

/**
 * Keeps the selection across a configuration change.
 *
 * A [Set] is not something [rememberSaveable] can store on its own; the ids are, and a list of them
 * is all the selection ever was.
 */
private val selectionSaver = listSaver<MutableState<Set<String>>, String>(
    save = { state -> state.value.toList() },
    restore = { ids -> mutableStateOf(ids.toSet()) },
)

private const val STORAGE_CARD_KEY = "storage-card"

/** Room under the last row for the floating selection bar, which is drawn over the list. */
private val LIST_BOTTOM_PADDING = 88.dp
private val BAR_HEIGHT = 10.dp

/** The narrowest the stored segment is drawn at, so a small library still marks the bar. */
private val MIN_SEGMENT_WIDTH = 12.dp

@Preview
@Composable
private fun DownloadsScreenPreview() {
    BPodcatTheme {
        DownloadsScreen(
            uiState = DownloadsUiState(
                isLoading = false,
                completedCount = 1,
                totalBytes = 90_000_000L,
                freeBytes = 4_000_000_000L,
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
            onEpisodeMarkPlayed = {},
            onRemoveSelected = {},
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
