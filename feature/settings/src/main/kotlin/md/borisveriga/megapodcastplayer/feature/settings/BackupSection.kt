package md.borisveriga.megapodcastplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
 * The backup section's two rows.
 *
 * Both are disabled while either action is in flight, because both write the same library and a
 * second tap during a restore would only interleave two runs over the same rows.
 *
 * @param state what to render.
 * @param onExport called when the user asks to write a backup; the caller launches the picker.
 * @param onRestore called when the user asks to read one.
 * @param modifier layout modifier.
 */
@Composable
internal fun BackupRows(
    state: BackupUiState,
    onExport: () -> Unit,
    onRestore: () -> Unit,
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
    }
}

/**
 * Asks the user to confirm a picked backup before anything is written.
 *
 * The queue warning is spelled out because replacing it is the one thing a restore does that a
 * merge cannot undo.
 *
 * @param pending the validated document awaiting a decision.
 * @param onConfirm called with whether to re-queue the downloads the backup records.
 * @param onDismiss called when the user backs out.
 */
@Composable
internal fun RestoreConfirmDialog(
    pending: PendingRestore,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reDownload by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_backup_restore_confirm_title)) },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_backup_restore_confirm_body,
                        pending.showCount,
                        pending.showCount,
                    ),
                )
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reDownload) }) {
                Text(text = stringResource(R.string.settings_backup_restore_confirm_action))
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
