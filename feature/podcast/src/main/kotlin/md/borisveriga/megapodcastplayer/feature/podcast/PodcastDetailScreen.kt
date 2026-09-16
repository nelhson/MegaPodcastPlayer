package md.borisveriga.megapodcastplayer.feature.podcast

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.common.format.formatPublishedDate
import md.borisveriga.megapodcastplayer.core.common.format.formatRemaining
import md.borisveriga.megapodcastplayer.core.common.format.toPlainText
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportStage
import md.borisveriga.megapodcastplayer.core.designsystem.R as DesignSystemR
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkBackdrop
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.DownloadButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.component.SortToggleChip
import md.borisveriga.megapodcastplayer.core.designsystem.component.SourceBadge
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
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import md.borisveriga.megapodcastplayer.core.model.EpisodeSort
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.episodeShareText
import md.borisveriga.megapodcastplayer.core.model.filterBy
import md.borisveriga.megapodcastplayer.core.model.orderedBy
import md.borisveriga.megapodcastplayer.core.model.showShareText

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
        // A tap opens the episode; the row's own play button plays it. See the sheet's KDoc for
        // why an episode had to become readable before it could become one tap away.
        onEpisodeClick = viewModel::openEpisode,
        onEpisodePlay = { episodeId -> viewModel.togglePlay(episodeId, onEpisodePlaying) },
        onEpisodePlayFrom = { episodeId, positionMs ->
            viewModel.playFrom(episodeId, positionMs, onEpisodePlaying)
        },
        onEpisodeAddToQueue = viewModel::addToQueue,
        onEpisodeSheetDismiss = viewModel::closeEpisode,
        onEpisodeDownloadToggle = viewModel::toggleDownload,
        onEpisodePlayNext = viewModel::playNext,
        onEpisodeSetPlayed = viewModel::setPlayed,
        onUndoPlayedChange = viewModel::undoPlayedChange,
        onEpisodeMove = viewModel::moveEpisode,
        onFilterChange = viewModel::setFilter,
        onSortChange = viewModel::setSort,
        onShowSettingsChange = viewModel::setShowSettings,
        onRefresh = viewModel::refresh,
        onRebuild = viewModel::rebuild,
        onRemove = viewModel::removePodcast,
        onDownloadAndExport = viewModel::downloadAndExport,
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
 * @param onEpisodePlayNext queues an episode to play after the current one, without interrupting
 *   it — which is the half of "what shall I listen to" that a tap on the row cannot express.
 * @param onEpisodePlay plays an episode, or pauses the one already playing.
 * @param onEpisodePlayFrom plays an episode from a position — what a chapter tap does.
 * @param onEpisodeAddToQueue puts an episode at the end of the queue.
 * @param onEpisodeSheetDismiss closes the episode sheet.
 * @param onEpisodeSetPlayed marks an episode played, or puts it back to unplayed.
 * @param onUndoPlayedChange reverses the last mark, from the snackbar that offered it.
 * @param onEpisodeMove applies a completed reorder on a hand-ordered show. Takes the ids currently
 *   on screen alongside the two positions, because a filter means those are a subset and the
 *   positions alone would name the wrong episodes.
 * @param onFilterChange remembers which episodes this show lists.
 * @param onSortChange remembers which end of this show the list starts at.
 * @param onShowSettingsChange applies a change made in the show settings sheet.
 * @param onRefresh the empty state's action. A show with no episodes is the one place a plain
 *   re-fetch is still what is wanted: there is nothing stored for a rebuild to prune, and no
 *   list for a pull to act on either.
 * @param onRebuild re-reads the feed from scratch and drops the episodes it no longer lists — what
 *   the pull does now. Called without a confirmation: episodes still in the feed keep their
 *   progress and downloads, so nothing the user could miss is at stake.
 * @param onRemove remove-show handler.
 * @param onDownloadAndExport downloads the episodes on screen and copies them into a folder: given
 *   the location the user just picked, as the picker's tree URI, and the name they gave the folder.
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
    onEpisodePlay: (String) -> Unit,
    onEpisodePlayFrom: (String, Long) -> Unit,
    onEpisodeAddToQueue: (String) -> Unit,
    onEpisodeSheetDismiss: () -> Unit,
    onEpisodeDownloadToggle: (String) -> Unit,
    onEpisodePlayNext: (String) -> Unit,
    onEpisodeSetPlayed: (String, Boolean) -> Unit,
    onUndoPlayedChange: () -> Unit,
    onEpisodeMove: (List<String>, Int, Int) -> Unit,
    onFilterChange: (EpisodeFilter) -> Unit,
    onSortChange: (EpisodeSort) -> Unit,
    onShowSettingsChange: (ShowSettings) -> Unit,
    onRefresh: () -> Unit,
    onRebuild: () -> Unit,
    onRemove: () -> Unit,
    onDownloadAndExport: (treeUri: String, folderName: String) -> Unit,
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
    // The filter and the sort order used to live here, as screen state. They are the show's now:
    // see [ShowSettings].
    val filter = uiState.settings.episodeFilter
    // Saveable so opening the Fold 7 mid-decision does not close the sheet.
    var showSettingsOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val moveUp = stringResource(R.string.podcast_move_up)
    val moveDown = stringResource(R.string.podcast_move_down)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val shareTitle = stringResource(R.string.episode_share_title)
    val shareShowTitle = stringResource(R.string.podcast_share_show_title)
    val feedClipLabel = stringResource(R.string.podcast_feed_clip_label)

    val undoLabel = stringResource(R.string.podcast_undo)

    // The export asks two things in turn: what the folder is called, then where it goes. The name
    // is held here, saveably, across the system picker, which can recreate the activity.
    var exportNameDialogOpen by rememberSaveable { mutableStateOf(false) }
    var exportFolderName by rememberSaveable { mutableStateOf("") }
    val exportFolderPicker = rememberExportFolderPicker { treeUri ->
        onDownloadAndExport(treeUri, exportFolderName)
    }

    uiState.podcast?.takeIf { exportNameDialogOpen }?.let { podcast ->
        ExportFolderNameDialog(
            initialName = podcast.title,
            onConfirm = { name ->
                exportNameDialogOpen = false
                exportFolderName = name
                exportFolderPicker.launch(null)
            },
            onDismiss = { exportNameDialogOpen = false },
        )
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        // Only the mark is offered back. The rest report something that already happened and
        // cannot be taken back — a refresh, a download — and a dead Undo beside them would teach
        // the user to stop reading the action.
        val result = snackbarHostState.showSnackbar(
            message = message.toText(resources),
            actionLabel = undoLabel.takeIf { message is PodcastDetailMessage.PlayedChanged },
        )
        if (result == SnackbarResult.ActionPerformed) onUndoPlayedChange() else onMessageShown()
    }

    uiState.openEpisode?.let { episode ->
        EpisodeSheet(
            episode = episode,
            showTitle = uiState.podcast?.title.orEmpty(),
            artworkUrl = episode.artworkUrl ?: uiState.podcast?.artworkUrl,
            chapters = uiState.chapters,
            isChaptersLoading = uiState.isChaptersLoading,
            now = now,
            onPlay = {
                onEpisodeSheetDismiss()
                onEpisodePlay(episode.id)
            },
            onPlayChapter = { chapter ->
                onEpisodeSheetDismiss()
                onEpisodePlayFrom(episode.id, chapter.startMs)
            },
            onPlayNext = {
                onEpisodeSheetDismiss()
                onEpisodePlayNext(episode.id)
            },
            onAddToQueue = {
                onEpisodeSheetDismiss()
                onEpisodeAddToQueue(episode.id)
            },
            // The two that leave the sheet open, because both are things you do *while* reading an
            // episode and both change what the sheet itself shows.
            onToggleDownload = { onEpisodeDownloadToggle(episode.id) },
            onSetPlayed = { played -> onEpisodeSetPlayed(episode.id, played) },
            onShare = {
                context.shareEpisode(
                    episode = episode,
                    showTitle = uiState.podcast?.title.orEmpty(),
                    chooserTitle = shareTitle,
                )
            },
            onDismiss = onEpisodeSheetDismiss,
        )
    }

    // Only with a show to be about: the sheet names it in its header, and the settings belong to
    // it. A removal that lands while the sheet is open therefore closes it rather than leaving a
    // sheet of controls attached to nothing.
    uiState.podcast?.takeIf { showSettingsOpen }?.let { podcast ->
        ShowSettingsSheet(
            showTitle = podcast.title,
            settings = uiState.settings,
            appSpeed = uiState.appSpeed,
            appAutoDownload = uiState.appAutoDownload,
            onSettingsChange = onShowSettingsChange,
            onDismiss = { showSettingsOpen = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // Small and quiet: the show's name is set large in the header a few dp below, and
            // repeating it at full strength here would be the same words twice.
            MegaPodcastPlayerTopAppBar(
                title = uiState.podcast?.title.orEmpty(),
                onBack = onBack.takeIf { showBackButton },
                backDescription = stringResource(R.string.podcast_back),
                actions = {
                    if (uiState.podcast != null) {
                        OverflowMenu(
                            onShare = {
                                context.shareShow(
                                    podcast = uiState.podcast,
                                    chooserTitle = shareShowTitle,
                                )
                            },
                            onCopyFeed = {
                                // No confirmation of our own: from Android 13 the system draws its
                                // own when anything is copied, and a snackbar under it would be the
                                // same news twice.
                                clipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        feedClipLabel,
                                        uiState.podcast.feedUrl,
                                    ),
                                )
                            },
                            onOpenSettings = { showSettingsOpen = true },
                            canExport = uiState.hasExportableEpisodes,
                            exportProgress = uiState.exportProgress,
                            onDownloadAndExport = { exportNameDialogOpen = true },
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
            // It keeps the line even though the pull that starts it spins an indicator of its own:
            // that one leaves with the finger, and a rebuild still running after a rotation has no
            // gesture behind it at all.
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

                // The pull rebuilds. It used to re-fetch, which on a feed that has gone wrong —
                // the reason anyone pulls a show's page twice — changes nothing at all, and the one
                // thing that would fix it was three taps into a menu. The gesture now means "fetch
                // this show again from scratch". It asks nothing first: every episode the feed
                // still lists keeps its progress and its audio, so the only things it removes are
                // episodes the publisher already took away.
                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRebuilding,
                    onRefresh = onRebuild,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Only a YouTube playlist is arranged by hand. An RSS show is a chronology,
                    // and offering to rearrange one would promise an order the next refresh
                    // could not keep.
                    val isReorderable = podcast.source == PodcastSource.YOUTUBE
                    // A show is either arranged by hand or sorted, never both: reversing a
                    // playlist the user has dragged into shape would leave the drag computing
                    // positions against an order nobody can see.
                    val shown = uiState.episodes
                        .filterBy(filter)
                        .let { if (isReorderable) it else it.orderedBy(uiState.settings.episodeSort) }
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
                                // Derived from the show's whole list, not from the filtered view
                                // and not from `first()`; see [headerAction].
                                action = uiState.episodes.headerAction(),
                                // The whole list too, for the same reason: the line says what the
                                // show holds, not what the chips are showing of it.
                                counts = uiState.episodes.countsLine(podcast.source, resources),
                                onPlay = onEpisodePlay,
                            )
                        }
                        item(key = FILTERS_KEY) {
                            ListControls(
                                filter = filter,
                                onFilterChange = onFilterChange,
                                sort = uiState.settings.episodeSort.takeUnless { isReorderable },
                                onSortChange = onSortChange,
                            )
                        }

                        if (shown.isEmpty()) {
                            item(key = FILTER_EMPTY_KEY) {
                                FilterEmptyState(onShowAll = { onFilterChange(EpisodeFilter.ALL) })
                            }
                        }

                        itemsIndexed(
                            items = drag.order,
                            key = { _, episode -> episode.id },
                        ) { index, episode ->
                            EpisodeListRow(
                                episode = episode,
                                metadata = episode.metadataLine(now, resources),
                                artworkUrl = episode.artworkUrl ?: podcast.artworkUrl,
                                index = index,
                                drag = drag,
                                isReorderable = isReorderable,
                                moveUp = moveUp,
                                moveDown = moveDown,
                                nowPlaying = uiState.nowPlaying,
                                onClick = { onEpisodeClick(episode.id) },
                                onPlay = { onEpisodePlay(episode.id) },
                                onDownloadToggle = { onEpisodeDownloadToggle(episode.id) },
                                onPlayNext = { onEpisodePlayNext(episode.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One episode in the show's list.
 *
 * The offline copy is a swipe rather than a button on the right. A list of fifty episodes carried
 * fifty controls, each one a target to aim at beside a row whose whole width already does something
 * — and downloading is the action this screen exists for, so it gets the gesture the library and
 * the queue give to theirs: a long pull, committed on release.
 *
 * One action rather than three, because there is only ever one thing to do with an episode's copy,
 * and which one follows the state it is in. That is the same rule the button had; only its shape
 * has changed.
 *
 * Queueing sits on the short pull instead, as a button to tap. A tap on the row already means "play
 * this now", and the other half of choosing what to listen to — "play this *after* what I am
 * listening to" — had nowhere to live on this screen at all. It is the revealed tier rather than
 * the committed one because it is the rarer of the two: someone opens a show's page to hear
 * something, and queues from it only when something is already playing.
 *
 * @param episode the episode.
 * @param metadata the line under the title, already assembled.
 * @param artworkUrl the episode's own artwork, or the show's.
 * @param index the row's position, for the reorder actions.
 * @param drag the reorder state the list shares.
 * @param isReorderable whether this show's order is the user's to keep; only a YouTube playlist is.
 * @param moveUp accessibility label for moving the row up.
 * @param moveDown accessibility label for moving the row down.
 * @param nowPlaying which episode the player has loaded, so the row's own control can show it.
 * @param onClick opens the episode's sheet.
 * @param onPlay plays it, or pauses it when it is the one already playing.
 * @param onDownloadToggle downloads it, cancels the transfer, or deletes the copy — whichever the
 *   current state means.
 * @param onPlayNext queues it to play after whatever is playing now.
 */
@Composable
private fun EpisodeListRow(
    episode: Episode,
    metadata: String,
    artworkUrl: String?,
    index: Int,
    drag: ReorderableState<Episode>,
    isReorderable: Boolean,
    moveUp: String,
    moveDown: String,
    nowPlaying: NowPlaying,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownloadToggle: () -> Unit,
    onPlayNext: () -> Unit,
) {
    val isDragging = drag.draggingKey == episode.id
    val isNowPlaying = nowPlaying.episodeId == episode.id
    val download = episode.downloadSwipeAction(onDownloadToggle)
    val playNext = SwipeAction(
        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
        label = stringResource(R.string.podcast_action_play_next),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = onPlayNext,
    )

    SwipeActionsRow(
        // Revealed rather than committed: it does not fire on release. Queueing changes what
        // happens after the thing the user is listening to — worth a deliberate tap, and the pull
        // that would fire it is already spoken for by the download. Marking played is not here at
        // all: it lives in the episode sheet, where the row that is about to leave a filtered list
        // is not the one under the finger.
        actions = listOf(playNext),
        fullSwipeAction = download,
        modifier = Modifier.graphicsLayer {
            // Only the dragged row moves; the rest are re-laid-out by the list as the order
            // changes underneath it.
            translationY = if (isDragging) drag.offset.y else 0f
            shadowElevation = if (isDragging) DRAG_ELEVATION else 0f
        },
    ) {
        EpisodeRow(
            // On the row rather than on the box around it: the row merges its children into one
            // node, and that merged node is what a screen reader lands on. Neither the drag nor
            // the swipe is visible to one, so both are published here as actions.
            modifier = Modifier
                .semantics {
                    customActions = (
                        if (isReorderable) drag.moveActions(index, moveUp, moveDown) else emptyList()
                        ) + listOf(playNext, download).asAccessibilityActions()
                }
                // Inside the swipe box rather than around it, so the row's two drags are settled
                // by the pointer that started them: this one consumes movement only once the
                // press has been held, and a horizontal swipe claims the gesture long before that.
                //
                // Only where the order is the user's to keep: a long press on a row of an RSS
                // show would pick it up and then refuse to put it anywhere.
                .then(
                    if (isReorderable) {
                        Modifier.reorderableLongPressDrag(drag, episode.id)
                    } else {
                        Modifier
                    },
                ),
            title = episode.title,
            metadata = metadata,
            artworkUrl = artworkUrl,
            isUnplayed = episode.isNew,
            isPlayed = episode.isPlayed,
            // The show page used to be the one list that hid this. The mark is how a list is read
            // down for what will play on a train with no signal, and the only ways to find out here
            // were the Downloaded filter chip or swiping a row to see what the backdrop said.
            isDownloaded = episode.downloadState == DownloadState.COMPLETED,
            playedFraction = episode.playedFraction,
            isNowPlaying = isNowPlaying,
            isPlaying = nowPlaying.isPlaying,
            // The trailing control below says which row is loaded and whether it is running; the
            // inline bars would say the same fact a second time, three pills to the left of it.
            showNowPlayingBars = false,
            onClick = onClick,
            trailing = {
                // The ring, and only while there is a transfer to draw one for. A permanent
                // download button on every row was rejected once and stays rejected, but a transfer
                // already under way is not an offer — it is progress, and progress with no visible
                // ring is what made a running download look like nothing happening.
                episode.runningDownload()?.let { running ->
                    DownloadButton(
                        state = running,
                        progressPercent = episode.downloadPercent,
                        onClick = onDownloadToggle,
                    )
                }
                // The one control a list of episodes exists for. It is what keeps playing at a
                // single tap now that the row's own tap opens the episode instead, and it doubles
                // as the now-playing mark: it is the only row in the list showing a pause.
                PlayPauseButton(
                    playing = isNowPlaying && nowPlaying.isPlaying,
                    onToggle = { onPlay() },
                    size = PlayPauseSize.Small,
                    buffering = isNowPlaying && nowPlaying.isBuffering,
                    containerColor = if (isNowPlaying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (isNowPlaying) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            },
        )
    }
}

/**
 * The download state of an episode whose transfer is under way, or null.
 *
 * Three of the five states are settled facts about the episode — not downloaded, downloaded, failed
 * — and the row says all three in other ways. The two that are *happening* are the ones worth a
 * control that moves.
 *
 * @return [DownloadState.QUEUED] or [DownloadState.DOWNLOADING], or null for anything at rest.
 */
private fun Episode.runningDownload(): DownloadState? = downloadState.takeIf {
    it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING
}

/**
 * What the swipe on this episode does, which depends on what its offline copy is currently doing.
 *
 * The colours carry the difference the labels make in words: fetching something is the ordinary
 * action, calling off a transfer takes nothing away, and deleting audio is the one that should look
 * like it.
 *
 * @param onToggle the handler; the same one for every state, as the view model's toggle already
 *   reads the state to decide.
 * @return the action to hand to [SwipeActionsRow].
 */
@Composable
private fun Episode.downloadSwipeAction(onToggle: () -> Unit): SwipeAction = when (downloadState) {
    // A failed download is retried rather than cleared, so it reads as "download" too.
    DownloadState.NOT_DOWNLOADED, DownloadState.FAILED -> SwipeAction(
        icon = Icons.Rounded.FileDownload,
        label = stringResource(R.string.podcast_action_download),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onToggle,
    )

    DownloadState.QUEUED, DownloadState.DOWNLOADING -> SwipeAction(
        icon = Icons.Rounded.Close,
        label = stringResource(R.string.podcast_action_cancel_download),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onToggle,
    )

    DownloadState.COMPLETED -> SwipeAction(
        icon = Icons.Rounded.Delete,
        label = stringResource(R.string.podcast_action_delete_download),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onToggle,
    )
}

/**
 * The show, at the top of its own page.
 *
 * The cover is drawn twice — once blurred, filling the width, and once sharp and raised over it.
 * That is the whole trick: a podcast's artwork is the most colourful thing the app has, and it is
 * what makes a show's page feel like that show's page rather than another list.
 *
 * @param podcast the show.
 * @param action what the one button does, or null for a show with no episodes yet.
 * @param counts how many episodes there are and how many are on the device, already assembled;
 *   null for a show with none. The library's row has carried this line since it was written and
 *   the page *about* the show did not, which is the wrong way round (SHOW-7).
 * @param onPlay plays the episode the action names.
 * @param modifier layout modifier.
 */
@Composable
private fun PodcastHeader(
    podcast: Podcast,
    action: HeaderAction?,
    counts: String?,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    // Whether the four collapsed lines were not enough. Measured rather than guessed: a character
    // count cannot know the width, the font scale or the language, and the button used to be drawn
    // under every description including the one-liners it had nothing to expand (SHOW-7).
    // Keyed to the text so a different show starts the question again.
    var isDescriptionClipped by remember(podcast.description) { mutableStateOf(false) }
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
                    .padding(vertical = MegaPodcastPlayerTheme.spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                // A raised card rather than a bare square: the artwork has to separate itself from
                // the blurred copy of itself behind it, and a shadow is what does that.
                Surface(
                    shape = MegaPodcastPlayerTheme.shapes.artworkLarge,
                    shadowElevation = MegaPodcastPlayerTheme.elevation.level3,
                ) {
                    PodcastArtwork(
                        url = podcast.artworkUrl,
                        size = ArtworkSize.Header,
                        shape = MegaPodcastPlayerTheme.shapes.artworkLarge,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
        ) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
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

            if (counts != null) {
                Text(
                    text = counts,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            action?.let { headerAction ->
                Button(onClick = { onPlay(headerAction.episodeId) }) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(
                        text = headerAction.label(),
                        modifier = Modifier.padding(start = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
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
                    // Recorded only while collapsed. Expanded, nothing overflows by definition, and
                    // reading the answer then would take the button away at the moment it is the
                    // only way back.
                    onTextLayout = { layout ->
                        if (!descriptionExpanded) isDescriptionClipped = layout.hasVisualOverflow
                    },
                    modifier = Modifier.animateContentSize(),
                )
                if (isDescriptionClipped) {
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
}

/**
 * The one row that says what this list is showing: which episodes, and from which end.
 *
 * The two controls sit together because they answer the same question and are used in the same
 * breath — "the downloaded ones, oldest first" is one thought — and because a sort control parked
 * anywhere else on this screen would be a second, quieter place to look.
 *
 * The order button is drawn only for a show that is not arranged by hand; see [sort].
 *
 * @param filter the current filter.
 * @param onFilterChange invoked with the chosen filter.
 * @param sort the current order, or null for a hand-arranged show, which has no order to choose.
 * @param onSortChange invoked with the chosen order.
 * @param modifier layout modifier.
 */
@Composable
private fun ListControls(
    filter: EpisodeFilter,
    onFilterChange: (EpisodeFilter) -> Unit,
    sort: EpisodeSort?,
    onSortChange: (EpisodeSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                vertical = MegaPodcastPlayerTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodeFilter.entries.forEach { option ->
            val isSelected = option == filter
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
                onClick = { onFilterChange(option) },
                label = { Text(text = stringResource(option.labelResId)) },
                modifier = Modifier.semantics { stateDescription = state },
            )
        }

        sort?.let { current -> SortButton(sort = current, onSortChange = onSortChange) }
    }
}

/**
 * Flips the show between newest-first and oldest-first.
 *
 * The words and the two enum values are this screen's; everything about how a sort control looks
 * and announces itself is the design system's [SortToggleChip], which the library's own sort
 * control is the other half of.
 *
 * @param sort the order currently applied.
 * @param onSortChange invoked with the other one.
 * @param modifier layout modifier.
 */
@Composable
private fun SortButton(
    sort: EpisodeSort,
    onSortChange: (EpisodeSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNewestFirst = sort == EpisodeSort.NEWEST_FIRST
    SortToggleChip(
        label = stringResource(
            if (isNewestFirst) R.string.podcast_sort_newest else R.string.podcast_sort_oldest,
        ),
        icon = if (isNewestFirst) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
        switchToDescription = stringResource(
            if (isNewestFirst) {
                R.string.podcast_sort_switch_to_oldest
            } else {
                R.string.podcast_sort_switch_to_newest
            },
        ),
        onClick = {
            onSortChange(
                if (isNewestFirst) EpisodeSort.OLDEST_FIRST else EpisodeSort.NEWEST_FIRST,
            )
        },
        modifier = modifier,
    )
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
            .padding(MegaPodcastPlayerTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
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
 * every episode and every download it has. It belongs behind a menu.
 *
 * Rebuilding the list used to sit above it. It is a gesture now — the pull on the list itself —
 * because it is what a reader wants when a show's page looks wrong, and wanting it from three taps
 * inside a menu is what made a pull that only re-fetched feel broken.
 *
 * A per-show background-refresh toggle used to head the menu. It was the odd one out — a setting
 * among actions, whose label had to state the current value and whose content description had to
 * state the opposite — and it made the two destructive entries below it that much easier to reach
 * by accident. Background refreshing now follows whatever the show was added with.
 *
 * The two that give the show away rather than change it — sharing it and copying its feed — head
 * the menu (SHOW-7). A show's page had no way to hand the show to anybody: the episode sheet could
 * share an episode and the moments export could name a feed, and a reader who wanted *this show* in
 * another app had to go and find it again. They are first because they are the two entries here
 * that leave everything exactly as it was.
 *
 * @param onShare hands the show to the system share sheet.
 * @param onCopyFeed puts the feed URL on the clipboard. Copying rather than opening: a feed URL
 *   opened in a browser is a page of XML, and what it is actually for is being pasted into another
 *   podcast app.
 * @param onOpenSettings opens the per-show settings sheet.
 * @param canExport whether the list on screen has any episodes. The export entry is disabled rather
 *   than hidden without one, so it can still be found under a filter that lists nothing.
 * @param exportProgress how far a running export has got, or null. While one runs the entry says so
 *   and cannot start another.
 * @param onDownloadAndExport asks for the folder's name, which then leads to the folder picker.
 * @param onRemove remove-show handler.
 */
@Composable
private fun OverflowMenu(
    onShare: () -> Unit,
    onCopyFeed: () -> Unit,
    onOpenSettings: () -> Unit,
    canExport: Boolean,
    exportProgress: ExportProgress?,
    onDownloadAndExport: () -> Unit,
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
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.podcast_share_show)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Share, contentDescription = null)
            },
            onClick = {
                expanded = false
                onShare()
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.podcast_copy_feed)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Link, contentDescription = null)
            },
            onClick = {
                expanded = false
                onCopyFeed()
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.podcast_show_settings)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.Tune, contentDescription = null)
            },
            onClick = {
                expanded = false
                onOpenSettings()
            },
        )
        DropdownMenuItem(
            text = { Text(text = exportMenuLabel(exportProgress)) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.DriveFileMove, contentDescription = null)
            },
            enabled = canExport && exportProgress == null,
            onClick = {
                expanded = false
                onDownloadAndExport()
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
 * What the export entry says: the action when idle, and how far a run has got while one goes.
 *
 * The stage is named because a run can wait on downloads for a long time, and a number that does not
 * move under "Exporting" would look stuck.
 *
 * @param progress the running export's progress, or null when none is running.
 * @return the entry's label.
 */
@Composable
private fun exportMenuLabel(progress: ExportProgress?): String = when {
    progress == null -> stringResource(R.string.podcast_download_and_export)

    progress.total == 0 -> stringResource(R.string.podcast_exporting)

    progress.stage == ExportStage.DOWNLOADING ->
        stringResource(R.string.podcast_export_downloading_progress, progress.done, progress.total)

    else -> stringResource(R.string.podcast_exporting_progress, progress.done, progress.total)
}

/**
 * Asks what the exported folder is called, before the picker asks where it goes.
 *
 * Starts from the show's title, which is what most exports want; the user edits it when they are
 * exporting a filtered part of a show, or into a folder they already use. The same name fills the
 * same folder on a second run.
 *
 * @param initialName the name the field starts with.
 * @param onConfirm receives the name, trimmed; not called while it is blank.
 * @param onDismiss closes the dialog without exporting.
 */
@Composable
private fun ExportFolderNameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.podcast_export_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md)) {
                Text(text = stringResource(R.string.podcast_export_name_body))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.podcast_export_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.podcast_export_name_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.podcast_export_name_cancel))
            }
        },
    )
}

/**
 * The system folder picker an export starts from.
 *
 * A folder rather than a file, because an export is one file per episode. The exporter makes the
 * grant it returns persistable, since the copying runs long after this result has been delivered.
 *
 * @param onPicked receives the picked folder's tree URI; not called when the picker is cancelled.
 * @return the launcher; launch it with no starting folder.
 */
@Composable
private fun rememberExportFolderPicker(onPicked: (String) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) onPicked(treeUri.toString())
    }

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
    formatPublishedDate(resources, publishedAt, now),
    formatRemaining(resources, durationMs, positionMs)?.takeIf { positionMs > 0 }
        ?: formatDuration(resources, durationMs),
).joinToString(resources.getString(R.string.podcast_metadata_separator))

/**
 * The header button's caption.
 *
 * Composable rather than a property on [HeaderAction] because it is copy, and copy lives in
 * resources; the type itself stays a pure decision that a test can make without a `Context`.
 *
 * *Continue* carries the remaining time when the duration is known — the number is what decides
 * whether to start now or wait — and drops it silently when it is not, rather than saying
 * "Continue · unknown".
 *
 * @return the words on the button.
 */
@Composable
private fun HeaderAction.label(): String = when (this) {
    is HeaderAction.Continue -> {
        val remaining = formatRemaining(
            LocalResources.current,
            durationMs = remainingMs,
            positionMs = 0L,
        )
        if (remaining == null) {
            stringResource(R.string.podcast_continue)
        } else {
            stringResource(R.string.podcast_continue_with_remaining, remaining)
        }
    }

    // "Newest", not "latest": *latest* reads as "the most recent one I played" as readily as "the
    // most recent one published", and on this screen both are plausible.
    is HeaderAction.Play -> stringResource(
        if (isReplay) R.string.podcast_play_newest_again else R.string.podcast_play_newest,
    )
}

/**
 * The counts line under a show's title: how many episodes there are, and how many are here.
 *
 * The same two facts the library's row carries, in the same order and with the same separator, so
 * that the page and the row do not describe one show in two vocabularies. Assembled from the
 * episodes rather than from a stored count, because this screen already holds the list.
 *
 * @param source decides the noun; a playlist has videos, and that is what the user called them
 *   when they added it.
 * @param resources for the plurals.
 * @return the line, or null for a show with no episodes — where the empty state is already saying
 *   it more usefully than a "0 episodes" would.
 */
private fun List<Episode>.countsLine(source: PodcastSource, resources: Resources): String? {
    if (isEmpty()) return null
    val episodes = resources.getQuantityString(
        if (source == PodcastSource.YOUTUBE) {
            R.plurals.podcast_video_count
        } else {
            R.plurals.podcast_episode_count
        },
        size,
        size,
    )
    val downloaded = count { it.downloadState == DownloadState.COMPLETED }
    if (downloaded == 0) return episodes
    return resources.getString(
        R.string.podcast_counts_combined,
        episodes,
        resources.getQuantityString(R.plurals.podcast_downloaded_count, downloaded, downloaded),
    )
}

/**
 * Hands a show to the system share sheet.
 *
 * The feed URL and not a web page, because the feed URL is the one string that means this show to
 * every podcast app there is — it is what this app's own add field takes, and what the moments
 * export writes under each show heading for the same reason.
 *
 * @param podcast the show being shared.
 * @param chooserTitle what the chooser is headed.
 */
internal fun Context.shareShow(podcast: Podcast, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SHARE_MIME_TYPE
        putExtra(
            Intent.EXTRA_TEXT,
            showShareText(showTitle = podcast.title, feedUrl = podcast.feedUrl),
        )
    }
    startActivity(Intent.createChooser(send, chooserTitle))
}

/**
 * Hands an episode to the system share sheet.
 *
 * The same path a moment takes, and deliberately so: this app does not have a share screen, it has
 * the platform's, and the only decision it makes is what text goes into it.
 *
 * @param episode the episode being shared.
 * @param showTitle the show it belongs to, which the message leads with.
 * @param chooserTitle what the chooser is headed.
 */
internal fun Context.shareEpisode(episode: Episode, showTitle: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SHARE_MIME_TYPE
        putExtra(
            Intent.EXTRA_TEXT,
            episodeShareText(
                showTitle = showTitle,
                episodeTitle = episode.title,
                audioUrl = episode.audioUrl,
            ),
        )
    }
    startActivity(Intent.createChooser(send, chooserTitle))
}

/** Plain text: a share is a sentence and a link, not a file. */
private const val SHARE_MIME_TYPE = "text/plain"

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

    is PodcastDetailMessage.QueuedNext ->
        resources.getString(R.string.podcast_message_queued_next, title)

    is PodcastDetailMessage.Queued ->
        resources.getString(R.string.podcast_message_queued, title)

    is PodcastDetailMessage.PlayedChanged -> resources.getString(
        if (isPlayed) {
            R.string.podcast_message_marked_played
        } else {
            R.string.podcast_message_marked_unplayed
        },
        title,
    )

    is PodcastDetailMessage.ExportStarted -> resources.getString(
        if (waitingForWifi) {
            R.string.podcast_message_export_started_wifi
        } else {
            R.string.podcast_message_export_started
        },
    )

    is PodcastDetailMessage.ExportFinished -> {
        // Files already there from an earlier export count: they are in the folder, which is what
        // the user asked for.
        val inFolder = summary.exported + summary.alreadyThere
        if (summary.failed == 0) {
            resources.getQuantityString(R.plurals.podcast_message_exported, inFolder, inFolder)
        } else {
            resources.getQuantityString(
                R.plurals.podcast_message_exported_with_failures,
                summary.failed,
                inFolder,
                summary.failed,
            )
        }
    }

    PodcastDetailMessage.ExportFailed ->
        resources.getString(R.string.podcast_message_export_failed)
}

/** How far a dragged episode is lifted above its neighbours, so they cannot clip it. */
private const val DRAG_ELEVATION = 8f

private const val HEADER_KEY = "header"
private const val FILTERS_KEY = "filters"
private const val FILTER_EMPTY_KEY = "filter-empty"

/** How much of a show's description is shown before it has been asked for in full. */
private const val COLLAPSED_LINES = 4

/**
 * The state both previews below render.
 *
 * Shared rather than written twice, because the two differ in exactly one argument and a second
 * copy of a show, an episode and a position would be a second thing to keep true.
 */
private fun previewUiState() = PodcastDetailUiState(
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
)

@ThemePreviews
@FontScalePreviews
@Composable
internal fun PodcastDetailScreenPreview() {
    MegaPodcastPlayerTheme {
        PodcastDetailScreen(
            uiState = previewUiState(),
            onBack = {},
            onEpisodeClick = {},
            onEpisodeDownloadToggle = {},
            onEpisodePlay = {},
            onEpisodePlayFrom = { _, _ -> },
            onEpisodeAddToQueue = {},
            onEpisodeSheetDismiss = {},
            onEpisodePlayNext = {},
            onEpisodeSetPlayed = { _, _ -> },
            onUndoPlayedChange = {},
            onEpisodeMove = { _, _, _ -> },
            onFilterChange = {},
            onSortChange = {},
            onShowSettingsChange = {},
            onRefresh = {},
            onRebuild = {},
            onRemove = {},
            onDownloadAndExport = { _, _ -> },
            onMessageShown = {},
        )
    }
}

/**
 * The same show as the detail pane of the two-pane library, where the arrow is gone.
 *
 * The one visible difference NAV-3 makes to this screen, and worth a golden of its own: the arrow
 * would point back to a list that is already on screen beside it.
 */
@ThemePreviews
@Composable
internal fun PodcastDetailScreenInPanePreview() {
    MegaPodcastPlayerTheme {
        PodcastDetailScreen(
            uiState = previewUiState(),
            onBack = {},
            onEpisodeClick = {},
            onEpisodeDownloadToggle = {},
            onEpisodePlay = {},
            onEpisodePlayFrom = { _, _ -> },
            onEpisodeAddToQueue = {},
            onEpisodeSheetDismiss = {},
            onEpisodePlayNext = {},
            onEpisodeSetPlayed = { _, _ -> },
            onUndoPlayedChange = {},
            onEpisodeMove = { _, _, _ -> },
            onFilterChange = {},
            onSortChange = {},
            onShowSettingsChange = {},
            onRefresh = {},
            onRebuild = {},
            onRemove = {},
            onDownloadAndExport = { _, _ -> },
            onMessageShown = {},
            showBackButton = false,
        )
    }
}
