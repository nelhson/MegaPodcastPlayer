package md.borisveriga.bpodcat.feature.downloads

import android.content.res.Resources
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.SwipeAction
import md.borisveriga.bpodcat.core.designsystem.component.SwipeActionsRow
import md.borisveriga.bpodcat.core.designsystem.component.asAccessibilityActions
import md.borisveriga.bpodcat.core.designsystem.reorder.ReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.moveActions
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.reorderableLongPressDrag
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
        onEpisodeRetry = viewModel::retry,
        onEpisodeRemove = viewModel::remove,
        onEpisodeQueue = viewModel::addToQueue,
        onMove = viewModel::move,
        onRefresh = viewModel::refresh,
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
 * @param onEpisodeRetry retry handler for a failed download.
 * @param onEpisodeRemove delete-this-download handler; cancels the transfer when it has not
 *   finished. A finished episode is confirmed first by the screen; a transfer is not.
 * @param onEpisodeQueue adds an episode to the end of the play queue.
 * @param onMove applies a completed drag. Takes the ids on screen in the order they were in
 *   *before* the gesture, then the two positions within them: one gesture is one edit, and the
 *   list is re-sorted by the download stack often enough that indices alone would go stale.
 * @param onRefresh pull-to-refresh handler.
 * @param onBrowseLibrary empty-state action handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    uiState: DownloadsUiState,
    onEpisodeClick: (String) -> Unit,
    onEpisodeRetry: (String) -> Unit,
    onEpisodeRemove: (String) -> Unit,
    onEpisodeQueue: (String) -> Unit,
    onMove: (List<String>, Int, Int) -> Unit,
    onRefresh: () -> Unit,
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
    // Saveable so a decision half-made does not vanish when the Fold 7 is opened mid-thought. The
    // id rather than the episode: the row it names is re-read from the list below, which is what
    // makes a confirmation for an episode that has since gone resolve to no dialog at all.
    var pendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    val pendingRemoval = uiState.downloads.firstOrNull { it.episode.id == pendingRemovalId }
    if (pendingRemoval != null) {
        RemovalDialog(
            title = pendingRemoval.episode.title,
            freedBytes = pendingRemoval.episode.downloadedBytes,
            onConfirm = {
                pendingRemovalId = null
                onEpisodeRemove(pendingRemoval.episode.id)
            },
            onDismiss = { pendingRemovalId = null },
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

                // The gesture needs something scrollable under it, which is why the empty state
                // above is outside it: there is nothing to pull.
                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    DownloadList(
                        uiState = uiState,
                        now = now,
                        onEpisodeClick = onEpisodeClick,
                        onEpisodeRetry = onEpisodeRetry,
                        onEpisodeQueue = onEpisodeQueue,
                        onMove = onMove,
                        // A finished episode is a file the user would have to fetch again, so it
                        // asks first. A transfer that has not finished is not: calling it off is
                        // exactly what the ring on the same row already does with one tap.
                        onEpisodeRemove = { download ->
                            if (download.episode.downloadState == DownloadState.COMPLETED) {
                                pendingRemovalId = download.episode.id
                            } else {
                                onEpisodeRemove(download.episode.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The scrolling body: the storage card, then a row per tracked episode.
 *
 * The rows are hand-orderable, the same long-press drag the queue and the library use. Downloads
 * arrive in an order nobody chose — whatever the download stack was doing, newest first — and this
 * is the screen where "listen to these three next" is a decision the user actually has. The storage
 * card sits in the same list but outside the reorder: its key is not one of the dragged keys, so
 * the hit test never offers it as a drop target and it stays pinned at the top.
 *
 * @param uiState what to render.
 * @param now reference time for relative date formatting.
 * @param onEpisodeClick tap handler for a finished episode.
 * @param onEpisodeRetry tap handler for a failed download.
 * @param onEpisodeQueue add-to-queue handler for a row's full swipe.
 * @param onEpisodeRemove delete-or-cancel handler, called with the whole row so the caller can
 *   decide whether it is destructive enough to confirm.
 * @param onMove applies a completed drag; see [DownloadsScreen].
 */
@Composable
private fun DownloadList(
    uiState: DownloadsUiState,
    now: Instant,
    onEpisodeClick: (String) -> Unit,
    onEpisodeRetry: (String) -> Unit,
    onEpisodeQueue: (String) -> Unit,
    onEpisodeRemove: (EpisodeWithShow) -> Unit,
    onMove: (List<String>, Int, Int) -> Unit,
) {
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    // Captured from the upstream list rather than read out of `drag.order` inside the callback:
    // by the time a gesture ends, the drawn order has already been rearranged locally, and the
    // move has to be expressed against the arrangement it started from.
    // Remembered against the list itself: a running transfer re-emits several times a second, and
    // this must not rebuild an id list on every one of those frames.
    val shownIds = remember(uiState.downloads) { uiState.downloads.map { it.episode.id } }
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = uiState.downloads,
        keyOf = { it.episode.id },
        onMove = { from, to -> onMove(shownIds, from, to) },
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BPodcatTheme.spacing.sm),
    ) {
        item(key = STORAGE_CARD_KEY) {
            StorageCard(
                episodeCount = uiState.completedCount,
                totalBytes = uiState.totalBytes,
                freeBytes = uiState.freeBytes,
            )
        }

        itemsIndexed(drag.order, key = { _, download -> download.episode.id }) { index, download ->
            DownloadRow(
                download = download,
                index = index,
                drag = drag,
                metadata = download.metadataLine(now, resources, uiState.unmeteredOnly),
                onClick = onEpisodeClick,
                onRetry = onEpisodeRetry,
                onQueue = { onEpisodeQueue(download.episode.id) },
                onRemove = { onEpisodeRemove(download) },
            )
        }
    }
}

/**
 * One tracked episode: draggable, swipeable, tappable, and queueable.
 *
 * Four gestures share the row and stay out of each other's way by asking for different things, the
 * same arrangement the queue and the library arrived at. A tap plays a finished episode (or retries
 * a failed one), a long right-to-left swipe queues it, a short one reveals "remove", and a long
 * *press* picks it up to reorder. Nothing is left at the end of the row.
 *
 * Queueing is the full swipe because it is what a downloaded episode is eventually *for*: it was
 * stored so it could be listened to, and lining one up should cost a single movement and no aim. It
 * used to be a button on every row, which is a control that has to be aimed at once per row.
 * Removal moved the other way, behind the short swipe: it deletes audio, no second gesture undoes
 * it, and a destructive action wants to be chosen rather than fired — the same reasoning that
 * puts "mark played" behind the queue's short swipe.
 *
 * None of the four gestures is visible to a screen reader, so all of them — both tiers of the
 * swipe and both directions of the reorder — are also published as custom accessibility actions.
 *
 * @param download the episode and its show.
 * @param index its position in the list, for the reorder actions.
 * @param drag the shared drag state, which owns the visual offset and the pending move.
 * @param metadata the line under the title, already assembled.
 * @param onClick tap handler for a finished episode.
 * @param onRetry tap handler for a failed download.
 * @param onQueue adds this episode to the end of the play queue.
 * @param onRemove delete-or-cancel handler.
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadRow(
    download: EpisodeWithShow,
    index: Int,
    drag: ReorderableState<EpisodeWithShow>,
    metadata: String,
    onClick: (String) -> Unit,
    onRetry: (String) -> Unit,
    onQueue: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = download.episode
    val isCompleted = episode.downloadState == DownloadState.COMPLETED
    val isFailed = episode.downloadState == DownloadState.FAILED
    val isDragging = drag.draggingKey == episode.id
    val moveUp = stringResource(R.string.downloads_move_up)
    val moveDown = stringResource(R.string.downloads_move_down)

    val queue = SwipeAction(
        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
        label = stringResource(R.string.downloads_action_queue),
        // The primary palette: this is the row being used rather than the row leaving, and it has
        // to read as the opposite of the remove button it is pulled past.
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onQueue,
    )
    val remove = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.downloads_action_remove),
        // The error palette, because the file is going. On this screen that is the whole point of
        // the gesture, and it should not look like a tidy-up.
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onRemove,
    )

    SwipeActionsRow(
        actions = listOf(remove),
        fullSwipeAction = queue,
        modifier = modifier.graphicsLayer {
            // Only the dragged row moves; the rest are re-laid-out by the list as the underlying
            // order changes, which is what makes the gap follow the finger.
            translationY = if (isDragging) drag.offset.y else 0f
            // Lifts the row above its neighbours so it is not clipped by them mid-drag.
            shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
        },
    ) {
        EpisodeRow(
            // On the row rather than on the box around it: the row merges its children into one
            // node, and that merged node is where a screen reader — which can see neither the
            // drag nor the swipe — looks for what the row can do.
            modifier = Modifier
                // Inside the swipe box rather than around it, so the row's two drags are settled
                // by the pointer that started them: this one consumes movement only once the
                // press has been held, and a swipe claims the gesture long before that.
                .reorderableLongPressDrag(drag, episode.id)
                .semantics {
                    // Both tiers of the swipe, flattened: to a screen reader they are not two
                    // tiers, they are simply the two things this row can do.
                    customActions = drag.moveActions(index, moveUp, moveDown) +
                        listOf(queue, remove).asAccessibilityActions()
                },
            title = episode.title,
            showTitle = download.showTitle,
            metadata = metadata,
            artworkUrl = download.artworkUrl,
            isPlayed = isCompleted && episode.isPlayed,
            playedFraction = if (isCompleted) episode.playedFraction else 0f,
            // What a tap does follows the state, because that is the only thing a tap could
            // sensibly mean: a finished episode plays, a failed one retries, and a transfer in
            // progress does nothing at all — there is no local audio to play, and streaming
            // instead would spend mobile data nobody asked to spend.
            onClick = when {
                isCompleted -> ({ onClick(episode.id) })
                isFailed -> ({ onRetry(episode.id) })
                else -> null
            },
        )
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
 * The confirmation shown before a downloaded episode is deleted.
 *
 * Deleting audio is the one action here that no second gesture undoes — the file has to be fetched
 * again — so it asks first, and says how much space it will actually free, which is usually why it
 * is being done. A swipe is deliberate, but it is also a gesture a thumb can start by accident on a
 * list that scrolls.
 *
 * @param title the episode being deleted, named so a mis-swipe is caught here rather than after.
 * @param freedBytes what deleting it gives back.
 * @param onConfirm proceed.
 * @param onDismiss cancel.
 */
@Composable
private fun RemovalDialog(
    title: String,
    freedBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.downloads_remove_dialog_title, title)) },
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

    is DownloadsMessage.Queued -> resources.getString(R.string.downloads_message_queued, title)

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

private const val STORAGE_CARD_KEY = "storage-card"

/** How far a picked-up row is lifted above its neighbours, in pixels; matches the queue. */
private const val DRAG_ELEVATION = 8f

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
            onEpisodeRetry = {},
            onEpisodeRemove = {},
            onEpisodeQueue = {},
            onMove = { _, _, _ -> },
            onRefresh = {},
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
