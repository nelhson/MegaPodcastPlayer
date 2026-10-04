package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import md.borisveriga.megapodcastplayer.core.common.format.formatVideoQuality
import md.borisveriga.megapodcastplayer.core.designsystem.component.WavyProgressLine
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

/**
 * Asks which quality to keep an episode's video at, before anything is downloaded.
 *
 * The list is the renditions the video actually comes in, asked of the extractor as the dialog
 * opens, because a download at a height the player cannot show would be a file nothing plays. A
 * pick is a choice and not yet an action: *Download* confirms it, so a mistaken tap on a 1080p row
 * on mobile data costs nothing.
 *
 * The rendition already on the phone, or on its way, starts picked; picking another and confirming
 * replaces it. Below the list, the way to get the space back — *Delete* for a finished file,
 * *Cancel* for a transfer, which has no file to delete yet.
 *
 * @param qualities the renditions on offer, lowest first; null while still being asked for.
 * @param failed true when the renditions could not be asked for.
 * @param download the episode's downloaded video, or null when it has none.
 * @param onDownload a rendition was confirmed.
 * @param onRemoveDownload the downloaded video is to go, finished or not.
 * @param onDismiss the dialog was put away.
 */
@Composable
internal fun VideoDownloadDialog(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    onDownload: (VideoQuality) -> Unit,
    onRemoveDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Saved as the height: VideoQuality is not Parcelable, and the height is all it holds.
    var pickedHeight by rememberSaveable { mutableStateOf(download?.quality?.height) }
    val picked = pickedHeight?.let(::VideoQuality)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.video_dialog_title)) },
        text = {
            VideoDownloadOptions(
                qualities = qualities,
                failed = failed,
                download = download,
                picked = picked,
                onPick = { pickedHeight = it.height },
                onRemoveDownload = onRemoveDownload,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { picked?.let(onDownload) },
                enabled = canDownload(picked, download),
            ) {
                Text(text = stringResource(R.string.video_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.video_dialog_cancel))
            }
        },
    )
}

/**
 * Whether *Download* has anything to do.
 *
 * Not when nothing is picked, and not when the pick is the rendition already on the phone or
 * already on its way — asking again would change nothing. A failed download at the same rendition
 * is the exception: confirming it again is how it is retried.
 *
 * @param picked the rendition picked in the dialog, if any.
 * @param download the episode's downloaded video, if any.
 * @return true when confirming would request a download.
 */
internal fun canDownload(picked: VideoQuality?, download: VideoDownload?): Boolean = when {
    picked == null -> false
    download == null -> true
    download.state == DownloadState.FAILED -> true
    else -> picked != download.quality
}

/**
 * The dialog's body, without the dialog, so a preview can hold it.
 *
 * @param qualities the renditions on offer; null while still being asked for.
 * @param failed true when they could not be asked for.
 * @param download the episode's downloaded video, if any.
 * @param picked the rendition currently picked.
 * @param onPick a rendition row was tapped.
 * @param onRemoveDownload the delete or cancel button was tapped.
 */
@Composable
private fun VideoDownloadOptions(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    picked: VideoQuality?,
    onPick: (VideoQuality) -> Unit,
    onRemoveDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.video_dialog_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            failed -> Text(
                text = stringResource(R.string.video_dialog_failed),
                color = MaterialTheme.colorScheme.error,
            )

            qualities == null -> WavyProgressLine(
                contentDescription = stringResource(R.string.video_dialog_loading),
            )

            qualities.isEmpty() -> Text(text = stringResource(R.string.video_dialog_empty))

            else -> Column(modifier = Modifier.selectableGroup()) {
                qualities.forEach { quality ->
                    QualityRow(
                        quality = quality,
                        selected = quality == picked,
                        onClick = { onPick(quality) },
                    )
                }
            }
        }
        if (download != null) {
            TextButton(onClick = onRemoveDownload) {
                Text(
                    text = stringResource(
                        if (download.isComplete) {
                            R.string.video_dialog_delete
                        } else {
                            R.string.video_dialog_cancel_download
                        },
                    ),
                )
            }
        }
    }
}

/**
 * One rendition: a radio button and its height, the whole row one target.
 *
 * @param quality the rendition.
 * @param selected whether it is the one picked.
 * @param onClick picks it.
 */
@Composable
private fun QualityRow(quality: VideoQuality, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MegaPodcastPlayerTheme.spacing.minTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        // Null: the row is the control, and a second target inside it would be read out twice.
        RadioButton(selected = selected, onClick = null)
        Text(text = formatVideoQuality(quality.height))
    }
}

/** The body with a 720p video half downloaded, in both schemes. */
@ThemePreviews
@Composable
internal fun VideoDownloadOptionsPreview() {
    MegaPodcastPlayerTheme {
        VideoDownloadOptions(
            qualities = listOf(VideoQuality(360), VideoQuality(720), VideoQuality(1080)),
            failed = false,
            download = VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, percent = 42f),
            picked = VideoQuality(720),
            onPick = {},
            onRemoveDownload = {},
        )
    }
}
