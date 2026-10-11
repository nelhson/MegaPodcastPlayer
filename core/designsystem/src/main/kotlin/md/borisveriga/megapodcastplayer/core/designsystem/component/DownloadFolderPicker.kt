package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders

/**
 * "Save to: Commute", beside a download button: which of the user's folders the download will be
 * filed under, and a menu to choose another.
 *
 * Draws nothing while the user has made no folders: with only the built-in *Downloads* there is
 * nothing to choose, and a control with one option is a control that is in the way.
 *
 * A folder is a label on a download, not a place on the phone (see `DownloadFolders`), so nothing
 * here names a path.
 *
 * @param folders the user's folders.
 * @param selectedFolderId the folder chosen; null for *Downloads*.
 * @param onSelect a folder was chosen; null for *Downloads*.
 * @param modifier layout modifier.
 */
@Composable
fun DownloadFolderPicker(
    folders: DownloadFolders,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.folders.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }
    val builtIn = stringResource(R.string.designsystem_folder_downloads)
    val selectedName = folders.folders.firstOrNull { it.id == selectedFolderId }?.name ?: builtIn
    // Read here rather than inside `semantics`, which is not a composable scope.
    val description = stringResource(R.string.designsystem_folder_picker_description, selectedName)
    val current = stringResource(R.string.designsystem_folder_current)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.designsystem_folder_picker_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            AssistChip(
                onClick = { isExpanded = true },
                label = { Text(text = selectedName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(imageVector = Icons.Rounded.Folder, contentDescription = null) },
                trailingIcon = { Icon(imageVector = Icons.Rounded.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.semantics { contentDescription = description },
            )
            DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
                val choices = listOf<Pair<String?, String>>(null to builtIn) + folders.folders.map { it.id to it.name }
                choices.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = if (id == selectedFolderId) {
                            { Icon(imageVector = Icons.Rounded.Check, contentDescription = current) }
                        } else {
                            null
                        },
                        onClick = {
                            isExpanded = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The folder chosen in a download sheet, kept across the activity being recreated.
 *
 * Until the user touches the picker it shows where a download with no choice would go — the
 * folder the episode is already in, else the default — and asks for exactly that, as a swipe
 * would; following the folders as they load rather than freezing whatever was known when the
 * sheet opened. Once touched, the choice is the user's and is sent as such.
 *
 * @param folders the user's folders.
 * @param episodeId the episode the sheet is about; a different episode starts untouched.
 * @return the choice.
 */
@Composable
fun rememberDownloadFolderChoice(
    folders: DownloadFolders,
    episodeId: String,
): DownloadFolderChoice {
    var picked by rememberSaveable(episodeId) { mutableStateOf<String?>(null) }
    var touched by rememberSaveable(episodeId) { mutableStateOf(false) }
    return DownloadFolderChoice(
        folderId = if (touched) picked else folders.folderForDownload(episodeId, DownloadDestination.Unspecified),
        isTouched = touched,
        onChoose = {
            picked = it
            touched = true
        },
    )
}

/**
 * A folder chosen in a download sheet.
 *
 * @property folderId the folder the picker shows; null for *Downloads*.
 * @property isTouched whether the user chose it, rather than it being where the download would go
 *   anyway.
 * @property onChoose records a choice.
 */
class DownloadFolderChoice(
    val folderId: String?,
    val isTouched: Boolean,
    val onChoose: (String?) -> Unit,
) {
    /** The choice as a request to the download repository. */
    val destination: DownloadDestination
        get() = when {
            !isTouched -> DownloadDestination.Unspecified
            folderId == null -> DownloadDestination.Downloads
            else -> DownloadDestination.Folder(folderId)
        }
}

/** The picker with a folder chosen, in both schemes. */
@ThemePreviews
@Composable
internal fun DownloadFolderPickerPreview() {
    MegaPodcastPlayerTheme {
        DownloadFolderPicker(
            folders = DownloadFolders.NONE.created(id = "f1", name = "Commute"),
            selectedFolderId = "f1",
            onSelect = {},
        )
    }
}
