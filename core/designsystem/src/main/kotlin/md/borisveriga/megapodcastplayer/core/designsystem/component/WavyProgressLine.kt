package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme

/**
 * An indeterminate hairline for work the user did not ask for.
 *
 * Three screens run a feed refresh on entry and each showed a stock [
 * androidx.compose.material3.LinearProgressIndicator] for it. This is the same idea in the app's
 * own language — the travelling wave of [WaveScrubber] at a size that fits under an app bar — so
 * that "something is happening in the background" looks like one thing everywhere.
 *
 * Deliberately quiet: no track behind it, and the wave rests at the same colour the scrubber uses,
 * so it reads as an ornament on the bar rather than as a task with a deadline. Anything the user
 * actually asked for gets a spinner and an answer instead.
 *
 * @param modifier layout modifier.
 * @param contentDescription what TalkBack announces; the caller names the work in progress.
 */
@Composable
fun WavyProgressLine(
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.designsystem_working),
) {
    val waveColor = MegaPodcastPlayerTheme.colors.waveform
    val density = LocalDensity.current

    val phase by rememberInfiniteTransition(label = "wavyLine").animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TRAVEL_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavyLinePhase",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val centerY = size.height / 2f
        val strokeWidth = with(density) { STROKE_WIDTH.toPx() }
        val amplitude = with(density) { WAVE_AMPLITUDE.toPx() }
        val wavelength = with(density) { WAVE_LENGTH.toPx() }
        val step = with(density) { WAVE_SAMPLE_STEP.toPx() }

        val path = Path().apply {
            moveTo(0f, centerY)
            var x = 0f
            while (x <= size.width) {
                lineTo(x, centerY + amplitude * sin(x / wavelength * 2 * PI.toFloat() - phase))
                x += step
            }
        }

        drawPath(
            path = path,
            color = waveColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        // Nothing marks the ends of an indeterminate wave, so a dot at each edge keeps the line
        // from looking like it was clipped by the screen.
        drawCircle(color = waveColor, radius = strokeWidth / 2f, center = Offset(0f, centerY))
        drawCircle(
            color = waveColor,
            radius = strokeWidth / 2f,
            center = Offset(size.width, centerY),
        )
    }
}

/** How long one wavelength takes to travel; slow enough to read as calm rather than urgent. */
private const val TRAVEL_DURATION_MS = 1_400

private val LINE_HEIGHT = 6.dp
private val STROKE_WIDTH = 2.dp
private val WAVE_AMPLITUDE = 1.5.dp
private val WAVE_LENGTH = 20.dp
private val WAVE_SAMPLE_STEP = 2.dp

@Preview
@Composable
private fun WavyProgressLinePreview() {
    MegaPodcastPlayerTheme {
        WavyProgressLine()
    }
}
