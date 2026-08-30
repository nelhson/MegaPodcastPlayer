package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import md.borisveriga.bpodcat.core.designsystem.R
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews

/**
 * The scrubber: a wave that travels while playing and flattens when paused.
 *
 * This is the app's signature control. The played portion is drawn as a sine wave whose amplitude
 * animates to zero on pause, so the difference between "playing" and "paused" is legible from
 * across the room without reading the button. The unplayed portion stays a flat rail, which keeps
 * the remaining time easy to judge.
 *
 * Dragging is committed on release, not per frame. The caller's position updates on a timer while
 * playing, and a scrubber that fed every intermediate pixel back would fight those ticks — so the
 * thumb follows the finger locally and only [onSeek]s once, which is the same trick the previous
 * hand-rolled scrubber used and the one part of it worth keeping.
 *
 * @param positionMs current playback position.
 * @param durationMs total duration; a non-positive value renders an inert, empty rail.
 * @param playing whether the wave should travel.
 * @param onSeek invoked once, on release, with the requested position in milliseconds.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 */
@Composable
fun WaveScrubber(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val hasDuration = durationMs > 0L
    // Null while the user is not dragging; a fraction in 0..1 while they are.
    var dragFraction by remember { mutableFloatStateOf(NO_DRAG) }

    val playedFraction = when {
        dragFraction >= 0f -> dragFraction
        hasDuration -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    val waveColor = BPodcatTheme.colors.waveform
    val trackColor = BPodcatTheme.colors.waveformTrack

    // Amplitude, not visibility: the wave flattens into the rail rather than being swapped for it.
    val amplitude by animateFloatAsState(
        targetValue = if (playing && enabled && hasDuration) 1f else 0f,
        animationSpec = Motion.lazy(),
        label = "waveAmplitude",
    )
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_TRAVEL_MS),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val density = LocalDensity.current
    val seekDescription = stringResource(R.string.designsystem_seek)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SCRUBBER_HEIGHT)
            .semantics {
                contentDescription = seekDescription
                progressBarRangeInfo = ProgressBarRangeInfo(playedFraction, 0f..1f)
            }
            .then(
                if (enabled && hasDuration) {
                    Modifier.pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                onSeek((dragFraction * durationMs).roundToLong())
                                dragFraction = NO_DRAG
                            },
                            onDragCancel = { dragFraction = NO_DRAG },
                            onHorizontalDrag = { change, _ ->
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                        )
                    }.pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).roundToLong())
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val centerY = size.height / 2f
        val playedWidth = size.width * playedFraction
        val strokeWidth = with(density) { STROKE_WIDTH.toPx() }
        val maxAmplitude = with(density) { WAVE_AMPLITUDE.toPx() } * amplitude
        val thumbRadius = with(density) { THUMB_RADIUS.toPx() }

        // Unplayed rail, drawn first so the wave and thumb sit over it.
        if (playedWidth < size.width) {
            drawLine(
                color = trackColor,
                start = Offset(playedWidth, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        if (playedWidth > 0f) {
            val path = Path().apply {
                moveTo(0f, centerY)
                var x = 0f
                val step = with(density) { WAVE_SAMPLE_STEP.toPx() }
                val wavelength = with(density) { WAVE_LENGTH.toPx() }
                while (x <= playedWidth) {
                    val y = centerY + maxAmplitude * sin(x / wavelength * 2 * PI.toFloat() - phase)
                    lineTo(x, y)
                    x += step
                }
            }
            drawPath(
                path = path,
                color = waveColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        drawCircle(color = waveColor, radius = thumbRadius, center = Offset(playedWidth, centerY))
    }
}

/**
 * A scrubber with its elapsed and remaining timecodes underneath.
 *
 * The labels use the tabular-figure type style, so a running clock does not re-measure and shove
 * the layout sideways once a second.
 *
 * @param positionMs current playback position.
 * @param durationMs total duration.
 * @param playing whether the wave should travel.
 * @param onSeek invoked once, on release, with the requested position.
 * @param elapsedLabel formatted elapsed time, e.g. `12:04`.
 * @param remainingLabel formatted remaining time, e.g. `-30:06`.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 */
@Composable
fun LabelledWaveScrubber(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    elapsedLabel: String,
    remainingLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier) {
        WaveScrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            onSeek = onSeek,
            enabled = enabled,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BPodcatTheme.spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsedLabel,
                style = BPodcatTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = remainingLabel,
                style = BPodcatTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** Sentinel for "the user is not dragging"; a negative fraction is never a legal position. */
private const val NO_DRAG = -1f

private val SCRUBBER_HEIGHT = 28.dp
private val STROKE_WIDTH = 4.dp
private val WAVE_AMPLITUDE = 4.dp
private val WAVE_LENGTH = 18.dp
private val WAVE_SAMPLE_STEP = 1.5.dp
private val THUMB_RADIUS = 6.dp
private const val WAVE_TRAVEL_MS = 1100

@ThemePreviews
@Composable
private fun WaveScrubberPreview() {
    BPodcatTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            LabelledWaveScrubber(
                positionMs = 724_000L,
                durationMs = 2_530_000L,
                playing = true,
                onSeek = {},
                elapsedLabel = "12:04",
                remainingLabel = "-30:06",
            )
            LabelledWaveScrubber(
                positionMs = 1_800_000L,
                durationMs = 2_530_000L,
                playing = false,
                onSeek = {},
                elapsedLabel = "30:00",
                remainingLabel = "-12:10",
            )
        }
    }
}
