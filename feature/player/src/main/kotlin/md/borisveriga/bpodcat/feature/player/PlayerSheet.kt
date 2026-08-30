package md.borisveriga.bpodcat.feature.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.media.PlaybackState

/**
 * The app shell's player layer: content, then the sheet on top of it.
 *
 * Owns the player's view model so that the shell above it needs nothing but a [PlayerSheetState],
 * and reserves the height of the collapsed bar in [content]'s padding so a list's last row is not
 * left permanently underneath it — which is what the sheet's predecessor, a sibling in a `Column`,
 * got for free and an overlay does not.
 *
 * Renders no sheet at all when the player is idle, so a user who has not started anything never
 * sees an empty bar.
 *
 * @param sheetState how open the sheet is; hoisted because the navigation bar reacts to it too.
 * @param onOpenQueue opens the queue screen.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 * @param content the app's screens, given the padding the sheet occupies at rest.
 */
@Composable
fun PlayerSheetScaffold(
    sheetState: PlayerSheetState,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // LocalResources rather than LocalContext.current.resources, so a configuration change
    // invalidates the read. Resolved here because `LaunchedEffect` runs outside composition.
    val resources = LocalResources.current

    LaunchedEffect(uiState.playback.errorMessage) {
        val error = uiState.playback.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(resources.getString(R.string.player_error, error))
        viewModel.onErrorShown()
    }

    // The queue can empty while the player is open — the last episode finishes, or the user
    // removes it. The sheet then stops being composed, and without this the state it left behind
    // would keep the navigation bar hidden with nothing on screen to bring it back.
    LaunchedEffect(uiState.isIdle) {
        if (uiState.isIdle) sheetState.collapse()
    }

    val reserved = if (uiState.isIdle) 0.dp else collapsedPlayerHeight

    Box(modifier = modifier.fillMaxSize()) {
        content(PaddingValues(bottom = reserved))

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = reserved),
        )

        if (!uiState.isIdle) {
            PlayerSheet(
                uiState = uiState,
                sheetState = sheetState,
                onPlayPause = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onSkipForward = viewModel::skipForward,
                onSkipBack = viewModel::skipBack,
                onSkipToNext = viewModel::skipToNext,
                onSkipToPrevious = viewModel::skipToPrevious,
                onCycleSpeed = viewModel::cycleSpeed,
                onOpenQueue = onOpenQueue,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The player as one surface that grows from a bar into the whole screen.
 *
 * Deliberately not two navigation destinations and not a `SharedTransitionLayout`. Everything here
 * is a function of [PlayerSheetState.progress]: the sheet's height, its corner radius, which body
 * is visible, and — the part that carries the illusion — the position and size of a *single* piece
 * of artwork, which travels from the bar's leading edge to the middle of the screen. Both bodies
 * leave a hole for it rather than drawing their own, so there is never a moment where two copies
 * cross-fade past each other.
 *
 * Because it is one number, the gesture is continuous and reversible: let go halfway and the sheet
 * goes wherever it was nearer to, and a predictive back drags it down rather than dismissing it.
 *
 * The drag is deliberately split. Collapsed, the whole bar is draggable, because there is nothing
 * underneath it to scroll. Expanded, only the header strip is, so the body scrolls normally — a
 * sheet-wide drag over a scrolling column is the classic way this interaction ends up fighting
 * itself.
 *
 * @param uiState what to render.
 * @param sheetState how open the sheet is.
 * @param onPlayPause play/pause handler.
 * @param onSeek absolute-seek handler.
 * @param onSkipForward skip-ahead handler.
 * @param onSkipBack skip-back handler.
 * @param onSkipToNext next-episode handler.
 * @param onSkipToPrevious previous-episode handler.
 * @param onCycleSpeed speed-button handler.
 * @param onOpenQueue opens the queue screen.
 * @param modifier layout modifier; must be given the space the sheet may grow into.
 */
@Composable
fun PlayerSheet(
    uiState: PlayerUiState,
    sheetState: PlayerSheetState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val progress = sheetState.progress
        // Read once, here: `maxWidth`/`maxHeight` belong to this scope, and the sheet's own Box
        // and Surface scopes below shadow the receiver they come from.
        val sheetWidth = maxWidth
        val sheetHeight = maxHeight
        val heroSize = sheetWidth * HERO_ARTWORK_WIDTH_FRACTION
        // Scaled by the fraction rather than switched at the ends. Collapsed, the sheet sits inside
        // the navigation suite's content area and needs no inset of its own; expanded, it has taken
        // the whole screen and has to clear the status bar and the gesture bar itself. Anything in
        // between is a real state the user can hold the sheet at, so the padding has to be too.
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigationBarBottom = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()
        val travelPx = with(density) { (sheetHeight - collapsedPlayerHeight).toPx() }
        val flingPx = with(density) { FlingThreshold.toPx() }

        // The back gesture drags the sheet down instead of dismissing it, and letting go mid-way
        // puts it back — which is the whole point of predictive back, and only possible because
        // the sheet is a fraction rather than a destination.
        PredictiveBackHandler(enabled = sheetState.isExpanded) { events ->
            try {
                events.collect { event -> sheetState.seekTo(1f - event.progress) }
                sheetState.collapse()
            } catch (abandoned: CancellationException) {
                // Letting go before the gesture completes is a normal outcome, not a failure: the
                // exception is how `PredictiveBackHandler` reports it, and the handler's coroutine
                // is still live, which is what lets the sheet animate back open.
                sheetState.expand()
            }
        }

        val dragState = rememberDraggableState { delta ->
            scope.launch { sheetState.dragBy(delta, travelPx) }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(lerp(collapsedPlayerHeight, sheetHeight, progress))
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = !sheetState.isExpanded,
                    onDragStopped = { velocity -> sheetState.settle(velocity, flingPx) },
                )
                .clickable(
                    enabled = !sheetState.isExpanded,
                    onClickLabel = stringResource(R.string.player_expand),
                    onClick = { scope.launch { sheetState.expand() } },
                ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = lerp(BPodcatTheme.shapes.sheetRadius, 0.dp, progress),
                topEnd = lerp(BPodcatTheme.shapes.sheetRadius, 0.dp, progress),
            ),
            tonalElevation = SheetTonalElevation,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = statusBarTop * progress,
                        bottom = navigationBarBottom * progress,
                    ),
            ) {
                if (progress < 1f) {
                    CollapsedPlayer(
                        playback = uiState.playback,
                        onPlayPause = onPlayPause,
                        onSkipForward = onSkipForward,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .height(collapsedPlayerHeight)
                            .graphicsLayer { alpha = collapsedAlpha(progress) },
                    )
                }

                if (progress > 0f) {
                    ExpandedPlayer(
                        uiState = uiState,
                        heroArtworkSize = heroSize,
                        onPlayPause = onPlayPause,
                        onSeek = onSeek,
                        onSkipForward = onSkipForward,
                        onSkipBack = onSkipBack,
                        onSkipToNext = onSkipToNext,
                        onSkipToPrevious = onSkipToPrevious,
                        onCycleSpeed = onCycleSpeed,
                        onOpenQueue = onOpenQueue,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = expandedAlpha(progress) },
                    )

                    SheetHeader(
                        onCollapse = { scope.launch { sheetState.collapse() } },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer { alpha = expandedAlpha(progress) }
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    sheetState.settle(velocity, flingPx)
                                },
                            ),
                    )
                }

                TravellingArtwork(
                    playback = uiState.playback,
                    progress = progress,
                    heroSize = heroSize,
                    sheetWidth = sheetWidth,
                )
            }
        }
    }
}

/**
 * The one piece of artwork, wherever the sheet currently has it.
 *
 * Its start and end geometry are computed rather than measured, because both layouts are fixed and
 * a measured position would always be a frame behind the finger. The two constants it depends on —
 * where [CollapsedPlayer] leaves its hole, and where [ExpandedPlayer] leaves its — live next to
 * those composables, so the gap and the artwork cannot drift apart silently.
 *
 * @param playback supplies the artwork URL.
 * @param progress how open the sheet is.
 * @param heroSize the artwork's size when fully expanded.
 * @param sheetWidth the sheet's width, which centres the expanded artwork.
 * @param modifier layout modifier.
 */
@Composable
private fun TravellingArtwork(
    playback: PlaybackState,
    progress: Float,
    heroSize: Dp,
    sheetWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val artworkSize = lerp(ArtworkSize.Mini.dimension, heroSize, progress)
    val x = lerp(collapsedHorizontalPadding, (sheetWidth - heroSize) / 2, progress)
    val y = lerp(CollapsedArtworkTop, expandedHeaderHeight + expandedArtworkTopGap, progress)
    val radius = lerp(
        BPodcatTheme.shapes.artworkRadius,
        BPodcatTheme.shapes.artworkLargeRadius,
        progress,
    )

    PodcastArtwork(
        url = playback.artworkUrl,
        // No named rung: the whole point is that the size is continuous between two of them.
        size = null,
        shape = RoundedCornerShape(radius),
        modifier = modifier
            .offset(x = x, y = y)
            .size(artworkSize),
    )
}

/**
 * The expanded player's grab strip: a collapse button and the drag target.
 *
 * Carries no title. The show's name is already under the artwork a few dp below, and repeating it
 * here would be the second of two labels a screen reader has to walk past to reach the controls.
 *
 * @param onCollapse closes the sheet.
 * @param modifier layout modifier, carrying the drag gesture.
 */
@Composable
private fun SheetHeader(
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(expandedHeaderHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.padding(start = BPodcatTheme.spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_close),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = BPodcatTheme.spacing.sm)
                .size(width = GrabberWidth, height = GrabberHeight)
                .clip(BPodcatTheme.shapes.pill)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GRABBER_ALPHA)),
        )
    }
}

/**
 * How opaque the collapsed bar is.
 *
 * Gone well before halfway, so the bar's text is not still legible under the expanded player's.
 */
private fun collapsedAlpha(progress: Float): Float =
    (1f - progress / COLLAPSED_FADE_END).coerceIn(0f, 1f)

/** How opaque the expanded body is; it starts appearing only once the bar has gone. */
private fun expandedAlpha(progress: Float): Float =
    ((progress - COLLAPSED_FADE_END) / (1f - COLLAPSED_FADE_END)).coerceIn(0f, 1f)

/** Where [CollapsedPlayer] leaves the top of its artwork hole: under the progress line. */
private val CollapsedArtworkTop: Dp = collapsedProgressHeight + collapsedVerticalPadding

/** Drag speed, per second, above which the direction of the flick decides where the sheet goes. */
private val FlingThreshold: Dp = 200.dp

private val SheetTonalElevation: Dp = 3.dp
private val GrabberWidth: Dp = 32.dp
private val GrabberHeight: Dp = 4.dp
private const val GRABBER_ALPHA = 0.4f

/** The fraction by which the collapsed bar has completely faded out. */
private const val COLLAPSED_FADE_END = 0.35f
