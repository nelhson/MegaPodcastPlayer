package md.borisveriga.megapodcastplayer.feature.moments

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.designsystem.R as DesignSystemR
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.NoteDialog
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.component.ScrollToTopEffect
import md.borisveriga.megapodcastplayer.core.designsystem.component.SectionHeader
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsAction
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeAction
import md.borisveriga.megapodcastplayer.core.designsystem.component.SwipeActionsRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.asAccessibilityActions
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentGroup
import md.borisveriga.megapodcastplayer.core.model.MomentShow
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import md.borisveriga.megapodcastplayer.core.model.MomentsFilter
import md.borisveriga.megapodcastplayer.core.model.groupedByShow
import md.borisveriga.megapodcastplayer.core.model.showsWithMoments

/**
 * The moments screen, wired to its view model.
 *
 * The document picker is launched from here rather than from [MomentsScreen]: it needs an
 * activity result registry, and none exists under `createComposeRule`, which is what the stateless
 * screen is tested with.
 *
 * @param onOpenSettings opens settings; the gear is on every top-level bar (NAV-5).
 * @param scrollToTopSignal how many times this tab has been re-tapped; a change puts the list back
 *   at the top (NAV-4).
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun MomentsRoute(
    onOpenSettings: () -> Unit,
    scrollToTopSignal: Int,
    modifier: Modifier = Modifier,
    viewModel: MomentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportTo) }

    MomentsScreen(
        uiState = uiState,
        onPlay = viewModel::play,
        onEdit = viewModel::edit,
        onDelete = viewModel::delete,
        onExport = { exportLauncher.launch(viewModel.suggestedFileName()) },
        onQueryChange = viewModel::setQuery,
        onShowChange = viewModel::setShow,
        onGroupByShowChange = viewModel::setGroupByShow,
        onOpenSettings = onOpenSettings,
        scrollToTopSignal = scrollToTopSignal,
        onSaveNote = viewModel::saveNote,
        onCancelEdit = viewModel::cancelEdit,
        onUndoDelete = viewModel::undoDelete,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless moments screen.
 *
 * Newest first, across every show, because that is the order a moment is looked for in: the reason
 * to open this screen is usually the thing marked ten minutes ago. That default is unchanged; what
 * MOM-2 adds is a way out of it — a search, a show, and a grouping — for the library that has grown
 * past the point where scrolling is the answer.
 *
 * The controls appear only once there are enough moments to be worth narrowing. A search field over
 * three of them costs more room than the list it filters, which is the judgement the library makes
 * about its own field.
 *
 * @param uiState what to render.
 * @param onPlay plays the episode a moment is in, a few seconds before the mark.
 * @param onEdit opens a moment's note.
 * @param onDelete removes a moment.
 * @param onExport writes every moment to a document.
 * @param onQueryChange invoked as the search field is typed into.
 * @param onShowChange invoked with the show to keep, or null for every show.
 * @param onGroupByShowChange invoked when the grouping is toggled.
 * @param onOpenSettings opens settings; the gear is on every top-level bar (NAV-5).
 * @param scrollToTopSignal how many times this tab has been re-tapped; a change puts the list
 *   back at the top (NAV-4).
 * @param onSaveNote saves the note being edited.
 * @param onCancelEdit closes the note editor without saving.
 * @param onUndoDelete puts back the moment the last delete removed.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    uiState: MomentsUiState,
    onPlay: (MomentWithEpisode) -> Unit,
    onEdit: (MomentWithEpisode) -> Unit,
    onDelete: (MomentWithEpisode) -> Unit,
    onExport: () -> Unit,
    onQueryChange: (String) -> Unit,
    onShowChange: (String?) -> Unit,
    onGroupByShowChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    scrollToTopSignal: Int,
    onSaveNote: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onUndoDelete: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted so the re-tap effect can reach it, and passed down to the list below.
    val listState = rememberLazyListState()

    ScrollToTopEffect(signal = scrollToTopSignal, state = listState)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // LocalResources rather than LocalContext.current.resources, so a configuration change
    // invalidates the read. Resolved here because `LaunchedEffect` runs outside composition.
    val resources = LocalResources.current

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(message.textResId()),
            actionLabel = if (message is MomentsMessage.Deleted) {
                resources.getString(R.string.moments_message_undo)
            } else {
                null
            },
            // Explicit, because Material's default for a snackbar with an action is Indefinite: the
            // undo would sit there until something else replaced it. Short, as the queue's is — the
            // row is already gone, and a long snackbar would sit over the next swipe.
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoDelete() else onMessageShown()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MegaPodcastPlayerTopAppBar(
                title = stringResource(R.string.moments_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    SettingsAction(onClick = onOpenSettings)
                    IconButton(onClick = onExport, enabled = uiState.moments.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Upload,
                            contentDescription = stringResource(R.string.moments_export),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above the switch, and outside it: the controls stay put while the list under them
            // becomes a list, an empty result or neither, which is what lets a search that matches
            // nothing be corrected without first being found again.
            if (uiState.isNarrowable) {
                MomentsControls(
                    filter = uiState.filter,
                    shows = uiState.shows,
                    onQueryChange = onQueryChange,
                    onShowChange = onShowChange,
                    onGroupByShowChange = onGroupByShowChange,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> LoadingState()

                    uiState.isEmpty -> EmptyState(
                        icon = Icons.Rounded.Bookmarks,
                        title = stringResource(R.string.moments_empty_title),
                        description = stringResource(R.string.moments_empty_description),
                    )

                    // A different empty state, because it is a different fact. "You have never
                    // saved a moment" and "none of your moments matches this" want opposite things
                    // done about them, and one message for both would be wrong half the time.
                    uiState.isNarrowedToNothing -> EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.moments_no_matches_title),
                        description = stringResource(R.string.moments_no_matches_description),
                    )

                    else -> MomentList(
                        listState = listState,
                        moments = uiState.moments,
                        groups = uiState.groups,
                        onPlay = onPlay,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    uiState.editing?.let { editing ->
        NoteDialog(
            title = stringResource(R.string.moments_note_title),
            placeholder = stringResource(R.string.moments_note_hint),
            confirmLabel = stringResource(R.string.moments_note_save),
            dismissLabel = stringResource(R.string.moments_note_cancel),
            onSave = onSaveNote,
            onDismiss = onCancelEdit,
            initialNote = editing.moment.note.orEmpty(),
        )
    }
}

/**
 * The narrowing controls: a search field, the show, and the grouping.
 *
 * The field is above and the two chips below it, which is the shape the library screen already
 * uses — one thing typed, then the things chosen. The chip row scrolls sideways for the reason that
 * one does: at the largest text size two chips are wider than a folded Fold 7.
 *
 * @param filter what is in force.
 * @param shows the shows that have moments, for the menu.
 * @param onQueryChange invoked on every keystroke.
 * @param onShowChange invoked with the chosen show, or null for all of them.
 * @param onGroupByShowChange invoked when the grouping is toggled.
 */
@Composable
private fun MomentsControls(
    filter: MomentsFilter,
    shows: List<MomentShow>,
    onQueryChange: (String) -> Unit,
    onShowChange: (String?) -> Unit,
    onGroupByShowChange: (Boolean) -> Unit,
) {
    Column {
        SearchField(query = filter.query, onQueryChange = onQueryChange)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                    vertical = MegaPodcastPlayerTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShowChip(selected = filter.feedUrl, shows = shows, onSelect = onShowChange)
            GroupByShowChip(
                isSelected = filter.groupByShow,
                onSelectedChange = onGroupByShowChange,
            )
        }
    }
}

/**
 * The field that narrows the moments by what is in them.
 *
 * Matched against the note and the episode title, and not the show — the show has a chip of its own
 * beside this, and letting a typed word do both jobs would make a search for one word mean two
 * things. See `narrowedBy` in `:core:model`.
 *
 * @param query what has been typed.
 * @param onQueryChange invoked on every keystroke; blank clears the narrowing.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
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
        placeholder = { Text(text = stringResource(R.string.moments_search_hint)) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            // Only while there is something to clear; an always-present X is a control that does
            // nothing, and it costs the field the width instead.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.moments_search_clear),
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
 * The chip that narrows to one show, and the menu it opens.
 *
 * A *filter* chip rather than the assist chip the sort menus use, because this one can be on: a
 * narrowing in force has to look different from a control that merely exists, or a user comes back
 * to a short list with no idea why. Its label is the chosen show, so the answer is legible without
 * opening anything, and each row carries its count — the menu exists to answer "which show was
 * that in", and a show with forty marks in it is the likelier answer.
 *
 * @param selected the chosen show's feed URL, or null.
 * @param shows the shows that have moments.
 * @param onSelect invoked with the chosen show, or null for all of them.
 */
@Composable
private fun ShowChip(
    selected: String?,
    shows: List<MomentShow>,
    onSelect: (String?) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val chosen = shows.firstOrNull { it.feedUrl == selected }
    // Read here rather than inside `semantics`, which is not a composable scope. The chip's label
    // is a show's name and says no verb; this supplies it.
    val description = stringResource(R.string.moments_filter_show_description)

    Box {
        FilterChip(
            selected = selected != null,
            onClick = { isExpanded = true },
            label = { Text(text = chosen?.title ?: stringResource(R.string.moments_filter_all_shows)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Podcasts, contentDescription = null)
            },
            modifier = Modifier.semantics { contentDescription = description },
        )

        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.moments_filter_all_shows)) },
                onClick = {
                    isExpanded = false
                    onSelect(null)
                },
            )
            shows.forEach { show ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                R.string.moments_filter_show_option,
                                show.title,
                                show.momentCount,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        isExpanded = false
                        onSelect(show.feedUrl)
                    },
                )
            }
        }
    }
}

/**
 * The chip that gathers the list under show headings.
 *
 * @param isSelected whether grouping is on.
 * @param onSelectedChange invoked with the new state.
 */
@Composable
private fun GroupByShowChip(isSelected: Boolean, onSelectedChange: (Boolean) -> Unit) {
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
        label = { Text(text = stringResource(R.string.moments_filter_group_by_show)) },
        modifier = Modifier.semantics { stateDescription = state },
    )
}

/**
 * The list itself, flat or under show headings.
 *
 * Keyed by row id so that deleting one animates the rest rather than rebuilding the list, and so
 * a half-open swipe belongs to the moment it was opened on even as rows above it disappear.
 * The headings are keyed by feed URL for the same reason.
 *
 * @param listState the scroll position, hoisted so a re-tap on the tab can reset it (NAV-4).
 * @param moments what to draw when the list is flat, newest first.
 * @param groups the same moments under headings; empty unless grouping is on.
 * @param onPlay plays a moment.
 * @param onEdit opens one's note.
 * @param onDelete removes one.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentList(
    listState: LazyListState,
    moments: List<MomentWithEpisode>,
    groups: List<MomentGroup>,
    onPlay: (MomentWithEpisode) -> Unit,
    onEdit: (MomentWithEpisode) -> Unit,
    onDelete: (MomentWithEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            items(items = moments, key = { it.moment.id }) { entry ->
                MomentRow(
                    entry = entry,
                    onPlay = { onPlay(entry) },
                    onEdit = { onEdit(entry) },
                    onDelete = { onDelete(entry) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        } else {
            groups.forEach { group ->
                item(key = group.feedUrl) {
                    SectionHeader(text = group.title)
                }
                items(items = group.moments, key = { it.moment.id }) { entry ->
                    MomentRow(
                        entry = entry,
                        onPlay = { onPlay(entry) },
                        onEdit = { onEdit(entry) },
                        onDelete = { onDelete(entry) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
    }
}

/**
 * One moment.
 *
 * The note is the headline when there is one, and the episode title when there is not. That is the
 * right way round: a moment with a note is remembered *by* the note, and a moment without one is
 * only ever "that spot in that episode". Which is why the episode title moves to the line above
 * rather than disappearing — the row must still say what it is about.
 *
 * **A long swipe deletes, as it removes a row from the queue.** A short pull opens the row onto two
 * buttons, Delete beside the row and Edit note at the edge, each needing a tap. Deleting the one
 * piece of writing in this app that is the user's own on a fast pull is safe only because the
 * snackbar offers it back, and [MomentsViewModel.delete] carries the whole row so that the undo has
 * something to rebuild from.
 *
 * There is no overflow menu: the swipe is the row's only control besides the tap that plays it, and
 * a screen reader — which can see no gesture — is given both actions as custom actions instead.
 *
 * @param entry the moment and its episode.
 * @param onPlay plays it.
 * @param onEdit opens its note.
 * @param onDelete removes it.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentRow(
    entry: MomentWithEpisode,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteAction = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.moments_action_delete),
        // The error palette, as the downloads screen's delete: the row is leaving.
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onDelete,
    )
    val editAction = SwipeAction(
        icon = Icons.Rounded.EditNote,
        label = stringResource(R.string.moments_action_note),
        // The primary palette: this is the row being used rather than the row leaving, and it has
        // to read as the opposite of the delete button beside it.
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onEdit,
    )
    // Delete first: it is the action the long pull commits, and the one drawn nearest the row.
    val actions = listOf(deleteAction, editAction)

    SwipeActionsRow(actions = actions, fullSwipeAction = deleteAction, modifier = modifier) {
        MomentRowContent(entry = entry, actions = actions, onPlay = onPlay)
    }
}

/**
 * The row under the swipe.
 *
 * @param entry the moment and its episode.
 * @param actions what the swipe offers, so a screen reader — which can see no gesture — is given
 *   the same two things as custom actions on the row's merged node.
 * @param onPlay plays it.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentRowContent(
    entry: MomentWithEpisode,
    actions: List<SwipeAction>,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timecode = formatPosition(entry.moment.positionMs)
    val note = entry.moment.note?.takeIf { it.isNotBlank() }
    val swipeActions = actions.asAccessibilityActions()

    ListItem(
        headlineContent = {
            Text(
                text = note ?: entry.episodeTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        overlineContent = if (note != null) {
            {
                Text(
                    text = entry.episodeTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.moments_row_subtitle, entry.showTitle, timecode),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            PodcastArtwork(url = entry.showArtworkUrl, size = ArtworkSize.Row)
        },
        modifier = modifier
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.moments_row_play, timecode),
                onClick = onPlay,
            )
            // On the row itself, whose `clickable` has already merged its children into one node:
            // that merged node is where a screen reader looks for what the row can do, and it
            // cannot see the swipe around it.
            .semantics { customActions = swipeActions },
    )
}

/**
 * The message a snackbar shows for this outcome.
 *
 * @return the string resource to read.
 */
private fun MomentsMessage.textResId(): Int = when (this) {
    is MomentsMessage.Deleted -> R.string.moments_message_deleted
    MomentsMessage.Exported -> R.string.moments_message_exported
    MomentsMessage.ExportFailed -> R.string.moments_message_export_failed
    MomentsMessage.NothingToExport -> R.string.moments_message_nothing_to_export
}

/**
 * What the picker is asked to create.
 *
 * `text/markdown` is registered but not universally known; a provider that does not recognise it
 * still creates the file, and the `.md` the suggested name carries is what every reader actually
 * goes by.
 */
private const val EXPORT_MIME_TYPE = "text/markdown"

/**
 * A saved moment, for the previews below.
 *
 * @param id the row id.
 * @param positionMs where in the episode it was marked.
 * @param note what the user typed, or null for a bare mark.
 */
private fun previewMoment(
    id: Long,
    positionMs: Long,
    note: String?,
    showTitle: String = "Podlodka Podcast",
    feedUrl: String = "https://podlodka.io/rss",
) = MomentWithEpisode(
    moment = Moment(
        id = id,
        episodeId = "e1",
        positionMs = positionMs,
        note = note,
        createdAtMs = 1_756_000_000_000L,
    ),
    episodeTitle = "Podlodka #492 — Как устроены дизайн-системы",
    showTitle = showTitle,
    showArtworkUrl = null,
    feedUrl = feedUrl,
    audioUrl = "https://cdn.example.com/e1.mp3",
)

/** Two moments, which is fewer than the screen offers to narrow. */
private val previewMoments = listOf(
    previewMoment(1L, 743_000L, "Определение дизайн-системы, которое стоит записать"),
    previewMoment(2L, 1_820_000L, null),
)

/**
 * Enough moments, across two shows, for the narrowing controls to appear.
 *
 * Its own sample rather than a longer version of [previewMoments]: the controls are drawn above a
 * threshold, and a preview that did not cross it would be a picture of the screen as it was before
 * MOM-2 — which the other preview already is.
 */
private val previewManyMoments = previewMoments + listOf(
    previewMoment(3L, 120_000L, "Про найм"),
    previewMoment(4L, 400_000L, null),
    previewMoment(5L, 900_000L, "Тестирование на проде", "Radio-T", "https://radio-t.com/rss"),
    previewMoment(6L, 1_100_000L, null, "Radio-T", "https://radio-t.com/rss"),
    previewMoment(7L, 1_500_000L, "Ссылка на статью", "Radio-T", "https://radio-t.com/rss"),
    previewMoment(8L, 2_000_000L, null, "Radio-T", "https://radio-t.com/rss"),
)

@ThemePreviews
@FontScalePreviews
@Composable
internal fun MomentsScreenPreview() {
    MegaPodcastPlayerTheme {
        MomentsScreen(
            uiState = MomentsUiState(
                moments = previewMoments,
                savedCount = previewMoments.size,
                isLoading = false,
            ),
            onPlay = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onQueryChange = {},
            onShowChange = {},
            onGroupByShowChange = {},
            onOpenSettings = {},
            scrollToTopSignal = 0,
            onSaveNote = {},
            onCancelEdit = {},
            onUndoDelete = {},
            onMessageShown = {},
        )
    }
}

/**
 * The screen once there are enough moments to narrow: the controls, and the list under show
 * headings. Grouping is on because that is the arrangement the flat preview cannot show.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun MomentsScreenGroupedPreview() {
    MegaPodcastPlayerTheme {
        MomentsScreen(
            uiState = MomentsUiState(
                moments = previewManyMoments,
                groups = previewManyMoments.groupedByShow(),
                shows = previewManyMoments.showsWithMoments(),
                filter = MomentsFilter(groupByShow = true),
                savedCount = previewManyMoments.size,
                isLoading = false,
            ),
            onPlay = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onQueryChange = {},
            onShowChange = {},
            onGroupByShowChange = {},
            onOpenSettings = {},
            scrollToTopSignal = 0,
            onSaveNote = {},
            onCancelEdit = {},
            onUndoDelete = {},
            onMessageShown = {},
        )
    }
}

@ThemePreviews
@Composable
internal fun MomentsScreenEmptyPreview() {
    MegaPodcastPlayerTheme {
        MomentsScreen(
            uiState = MomentsUiState(isLoading = false),
            onPlay = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onQueryChange = {},
            onShowChange = {},
            onGroupByShowChange = {},
            onOpenSettings = {},
            scrollToTopSignal = 0,
            onSaveNote = {},
            onCancelEdit = {},
            onUndoDelete = {},
            onMessageShown = {},
        )
    }
}
