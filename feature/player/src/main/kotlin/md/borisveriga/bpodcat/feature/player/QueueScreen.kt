package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
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
 * @param onBack invoked when the user leaves the screen.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt; shared with the player, because it is the same queue.
 */
@Composable
fun QueueRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QueueScreen(
        uiState = uiState,
        onBack = onBack,
        onPlay = viewModel::playQueued,
        onRemove = viewModel::removeFromQueue,
        onMarkPlayed = viewModel::markPlayed,
        onMove = viewModel::moveInUpNext,
        modifier = modifier,
    )
}

/**
 * Stateless queue screen: what is playing, then what follows, reorderable and removable.
 *
 * The queue used to live at the bottom of the now-playing screen, below the artwork and the
 * transport controls, where it could only be reached by scrolling past everything else and could
 * not be edited beyond removing a row. It is a list the user manages, so it gets a screen.
 *
 * @param uiState what to render; [PlayerUiState.upNext] is the editable part.
 * @param onBack dismiss handler.
 * @param onPlay plays a queued episode immediately.
 * @param onRemove drops a queued episode.
 * @param onMarkPlayed marks a queued episode played, which also drops it from the queue.
 * @param onMove applies a completed drag, as positions within [PlayerUiState.upNext]. Called once
 *   on release rather than on every frame of the drag: one gesture is one edit, and a stream of
 *   them would make the player and the database renegotiate the order dozens of times.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    uiState: PlayerUiState,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMarkPlayed: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = uiState.upNext,
        keyOf = { it.episode.id },
        onMove = onMove,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.queue_back),
                        )
                    }
                },
            )
        },
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
 * One reorderable queue row: hold to move it, swipe it left for what else it can do.
 *
 * Both gestures are invisible to a screen reader, so both are also published as custom
 * accessibility actions. That is not a nicety here: without them the queue would be readable and
 * completely uneditable with TalkBack on.
 *
 * The two gestures do not fight, which is why the row can carry both: a swipe starts on sideways
 * movement, a reorder starts on holding still.
 *
 * @param entry the queued episode.
 * @param index its position in the "up next" list.
 * @param drag the shared drag state, which owns the visual offset and the pending move.
 * @param onPlay plays this episode now.
 * @param onRemove drops it from the queue.
 * @param onMarkPlayed marks it played.
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

    val actions = listOf(
        SwipeAction(
            icon = Icons.Rounded.DoneAll,
            label = stringResource(R.string.queue_action_mark_played),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onMarkPlayed,
        ),
        SwipeAction(
            icon = Icons.Rounded.Delete,
            label = stringResource(R.string.queue_action_remove),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onRemove,
        ),
    )

    SwipeActionsRow(
        actions = actions,
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
                .semantics {
                    customActions =
                        drag.moveActions(index, moveUp, moveDown) + actions.asAccessibilityActions()
                }
                .reorderableLongPressDrag(drag, entry.episode.id),
            title = entry.episode.title,
            showTitle = entry.showTitle,
            artworkUrl = entry.artworkUrl,
            artworkSize = ArtworkSize.Row,
            playedFraction = entry.episode.playedFraction,
            onClick = onPlay,
        )
    }
}

private const val NOW_PLAYING_KEY = "now-playing"
private const val UP_NEXT_KEY = "up-next"
private const val DRAG_ELEVATION = 8f
