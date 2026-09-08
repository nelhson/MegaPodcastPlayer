package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Named haptics, the way [Motion] names springs.
 *
 * The app had none at all. Every gesture in it — long-press to pick a row up, a two-tier swipe that
 * arms and then commits, a sheet that snaps to one of two ends, a button pressed blind to mark a
 * moment — was silent to the hand. Each of those is a moment where the screen has just changed its
 * mind about what will happen if you let go, and a tick is the difference between an interface that
 * is animated and one that is physical.
 *
 * Named by *what happened*, not by which platform constant it maps to, for the same reason
 * [Motion]'s factories are: "the row was picked up" is a decision the design system gets to make
 * once, and the constant behind it can change without every call site being rewritten.
 *
 * The platform already respects the user's touch-feedback setting and the view's own
 * `isHapticFeedbackEnabled`, so nothing here needs a switch of its own — unlike motion, which
 * Compose does not gate; see [LocalReduceMotion].
 *
 * @property feedback the platform sink, from [LocalHapticFeedback].
 */
@Immutable
class MegaPodcastPlayerHaptics internal constructor(private val feedback: HapticFeedback) {

    /**
     * A row has been picked up by a long press and now follows the finger.
     *
     * The one haptic the platform would arguably give for free — but only for `combinedClickable`,
     * and reordering is driven by `detectDragGesturesAfterLongPress`, which is silent.
     */
    fun pickUp() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** A row has been let go and dropped into its new place. */
    fun drop() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

    /**
     * A swipe has just crossed the threshold past which releasing commits.
     *
     * The backdrop lights up at the same instant. Both say the same thing, which is the point: the
     * user is most often looking at their thumb rather than at the row underneath it.
     */
    fun arm() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** A sheet or a drawer has settled at one of its ends. */
    fun snap() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /**
     * Something was saved by a press that shows nothing at the moment it happens.
     *
     * Marking a moment is the case this exists for: the button is meant to be pressed without
     * looking, walking, with the phone in a pocket, and until now the only confirmation was a
     * snackbar the user was not watching for.
     */
    fun saved() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

/**
 * The app's haptics, bound to the current composition.
 *
 * @return a stable handle; safe to hold across recompositions.
 */
@Composable
fun rememberHaptics(): MegaPodcastPlayerHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { MegaPodcastPlayerHaptics(feedback) }
}
