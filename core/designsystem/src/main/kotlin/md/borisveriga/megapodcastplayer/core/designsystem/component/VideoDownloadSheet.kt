package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.format.formatVideoQuality

/**
 * Keeps an episode's video on the phone, at one of the qualities it comes in: the one picker for
 * that, wherever it is opened from.
 *
 * There were two, and they disagreed. The show page asked in a dialog with a confirm button; the
 * video screen asked in a sheet of chips where a tap downloaded at once. The same question — which
 * of this video's renditions to keep — got two shapes, two sets of words and two ideas of whether
 * a finished file is deleted without asking. This is the one that was kept of each:
 *
 * - **Pick, then confirm**, from the dialog. A tap on a 1080p row on mobile data is a choice, not
 *   yet a download; *Download* is the action.
 * - **One status line**, under the title: what the phone holds of this video now, with a progress
 *   bar while it is arriving.
 * - **Delete asks first**, from the sheet. A finished file going is asked about, naming the quality
 *   and saying the audio stays; a transfer being called off is not, since nothing that was there
 *   is lost.
 *
 * The rendition already on the phone, or on its way, starts picked; picking another and confirming
 * replaces it. The list is the renditions the video actually comes in, because a download at a
 * height the player cannot show would be a file nothing plays.
 *
 * @param qualities the renditions on offer, lowest first; null while still being asked for.
 * @param failed true when the renditions could not be asked for.
 * @param download the episode's downloaded video, or null when it has none.
 * @param onDownload a rendition was confirmed.
 * @param onDelete the downloaded video is to go, finished or not; for a finished one, only once the
 *   question has been answered.
 * @param onDismiss the sheet was put away.
 * @param modifier layout modifier.
 * @param waitingForWifi whether downloads wait for Wi-Fi, which is then why a queued video has not
 *   started, and the status line says so as the audio's does.
 * @param folderPicker drawn above the download button, to choose which folder the download is filed
 *   under; null when the caller has its own, or there is nothing to choose.
 */
// MegaPodcastPlayerBottomSheet is built on the experimental ModalBottomSheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloadSheet(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    onDownload: (VideoQuality) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    waitingForWifi: Boolean = false,
    folderPicker: (@Composable () -> Unit)? = null,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        // Open all the way: half open, a video with six renditions put the delete button below the
        // fold, and the way to get the space back was the one thing on the sheet not shown.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.designsystem_video_download_title),
        subtitle = videoDownloadStatus(download, waitingForWifi),
    ) {
        VideoDownloadOptions(
            qualities = qualities,
            failed = failed,
            download = download,
            onDownload = onDownload,
            onDelete = { if (download?.isComplete == true) confirmingDelete = true else onDelete() },
            folderPicker = folderPicker,
        )
    }

    if (confirmingDelete && download != null) {
        DeleteVideoDialog(
            quality = download.quality,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * Whether *Download* has anything to do.
 *
 * Not when nothing is picked, and not when the pick is the rendition already on the phone or
 * already on its way — asking again would change nothing. A failed download at the same rendition
 * is the exception: confirming it again is how it is retried.
 *
 * @param picked the rendition picked, if any.
 * @param download the episode's downloaded video, if any.
 * @return true when confirming would request a download.
 */
fun canDownloadVideo(picked: VideoQuality?, download: VideoDownload?): Boolean = when {
    picked == null -> false
    download == null -> true
    download.state == DownloadState.FAILED -> true
    else -> picked != download.quality
}

/**
 * The sheet's body, without the sheet, so a test and a preview can hold it: the progress bar, the
 * renditions, and the two buttons.
 *
 * Holds the pick itself, saved as a height — `VideoQuality` is not `Parcelable`, and the height is
 * all it holds — so a rotation does not lose a choice not yet confirmed.
 *
 * @param qualities the renditions on offer; null while still being asked for.
 * @param failed true when they could not be asked for.
 * @param download the episode's downloaded video, if any.
 * @param onDownload a rendition was confirmed.
 * @param onDelete the delete or cancel button was pressed; asking first is the caller's.
 * @param folderPicker drawn above the download button; see [VideoDownloadSheet].
 */
@Composable
fun VideoDownloadOptions(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    onDownload: (VideoQuality) -> Unit,
    onDelete: () -> Unit,
    folderPicker: (@Composable () -> Unit)? = null,
) {
    var pickedHeight by rememberSaveable { mutableStateOf(download?.quality?.height) }
    val picked = pickedHeight?.let(::VideoQuality)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
            .padding(bottom = MegaPodcastPlayerTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        // The status line's bar, under the words the sheet's subtitle already says. Silent: the
        // subtitle carries the percentage, and two announcements of one number is one too many.
        if (download?.state == DownloadState.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { download.percent / FULL_PERCENT },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
        }

        when {
            failed -> Text(
                text = stringResource(R.string.designsystem_video_download_qualities_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            qualities == null -> WavyProgressLine(
                contentDescription = stringResource(R.string.designsystem_video_download_loading),
            )

            qualities.isEmpty() -> Text(
                text = stringResource(R.string.designsystem_video_download_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Column(modifier = Modifier.selectableGroup()) {
                qualities.forEach { quality ->
                    QualityRow(
                        quality = quality,
                        selected = quality == picked,
                        onClick = { pickedHeight = quality.height },
                    )
                }
            }
        }

        // Where the download goes, read just before the button that sends it there.
        folderPicker?.invoke()

        // Stacked rather than side by side: at a large font the pair no longer fits one line, and
        // the button that spends data was the one that broke mid-word.
        Button(
            onClick = { picked?.let(onDownload) },
            enabled = canDownloadVideo(picked, download),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.designsystem_video_download_confirm))
        }
        // The way to get the space back, under the button that spends it. A finished file is
        // deleted; a transfer has no file yet and is called off (COPY-2).
        if (download != null) {
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    text = stringResource(
                        if (download.isComplete) {
                            R.string.designsystem_video_download_delete
                        } else {
                            R.string.designsystem_video_download_cancel
                        },
                    ),
                )
            }
        }
    }
}

/**
 * What the sheet says under its title: what is on the phone now, and what to do about it.
 *
 * @param download the episode's downloaded video, if any.
 * @param waitingForWifi whether a queued download is waiting for Wi-Fi rather than in line.
 */
@Composable
private fun videoDownloadStatus(download: VideoDownload?, waitingForWifi: Boolean): String {
    if (download == null) return stringResource(R.string.designsystem_video_download_description)
    val quality = formatVideoQuality(download.quality.height)
    return when (download.state) {
        DownloadState.COMPLETED -> stringResource(R.string.designsystem_video_download_completed, quality)

        DownloadState.DOWNLOADING -> stringResource(
            R.string.designsystem_video_download_progress,
            quality,
            download.percent.roundToInt(),
        )

        DownloadState.FAILED -> stringResource(R.string.designsystem_video_download_failed, quality)

        DownloadState.QUEUED, DownloadState.NOT_DOWNLOADED -> stringResource(
            if (waitingForWifi) {
                R.string.designsystem_video_download_queued_wifi
            } else {
                R.string.designsystem_video_download_queued
            },
            quality,
        )
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

/**
 * Asks before a finished video is deleted, naming what goes and what stays.
 *
 * @param quality the rendition on the phone.
 * @param onConfirm the video is to be deleted.
 * @param onDismiss the question was put away.
 */
@Composable
private fun DeleteVideoDialog(quality: VideoQuality, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.designsystem_video_download_delete_title,
                    formatVideoQuality(quality.height),
                ),
            )
        },
        text = { Text(text = stringResource(R.string.designsystem_video_download_delete_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.designsystem_video_download_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.designsystem_video_download_delete_dismiss))
            }
        },
    )
}

/** What [VideoDownload.percent] is out of. */
private const val FULL_PERCENT = 100f

/**
 * The body with a 720p video part way down, in both schemes: the progress bar, the pick on the
 * rendition arriving, *Download* waiting for another, and the transfer's *Cancel download*.
 */
@ThemePreviews
@Composable
internal fun VideoDownloadOptionsPreview() {
    MegaPodcastPlayerTheme {
        VideoDownloadOptions(
            qualities = listOf(VideoQuality(360), VideoQuality(720), VideoQuality(1080)),
            failed = false,
            download = VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, percent = 42f),
            onDownload = {},
            onDelete = {},
        )
    }
}
