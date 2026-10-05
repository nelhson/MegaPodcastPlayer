package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload

/**
 * The question asked before an episode's download is deleted, wherever the delete starts.
 *
 * Deleting the audio takes the episode's video with it — a picture without its sound does not play
 * offline — and three of the four places that delete used to say nothing about the video at all: a
 * swipe labelled *Delete download* could take a gigabyte of picture with the forty megabytes of
 * sound it named. So the question names both when both go, and says how much space comes back,
 * which is usually why it is being asked.
 *
 * One component, so the show page, Downloads and the player ask in the same words.
 *
 * @param episodeTitle the episode, named so a mis-swipe is caught here rather than after.
 * @param withVideo true when the episode's video goes too.
 * @param freed what deleting gives back, already formatted (`formatBytes`); null when it is not
 *   known, and the sentence then leaves the size out rather than say nothing is freed.
 * @param onConfirm the download is to go.
 * @param onDismiss the question was put away.
 */
@Composable
fun DeleteDownloadDialog(
    episodeTitle: String,
    withVideo: Boolean,
    freed: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (withVideo) {
                        R.string.designsystem_delete_download_title_with_video
                    } else {
                        R.string.designsystem_delete_download_title
                    },
                    episodeTitle,
                ),
            )
        },
        text = {
            Text(
                text = if (freed != null) {
                    stringResource(R.string.designsystem_delete_download_text_freeing, freed)
                } else {
                    stringResource(R.string.designsystem_delete_download_text)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.designsystem_delete_download_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.designsystem_delete_download_dismiss))
            }
        },
    )
}

/**
 * Whether removing an episode's audio download also deletes a video, and so must ask first.
 *
 * Any video at all, finished or still arriving: the removal takes either, and a question that came
 * only once the picture had finished would leave the one person most likely to be surprised — the
 * one who started a large download a minute ago — unasked.
 *
 * @param audio the episode's audio download state.
 * @param video the episode's video download, if any.
 * @return true when the tap means a removal and a video goes with it.
 */
fun removalTakesVideo(audio: DownloadState, video: VideoDownload?): Boolean =
    video != null && audio != DownloadState.NOT_DOWNLOADED && audio != DownloadState.FAILED

/** The question for an episode with a video, in both schemes; previewed, not captured (a dialog). */
@ThemePreviews
@Composable
internal fun DeleteDownloadDialogPreview() {
    MegaPodcastPlayerTheme {
        DeleteDownloadDialog(
            episodeTitle = "Did a 50 year old military secret just solve agent prompt injection?",
            withVideo = true,
            freed = "412 MB",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
