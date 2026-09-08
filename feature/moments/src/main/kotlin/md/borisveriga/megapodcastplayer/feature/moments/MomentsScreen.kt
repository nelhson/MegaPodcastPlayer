package md.borisveriga.megapodcastplayer.feature.moments

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerLargeTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.NoteDialog
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import md.borisveriga.megapodcastplayer.core.model.momentShareText

/**
 * The moments screen, wired to its view model.
 *
 * The document picker and the share sheet are launched from here rather than from [MomentsScreen]:
 * both need an activity result registry or a context, and neither exists under
 * `createComposeRule`, which is what the stateless screen is tested with.
 *
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun MomentsRoute(
    modifier: Modifier = Modifier,
    viewModel: MomentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val shareChooserTitle = stringResource(R.string.moments_share_chooser)

    MomentsScreen(
        uiState = uiState,
        onPlay = viewModel::play,
        onShare = { moment -> context.shareMoment(moment, shareChooserTitle) },
        onEdit = viewModel::edit,
        onDelete = viewModel::delete,
        onExport = { exportLauncher.launch(viewModel.suggestedFileName()) },
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
 * to open this screen is usually the thing marked ten minutes ago. Grouping by show is the export's
 * job — a document is read, a list is searched.
 *
 * @param uiState what to render.
 * @param onPlay plays the episode a moment is in, a few seconds before the mark.
 * @param onShare hands one moment to the share sheet.
 * @param onEdit opens a moment's note.
 * @param onDelete removes a moment.
 * @param onExport writes every moment to a document.
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
    onShare: (MomentWithEpisode) -> Unit,
    onEdit: (MomentWithEpisode) -> Unit,
    onDelete: (MomentWithEpisode) -> Unit,
    onExport: () -> Unit,
    onSaveNote: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onUndoDelete: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )
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
        )
        if (result == SnackbarResult.ActionPerformed) onUndoDelete() else onMessageShown()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MegaPodcastPlayerLargeTopAppBar(
                title = stringResource(R.string.moments_title),
                scrollBehavior = scrollBehavior,
                actions = {
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> LoadingState()

                uiState.isEmpty -> EmptyState(
                    icon = Icons.Rounded.Bookmarks,
                    title = stringResource(R.string.moments_empty_title),
                    description = stringResource(R.string.moments_empty_description),
                )

                else -> MomentList(
                    moments = uiState.moments,
                    onPlay = onPlay,
                    onShare = onShare,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
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
 * The list itself.
 *
 * Keyed by row id so that deleting one animates the rest rather than rebuilding the list, and so
 * the open overflow menu belongs to the moment it was opened on even as rows above it disappear.
 *
 * @param moments what to draw, newest first.
 * @param onPlay plays a moment.
 * @param onShare shares one.
 * @param onEdit opens one's note.
 * @param onDelete removes one.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentList(
    moments: List<MomentWithEpisode>,
    onPlay: (MomentWithEpisode) -> Unit,
    onShare: (MomentWithEpisode) -> Unit,
    onEdit: (MomentWithEpisode) -> Unit,
    onDelete: (MomentWithEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = moments, key = { it.moment.id }) { entry ->
            MomentRow(
                entry = entry,
                onPlay = { onPlay(entry) },
                onShare = { onShare(entry) },
                onEdit = { onEdit(entry) },
                onDelete = { onDelete(entry) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
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
 * @param entry the moment and its episode.
 * @param onPlay plays it.
 * @param onShare shares it.
 * @param onEdit opens its note.
 * @param onDelete removes it.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentRow(
    entry: MomentWithEpisode,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timecode = formatPosition(entry.moment.positionMs)
    val note = entry.moment.note?.takeIf { it.isNotBlank() }

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
        trailingContent = {
            MomentMenu(onShare = onShare, onEdit = onEdit, onDelete = onDelete)
        },
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = stringResource(R.string.moments_row_play, timecode),
            onClick = onPlay,
        ),
    )
}

/**
 * The per-row overflow: share, edit the note, delete.
 *
 * A menu rather than three buttons on every row. Sharing is the action this feature exists for but
 * it is still not the *common* one — playing the moment back is — and three trailing controls would
 * leave a two-line row with more chrome than content.
 *
 * @param onShare shares the moment.
 * @param onEdit opens its note.
 * @param onDelete removes it.
 * @param modifier layout modifier.
 */
@Composable
private fun MomentMenu(
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.moments_row_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.moments_action_share)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Share, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.moments_action_note)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.EditNote, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.moments_action_delete)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Delete, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Hands one moment to whatever the user shares things with.
 *
 * `createChooser` rather than the bare intent so the system sheet always appears: a default set for
 * "send text" would otherwise fire the same app every time without asking.
 *
 * @param moment the moment to share.
 * @param chooserTitle the sheet's heading.
 */
private fun Context.shareMoment(moment: MomentWithEpisode, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SHARE_MIME_TYPE
        putExtra(Intent.EXTRA_TEXT, momentShareText(moment))
    }
    startActivity(Intent.createChooser(send, chooserTitle))
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

/** A shared moment is plain text: it has to paste into a chat window as readily as into a note. */
private const val SHARE_MIME_TYPE = "text/plain"

/**
 * A saved moment, for the previews below.
 *
 * @param id the row id.
 * @param positionMs where in the episode it was marked.
 * @param note what the user typed, or null for a bare mark.
 */
private fun previewMoment(id: Long, positionMs: Long, note: String?) = MomentWithEpisode(
    moment = Moment(
        id = id,
        episodeId = "e1",
        positionMs = positionMs,
        note = note,
        createdAtMs = 1_756_000_000_000L,
    ),
    episodeTitle = "Podlodka #492 — Как устроены дизайн-системы",
    showTitle = "Podlodka Podcast",
    showArtworkUrl = null,
    feedUrl = "https://podlodka.io/rss",
    audioUrl = "https://cdn.example.com/e1.mp3",
)

@ThemePreviews
@FontScalePreviews
@Composable
private fun MomentsScreenPreview() {
    MegaPodcastPlayerTheme {
        MomentsScreen(
            uiState = MomentsUiState(
                moments = listOf(
                    previewMoment(1L, 743_000L, "Определение дизайн-системы, которое стоит записать"),
                    previewMoment(2L, 1_820_000L, null),
                ),
                isLoading = false,
            ),
            onPlay = {},
            onShare = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onSaveNote = {},
            onCancelEdit = {},
            onUndoDelete = {},
            onMessageShown = {},
        )
    }
}

@ThemePreviews
@Composable
private fun MomentsScreenEmptyPreview() {
    MegaPodcastPlayerTheme {
        MomentsScreen(
            uiState = MomentsUiState(isLoading = false),
            onPlay = {},
            onShare = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onSaveNote = {},
            onCancelEdit = {},
            onUndoDelete = {},
            onMessageShown = {},
        )
    }
}
