package md.borisveriga.megapodcastplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme

/**
 * The backup section's four rows: two for this app's own file, two for everybody else's.
 *
 * The pairs sit together, and the order says which is which — the backup first, because it is the
 * one that answers "what happens when the database is wiped", and the subscription list second,
 * because it answers a question about *other apps*. Their supporting lines do the rest: one carries
 * positions, downloads and moments, the other carries shows and nothing else.
 *
 * All four are disabled while any of them is in flight, because all four read or write the same
 * library and a second tap during a restore would only interleave two runs over the same rows.
 *
 * @param state what to render.
 * @param onExport called when the user asks to write a backup; the caller launches the picker.
 * @param onRestore called when the user asks to read one.
 * @param onExportOpml called when the user asks to write a subscription list.
 * @param onImportOpml called when the user asks to read one.
 * @param modifier layout modifier.
 */
@Composable
internal fun BackupRows(
    state: BackupUiState,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onExportOpml: () -> Unit,
    onImportOpml: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_backup_export)) },
            supportingContent = { Text(text = lastBackupLabel(state.lastBackupAtMs)) },
            leadingContent = { Icon(imageVector = Icons.Rounded.Save, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onExport)
                .semantics { role = Role.Button },
        )

        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_backup_restore)) },
            supportingContent = { Text(text = restoreLabel(state.restore)) },
            leadingContent = {
                Icon(imageVector = Icons.Rounded.Restore, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onRestore)
                .semantics { role = Role.Button },
        )

        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_opml_export)) },
            supportingContent = {
                Text(text = stringResource(R.string.settings_opml_export_description))
            },
            leadingContent = {
                Icon(imageVector = Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onExportOpml)
                .semantics { role = Role.Button },
        )

        ListItem(
            headlineContent = { Text(text = stringResource(R.string.settings_opml_import)) },
            supportingContent = {
                Text(text = stringResource(R.string.settings_opml_import_description))
            },
            leadingContent = {
                Icon(imageVector = Icons.AutoMirrored.Rounded.Login, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onImportOpml)
                .semantics { role = Role.Button },
        )
    }
}

/**
 * Asks the user to confirm a picked file before anything is fetched.
 *
 * Two kinds of file reach this dialog and it says different things about them. For a backup the
 * queue warning is spelled out, because replacing it is the one thing a restore does that a merge
 * cannot undo, and the re-download switch is offered because the file records what was offline. An
 * OPML file has neither: it carries shows and nothing else, so the switch is not drawn — a control
 * that cannot do anything is worse than a missing one — and the body says what will and will not
 * arrive, before a single feed is fetched.
 *
 * @param pending the validated document awaiting a decision.
 * @param onConfirm called with whether to re-queue the downloads the backup records; always false
 *   for an OPML import, which records none.
 * @param onDismiss called when the user backs out.
 */
@Composable
internal fun RestoreConfirmDialog(
    pending: PendingRestore,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reDownload by remember { mutableStateOf(false) }
    val isOpml = pending.source == RestoreSource.OPML

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isOpml) {
                        R.string.settings_opml_import_confirm_title
                    } else {
                        R.string.settings_backup_restore_confirm_title
                    },
                ),
            )
        },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        if (isOpml) {
                            R.plurals.settings_opml_import_confirm_body
                        } else {
                            R.plurals.settings_backup_restore_confirm_body
                        },
                        pending.showCount,
                        pending.showCount,
                    ),
                )
                // Said rather than folded into the total. A file whose extra rows were folders is
                // ordinary; one whose rows were mostly refused is a file worth looking at again,
                // and the user cannot tell the two apart from a count of what arrived.
                if (pending.skipped > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_opml_import_skipped,
                            pending.skipped,
                            pending.skipped,
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
                if (!isOpml) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MegaPodcastPlayerTheme.spacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_backup_re_download),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = reDownload, onCheckedChange = { reDownload = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reDownload) }) {
                Text(
                    text = stringResource(
                        if (isOpml) {
                            R.string.settings_opml_import_confirm_action
                        } else {
                            R.string.settings_backup_restore_confirm_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_backup_cancel))
            }
        },
    )
}

/**
 * Reports what a finished restore managed to do.
 *
 * Feeds that failed are named rather than counted: "two shows could not be fetched" is not
 * actionable, and the whole point of the file is that the user can go and add the missing one.
 *
 * @param summary the finished run.
 * @param onDismiss called when the user closes it.
 */
@Composable
internal fun RestoreResultDialog(summary: RestoreSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_backup_restore_done_title)) },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_backup_restore_done_shows,
                        summary.showsRestored,
                        summary.showsRestored,
                    ),
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_backup_restore_done_episodes,
                        summary.episodesRestored,
                        summary.episodesRestored,
                    ),
                    modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                )
                if (summary.episodesMissing > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_backup_restore_done_missing,
                            summary.episodesMissing,
                            summary.episodesMissing,
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
                if (summary.momentsRestored > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_backup_restore_done_moments,
                            summary.momentsRestored,
                            summary.momentsRestored,
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
                if (summary.failedTitles.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.settings_backup_restore_done_failed,
                            summary.failedTitles.joinToString(separator = ", "),
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_backup_done))
            }
        },
    )
}

/**
 * The export row's supporting line.
 *
 * "No backup yet" is the whole reason this section carries a subtitle: the database is recreated
 * rather than migrated, so the absence of a backup is a warning the user needs before the release
 * that acts on it, not after.
 *
 * @param lastBackupAtMs when the last export was written, or null if never.
 * @return the line to show.
 */
@Composable
private fun lastBackupLabel(lastBackupAtMs: Long?): String = if (lastBackupAtMs == null) {
    stringResource(R.string.settings_backup_never)
} else {
    stringResource(R.string.settings_backup_last, formatBackupDate(lastBackupAtMs))
}

/**
 * The restore row's supporting line.
 *
 * @param restore the current or most recent run.
 * @return the line to show.
 */
@Composable
private fun restoreLabel(restore: RestoreRun?): String = when (restore) {
    is RestoreRun.Running -> if (restore.progress.total > 0) {
        stringResource(
            R.string.settings_backup_restoring_progress,
            restore.progress.completed + 1,
            restore.progress.total,
            restore.progress.currentTitle,
        )
    } else {
        stringResource(R.string.settings_backup_restoring)
    }

    is RestoreRun.Failed -> stringResource(R.string.settings_backup_restore_failed)

    is RestoreRun.Finished, null -> stringResource(R.string.settings_backup_restore_description)
}

/**
 * Formats an export timestamp in the reader's own locale and zone.
 *
 * @param epochMilli when the export was written.
 * @return a medium-length local date, e.g. "7 Sept 2026".
 */
private fun formatBackupDate(epochMilli: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMilli))
