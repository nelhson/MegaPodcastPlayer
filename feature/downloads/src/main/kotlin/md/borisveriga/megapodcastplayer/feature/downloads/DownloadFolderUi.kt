package md.borisveriga.megapodcastplayer.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.common.format.formatBytes
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadFolder
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.FolderNameProblem
import md.borisveriga.megapodcastplayer.core.model.FolderView
import md.borisveriga.megapodcastplayer.core.model.totals

/**
 * The folder half of the downloads screen: choosing which folder to look at, moving a download
 * between folders, and making, renaming and deleting folders.
 *
 * A folder here is a label — see `DownloadFolders` — so nothing on these surfaces moves a byte,
 * and only one of them deletes anything: the delete-folder question, and only when its box is
 * ticked.
 */

/**
 * What the user can do to folders from the downloads screen, gathered so the screen's signature
 * does not grow by one lambda per action.
 *
 * Every member defaults to doing nothing, which is what a preview and most tests want.
 *
 * @property onShowFolder shows one folder, or all of them.
 * @property onMove files a download under a folder; null for *Downloads*.
 * @property onCreate makes a folder, moving a download into it when one is given, and reports a
 *   refused name — or null once it is made — to its callback.
 * @property onRename renames a folder and reports a refused name, or null, to its callback.
 * @property onSetDefault sets where downloads go when nobody says; null for *Downloads*.
 * @property onDelete deletes a folder, and its downloads too when the flag is set.
 */
class DownloadFolderActions(
    val onShowFolder: (FolderView) -> Unit = {},
    val onMove: (episodeId: String, folderId: String?) -> Unit = { _, _ -> },
    val onCreate: (name: String, moveEpisodeId: String?, onDone: (FolderNameProblem?) -> Unit) -> Unit =
        { _, _, _ -> },
    val onRename: (folderId: String, name: String, onDone: (FolderNameProblem?) -> Unit) -> Unit =
        { _, _, _ -> },
    val onSetDefault: (folderId: String?) -> Unit = {},
    val onDelete: (folderId: String, withDownloads: Boolean) -> Unit = { _, _ -> },
)

/**
 * The chip that says which folder the list is showing, and opens the list of them.
 *
 * A dropdown rather than one chip per folder: folders are the user's and there may be many, and a
 * row of them would push the Audio/Video chips off the folded screen. Selected-looking whenever it
 * narrows the list, as the other chips are.
 *
 * @param view the folder on screen.
 * @param folders every folder.
 * @param counts how many downloads each folder holds; *Downloads* under null.
 * @param onShowFolder a folder was chosen.
 * @param onNewFolder *New folder* was chosen.
 * @param onManageFolders *Manage folders* was chosen.
 */
@Composable
internal fun FolderChip(
    view: FolderView,
    folders: DownloadFolders,
    counts: Map<String?, Int>,
    onShowFolder: (FolderView) -> Unit,
    onNewFolder: () -> Unit,
    onManageFolders: () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    // Read here rather than inside `semantics`, which is not a composable scope. The label is a
    // folder's name and says no verb; this supplies it.
    val description = stringResource(R.string.downloads_folder_chip_description)
    val label = when (view) {
        FolderView.AllFolders -> stringResource(R.string.downloads_folder_all)
        FolderView.Downloads -> stringResource(R.string.downloads_folder_downloads)
        is FolderView.Folder -> folders.folders.firstOrNull { it.id == view.folderId }?.name.orEmpty()
    }

    Box {
        FilterChip(
            selected = view != FolderView.AllFolders,
            onClick = { isExpanded = true },
            label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
            modifier = Modifier.semantics { contentDescription = description },
        )

        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            fun choose(choice: FolderView) {
                isExpanded = false
                onShowFolder(choice)
            }
            FolderMenuItem(
                text = stringResource(R.string.downloads_folder_all),
                count = counts.values.sum(),
                isCurrent = view == FolderView.AllFolders,
                onClick = { choose(FolderView.AllFolders) },
            )
            FolderMenuItem(
                text = stringResource(R.string.downloads_folder_downloads),
                count = counts[null] ?: 0,
                isCurrent = view == FolderView.Downloads,
                onClick = { choose(FolderView.Downloads) },
            )
            folders.folders.forEach { folder ->
                FolderMenuItem(
                    text = folder.name,
                    count = counts[folder.id] ?: 0,
                    isCurrent = view == FolderView.Folder(folder.id),
                    onClick = { choose(FolderView.Folder(folder.id)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.downloads_folder_new)) },
                leadingIcon = { Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = null) },
                onClick = {
                    isExpanded = false
                    onNewFolder()
                },
            )
            // Only once there is something to manage: *Downloads* cannot be renamed or deleted.
            if (folders.folders.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.downloads_folder_manage)) },
                    leadingIcon = { Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null) },
                    onClick = {
                        isExpanded = false
                        onManageFolders()
                    },
                )
            }
        }
    }
}

/**
 * One folder in the folder menu: its name, a tick when it is on screen, and how many it holds.
 *
 * @param text the folder's name.
 * @param count how many downloads it holds.
 * @param isCurrent whether it is the folder on screen.
 * @param onClick shows it.
 */
@Composable
private fun FolderMenuItem(text: String, count: Int, isCurrent: Boolean, onClick: () -> Unit) {
    val current = stringResource(R.string.downloads_folder_current)
    DropdownMenuItem(
        text = { Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            // Always given the slot, empty when not current, so the names line up down the menu.
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = current,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(modifier = Modifier.size(MENU_ICON_SIZE))
            }
        },
        trailingIcon = {
            Text(
                text = count.toString(),
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}

/**
 * Where a download should be filed: *Downloads*, a folder, or a new one.
 *
 * Tapping a folder moves the download and closes the sheet in one step, since a move can be undone
 * from the snackbar it leaves. The folder it is already in is ticked and does nothing.
 *
 * @param episodeTitle the download's title, said in the subtitle so the sheet names what it moves.
 * @param currentFolderId the folder it is in now; null for *Downloads*.
 * @param folders every folder.
 * @param onMove a folder was chosen; null for *Downloads*.
 * @param onNewFolder *New folder* was chosen.
 * @param onDismiss the sheet was put away.
 */
// MegaPodcastPlayerBottomSheet is built on the experimental ModalBottomSheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveToFolderSheet(
    episodeTitle: String,
    currentFolderId: String?,
    folders: DownloadFolders,
    onMove: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.downloads_move_title),
        subtitle = stringResource(R.string.downloads_move_subtitle, episodeTitle),
    ) {
        MoveToFolderOptions(
            currentFolderId = currentFolderId,
            folders = folders,
            onMove = onMove,
            onNewFolder = onNewFolder,
        )
    }
}

/**
 * The move sheet's body, without the sheet, so a test can hold it.
 *
 * @param currentFolderId the folder the download is in now; null for *Downloads*.
 * @param folders every folder.
 * @param onMove a folder was chosen; null for *Downloads*. Not called for the current one.
 * @param onNewFolder *New folder* was chosen.
 */
@Composable
internal fun MoveToFolderOptions(
    currentFolderId: String?,
    folders: DownloadFolders,
    onMove: (String?) -> Unit,
    onNewFolder: () -> Unit,
) {
    val current = stringResource(R.string.downloads_folder_current)
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        val choices = listOf<Pair<String?, String>>(null to stringResource(R.string.downloads_folder_downloads)) +
            folders.folders.map { it.id to it.name }
        choices.forEach { (id, name) ->
            val isCurrent = id == currentFolderId
            ListItem(
                headlineContent = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
                trailingContent = if (isCurrent) {
                    { Icon(imageVector = Icons.Rounded.Check, contentDescription = current) }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable(enabled = !isCurrent, role = Role.Button) { onMove(id) },
            )
        }
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.downloads_folder_new)) },
            leadingContent = { Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clickable(role = Role.Button, onClick = onNewFolder),
        )
    }
}

/**
 * Asks for a folder's name, to make one or to rename one.
 *
 * The name is judged as it is typed, against the folders as the screen has them, so a name that
 * will be refused says so before the button is pressed rather than after. *Downloads* — the
 * built-in folder's name in the app's language — counts as taken: two folders by one name is the
 * thing the rule is there to prevent, whichever of them the user made.
 *
 * @param initialName what the field starts with: empty for a new folder, the name for a rename.
 * @param folders every folder, to judge the name against.
 * @param renamingId the folder being renamed, whose own name does not count as taken; null when
 *   making one.
 * @param onConfirm the name was confirmed; given a callback to report a refusal the screen could
 *   not have seen coming, or null to close.
 * @param onDismiss the dialog was put away.
 */
@Composable
internal fun FolderNameDialog(
    initialName: String,
    folders: DownloadFolders,
    renamingId: String?,
    onConfirm: (name: String, onDone: (FolderNameProblem?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var refused by remember { mutableStateOf<FolderNameProblem?>(null) }
    val builtIn = stringResource(R.string.downloads_folder_downloads)
    val problem = refused ?: folderNameProblem(name, folders, renamingId, builtIn)
    // Blank is the field's starting state, not a mistake to point at; the button being off says it.
    val shownProblem = problem.takeIf { it != FolderNameProblem.BLANK }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (renamingId == null) {
                        R.string.downloads_folder_create_title
                    } else {
                        R.string.downloads_folder_rename_title
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    refused = null
                },
                label = { Text(text = stringResource(R.string.downloads_folder_name_label)) },
                singleLine = true,
                isError = shownProblem != null,
                supportingText = shownProblem?.let { { Text(text = it.message()) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onConfirm(name) { result -> refused = result } },
            ) {
                Text(
                    text = stringResource(
                        if (renamingId == null) R.string.downloads_folder_create else R.string.downloads_folder_rename,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.downloads_folder_cancel)) }
        },
    )
}

/**
 * What is wrong with a folder name as typed into the dialog, if anything.
 *
 * The folders' own rule, plus one the folders cannot know: the built-in folder's name, which is a
 * string in the app's language rather than a stored folder, counts as taken.
 *
 * @param name the name as typed.
 * @param folders every folder.
 * @param renamingId the folder being renamed, whose own name is allowed; null when making one.
 * @param builtInName the built-in folder's name as the screen shows it.
 * @return the problem, or null when the name can be sent.
 */
internal fun folderNameProblem(
    name: String,
    folders: DownloadFolders,
    renamingId: String?,
    builtInName: String,
): FolderNameProblem? =
    folders.nameProblem(name, ignoringId = renamingId)
        ?: FolderNameProblem.TAKEN.takeIf { name.trim().equals(builtInName, ignoreCase = true) }

/** What to tell the user about a refused folder name. */
@Composable
private fun FolderNameProblem.message(): String = when (this) {
    FolderNameProblem.BLANK -> stringResource(R.string.downloads_folder_name_blank)

    FolderNameProblem.TOO_LONG ->
        pluralStringResource(
            R.plurals.downloads_folder_name_too_long,
            DownloadFolders.MAX_NAME_LENGTH,
            DownloadFolders.MAX_NAME_LENGTH,
        )

    FolderNameProblem.TAKEN -> stringResource(R.string.downloads_folder_name_taken)
}

/**
 * Every folder, with what can be done to each: make it the default, rename it, delete it.
 *
 * *Downloads* is listed too, because it can be the default and is the one to choose to stop new
 * downloads going into a folder; it has no other actions.
 *
 * @param folders every folder.
 * @param counts how many downloads each holds; *Downloads* under null.
 * @param onSetDefault a folder was made the default; null for *Downloads*.
 * @param onRename *Rename* was chosen for a folder.
 * @param onDelete *Delete* was chosen for a folder.
 * @param onNewFolder *New folder* was chosen.
 * @param onDismiss the sheet was put away.
 */
// MegaPodcastPlayerBottomSheet is built on the experimental ModalBottomSheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageFoldersSheet(
    folders: DownloadFolders,
    counts: Map<String?, Int>,
    onSetDefault: (String?) -> Unit,
    onRename: (DownloadFolder) -> Unit,
    onDelete: (DownloadFolder) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.downloads_folder_manage_title),
    ) {
        ManageFoldersOptions(folders, counts, onSetDefault, onRename, onDelete, onNewFolder)
    }
}

/**
 * The manage sheet's body, without the sheet, so a test can hold it.
 *
 * @param folders every folder.
 * @param counts how many downloads each holds; *Downloads* under null.
 * @param onSetDefault a folder was made the default; null for *Downloads*.
 * @param onRename *Rename* was chosen for a folder.
 * @param onDelete *Delete* was chosen for a folder.
 * @param onNewFolder *New folder* was chosen.
 */
@Composable
internal fun ManageFoldersOptions(
    folders: DownloadFolders,
    counts: Map<String?, Int>,
    onSetDefault: (String?) -> Unit,
    onRename: (DownloadFolder) -> Unit,
    onDelete: (DownloadFolder) -> Unit,
    onNewFolder: () -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        ManagedFolderRow(
            name = stringResource(R.string.downloads_folder_downloads),
            count = counts[null] ?: 0,
            isDefault = folders.defaultFolderId == null,
            onSetDefault = { onSetDefault(null) },
            onRename = null,
            onDelete = null,
        )
        folders.folders.forEach { folder ->
            ManagedFolderRow(
                name = folder.name,
                count = counts[folder.id] ?: 0,
                isDefault = folders.defaultFolderId == folder.id,
                onSetDefault = { onSetDefault(folder.id) },
                onRename = { onRename(folder) },
                onDelete = { onDelete(folder) },
            )
        }
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.downloads_folder_new)) },
            leadingContent = { Icon(imageVector = Icons.Rounded.CreateNewFolder, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clickable(role = Role.Button, onClick = onNewFolder),
        )
    }
}

/**
 * One folder in the manage sheet, with its actions behind an overflow button.
 *
 * @param name the folder's name.
 * @param count how many downloads it holds.
 * @param isDefault whether new downloads go here.
 * @param onSetDefault makes it the default.
 * @param onRename starts a rename; null for *Downloads*, which keeps its name.
 * @param onDelete starts a delete; null for *Downloads*, which cannot be deleted.
 */
@Composable
private fun ManagedFolderRow(
    name: String,
    count: Int,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val countText = pluralStringResource(R.plurals.downloads_folder_count, count, count)
    val supporting = if (isDefault) {
        stringResource(
            R.string.downloads_folder_counts_combined,
            countText,
            stringResource(R.string.downloads_folder_is_default),
        )
    } else {
        countText
    }

    ListItem(
        headlineContent = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(text = supporting) },
        leadingContent = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { isMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.downloads_folder_more, name),
                    )
                }
                DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                    if (!isDefault) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.downloads_folder_make_default)) },
                            onClick = {
                                isMenuOpen = false
                                onSetDefault()
                            },
                        )
                    }
                    onRename?.let { rename ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.downloads_folder_rename)) },
                            onClick = {
                                isMenuOpen = false
                                rename()
                            },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.downloads_folder_delete)) },
                            onClick = {
                                isMenuOpen = false
                                delete()
                            },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/**
 * Asks before deleting a folder, and whether its downloads go with it.
 *
 * Deleting the folder alone loses nothing — its downloads move to *Downloads* — so the box is off
 * by default and the question, read without ticking anything, is a harmless one. Ticked, it counts
 * what leaves the device and the button says so (COPY_RULES §3).
 *
 * @param name the folder's name.
 * @param count how many downloads it holds.
 * @param freed what deleting them would give back, already formatted; null when nothing has
 *   finished downloading in it.
 * @param onConfirm the folder is to go; with its downloads when the flag is set.
 * @param onDismiss the question was put away.
 */
@Composable
internal fun DeleteFolderDialog(
    name: String,
    count: Int,
    freed: String?,
    onConfirm: (withDownloads: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var withDownloads by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.downloads_folder_delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
                Text(
                    text = if (count == 0) {
                        stringResource(R.string.downloads_folder_delete_empty)
                    } else {
                        pluralStringResource(R.plurals.downloads_folder_delete_moves, count, count)
                    },
                )
                if (count > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = withDownloads, role = Role.Checkbox) { withDownloads = it },
                    ) {
                        Checkbox(checked = withDownloads, onCheckedChange = null)
                        Text(
                            text = if (freed != null) {
                                pluralStringResource(
                                    R.plurals.downloads_folder_delete_also_freeing,
                                    count,
                                    count,
                                    freed,
                                )
                            } else {
                                pluralStringResource(R.plurals.downloads_folder_delete_also, count, count)
                            },
                            modifier = Modifier.padding(start = MegaPodcastPlayerTheme.spacing.sm),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(withDownloads) }) {
                Text(
                    text = stringResource(
                        if (withDownloads) {
                            R.string.downloads_folder_delete_with_downloads
                        } else {
                            R.string.downloads_folder_delete
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.downloads_folder_cancel)) }
        },
    )
}

/** What the folder-name dialog was opened to do. */
internal sealed interface FolderNameRequest {

    /**
     * Make a folder.
     *
     * @property moveEpisodeId the download whose move sheet asked for it, which goes into the new
     *   folder once it exists; null when it came from the folder menu.
     */
    data class Create(val moveEpisodeId: String?) : FolderNameRequest

    /**
     * Rename a folder.
     *
     * @property folderId the folder.
     */
    data class Rename(val folderId: String) : FolderNameRequest
}

/**
 * Which folder sheet or dialog is open.
 *
 * Saved across the activity being recreated — opening the Fold mid-rename must not lose the
 * dialog — as ids rather than objects: each surface re-reads what it names from the state, so a
 * download or folder that went away in the meantime resolves to no surface at all.
 *
 * @param movingEpisodeId the download whose move sheet is open.
 * @param nameDialog the folder-name dialog, if open.
 * @param isManaging whether the manage sheet is open.
 * @param deletingFolderId the folder whose delete question is open.
 */
@Stable
internal class FolderUiState(
    movingEpisodeId: String?,
    nameDialog: FolderNameRequest?,
    isManaging: Boolean,
    deletingFolderId: String?,
) {
    /** The download whose move sheet is open. */
    var movingEpisodeId by mutableStateOf(movingEpisodeId)

    /** The folder-name dialog, if open. */
    var nameDialog by mutableStateOf(nameDialog)

    /** Whether the manage sheet is open. */
    var isManaging by mutableStateOf(isManaging)

    /** The folder whose delete question is open. */
    var deletingFolderId by mutableStateOf(deletingFolderId)

    companion object {
        /** Saves the four fields as strings; a request is spelled "create:<id>" or "rename:<id>". */
        val Saver: Saver<FolderUiState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.movingEpisodeId.orEmpty(),
                    when (val request = state.nameDialog) {
                        is FolderNameRequest.Create -> CREATE + request.moveEpisodeId.orEmpty()
                        is FolderNameRequest.Rename -> RENAME + request.folderId
                        null -> ""
                    },
                    state.isManaging.toString(),
                    state.deletingFolderId.orEmpty(),
                )
            },
            restore = { saved ->
                val request = saved[1]
                FolderUiState(
                    movingEpisodeId = saved[0].ifEmpty { null },
                    nameDialog = when {
                        request.startsWith(CREATE) ->
                            FolderNameRequest.Create(request.removePrefix(CREATE).ifEmpty { null })

                        request.startsWith(RENAME) -> FolderNameRequest.Rename(request.removePrefix(RENAME))

                        else -> null
                    },
                    isManaging = saved[2].toBoolean(),
                    deletingFolderId = saved[3].ifEmpty { null },
                )
            },
        )

        private const val CREATE = "create:"
        private const val RENAME = "rename:"
    }
}

/**
 * A [FolderUiState] with nothing open, kept across the activity being recreated.
 *
 * @return the state.
 */
@Composable
internal fun rememberFolderUiState(): FolderUiState = rememberSaveable(saver = FolderUiState.Saver) {
    FolderUiState(movingEpisodeId = null, nameDialog = null, isManaging = false, deletingFolderId = null)
}

/**
 * Draws whichever folder sheets and dialogs [state] says are open.
 *
 * @param uiState the downloads and folders the surfaces describe.
 * @param folderActions what their buttons do.
 * @param state which are open; each closes itself here once done.
 */
@Composable
internal fun DownloadFolderSurfaces(
    uiState: DownloadsUiState,
    folderActions: DownloadFolderActions,
    state: FolderUiState,
) {
    val resources = LocalResources.current
    val folders = uiState.folders

    val moving = uiState.downloads.firstOrNull { it.episode.id == state.movingEpisodeId }
    if (moving != null) {
        MoveToFolderSheet(
            episodeTitle = moving.episode.title,
            currentFolderId = folders.folderOf(moving.episode.id),
            folders = folders,
            onMove = { folderId ->
                state.movingEpisodeId = null
                folderActions.onMove(moving.episode.id, folderId)
            },
            onNewFolder = {
                state.movingEpisodeId = null
                state.nameDialog = FolderNameRequest.Create(moveEpisodeId = moving.episode.id)
            },
            onDismiss = { state.movingEpisodeId = null },
        )
    }

    if (state.isManaging) {
        ManageFoldersSheet(
            folders = folders,
            counts = uiState.folderCounts,
            onSetDefault = folderActions.onSetDefault,
            onRename = { state.nameDialog = FolderNameRequest.Rename(it.id) },
            onDelete = { state.deletingFolderId = it.id },
            onNewFolder = { state.nameDialog = FolderNameRequest.Create(moveEpisodeId = null) },
            onDismiss = { state.isManaging = false },
        )
    }

    when (val request = state.nameDialog) {
        is FolderNameRequest.Create -> FolderNameDialog(
            initialName = "",
            folders = folders,
            renamingId = null,
            onConfirm = { name, onRefused ->
                folderActions.onCreate(name, request.moveEpisodeId) { problem ->
                    if (problem == null) state.nameDialog = null else onRefused(problem)
                }
            },
            onDismiss = { state.nameDialog = null },
        )

        is FolderNameRequest.Rename -> folders.folders.firstOrNull { it.id == request.folderId }?.let { folder ->
            FolderNameDialog(
                initialName = folder.name,
                folders = folders,
                renamingId = folder.id,
                onConfirm = { name, onRefused ->
                    folderActions.onRename(folder.id, name) { problem ->
                        if (problem == null) state.nameDialog = null else onRefused(problem)
                    }
                },
                onDismiss = { state.nameDialog = null },
            )
        }

        null -> Unit
    }

    folders.folders.firstOrNull { it.id == state.deletingFolderId }?.let { folder ->
        val members = uiState.downloads.filter { folders.folderOf(it.episode.id) == folder.id }
        val freed = members.totals(uiState.videoDownloads).totalBytes
        DeleteFolderDialog(
            name = folder.name,
            count = members.size,
            freed = freed.takeIf { it > 0L }?.let { formatBytes(resources, it) },
            onConfirm = { withDownloads ->
                state.deletingFolderId = null
                folderActions.onDelete(folder.id, withDownloads)
            },
            onDismiss = { state.deletingFolderId = null },
        )
    }
}

/** The width a menu item's leading icon takes, held open when it has none. */
private val MENU_ICON_SIZE = 24.dp
