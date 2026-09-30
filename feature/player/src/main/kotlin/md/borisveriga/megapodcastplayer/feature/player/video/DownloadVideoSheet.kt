package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlin.math.roundToInt
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.feature.player.R

/**
 * Keeps the episode's video on the phone, at one of the qualities it can be watched at.
 *
 * The same chips as the quality picker, over the same list, because the question is the same —
 * which of the renditions this video comes in — and a download offered at a height the player
 * cannot show would be a file nothing plays. The chip marked is the rendition on the phone, or on
 * its way; tapping another replaces it.
 *
 * Below them, the way to get the space back: *Delete* once the video is on the phone, *Cancel
 * download* while it is still arriving, because a transfer has no file to delete yet. Only the
 * first asks: it throws away a finished file, and a cancelled transfer loses nothing that was
 * there.
 *
 * @param qualities the renditions on offer, lowest first; null while still being asked for.
 * @param failed true when the renditions could not be asked for at all.
 * @param download the episode's downloaded video, or null when it has none.
 * @param onDownload a rendition was tapped.
 * @param onDelete the video download is to go, finished or not.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadVideoSheet(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    onDownload: (VideoQuality) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.video_download_title),
        subtitle = downloadSubtitle(download),
    ) {
        DownloadVideoOptions(
            qualities = qualities,
            failed = failed,
            download = download,
            onDownload = onDownload,
            onDelete = { if (download?.isComplete == true) confirmingDelete = true else onDelete() },
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
 * The sheet's contents, without the sheet, so a preview can hold them.
 *
 * @param qualities the renditions on offer; null while still being asked for.
 * @param failed true when the renditions could not be asked for.
 * @param download the episode's downloaded video, if any.
 * @param onDownload a rendition was tapped.
 * @param onDelete the delete or cancel button was tapped.
 */
@Composable
internal fun DownloadVideoOptions(
    qualities: List<VideoQuality>?,
    failed: Boolean,
    download: VideoDownload?,
    onDownload: (VideoQuality) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QualityOptions(
            qualities = qualities,
            selected = download?.quality,
            failed = failed,
            onSelect = onDownload,
        )
        if (download != null) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.padding(bottom = MegaPodcastPlayerTheme.spacing.md),
            ) {
                Text(
                    text = stringResource(
                        if (download.isComplete) R.string.video_download_delete else R.string.video_download_cancel,
                    ),
                )
            }
        }
    }
}

/**
 * What the sheet says under its title: what is on the phone now, and what a tap will do about it.
 *
 * @param download the episode's downloaded video, if any.
 */
@Composable
private fun downloadSubtitle(download: VideoDownload?): String {
    if (download == null) return stringResource(R.string.video_download_description)
    val quality = stringResource(R.string.video_quality_label, download.quality.height)
    return when (download.state) {
        DownloadState.COMPLETED -> stringResource(R.string.video_download_completed, quality)

        DownloadState.DOWNLOADING ->
            stringResource(R.string.video_download_progress, quality, download.percent.roundToInt())

        DownloadState.FAILED -> stringResource(R.string.video_download_failed, quality)

        DownloadState.QUEUED, DownloadState.NOT_DOWNLOADED ->
            stringResource(R.string.video_download_queued, quality)
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
private fun DeleteVideoDialog(
    quality: VideoQuality,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = stringResource(R.string.video_quality_label, quality.height)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.video_download_delete_title, label)) },
        text = { Text(text = stringResource(R.string.video_download_delete_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.video_download_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.video_download_delete_cancel))
            }
        },
    )
}

/** The sheet's contents with a video half downloaded, in both schemes. */
@ThemePreviews
@Composable
internal fun DownloadVideoOptionsPreview() {
    MegaPodcastPlayerTheme {
        DownloadVideoOptions(
            qualities = listOf(VideoQuality(360), VideoQuality(720), VideoQuality(1080)),
            failed = false,
            download = VideoDownload(VideoQuality(720), DownloadState.DOWNLOADING, percent = 42f),
            onDownload = {},
            onDelete = {},
        )
    }
}
