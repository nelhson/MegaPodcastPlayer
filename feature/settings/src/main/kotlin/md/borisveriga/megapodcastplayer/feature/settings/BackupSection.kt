package md.borisveriga.megapodcastplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * The subscriptions section's two rows: out to a file, and back in from one.
 *
 * There were four. Two of them wrote this app's own JSON — the library plus positions, the queue,
 * downloads and moments — and two wrote OPML, which is shows and nothing else. The pair have
 * collapsed into one because what a subscription *is* here is a link: an RSS feed URL or a YouTube
 * playlist's, from which everything else is fetched. A file that also carried the fetching's
 * results could only ever disagree with the feed, and the second row differed from the first by a
 * parenthesis.
 *
 * Both are disabled while either is in flight, because both read or write the same library and a
 * second tap during an import would only interleave two runs over the same rows.
 *
 * @param state what to render.
 * @param onExport called when the user asks to write the list; the caller launches the picker.
 * @param onImport called when the user asks to read one.
 * @param modifier layout modifier.
 */
@Composable
internal fun BackupRows(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.settings_subscriptions_export))
            },
            supportingContent = { Text(text = lastExportLabel(state.lastBackupAtMs)) },
            leadingContent = {
                Icon(imageVector = Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onExport)
                .semantics { role = Role.Button },
        )

        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.settings_subscriptions_import))
            },
            supportingContent = { Text(text = importLabel(state.restore)) },
            leadingContent = {
                Icon(imageVector = Icons.AutoMirrored.Rounded.Login, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(enabled = !state.isBusy, onClick = onImport)
                .semantics { role = Role.Button },
        )
    }
}

/**
 * Asks the user to confirm a picked file before anything is fetched.
 *
 * It says what will and will not arrive while there is still nothing to undo: a list carries links,
 * so a show already in the library is left exactly as it is, and nothing in the file can move a
 * position or delete a download.
 *
 * @param pending the validated document awaiting a decision.
 * @param onConfirm called when the user accepts.
 * @param onDismiss called when the user backs out.
 */
@Composable
internal fun RestoreConfirmDialog(
    pending: PendingRestore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.settings_subscriptions_import_confirm_title))
        },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_subscriptions_import_confirm_body,
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
                            R.plurals.settings_subscriptions_import_skipped,
                            pending.skipped,
                            pending.skipped,
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.settings_subscriptions_import_confirm_action),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

/**
 * Reports what a finished import managed to do.
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
        title = {
            Text(text = stringResource(R.string.settings_subscriptions_import_done_title))
        },
        text = {
            Column {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_subscriptions_import_done_shows,
                        summary.showsRestored,
                        summary.showsRestored,
                    ),
                )
                if (summary.failedTitles.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.settings_subscriptions_import_done_failed,
                            summary.failedTitles.joinToString(separator = ", "),
                        ),
                        modifier = Modifier.padding(top = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_done))
            }
        },
    )
}

/**
 * The export row's supporting line.
 *
 * "Not exported yet" is the whole reason this section carries a subtitle: the database is recreated
 * rather than migrated, so the absence of an export is a warning the user needs before the release
 * that acts on it, not after.
 *
 * @param lastExportAtMs when the last export was written, or null if never.
 * @return the line to show.
 */
@Composable
private fun lastExportLabel(lastExportAtMs: Long?): String = if (lastExportAtMs == null) {
    stringResource(R.string.settings_subscriptions_never)
} else {
    stringResource(R.string.settings_subscriptions_last, formatExportDate(lastExportAtMs))
}

/**
 * The import row's supporting line.
 *
 * @param restore the current or most recent run.
 * @return the line to show.
 */
@Composable
private fun importLabel(restore: RestoreRun?): String = when (restore) {
    is RestoreRun.Running -> if (restore.progress.total > 0) {
        stringResource(
            R.string.settings_subscriptions_importing_progress,
            restore.progress.completed + 1,
            restore.progress.total,
            restore.progress.currentTitle,
        )
    } else {
        stringResource(R.string.settings_subscriptions_importing)
    }

    is RestoreRun.Failed -> stringResource(R.string.settings_subscriptions_import_failed)

    is RestoreRun.Finished, null ->
        stringResource(R.string.settings_subscriptions_import_description)
}

/**
 * Formats an export timestamp in the reader's own locale and zone.
 *
 * @param epochMilli when the export was written.
 * @return a medium-length local date, e.g. "7 Sept 2026".
 */
private fun formatExportDate(epochMilli: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMilli))
