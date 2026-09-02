package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.Motion

/**
 * A list row that reveals action buttons when it is dragged from right to left.
 *
 * A reveal rather than Material's [androidx.compose.material3.SwipeToDismissBox], which the queue
 * used before this existed. A dismiss box can only carry *one* meaning per direction, and it fires
 * it the moment the row passes a threshold — fine for "delete", useless the moment a row has two
 * things to offer, and unforgiving either way, since the gesture that reveals the choice is the
 * same gesture that commits it. Here the swipe only opens the row; the tap that follows decides.
 *
 * The buttons sit behind the row and are uncovered by it, rather than sliding in from the edge, so
 * how far the row has moved is exactly how much of the choice the user can see.
 *
 * ## Accessibility
 *
 * A drag is invisible to a screen reader, so these actions must also be published as custom
 * accessibility actions — see [asAccessibilityActions]. That is not done here: this composable
 * wraps the row rather than being it, and a screen reader lands on the *row's* merged node, so the
 * actions have to go on the modifier the call site passes to `EpisodeRow`/`ShowRow`. Doing it here
 * would put them on a node TalkBack never visits.
 *
 * @param actions what to reveal, drawn left to right in the order given. An empty list disables the
 *   gesture entirely, which is the honest rendering of a row with nothing to offer.
 * @param modifier layout modifier.
 * @param enabled false to hold the row shut; used where another gesture owns the row, such as the
 *   downloads screen while a selection is in progress.
 * @param content the row itself.
 */
@Composable
fun SwipeActionsRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // How far the row has been pulled, in pixels: 0 shut, -revealWidth fully open. An Animatable
    // rather than a plain float because the release has to be animated and the drag has not — and
    // `snapTo` from the drag handler is what lets one value serve both.
    val offset = remember { Animatable(0f) }
    // Measured rather than assumed: the buttons are as wide as their labels, which are translated.
    var revealWidth by remember { mutableFloatStateOf(0f) }
    val isOpen = offset.value < -OPEN_EPSILON_PX
    val gesturesEnabled = enabled && actions.isNotEmpty()

    // A row held open while its gesture is taken away — the downloads screen entering selection
    // mode — would strand buttons the user can no longer close.
    LaunchedEffect(gesturesEnabled) {
        if (!gesturesEnabled) offset.animateTo(0f, Motion.smooth())
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .onSizeChanged { revealWidth = it.width.toFloat() }
                // A shut row's buttons are laid out — that is how their width is known — but they
                // sit underneath it, where nothing can reach them. Leaving them in the semantic
                // tree would put three unreachable controls behind every row in the list, so they
                // join it only once the row is actually open. What a screen reader uses instead is
                // the same actions published on the row itself; see [asAccessibilityActions].
                .then(if (isOpen) Modifier else Modifier.clearAndSetSemantics {}),
        ) {
            actions.forEach { action ->
                SwipeActionButton(
                    action = action,
                    onClick = {
                        scope.launch { offset.animateTo(0f, Motion.smooth()) }
                        action.onClick()
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        // Clamped rather than rubber-banded: the buttons are the whole extent of
                        // the gesture, and letting the row travel past them would only reveal the
                        // background behind it.
                        scope.launch {
                            offset.snapTo((offset.value + delta).coerceIn(-revealWidth, 0f))
                        }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = gesturesEnabled,
                    onDragStopped = { velocity ->
                        offset.animateTo(settleTarget(offset.value, velocity, revealWidth), Motion.smooth())
                    },
                ),
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
                        ) {
                            scope.launch { offset.animateTo(0f, Motion.smooth()) }
                        }
                        // The buttons behind it are the reachable version of everything this
                        // scrim covers; announcing it would only put an unnamed target in the way.
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/**
 * Where a released row should come to rest.
 *
 * Velocity wins over position, so a quick flick opens or closes the row whatever distance it
 * covered; a slow drag falls back to whichever end it is nearer. That ordering is what makes the
 * gesture feel like it is being thrown rather than measured.
 *
 * @param offset where the row is now, in pixels; negative is open.
 * @param velocity release velocity in pixels per second; negative is leftwards.
 * @param revealWidth how wide the buttons are, and therefore the open position.
 * @return the offset to animate to.
 */
internal fun settleTarget(offset: Float, velocity: Float, revealWidth: Float): Float = when {
    revealWidth <= 0f -> 0f
    abs(velocity) > FLING_VELOCITY_PX_PER_S -> if (velocity < 0f) -revealWidth else 0f
    offset < -revealWidth / 2f -> -revealWidth
    else -> 0f
}

/**
 * One revealed button.
 *
 * Icon over label rather than icon alone: three unlabelled squares behind a row are a guessing
 * game, and the label is also the only thing that distinguishes "mark as played" from "pin" at a
 * glance.
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

/**
 * The same actions, as things a screen reader can perform.
 *
 * Returned as a list rather than applied as a modifier so a call site with actions of its own — the
 * queue also offers "move up" and "move down" — can append to them instead of choosing between
 * them. They belong on whichever node merges the row's children, since that is what a screen reader
 * lands on.
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
 * How fast a release has to be to decide the outcome on its own, in pixels per second.
 *
 * Roughly a finger-width per tenth of a second on the Fold 7, which is comfortably above the speed
 * of someone dragging deliberately and comfortably below a flick.
 */
private const val FLING_VELOCITY_PX_PER_S = 400f

/** Below this many pixels of travel the row counts as shut, absorbing animation rounding. */
private const val OPEN_EPSILON_PX = 1f

/** Wide enough for a two-word label at the accessibility touch-target floor. */
private val BUTTON_MIN_WIDTH = 76.dp
