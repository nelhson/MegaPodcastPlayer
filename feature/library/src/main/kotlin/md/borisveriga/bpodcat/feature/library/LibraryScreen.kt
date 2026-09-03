package md.borisveriga.bpodcat.feature.library

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.designsystem.component.BPodcatTopAppBar
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.ShowRow
import md.borisveriga.bpodcat.core.designsystem.component.ShowTile
import md.borisveriga.bpodcat.core.designsystem.component.SwipeAction
import md.borisveriga.bpodcat.core.designsystem.component.SwipeActionsRow
import md.borisveriga.bpodcat.core.designsystem.component.WavyProgressLine
import md.borisveriga.bpodcat.core.designsystem.component.asAccessibilityActions
import md.borisveriga.bpodcat.core.designsystem.reorder.ReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.moveActions
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.reorderableLongPressDrag
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion
import md.borisveriga.bpodcat.core.model.LibraryLayout
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

/**
 * Library screen: every subscribed show, refreshed on entry, with two routes into search.
 *
 * @param onPodcastClick invoked with a podcast id when a show is tapped.
 * @param onSearchClick invoked when the user wants to look a show up by name.
 * @param onPasteLinkClick invoked when the user wants to add a show from a link they have copied.
 * @param onOpenSettings invoked when the user taps the top bar's settings action.
 * @param onMove invoked with positions in the library once a reorder gesture finishes.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun LibraryRoute(
    onPodcastClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Tied to the lifecycle rather than run once from `init` or `LaunchedEffect(Unit)`, because
    // neither would fire often enough: switching tabs saves and restores this destination, so the
    // view model and the composition both survive and a one-shot effect would run only on the first
    // visit of the whole process. The back stack entry going RESUMED is the accurate signal for
    // "the user is looking at the library now" — it covers returning from a show, switching back to
    // this tab, and bringing the app to the foreground. The staleness window in the view model is
    // what stops that being a lot of network traffic.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStale()
        onPauseOrDispose { }
    }

    LibraryScreen(
        uiState = uiState,
        onPodcastClick = onPodcastClick,
        onSearchClick = onSearchClick,
        onPasteLinkClick = onPasteLinkClick,
        onOpenSettings = onOpenSettings,
        onMove = viewModel::move,
        onQueueNewest = viewModel::queueNewest,
        onMarkAllPlayed = viewModel::markAllPlayed,
        onRemove = viewModel::remove,
        onUndoMarkAllPlayed = viewModel::undoMarkAllPlayed,
        onLayoutChange = viewModel::setLayout,
        onRefresh = viewModel::refreshAll,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless library screen.
 *
 * @param uiState what to render.
 * @param onPodcastClick show tap handler.
 * @param onSearchClick opens search by name.
 * @param onPasteLinkClick opens search with a copied link.
 * @param onOpenSettings opens the settings screen.
 * @param onMove applies a completed reorder, as positions within [LibraryUiState.podcasts].
 *   Called once on release rather than per frame: one gesture is one edit.
 * @param onQueueNewest queues a show's newest unplayed episode; what a full swipe commits.
 * @param onMarkAllPlayed marks a whole show played.
 * @param onRemove removes a show, once the confirmation this screen owns has been accepted.
 * @param onUndoMarkAllPlayed reverses the last mark-all-played.
 * @param onLayoutChange grid/list toggle handler.
 * @param onRefresh pull-to-refresh handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onPodcastClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onQueueNewest: (PodcastWithCounts) -> Unit,
    onMarkAllPlayed: (PodcastWithCounts) -> Unit,
    onRemove: (PodcastWithCounts) -> Unit,
    onUndoMarkAllPlayed: () -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition rather than inside the effect: `LaunchedEffect` runs outside the
    // composition, where `stringResource` is not available. `LocalResources` rather than
    // `LocalContext.current.resources`, so a configuration change invalidates the read.
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.library_undo)
    // Pinned rather than collapsing: this is one of three tabs, and the bar is what tells the user
    // which of them they are on. A large bar would spend the top third of the screen restating the
    // tab the navigation bar already highlights, and then scroll the name away exactly when a fast
    // scroll makes it easiest to lose track of.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Saveable so the menu does not silently close when the Fold 7 is opened mid-decision.
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The show a removal is being confirmed for, held as an id rather than the object so it too
    // survives the fold — and so a show that disappears under the dialog simply closes it.
    var pendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRemoval = pendingRemovalId?.let { id ->
        uiState.podcasts.firstOrNull { it.podcast.id == id }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.toText(resources),
            // Only one of the outcomes can be taken back. Offering "Undo" beside a refresh summary
            // would be a button that either does nothing or does something unrelated.
            actionLabel = undoLabel.takeIf { message is LibraryMessage.MarkedAllPlayed },
        )
        if (result == SnackbarResult.ActionPerformed) onUndoMarkAllPlayed() else onMessageShown()
    }

    pendingRemoval?.let { podcast ->
        RemoveShowDialog(
            podcast = podcast,
            onConfirm = {
                pendingRemovalId = null
                onRemove(podcast)
            },
            onDismiss = { pendingRemovalId = null },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BPodcatTopAppBar(
                title = stringResource(R.string.library_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    LayoutToggle(layout = uiState.layout, onLayoutChange = onLayoutChange)
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.library_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            AddMenu(
                expanded = addMenuExpanded,
                onExpandedChange = { addMenuExpanded = it },
                onSearchClick = onSearchClick,
                onPasteLinkClick = onPasteLinkClick,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The whole of the automatic refresh's presence on screen. Deliberately a line under
            // the title rather than anything that moves the list or blocks a tap: the user did not
            // ask for this and should be able to ignore it completely.
            if (uiState.isAutoRefreshing) {
                WavyProgressLine(
                    contentDescription = stringResource(R.string.library_refreshing),
                )
            }

            when {
                uiState.isLoading -> LoadingState(
                    contentDescription = stringResource(R.string.library_loading),
                )

                // No pull-to-refresh here: there are no feeds to fetch, and the gesture needs
                // something scrollable underneath it to work at all.
                uiState.podcasts.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.GridView,
                    title = stringResource(R.string.library_empty_title),
                    description = stringResource(R.string.library_empty_description),
                    actionLabel = stringResource(R.string.library_add_search),
                    onAction = onSearchClick,
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (uiState.layout) {
                        LibraryLayout.GRID -> ShowGrid(
                            podcasts = uiState.podcasts,
                            onPodcastClick = onPodcastClick,
                            onMove = onMove,
                        )

                        LibraryLayout.LIST -> ShowList(
                            podcasts = uiState.podcasts,
                            onPodcastClick = onPodcastClick,
                            onMove = onMove,
                            onQueueNewest = onQueueNewest,
                            onMarkAllPlayed = onMarkAllPlayed,
                            onRemoveRequest = { pendingRemovalId = it.podcast.id },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The shows as a wall of cover art, rearrangeable by long press.
 *
 * Adaptive rather than a fixed column count, so the same code fills a phone, a rail-width pane and
 * the Fold 7 opened out without any of them being a special case.
 *
 * A long press: a tile is artwork edge to edge, and carving a grip out of it would cost the cover
 * the space it exists to show. The list layout is picked up the same way.
 *
 * @param podcasts the library.
 * @param onPodcastClick tile tap handler.
 * @param onMove reports a finished reorder as positions in [podcasts].
 */
@Composable
private fun ShowGrid(
    podcasts: List<PodcastWithCounts>,
    onPodcastClick: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val resources = LocalResources.current
    val moveUp = stringResource(R.string.library_move_up)
    val moveDown = stringResource(R.string.library_move_down)
    val gridState = rememberLazyGridState()
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(gridState),
        items = podcasts,
        keyOf = { it.podcast.id },
        onMove = onMove,
    )

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(BPodcatTheme.spacing.sm),
    ) {
        itemsIndexed(
            items = drag.order,
            key = { _, entry -> entry.podcast.id },
        ) { index, entry ->
            val isDragging = drag.draggingKey == entry.podcast.id

            ShowTile(
                title = entry.podcast.title,
                artworkUrl = entry.podcast.artworkUrl,
                author = entry.podcast.author,
                source = entry.podcast.source,
                badgeCount = entry.newEpisodeCount,
                isDownloaded = entry.downloadedCount > 0,
                stateDescription = entry.newEpisodeDescription(resources),
                onClick = { onPodcastClick(entry.podcast.id) },
                modifier = Modifier
                    // On the tile/row itself, which merges its children: that merged node is
                    // what a screen reader lands on, and a drag is invisible to one.
                    .semantics { customActions = drag.moveActions(index, moveUp, moveDown) }
                    .graphicsLayer {
                        // Only the dragged tile moves; the rest are re-laid-out by the grid as the
                        // order changes, which is what makes the gap follow the finger.
                        translationX = if (isDragging) drag.offset.x else 0f
                        translationY = if (isDragging) drag.offset.y else 0f
                        // Lifts it above its neighbours so it is not clipped by them mid-drag.
                        shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
                    }
                    .reorderableLongPressDrag(drag, entry.podcast.id),
            )
        }
    }
}

/**
 * The shows as rows, for a library too long to recognise by cover alone.
 *
 * Rearrangeable by a long press on the row, the same gesture and the same code as the grid, and
 * swipeable from right to left for the three things a show can be told to do. The grid gets none of
 * the swipe: a tile is 148dp of artwork with nothing to spare, so half of one is not a distance a
 * commit threshold can live at, and two revealed buttons would leave no tile.
 *
 * @param podcasts the library.
 * @param onPodcastClick row tap handler.
 * @param onMove reports a finished reorder as positions in [podcasts].
 * @param onQueueNewest queues the show's newest unplayed episode; the full-swipe action.
 * @param onMarkAllPlayed marks the whole show played.
 * @param onRemoveRequest asks for the show to be removed; the screen confirms before it happens.
 */
@Composable
private fun ShowList(
    podcasts: List<PodcastWithCounts>,
    onPodcastClick: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onQueueNewest: (PodcastWithCounts) -> Unit,
    onMarkAllPlayed: (PodcastWithCounts) -> Unit,
    onRemoveRequest: (PodcastWithCounts) -> Unit,
) {
    val listState = rememberLazyListState()
    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = podcasts,
        keyOf = { it.podcast.id },
        onMove = onMove,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = BPodcatTheme.spacing.sm),
    ) {
        itemsIndexed(
            items = drag.order,
            key = { _, entry -> entry.podcast.id },
        ) { index, entry ->
            ShowListRow(
                entry = entry,
                index = index,
                drag = drag,
                onPodcastClick = { onPodcastClick(entry.podcast.id) },
                onQueueNewest = { onQueueNewest(entry) },
                onMarkAllPlayed = { onMarkAllPlayed(entry) },
                onRemoveRequest = { onRemoveRequest(entry) },
            )
        }
    }
}

/**
 * One library row: draggable, swipeable, and tappable into the show.
 *
 * Queueing is the full swipe because it is the thing wanted most often and the only one of the
 * three that adds rather than takes away — the gesture that costs no aim should be the one that
 * cannot go wrong. Marking a show off and unsubscribing from it are both rare and both large, so
 * they are behind the reveal, where they have to be read before they can be tapped.
 *
 * @param entry the show.
 * @param index its position in the library, for the reorder actions.
 * @param drag the shared drag state, which owns the visual offset and the pending move.
 * @param onPodcastClick opens the show.
 * @param onQueueNewest queues its newest unplayed episode.
 * @param onMarkAllPlayed marks the whole show played.
 * @param onRemoveRequest asks for it to be removed.
 */
@Composable
private fun ShowListRow(
    entry: PodcastWithCounts,
    index: Int,
    drag: ReorderableState<PodcastWithCounts>,
    onPodcastClick: () -> Unit,
    onQueueNewest: () -> Unit,
    onMarkAllPlayed: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    val resources = LocalResources.current
    val moveUp = stringResource(R.string.library_move_up)
    val moveDown = stringResource(R.string.library_move_down)
    val isDragging = drag.draggingKey == entry.podcast.id

    val queueNewest = SwipeAction(
        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
        label = stringResource(R.string.library_action_queue_next),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onQueueNewest,
    )
    val markAllPlayed = SwipeAction(
        icon = Icons.Rounded.DoneAll,
        label = stringResource(R.string.library_action_mark_all_played),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onMarkAllPlayed,
    )
    val remove = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.library_action_remove),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onRemoveRequest,
    )

    SwipeActionsRow(
        actions = listOf(markAllPlayed, remove),
        fullSwipeAction = queueNewest,
        modifier = Modifier.graphicsLayer {
            translationY = if (isDragging) drag.offset.y else 0f
            shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
        },
    ) {
        ShowRow(
            modifier = Modifier
                // On the row itself, which merges its children: that merged node is what a screen
                // reader lands on, and neither the drag nor the swipe is visible to one.
                .semantics {
                    customActions = drag.moveActions(index, moveUp, moveDown) +
                        listOf(queueNewest, markAllPlayed, remove).asAccessibilityActions()
                }
                // Inside the swipe box rather than around it, so the row's two drags are settled by
                // the pointer that started them: this one consumes movement only once the press has
                // been held, and a horizontal swipe claims the gesture long before that.
                .reorderableLongPressDrag(drag, entry.podcast.id),
            title = entry.podcast.title,
            author = entry.podcast.author,
            metadata = entry.countsLine(resources),
            artworkUrl = entry.podcast.artworkUrl,
            isDownloaded = entry.downloadedCount > 0,
            source = entry.podcast.source,
            stateDescription = entry.newEpisodeDescription(resources),
            onClick = onPodcastClick,
            trailing = {
                // The same mark the grid puts on a cover. Without it the list would be the layout
                // that cannot answer "which of these has something new".
                if (entry.newEpisodeCount > 0) {
                    Badge(
                        containerColor = BPodcatTheme.colors.unplayed,
                        contentColor = BPodcatTheme.colors.onUnplayed,
                        // The row already announces "3 new episodes"; a bare number read out after
                        // it would be the same fact, less usefully put.
                        modifier = Modifier.clearAndSetSemantics {},
                    ) {
                        Text(text = entry.newEpisodeCount.toString())
                    }
                }
            },
        )
    }
}

/**
 * The confirmation shown before a show leaves the library.
 *
 * The one gesture on this screen with no way back. Queueing and marking off are both offered back
 * in a snackbar; a removal cannot be, because re-subscribing re-fetches the feed and what returns
 * is a fresh show — the played flags, the positions and the downloaded files are gone. So the
 * friction goes in front of the action rather than behind it.
 *
 * What is at stake is counted rather than described: nobody hesitates over a show they have never
 * started, and everybody wants to know before they lose twelve downloads.
 *
 * @param podcast the show about to be removed.
 * @param onConfirm proceed.
 * @param onDismiss cancel.
 */
@Composable
private fun RemoveShowDialog(
    podcast: PodcastWithCounts,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Rounded.Delete, contentDescription = null) },
        title = {
            Text(text = stringResource(R.string.library_remove_dialog_title, podcast.podcast.title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm)) {
                Text(text = stringResource(R.string.library_remove_dialog_text))
                if (podcast.downloadedCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.library_remove_dialog_at_stake,
                            podcast.downloadedCount,
                            podcast.downloadedCount,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.library_action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_cancel))
            }
        },
    )
}

/**
 * The button that swaps the two layouts.
 *
 * Shows the layout it would switch *to*, and says so, because an icon of the layout you are already
 * looking at is a puzzle rather than a control.
 *
 * @param layout the layout currently on screen.
 * @param onLayoutChange invoked with the layout to switch to.
 */
@Composable
private fun LayoutToggle(layout: LibraryLayout, onLayoutChange: (LibraryLayout) -> Unit) {
    IconButton(onClick = { onLayoutChange(layout.toggled) }) {
        Icon(
            imageVector = when (layout) {
                LibraryLayout.GRID -> Icons.AutoMirrored.Rounded.ViewList
                LibraryLayout.LIST -> Icons.Rounded.GridView
            },
            contentDescription = stringResource(
                when (layout) {
                    LibraryLayout.GRID -> R.string.library_layout_show_list
                    LibraryLayout.LIST -> R.string.library_layout_show_grid
                },
            ),
        )
    }
}

/**
 * The add button, and the two ways of adding a show.
 *
 * A single "Add" button used to open search, where a pasted link happened to work but nothing said
 * so — the only mention of it was in the empty state, which a library with shows in it never shows.
 * Naming both routes on the button itself is the fix.
 *
 * Hand-built rather than Material's `FloatingActionButtonMenu`, which is Expressive and not in
 * material3 1.4.0's public API.
 *
 * @param expanded whether the two actions are showing.
 * @param onExpandedChange opens and closes the menu.
 * @param onSearchClick opens search by name.
 * @param onPasteLinkClick opens search with a copied link.
 */
@Composable
private fun AddMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
) {
    // An open menu is the innermost thing on screen, so back closes it before it leaves the tab.
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    // The plus turns into a close glyph by rotating an eighth of a turn, which is the same two
    // strokes in both states — no second icon, and nothing to cross-fade.
    val rotation by animateFloatAsState(
        targetValue = if (expanded) CLOSE_ROTATION_DEGREES else 0f,
        animationSpec = Motion.bouncy(),
        label = "addMenuRotation",
    )

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(animationSpec = Motion.bouncy()),
            exit = fadeOut() + scaleOut(animationSpec = Motion.smooth()),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
            ) {
                AddMenuItem(
                    label = stringResource(R.string.library_add_link),
                    icon = Icons.Rounded.Link,
                    onClick = {
                        onExpandedChange(false)
                        onPasteLinkClick()
                    },
                )
                AddMenuItem(
                    label = stringResource(R.string.library_add_search),
                    icon = Icons.Rounded.Search,
                    onClick = {
                        onExpandedChange(false)
                        onSearchClick()
                    },
                )
            }
        }

        FloatingActionButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.library_add_close else R.string.library_add_podcast,
                ),
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/**
 * One entry in the add menu.
 *
 * The label is repeated as a content description rather than left to the button's own text.
 * `ExtendedFloatingActionButton` wraps its icon and label in `clearAndSetSemantics`, so the text
 * inside it never reaches an accessibility service: without this the menu is two unnamed buttons.
 *
 * @param label what the entry does.
 * @param icon the glyph beside it; decorative, since the label is right there.
 * @param onClick invoked on tap.
 */
@Composable
private fun AddMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        text = { Text(text = label) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

/**
 * The counts line under a show: how many episodes there are, and how many are on the device.
 *
 * @param resources for the plurals.
 * @return the metadata line.
 */
private fun PodcastWithCounts.countsLine(resources: Resources): String {
    // "videos" rather than "episodes" for a playlist: it is what the user called them when they
    // added it.
    val countLabel = resources.getQuantityString(
        if (podcast.source == PodcastSource.YOUTUBE) {
            R.plurals.library_video_count
        } else {
            R.plurals.library_episode_count
        },
        episodeCount,
        episodeCount,
    )
    return if (downloadedCount > 0) {
        resources.getString(
            R.string.library_counts_combined,
            countLabel,
            resources.getQuantityString(
                R.plurals.library_downloaded_count,
                downloadedCount,
                downloadedCount,
            ),
        )
    } else {
        countLabel
    }
}

/**
 * What a row or tile announces beyond its text: how many episodes are waiting.
 *
 * Null when there are none, so a show with nothing new says nothing rather than "0 new episodes".
 *
 * @param resources for the plural.
 * @return the state description, or null.
 */
private fun PodcastWithCounts.newEpisodeDescription(resources: Resources): String? =
    if (newEpisodeCount > 0) {
        resources.getQuantityString(
            R.plurals.library_message_new_episodes,
            newEpisodeCount,
            newEpisodeCount,
        )
    } else {
        null
    }

/**
 * Turns a [LibraryMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun LibraryMessage.toText(resources: Resources): String = when (this) {
    is LibraryMessage.Removed -> resources.getString(R.string.library_message_removed, title)

    is LibraryMessage.Queued -> resources.getString(R.string.library_message_queued, episodeTitle)

    is LibraryMessage.NothingToQueue ->
        resources.getString(R.string.library_message_nothing_to_queue, showTitle)

    is LibraryMessage.MarkedAllPlayed -> if (count == 0) {
        // "Marked 0 episodes played" reads as a failure. The show was already finished, and saying
        // so is both the truth and the more useful sentence.
        resources.getString(R.string.library_message_already_played, showTitle)
    } else {
        resources.getQuantityString(R.plurals.library_message_marked_played, count, count)
    }

    is LibraryMessage.RefreshFinished -> with(summary) {
        val newEpisodes = resources.getQuantityString(
            R.plurals.library_message_new_episodes,
            newEpisodeCount,
            newEpisodeCount,
        )
        when {
            failedTitles.isNotEmpty() && newEpisodeCount > 0 -> resources.getString(
                R.string.library_message_new_and_failed,
                newEpisodes,
                resources.getQuantityString(
                    R.plurals.library_message_failed_feeds,
                    failedTitles.size,
                    failedTitles.size,
                ),
            )

            failedTitles.isNotEmpty() -> resources.getString(
                R.string.library_message_refresh_failed,
                failedTitles.joinToString(),
            )

            newEpisodeCount > 0 -> newEpisodes

            else -> resources.getString(R.string.library_message_no_new_episodes)
        }
    }
}

/** The narrowest a cover tile may be before the grid drops a column. */
private val TILE_MIN_WIDTH = 148.dp

/** An eighth of a turn, which is what turns a plus into a close glyph. */
private const val CLOSE_ROTATION_DEGREES = 45f

/** How far a dragged show is lifted above its neighbours, so they cannot clip it. */
private const val DRAG_ELEVATION = 8f

@Preview
@Composable
private fun LibraryScreenPreview() {
    BPodcatTheme {
        LibraryScreen(
            uiState = LibraryUiState(
                isLoading = false,
                podcasts = listOf(
                    previewEntry("1", "Podlodka Podcast", newEpisodeCount = 3),
                    previewEntry("2", "Acquired", newEpisodeCount = 0),
                ),
            ),
            onPodcastClick = {},
            onSearchClick = {},
            onPasteLinkClick = {},
            onOpenSettings = {},
            onMove = { _, _ -> },
            onQueueNewest = {},
            onMarkAllPlayed = {},
            onRemove = {},
            onUndoMarkAllPlayed = {},
            onLayoutChange = {},
            onRefresh = {},
            onMessageShown = {},
        )
    }
}

@Preview
@Composable
private fun LibraryScreenListPreview() {
    BPodcatTheme {
        LibraryScreen(
            uiState = LibraryUiState(
                isLoading = false,
                layout = LibraryLayout.LIST,
                podcasts = listOf(previewEntry("1", "Podlodka Podcast", newEpisodeCount = 3)),
            ),
            onPodcastClick = {},
            onSearchClick = {},
            onPasteLinkClick = {},
            onOpenSettings = {},
            onMove = { _, _ -> },
            onQueueNewest = {},
            onMarkAllPlayed = {},
            onRemove = {},
            onUndoMarkAllPlayed = {},
            onLayoutChange = {},
            onRefresh = {},
            onMessageShown = {},
        )
    }
}

/**
 * One library entry for the previews.
 *
 * @param id the show's id, which also keys the list.
 * @param title the show's name.
 * @param newEpisodeCount unplayed episodes, for the badge.
 */
private fun previewEntry(id: String, title: String, newEpisodeCount: Int) = PodcastWithCounts(
    podcast = Podcast(
        id = id,
        itunesId = 1209828744L,
        title = title,
        author = "Егор Толстой и другие",
        feedUrl = "https://example.com/feed.rss",
        artworkUrl = null,
        description = "",
        addedAt = Instant.EPOCH,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    ),
    episodeCount = 412,
    newEpisodeCount = newEpisodeCount,
    downloadedCount = 2,
)
