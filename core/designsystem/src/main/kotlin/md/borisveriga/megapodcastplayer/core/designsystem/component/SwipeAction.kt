package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One thing a row offers when it is swiped.
 *
 * The same type describes both tiers of the gesture — the buttons a short swipe reveals and the
 * single action a long one commits — because they are the same idea seen from different distances,
 * and a call site that had to fill in two shapes would be free to describe the same action twice in
 * two different ways.
 *
 * @property icon the glyph on the button, or on the backdrop for a full swipe.
 * @property label what it does; drawn under the icon and spoken by the accessibility action, so it
 *   has to read as an action rather than a noun — "Mark as played", not "Played".
 * @property containerColor the button's ground, or the backdrop's. Chosen by the call site rather
 *   than derived here, because the actions differ in weight: removing something is destructive and
 *   should say so, marking it played is not.
 * @property contentColor icon and label colour, drawn on [containerColor].
 * @property onClick invoked on tap, or on releasing a committed full swipe. [SwipeActionsRow]
 *   closes the row first, so the handler never has to.
 */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)
