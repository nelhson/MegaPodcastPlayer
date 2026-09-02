package md.borisveriga.bpodcat.feature.podcast

import android.content.res.Resources
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.common.format.formatDuration
import md.borisveriga.bpodcat.core.common.format.formatPublishedDate
import md.borisveriga.bpodcat.core.common.format.formatRemaining
import md.borisveriga.bpodcat.core.common.format.toPlainText
import md.borisveriga.bpodcat.core.designsystem.R as DesignSystemR
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkBackdrop
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.BPodcatTopAppBar
import md.borisveriga.bpodcat.core.designsystem.component.DownloadButton
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.component.SourceBadge
import md.borisveriga.bpodcat.core.designsystem.component.WavyProgressLine
import md.borisveriga.bpodcat.core.designsystem.reorder.ReorderHandle
import md.borisveriga.bpodcat.core.designsystem.reorder.moveActions
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableLayout
import md.borisveriga.bpodcat.core.designsystem.reorder.rememberReorderableState
import md.borisveriga.bpodcat.core.designsystem.reorder.reorderableHandle
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Podcast detail screen: the show's header and its episode list.
 *
 * @param onBack invoked when the user navigates back, and automatically once the show is removed.
 * @param onEpisodePlaying invoked once a tapped episode has been handed to the player, so the caller
 *   can open the full player.
 * @param modifier layout modifier.
 * @param showBackButton false when the screen is rendered as the detail pane of a two-pane layout,
 *   where the list is still on screen and a back arrow would be misleading.
 * @param viewModel injected by Hilt.
 */
@Composable
fun PodcastDetailRoute(
    onBack: () -> Unit,
    onEpisodePlaying: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The show disappearing means it was removed; leave the screen rather than render an empty one.
    LaunchedEffect(uiState.isLoading, uiState.podcast) {
        if (!uiState.isLoading && uiState.podcast == null) onBack()
    }

    // Lifecycle-tied rather than a one-shot in the view model's `init`, so that coming back from the
    // full player — or from the app having been in the background for an hour — checks the feed
    // again. The staleness window in the view model is what keeps that cheap.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    PodcastDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onEpisodeClick = { episodeId -> viewModel.playEpisode(episodeId, onEpisodePlaying) },
        onEpisodeDownloadToggle = viewModel::toggleDownload,
        onEpisodeMove = viewModel::moveEpisode,
        onRefresh = viewModel::refresh,
        onAutoRefreshChange = viewModel::setAutoRefresh,
        onRebuild = viewModel::rebuild,
        onRemove = viewModel::removePodcast,
        onMessageShown = viewModel::onMessageShown,
        showBackButton = showBackButton,
        modifier = modifier,
    )
}

/**
 * Stateless podcast detail screen.
 *
 * @param uiState what to render.
 * @param onBack back handler.
 * @param onEpisodeClick episode tap handler; a tap starts playback.
 * @param onEpisodeDownloadToggle download/remove handler; one action, because the button's
 *   meaning follows the episode's download state.
 * @param onEpisodeMove applies a completed reorder on a hand-ordered show. Takes the ids currently
 *   on screen alongside the two positions, because a filter means those are a subset and the
 *   positions alone would name the wrong episodes.
 * @param onRefresh pull-to-refresh handler; also the empty state's action.
 * @param onAutoRefreshChange background-refresh toggle handler.
 * @param onRebuild deletes the episode list and imports the feed again from scratch; the screen
 *   confirms first, so this is only ever called once the user has said yes.
 * @param onRemove remove-show handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 * @param showBackButton whether to render the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    uiState: PodcastDetailUiState,
    onBack: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onEpisodeDownloadToggle: (String) -> Unit,
    onEpisodeMove: (List<String>, Int, Int) -> Unit,
    onRefresh: () -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRebuild: () -> Unit,
    onRemove: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current
    val now = remember { Instant.now() }
    // Saveable so neither choice — nor a confirmation mid-decision — is lost when the Fold 7 is
    // opened.
    var filter by rememberSaveable { mutableStateOf(EpisodeFilter.ALL) }
    var confirmingRebuild by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val moveUp = stringResource(R.string.podcast_move_up)
    val moveDown = stringResource(R.string.podcast_move_down)

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    if (confirmingRebuild) {
        RebuildDialog(
            episodeCount = uiState.episodes.size,
            atStakeCount = uiState.episodes.count { it.isAtStakeInARebuild },
            onConfirm = {
                confirmingRebuild = false
                onRebuild()
            },
            onDismiss = { confirmingRebuild = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // Small and quiet: the show's name is set large in the header a few dp below, and
            // repeating it at full strength here would be the same words twice.
            BPodcatTopAppBar(
                title = uiState.podcast?.title.orEmpty(),
                onBack = onBack.takeIf { showBackButton },
                backDescription = stringResource(R.string.podcast_back),
                actions = {
                    if (uiState.podcast != null) {
                        OverflowMenu(
                            autoRefresh = uiState.podcast.autoRefresh,
                            onAutoRefreshChange = onAutoRefreshChange,
                            // A confirmation that protects nothing is only a tax, so a show with
                            // no episodes stored rebuilds on the tap. Everywhere else it asks.
                            onRebuild = {
                                if (uiState.episodes.isEmpty()) onRebuild() else confirmingRebuild = true
                            },
                            onRemove = onRemove,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val podcast = uiState.podcast
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The automatic refresh's entire footprint: a line under the title, nothing that moves
            // the list the user is already reading. A rebuild borrows the same line rather than
            // blocking the screen — it leaves the old list readable until the moment it is
            // replaced — but says something different, because the two are not the same promise.
            when {
                uiState.isRebuilding -> WavyProgressLine(
                    contentDescription = stringResource(R.string.podcast_rebuilding),
                )

                uiState.isAutoRefreshing -> WavyProgressLine(
                    contentDescription = stringResource(R.string.podcast_refreshing),
                )
            }

            when {
                uiState.isLoading || podcast == null -> LoadingState(
                    contentDescription = stringResource(R.string.podcast_loading),
                )

                // The empty state keeps an explicit refresh action rather than the gesture: there is
                // no list here for a pull to act on, and an empty show is exactly when someone wants
                // to press something and find out why.
                uiState.episodes.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.PlaylistRemove,
                    title = stringResource(R.string.podcast_empty_title),
                    description = stringResource(
                        if (podcast.source == PodcastSource.YOUTUBE) {
                            R.string.podcast_empty_description_youtube
                        } else {
                            R.string.podcast_empty_description_rss
                        },
                    ),
                    actionLabel = stringResource(R.string.podcast_empty_action),
                    onAction = onRefresh,
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val shown = uiState.episodes.filterBy(filter)
                    // Only a YouTube playlist is arranged by hand. An RSS show is a chronology,
                    // and offering to rearrange one would promise an order the next refresh
                    // could not keep.
                    val isReorderable = podcast.source == PodcastSource.YOUTUBE
                    val drag = rememberReorderableState(
                        layout = rememberReorderableLayout(listState),
                        items = shown,
                        keyOf = { it.id },
                        onMove = { from, to -> onEpisodeMove(shown.map { it.id }, from, to) },
                    )

                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = HEADER_KEY) {
                            PodcastHeader(
                                podcast = podcast,
                                onPlayLatest = {
                                    uiState.episodes.firstOrNull()
                                        ?.let { onEpisodeClick(it.id) }
                                },
                            )
                        }
                        item(key = FILTERS_KEY) {
                            FilterChips(selected = filter, onSelect = { filter = it })
                        }

                        if (shown.isEmpty()) {
                            item(key = FILTER_EMPTY_KEY) {
                                FilterEmptyState(onShowAll = { filter = EpisodeFilter.ALL })
                            }
                        }

                        itemsIndexed(
                            items = drag.order,
                            key = { _, episode -> episode.id },
                        ) { index, episode ->
                            val isDragging = drag.draggingKey == episode.id

                            EpisodeRow(
                                modifier = Modifier
                                    .semantics {
                                        if (isReorderable) {
                                            customActions =
                                                drag.moveActions(index, moveUp, moveDown)
                                        }
                                    }
                                    .graphicsLayer {
                                        translationY = if (isDragging) drag.offset.y else 0f
                                        shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
                                    },
                                title = episode.title,
                                metadata = episode.metadataLine(now, resources),
                                artworkUrl = episode.artworkUrl ?: podcast.artworkUrl,
                                isUnplayed = episode.isNew,
                                isPlayed = episode.isPlayed,
                                playedFraction = episode.playedFraction,
                                onClick = { onEpisodeClick(episode.id) },
                                trailing = {
                                    DownloadButton(
                                        state = episode.downloadState,
                                        progressPercent = episode.downloadPercent,
                                        onClick = { onEpisodeDownloadToggle(episode.id) },
                                    )
                                    if (isReorderable) {
                                        ReorderHandle(
                                            modifier = Modifier
                                                .reorderableHandle(drag, episode.id),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The show, at the top of its own page.
 *
 * The cover is drawn twice — once blurred, filling the width, and once sharp and raised over it.
 * That is the whole trick: a podcast's artwork is the most colourful thing the app has, and it is
 * what makes a show's page feel like that show's page rather than another list.
 *
 * @param podcast the show.
 * @param onPlayLatest starts the newest episode; the one thing most visits to this screen want.
 * @param modifier layout modifier.
 */
@Composable
private fun PodcastHeader(
    podcast: Podcast,
    onPlayLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    // Feed descriptions are HTML fragments, often double-escaped.
    val description = remember(podcast.description) { podcast.description.toPlainText() }

    Column(modifier = modifier.fillMaxWidth()) {
        // The wash is behind the cover only. The show's name, its description and its buttons sit
        // on the page below it, on the ordinary surface: cover art is arbitrary third-party
        // imagery, and body text over an arbitrary photograph is a contrast bet the app loses on
        // any bright artwork — which is most of them.
        ArtworkBackdrop(
            url = podcast.artworkUrl,
            scrim = Brush.verticalGradient(
                listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BPodcatTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                // A raised card rather than a bare square: the artwork has to separate itself from
                // the blurred copy of itself behind it, and a shadow is what does that.
                Surface(
                    shape = BPodcatTheme.shapes.artworkLarge,
                    shadowElevation = BPodcatTheme.elevation.level3,
                ) {
                    PodcastArtwork(
                        url = podcast.artworkUrl,
                        size = ArtworkSize.Header,
                        shape = BPodcatTheme.shapes.artworkLarge,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BPodcatTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
        ) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
            ) {
                SourceBadge(source = podcast.source)
                Text(
                    text = podcast.author,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Button(onClick = onPlayLatest) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                Text(
                    text = stringResource(R.string.podcast_play_latest),
                    modifier = Modifier.padding(start = BPodcatTheme.spacing.sm),
                )
            }

            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Clipped rather than truncated for good: a hard four-line cut with no way past
                    // it is how a show's own summary becomes unreadable in the app that shows it.
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.animateContentSize(),
                )
                TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                    Text(
                        text = stringResource(
                            if (descriptionExpanded) {
                                R.string.podcast_description_collapse
                            } else {
                                R.string.podcast_description_expand
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The four ways of looking at a show's episodes.
 *
 * @param selected the current filter.
 * @param onSelect invoked with the chosen filter.
 * @param modifier layout modifier.
 */
@Composable
private fun FilterChips(
    selected: EpisodeFilter,
    onSelect: (EpisodeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = BPodcatTheme.spacing.screenHorizontal,
                vertical = BPodcatTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
    ) {
        EpisodeFilter.entries.forEach { option ->
            val isSelected = option == selected
            // Built here rather than inside `semantics`, which is not a composable scope.
            // The same two words the settings chips announce, from the design system, rather than
            // a second copy of "Selected" that a translator would have to find twice.
            val state = stringResource(
                if (isSelected) {
                    DesignSystemR.string.designsystem_chip_selected
                } else {
                    DesignSystemR.string.designsystem_chip_not_selected
                },
            )
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(text = stringResource(option.labelResId)) },
                modifier = Modifier.semantics { stateDescription = state },
            )
        }
    }
}

/**
 * What a filter with no matches shows.
 *
 * Offers the way out rather than only stating the fact: the most likely next thing anyone wants is
 * the full list back.
 *
 * @param onShowAll clears the filter.
 * @param modifier layout modifier.
 */
@Composable
private fun FilterEmptyState(onShowAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BPodcatTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.podcast_filter_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.podcast_filter_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onShowAll) {
            Text(text = stringResource(R.string.podcast_filter_empty_action))
        }
    }
}

/**
 * The show's rarely-used actions.
 *
 * Removing a show sat in the top bar, one mis-tap from the back arrow, for something that deletes
 * every episode and every download it has. It belongs behind a menu. The background-refresh switch
 * joins it because until now the repository could turn it off and nothing in the app could.
 *
 * Rebuilding the list belongs here for the same reason removal does, and is ordered between the
 * two: it destroys less than removing the show but more than any refresh, and putting it directly
 * above "Remove this podcast" keeps the menu reading from harmless to irreversible.
 *
 * @param autoRefresh whether this show is refreshed in the background.
 * @param onAutoRefreshChange toggle handler.
 * @param onRebuild opens the rebuild confirmation, or rebuilds outright when there is nothing
 *   stored to lose.
 * @param onRemove remove-show handler.
 */
@Composable
private fun OverflowMenu(
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRebuild: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.podcast_more_actions),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        val autoRefreshLabel = stringResource(
            if (autoRefresh) R.string.podcast_auto_refresh_on else R.string.podcast_auto_refresh_off,
        )
        DropdownMenuItem(
            text = { Text(text = autoRefreshLabel) },
            leadingIcon = {
                Icon(
                    imageVector = if (autoRefresh) {
                        Icons.Rounded.Sync
                    } else {
                        Icons.Rounded.SyncDisabled
                    },
                    contentDescription = null,
                )
            },
            onClick = {
                expanded = false
                onAutoRefreshChange(!autoRefresh)
            },
            // The label states the current setting, so the item needs to say what pressing it does.
            modifier = Modifier.semantics { contentDescription = autoRefreshLabel },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.podcast_rebuild)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.RestartAlt, contentDescription = null)
            },
            onClick = {
                expanded = false
                onRebuild()
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.podcast_remove)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Delete, contentDescription = null)
            },
            onClick = {
                expanded = false
                onRemove()
            },
        )
    }
}

/**
 * The confirmation shown before the episode list is deleted and fetched again.
 *
 * Everything else on this screen is either reversible or replaces one episode's state; this throws
 * away the show's whole history in a way no second tap undoes, so it asks first — and unlike
 * "Remove this podcast", which at least announces itself by emptying the library, a rebuild leaves
 * a list that looks much like the one before it, so a mis-tap could go unnoticed for weeks.
 *
 * What is at stake is counted rather than described, because "you will lose your progress" is
 * frightening in the abstract and decidable in the concrete: nobody hesitates over a show they have
 * never started, and everybody wants to know before they lose twelve downloads.
 *
 * @param episodeCount how many episodes will be deleted.
 * @param atStakeCount how many of those carry something the rebuild destroys; the sentence is
 *   omitted entirely at zero rather than warning about nothing.
 * @param onConfirm proceed.
 * @param onDismiss cancel.
 */
@Composable
private fun RebuildDialog(
    episodeCount: Int,
    atStakeCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Rounded.RestartAlt, contentDescription = null) },
        title = {
            Text(
                text = pluralStringResource(
                    R.plurals.podcast_rebuild_dialog_title,
                    episodeCount,
                    episodeCount,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm)) {
                Text(text = stringResource(R.string.podcast_rebuild_dialog_text))
                if (atStakeCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.podcast_rebuild_dialog_at_stake,
                            atStakeCount,
                            atStakeCount,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.podcast_rebuild_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.podcast_cancel))
            }
        },
    )
}

/**
 * Whether a rebuild would destroy anything about this episode worth being warned about.
 *
 * Deliberately not "has it been touched": a played episode with no audio on the device costs the
 * user a mark they can set again in one tap, and counting it would inflate the warning past the
 * point anyone reads it. Progress mid-episode and audio on the device are the two that cost real
 * time to recover.
 */
private val Episode.isAtStakeInARebuild: Boolean
    get() = positionMs > 0 || downloadState != DownloadState.NOT_DOWNLOADED

/**
 * The metadata line under an episode title.
 *
 * Shows publication date, duration and — once playback has started — how much is left, which is the
 * number that actually matters when picking what to listen to next.
 *
 * @param now reference time for relative date formatting.
 * @param resources for the marks that are not formatter output.
 * @return the line to show.
 */
private fun Episode.metadataLine(now: Instant, resources: Resources): String = listOfNotNull(
    formatPublishedDate(publishedAt, now),
    formatRemaining(durationMs, positionMs)?.takeIf { positionMs > 0 } ?: formatDuration(durationMs),
).joinToString(resources.getString(R.string.podcast_metadata_separator))

/**
 * Turns a [PodcastDetailMessage] into snackbar text.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @return the text to show.
 */
private fun PodcastDetailMessage.toText(resources: Resources): String = when (this) {
    is PodcastDetailMessage.Refreshed -> if (newEpisodeCount == 0) {
        resources.getString(R.string.podcast_message_no_new_episodes)
    } else {
        resources.getQuantityString(
            R.plurals.podcast_message_new_episodes,
            newEpisodeCount,
            newEpisodeCount,
        )
    }

    is PodcastDetailMessage.RefreshFailed ->
        resources.getString(R.string.podcast_message_refresh_failed, reason)

    // No "no episodes" special case, unlike a refresh: a rebuild that lands on zero means the feed
    // now publishes nothing, which is news rather than the ordinary answer, and the plural says it.
    is PodcastDetailMessage.Rebuilt -> resources.getQuantityString(
        R.plurals.podcast_message_rebuilt,
        episodeCount,
        episodeCount,
    )

    is PodcastDetailMessage.RebuildFailed ->
        resources.getString(R.string.podcast_message_rebuild_failed, reason)

    PodcastDetailMessage.EpisodeUnavailable ->
        resources.getString(R.string.podcast_message_episode_unavailable)

    is PodcastDetailMessage.DownloadQueued -> resources.getString(
        if (waitingForWifi) {
            R.string.podcast_message_download_waiting_for_wifi
        } else {
            R.string.podcast_message_downloading
        },
        title,
    )

    is PodcastDetailMessage.DownloadRemoved ->
        resources.getString(R.string.podcast_message_download_removed, title)
}

/** How far a dragged episode is lifted above its neighbours, so they cannot clip it. */
private const val DRAG_ELEVATION = 8f

private const val HEADER_KEY = "header"
private const val FILTERS_KEY = "filters"
private const val FILTER_EMPTY_KEY = "filter-empty"

/** How much of a show's description is shown before it has been asked for in full. */
private const val COLLAPSED_LINES = 4

@Preview
@Composable
private fun PodcastDetailScreenPreview() {
    BPodcatTheme {
        PodcastDetailScreen(
            uiState = PodcastDetailUiState(
                isLoading = false,
                podcast = Podcast(
                    id = "1",
                    itunesId = 1209828744L,
                    title = "Podlodka Podcast",
                    author = "Егор Толстой",
                    feedUrl = "https://example.com/feed.rss",
                    artworkUrl = null,
                    description = "Еженедельное шоу о разработке и людях в IT.",
                    addedAt = Instant.EPOCH,
                    lastRefreshAt = null,
                    etag = null,
                    lastModified = null,
                    autoRefresh = true,
                ),
                episodes = listOf(
                    Episode(
                        id = "e1",
                        podcastId = "1",
                        guid = "g1",
                        title = "Podlodka #400 – Мультиплатформа",
                        description = "",
                        audioUrl = "https://example.com/1.mp3",
                        artworkUrl = null,
                        durationMs = 5_025_000L,
                        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
                        sizeBytes = null,
                        positionMs = 1_200_000L,
                        isNew = true,
                    ),
                ),
            ),
            onBack = {},
            onEpisodeClick = {},
            onEpisodeDownloadToggle = {},
            onEpisodeMove = { _, _, _ -> },
            onRefresh = {},
            onAutoRefreshChange = {},
            onRebuild = {},
            onRemove = {},
            onMessageShown = {},
        )
    }
}
