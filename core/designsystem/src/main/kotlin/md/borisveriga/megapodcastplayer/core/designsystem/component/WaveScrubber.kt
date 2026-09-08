package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.Motion
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

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
 * @param markers positions to tick, as fractions in `0f..1f` — chapter starts, in practice. Drawn
 *   as hairlines through the rail rather than as anything tappable: they are a map of the episode,
 *   and a target three pixels wide beside a control the user is dragging would be a target nobody
 *   could hit on purpose. The chapter list on the episode sheet is where a chapter is chosen.
 */
@Composable
fun WaveScrubber(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    markers: List<Float> = emptyList(),
) {
    val hasDuration = durationMs > 0L
    // Null while the user is not dragging; a fraction in 0..1 while they are.
    var dragFraction by remember { mutableFloatStateOf(NO_DRAG) }

    val playedFraction = when {
        dragFraction >= 0f -> dragFraction
        hasDuration -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    val waveColor = MegaPodcastPlayerTheme.colors.waveform
    val trackColor = MegaPodcastPlayerTheme.colors.waveformTrack

    // A user who has asked the system to remove animations gets the rail and the thumb, with no
    // wave and nothing travelling. The control still says everything it has to — position, duration
    // and whether it can be dragged — because none of that was ever carried by the motion.
    val still = MegaPodcastPlayerTheme.reduceMotion
    val alive = playing && enabled && hasDuration

    // Amplitude, not visibility: the wave flattens into the rail rather than being swapped for it.
    val amplitude by animateFloatAsState(
        targetValue = if (alive && !still) 1f else 0f,
        animationSpec = Motion.lazy(),
        label = "waveAmplitude",
    )
    val travellingPhase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_TRAVEL_MS),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    val phase = if (still) 0f else travellingPhase

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

        // Over the rail and the wave, under the thumb: a marker the thumb is sitting on is one
        // the user is already at, and a tick drawn on top of the thumb reads as a defect in it.
        drawMarkers(
            markers = markers,
            playedWidth = playedWidth,
            centerY = centerY,
            playedColor = trackColor,
            unplayedColor = waveColor,
        )

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
 * @param remainingLabel the right-hand label, e.g. `-30:06`.
 * @param onRemainingClick makes that label tappable — the player cycles it between what is left,
 *   how long the episode is, and the time of day it will finish at. Null leaves it inert, which is
 *   what a caller with only one thing to say there wants.
 * @param remainingClickLabel names that tap for a screen reader, which cannot see that the label
 *   changed. Required in practice whenever [onRemainingClick] is set.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param markers chapter starts to tick, as fractions; see [WaveScrubber].
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
    markers: List<Float> = emptyList(),
    onRemainingClick: (() -> Unit)? = null,
    remainingClickLabel: String? = null,
) {
    Column(modifier = modifier) {
        WaveScrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            onSeek = onSeek,
            enabled = enabled,
            markers = markers,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsedLabel,
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = remainingLabel,
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = if (onRemainingClick == null) {
                    Modifier
                } else {
                    // Padded rather than sized to the touch target: a 48dp box under a timecode
                    // would push the labels apart and put the scrubber's own thumb out of reach at
                    // the right-hand end. The label is small, and it is the only thing near it.
                    Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = remainingClickLabel,
                            onClick = onRemainingClick,
                        )
                        .padding(MegaPodcastPlayerTheme.spacing.xs)
                },
            )
        }
    }
}

/**
 * Draws the chapter ticks along the rail.
 *
 * A separate step rather than an inline loop because the scrubber's own body is already at the
 * edge of what the project's complexity rule allows, and this is the one part of it that is pure
 * drawing with no state behind it.
 *
 * @param markers positions as fractions in `0f..1f`; anything outside is clamped.
 * @param playedWidth where the played portion ends, which decides each tick's colour.
 * @param centerY the rail's centre line.
 * @param playedColor the colour for a tick over the wave — the track colour, because the wave is
 *   already the accent there and an accent tick on it would vanish.
 * @param unplayedColor the colour for a tick over the flat rail, for the same reason reversed.
 */
private fun DrawScope.drawMarkers(
    markers: List<Float>,
    playedWidth: Float,
    centerY: Float,
    playedColor: Color,
    unplayedColor: Color,
) {
    if (markers.isEmpty()) return

    val markerHeight = MARKER_HEIGHT.toPx()
    val markerWidth = MARKER_WIDTH.toPx()
    markers.forEach { fraction ->
        val x = size.width * fraction.coerceIn(0f, 1f)
        drawLine(
            color = if (x <= playedWidth) playedColor else unplayedColor,
            start = Offset(x, centerY - markerHeight / 2f),
            end = Offset(x, centerY + markerHeight / 2f),
            strokeWidth = markerWidth,
            cap = StrokeCap.Butt,
        )
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
private val MARKER_HEIGHT = 14.dp
private val MARKER_WIDTH = 1.5.dp
private const val WAVE_TRAVEL_MS = 1100

@ThemePreviews
@Composable
private fun WaveScrubberPreview() {
    MegaPodcastPlayerTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            LabelledWaveScrubber(
                positionMs = 724_000L,
                durationMs = 2_530_000L,
                playing = true,
                onSeek = {},
                elapsedLabel = "12:04",
                remainingLabel = "-30:06",
                markers = listOf(0.12f, 0.34f, 0.61f, 0.88f),
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
