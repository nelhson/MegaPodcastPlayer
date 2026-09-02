package md.borisveriga.bpodcat.feature.player

import android.content.res.Resources
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.BPodcatLargeTopAppBar
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.SectionHeader
import md.borisveriga.bpodcat.core.designsystem.component.SwipeAction
import md.borisveriga.bpodcat.core.designsystem.component.SwipeActionsRow
import md.borisveriga.bpodcat.core.designsystem.component.asAccessibilityActions
import md.borisveriga.bpodcat.core.designsystem.reorder.ReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.moveActions
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.reorderableLongPressDrag
import md.borisveriga.bpodcat.core.media.PlayableEpisode

/**
 * The play queue.
 *
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt; shared with the player, because it is the same queue.
 */
@Composable
fun QueueRoute(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QueueScreen(
        uiState = uiState,
        onPlay = viewModel::playQueued,
        onRemove = viewModel::removeFromQueue,
        onMarkPlayed = viewModel::markQueuedPlayed,
        onMove = viewModel::moveInUpNext,
        onUndo = viewModel::undoQueueChange,
        onMessageShown = viewModel::onQueueMessageShown,
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
 * @param onMarkPlayed marks a queued episode played, which also drops it.
 * @param onMove applies a completed drag, as positions within [PlayerUiState.upNext]. Called once
 *   on release rather than on every frame of the drag: one gesture is one edit, and a stream of
 *   them would make the player and the database renegotiate the order dozens of times.
 * @param onUndo reverses whichever of the two the snackbar is currently offering back.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    uiState: PlayerUiState,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMarkPlayed: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onUndo: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition rather than inside the effect: `LaunchedEffect` runs outside the
    // composition, where `stringResource` is not available. `LocalResources` rather than
    // `LocalContext.current.resources`, so a configuration change invalidates the read.
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.queue_undo)
    // Matches the other tabs: the screen opens on its name and gives the height back to the list
    // as soon as the user scrolls.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
            BPodcatLargeTopAppBar(
                title = stringResource(R.string.queue_title),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val nowPlaying = uiState.queue.firstOrNull { it.episode.id == uiState.playback.episodeId }

        if (nowPlaying == null && drag.order.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = stringResource(R.string.queue_empty_title),
                description = stringResource(R.string.queue_empty_description),
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
            if (nowPlaying != null) {
                item(key = NOW_PLAYING_KEY) {
                    SectionHeader(text = stringResource(R.string.queue_now_playing))
                    // No swipe on this one: "remove" and "mark played" both mean "stop playing
                    // this", which is what the player's own controls are for, and a gesture that
                    // silently stopped the audio would be a surprising thing to find by accident.
                    EpisodeRow(
                        title = nowPlaying.episode.title,
                        showTitle = nowPlaying.showTitle,
                        artworkUrl = nowPlaying.artworkUrl,
                        artworkSize = ArtworkSize.Row,
                        playedFraction = uiState.playback.progress,
                        isNowPlaying = true,
                        isPlaying = uiState.playback.isPlaying,
                    )
                }
            }

            if (drag.order.isNotEmpty()) {
                item(key = UP_NEXT_KEY) {
                    SectionHeader(text = stringResource(R.string.player_up_next))
                }
            }

            itemsIndexed(drag.order, key = { _, entry -> entry.episode.id }) { index, entry ->
                QueueEntry(
                    entry = entry,
                    index = index,
                    drag = drag,
                    onPlay = { onPlay(entry.episode.id) },
                    onRemove = { onRemove(entry.episode.id) },
                    onMarkPlayed = { onMarkPlayed(entry.episode.id) },
                )
            }
        }
    }
}

/**
 * One reorderable, swipeable queue row.
 *
 * Four gestures share it, and they stay out of each other's way by asking for different things: a
 * tap plays the episode, a short right-to-left swipe reveals "mark played", a long one removes the
 * row, and a long press picks it up. The press is the one that has to be *held*, which is what
 * leaves the other three — and the queue's own scrolling — free to happen first.
 *
 * Removal is the full swipe rather than the button because it is the one done constantly: a queue
 * is pruned far more often than it is marked off. Marking played is the rarer, more considered
 * choice, so it is the one that has to be aimed at.
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
 * @param onMarkPlayed marks it played, which also drops it.
 * @param modifier layout modifier.
 */
@Composable
private fun QueueEntry(
    entry: PlayableEpisode,
    index: Int,
    drag: ReorderableState<PlayableEpisode>,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMarkPlayed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDragging = drag.draggingKey == entry.episode.id
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)

    val markPlayed = SwipeAction(
        icon = Icons.Rounded.DoneAll,
        label = stringResource(R.string.queue_action_mark_played),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onMarkPlayed,
    )
    val remove = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.queue_action_remove),
        // The error palette, because this is the row leaving. Both actions take the episode out of
        // the queue, and the colour is what distinguishes "I have listened to this" from "I am not
        // going to".
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onRemove,
    )

    SwipeActionsRow(
        actions = listOf(markPlayed),
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
                    // Both tiers of the swipe, flattened: to a screen reader they are not two
                    // tiers, they are simply the two things this row can do.
                    customActions = drag.moveActions(index, moveUp, moveDown) +
                        listOf(markPlayed, remove).asAccessibilityActions()
                },
            title = entry.episode.title,
            showTitle = entry.showTitle,
            artworkUrl = entry.artworkUrl,
            artworkSize = ArtworkSize.Row,
            playedFraction = entry.episode.playedFraction,
            onClick = onPlay,
        )
    }
}

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

    is QueueMessage.MarkedPlayed ->
        resources.getString(R.string.queue_message_marked_played, episodeTitle)
}

private const val NOW_PLAYING_KEY = "now-playing"
private const val UP_NEXT_KEY = "up-next"
private const val DRAG_ELEVATION = 8f
