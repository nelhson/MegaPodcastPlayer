package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import md.borisveriga.bpodcat.core.designsystem.R
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews
import md.borisveriga.bpodcat.core.model.DownloadState

/**
 * One control with five faces, covering every state of an episode's offline copy.
 *
 * It replaces a private copy that lived in the podcast-detail screen and a separate text label on
 * the downloads screen, which between them meant the same five states looked like two different
 * ideas depending on which screen you were on.
 *
 * The determinate ring is drawn rather than composed from a `CircularProgressIndicator` so the
 * glyph can sit inside it at a fixed size: the stock indicator wants the whole box.
 *
 * @param state the episode's current offline availability.
 * @param progressPercent download progress in `0f..100f`; only read while downloading.
 * @param onClick invoked when the control is pressed. The meaning depends on [state] — start,
 *   cancel, remove or retry — and is the caller's to interpret.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 */
@Composable
fun DownloadButton(
    state: DownloadState,
    progressPercent: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val description = when (state) {
        DownloadState.NOT_DOWNLOADED -> stringResource(R.string.designsystem_download)

        DownloadState.QUEUED -> stringResource(R.string.designsystem_download_queued)

        DownloadState.DOWNLOADING -> stringResource(
            R.string.designsystem_download_in_progress,
            progressPercent.roundToInt(),
        )

        DownloadState.COMPLETED -> stringResource(R.string.designsystem_download_remove)

        DownloadState.FAILED -> stringResource(R.string.designsystem_download_retry)
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        // The description lives on the button, not on whatever it happens to be drawing: two of
        // the five faces are canvases with no icon to hang a label on, and a control whose
        // accessible name appears and disappears with its glyph is worse than one with none.
        modifier = modifier
            .size(BPodcatTheme.spacing.minTouchTarget)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (state) {
                DownloadState.NOT_DOWNLOADED -> Icon(
                    imageVector = Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Queued has no measurable progress yet, so it gets the indeterminate morph rather
                // than a ring sitting at zero, which reads as "stuck".
                DownloadState.QUEUED -> MorphingIndicator(size = INDETERMINATE_SIZE)

                DownloadState.DOWNLOADING -> DownloadRing(progressPercent = progressPercent)

                DownloadState.COMPLETED -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = BPodcatTheme.colors.downloaded,
                )

                DownloadState.FAILED -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The determinate progress ring drawn while a download is running.
 *
 * @param progressPercent progress in `0f..100f`.
 * @param modifier layout modifier.
 */
@Composable
private fun DownloadRing(
    progressPercent: Float,
    modifier: Modifier = Modifier,
) {
    val sweep by animateFloatAsState(
        targetValue = (progressPercent / PERCENT_MAX).coerceIn(0f, 1f),
        animationSpec = Motion.smooth(),
        label = "downloadProgress",
    )
    val ringColor = BPodcatTheme.colors.downloaded
    val trackColor = BPodcatTheme.colors.waveformTrack

    Canvas(modifier = modifier.size(RING_SIZE)) {
        val stroke = Stroke(width = RING_STROKE.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(
            width = size.width - stroke.width,
            height = size.height - stroke.width,
        )
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = FULL_CIRCLE,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = ringColor,
            // Start at twelve o'clock rather than three, which is where people expect progress to
            // begin.
            startAngle = START_ANGLE,
            sweepAngle = FULL_CIRCLE * sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

private val RING_SIZE = 22.dp
private val RING_STROKE = 2.5.dp
private val INDETERMINATE_SIZE = 20.dp
private const val PERCENT_MAX = 100f
private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f

@ThemePreviews
@Composable
private fun DownloadButtonPreview() {
    BPodcatTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadState.entries.forEach { state ->
                DownloadButton(state = state, progressPercent = 62f, onClick = {})
            }
        }
    }
}
