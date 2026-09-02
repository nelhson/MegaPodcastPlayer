package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion

/**
 * A list row with a two-tier right-to-left swipe: a short pull reveals buttons, a long one commits.
 *
 * Two tiers because a row has two kinds of thing to offer and they want opposite gestures. The
 * action reached for constantly — queueing an episode, clearing one out of the queue — should cost
 * one movement and no aim, so it is the one a long pull fires on release. Everything rarer wants to
 * be *chosen* rather than triggered, so a short pull only opens the row and the tap that follows
 * decides.
 *
 * That is also why this is not Material's [androidx.compose.material3.SwipeToDismissBox], which the
 * queue used before. A dismiss box carries one meaning per direction and fires it at a threshold,
 * so the gesture that reveals the choice is the gesture that commits it — fine for "delete", and
 * nothing else.
 *
 * The buttons sit behind the row and are uncovered by it, rather than sliding in from the edge, so
 * how far the row has moved is exactly how much of the choice the user can see. Pulled past them
 * they dissolve into [fullSwipeAction]'s backdrop, which stays muted until the row passes the
 * commit threshold and then lights up: the swipe says what it is about to do before it does it.
 *
 * ## Accessibility
 *
 * A drag is invisible to a screen reader, so every action here must also be published as a custom
 * accessibility action — see [asAccessibilityActions]. That is not done here: this composable wraps
 * the row rather than being it, and a screen reader lands on the *row's* merged node, so the
 * actions have to go on the modifier the call site passes to `EpisodeRow`/`ShowRow`. Doing it here
 * would put them on a node TalkBack never visits.
 *
 * @param actions what a short swipe reveals, drawn left to right in the order given. May be empty,
 *   which is the honest rendering of a row whose only offer is the full swipe.
 * @param modifier layout modifier.
 * @param fullSwipeAction what a swipe past half the row's width commits on release. Null confines
 *   the gesture to the reveal, and with it the distance the row can travel.
 * @param enabled false to hold the row shut; used where another gesture owns the row, such as a
 *   screen in selection mode.
 * @param containerColor what the row is drawn on. Painted here rather than left to [content],
 *   because it is what hides the buttons: a row with a transparent background shows every one of
 *   them through itself, permanently, and the gesture stops meaning anything. `ShowRow` is exactly
 *   such a row.
 * @param content the row itself.
 */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    fullSwipeAction: SwipeAction? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // How far the row has been pulled, in pixels: 0 shut, negative open.
    //
    // A plain float written synchronously, not an `Animatable`. Every drag event would otherwise
    // have to `launch` a coroutine to reach `snapTo`, and those land in whatever order the
    // dispatcher gets to them — including *after* the release animation has started, which cancels
    // it and strands the row mid-swipe.
    var offsetPx by remember { mutableFloatStateOf(0f) }
    // Both measured rather than assumed: the buttons are as wide as their labels, which are
    // translated, and the row is as wide as whatever pane it has been given.
    var revealWidth by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    // Read late, so a release cannot call a handler from a composition that has since gone.
    val currentFullSwipe by rememberUpdatedState(fullSwipeAction)

    val commitThreshold = if (fullSwipeAction != null) rowWidth * FULL_SWIPE_FRACTION else 0f
    val travelLimit = if (fullSwipeAction != null) rowWidth else revealWidth
    val pulled = -offsetPx
    val isOpen = pulled > OPEN_EPSILON_PX
    // Past the buttons is past the point of choosing between them.
    val isCommitting = fullSwipeAction != null && pulled > revealWidth
    val isArmed = commitThreshold > 0f && pulled >= commitThreshold
    val gesturesEnabled = enabled && (actions.isNotEmpty() || fullSwipeAction != null)

    // The settle animation, held so a new gesture can interrupt one still in flight.
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // Runs on the *composition's* scope rather than the drag's, and that is the whole point. A
    // committed swipe calls its action, the action changes state, the recomposition that follows
    // resets the `draggable` node — and with it the drag scope, cancelling any animation still
    // running inside it. The row would stay parked at the far edge showing the action it had just
    // performed. This scope outlives all of that.
    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = target,
                animationSpec = Motion.smooth(),
            ) { value, _ -> offsetPx = value }
        }
    }

    // A row held open while its gesture is taken away would strand buttons the user cannot close.
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled && offsetPx != 0f) settleTo(0f)
    }

    Box(modifier = modifier.onSizeChanged { rowWidth = it.width.toFloat() }) {
        fullSwipeAction?.let { action ->
            // Full-bleed and underneath everything, so it is uncovered by exactly as much as the
            // row has moved. Until the row passes the buttons it is entirely hidden behind them.
            FullSwipeBackdrop(action = action, isArmed = isArmed)
        }

        // The buttons are wrapped in a `matchParentSize` box rather than aligned directly, and that
        // is load-bearing rather than tidiness. A row lives in a `LazyColumn`, which measures its
        // items with an unbounded height, and `fillMaxHeight` against an unbounded constraint does
        // nothing at all — the buttons would size to their own icon and label and sit as a short
        // coloured patch beside a taller row. `matchParentSize` is measured after the content and
        // therefore knows the row's real height, which is what gives them something to fill.
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SwipeActionButtons(
                actions = actions,
                // Faded rather than removed: they are still what defines the reveal width, and the
                // fade is what makes the two tiers read as one gesture rather than two.
                isVisible = !isCommitting,
                isOpen = isOpen,
                onWidthChange = { revealWidth = it },
                onAction = { action ->
                    settleTo(0f)
                    action.onClick()
                },
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        // Clamped rather than rubber-banded: the travel limit is the whole extent
                        // of the gesture, and letting the row go past it would only reveal the
                        // background behind it.
                        offsetPx = (offsetPx + delta).coerceIn(-travelLimit, 0f)
                    },
                    orientation = Orientation.Horizontal,
                    enabled = gesturesEnabled,
                    // A settle still springing when the next gesture starts would fight the finger
                    // for the same value, and the finger has to win.
                    onDragStarted = { settleJob?.cancel() },
                    onDragStopped = { velocity ->
                        val outcome =
                            releaseOutcome(offsetPx, velocity, revealWidth, commitThreshold)
                        // Fired before the row is put back rather than after, so the snackbar is
                        // not held behind a spring. The row is animated shut rather than flung off
                        // the edge because its disappearance — when the action is one that removes
                        // it — comes from the data a beat later, and a row that flew away and then
                        // sprang back in order to vanish properly is worse than one that never
                        // left.
                        if (outcome == SwipeRelease.COMMIT) currentFullSwipe?.onClick?.invoke()
                        settleTo(if (outcome == SwipeRelease.OPEN) -revealWidth else 0f)
                    },
                )
                // Opaque, and the reason the buttons behind it are a secret until they are
                // uncovered. Drawn by this component rather than assumed of the content: `ShowRow`
                // paints nothing at all, which is what let the library's buttons show through
                // every row permanently.
                .background(containerColor),
        ) {
            content()

            // While the row is open its own tap belongs to closing it, not to playing an episode.
            // Without this the first tap after a swipe would both start playback and leave the
            // buttons showing, which is the worst reading of an ambiguous tap.
            if (isOpen) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { settleTo(0f) }
                        // The buttons behind it are the reachable version of everything this scrim
                        // covers; announcing it would only put an unnamed target in the way.
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/** What a released row should do, as decided by [releaseOutcome]. */
internal enum class SwipeRelease {
    /** Spring shut; the gesture asked for nothing. */
    SHUT,

    /** Rest with the buttons showing, waiting for a tap. */
    OPEN,

    /** Run the full-swipe action, then spring shut. */
    COMMIT,
}

/**
 * What a released row does, given how far and how fast it was pulled.
 *
 * The two tiers answer to different rules on purpose. The commit tier answers to distance alone: it
 * is the destructive one on both screens that use it, and a flick that never travelled far says
 * nothing about whether the user meant to fire it. Below that, velocity wins over position, so a
 * quick flick opens or closes the row whatever distance it happened to cover — which is what makes
 * the reveal feel like it is being thrown rather than measured.
 *
 * @param offset where the row is now, in pixels; negative is open.
 * @param velocity release velocity in pixels per second; negative is leftwards.
 * @param revealWidth how wide the buttons are, and therefore the open position.
 * @param commitThreshold how far the row must have travelled to fire the full-swipe action; 0 when
 *   there is no such action, which disables the tier entirely.
 * @return what to do.
 */
internal fun releaseOutcome(
    offset: Float,
    velocity: Float,
    revealWidth: Float,
    commitThreshold: Float,
): SwipeRelease = when {
    commitThreshold > 0f && -offset >= commitThreshold -> SwipeRelease.COMMIT

    // Nothing has been measured yet, or there are no buttons. Either way there is no open position
    // to settle into, and -0f is not one.
    revealWidth <= 0f -> SwipeRelease.SHUT

    abs(velocity) > FLING_VELOCITY_PX_PER_S ->
        if (velocity < 0f) SwipeRelease.OPEN else SwipeRelease.SHUT

    offset < -revealWidth / 2f -> SwipeRelease.OPEN

    else -> SwipeRelease.SHUT
}

/**
 * The buttons a short swipe reveals.
 *
 * @param actions what to draw, left to right.
 * @param isVisible false once the row has been pulled past them, which fades them out.
 * @param isOpen whether the row is actually open, which is what decides they are reachable.
 * @param onWidthChange reports the measured width; the row's resting-open position.
 * @param onAction invoked with the tapped action.
 * @param modifier layout modifier; the call site aligns this to the row's trailing edge.
 */
@Composable
private fun SwipeActionButtons(
    actions: List<SwipeAction>,
    isVisible: Boolean,
    isOpen: Boolean,
    onWidthChange: (Float) -> Unit,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonsAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "swipeActionButtons",
    )

    Row(
        modifier = modifier
            .fillMaxHeight()
            .onSizeChanged { onWidthChange(it.width.toFloat()) }
            .graphicsLayer { alpha = buttonsAlpha }
            // A shut row's buttons are laid out — that is how their width is known — but they sit
            // underneath it, where nothing can reach them. Leaving them in the semantic tree would
            // put unreachable controls behind every row in the list, so they join it only once the
            // row is actually open. What a screen reader uses instead is the same actions published
            // on the row itself; see [asAccessibilityActions].
            .then(if (isOpen && isVisible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        actions.forEach { action ->
            SwipeActionButton(action = action, onClick = { onAction(action) })
        }
    }
}

/**
 * The ground a full swipe uncovers, behind everything else.
 *
 * Muted until the row passes the commit threshold and then at full strength, which is the only
 * warning the user gets that letting go now will do something. Purely decorative: the action it
 * stands for is published on the row as an accessibility action, and this backdrop is never
 * reachable by touch.
 *
 * @param action the action about to be committed.
 * @param isArmed whether the row has passed the commit threshold.
 */
@Composable
private fun BoxScope.FullSwipeBackdrop(action: SwipeAction, isArmed: Boolean) {
    val backdropAlpha by animateFloatAsState(
        targetValue = if (isArmed) 1f else UNARMED_BACKDROP_ALPHA,
        animationSpec = Motion.fade(),
        label = "fullSwipeBackdrop",
    )

    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = backdropAlpha }
            .background(action.containerColor)
            .padding(horizontal = BPodcatTheme.spacing.lg)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = action.contentColor,
            )
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelSmall,
                color = action.contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = BPodcatTheme.spacing.xxs),
            )
        }
    }
}

/**
 * One revealed button.
 *
 * Icon over label rather than icon alone: unlabelled squares behind a row are a guessing game, and
 * the label is also the only thing that distinguishes "mark as played" from "remove" at a glance.
 *
 * @param action what the button does.
 * @param onClick invoked on tap; closes the row and then runs [SwipeAction.onClick].
 */
@Composable
private fun SwipeActionButton(action: SwipeAction, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = BUTTON_MIN_WIDTH)
            .background(action.containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = BPodcatTheme.spacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = action.icon,
            // The label is directly underneath, and the row publishes the same action by name for
            // a screen reader; a content description here would be the third copy.
            contentDescription = null,
            tint = action.contentColor,
            modifier = Modifier.size(BUTTON_ICON_SIZE),
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = action.contentColor,
            textAlign = TextAlign.Center,
            // Two lines, because the honest labels are two and three words — "Mark all played"
            // does not fit one line at this width and is not worth abbreviating into a guess.
            maxLines = BUTTON_LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = BPodcatTheme.spacing.xs),
        )
    }
}

/**
 * The same actions, as things a screen reader can perform.
 *
 * Returned as a list rather than applied as a modifier so a call site with actions of its own — the
 * queue also offers "move up" and "move down" — can append to them instead of choosing between
 * them. They belong on whichever node merges the row's children, since that is what a screen reader
 * lands on.
 *
 * Call sites include the full-swipe action here too: to a screen reader the two tiers are not two
 * tiers, they are simply everything the row can do.
 *
 * @return one action per [SwipeAction], in the order given.
 */
fun List<SwipeAction>.asAccessibilityActions(): List<CustomAccessibilityAction> = map { action ->
    CustomAccessibilityAction(action.label) {
        action.onClick()
        true
    }
}

/**
 * How far a row must be pulled, as a fraction of its own width, for a release to commit.
 *
 * Half. Far enough that it cannot be reached by a swipe meant to reveal the buttons — which rest at
 * a couple of button widths — and far enough to be unmistakably deliberate, which matters when the
 * action on the other side of it removes something.
 */
private const val FULL_SWIPE_FRACTION = 0.5f

/**
 * How fast a release has to be to decide the reveal on its own, in pixels per second.
 *
 * Roughly a finger-width per tenth of a second on the Fold 7, which is comfortably above the speed
 * of someone dragging deliberately and comfortably below a flick.
 */
private const val FLING_VELOCITY_PX_PER_S = 400f

/** Below this many pixels of travel the row counts as shut, absorbing animation rounding. */
private const val OPEN_EPSILON_PX = 1f

/** How present the full-swipe backdrop is before the row has passed the commit threshold. */
private const val UNARMED_BACKDROP_ALPHA = 0.4f

/** Wide enough for a two-line label at the accessibility touch-target floor. */
private val BUTTON_MIN_WIDTH = 84.dp

/** Slightly under the 24dp default, so the icon and its label read as one stacked unit. */
private val BUTTON_ICON_SIZE = 22.dp

/** "Mark all played" is three words and wraps; anything longer than this is the caller's problem. */
private const val BUTTON_LABEL_MAX_LINES = 2
