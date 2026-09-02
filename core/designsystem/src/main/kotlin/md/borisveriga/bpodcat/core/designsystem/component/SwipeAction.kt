package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One thing a row offers when it is swiped open.
 *
 * @property icon the glyph on the button.
 * @property label what it does; drawn under the icon and spoken by the accessibility action, so it
 *   has to read as an action rather than a noun — "Mark as played", not "Played".
 * @property containerColor the button's ground. Chosen by the call site rather than derived here,
 *   because the actions differ in weight: removing something is destructive and should say so,
 *   marking it played is not.
 * @property contentColor icon and label colour, drawn on [containerColor].
 * @property onClick invoked on tap. [SwipeActionsRow] closes the row first, so the handler never
 *   has to.
 */
data class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)
