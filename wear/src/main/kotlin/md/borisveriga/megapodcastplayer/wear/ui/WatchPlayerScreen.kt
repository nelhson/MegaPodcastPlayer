package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.touchTargetAwareSize
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.wearprotocol.WatchEpisode
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
        onPlayOnPhone = viewModel::playOnPhone,
        onQueueOnPhone = viewModel::queueOnPhone,
        onRetry = viewModel::retry,
        onBeginScrub = viewModel::beginScrub,
        onScrubBy = viewModel::scrubBy,
        onCommitScrub = viewModel::commitScrub,
        onBeginVolume = viewModel::beginVolume,
        onEndVolume = viewModel::endVolume,
        onSetVolume = viewModel::setVolume,
        onAdjustVolumeBy = viewModel::adjustVolumeBy,
    )
}

/**
 * The watch's remote control.
 *
 * Stateless so it can be previewed and screenshot-tested without a phone at the other end.
 *
 * One column, top to bottom: what is playing, the scrubber, the transport, previous, speed and
 * next, the volume, the phone's queue, and what is downloaded on the phone. The top block carries
 * no buttons, which is what buys the transport its place on the first screen; the volume is a row
 * of its own directly under the player's controls, a short scroll away, because it is adjusted
 * now and then rather than pressed blind. The transport is the one thing here that has to be under
 * the thumb within a second of raising the wrist, and it is: the two lists are the only parts whose
 * length the phone decides, and they are last in the column, so however long they grow nothing
 * above them moves. The screen was once split into two pages to keep a growing list from pushing
 * pause off the bottom; putting the lists last does the same with nothing to swipe. This is the
 * whole app — there is no second screen, and the tile that used to be a smaller copy of this one is
 * gone.
 *
 * The bezel scrolls the list unless the scrubber or the volume bar has taken it, and comes back to
 * the list the moment neither holds it. Those two are list items, and a lazy list does not keep a
 * focused item composed: had the bezel been left with whichever of them took it last, scrolling
 * that row out of view would have taken the focus with it, and every turn after would go nowhere.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onPlayOnPhone invoked with the episode id when an episode row is tapped.
 * @param onQueueOnPhone invoked with the episode id by a downloaded row's queue button.
 * @param onRetry invoked when the user retries a failed connection.
 * @param position where playback has reached, read only by the bar and only when it draws. A
 *   lambda rather than a value so that the clock, which moves this once a second, is not a reason
 *   for the list to recompose; see [WatchPlayerUiState] for why that matters.
 * @param onBeginScrub invoked when the user takes hold of the progress bar.
 * @param onScrubBy invoked as they move it, with a signed offset in milliseconds.
 * @param onCommitScrub invoked when they settle, which is what actually seeks.
 * @param onBeginVolume invoked when a tap on the volume bar hands it the bezel.
 * @param onEndVolume invoked when a second tap gives the bezel back to the list.
 * @param onSetVolume invoked with an absolute level by the bar's own minus and plus buttons.
 * @param onAdjustVolumeBy invoked with a signed number of steps as the bezel turns, or as a
 *   finger is dragged along the volume bar.
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
    onQueueOnPhone: (String) -> Unit,
    onRetry: () -> Unit,
    position: () -> PlaybackPosition = { PlaybackPosition() },
    onBeginScrub: () -> Unit = {},
    onScrubBy: (Long) -> Unit = {},
    onCommitScrub: () -> Unit = {},
    onBeginVolume: () -> Unit = {},
    onEndVolume: () -> Unit = {},
    onSetVolume: (Int) -> Unit = {},
    onAdjustVolumeBy: (Int) -> Unit = {},
) {
    // A phone we cannot reach makes every control below meaningless, so the same fact replaces
    // the screen and says what to do about it.
    if (uiState.showsLinkProblem) {
        LinkProblemScreen(link = uiState.link, onRetry = onRetry)
        return
    }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    // Read here rather than where it is used, which is the progress bar. That is a list item, so
    // reading it there meant a `Settings.Global` read while composing and a ContentObserver
    // registered and unregistered as the effect was applied and disposed — three trips to the
    // system server, on the main thread, every time it scrolled out of view and back. It is one
    // reading per screen, and this is where a screen's readings belong.
    val reduceMotion = rememberReduceMotion()

    // The bezel's home. Taken back whenever neither the scrubber nor the volume bar holds it; see
    // this function's documentation for why it cannot be left with them.
    val listFocus = remember { FocusRequester() }
    val bezelHeldByRow = uiState.isScrubbing || uiState.isAdjustingVolume
    LaunchedEffect(bezelHeldByRow) {
        if (!bezelHeldByRow) listFocus.requestFocus()
    }

    // A scrolling list rather than a fixed layout even for the controls alone, because at 200 %
    // font scale five items do not fit a round screen and the alternative to scrolling is clipping.
    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            // Ahead of the list's own rotary modifier, so it names the focus target that one adds.
            modifier = Modifier.fillMaxSize().focusRequester(listFocus),
        ) {
            if (uiState.lastCommandFailed) {
                item(key = "failed", contentType = "note") {
                    CommandFailedNote(modifier = Modifier.scrollTransform(this, spec))
                }
            }

            if (uiState.showsControls) {
                item(key = "top", contentType = "top") {
                    NowPlayingTop(uiState = uiState, modifier = Modifier.scrollTransform(this, spec))
                }
                item(key = "progress", contentType = "progress") {
                    ProgressRow(
                        uiState = uiState,
                        position = position,
                        playing = uiState.snapshot.isPlaying && !reduceMotion,
                        onBeginScrub = onBeginScrub,
                        onScrubBy = onScrubBy,
                        onCommitScrub = onCommitScrub,
                        modifier = Modifier.scrollTransform(this, spec),
                    )
                }
                item(key = "transport", contentType = "transport") {
                    TransportRow(
                        uiState = uiState,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                        modifier = Modifier.scrollTransform(this, spec),
                    )
                }
                item(key = "secondary", contentType = "secondary") {
                    SecondaryRow(
                        uiState = uiState,
                        onSkipToPrevious = onSkipToPrevious,
                        onSkipToNext = onSkipToNext,
                        onCycleSpeed = onCycleSpeed,
                        modifier = Modifier.scrollTransform(this, spec),
                    )
                }
                // Right under the player's controls and above the lists, so it is the first thing
                // a scroll reaches — and no row at all for a phone that gave no scale to move along.
                if (uiState.canSetVolume) {
                    item(key = "volume", contentType = "volume") {
                        VolumeRow(
                            uiState = uiState,
                            onBeginVolume = onBeginVolume,
                            onEndVolume = onEndVolume,
                            onSetVolume = onSetVolume,
                            onAdjustVolumeBy = onAdjustVolumeBy,
                            modifier = Modifier.scrollTransform(this, spec),
                        )
                    }
                }
            } else {
                // Nothing to control means no controls: the sentence explaining the missing
                // transport sits above the episodes it tells the wearer to pick from.
                item(key = "idle", contentType = "idle") {
                    NothingPlaying(
                        hasQueue = uiState.snapshot.upNext.isNotEmpty(),
                        modifier = Modifier.scrollTransform(this, spec),
                    )
                }
            }

            phoneQueue(uiState = uiState, spec = spec, onPlayOnPhone = onPlayOnPhone)
            phoneDownloads(
                uiState = uiState,
                spec = spec,
                onPlayOnPhone = onPlayOnPhone,
                onQueueOnPhone = onQueueOnPhone,
            )
        }
    }
}

/**
 * Applies the column's scroll transformation to one item.
 *
 * [TransformingLazyColumn] hands each item its own scroll progress, and this is what turns that
 * progress into the shrinking and fading that makes a list look right on a round screen. The
 * previous `ScalingLazyColumn` did the same thing by wrapping every item in a `Box` whose
 * `graphicsLayer` block scanned the list's visible items — by index, once per item, once per frame
 * — to find out where that item had reached. Here the item is simply told.
 *
 * Used in place of the Material `transformation` parameter that [Button] and [ListHeader] accept,
 * because half the things in this column — the transport rows, the top block — are not
 * surfaces at all, and a column where only some items taper reads as a bug.
 *
 * It must be the *container* transformation and not the content one. The spec splits the two:
 * `applyContentTransformation` sets alpha alone, while the scale and the recentring `translationY`
 * live in `applyContainerTransformation` — and [transformedHeight] has already shrunk the item's
 * layout slot to `scale * height` by the time either runs. Pairing the shrunken slot with content
 * that still draws full size and never recentres is not a smaller item; it is a full-size item
 * overlapping its neighbour by the difference, with the taper gone. That was the first version of
 * this function, and nothing in the module caught it.
 *
 * @param scope the item's own scope, which is where its scroll progress comes from.
 * @param spec how progress becomes height, scale and alpha; see [rememberTransformationSpec].
 */
private fun Modifier.scrollTransform(
    scope: TransformingLazyColumnItemScope,
    spec: TransformationSpec,
): Modifier = transformedHeight(scope, spec)
    .graphicsLayer { with(scope) { with(spec) { applyContainerTransformation(scrollProgress) } } }

/**
 * The phone's queue, at the bottom of the column.
 *
 * The header names the list rather than the action its rows perform: on a screen this small the
 * header is the only thing that says *whose* episodes these are. Every row is keyed, so that a
 * queue that changes moves the rows around the change rather than rebuilding them.
 *
 * Every row also carries a content type. Rows of the same type can hand their composition on to
 * the next row of that type as the list scrolls, instead of each one being built from nothing; a
 * queue row and a downloaded row are differently shaped, so they say so and keep to their own
 * pools. `ScalingLazyColumn` had no way to express this — its scope takes a key and nothing else —
 * so every item shared one pool typed `null`. That still reused correctly while scrolling *within*
 * a section, where the slot leaving and the slot arriving are the same shape; what it could not do
 * is tell a queue row from a downloaded row at the boundary between them.
 *
 * @param uiState what to draw.
 * @param spec the column's scroll transformation, applied to each row.
 * @param onPlayOnPhone invoked with the episode id when a queued episode is tapped.
 */
private fun TransformingLazyColumnScope.phoneQueue(
    uiState: WatchPlayerUiState,
    spec: TransformationSpec,
    onPlayOnPhone: (String) -> Unit,
) {
    if (uiState.snapshot.upNext.isEmpty()) return

    item(key = "queue-header", contentType = "listHeader") {
        ListHeader(modifier = Modifier.scrollTransform(this, spec)) {
            Text(text = stringResource(R.string.watch_phone_queue))
        }
    }
    items(
        uiState.snapshot.upNext,
        key = { "queue:${it.id}" },
        contentType = { "queueRow" },
    ) { episode ->
        QueueRow(
            episode = episode,
            onClick = { onPlayOnPhone(episode.id) },
            modifier = Modifier.scrollTransform(this, spec),
        )
    }
}

/**
 * What is downloaded on the phone but not queued, under the queue.
 *
 * The queue above it is what the wearer already decided to listen to; this is everything else they
 * could, and it is the only part of a library that reaching the wrist makes any sense of — an
 * episode that is not on the phone's disk cannot be started from a wrist out of Bluetooth range
 * anyway. Under the queue rather than above it because it is the rarer errand: most raises of the
 * wrist are about what is playing, some are about what is next, and only a few are about changing
 * what is next.
 *
 * Each row carries two actions, which is one more than anything else on this screen. That is the
 * point of the section: playing a downloaded episode *now* means stopping what is in your ears, and
 * the usual answer — "after this one" — had nowhere to live. The row itself plays, as the queue's
 * rows do, and the button adds to the queue.
 *
 * @param uiState what to draw.
 * @param spec the column's scroll transformation, applied to each row.
 * @param onPlayOnPhone invoked with the episode id when a row is tapped.
 * @param onQueueOnPhone invoked with the episode id when a row's queue button is tapped.
 */
private fun TransformingLazyColumnScope.phoneDownloads(
    uiState: WatchPlayerUiState,
    spec: TransformationSpec,
    onPlayOnPhone: (String) -> Unit,
    onQueueOnPhone: (String) -> Unit,
) {
    if (uiState.snapshot.downloaded.isEmpty()) return

    item(key = "downloads-header", contentType = "listHeader") {
        ListHeader(modifier = Modifier.scrollTransform(this, spec)) {
            Text(text = stringResource(R.string.watch_phone_downloads))
        }
    }
    items(
        uiState.snapshot.downloaded,
        key = { "downloaded:${it.id}" },
        contentType = { "downloadedRow" },
    ) { episode ->
        DownloadedRow(
            episode = episode,
            onClick = { onPlayOnPhone(episode.id) },
            onQueue = { onQueueOnPhone(episode.id) },
            modifier = Modifier.scrollTransform(this, spec),
        )
    }
}

/**
 * What is playing: the show on one line, the episode's title under it.
 *
 * Nothing to press here. The moment button that used to sit beside the show is gone from the
 * watch, and the volume has a row of its own under the transport — see [VolumeRow] — so this block
 * is only ever read, and is as short as reading it allows.
 *
 * There is no cover art and no decorative animation here on purpose. The art answered "which show
 * is this" badly — third-party imagery behind a title needs a scrim heavy enough that little of the
 * picture survives — and a colour answers it at the same glance for nothing; see [showAccent].
 * Whether the phone is playing is said by the progress bar's glint, where the eye already goes for
 * "how far".
 *
 * @param uiState what to draw.
 * @param modifier applied to the block; carries the column's scroll transformation.
 */
@Composable
private fun NowPlayingTop(uiState: WatchPlayerUiState, modifier: Modifier = Modifier) {
    val accent = showAccent(uiState.snapshot.showTitle)
    // Remembered rather than rebuilt each composition: a brush is a shader's cache key, and a new
    // instance every time is a new shader every time.
    val wash = remember(accent) {
        // Fading out at the bottom rather than ending on an edge: the rows below continue the
        // column, and a hard band across a round screen would cut the layout in half.
        Brush.verticalGradient(
            listOf(
                accent.copy(alpha = WASH_TOP_ALPHA),
                accent.copy(alpha = WASH_FADE_ALPHA),
                Color.Transparent,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The shape goes to `background` rather than to a `clip` above it: one node that draws
            // the wash within the shape, instead of a clip node the wash is then drawn through.
            .background(brush = wash, shape = MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uiState.snapshot.showTitle.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShowDot(accent = accent)
                Text(
                    text = uiState.snapshot.showTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = uiState.snapshot.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
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
 * The bar is also what says the phone is playing: a glint travels along its filled part while it
 * plays, and stops dead when it pauses; see [ProgressGlint].
 *
 * @param uiState what to draw.
 * @param position where playback has reached, or the scrub preview while scrubbing.
 * @param playing whether to run the glint — the phone is playing and the wearer has not asked for
 *   stillness.
 * @param onBeginScrub takes hold of the bar.
 * @param onScrubBy moves it by a signed offset in milliseconds.
 * @param onCommitScrub seeks to where it was left.
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
private fun ProgressRow(
    uiState: WatchPlayerUiState,
    position: () -> PlaybackPosition,
    playing: Boolean,
    onBeginScrub: () -> Unit,
    onScrubBy: (Long) -> Unit,
    onCommitScrub: () -> Unit,
    modifier: Modifier = Modifier,
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

    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
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
            // Not while scrubbing: then the thumb is the thing moving, and a second moving thing on
            // the same bar would be competing with the one the wearer's finger is on.
            if (playing && !uiState.isScrubbing) {
                ProgressGlint(progress = { position().progress }, modifier = Modifier.matchParentSize())
            }
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The phone's media volume: a bar with a step either side of it, in a row of its own.
 *
 * Sits directly under the player's controls rather than up with the title. It used to share the
 * title's slot behind a toggle, which kept it off the first screen but made it a two-step control
 * that hid the episode while it was open; as its own row it is always there, one short scroll down,
 * and the title never has to make way for it.
 *
 * Built on Wear Material3's [Slider], which is already the shape of this control — two 48 dp
 * buttons with a bar between them, its own detent haptics, and range semantics that let TalkBack
 * read the level without this screen announcing a bare number at it. What the slider does not have
 * is the bezel, because rotary input goes to whoever holds focus and a slider does not ask for it
 * — nor a drag: its bar is drawn, not held, so the finger's movement along it is added here too,
 * on the scale the bar suggests. See [bankVolumeSteps] for how either distance becomes steps.
 *
 * The bezel is taken the way the scrubber takes it: a tap on the bar hands it over, and a second
 * tap — or a few still seconds, which the view model times — gives it back to the list. The bar is
 * outlined while it holds the bezel, so the mode is visible as well as spoken. The buttons and the
 * drag need no mode at all, which is what keeps the bar usable for a wearer who never discovers
 * the bezel.
 *
 * The tap is heard only on the bar between the two buttons, not on the whole row. An enabled
 * button consumes its own tap; for a disabled one — "Quieter" at zero, "Louder" at the top — the
 * slider makes no such promise, and a row-wide tap that a greyed-out button let through would be a
 * change of mode nobody asked for. Bounding the target by position makes it not matter.
 * There is no ripple for the same reason it has no row-wide target: the outline is the answer to
 * the tap, and a ripple bounded to the row would light the corners outside the pill.
 *
 * Giving the bezel back hands the focus to the list rather than leaving it here; see
 * [WatchPlayerScreen]. The rotary handler still declines every turn while the mode is off, for the
 * moment between the two.
 *
 * The one thing it does not copy from the scrubber is the pause before the value is sent. A seek
 * is a jump to somewhere you cannot hear until you arrive; a volume change is audible while the
 * finger is still moving, so every step goes out at once and the throttling happens behind it.
 *
 * @param uiState what to draw, including the level and whether the bezel is held.
 * @param onBeginVolume hands the bar the bezel.
 * @param onEndVolume gives the bezel back to the list.
 * @param onSetVolume sets an absolute level; what the slider's own buttons report.
 * @param onAdjustVolumeBy moves by whole steps; what the bezel and a drag along the bar report.
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
internal fun VolumeRow(
    uiState: WatchPlayerUiState,
    onBeginVolume: () -> Unit,
    onEndVolume: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onAdjustVolumeBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engaged = uiState.isAdjustingVolume
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    // Rotary arrives as scroll pixels, not as detents, and a finger as a distance, so each is
    // banked until it adds up to a step. Two banks, because the two are worth different distances
    // per step and a remainder from one means nothing to the other. Kept outside composition: a
    // turn moves the volume, and must not also recompose the row that is reading it.
    val turned = remember { mutableFloatStateOf(0f) }
    val dragged = remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(engaged) {
        if (!engaged) return@LaunchedEffect
        // A new hold starts from nothing: what the last one left over was a turn that has ended.
        turned.floatValue = 0f
        // Rotary events go to whatever holds focus, so taking the bezel claims it.
        focusRequester.requestFocus()
    }

    // Dragging the length of the bar covers the phone's whole scale, so the fill follows the
    // finger — which is what the bar suggests and what the scrubber already does with an episode.
    // The bar is the row less the button at either end of it.
    val maxVolume = uiState.snapshot.maxVolume
    val buttonPx = with(LocalDensity.current) { SLIDER_BUTTON_WIDTH.toPx() }
    val barWidthPx = (rowWidthPx - buttonPx * 2).coerceAtLeast(0f)
    val dragPixelsPerStep: Float = if (maxVolume > 0) barWidthPx / maxVolume else 0f

    val volumeLabel = stringResource(
        if (engaged) R.string.watch_volume_active else R.string.watch_volume,
    )
    // An outline rather than a fill: the slider paints its own opaque container, so a tint behind
    // it would never be seen.
    val outline = if (engaged) MaterialTheme.colorScheme.primary else Color.Transparent
    // Read through state by the gesture below, which is started once and outlives recompositions.
    val toggle by rememberUpdatedState { if (engaged) onEndVolume() else onBeginVolume() }

    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width }
                .border(width = VOLUME_HELD_OUTLINE, color = outline, shape = CircleShape)
                .semantics {
                    contentDescription = volumeLabel
                    // What the tap below is to a finger, for TalkBack, which has no position.
                    onClick {
                        toggle()
                        true
                    }
                }
                .pointerInput(buttonPx) {
                    detectTapGestures { offset ->
                        if (offset.x > buttonPx && offset.x < size.width - buttonPx) toggle()
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    if (!engaged) return@onRotaryScrollEvent false
                    val banked = bankVolumeSteps(
                        bankedPx = turned.floatValue + event.verticalScrollPixels,
                        pixelsPerStep = ROTARY_PIXELS_PER_VOLUME_STEP,
                    )
                    turned.floatValue = banked.remainderPx
                    if (banked.steps != 0) {
                        // Same sign as the scrubber, which turns the same bezel on the same screen:
                        // forward is later there and louder here. Two controls that answered one
                        // turn in opposite directions would be a coin toss.
                        onAdjustVolumeBy(banked.steps)
                        // The slider buzzes for its own buttons; the bezel has to be given the same
                        // tick by hand, or half the control would be silent to the hand.
                        if (uiState.volumeWouldMove(banked.steps)) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                    true
                }
                // The slider draws a bar and does not let a finger move it: it is two buttons and
                // a picture. A bar that looks draggable and is not reads as broken, so the drag is
                // added here.
                .draggable(
                    state = rememberDraggableState { delta ->
                        val banked = bankVolumeSteps(
                            bankedPx = dragged.floatValue + delta,
                            pixelsPerStep = dragPixelsPerStep,
                        )
                        dragged.floatValue = banked.remainderPx
                        if (banked.steps != 0) {
                            onAdjustVolumeBy(banked.steps)
                            if (uiState.volumeWouldMove(banked.steps)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                    },
                    orientation = Orientation.Horizontal,
                    // A new drag starts from nothing: what the last one left over was distance
                    // along a gesture that has ended.
                    onDragStarted = { dragged.floatValue = 0f },
                ),
        ) {
            Slider(
                value = uiState.volumeLevel,
                onValueChange = onSetVolume,
                valueProgression = 0..uiState.snapshot.maxVolume,
                // A phone's scale is a dozen-odd steps, and a dozen segments across a 45 mm screen
                // reads as a pattern rather than as a level.
                segmented = false,
                decreaseIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
                        contentDescription = stringResource(R.string.watch_volume_down),
                    )
                },
                increaseIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.watch_volume_up),
                    )
                },
            )
        }
        if (uiState.showsVolumeHint) {
            Text(
                text = stringResource(R.string.watch_volume_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/**
 * A soft band of light travelling along the filled part of the progress bar.
 *
 * This replaced the waveform that used to sit over the title. That animation answered "is it
 * playing" from a part of the screen that says nothing else about playback; the bar is already where
 * the eye goes for "how far", and a moving highlight there answers both at one glance. It is kept
 * faint and narrow so that it reads as life in the bar rather than as a second progress indicator.
 *
 * Composed only while playing, so a pause removes it outright instead of freezing a highlight
 * mid-bar that would look like a marker. The phase is an infinite transition, which keeps the
 * frame clock running for as long as the bar is on screen and playing; that is the one fixed cost
 * every scroll of this screen pays on top of its own, and it is what says "playing".
 *
 * @param progress how much of the bar is filled, from zero to one.
 * @param modifier sizes the glint to the bar it runs along.
 */
@Composable
private fun ProgressGlint(progress: () -> Float, modifier: Modifier = Modifier) {
    val phase = rememberInfiniteTransition(label = "glint").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // Linear and restarting: the glint runs one way, like the playhead, and a reversing sweep
        // would read as playback going backwards.
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GLINT_PERIOD_MS, easing = LinearEasing),
        ),
        label = "glint-phase",
    )
    ProgressGlintFrame(progress = progress, phase = { phase.value }, modifier = modifier)
}

/**
 * One frame of [ProgressGlint]: the band at [phase] along the fill at [progress].
 *
 * Split from the animation so that a frame can be rendered still, which is what a screenshot
 * golden needs — and so that what a frame *costs* is all in one place. Nothing here is allocated
 * per frame that can be made once: the gradient is a shader, and a shader is a cache key, so it is
 * built once per band width and translated into place each frame rather than rebuilt at each new
 * position; the clip path is one object, emptied and refilled. Both the phase and the progress
 * are read inside the draw lambda, never in composition: each frame repaints this layer and
 * nothing else. A watch has little heap to spare, and an allocation at 60 Hz for 1.8 s at a time is
 * a collector that runs while the wearer is scrolling.
 *
 * @param progress how much of the bar is filled, from zero to one.
 * @param phase how far the band has travelled, from zero (wholly before the bar) to one (wholly
 *   past the fill), so that it slides in and out rather than popping into being at the left edge.
 * @param modifier sizes the glint to the bar it runs along.
 */
@Composable
internal fun ProgressGlintFrame(
    progress: () -> Float,
    phase: () -> Float,
    modifier: Modifier = Modifier,
) {
    val light = MaterialTheme.colorScheme.onSurface.copy(alpha = GLINT_ALPHA)
    val band = with(LocalDensity.current) { GLINT_WIDTH.toPx() }
    // The band's gradient, from its own left edge: the same object every frame, moved by
    // translating the canvas rather than by asking for a new one at the new position.
    val glow = remember(light, band) {
        Brush.horizontalGradient(
            colors = listOf(Color.Transparent, light, Color.Transparent),
            startX = 0f,
            endX = band,
        )
    }
    val clip = remember { Path() }

    Canvas(
        modifier = modifier
            // A layer of its own, so redrawing the glint every frame redraws the glint and not the
            // list it sits in.
            .graphicsLayer()
            // Decorative: the play button already says whether the phone is playing.
            .clearAndSetSemantics { },
    ) {
        val filled = size.width * progress().coerceIn(0f, 1f)
        if (filled <= 0f) return@Canvas

        val centre = -band * HALF + phase().coerceIn(0f, 1f) * (filled + band)
        val radius = size.height * HALF
        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, filled, size.height, CornerRadius(radius)))
        clipPath(clip) {
            translate(left = centre - band * HALF) {
                drawRect(brush = glow, size = Size(band, size.height))
            }
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
        style = MaterialTheme.typography.labelMedium,
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

/**
 * Skip back, play/pause, skip forward — the three buttons that get used while walking.
 *
 * @param uiState what is playing.
 * @param onTogglePlayPause invoked by the centre button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
private fun TransportRow(
    uiState: WatchPlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved here rather than inside the semantics lambda below, which is not composable.
    val bufferingLabel = stringResource(R.string.watch_buffering)

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(TRANSPORT_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onSkipBack,
            modifier = Modifier.touchTargetAwareSize(SKIP_BUTTON_SIZE),
        ) {
            SkipGlyph(skipMs = uiState.snapshot.skipBackMs, forward = false)
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
                modifier = Modifier.touchTargetAwareSize(PLAY_BUTTON_SIZE),
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

        FilledTonalIconButton(
            onClick = onSkipForward,
            modifier = Modifier.touchTargetAwareSize(SKIP_BUTTON_SIZE),
        ) {
            SkipGlyph(skipMs = uiState.snapshot.skipForwardMs, forward = true)
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
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
private fun SecondaryRow(
    uiState: WatchPlayerUiState,
    onSkipToPrevious: () -> Unit,
    onSkipToNext: () -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSkipToPrevious,
            modifier = Modifier.touchTargetAwareSize(IconButtonDefaults.SmallButtonSize),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.watch_previous_episode),
            )
        }

        TextButton(
            onClick = onCycleSpeed,
            modifier = Modifier.touchTargetAwareSize(TextButtonDefaults.SmallButtonSize),
        ) {
            Text(text = formatSpeed(uiState.snapshot.speed))
        }

        IconButton(
            onClick = onSkipToNext,
            enabled = uiState.snapshot.hasNext,
            modifier = Modifier.touchTargetAwareSize(IconButtonDefaults.SmallButtonSize),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.watch_next_episode),
            )
        }
    }
}

/**
 * One "up next" row; tapping it asks the phone to play that episode.
 *
 * Carries the show's colour as a dot, the same one the header uses, so a queue holding three shows
 * can be told apart without reading it.
 *
 * @param episode the episode.
 * @param onClick asks the phone to play it.
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
private fun QueueRow(episode: WatchEpisode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        icon = { ShowDot(accent = showAccent(episode.showTitle)) },
        label = { Text(text = episode.title, maxLines = 2) },
        secondaryLabel = { Text(text = episode.showTitle, maxLines = 1) },
    )
}

/**
 * One downloaded episode: a button that plays it, and a button that queues it.
 *
 * Two targets side by side rather than one row with a hidden second action, because a watch has no
 * swipe to spare — the system's own back gesture owns the horizontal — and no long press anyone
 * discovers. The queue button is kept to the minimum comfortable target and the episode takes the
 * rest of the width, so the larger, more likely action is also the easier one to hit while walking.
 *
 * @param episode the episode.
 * @param onClick plays it on the phone, interrupting what is playing.
 * @param onQueue puts it at the end of the phone's queue instead.
 * @param modifier applied to the row; carries the column's scroll transformation.
 */
@Composable
private fun DownloadedRow(
    episode: WatchEpisode,
    onClick: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            icon = { ShowDot(accent = showAccent(episode.showTitle)) },
            label = { Text(text = episode.title, maxLines = 2) },
            secondaryLabel = { Text(text = episode.showTitle, maxLines = 1) },
        )
        FilledTonalIconButton(
            onClick = onQueue,
            modifier = Modifier.size(QUEUE_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                // Names the episode, because a screen reader arrives at this button having left the
                // row beside it: "Add to queue" alone would not say what is being queued.
                contentDescription = stringResource(R.string.watch_queue_episode, episode.title),
            )
        }
    }
}

/**
 * Shown when the phone is reachable but has nothing loaded.
 *
 * @param hasQueue whether there is a queue below to point the wearer at.
 * @param modifier applied to the column; carries the list's scroll transformation.
 */
@Composable
private fun NothingPlaying(hasQueue: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
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

/**
 * Shown after a command that could not be delivered.
 *
 * @param modifier applied to the note; carries the column's scroll transformation.
 */
@Composable
private fun CommandFailedNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.watch_command_failed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
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
 * A skip button's glyph: the circular arrow, with the number of seconds inside it.
 *
 * The watch's own copy of the phone's `SkipGlyph`, for the reason every duplicated thing in this
 * file is duplicated — `:wear` sees `:core:wearprotocol` and `:core:common` and nothing else, and a
 * Wear-sized dependency on the phone's design system would be the larger mistake.
 *
 * Drawn rather than picked, and the reason matters here more than on the phone: Material ships
 * numbered icons for 5, 10 and 30 seconds only, and the phone offers 15, 45 and 60 as well. Those
 * used to fall back to two stacked triangles — the universal glyph for *previous track* — on a
 * screen the size of a watch face, where a misread button is pressed before it is read.
 *
 * @param skipMs the distance configured on the phone.
 * @param forward true for the skip-ahead button, which mirrors the arc.
 * @param modifier layout modifier.
 */
@Composable
private fun SkipGlyph(skipMs: Long, forward: Boolean, modifier: Modifier = Modifier) {
    val seconds = (skipMs / MILLIS_PER_SECOND).coerceAtLeast(1L).toInt()
    val description = skipContentDescription(skipMs, forward)
    val numeralSize = with(LocalDensity.current) { (SKIP_GLYPH_SIZE * SKIP_NUMERAL_FRACTION).toSp() }

    Box(
        // One node, one description: the arc and the digits are two halves of a single glyph.
        modifier = modifier
            .size(SKIP_GLYPH_SIZE)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Replay,
            contentDescription = null,
            // Mirrored on the x axis, which turns the anticlockwise arc clockwise.
            modifier = Modifier
                .size(SKIP_GLYPH_SIZE)
                .scale(scaleX = if (forward) -1f else 1f, scaleY = 1f),
        )
        Text(
            text = seconds.toString(),
            fontSize = numeralSize,
            lineHeight = numeralSize,
            fontWeight = FontWeight.Bold,
            // The arc opens at the top, so the digits sit just below the centre.
            modifier = Modifier.padding(top = SKIP_GLYPH_SIZE * SKIP_NUMERAL_DROP),
        )
    }
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
    val seconds = (skipMs / MILLIS_PER_SECOND).coerceAtLeast(1L).toInt()
    return pluralStringResource(
        id = if (forward) R.plurals.watch_skip_forward else R.plurals.watch_skip_back,
        count = seconds,
        seconds,
    )
}

/** The queue button beside a downloaded episode: the minimum target worth aiming at on a wrist. */
private val QUEUE_BUTTON_SIZE = 44.dp

/** The side of a skip glyph, matching what a Wear `IconButton` gave the icon it used to hold. */
private val SKIP_GLYPH_SIZE = 24.dp

/** The numeral's height as a fraction of the glyph, measured off Material's own `Replay30`. */
private const val SKIP_NUMERAL_FRACTION = 0.38f

/** How far below centre the numeral sits, as a fraction of the glyph. */
private const val SKIP_NUMERAL_DROP = 0.08f

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The play button: still larger than its neighbours, because it is the one pressed blind, but by a
 * step rather than by a size class. At 60 dp beside two 52 dp skips the transport outweighed the
 * episode and the bar above it, and ran off the bottom of a small round face.
 */
private val PLAY_BUTTON_SIZE = 52.dp

/**
 * The skip buttons, drawn smaller than Wear's default and kept at its 48 dp touch target by
 * `touchTargetAwareSize`: the target is what the thumb needs, the drawn size is what the eye weighs.
 */
private val SKIP_BUTTON_SIZE = 44.dp

/** The gap between the three transport buttons; the smaller buttons leave room for more air. */
private val TRANSPORT_GAP = 10.dp

/** Sized to clear the play button so the ring reads as around it rather than on it. */
private val BUFFERING_RING_SIZE = 62.dp

/**
 * The bar at rest. Thick enough to read at a glance — at 6 dp it was a hairline under buttons
 * several times its weight — and still plainly a bar rather than a button.
 */
private val PROGRESS_BAR_HEIGHT = 10.dp

/** The bar while scrubbing: thick enough to be a target for a fingertip. */
private val SCRUB_BAR_HEIGHT = 18.dp

/** The thumb on that bar. As tall as the bar, so it reads as a grip on it and not a dot above it. */
private val SCRUB_THUMB_SIZE = 18.dp

/** The glint's width along the bar: long enough to read as light, short enough not to be a marker. */
private val GLINT_WIDTH = 28.dp

/** One trip of the glint along the fill. Slow enough to read as breathing rather than flickering. */
private const val GLINT_PERIOD_MS = 1_800

/** The glint's strength at its brightest, over the bar's fill. */
private const val GLINT_ALPHA = 0.5f

/** The outline around the volume bar while it holds the bezel: enough to read as a mode. */
private val VOLUME_HELD_OUTLINE = 2.dp

/** Half: centres the glint on its position and rounds the bar's ends. */
private const val HALF = 0.5f

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

/**
 * Scroll pixels the bezel must report before the volume moves by one of the phone's steps.
 *
 * Rotary hardware reports a scroll distance rather than detents, and the distance a detent is
 * worth differs between a rotating bezel, a touch bezel and a crown. This number is the one thing
 * on this screen that can only be settled on a wrist: too small and a flick empties the volume,
 * too large and a deliberate turn does nothing.
 */
private const val ROTARY_PIXELS_PER_VOLUME_STEP = 48f

/**
 * The width of each of the Wear slider's two buttons, which the volume row's drag scale leaves out.
 *
 * The slider does not publish it; it is the touch target its buttons are built to.
 */
private val SLIDER_BUTTON_WIDTH = 48.dp
