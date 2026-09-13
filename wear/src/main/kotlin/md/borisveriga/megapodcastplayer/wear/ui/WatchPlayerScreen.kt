package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode
import md.borisveriga.megapodcastplayer.wear.R
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink

/**
 * The watch's remote control, wired to its view model.
 *
 * @param viewModel supplies the phone's state and turns taps into commands.
 */
@Composable
fun WatchPlayerScreen(viewModel: WatchPlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Handed down as a lambda rather than read here: the read happens wherever the lambda is
    // called, and it is called in exactly one place — the time label — so the clock ticking
    // recomposes that label and not the list this function hosts. The lambda captures nothing
    // but the state object, so it is the same lambda from one recomposition to the next.
    val position by viewModel.position.collectAsStateWithLifecycle()

    WatchPlayerScreen(
        uiState = uiState,
        position = { position },
        onTogglePlayPause = viewModel::togglePlayPause,
        onSkipForward = viewModel::skipForward,
        onSkipBack = viewModel::skipBack,
        onSkipToNext = viewModel::skipToNext,
        onSkipToPrevious = viewModel::skipToPrevious,
        onCycleSpeed = viewModel::cycleSpeed,
        onMarkMoment = viewModel::markMoment,
        onPlayOnPhone = viewModel::playOnPhone,
        onRetry = viewModel::retry,
        onBeginScrub = viewModel::beginScrub,
        onScrubBy = viewModel::scrubBy,
        onCommitScrub = viewModel::commitScrub,
    )
}

/**
 * The watch's remote control.
 *
 * Stateless so it can be previewed and screenshot-tested without a phone at the other end.
 *
 * One column, top to bottom: what is playing, its scrubber, the transport, the sitting-down
 * controls, the moment button, and then the phone's queue. The transport is the one thing here that
 * has to be under the thumb within a second of raising the wrist, and it is: the queue is the only
 * part whose length the phone decides, and it is the last thing in the column, so however long it
 * grows nothing above it moves. The screen was once split into two pages to keep a growing list
 * from pushing pause off the bottom; putting the list last does the same with nothing to swipe.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onMarkMoment invoked by the mark-a-moment button.
 * @param onPlayOnPhone invoked with the episode id when a queued episode is tapped.
 * @param onRetry invoked when the user retries a failed connection.
 * @param position where playback has reached, read only by the bar and only when it draws. A
 *   lambda rather than a value so that the clock, which moves this once a second, is not a reason
 *   for the list to recompose; see [WatchPlayerUiState] for why that matters.
 * @param onBeginScrub invoked when the user takes hold of the progress bar.
 * @param onScrubBy invoked as they move it, with a signed offset in milliseconds.
 * @param onCommitScrub invoked when they settle, which is what actually seeks.
 */
@Composable
fun WatchPlayerScreen(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onPlayOnPhone: (String) -> Unit,
    onRetry: () -> Unit,
    position: () -> PlaybackPosition = { PlaybackPosition() },
    onMarkMoment: () -> Unit = {},
    onBeginScrub: () -> Unit = {},
    onScrubBy: (Long) -> Unit = {},
    onCommitScrub: () -> Unit = {},
) {
    // A phone we cannot reach makes every control below meaningless, so the same fact replaces
    // the screen and says what to do about it.
    if (uiState.showsLinkProblem) {
        LinkProblemScreen(link = uiState.link, onRetry = onRetry)
        return
    }

    val listState = rememberScalingLazyListState()

    // A scrolling list rather than a fixed layout even for the controls alone, because at 200 %
    // font scale five items do not fit a round screen and the alternative to scrolling is clipping.
    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (uiState.lastCommandFailed) {
                item { CommandFailedNote() }
            }

            if (uiState.showsControls) {
                item { NowPlayingHeader(uiState) }
                item {
                    ProgressRow(
                        uiState = uiState,
                        position = position,
                        onBeginScrub = onBeginScrub,
                        onScrubBy = onScrubBy,
                        onCommitScrub = onCommitScrub,
                    )
                }
                item {
                    TransportRow(
                        uiState = uiState,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                    )
                }
                item {
                    SecondaryRow(
                        uiState = uiState,
                        onSkipToPrevious = onSkipToPrevious,
                        onSkipToNext = onSkipToNext,
                        onCycleSpeed = onCycleSpeed,
                    )
                }
                item { MarkMomentRow(saved = uiState.momentSaved, onClick = onMarkMoment) }
            } else {
                // Nothing to control means no controls: the sentence explaining the missing
                // transport sits above the episodes it tells the wearer to pick from.
                item { NothingPlaying(hasQueue = uiState.snapshot.upNext.isNotEmpty()) }
            }

            phoneQueue(uiState = uiState, onPlayOnPhone = onPlayOnPhone)
        }
    }
}

/**
 * The phone's queue, at the bottom of the column.
 *
 * The header names the list rather than the action its rows perform: on a screen this small the
 * header is the only thing that says *whose* episodes these are. Every row is keyed, so that a
 * queue that changes moves the rows around the change rather than rebuilding them.
 *
 * @param uiState what to draw.
 * @param onPlayOnPhone invoked with the episode id when a queued episode is tapped.
 */
private fun ScalingLazyListScope.phoneQueue(
    uiState: WatchPlayerUiState,
    onPlayOnPhone: (String) -> Unit,
) {
    if (uiState.snapshot.upNext.isEmpty()) return

    item { ListHeader { Text(text = stringResource(R.string.watch_phone_queue)) } }
    items(uiState.snapshot.upNext, key = { "queue:${it.id}" }) { episode ->
        QueueRow(episode = episode, onClick = { onPlayOnPhone(episode.id) })
    }
}

/**
 * Title, show and a waveform that moves while the phone is playing.
 *
 * There is no cover art here on purpose. What the art was really doing was answering "which show is
 * this" before the words were read — and it answered badly, because arbitrary third-party imagery
 * behind a title needs a scrim heavy enough that little of the picture survives it. A colour answers
 * the same question at the same glance and costs nothing to send; see [showAccent].
 *
 * The waveform answers the other glance-level question, "is it actually playing", by moving only
 * when it is. That reaches the eye before the transport button's glyph does.
 */
@Composable
private fun NowPlayingHeader(uiState: WatchPlayerUiState) {
    val accent = showAccent(uiState.snapshot.showTitle)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // Fading out at the bottom rather than ending on an edge: the progress bar sits directly
            // below, and a hard band across a round screen would cut the layout in half.
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = WASH_TOP_ALPHA),
                        accent.copy(alpha = WASH_FADE_ALPHA),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Waveform(accent = accent, moving = uiState.snapshot.isPlaying)

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = uiState.snapshot.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        if (uiState.snapshot.showTitle.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShowDot(accent = accent)
                Text(
                    text = uiState.snapshot.showTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Bars in the show's colour, rising and falling while the phone plays and still when it does not.
 *
 * One animation drives all of them: each bar reads the same travelling phase a little later than its
 * neighbour, which is what makes the shape move along the row instead of pulsing in unison. Seven
 * separate animations would look much the same and cost seven times as much on a wrist.
 *
 * @param accent the show's colour, from [showAccent].
 * @param moving whether the phone is playing; when it is not, the bars sit at [WAVEFORM_REST].
 * @param modifier applied to the band the bars are drawn in.
 */
@Composable
private fun Waveform(accent: Color, moving: Boolean, modifier: Modifier = Modifier) {
    // Kept as State and unwrapped inside the draw lambda below, not here: a value read during
    // composition would recompose this function on every animation frame, where a draw-phase read
    // only repaints. On a watch that difference is battery.
    // A wearer who has turned animations off gets the bars at rest. Whether the phone is playing is
    // said by the transport button, which is where it always was; the waveform only ever repeated it.
    val animate = moving && !rememberReduceMotion()

    val phase: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "waveform").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // Linear and restarting rather than reversing: the wave travels one way along the bars,
            // and a reversing sweep would visibly walk back the way it came.
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing),
            ),
            label = "waveform-phase",
        )
    } else {
        null
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT)
            // A layer of its own, so that redrawing the bars every animation frame redraws the
            // bars and not the list they sit in — which, mid-scroll, is already being redrawn
            // for reasons of its own.
            .graphicsLayer()
            // Decorative: whether the phone is playing is already spoken by the transport button,
            // and a waveform TalkBack stopped on would only be one more thing to swipe past.
            .clearAndSetSemantics { },
    ) {
        val barWidth = WAVEFORM_BAR_WIDTH.toPx()
        val gap = WAVEFORM_BAR_GAP.toPx()
        val span = WAVEFORM_BARS * barWidth + (WAVEFORM_BARS - 1) * gap
        val centreBar = (WAVEFORM_BARS - 1) * HALF

        repeat(WAVEFORM_BARS) { index ->
            // Tallest in the middle, tapering outwards: a row of equally tall bars reads as a chart;
            // this reads as a sound.
            val reach = 1f - WAVEFORM_TAPER * (abs(index - centreBar) / centreBar)
            val level = phase?.let {
                val angle = (it.value + index * WAVEFORM_BAR_PHASE) * TWO_PI
                WAVEFORM_REST + (1f - WAVEFORM_REST) * reach * (HALF + HALF * sin(angle))
            } ?: WAVEFORM_REST
            val barHeight = size.height * level

            drawRoundRect(
                color = accent,
                topLeft = Offset(
                    x = (size.width - span) * HALF + index * (barWidth + gap),
                    y = (size.height - barHeight) * HALF,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * HALF),
            )
        }
    }
}

/**
 * The show's colour as a dot, so a show line reads as an identity rather than as a subtitle.
 *
 * @param accent the show's colour, from [showAccent].
 * @param modifier applied to the dot.
 */
@Composable
private fun ShowDot(accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SHOW_DOT_SIZE)
            .clip(CircleShape)
            .background(accent),
    )
}

/**
 * The scrubber and the two times around it.
 *
 * A linear indicator rather than the round one: this list scrolls, and a progress ring pinned to the
 * bezel would keep sliding away from the episode it describes.
 *
 * Scrubbing is an explicit mode, entered by tapping the bar. The alternative — a bar that is always
 * draggable — would fight the list it sits in, because the same horizontal-ish gesture also scrolls,
 * and rotary input has only one focus owner. Tapping first makes the choice unambiguous: while
 * scrubbing, the bezel moves the position; otherwise it scrolls the list, as everywhere else.
 *
 * The position is read here through a lambda and never as a value, and only inside things that
 * run outside composition — the indicator's own progress lambda, the thumb's offset, the
 * snapshot flow below — or inside [PositionLabel], which is the one composable built to be
 * recomposed once a second. The row itself, with its gesture modifiers, is not.
 *
 * @param uiState what to draw.
 * @param position where playback has reached, or the scrub preview while scrubbing.
 * @param onBeginScrub takes hold of the bar.
 * @param onScrubBy moves it by a signed offset in milliseconds.
 * @param onCommitScrub seeks to where it was left.
 */
@Composable
private fun ProgressRow(
    uiState: WatchPlayerUiState,
    position: () -> PlaybackPosition,
    onBeginScrub: () -> Unit,
    onScrubBy: (Long) -> Unit,
    onCommitScrub: () -> Unit,
) {
    val durationMs = uiState.snapshot.knownDurationMs
    var barWidthPx by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    // Dragging the full width of the bar covers the whole episode, which is the scale the bar itself
    // suggests. Rotary uses the same scale, so the two gestures agree.
    val msPerPixel: Float = if (durationMs != null && barWidthPx > 0) {
        durationMs.toFloat() / barWidthPx
    } else {
        0f
    }

    LaunchedEffect(uiState.isScrubbing) {
        if (!uiState.isScrubbing) return@LaunchedEffect

        // Rotary events go to whatever holds focus, so the bar has to claim it on entering scrub
        // mode and give it back on leaving, or the list would keep consuming the bezel.
        focusRequester.requestFocus()

        // Committing on a pause rather than on release: rotary has no "release", and a bezel turn
        // arrives as a burst of events, so each movement restarts the wait. The first value is
        // where the bar stood when it was grabbed, and is dropped: until the position changes the
        // user has only tapped into scrub mode without moving anything, and committing then would
        // seek to where playback already is and drop them straight back out of the mode they just
        // deliberately entered. Watched as a snapshot flow rather than read in composition, so
        // that the position moving does not recompose the row.
        snapshotFlow { position().positionMs }
            .drop(1)
            .collectLatest {
                delay(SCRUB_COMMIT_DELAY_MS)
                onCommitScrub()
            }
    }

    val scrubLabel = stringResource(
        if (uiState.isScrubbing) R.string.watch_scrub_active else R.string.watch_scrub,
    )

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // The bar and its thumb share one box so the thumb can be placed by the same fraction the
        // bar fills, rather than by a second copy of the arithmetic.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (uiState.isScrubbing) SCRUB_BAR_HEIGHT else PROGRESS_BAR_HEIGHT)
                .onSizeChanged { barWidthPx = it.width }
                .semantics { contentDescription = scrubLabel }
                .then(
                    if (uiState.canScrub) {
                        Modifier
                            .clickable { if (uiState.isScrubbing) onCommitScrub() else onBeginScrub() }
                            .focusRequester(focusRequester)
                            .focusable()
                            .onRotaryScrollEvent { event ->
                                if (!uiState.isScrubbing) return@onRotaryScrollEvent false
                                onScrubBy((event.verticalScrollPixels * msPerPixel).toLong())
                                true
                            }
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    onScrubBy((delta * msPerPixel).toLong())
                                },
                                orientation = Orientation.Horizontal,
                                onDragStarted = { onBeginScrub() },
                                onDragStopped = { onCommitScrub() },
                            )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            LinearProgressIndicator(
                progress = { position().progress },
                modifier = Modifier.fillMaxWidth().height(
                    if (uiState.isScrubbing) SCRUB_BAR_HEIGHT else PROGRESS_BAR_HEIGHT,
                ),
            )
            if (uiState.isScrubbing) {
                ScrubThumb(progress = { position().progress }, trackWidthPx = barWidthPx)
            }
        }
        if (uiState.showsScrubHint) {
            Text(
                text = stringResource(R.string.watch_scrub_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PositionLabel(position = position, isScrubbing = uiState.isScrubbing)
            Text(
                // Nothing is shown rather than "0:00" while the phone has not read the duration:
                // a zero-length episode is a claim, an empty label is just an absence.
                text = uiState.snapshot.knownDurationMs?.let(::formatPlaybackTime).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The elapsed time, and the one composable on the screen that reads the clock in composition.
 *
 * Kept to a single `Text` on purpose: the position changes once a second, and whatever reads it
 * is recomposed once a second. Reading it here rather than in [ProgressRow] means the row's
 * gesture modifiers, focus handling and bar are built once and left alone while the seconds go
 * by.
 *
 * @param position where playback has reached.
 * @param isScrubbing true while the label is showing the scrub preview, which colours it as the
 *   thing being adjusted.
 */
@Composable
private fun PositionLabel(position: () -> PlaybackPosition, isScrubbing: Boolean) {
    Text(
        text = formatPlaybackTime(position().positionMs),
        style = MaterialTheme.typography.labelSmall,
        color = if (isScrubbing) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * The grip on the bar, drawn only while the bar is being held.
 *
 * The whole of what makes scrub mode visible. Before this, the only difference between reading the
 * position and moving it was that the bar got taller, which nobody reads as *this is now a
 * control*; a thumb is the shape every slider ever made has used to say so.
 *
 * Placed by offsetting it from the start of the track rather than by weighting two spacers, so
 * that a progress of zero and a progress of one both leave it inside the bar rather than half off
 * the end of it. The offset is worked out in the layout pass, from a lambda: the thumb follows a
 * bezel turn by being placed again, not by being composed again.
 *
 * @param progress how far along the track it sits, from zero to one.
 * @param trackWidthPx the measured width of the bar; zero before the first layout pass, which puts
 *   the thumb at the start for one frame rather than not drawing it at all.
 */
@Composable
private fun ScrubThumb(progress: () -> Float, trackWidthPx: Int) {
    val thumbPx = with(LocalDensity.current) { SCRUB_THUMB_SIZE.roundToPx() }
    val travelPx = (trackWidthPx - thumbPx).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .offset { IntOffset(x = (travelPx * progress().coerceIn(0f, 1f)).roundToInt(), y = 0) }
            .size(SCRUB_THUMB_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** Skip back, play/pause, skip forward — the three buttons that get used while walking. */
@Composable
private fun TransportRow(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
) {
    // Resolved here rather than inside the semantics lambda below, which is not composable.
    val bufferingLabel = stringResource(R.string.watch_buffering)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onSkipBack) {
            Icon(
                imageVector = skipBackIcon(uiState.snapshot.skipBackMs),
                contentDescription = skipContentDescription(uiState.snapshot.skipBackMs, forward = false),
            )
        }

        Box(contentAlignment = Alignment.Center) {
            // A ring around the button rather than a changed glyph: buffering is a state playback is
            // *in*, not a third thing the button could do, and the button must stay pressable.
            if (uiState.snapshot.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(BUFFERING_RING_SIZE)
                        .semantics { contentDescription = bufferingLabel },
                )
            }

            FilledIconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(PLAY_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (uiState.snapshot.isPlaying) {
                        Icons.Rounded.Pause
                    } else {
                        Icons.Rounded.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (uiState.snapshot.isPlaying) R.string.watch_pause else R.string.watch_play,
                    ),
                )
            }
        }

        FilledTonalIconButton(onClick = onSkipForward) {
            Icon(
                imageVector = skipForwardIcon(uiState.snapshot.skipForwardMs),
                contentDescription = skipContentDescription(uiState.snapshot.skipForwardMs, forward = true),
            )
        }
    }
}

/**
 * The controls used while sitting down: previous and next through the phone's queue, with the
 * speed between them.
 *
 * @param uiState what is playing.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onCycleSpeed invoked by the speed button.
 */
@Composable
private fun SecondaryRow(
    uiState: WatchPlayerUiState,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipToPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.watch_previous_episode),
            )
        }

        TextButton(onClick = onCycleSpeed) {
            Text(text = formatSpeed(uiState.snapshot.speed))
        }

        IconButton(onClick = onSkipToNext, enabled = uiState.snapshot.hasNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.watch_next_episode),
            )
        }
    }
}

/**
 * The button that keeps the spot the wearer is listening to.
 *
 * Full width and on a row of its own, rather than a fourth glyph squeezed into [SecondaryRow]. This
 * is the one control here that is pressed *without looking* — mid-run, mid-walk, through a sleeve —
 * so it is given the largest target on the screen after play/pause, and the others keep theirs.
 *
 * The label is the confirmation. A watch has no snackbar and marking leaves nothing behind, so the
 * button says "Saved" for a few seconds; without that the wearer presses again to check, which is
 * why the phone folds two marks a few seconds apart into one.
 *
 * @param saved true while the confirmation is showing.
 * @param onClick marks a moment at the playhead.
 */
@Composable
private fun MarkMomentRow(saved: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Keyed on the confirmation rather than fired from the click, because the two are not the same
    // event: a mark that could neither be delivered nor queued sets nothing, and a wrist that
    // buzzed anyway would have said the moment was kept when it was not.
    LaunchedEffect(saved) {
        if (saved) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = {
            Icon(
                imageVector = if (saved) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                contentDescription = null,
            )
        },
        label = {
            Text(
                text = stringResource(
                    if (saved) R.string.watch_moment_saved else R.string.watch_moment_mark,
                ),
            )
        },
    )
}

/**
 * One "up next" row; tapping it asks the phone to play that episode.
 *
 * Carries the show's colour as a dot, the same one the header uses, so a queue holding three shows
 * can be told apart without reading it.
 */
@Composable
private fun QueueRow(episode: QueuedEpisode, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { ShowDot(accent = showAccent(episode.showTitle)) },
        label = { Text(text = episode.title, maxLines = 2) },
        secondaryLabel = { Text(text = episode.showTitle, maxLines = 1) },
    )
}

/** Shown when the phone is reachable but has nothing loaded. */
@Composable
private fun NothingPlaying(hasQueue: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.watch_nothing_playing_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (hasQueue) {
                    R.string.watch_nothing_playing_with_queue
                } else {
                    R.string.watch_nothing_playing_empty
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shown after a command that could not be delivered. */
@Composable
private fun CommandFailedNote() {
    Text(
        text = stringResource(R.string.watch_command_failed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * The whole screen when there is no phone to control.
 *
 * @param link which flavour of unreachable; each gets the sentence that names what to do about it.
 * @param onRetry invoked by the retry button.
 */
@Composable
private fun LinkProblemScreen(link: PhoneLink, onRetry: () -> Unit) {
    ScreenScaffold {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(
                        when (link) {
                            PhoneLink.CHECKING -> R.string.watch_link_checking_title
                            PhoneLink.APP_NOT_INSTALLED -> R.string.watch_link_app_missing_title
                            else -> R.string.watch_link_disconnected_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(
                        when (link) {
                            PhoneLink.CHECKING -> R.string.watch_link_checking_description

                            PhoneLink.APP_NOT_INSTALLED ->
                                R.string.watch_link_app_missing_description

                            else -> R.string.watch_link_disconnected_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (link != PhoneLink.CHECKING) {
                    TextButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.watch_retry))
                    }
                }
            }
        }
    }
}

/**
 * Picks the skip-ahead glyph matching the interval configured on the phone.
 *
 * Material only ships numbered icons for 5, 10 and 30 seconds. Showing "30" on a button that jumps
 * 45 is a small lie the user notices the first time they press it, so anything else falls back to
 * the unnumbered glyph. Deliberately the same rule as the phone's player, so the two agree.
 *
 * @param skipMs the configured distance.
 */
private fun skipForwardIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Forward5
    10_000L -> Icons.Rounded.Forward10
    30_000L -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/**
 * Picks the skip-back glyph matching the interval configured on the phone; see [skipForwardIcon].
 *
 * @param skipMs the configured distance.
 */
private fun skipBackIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Replay5
    10_000L -> Icons.Rounded.Replay10
    30_000L -> Icons.Rounded.Replay30
    else -> Icons.Rounded.FastRewind
}

/**
 * Describes a skip button for TalkBack.
 *
 * The glyph carries the number visually; the description has to say it out loud.
 *
 * @param skipMs the configured distance.
 * @param forward true for the skip-ahead button.
 * @return the spoken label, pluralised on the number of seconds.
 */
@Composable
private fun skipContentDescription(skipMs: Long, forward: Boolean): String {
    val seconds = (skipMs / 1_000L).coerceAtLeast(1L).toInt()
    return pluralStringResource(
        id = if (forward) R.plurals.watch_skip_forward else R.plurals.watch_skip_back,
        count = seconds,
        seconds,
    )
}

/** The play button is deliberately larger than its neighbours: it is the one pressed blind. */
private val PLAY_BUTTON_SIZE = 60.dp

/** Sized to clear the play button so the ring reads as around it rather than on it. */
private val BUFFERING_RING_SIZE = 72.dp

/** The bar at rest: thin, because it is only being read. */
private val PROGRESS_BAR_HEIGHT = 6.dp

/** The bar while scrubbing: thick enough to be a target for a fingertip. */
private val SCRUB_BAR_HEIGHT = 14.dp

/** The thumb on that bar. As tall as the bar, so it reads as a grip on it and not a dot above it. */
private val SCRUB_THUMB_SIZE = 14.dp

/** The band the waveform is drawn in. Sized to be read, not to compete with the title under it. */
private val WAVEFORM_HEIGHT = 18.dp

/** One waveform bar, and the gap to the next. Equal, which is what makes the row read as a comb. */
private val WAVEFORM_BAR_WIDTH = 4.dp
private val WAVEFORM_BAR_GAP = 4.dp

/** Bars in the waveform. Odd, so one sits in the middle and the taper is symmetric about it. */
private const val WAVEFORM_BARS = 7

/** How much shorter the outermost bar reaches than the middle one. */
private const val WAVEFORM_TAPER = 0.5f

/** The height the bars keep when nothing is playing: still a waveform, but plainly a stopped one. */
private const val WAVEFORM_REST = 0.16f

/** How far along the wave each next bar sits, in turns. This is the whole travelling effect. */
private const val WAVEFORM_BAR_PHASE = 0.14f

/** One trip of the wave across the bars. Slow enough to read as breathing rather than flickering. */
private const val WAVE_PERIOD_MS = 1_400

/** Half: centres the bars, and folds sine's -1..1 down onto 0..1. */
private const val HALF = 0.5f

/** One turn, in radians, for the sine above. */
private val TWO_PI = (PI * 2).toFloat()

/** The show's colour behind the header: its strength at the top, and where it fades out. */
private const val WASH_TOP_ALPHA = 0.30f
private const val WASH_FADE_ALPHA = 0.10f

/** The show's colour as a dot beside a show name. */
private val SHOW_DOT_SIZE = 6.dp

/**
 * How long the scrub position must hold still before it is sent.
 *
 * Long enough to span the gap between two deliberate bezel detents, short enough that letting go
 * feels like it seeked immediately.
 */
private const val SCRUB_COMMIT_DELAY_MS = 600L
