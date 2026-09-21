package md.borisveriga.megapodcastplayer.feature.library

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.R as DesignSystemR
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.ScrollToTopEffect
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowTile
import md.borisveriga.megapodcastplayer.core.designsystem.component.SortMenuChip
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeAction
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeActionsRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.WavyProgressLine
import md.borisveriga.megapodcastplayer.core.designsystem.component.asAccessibilityActions
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.ReorderableState
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.moveActions
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.megapodcastplayer.core.designsystem.reorder.reorderableLongPressDrag
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.LibraryFilter
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.LibrarySort
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts

/**
 * Library screen: every subscribed show, refreshed on entry, with a button into search.
 *
 * @param onPodcastClick invoked with a podcast id when a show is tapped.
 * @param onSearchClick invoked when the user wants to look a show up by name.
 * @param onOpenSettings invoked when the user taps the top bar's settings action.
 * @param onMove invoked with positions in the library once a reorder gesture finishes.
 * @param modifier layout modifier.
 * @param selectedPodcastId the show a detail pane beside this list is showing, or null when the
 *   library is the whole screen. Passed in rather than remembered here: which show is open is a
 *   fact about the layout the library is embedded in, and the library that fills a folded phone
 *   has no answer to it.
 * @param viewModel injected by Hilt.
 */
@Composable
fun LibraryRoute(
    onPodcastClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onOpenSettings: () -> Unit,
    scrollToTopSignal: Int,
    modifier: Modifier = Modifier,
    selectedPodcastId: String? = null,
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
        onOpenSettings = onOpenSettings,
        onMove = viewModel::move,
        onRemove = viewModel::remove,
        onLayoutChange = viewModel::setLayout,
        onSortChange = viewModel::setSort,
        onQueryChange = viewModel::setQuery,
        onOnlyWithNewEpisodesChange = viewModel::setOnlyWithNewEpisodes,
        onClearFilter = viewModel::clearFilter,
        onRefresh = viewModel::refreshAll,
        onMessageShown = viewModel::onMessageShown,
        selectedPodcastId = selectedPodcastId,
        scrollToTopSignal = scrollToTopSignal,
        modifier = modifier,
    )
}

/**
 * Stateless library screen.
 *
 * @param uiState what to render.
 * @param onPodcastClick show tap handler.
 * @param onSearchClick opens search by name.
 * @param onOpenSettings opens the settings screen.
 * @param scrollToTopSignal see [LibraryScreen].
 * @param onMove applies a completed reorder, as positions within [LibraryUiState.podcasts].
 *   Called once on release rather than per frame: one gesture is one edit.
 * @param onRemove removes a show, once the confirmation this screen owns has been accepted.
 * @param onLayoutChange grid/list toggle handler.
 * @param onSortChange invoked with the order to list the shows in.
 * @param onQueryChange invoked as the filter field is typed into.
 * @param onOnlyWithNewEpisodesChange invoked when the *Has new episodes* chip is toggled.
 * @param onClearFilter drops every narrowing, showing the whole library again.
 * @param onRefresh pull-to-refresh handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param scrollToTopSignal how many times this tab has been re-tapped; a change puts the list
 *   or grid back at the top (NAV-4).
 * @param modifier layout modifier.
 * @param selectedPodcastId the show a detail pane beside this list is showing, or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onPodcastClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (PodcastWithCounts) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onQueryChange: (String) -> Unit,
    onOnlyWithNewEpisodesChange: (Boolean) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    scrollToTopSignal: Int,
    modifier: Modifier = Modifier,
    selectedPodcastId: String? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition rather than inside the effect: `LaunchedEffect` runs outside the
    // composition, where `stringResource` is not available. `LocalResources` rather than
    // `LocalContext.current.resources`, so a configuration change invalidates the read.
    val resources = LocalResources.current
    // Pinned rather than collapsing: this is one of three tabs, and the bar is what tells the user
    // which of them they are on. A large bar would spend the top third of the screen restating the
    // tab the navigation bar already highlights, and then scroll the name away exactly when a fast
    // scroll makes it easiest to lose track of.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // The show a removal is being confirmed for, held as an id rather than the object so it too
    // survives the fold — and so a show that disappears under the dialog simply closes it.
    var pendingRemovalId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRemoval = pendingRemovalId?.let { id ->
        uiState.podcasts.firstOrNull { it.podcast.id == id }
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = message.toText(resources))
        onMessageShown()
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
            MegaPodcastPlayerTopAppBar(
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
            AddButton(onSearchClick = onSearchClick)
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

            // Drawn above the list rather than in the top bar: they describe the list, they
            // change while it is being looked at, and the bar has no room left. Hidden entirely
            // while the library is empty or still loading, when there is nothing to order.
            if (!uiState.isLoading && uiState.libraryCount > 0) {
                LibraryControls(
                    sort = uiState.sort,
                    filter = uiState.filter,
                    isNarrowable = uiState.isNarrowable,
                    onSortChange = onSortChange,
                    onQueryChange = onQueryChange,
                    onOnlyWithNewEpisodesChange = onOnlyWithNewEpisodesChange,
                )
            }

            when {
                uiState.isLoading -> LoadingState(
                    contentDescription = stringResource(R.string.library_loading),
                )

                // A filter that matches nothing is not an empty library, and offering "search
                // Apple Podcasts" to someone who has mistyped the name of a show they already
                // follow would be answering a question nobody asked.
                uiState.isFilteredEmpty -> EmptyState(
                    icon = Icons.Rounded.FilterList,
                    title = stringResource(R.string.library_filtered_empty_title),
                    description = stringResource(R.string.library_filtered_empty_description),
                    actionLabel = stringResource(R.string.library_filter_clear),
                    onAction = onClearFilter,
                )

                // No pull-to-refresh here: there are no feeds to fetch, and the gesture needs
                // something scrollable underneath it to work at all.
                uiState.podcasts.isEmpty() -> EmptyState(
                    // The podcast mark, not a layout glyph. `GridView` says "grid", which is a way
                    // of *arranging* shows and not a way of having none — the same mark the artwork
                    // placeholder uses is what "no shows yet" actually looks like (NAV-8).
                    icon = Icons.Rounded.Podcasts,
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
                            scrollToTopSignal = scrollToTopSignal,
                            podcasts = uiState.podcasts,
                            isReorderable = uiState.isReorderable,
                            selectedPodcastId = selectedPodcastId,
                            onPodcastClick = onPodcastClick,
                            onMove = onMove,
                            onRemoveRequest = { pendingRemovalId = it.podcast.id },
                        )

                        LibraryLayout.LIST -> ShowList(
                            scrollToTopSignal = scrollToTopSignal,
                            podcasts = uiState.podcasts,
                            isReorderable = uiState.isReorderable,
                            selectedPodcastId = selectedPodcastId,
                            onPodcastClick = onPodcastClick,
                            onMove = onMove,
                            onRemoveRequest = { pendingRemovalId = it.podcast.id },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The row (or two) that says what the library is showing: in which order, and narrowed to what.
 *
 * The sort control is always here, because a library of any size can be in the wrong order. The
 * two narrowings appear only once the library is long enough to need them — see
 * [LibraryUiState.isNarrowable] — so a new library of three shows is not handed a search field
 * pointing at three shows.
 *
 * @param sort the order currently applied.
 * @param filter what the list is narrowed to.
 * @param isNarrowable whether the library is long enough for the narrowing controls.
 * @param onSortChange invoked with the chosen order.
 * @param onQueryChange invoked as the field is typed into.
 * @param onOnlyWithNewEpisodesChange invoked when the *Has new episodes* chip is toggled.
 */
@Composable
private fun LibraryControls(
    sort: LibrarySort,
    filter: LibraryFilter,
    isNarrowable: Boolean,
    onSortChange: (LibrarySort) -> Unit,
    onQueryChange: (String) -> Unit,
    onOnlyWithNewEpisodesChange: (Boolean) -> Unit,
) {
    Column {
        if (isNarrowable) {
            // Somewhere for focus to land that is not the field below. When a screen is given
            // focus — on arriving at this tab, on coming back to it — it goes to the first thing
            // that will take it, and in touch mode a text field is the only thing here that will:
            // buttons decline. A focused field opens the keyboard, so the library opened with half
            // of itself covered by a keyboard nobody asked for. This comes first, takes the focus
            // and shows nothing for it; the field still takes it on a tap, which is the only time
            // the keyboard is wanted. A leaf with its semantics cleared, not the screen itself:
            // a focusable screen is one unlabelled stop TalkBack would read the whole page from.
            //
            // In a box with the field rather than above it, so that it costs the layout nothing:
            // it sits in the corner of the field's own padding.
            Box {
                Spacer(
                    modifier = Modifier
                        .size(FOCUS_SINK_SIZE)
                        .clearAndSetSemantics {}
                        .focusable(),
                )
                FilterField(query = filter.query, onQueryChange = onQueryChange)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Scrollable for the same reason the show page's control row is: at the largest
                // font scale two chips are wider than a folded Fold 7.
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                    vertical = MegaPodcastPlayerTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortMenuChip(
                label = stringResource(sort.labelResId),
                options = LibrarySort.entries,
                selected = sort,
                onSelect = onSortChange,
                menuDescription = stringResource(R.string.library_sort_description),
                optionLabel = { option -> stringResource(option.labelResId) },
            )

            if (isNarrowable) {
                NewEpisodesChip(
                    isSelected = filter.onlyWithNewEpisodes,
                    onSelectedChange = onOnlyWithNewEpisodesChange,
                )
            }
        }
    }
}

/**
 * The field that narrows the library by name.
 *
 * Matched against the author as well as the title, which is not visible here and is deliberate:
 * half the shows in a library are remembered by who makes them, and a field that silently refused
 * to find "Podlodka" by its hosts would read as broken.
 *
 * Its own value comes from the state rather than from a local `remember`, so what has been typed
 * survives the fold and the process the same way the list under it does.
 *
 * @param query what has been typed.
 * @param onQueryChange invoked on every keystroke; blank clears the narrowing.
 */
@Composable
private fun FilterField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                vertical = MegaPodcastPlayerTheme.spacing.sm,
            ),
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.library_filter_hint)) },
        leadingIcon = {
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
        },
        trailingIcon = {
            // Only while there is something to clear: an always-present X on an empty field is a
            // control that does nothing, and it costs the field the width instead.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.library_filter_clear),
                    )
                }
            }
        },
        // Nothing to submit — the list narrows as it is typed — so the key that would say "Search"
        // says "Done" and puts the keyboard away.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

/**
 * The chip that hides every show with nothing new.
 *
 * *New* rather than *unplayed*: it counts episodes that arrived since the user last looked, which
 * is what someone opening the library in the morning is asking about. A show with forty unplayed
 * episodes and nothing new is not what they came for, and it is exactly what a chip labelled
 * "unplayed" would hand them.
 *
 * @param isSelected whether the narrowing is on.
 * @param onSelectedChange invoked with the new state.
 */
@Composable
private fun NewEpisodesChip(isSelected: Boolean, onSelectedChange: (Boolean) -> Unit) {
    // Built here rather than inside `semantics`, which is not a composable scope. The same two
    // words every other chip in the app announces, from the design system.
    val state = stringResource(
        if (isSelected) {
            DesignSystemR.string.designsystem_chip_selected
        } else {
            DesignSystemR.string.designsystem_chip_not_selected
        },
    )
    FilterChip(
        selected = isSelected,
        onClick = { onSelectedChange(!isSelected) },
        label = { Text(text = stringResource(R.string.library_filter_has_new)) },
        modifier = Modifier.semantics { stateDescription = state },
    )
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
 * Dragging is offered only while the grid is showing the stored arrangement; see
 * [LibraryUiState.isReorderable]. Removing is offered always, through a context menu the same press
 * opens when it is released without travelling (LIB-4/D-8): a grid and a list showing the same
 * library should be able to do the same things to it, and until this the list's swipe was the only
 * way out of a subscription short of opening the show.
 *
 * @param podcasts the library.
 * @param isReorderable whether the drag gesture and its spoken equivalents are on offer.
 * @param selectedPodcastId the show a detail pane beside this grid is showing, or null.
 * @param onPodcastClick tile tap handler.
 * @param onMove reports a finished reorder as positions in [podcasts].
 * @param onRemoveRequest asks for a show to be removed; the screen confirms before it happens.
 */
@Composable
private fun ShowGrid(
    scrollToTopSignal: Int,
    podcasts: List<PodcastWithCounts>,
    isReorderable: Boolean,
    selectedPodcastId: String?,
    onPodcastClick: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemoveRequest: (PodcastWithCounts) -> Unit,
) {
    val resources = LocalResources.current
    val moveUp = stringResource(R.string.library_move_up)
    val moveDown = stringResource(R.string.library_move_down)
    val removeLabel = stringResource(R.string.library_action_remove)
    val gridState = rememberLazyGridState()
    // The show whose menu is open, as an id rather than an index: the grid re-lays itself out on
    // every emission, and a menu anchored to position 4 would follow whichever show arrives there.
    // Not saved across process death on purpose - a menu is a gesture in flight, not a place.
    var menuForId by remember { mutableStateOf<String?>(null) }

    ScrollToTopEffect(signal = scrollToTopSignal, state = gridState)

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
        contentPadding = PaddingValues(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        itemsIndexed(
            items = drag.order,
            key = { _, entry -> entry.podcast.id },
        ) { index, entry ->
            val isDragging = drag.draggingKey == entry.podcast.id

            // The menu is anchored to the tile it belongs to, so it opens where the finger is
            // rather than at a corner of the grid.
            Box {
                ShowTile(
                    title = entry.podcast.title,
                    artworkUrl = entry.podcast.artworkUrl,
                    author = entry.podcast.author,
                    source = entry.podcast.source,
                    badgeCount = entry.newEpisodeCount,
                    downloadedCount = entry.downloadedCount,
                    stateDescription = entry.newEpisodeDescription(resources),
                    isSelected = entry.podcast.id == selectedPodcastId,
                    onClick = { onPodcastClick(entry.podcast.id) },
                    modifier = Modifier
                        // On the tile itself, which merges its children: that merged node is what a
                        // screen reader lands on, and neither a drag nor a press held in place is
                        // visible to one. The removal is published as an action rather than as
                        // "open the menu", because a menu is a way of reaching a thing and the
                        // thing is what a screen reader should be handed.
                        .semantics {
                            val moves = if (isReorderable) {
                                drag.moveActions(index, moveUp, moveDown)
                            } else {
                                emptyList()
                            }
                            customActions = moves + CustomAccessibilityAction(removeLabel) {
                                onRemoveRequest(entry)
                                true
                            }
                        }
                        .graphicsLayer {
                            // Only the dragged tile moves; the rest are re-laid-out by the grid as
                            // the order changes, which is what makes the gap follow the finger.
                            translationX = if (isDragging) drag.offset.x else 0f
                            translationY = if (isDragging) drag.offset.y else 0f
                            // Lifts it above its neighbours so it is not clipped by them mid-drag.
                            shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
                        }
                        // The press is held whether or not this grid can be rearranged: where a
                        // computed order or an active filter takes the drag away (see
                        // [LibraryUiState.isReorderable]) the menu is the only thing a tile
                        // offers, and losing it with the drag would make removing a show depend on
                        // which order the library happens to be in.
                        .reorderableLongPressDrag(
                            state = drag,
                            key = entry.podcast.id,
                            enabled = isReorderable,
                            onReleasedInPlace = { menuForId = entry.podcast.id },
                        ),
                )

                ShowTileMenu(
                    expanded = menuForId == entry.podcast.id,
                    onDismiss = { menuForId = null },
                    onRemove = {
                        menuForId = null
                        onRemoveRequest(entry)
                    },
                )
            }
        }
    }
}

/**
 * What a tile offers when its press is held and released in place.
 *
 * One entry, and that is not an oversight. The menu exists to close the gap the grid had against
 * the list, and the list's swipe holds exactly one thing: a show can be removed. The show's own
 * page offers two more - its settings sheet and a rebuild - and both stay there, because both need
 * what the library has not got. A rebuild has to free the downloads of the episodes it drops, which
 * the download stack the show's page holds does; the settings sheet is the
 * show's page in miniature. Copying either into a second module would be the second place to
 * maintain that this plan keeps declining to build (D-23).
 *
 * @param expanded whether this tile's menu is the open one.
 * @param onDismiss closes it, tapped away or backed out of.
 * @param onRemove asks for the show to be removed; the screen still confirms.
 */
@Composable
private fun ShowTileMenu(expanded: Boolean, onDismiss: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.library_action_remove)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Delete, contentDescription = null)
            },
            onClick = onRemove,
        )
    }
}

/**
 * The shows as rows, for a library too long to recognise by cover alone.
 *
 * Rearrangeable by a long press on the row, the same gesture and the same code as the grid, and
 * swipeable from right to left to reveal the one thing a show can be told to do. The grid gets none
 * of the swipe: a tile is 148dp of artwork with nothing to spare, and a revealed button would leave
 * no tile.
 *
 * @param podcasts the library.
 * @param isReorderable whether the drag gesture is on offer; the swipe is not affected by it,
 *   because removing a show means the same thing in every order.
 * @param selectedPodcastId the show a detail pane beside this list is showing, or null.
 * @param onPodcastClick row tap handler.
 * @param onMove reports a finished reorder as positions in [podcasts].
 * @param onRemoveRequest asks for the show to be removed; the screen confirms before it happens.
 */
@Composable
private fun ShowList(
    scrollToTopSignal: Int,
    podcasts: List<PodcastWithCounts>,
    isReorderable: Boolean,
    selectedPodcastId: String?,
    onPodcastClick: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemoveRequest: (PodcastWithCounts) -> Unit,
) {
    val listState = rememberLazyListState()

    ScrollToTopEffect(signal = scrollToTopSignal, state = listState)

    val drag = rememberReorderableState(
        layout = rememberReorderableLayout(listState),
        items = podcasts,
        keyOf = { it.podcast.id },
        onMove = onMove,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = MegaPodcastPlayerTheme.spacing.sm),
    ) {
        itemsIndexed(
            items = drag.order,
            key = { _, entry -> entry.podcast.id },
        ) { index, entry ->
            ShowListRow(
                entry = entry,
                index = index,
                drag = drag,
                isReorderable = isReorderable,
                isSelected = entry.podcast.id == selectedPodcastId,
                onPodcastClick = { onPodcastClick(entry.podcast.id) },
                onRemoveRequest = { onRemoveRequest(entry) },
            )
        }
    }
}

/**
 * One library row: draggable, swipeable, and tappable into the show.
 *
 * Unsubscribing is the only thing on offer, and it is rare and large, so it sits behind the reveal
 * rather than on a full swipe: it has to be read, and then tapped, before anything happens.
 *
 * @param entry the show.
 * @param index its position in the library, for the reorder actions.
 * @param drag the shared drag state, which owns the visual offset and the pending move.
 * @param isReorderable whether this row can be picked up.
 * @param isSelected whether a detail pane beside this list is showing this show.
 * @param onPodcastClick opens the show.
 * @param onRemoveRequest asks for it to be removed.
 */
@Composable
private fun ShowListRow(
    entry: PodcastWithCounts,
    index: Int,
    drag: ReorderableState<PodcastWithCounts>,
    isReorderable: Boolean,
    isSelected: Boolean,
    onPodcastClick: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    val resources = LocalResources.current
    val moveUp = stringResource(R.string.library_move_up)
    val moveDown = stringResource(R.string.library_move_down)
    val isDragging = drag.draggingKey == entry.podcast.id

    val remove = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.library_action_remove),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onRemoveRequest,
    )

    SwipeActionsRow(
        actions = listOf(remove),
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
                    val moves = if (isReorderable) {
                        drag.moveActions(index, moveUp, moveDown)
                    } else {
                        emptyList()
                    }
                    customActions = moves + listOf(remove).asAccessibilityActions()
                }
                // Inside the swipe box rather than around it, so the row's two drags are settled by
                // the pointer that started them: this one consumes movement only once the press has
                // been held, and a horizontal swipe claims the gesture long before that.
                .then(
                    if (isReorderable) {
                        Modifier.reorderableLongPressDrag(drag, entry.podcast.id)
                    } else {
                        Modifier
                    },
                ),
            title = entry.podcast.title,
            author = entry.podcast.author,
            metadata = entry.countsLine(resources),
            artworkUrl = entry.podcast.artworkUrl,
            downloadedCount = entry.downloadedCount,
            source = entry.podcast.source,
            stateDescription = entry.newEpisodeDescription(resources),
            isSelected = isSelected,
            onClick = onPodcastClick,
            trailing = {
                // The same mark the grid puts on a cover. Without it the list would be the layout
                // that cannot answer "which of these has something new".
                if (entry.newEpisodeCount > 0) {
                    Badge(
                        containerColor = MegaPodcastPlayerTheme.colors.unplayed,
                        contentColor = MegaPodcastPlayerTheme.colors.onUnplayed,
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
            Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
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
 * The add button: one tap, straight into search.
 *
 * This used to open a two-item menu naming "Search" and "Paste a link" separately, because a single
 * button that opened search left the pasted-link route unadvertised. The menu bought that wording
 * at the price of a tap on every single add, which is the common case; search now opens with the
 * field already focused and the keyboard up, so a pasted link goes in exactly where a typed name
 * does and the extra step earns nothing.
 *
 * @param onSearchClick opens search.
 */
@Composable
private fun AddButton(onSearchClick: () -> Unit) {
    FloatingActionButton(
        onClick = onSearchClick,
        // Held further off the end edge than the Scaffold's own FAB inset puts it.
        modifier = Modifier.padding(end = AddButtonEndPadding),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.library_add_podcast),
        )
    }
}

/**
 * The counts line under a show: how many episodes there are, and how many were never started.
 *
 * How many are on the device is no longer among them. It is the one count that is also a glyph —
 * the row draws the mark and the number in front of this line — and writing it out as well made it
 * three clauses long to say what the mark says at a glance.
 *
 * @param resources for the plurals.
 * @return the metadata line.
 */
private fun PodcastWithCounts.countsLine(resources: Resources): String {
    // "videos" rather than "episodes" for a playlist: it is what the user called them when they
    // added it.
    val parts = buildList {
        add(
            resources.getQuantityString(
                if (podcast.source == PodcastSource.YOUTUBE) {
                    R.plurals.library_video_count
                } else {
                    R.plurals.library_episode_count
                },
                episodeCount,
                episodeCount,
            ),
        )
        // *Unplayed*, never started — the other half of the vocabulary the badge's *new* is. The
        // row is the one place both facts are on screen at once, and it is what makes the badge
        // legible: three new, and thirty never started, are different things to know.
        if (unplayedCount > 0) {
            add(
                resources.getQuantityString(
                    R.plurals.library_unplayed_count,
                    unplayedCount,
                    unplayedCount,
                ),
            )
        }
    }
    // Joined through the resource rather than with a literal separator, so a translator can
    // re-punctuate the line; folded rather than formatted, because it is two or three parts long.
    return parts.reduce { line, part ->
        resources.getString(R.string.library_counts_combined, line, part)
    }
}

/**
 * The caption on each order in the sort menu.
 *
 * Beside the screen that draws them rather than on [LibrarySort] itself: the rule an order applies
 * is a fact about a library and lives in `:core:model`, the words for it are a fact about this
 * screen and live with the `strings.xml` that holds them.
 */
@get:StringRes
private val LibrarySort.labelResId: Int
    get() = when (this) {
        LibrarySort.MANUAL -> R.string.library_sort_manual
        LibrarySort.RECENTLY_UPDATED -> R.string.library_sort_recent
        LibrarySort.TITLE -> R.string.library_sort_title
        LibrarySort.MOST_UNPLAYED -> R.string.library_sort_unplayed
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

/** How far the add button sits from the end edge, on top of the Scaffold's own inset. */
private val AddButtonEndPadding = 32.dp

/** How far a dragged show is lifted above its neighbours, so they cannot clip it. */
private const val DRAG_ELEVATION = 8f

/**
 * A library with nothing in it.
 *
 * It had no preview and therefore no golden, which is how it kept a `GridView` glyph — a picture of
 * a *layout* — as its answer to "no shows yet" for as long as it did (NAV-8). The state a new
 * install opens on is worth a picture.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun LibraryScreenEmptyPreview() {
    MegaPodcastPlayerTheme {
        LibraryScreen(
            uiState = LibraryUiState(isLoading = false, podcasts = emptyList()),
            onPodcastClick = {},
            onSearchClick = {},
            onOpenSettings = {},
            onMove = { _, _ -> },
            onRemove = {},
            onLayoutChange = {},
            onSortChange = {},
            onQueryChange = {},
            onOnlyWithNewEpisodesChange = {},
            onClearFilter = {},
            onRefresh = {},
            onMessageShown = {},
            scrollToTopSignal = 0,
        )
    }
}

@ThemePreviews
@FontScalePreviews
@Composable
internal fun LibraryScreenPreview() {
    MegaPodcastPlayerTheme {
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
            onOpenSettings = {},
            onMove = { _, _ -> },
            onRemove = {},
            onLayoutChange = {},
            onSortChange = {},
            onQueryChange = {},
            onOnlyWithNewEpisodesChange = {},
            onClearFilter = {},
            onRefresh = {},
            onMessageShown = {},
            scrollToTopSignal = 0,
        )
    }
}

@ThemePreviews
@FontScalePreviews
@Composable
internal fun LibraryScreenListPreview() {
    MegaPodcastPlayerTheme {
        LibraryScreen(
            uiState = LibraryUiState(
                isLoading = false,
                layout = LibraryLayout.LIST,
                podcasts = listOf(previewEntry("1", "Podlodka Podcast", newEpisodeCount = 3)),
            ),
            onPodcastClick = {},
            onSearchClick = {},
            onOpenSettings = {},
            onMove = { _, _ -> },
            onRemove = {},
            onLayoutChange = {},
            onSortChange = {},
            onQueryChange = {},
            onOnlyWithNewEpisodesChange = {},
            onClearFilter = {},
            onRefresh = {},
            onMessageShown = {},
            scrollToTopSignal = 0,
        )
    }
}

/**
 * The library as the list pane of a two-pane layout, with the open show marked.
 *
 * A list rather than a grid, and deliberately: the row is where the wash has the most to prove,
 * because it runs the full width behind a title, an author, a counts line and a badge, and every
 * one of them has to stay legible on it.
 */
@ThemePreviews
@Composable
internal fun LibraryScreenSelectedPreview() {
    MegaPodcastPlayerTheme {
        LibraryScreen(
            uiState = LibraryUiState(
                isLoading = false,
                layout = LibraryLayout.LIST,
                podcasts = listOf(
                    previewEntry("1", "Podlodka Podcast", newEpisodeCount = 3),
                    previewEntry("2", "Acquired", newEpisodeCount = 0),
                ),
            ),
            onPodcastClick = {},
            onSearchClick = {},
            onOpenSettings = {},
            onMove = { _, _ -> },
            onRemove = {},
            onLayoutChange = {},
            onSortChange = {},
            onQueryChange = {},
            onOnlyWithNewEpisodesChange = {},
            onClearFilter = {},
            onRefresh = {},
            onMessageShown = {},
            scrollToTopSignal = 0,
            selectedPodcastId = "1",
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
    unplayedCount = 37,
)

/** The size of the library's focus sink: something, because a node of no size is offered no focus. */
private val FOCUS_SINK_SIZE = 1.dp
