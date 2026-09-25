package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Title
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
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
        onMarkMoment = viewModel::markMoment,
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
 * One column, top to bottom: what is playing — flanked by the moment button and the volume toggle,
 * with the volume bar taking the title's place while it is open — then the scrubber, the transport,
 * previous, speed and next, the phone's queue, and what is downloaded on the phone. The moment and
 * the volume sit up with the title rather than in rows of their own, which is what buys the
 * transport its place on the first screen: a round face holds five rows of thumb-sized targets
 * only if the fifth is at its bottom edge, and that row is the one used sitting down. The transport
 * is the one thing here that has to be under the thumb within a second
 * of raising the wrist, and it is: the two lists are the only parts whose length the phone decides,
 * and they are last in the column, so however long they grow nothing above them moves. The screen
 * was once split into two pages to keep a growing list from pushing pause off the bottom; putting
 * the lists last does the same with nothing to swipe. This is the whole app — there is no second
 * screen, and the tile that used to be a smaller copy of this one is gone.
 *
 * @param uiState what to draw.
 * @param onTogglePlayPause invoked by the centre transport button.
 * @param onSkipForward invoked by the skip-ahead button.
 * @param onSkipBack invoked by the skip-back button.
 * @param onSkipToNext invoked by the next-episode button.
 * @param onSkipToPrevious invoked by the previous-episode button.
 * @param onCycleSpeed invoked by the speed button.
 * @param onMarkMoment invoked by the mark-a-moment button.
 * @param onPlayOnPhone invoked with the episode id when an episode row is tapped.
 * @param onQueueOnPhone invoked with the episode id by a downloaded row's queue button.
 * @param onRetry invoked when the user retries a failed connection.
 * @param position where playback has reached, read only by the bar and only when it draws. A
 *   lambda rather than a value so that the clock, which moves this once a second, is not a reason
 *   for the list to recompose; see [WatchPlayerUiState] for why that matters.
 * @param onBeginScrub invoked when the user takes hold of the progress bar.
 * @param onScrubBy invoked as they move it, with a signed offset in milliseconds.
 * @param onCommitScrub invoked when they settle, which is what actually seeks.
 * @param onBeginVolume invoked when the user takes hold of the volume bar.
 * @param onEndVolume invoked when they let go of it.
 * @param onSetVolume invoked with an absolute level by the row's own minus and plus buttons.
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
    onMarkMoment: () -> Unit = {},
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

    // Read here rather than where it is used, which is the waveform inside the header. The header
    // is a list item, so reading it there meant a `Settings.Global` read while composing and a
    // ContentObserver registered and unregistered as the effect was applied and disposed — three
    // trips to the system server, on the main thread, every time the header scrolled out of view
    // and back. It is one reading per screen, and this is where a screen's readings belong.
    val reduceMotion = rememberReduceMotion()

    // A scrolling list rather than a fixed layout even for the controls alone, because at 200 %
    // font scale five items do not fit a round screen and the alternative to scrolling is clipping.
    ScreenScaffold(
        scrollState = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (uiState.lastCommandFailed) {
                item(key = "failed", contentType = "note") {
                    CommandFailedNote(modifier = Modifier.scrollTransform(this, spec))
                }
            }

            if (uiState.showsControls) {
                item(key = "top", contentType = "top") {
                    NowPlayingTop(
                        uiState = uiState,
                        reduceMotion = reduceMotion,
                        onMarkMoment = onMarkMoment,
                        onBeginVolume = onBeginVolume,
                        onEndVolume = onEndVolume,
                        onSetVolume = onSetVolume,
                        onAdjustVolumeBy = onAdjustVolumeBy,
                        modifier = Modifier.scrollTransform(this, spec),
                    )
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
 * because half the things in this column — the transport rows, the waveform header — are not
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
 * What is playing, with the two buttons that are pressed without looking either side of it.
 *
 * The first row is the moment button, the show and the volume toggle; under it sits the episode's
 * title — or, while the toggle is on, the volume bar in the title's place. The two share a slot
 * rather than stacking because the volume is a thing adjusted and put away, and every row it took
 * permanently was a row the transport was pushed further down the wrist. The bar is shown exactly
 * while [WatchPlayerUiState.isAdjustingVolume] is true, so the view model's own release timer is
 * what brings the title back: a turn-and-forget leaves the screen as it found it.
 *
 * There is no cover art and no animation here on purpose. The art answered "which show is this"
 * badly — third-party imagery behind a title needs a scrim heavy enough that little of the picture
 * survives — and a colour answers it at the same glance for nothing; see [showAccent]. Whether the
 * phone is playing is said by the progress bar's glint, where the eye already goes for "how far".
 *
 * @param uiState what to draw.
 * @param reduceMotion whether the wearer has asked for no animations; read once above the list.
 * @param onMarkMoment marks a moment at the phone's playhead.
 * @param onBeginVolume opens the volume bar and hands it the bezel.
 * @param onEndVolume closes it, giving the bezel back to the list.
 * @param onSetVolume sets an absolute level; what the bar's own buttons report.
 * @param onAdjustVolumeBy moves by whole steps; what the bezel and a drag report.
 * @param modifier applied to the block; carries the column's scroll transformation.
 */
@Composable
private fun NowPlayingTop(
    uiState: WatchPlayerUiState,
    reduceMotion: Boolean,
    onMarkMoment: () -> Unit,
    onBeginVolume: () -> Unit,
    onEndVolume: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onAdjustVolumeBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val volumeOpen = uiState.isAdjustingVolume && uiState.canSetVolume

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The shape goes to `background` rather than to a `clip` above it: one node that draws
            // the wash within the shape, instead of a clip node the wash is then drawn through.
            .background(brush = wash, shape = MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MomentIconButton(saved = uiState.momentSaved, reduceMotion = reduceMotion, onClick = onMarkMoment)

            // The show sits between the two buttons, on the one line of the top row that would
            // otherwise be empty: at the top of a round screen the middle is the widest part left.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.snapshot.showTitle.isNotBlank()) {
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

            if (uiState.canSetVolume) {
                VolumeToggleButton(
                    open = volumeOpen,
                    onOpen = onBeginVolume,
                    onClose = onEndVolume,
                )
            } else {
                // Holds the toggle's place, so the show stays centred under the screen's middle
                // rather than under the middle of whatever is left beside the moment button.
                Spacer(modifier = Modifier.size(IconButtonDefaults.SmallButtonSize))
            }
        }

        AnimatedContent(
            targetState = volumeOpen,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "title-or-volume",
        ) { showVolume ->
            if (showVolume) {
                VolumePanel(
                    uiState = uiState,
                    onSetVolume = onSetVolume,
                    onAdjustVolumeBy = onAdjustVolumeBy,
                )
            } else {
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
    }
}

/**
 * The button that keeps the spot the wearer is listening to.
 *
 * Small and in the top corner rather than a full-width row of its own: the full-width button cost
 * the transport its place on the first screen. It is still the first thing the thumb meets coming
 * off the bezel, and it keeps the standard touch target however small it is drawn.
 *
 * The icon is the confirmation. A watch has no snackbar and marking leaves nothing behind, so for
 * a few seconds the glyph turns into a saved bookmark, the button fills with the accent and gives
 * one small pop; without that the wearer presses again to check, which is why the phone folds two
 * marks a few seconds apart into one. The buzz says the same thing to a wrist nobody is looking at.
 *
 * @param saved true while the confirmation is showing.
 * @param reduceMotion whether to skip the pop; the colour and glyph still change.
 * @param onClick marks a moment at the playhead.
 * @param modifier applied to the button.
 */
@Composable
private fun MomentIconButton(
    saved: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    // Keyed on the confirmation rather than fired from the click, because the two are not the same
    // event: a mark that could neither be delivered nor queued sets nothing, and a wrist that
    // buzzed anyway would have said the moment was kept when it was not.
    LaunchedEffect(saved) {
        if (!saved) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (!reduceMotion) {
            pop.animateTo(MOMENT_POP_SCALE, animationSpec = tween(MOMENT_POP_MS))
            pop.animateTo(1f, animationSpec = tween(MOMENT_POP_MS))
        }
    }

    val tonal = IconButtonDefaults.filledTonalIconButtonColors()
    val container by animateColorAsState(
        targetValue = if (saved) MaterialTheme.colorScheme.primary else tonal.containerColor,
        label = "moment-container",
    )
    val content by animateColorAsState(
        targetValue = if (saved) MaterialTheme.colorScheme.onPrimary else tonal.contentColor,
        label = "moment-content",
    )

    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = container,
            contentColor = content,
        ),
        modifier = modifier
            .touchTargetAwareSize(IconButtonDefaults.SmallButtonSize)
            // Read in the layer block, so the pop redraws the button rather than recomposing it.
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            },
    ) {
        Icon(
            imageVector = if (saved) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
            contentDescription = stringResource(
                if (saved) R.string.watch_moment_saved else R.string.watch_moment_mark,
            ),
            modifier = Modifier.size(IconButtonDefaults.iconSizeFor(IconButtonDefaults.SmallButtonSize)),
        )
    }
}

/**
 * The switch between the episode's title and the volume bar that takes its place.
 *
 * One button that becomes its own way back, rather than a volume button and a close button: the
 * thumb that opened the bar is already where the way out is. While the bar is open the glyph is the
 * title's, which says what pressing it returns to rather than what is currently showing.
 *
 * @param open true while the volume bar is in the title's place.
 * @param onOpen shows the bar and hands it the bezel.
 * @param onClose puts the title back.
 * @param modifier applied to the button.
 */
@Composable
private fun VolumeToggleButton(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (open) {
        IconButtonDefaults.filledIconButtonColors()
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }

    FilledTonalIconButton(
        onClick = if (open) onClose else onOpen,
        colors = colors,
        modifier = modifier.touchTargetAwareSize(IconButtonDefaults.SmallButtonSize),
    ) {
        Icon(
            imageVector = if (open) Icons.Rounded.Title else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(
                if (open) R.string.watch_volume_hide else R.string.watch_volume_show,
            ),
            modifier = Modifier.size(IconButtonDefaults.iconSizeFor(IconButtonDefaults.SmallButtonSize)),
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The phone's media volume: a bar with a step either side of it, in the title's place.
 *
 * Built on Wear Material3's [Slider], which is already the shape of this control — two 48 dp
 * buttons with a bar between them, its own detent haptics, and range semantics that let TalkBack
 * read the level without this screen announcing a bare number at it. What the slider does not have
 * is the bezel, because rotary input goes to whoever holds focus and a slider does not ask for it
 * — nor a drag: its bar is drawn, not held, so the finger's movement along it is added here too,
 * on the scale the bar suggests. See [bankVolumeSteps] for how either distance becomes steps.
 *
 * It is composed only while the volume is open, and opening it is what holds the bezel — so the
 * focus is claimed the moment the panel appears, and the wearer who pressed the volume toggle can
 * turn straight away. There is no tap-to-hold on the bar any more: the toggle is that tap, and the
 * toggle, or a few still seconds, is the way out. The buttons and the drag need neither, which is
 * what keeps the bar usable for a wearer who never discovers the bezel.
 *
 * The one thing it does not copy from the scrubber is the pause before the value is sent. A seek
 * is a jump to somewhere you cannot hear until you arrive; a volume change is audible while the
 * finger is still moving, so every step goes out at once and the throttling happens behind it.
 *
 * @param uiState what to draw, including the level.
 * @param onSetVolume sets an absolute level; what the slider's own buttons report.
 * @param onAdjustVolumeBy moves by whole steps; what the bezel and a drag along the bar report.
 * @param modifier applied to the panel.
 */
@Composable
private fun VolumePanel(
    uiState: WatchPlayerUiState,
    onSetVolume: (Int) -> Unit,
    onAdjustVolumeBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    // Rotary arrives as scroll pixels, not as detents, so the pixels are banked until they add up
    // to a step. Kept outside composition: a turn moves the volume, and must not also recompose
    // the row that is reading it.
    val turned = remember { mutableFloatStateOf(0f) }
    // The same bank for a finger, kept apart from the bezel's because the two are worth different
    // distances per step and a remainder from one means nothing to the other.
    val dragged = remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }

    // Dragging the length of the bar covers the phone's whole scale, so the fill follows the
    // finger — which is what the bar suggests and what the scrubber already does with an episode.
    // The bar is the row less the button at either end of it.
    val maxVolume = uiState.snapshot.maxVolume
    val buttonsPx = with(LocalDensity.current) { (SLIDER_BUTTON_WIDTH * 2).toPx() }
    val barWidthPx = (rowWidthPx - buttonsPx).coerceAtLeast(0f)
    val dragPixelsPerStep: Float = if (maxVolume > 0) barWidthPx / maxVolume else 0f

    // Whether a move by [steps] changes anything. At an end stop it does not, and a tick for a
    // step that was not taken is the hand being told something that did not happen.
    val level = uiState.volumeLevel
    val moves = { steps: Int -> (level + steps).coerceIn(0, maxVolume) != level }

    LaunchedEffect(Unit) {
        // Rotary events go to whatever holds focus, so the panel claims it as it appears. Nothing
        // gives it back explicitly: the list reclaims it as the panel leaves composition.
        focusRequester.requestFocus()
    }

    val volumeLabel = stringResource(R.string.watch_volume_active)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    val banked = bankVolumeSteps(
                        bankedPx = turned.floatValue + event.verticalScrollPixels,
                        pixelsPerStep = ROTARY_PIXELS_PER_VOLUME_STEP,
                    )
                    turned.floatValue = banked.remainderPx
                    if (banked.steps != 0) {
                        // Same sign as the scrubber, which turns the same bezel on the same
                        // screen: forward is later there and louder here. Two controls that
                        // answered one turn in opposite directions would be a coin toss.
                        onAdjustVolumeBy(banked.steps)
                        // The slider buzzes for its own buttons; the bezel has to be given the
                        // same tick by hand, or half the control would be silent to the hand.
                        if (moves(banked.steps)) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                    true
                }
                .onSizeChanged { rowWidthPx = it.width }
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
                            if (moves(banked.steps)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                    },
                    orientation = Orientation.Horizontal,
                    // A new drag starts from nothing: what the last one left over was distance
                    // along a gesture that has ended.
                    onDragStarted = { dragged.floatValue = 0f },
                )
                .semantics { contentDescription = volumeLabel },
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
 * mid-bar that would look like a marker. The phase and the progress are both read inside the draw
 * lambda, never in composition: each frame repaints this layer and nothing else.
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
    val light = MaterialTheme.colorScheme.onSurface.copy(alpha = GLINT_ALPHA)

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

        val band = GLINT_WIDTH.toPx()
        // Starts wholly before the bar and ends wholly past the fill, so the band slides in and out
        // rather than popping into being at the left edge.
        val centre = -band * HALF + phase.value * (filled + band)
        val radius = size.height * HALF
        val clip = Path().apply {
            addRoundRect(RoundRect(0f, 0f, filled, size.height, CornerRadius(radius)))
        }
        clipPath(clip) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, light, Color.Transparent),
                    startX = centre - band * HALF,
                    endX = centre + band * HALF,
                ),
                topLeft = Offset(centre - band * HALF, 0f),
                size = Size(band, size.height),
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
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onSkipBack) {
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

/** The glint's width along the bar: long enough to read as light, short enough not to be a marker. */
private val GLINT_WIDTH = 28.dp

/** One trip of the glint along the fill. Slow enough to read as breathing rather than flickering. */
private const val GLINT_PERIOD_MS = 1_800

/** The glint's strength at its brightest, over the bar's accent fill. */
private const val GLINT_ALPHA = 0.5f

/** How far the moment button swells when a save is confirmed, and how long each half of it takes. */
private const val MOMENT_POP_SCALE = 1.15f
private const val MOMENT_POP_MS = 140

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
