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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhonelinkErase
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
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
import kotlin.math.sin
import kotlinx.coroutines.delay
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode
import md.borisveriga.megapodcastplayer.wear.R
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode

/**
 * The watch's remote control, wired to its view model.
 *
 * @param viewModel supplies the phone's state and turns taps into commands.
 */
@Composable
fun WatchPlayerScreen(viewModel: WatchPlayerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WatchPlayerScreen(
        uiState = uiState,
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
        onPlayOnWatch = viewModel::playOnWatch,
        onCopyToWatch = viewModel::copyToWatch,
        onCancelCopyToWatch = viewModel::cancelCopyToWatch,
        onRemoveFromWatch = viewModel::removeFromWatch,
        onRemoveAllFromWatch = viewModel::removeAllFromWatch,
        onBackToPhone = viewModel::backToPhone,
    )
}

/**
 * The watch's remote control.
 *
 * Stateless so it can be previewed and screenshot-tested without a phone at the other end.
 *
 * Two pages side by side rather than one long column: *now playing*, and the episodes to choose
 * from. Everything used to live in a single list, and that list grew — the phone's queue, what the
 * phone has downloaded, what this watch holds — until pause was somewhere below the bottom of a
 * 45 mm screen. The transport is the one thing here that has to be under the thumb within a second
 * of raising the wrist, so it gets a page whose length nothing can change. Paging is what Wear does
 * about exactly this, and it is not navigation: a swipe, with two dots to say the second page is
 * there, rather than a route and a back stack to get wrong.
 *
 * The pager appears only when there is something to control. With nothing playing there is no
 * now-playing page worth swiping to, so the screen collapses back to the one list it always was,
 * and the sentence explaining the missing transport sits above the episodes it tells you to pick
 * from.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onMarkMoment invoked by the mark-a-moment button.
 * @param onPlayOnPhone invoked with the episode id when a row describing the phone is tapped,
 *   whether it is queued there or merely downloaded there.
 * @param onRetry invoked when the user retries a failed connection.
 * @param onBeginScrub invoked when the user takes hold of the progress bar.
 * @param onScrubBy invoked as they move it, with a signed offset in milliseconds.
 * @param onCommitScrub invoked when they settle, which is what actually seeks.
 * @param onPlayOnWatch invoked with a stored episode to play it on the watch itself.
 * @param onCopyToWatch invoked with an episode id to ask the phone to send its audio over.
 * @param onCancelCopyToWatch invoked with an episode id to abandon a copy that is arriving.
 * @param onRemoveFromWatch invoked with an episode id to delete it from the watch.
 * @param onRemoveAllFromWatch invoked to delete everything the watch holds.
 * @param onBackToPhone invoked to stop local playback and go back to controlling the phone.
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
    onMarkMoment: () -> Unit = {},
    onBeginScrub: () -> Unit = {},
    onScrubBy: (Long) -> Unit = {},
    onCommitScrub: () -> Unit = {},
    onPlayOnWatch: (StoredEpisode) -> Unit = {},
    onCopyToWatch: (String) -> Unit = {},
    onCancelCopyToWatch: (String) -> Unit = {},
    onRemoveFromWatch: (String) -> Unit = {},
    onRemoveAllFromWatch: () -> Unit = {},
    onBackToPhone: () -> Unit = {},
) {
    // A phone we cannot reach makes every control below meaningless — unless the watch has episodes
    // of its own, which is exactly the case they exist for. So this replaces the screen only when
    // there is nothing else to show; otherwise the same fact becomes one line above the list.
    if (uiState.showsLinkProblem) {
        LinkProblemScreen(link = uiState.link, onRetry = onRetry)
        return
    }

    val episodesPage: @Composable () -> Unit = {
        EpisodesPage(
            uiState = uiState,
            onPlayOnPhone = onPlayOnPhone,
            onPlayOnWatch = onPlayOnWatch,
            onCopyToWatch = onCopyToWatch,
            onCancelCopyToWatch = onCancelCopyToWatch,
            onRemoveFromWatch = onRemoveFromWatch,
            onRemoveAllFromWatch = onRemoveAllFromWatch,
        )
    }

    // Nothing to control means nothing to put on a first page, and a pager whose first page is an
    // apology is worse than no pager: the dots would promise a screen not worth the swipe.
    if (!uiState.showsControls) {
        episodesPage()
        return
    }

    val pagerState = rememberPagerState { PAGE_COUNT }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(
            state = pagerState,
            // Scrubbing is a horizontal drag on a bar inside a horizontally paged screen. The bar
            // wins the gesture by being the inner one, but a drag that begins a few pixels off it
            // would turn the page instead of moving the playhead — so while the bar is held there
            // are no pages to turn.
            userScrollEnabled = !uiState.isScrubbing,
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                if (page == PAGE_NOW_PLAYING) {
                    NowPlayingPage(
                        uiState = uiState,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                        onSkipToNext = onSkipToNext,
                        onSkipToPrevious = onSkipToPrevious,
                        onCycleSpeed = onCycleSpeed,
                        onMarkMoment = onMarkMoment,
                        onBeginScrub = onBeginScrub,
                        onScrubBy = onScrubBy,
                        onCommitScrub = onCommitScrub,
                        onBackToPhone = onBackToPhone,
                    )
                } else {
                    episodesPage()
                }
            }
        }
    }
}

/**
 * The page that is only ever as long as the controls on it.
 *
 * Header, scrubber, transport, the controls used sitting down, and the moment button — five items,
 * however many episodes the watch is carrying. That fixed length is the whole point of the page:
 * before it, the same five sat on top of three growing lists, and the last two of them were a
 * scroll away on a watch holding a walk's worth of audio.
 *
 * Still a scrolling list rather than a fixed layout, because at 200 % font scale five items do not
 * fit a round screen and the alternative to scrolling is clipping.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onMarkMoment invoked by the mark-a-moment button.
 * @param onBeginScrub invoked when the user takes hold of the progress bar.
 * @param onScrubBy invoked as they move it, with a signed offset in milliseconds.
 * @param onCommitScrub invoked when they settle, which is what actually seeks.
 * @param onBackToPhone invoked to stop local playback and go back to controlling the phone.
 */
@Composable
private fun NowPlayingPage(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onMarkMoment: () -> Unit,
    onBeginScrub: () -> Unit,
    onScrubBy: (Long) -> Unit,
    onCommitScrub: () -> Unit,
    onBackToPhone: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            linkNotes(uiState)

            item { NowPlayingHeader(uiState) }
            item {
                ProgressRow(
                    uiState = uiState,
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
                    onBackToPhone = onBackToPhone,
                )
            }
            item { MarkMomentRow(saved = uiState.momentSaved, onClick = onMarkMoment) }
        }
    }
}

/**
 * The page that holds every episode the watch can offer, wherever it lives.
 *
 * Two lists describing the phone, then one describing the watch. Each header names the list rather
 * than the action its rows perform: on a screen this small the header is the only thing that says
 * *whose* episodes these are, and "up next" and "downloaded" are both true of the phone at once.
 *
 * This is also the whole screen when nothing is playing — see [WatchPlayerScreen] — which is why the
 * sentence about an idle phone is at the top of it rather than on a page of its own.
 *
 * @param uiState what to draw.
 * @param onPlayOnPhone invoked with the episode id when a row describing the phone is tapped.
 * @param onPlayOnWatch invoked with a stored episode to play it on the watch itself.
 * @param onCopyToWatch invoked with an episode id to ask the phone to send its audio over.
 * @param onCancelCopyToWatch invoked with an episode id to abandon a copy that is arriving.
 * @param onRemoveFromWatch invoked with an episode id to delete it from the watch.
 * @param onRemoveAllFromWatch invoked to delete everything the watch holds.
 */
@Composable
private fun EpisodesPage(
    uiState: WatchPlayerUiState,
    onPlayOnPhone: (String) -> Unit,
    onPlayOnWatch: (StoredEpisode) -> Unit,
    onCopyToWatch: (String) -> Unit,
    onCancelCopyToWatch: (String) -> Unit,
    onRemoveFromWatch: (String) -> Unit,
    onRemoveAllFromWatch: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            linkNotes(uiState)

            if (!uiState.showsControls) {
                item { NothingPlaying(hasQueue = uiState.snapshot.upNext.isNotEmpty()) }
            }

            // The phone's queue, which only means anything while the phone is the one playing.
            if (uiState.source == PlaybackSource.PHONE && uiState.snapshot.upNext.isNotEmpty()) {
                item { ListHeader { Text(text = stringResource(R.string.watch_phone_queue)) } }
                items(uiState.snapshot.upNext) { episode ->
                    QueueRow(episode = episode, onClick = { onPlayOnPhone(episode.id) })
                }
            }

            // What the phone holds offline and has not sent here. Directly below the queue because
            // the two answer the same question — what is on the phone — and a wrist scrolls once.
            if (uiState.copyable.isNotEmpty()) {
                item {
                    ListHeader { Text(text = stringResource(R.string.watch_downloaded_on_phone)) }
                }
                items(uiState.copyable) { episode ->
                    CopyableRow(
                        episode = episode,
                        onPlay = { onPlayOnPhone(episode.id) },
                        onCopy = { onCopyToWatch(episode.id) },
                    )
                }
            }

            if (uiState.stored.isNotEmpty() || uiState.arriving.isNotEmpty()) {
                item {
                    ListHeader { Text(text = stringResource(R.string.watch_on_this_watch)) }
                }
                items(uiState.stored) { episode ->
                    StoredRow(
                        episode = episode,
                        onPlay = { onPlayOnWatch(episode) },
                        onRemove = { onRemoveFromWatch(episode.id) },
                    )
                }
                items(uiState.arriving) { arriving ->
                    ArrivingRow(
                        arriving = arriving,
                        onCancel = { onCancelCopyToWatch(arriving.episode.id) },
                    )
                }
                if (uiState.stored.isNotEmpty()) {
                    item { RemoveAllRow(onClick = onRemoveAllFromWatch) }
                }
            } else if (uiState.showsNothingToCopy) {
                // Nothing here and nothing offered: the note explains the empty screen, under the
                // heading of the list it would have filled.
                item {
                    ListHeader { Text(text = stringResource(R.string.watch_on_this_watch)) }
                }
                item { NothingToCopyNote() }
            }
        }
    }
}

/**
 * The two sentences about the phone link, at the top of whichever page is being read.
 *
 * Repeated on both pages rather than given to one of them. Both answer "why is this screen not
 * doing what I asked", the question is asked from whichever page the thumb happens to be on, and a
 * fact that is true of the app is true on every page of it.
 *
 * @param uiState what to draw.
 */
private fun ScalingLazyListScope.linkNotes(uiState: WatchPlayerUiState) {
    if (uiState.showsPhoneOutOfRange) {
        item { PhoneOutOfRangeNote() }
    }
    if (uiState.lastCommandFailed) {
        item { CommandFailedNote() }
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

        // The header is otherwise identical whichever device is playing, and the difference matters:
        // one of them keeps working when the other is in another room.
        if (uiState.source == PlaybackSource.WATCH) {
            Text(
                text = stringResource(R.string.watch_playing_on_watch),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

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
 * @param uiState what to draw, including the scrub preview position.
 * @param onBeginScrub takes hold of the bar.
 * @param onScrubBy moves it by a signed offset in milliseconds.
 * @param onCommitScrub seeks to where it was left.
 */
@Composable
private fun ProgressRow(
    uiState: WatchPlayerUiState,
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

    // Rotary events go to whatever holds focus, so the bar has to claim it on entering scrub mode
    // and give it back on leaving, or the list would keep consuming the bezel.
    LaunchedEffect(uiState.isScrubbing) {
        if (uiState.isScrubbing) focusRequester.requestFocus()
    }

    // Committing on a pause rather than on release: rotary has no "release", and a bezel turn
    // arrives as a burst of events. Re-keyed on the position, so each movement restarts the wait.
    if (uiState.isScrubbing) {
        // Where the bar stood when it was grabbed. Until that changes the user has only tapped into
        // scrub mode without moving anything, and committing then would seek to where playback
        // already is and drop them straight back out of the mode they just deliberately entered.
        // Remembered inside this branch, so leaving scrub mode forgets it.
        val grabbedAtMs = remember { uiState.positionMs }

        LaunchedEffect(uiState.positionMs) {
            if (uiState.positionMs != grabbedAtMs) {
                delay(SCRUB_COMMIT_DELAY_MS)
                onCommitScrub()
            }
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
                progress = { uiState.progress },
                modifier = Modifier.fillMaxWidth().height(
                    if (uiState.isScrubbing) SCRUB_BAR_HEIGHT else PROGRESS_BAR_HEIGHT,
                ),
            )
            if (uiState.isScrubbing) {
                ScrubThumb(progress = uiState.progress, trackWidthPx = barWidthPx)
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
            Text(
                text = formatPlaybackTime(uiState.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.isScrubbing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
 * The grip on the bar, drawn only while the bar is being held.
 *
 * The whole of what makes scrub mode visible. Before this, the only difference between reading the
 * position and moving it was that the bar got taller, which nobody reads as *this is now a
 * control*; a thumb is the shape every slider ever made has used to say so.
 *
 * Placed by padding it away from the start of the track rather than by weighting two spacers, so
 * that a progress of zero and a progress of one both leave it inside the bar rather than half off
 * the end of it.
 *
 * @param progress how far along the track it sits, from zero to one.
 * @param trackWidthPx the measured width of the bar; zero before the first layout pass, which puts
 *   the thumb at the start for one frame rather than not drawing it at all.
 */
@Composable
private fun ScrubThumb(progress: Float, trackWidthPx: Int) {
    val trackWidth = with(LocalDensity.current) { trackWidthPx.toDp() }
    val travel = (trackWidth - SCRUB_THUMB_SIZE).coerceAtLeast(0.dp)

    Box(
        modifier = Modifier
            .padding(start = travel * progress.coerceIn(0f, 1f))
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
 * The controls used while sitting down.
 *
 * What sits beside the speed depends on where the audio is. The phone has a queue, so it gets
 * previous and next; the watch holds a handful of episodes picked one at a time and has no "next",
 * so in its place is the way back — the one control that turns the player back into a remote.
 *
 * @param uiState what is playing and where.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onBackToPhone invoked to stop local playback.
 */
@Composable
private fun SecondaryRow(
    uiState: WatchPlayerUiState,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onCycleSpeed: () -> Unit,
    onBackToPhone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.source == PlaybackSource.PHONE) {
            IconButton(onClick = onSkipToPrevious) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.watch_previous_episode),
                )
            }
        }

        TextButton(onClick = onCycleSpeed) {
            Text(text = formatSpeed(uiState.snapshot.speed))
        }

        if (uiState.source == PlaybackSource.PHONE) {
            IconButton(onClick = onSkipToNext, enabled = uiState.snapshot.hasNext) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.watch_next_episode),
                )
            }
        } else {
            IconButton(onClick = onBackToPhone) {
                Icon(
                    imageVector = Icons.Rounded.PhonelinkErase,
                    contentDescription = stringResource(R.string.watch_back_to_phone),
                )
            }
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
 * One episode held on the watch: tap to play it here, or delete it.
 *
 * The delete button is on the row rather than behind a gesture because this list is the only place
 * storage is managed, and a watch fills up quietly.
 *
 * @param episode the stored episode.
 * @param onPlay invoked when the row is tapped.
 * @param onRemove invoked by the delete button.
 */
@Composable
private fun StoredRow(
    episode: StoredEpisode,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier.weight(1f),
            icon = { ShowDot(accent = showAccent(episode.showTitle)) },
            label = { Text(text = episode.title, maxLines = 2) },
            secondaryLabel = {
                Text(text = storedSubtitle(episode), maxLines = 1)
            },
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.watch_remove_from_watch),
            )
        }
    }
}

/**
 * The second line of a stored row: the show, and how long the episode asks for.
 *
 * Every row that can answer it gives a number, because the question asked of this list is always the
 * same one — *will this fit my walk*. A part-heard episode answers it with what is left; an
 * untouched one with its whole length, which is the same question one step earlier. "0% played" is
 * still noise, which is why an unstarted episode says its length rather than its progress.
 *
 * A finished episode says so instead of saying a length: what is left of it is nothing, and its
 * length is no longer a thing anybody is deciding about.
 *
 * @param episode the stored episode.
 */
@Composable
private fun storedSubtitle(episode: StoredEpisode): String = when {
    episode.isPlayed -> stringResource(R.string.watch_stored_played, episode.showTitle)

    episode.positionMs > 0L && episode.durationMs > 0L -> stringResource(
        R.string.watch_stored_remaining,
        episode.showTitle,
        formatCompactRemaining(episode.durationMs - episode.positionMs),
    )

    episode.durationMs > 0L -> stringResource(
        R.string.watch_stored_length,
        episode.showTitle,
        formatCompactRemaining(episode.durationMs),
    )

    // A copy that arrived without a duration. The show's name is all there is to say, and inventing
    // a "0m" for it would be the one thing worse than saying nothing.
    else -> episode.showTitle
}

/**
 * An episode arriving over Bluetooth, and the one thing worth doing to it: stopping it.
 *
 * The row itself is still not pressable — a tap on it could only mean "send this again", which is
 * already happening — so the only target is the cancel button beside the bar. That button is the
 * answer to the wrong episode having been tapped, and to a copy that has plainly stalled: an episode
 * is tens of megabytes over Bluetooth, which is minutes of a wearer's radio to get back.
 *
 * @param arriving what is coming and how much of it has landed.
 * @param onCancel invoked to abandon the transfer.
 */
@Composable
private fun ArrivingRow(arriving: ArrivingEpisode, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = arriving.episode.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            LinearProgressIndicator(
                progress = { arriving.progress.fraction },
                modifier = Modifier.fillMaxWidth().height(PROGRESS_BAR_HEIGHT),
            )
            Text(
                text = stringResource(R.string.watch_copying),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.watch_cancel_copy),
            )
        }
    }
}

/**
 * One episode the phone has downloaded and the watch does not hold: play it there, or fetch it here.
 *
 * Two targets rather than one, because this list answers two different wants with the same rows.
 * Most of the time the phone is in a pocket and the episode is meant to come out of it now, which
 * was previously impossible from this list — the only thing a row did was start a multi-minute
 * Bluetooth copy, and the wearer had to find the episode again in the phone's own queue to hear it.
 * So the row itself now does the common thing, and the copy keeps its own button beside it, laid
 * out exactly as the delete button on a stored row is; see [StoredRow].
 *
 * The play glyph is described rather than decorative for the same reason the download one is: the
 * header above names the list ("Downloaded on phone") and not the action, so between them the two
 * glyphs are the only thing saying which device each target reaches — and TalkBack cannot see a
 * glyph.
 *
 * @param episode what the phone offered.
 * @param onPlay invoked to start the episode on the phone.
 * @param onCopy invoked to start the transfer to the watch.
 */
@Composable
private fun CopyableRow(
    episode: OfflineEpisode,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPlay,
            modifier = Modifier.weight(1f),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.watch_play_on_phone),
                )
            },
            label = { Text(text = episode.title, maxLines = 2) },
            secondaryLabel = { Text(text = episode.showTitle, maxLines = 1) },
        )
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.watch_copy_to_watch),
            )
        }
    }
}

/**
 * Clears the watch's storage.
 *
 * At the end of the list rather than the top: it is the destructive one, and the list above it is
 * what somebody came here to use.
 *
 * @param onClick invoked to remove everything.
 */
@Composable
private fun RemoveAllRow(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.watch_free_up_space))
    }
}

/**
 * What to do about a watch holding nothing.
 *
 * Also the only place the feature announces itself: nobody looks for a "copy to watch" list that is
 * not there, so an empty watch says where episodes come from rather than saying nothing.
 */
@Composable
private fun NothingToCopyNote() {
    Text(
        text = stringResource(R.string.watch_nothing_to_copy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * One line saying the phone is not there.
 *
 * Deliberately not the full-screen version: the wearer is looking at episodes that play without a
 * phone, and a wall in front of them would be wrong about what is possible.
 */
@Composable
private fun PhoneOutOfRangeNote() {
    Text(
        text = stringResource(R.string.watch_phone_out_of_range),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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

/**
 * The two pages of the screen, and the order they sit in.
 *
 * Now playing first because that is what a raised wrist is nearly always asking about; the episodes
 * are a deliberate second question. Spelled out rather than left as bare 0 and 1 because the pager
 * hands the index back as an `Int` and `if (page == 0)` says nothing about which page that is.
 */
private const val PAGE_NOW_PLAYING = 0
private const val PAGE_COUNT = 2

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
