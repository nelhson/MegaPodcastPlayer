package md.borisveriga.megapodcastplayer.feature.player

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.common.format.formatCountdown
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.NowPlayingBars
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeAction
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeActionsRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.asAccessibilityActions
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.ReorderableState
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.moveActions
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.reorderableLongPressDrag
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState

/**
 * The play queue.
 *
 * @param onBrowseLibrary opens the library, which is where episodes are queued from; the empty
 *   state's only action, because "nothing queued" with nowhere to go is a dead end.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt; shared with the player, because it is the same queue.
 */
@Composable
fun QueueRoute(
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QueueScreen(
        uiState = uiState,
        onPlay = viewModel::playQueued,
        onRemove = viewModel::removeFromQueue,
        onMove = viewModel::moveInUpNext,
        onClear = viewModel::clearQueue,
        onUndo = viewModel::undoQueueChange,
        onMessageShown = viewModel::onQueueMessageShown,
        onBrowseLibrary = onBrowseLibrary,
        modifier = modifier,
    )
}

/**
 * Stateless queue screen: what is playing, then what follows, reorderable and editable by swipe.
 *
 * The queue used to live at the bottom of the now-playing screen, below the artwork and the
 * transport controls, where it could only be reached by scrolling past everything else and could
 * not be edited beyond removing a row. It is a list the user manages, so it gets a screen — and,
 * since it is one of the three lists the app is made of, a tab. It carries no back arrow for that
 * reason: a top-level destination has nothing behind it.
 *
 * @param uiState what to render; [PlayerUiState.upNext] is the editable part.
 * @param onPlay plays a queued episode immediately.
 * @param onRemove drops a queued episode.
 * @param onMove applies a completed drag, as positions within [PlayerUiState.upNext]. Called once
 *   on release rather than on every frame of the drag: one gesture is one edit, and a stream of
 *   them would make the player and the database renegotiate the order dozens of times.
 * @param onClear empties the queue of everything after the episode playing.
 * @param onUndo reverses whichever of the two the snackbar is currently offering back.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param onBrowseLibrary opens the library from the empty state.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    uiState: PlayerUiState,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onMessageShown: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition rather than inside the effect: `LaunchedEffect` runs outside the
    // composition, where `stringResource` is not available. `LocalResources` rather than
    // `LocalContext.current.resources`, so a configuration change invalidates the read.
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.queue_undo)
    // Pinned rather than collapsing: this is one of three tabs, and the bar is what tells the user
    // which of them they are on. A large bar would spend the top third of the screen restating the
    // tab the navigation bar already highlights, and then scroll the name away exactly when a fast
    // scroll makes it easiest to lose track of.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = uiState.upNext,
        keyOf = { it.episode.id },
        onMove = onMove,
    )

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.toText(resources),
            actionLabel = undoLabel,
            // Short: the row is already gone from the list, so the snackbar is the only thing on
            // screen still referring to it, and a long one would sit over the next swipe.
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onMessageShown()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MegaPodcastPlayerTopAppBar(
                title = stringResource(R.string.queue_title),
                // How long the queue is, in the unit a queue is actually measured in. A count of
                // episodes says nothing about whether it fits the walk home.
                subtitle = formatDuration(resources, uiState.upNext.remainingMs())
                    ?.let { stringResource(R.string.queue_remaining, it) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (uiState.upNext.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistRemove,
                                contentDescription = stringResource(R.string.queue_clear),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (drag.order.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.queue_empty_title),
                description = stringResource(R.string.queue_empty_description),
                // An empty state with nothing to press is a dead end, and this one is reached by
                // tapping a tab rather than by running out of something.
                actionLabel = stringResource(R.string.queue_empty_action),
                onAction = onBrowseLibrary,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // What is playing, above what is waiting. By design this screen lists only "up next",
            // which left it saying nothing at all about the episode the queue is a queue *behind* —
            // so the first row the user reads was the second thing that will play.
            uiState.nowPlaying?.let { playing ->
                item(key = NOW_PLAYING_KEY) {
                    NowPlayingHeader(entry = playing, playback = uiState.playback)
                }
            }

            itemsIndexed(drag.order, key = { _, entry -> entry.episode.id }) { index, entry ->
                QueueEntry(
                    entry = entry,
                    index = index,
                    drag = drag,
                    onPlay = { onPlay(entry.episode.id) },
                    onRemove = { onRemove(entry.episode.id) },
                )
            }
        }
    }
}

/**
 * One reorderable, swipeable queue row.
 *
 * Three gestures share it, and they stay out of each other's way by asking for different things: a
 * tap plays the episode, a right-to-left swipe removes the row, and a long press picks it up. The
 * press is the one that has to be *held*, which is what leaves the other two — and the queue's own
 * scrolling — free to happen first.
 *
 * Removal is the full swipe rather than a revealed button because it is the one thing a queue row
 * is asked for, and it is done constantly: a queue is pruned far more often than it is reordered.
 *
 * Every one of them is invisible to a screen reader, so all of them are also published as custom
 * accessibility actions. That is not a nicety here: without them the queue would be readable and
 * completely uneditable with TalkBack on.
 *
 * @param entry the queued episode.
 * @param index its position in the "up next" list.
 * @param drag the shared drag state, which owns the visual offset and the pending move.
 * @param onPlay plays this episode now.
 * @param onRemove drops it from the queue.
 * @param modifier layout modifier.
 */
@Composable
private fun QueueEntry(
    entry: PlayableEpisode,
    index: Int,
    drag: ReorderableState<PlayableEpisode>,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDragging = drag.draggingKey == entry.episode.id
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)

    val remove = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.queue_action_remove),
        // The error palette, because this is the row leaving.
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onRemove,
    )

    SwipeActionsRow(
        // Nothing to reveal: the row's one action is the full swipe.
        actions = emptyList(),
        fullSwipeAction = remove,
        modifier = modifier.graphicsLayer {
            // Only the dragged row moves; the rest are re-laid-out by the list as the underlying
            // order changes, which is what makes the gap follow the finger.
            translationY = if (isDragging) drag.offset.y else 0f
            // Lifts the row above its neighbours so it is not clipped by them mid-drag.
            shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
        },
    ) {
        EpisodeRow(
            // The actions go on the row rather than on the box around it: the row merges its
            // children into one node, and that merged node is what a screen reader lands on.
            modifier = Modifier
                // Inside the swipe box rather than around it, so the row's two drags are settled
                // by the pointer that started them: this one consumes movement only once the
                // press has been held, and a swipe claims the gesture long before that.
                .reorderableLongPressDrag(drag, entry.episode.id)
                .semantics {
                    // The swipe is invisible to a screen reader, so what it commits is published
                    // here as something that can simply be chosen.
                    customActions = drag.moveActions(index, moveUp, moveDown) +
                        listOf(remove).asAccessibilityActions()
                },
            title = entry.episode.title,
            showTitle = entry.showTitle,
            artworkUrl = entry.artworkUrl,
            artworkSize = ArtworkSize.Row,
            // The queue is where the offline question is actually asked: these are the episodes
            // about to play, and which of them need a connection decides what is safe to start.
            isDownloaded = entry.episode.downloadState == DownloadState.COMPLETED,
            playedFraction = entry.episode.playedFraction,
            onClick = onPlay,
        )
    }
}

/**
 * What is playing, above the list of what is not yet.
 *
 * Slim on purpose: a second full episode row at the top of the queue would read as the first thing
 * in it. This is a status line — artwork, title, and how far through — with no gestures of its own,
 * because everything you can do to the episode playing is on the player a tap away.
 *
 * @param entry the episode the player has loaded.
 * @param playback where the player has it, for the position line.
 * @param modifier layout modifier.
 */
@Composable
private fun NowPlayingHeader(
    entry: PlayableEpisode,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val position = formatPosition(playback.positionMs)
    val remaining = formatCountdown(playback.knownDurationMs, playback.positionMs)
    val label = stringResource(R.string.queue_now_playing)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                vertical = MegaPodcastPlayerTheme.spacing.md,
            )
            // One node, like every row in the app: a screen reader lands on "Now playing,
            // <episode>, 12:04" rather than walking three anonymous fragments.
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
        ) {
            PodcastArtwork(
                url = entry.episode.artworkUrl ?: entry.showArtworkUrl,
                size = ArtworkSize.Mini,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(position, remaining).joinToString(SEPARATOR),
                    style = MegaPodcastPlayerTheme.type.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NowPlayingBars(playing = playback.isPlaying)
        }

        HorizontalDivider()
    }
}

/**
 * How much listening the queue holds.
 *
 * Sums what is *left* of each episode rather than each episode's length: a queue of three
 * half-finished episodes is not three hours of listening, and the number exists to answer "does
 * this fit the walk home".
 *
 * @return the total in milliseconds, or null when no episode has a duration to add.
 */
private fun List<PlayableEpisode>.remainingMs(): Long? {
    val total = sumOf { entry ->
        val duration = entry.episode.durationMs ?: 0L
        (duration - entry.episode.positionMs).coerceAtLeast(0L)
    }
    return total.takeIf { it > 0L }
}

/** Between the elapsed and remaining timecodes on the now-playing line. */
private const val SEPARATOR = " · "

/** Stable key for the now-playing header, so the list does not confuse it with a row. */
private const val NOW_PLAYING_KEY = "now-playing"

/**
 * Turns a [QueueMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun QueueMessage.toText(resources: Resources): String = when (this) {
    is QueueMessage.Removed -> resources.getString(R.string.queue_message_removed, episodeTitle)

    is QueueMessage.Cleared ->
        resources.getQuantityString(R.plurals.queue_message_cleared, count, count)
}

private const val DRAG_ELEVATION = 8f

@ThemePreviews
@FontScalePreviews
@Composable
private fun QueueScreenPreview() {
    MegaPodcastPlayerTheme {
        QueueScreen(
            uiState = PlayerUiState(
                playback = previewPlayback,
                queue = previewQueue,
                lastPlayedEpisodeId = "e1",
            ),
            onPlay = {},
            onRemove = {},
            onMove = { _, _ -> },
            onClear = {},
            onUndo = {},
            onMessageShown = {},
            onBrowseLibrary = {},
        )
    }
}

@ThemePreviews
@Composable
private fun QueueScreenEmptyPreview() {
    MegaPodcastPlayerTheme {
        QueueScreen(
            uiState = PlayerUiState(),
            onPlay = {},
            onRemove = {},
            onMove = { _, _ -> },
            onClear = {},
            onUndo = {},
            onMessageShown = {},
            onBrowseLibrary = {},
        )
    }
}
