package md.borisveriga.bpodcat.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.BPodcatLargeTopAppBar
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.SectionHeader
import md.borisveriga.bpodcat.core.designsystem.reorder.ReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.reorderableLongPressDrag
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
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
        onMove = viewModel::moveInUpNext,
        modifier = modifier,
    )
}

/**
 * Stateless queue screen: what is playing, then what follows, reorderable and removable.
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
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    uiState: PlayerUiState,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Matches the other tabs: the screen opens on its name and gives the height back to the list
    // as soon as the user scrolls.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = uiState.upNext,
        keyOf = { it.episode.id },
        onMove = onMove,
    )

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
                )
            }
        }
    }
}

/**
 * One reorderable, removable queue row.
 *
 * Three gestures share it, and they stay out of each other's way by asking for different things: a
 * tap plays the episode, a horizontal swipe removes the row, and a long press picks it up. The
 * press is the one that has to be held, which is what leaves the other two — and the queue's own
 * scrolling — free to happen first.
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueEntry(
    entry: PlayableEpisode,
    index: Int,
    drag: ReorderableState<PlayableEpisode>,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // LocalResources rather than LocalContext.current.resources, so a configuration change
    // invalidates the read.
    val resources = LocalResources.current
    val isDragging = drag.draggingKey == entry.episode.id

    val dismissState = rememberSwipeToDismissBoxState()

    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    val remove = resources.getString(R.string.player_remove_from_queue, entry.episode.title)

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { RemoveBackdrop() },
        onDismiss = { onRemove() },
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
                    customActions = buildList {
                        if (index > 0) {
                            add(
                                CustomAccessibilityAction(moveUp) {
                                    drag.move(index, index - 1)
                                    true
                                },
                            )
                        }
                        if (index < drag.order.lastIndex) {
                            add(
                                CustomAccessibilityAction(moveDown) {
                                    drag.move(index, index + 1)
                                    true
                                },
                            )
                        }
                        add(
                            CustomAccessibilityAction(remove) {
                                onRemove()
                                true
                            },
                        )
                    }
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

/** What a swipe reveals behind a queue row. */
@Composable
private fun RemoveBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = BPodcatTheme.spacing.xl),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.alpha(BACKDROP_ALPHA),
        )
    }
}

private const val NOW_PLAYING_KEY = "now-playing"
private const val UP_NEXT_KEY = "up-next"
private const val DRAG_ELEVATION = 8f
private const val BACKDROP_ALPHA = 0.8f
