package md.borisveriga.megapodcastplayer.feature.player

import android.content.res.Resources
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkBackdrop
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.NoteDialog
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.rememberHaptics
import md.borisveriga.megapodcastplayer.core.media.PlaybackError
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

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
    val haptics = rememberHaptics()
    // LocalResources rather than LocalContext.current.resources, so a configuration change
    // invalidates the read. Resolved here because `LaunchedEffect` runs outside composition.
    val resources = LocalResources.current

    LaunchedEffect(uiState.playback.error) {
        val playback = uiState.playback
        val error = playback.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error.toText(resources, playback.errorMessage))
        viewModel.onErrorShown()
    }

    // The queue can empty while the player is open — the last episode finishes, or the user
    // removes it. The sheet then stops being composed, and without this the state it left behind
    // would keep the navigation bar hidden with nothing on screen to bring it back.
    LaunchedEffect(uiState.isIdle) {
        if (uiState.isIdle) sheetState.collapse()
    }

    // Whether the list of this episode's moments is open. Held here, like the sleep timer's, for
    // the same reason: it is a question about this screen, and the moments themselves are saved.
    var momentsOpen by rememberSaveable { mutableStateOf(false) }

    if (momentsOpen) {
        MomentsSheet(
            moments = uiState.moments,
            onJumpTo = { moment ->
                momentsOpen = false
                viewModel.seekTo(moment.positionMs)
            },
            onDismiss = { momentsOpen = false },
        )
    }

    // Whether the speed sheet is open. Saved, so a rotation mid-drag does not close the picker and
    // strand the rate wherever the thumb happened to be. The rate itself is not held here: it is a
    // preference, and the sheet only ever writes it.
    var speedOpen by rememberSaveable { mutableStateOf(false) }

    if (speedOpen) {
        SpeedSheet(
            speed = uiState.playback.speed,
            onPreview = viewModel::previewSpeed,
            onCommit = viewModel::setSpeed,
            onDismiss = { speedOpen = false },
        )
    }

    // Whether the sleep timer sheet is open. A question about this screen rather than about the
    // app: the timer itself is in memory in `:core:media`, and a sheet reopened by a rotation would
    // be the app asking a question the user has already answered.
    var sleepTimerOpen by rememberSaveable { mutableStateOf(false) }

    if (sleepTimerOpen) {
        SleepTimerSheet(
            state = uiState.sleep,
            chapterRemainingMs = uiState.chapterRemainingMs,
            onArmAfter = viewModel::armSleepTimer,
            onArmEndOfEpisode = viewModel::armSleepAtEndOfEpisode,
            onCancel = viewModel::cancelSleepTimer,
            onDismiss = { sleepTimerOpen = false },
        )
    }

    // Only while a countdown is running: the sensor is the one thing here with a battery cost, and
    // "shake to add fifteen minutes" is meaningless with nothing to add to.
    ShakeToExtendEffect(
        enabled = uiState.sleep.remainingMs != null,
        onShake = viewModel::extendSleepTimer,
    )

    // The moment the note dialog is being written for, or null. Held here rather than in the view
    // model because it is a question about this screen — the moment itself is already saved, and
    // closing the dialog without typing anything is not a state worth surviving a process death.
    var noteFor by remember { mutableStateOf<SavedMoment?>(null) }

    LaunchedEffect(uiState.momentSaved) {
        val saved = uiState.momentSaved ?: return@LaunchedEffect
        // A tick first, because the button this confirms is meant to be pressed without looking —
        // walking, in a pocket, through a sleeve. The snackbar is for the case where the user *is*
        // looking; the hand is for the case where they are not.
        haptics.saved()
        // The action is offered rather than the dialog opened: the button exists to be pressed
        // without looking, and stopping to type is the exception, not the follow-through.
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(
                R.string.player_moment_saved,
                formatPosition(saved.positionMs),
            ),
            actionLabel = resources.getString(R.string.player_moment_add_note),
        )
        if (result == SnackbarResult.ActionPerformed) noteFor = saved
        viewModel.onMomentMessageShown()
    }

    // Dismissing stops playback and empties the queue, which is not a small thing to do on a
    // gesture — so it follows the app's own rule and is offered straight back. The undo restores
    // both the queue and the position it was dismissed at.
    LaunchedEffect(uiState.dismissed) {
        if (!uiState.dismissed) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(R.string.player_dismissed),
            actionLabel = resources.getString(R.string.player_dismiss_undo),
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDismiss()
        viewModel.onDismissMessageShown()
    }

    noteFor?.let { saved ->
        NoteDialog(
            title = stringResource(R.string.player_moment_note_title),
            placeholder = stringResource(R.string.player_moment_note_hint),
            confirmLabel = stringResource(R.string.player_moment_note_save),
            dismissLabel = stringResource(R.string.player_moment_note_cancel),
            onSave = { note ->
                viewModel.setMomentNote(saved.id, note)
                noteFor = null
            },
            onDismiss = { noteFor = null },
        )
    }

    val reserved = if (uiState.isIdle) 0.dp else collapsedPlayerHeight()

    Box(modifier = modifier.fillMaxSize()) {
        content(PaddingValues(bottom = reserved))

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
                onOpenSpeed = { speedOpen = true },
                onOpenSleepTimer = { sleepTimerOpen = true },
                onToggleDownload = viewModel::toggleCurrentDownload,
                onMarkMoment = viewModel::markMoment,
                onOpenMoments = { momentsOpen = true },
                onOpenQueue = onOpenQueue,
                onDismiss = viewModel::dismiss,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Last, so it draws over the sheet rather than under it. Every message here is about
        // something the user did *in* the player — marking a moment, a playback error — and the
        // expanded sheet fills the screen, so a host drawn before it would put the confirmation
        // for a button press behind the button that was pressed.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = reserved),
        )
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
 * @param onOpenSpeed opens the speed sheet.
 * @param onOpenSleepTimer opens the sleep timer sheet.
 * @param onToggleDownload starts, cancels, retries or deletes the episode's offline copy.
 * @param onMarkMoment saves a moment at the playhead.
 * @param onOpenMoments opens the list of this episode's moments.
 * @param onOpenQueue opens the queue screen.
 * @param onDismiss stops playback and puts the player away; what a downward pull on the collapsed
 *   bar commits to, and what the bar's spoken action does.
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
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onToggleDownload: () -> Unit,
    onMarkMoment: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenQueue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val collapsedHeight = collapsedPlayerHeight()
    val dismissLabel = stringResource(R.string.player_dismiss)

    // One tick each time the sheet commits to an end, whether it got there by a drag, a fling, a
    // tap or the back gesture. Keyed on the intent rather than the fraction: the sheet is settling
    // for the length of a spring, and the hand should be told when the decision was made.
    val haptics = rememberHaptics()
    var settledAt by remember { mutableStateOf(sheetState.targetValue) }
    LaunchedEffect(sheetState.targetValue) {
        if (sheetState.targetValue != settledAt) {
            settledAt = sheetState.targetValue
            haptics.snap()
        }
    }

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
        val travelPx = with(density) { (sheetHeight - collapsedHeight).toPx() }
        val flingPx = with(density) { FlingThreshold.toPx() }
        val dismissPx = with(density) { DismissThreshold.toPx() }

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

        // UNDISPATCHED so a delta runs to its `snapTo` before `launch` returns. Dispatched, the
        // last deltas of a gesture could land after `settle` had already started animating and
        // cancel it; the sheet would then stop wherever the finger left it, half open.
        val dragState = rememberDraggableState { delta ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sheetState.dragBy(delta, travelPx)
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(lerp(collapsedHeight, sheetHeight, progress))
                // The bar follows a downward pull and fades as it goes, so the gesture shows its
                // own progress rather than committing at an invisible threshold. Nothing moves
                // while the sheet is expanded: `pullDownPx` only accumulates against a collapsed one.
                .graphicsLayer {
                    translationY = sheetState.pullDownPx
                    alpha = 1f - (sheetState.pullDownPx / dismissPx).coerceIn(0f, 1f) *
                        DISMISS_FADE_DEPTH
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = !sheetState.isExpanded,
                    onDragStarted = { sheetState.onDragStarted() },
                    // Settled on the hoisted scope rather than the draggable's own coroutine.
                    // `settle` sets `targetValue` before it animates, which flips `enabled` above
                    // to false — and disabling a `draggable` tears down the very coroutine the
                    // animation is suspended in, stranding the sheet part-open.
                    onDragStopped = { velocity ->
                        scope.launch {
                            if (sheetState.consumePullDown(dismissPx)) {
                                onDismiss()
                            } else {
                                sheetState.settle(velocity, flingPx)
                            }
                        }
                    },
                )
                // Added and removed rather than merely disabled, which is not the same thing to a
                // screen reader: a `clickable` merges every descendant into one node whatever its
                // `enabled` says, so leaving it on the expanded player would fold the titles, the
                // timecodes and the scrubber into a single unreadable node the size of the screen.
                // Collapsed, that merge is exactly right — the bar is one thing that opens.
                .then(
                    if (sheetState.isExpanded) {
                        Modifier
                    } else {
                        Modifier
                            .clickable(
                                onClickLabel = stringResource(R.string.player_expand),
                                onClick = { scope.launch { sheetState.expand() } },
                            )
                            // The pull's spoken twin. A gesture without one is a control a
                            // TalkBack user does not have, and dismissing is the only way to stop
                            // playback from the bar.
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(dismissLabel) {
                                        onDismiss()
                                        true
                                    },
                                )
                            }
                    },
                ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = lerp(MegaPodcastPlayerTheme.shapes.sheetRadius, 0.dp, progress),
                topEnd = lerp(MegaPodcastPlayerTheme.shapes.sheetRadius, 0.dp, progress),
            ),
            tonalElevation = SheetTonalElevation,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (progress > 0f) {
                    // The cover, blurred into a wash behind everything else. Outside the inset
                    // padding, so the wash reaches the status bar the way a full-screen player's
                    // should; the content it sits behind is inset separately below. It fades in
                    // with the expanded body — a backdrop that arrived while the collapsed bar was
                    // still legible would put the bar's text on someone's artwork.
                    ArtworkBackdrop(
                        url = uiState.artworkUrl,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = expandedAlpha(progress) },
                        content = {},
                    )
                }

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
                            settings = uiState.settings,
                            onPlayPause = onPlayPause,
                            onSkipBack = onSkipBack,
                            onSkipForward = onSkipForward,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .height(collapsedHeight)
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
                            onOpenSpeed = onOpenSpeed,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onToggleDownload = onToggleDownload,
                            onMarkMoment = onMarkMoment,
                            onOpenMoments = onOpenMoments,
                            onOpenQueue = onOpenQueue,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = expandedAlpha(progress) },
                        )

                        SheetHeader(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .graphicsLayer { alpha = expandedAlpha(progress) }
                                .draggable(
                                    state = dragState,
                                    orientation = Orientation.Vertical,
                                    onDragStarted = { sheetState.onDragStarted() },
                                    onDragStopped = { velocity ->
                                        scope.launch { sheetState.settle(velocity, flingPx) }
                                    },
                                ),
                        )
                    }

                    TravellingArtwork(
                        artworkUrl = uiState.artworkUrl,
                        progress = progress,
                        heroSize = heroSize,
                        sheetWidth = sheetWidth,
                    )
                }
            }
        }
    }
}

/**
 * Turns a playback failure into a sentence.
 *
 * The player used to show *Playback problem: <exception message>*, and the exception's message is
 * written for whoever reads the bug report: "Source error", "Response code: 404". Three of the four
 * common causes have something the user can do about them, and none of those strings says what.
 *
 * [PlaybackError.UNKNOWN] keeps the player's own words, because when the app does not know what
 * happened, the least useful thing it can do is pretend it does.
 *
 * Takes [Resources] rather than being a `@Composable`, because the caller is a `LaunchedEffect`.
 *
 * @param resources resolved from the composition by the caller.
 * @param detail the player's own message, for the unknown case.
 * @return the text to show.
 */
private fun PlaybackError.toText(resources: Resources, detail: String?): String = when (this) {
    PlaybackError.NO_CONNECTION -> resources.getString(R.string.player_error_no_connection)

    PlaybackError.EPISODE_GONE -> resources.getString(R.string.player_error_episode_gone)

    PlaybackError.UNSUPPORTED_FORMAT ->
        resources.getString(R.string.player_error_unsupported_format)

    PlaybackError.YOUTUBE_UNAVAILABLE -> resources.getString(R.string.player_error_youtube)

    PlaybackError.UNKNOWN -> resources.getString(
        R.string.player_error,
        detail.orEmpty(),
    )
}

/**
 * The one piece of artwork, wherever the sheet currently has it.
 *
 * Its start and end geometry are computed rather than measured, because both layouts are fixed and
 * a measured position would always be a frame behind the finger. The two constants it depends on —
 * where [CollapsedPlayer] leaves its hole, and where [ExpandedPlayer] leaves its — live next to
 * those composables, so the gap and the artwork cannot drift apart silently.
 *
 * @param artworkUrl what to draw: the current chapter's image when it has one, else the episode's.
 * @param progress how open the sheet is.
 * @param heroSize the artwork's size when fully expanded.
 * @param sheetWidth the sheet's width, which centres the expanded artwork.
 * @param modifier layout modifier.
 */
@Composable
private fun TravellingArtwork(
    artworkUrl: String?,
    progress: Float,
    heroSize: Dp,
    sheetWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val artworkSize = lerp(ArtworkSize.Mini.dimension, heroSize, progress)
    val x = lerp(collapsedHorizontalPadding, (sheetWidth - heroSize) / 2, progress)
    val y = lerp(CollapsedArtworkTop, expandedHeaderHeight + expandedArtworkTopGap, progress)
    val radius = lerp(
        MegaPodcastPlayerTheme.shapes.artworkRadius,
        MegaPodcastPlayerTheme.shapes.artworkLargeRadius,
        progress,
    )

    PodcastArtwork(
        url = artworkUrl,
        // No named rung: the whole point is that the size is continuous between two of them.
        size = null,
        shape = RoundedCornerShape(radius),
        modifier = modifier
            .offset(x = x, y = y)
            .size(artworkSize),
    )
}

/**
 * The expanded player's grab strip: the grabber, and the drag target it advertises.
 *
 * Carries no title. The show's name is already under the artwork a few dp below, and repeating it
 * here would be the second of two labels a screen reader has to walk past to reach the controls.
 *
 * Carries no collapse button either. A chevron in the top-left corner was a second, smaller way to
 * do what the grabber, a downward drag anywhere on this strip and the back gesture all already do,
 * and it sat where a back arrow sits — which reads as "go back" on a surface that has nowhere to go
 * back to. The whole strip is the drag target, and the grabber is drawn large enough to say so.
 *
 * @param modifier layout modifier, carrying the drag gesture.
 */
@Composable
private fun SheetHeader(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(expandedHeaderHeight),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            // The pill is inset from the very top rather than tucked under it: a grabber pressed
            // against the status bar looks like an artefact of the cutout, not a handle.
            modifier = Modifier
                .padding(top = GrabberTopPadding)
                .size(width = GrabberWidth, height = GrabberHeight)
                .clip(MegaPodcastPlayerTheme.shapes.pill)
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
private val CollapsedArtworkTop: Dp
    @Composable get() = collapsedProgressHeight + collapsedVerticalPadding

/** Drag speed, per second, above which the direction of the flick decides where the sheet goes. */
private val FlingThreshold: Dp = 200.dp

/**
 * How far the collapsed bar has to be pulled down before letting go dismisses the player.
 *
 * Most of the bar's own height, so the gesture is unmistakably deliberate: an accidental downward
 * graze on the way to the navigation bar stops well short of it and springs back.
 */
private val DismissThreshold: Dp = 56.dp

/** How much of the bar's opacity the pull takes away by the time it commits. */
private const val DISMISS_FADE_DEPTH = 0.6f

private val SheetTonalElevation: Dp = 3.dp

/**
 * The grabber, drawn at the size of a thing meant to be grabbed.
 *
 * A 32x4 pill under 8dp of padding read as decoration; this is the only remaining affordance for
 * closing the sheet by hand, so it is worth the space.
 */
private val GrabberWidth: Dp = 48.dp
private val GrabberHeight: Dp = 6.dp
private val GrabberTopPadding: Dp = 16.dp
private const val GRABBER_ALPHA = 0.4f

/** The fraction by which the collapsed bar has completely faded out. */
private const val COLLAPSED_FADE_END = 0.35f

/**
 * The expanded player, in both schemes and at three font scales.
 *
 * Rendered through [PlayerSheet] rather than [ExpandedPlayer] directly, because the artwork, the
 * backdrop and the header strip belong to the sheet: a preview of the body alone would show a
 * layout that never appears, with a hole where the cover should be.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun ExpandedPlayerPreview() {
    MegaPodcastPlayerTheme {
        PlayerSheet(
            uiState = PlayerUiState(
                playback = previewPlayback,
                settings = PlaybackSettings(),
                moments = emptyList(),
            ),
            sheetState = rememberPlayerSheetState(PlayerSheetValue.Expanded),
            onPlayPause = {},
            onSeek = {},
            onSkipForward = {},
            onSkipBack = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onOpenSpeed = {},
            onOpenSleepTimer = {},
            onToggleDownload = {},
            onMarkMoment = {},
            onOpenMoments = {},
            onOpenQueue = {},
            onDismiss = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
