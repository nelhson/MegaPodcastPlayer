package md.borisveriga.bpodcat.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Before this existed the app contained 95 hand-written `.dp` literals, and the horizontal
 * padding of a screen was 12, 16 or 20dp depending on which file you opened. The point of naming
 * the steps is not that `lg` is shorter to type than `16.dp` — it is that the *layout* constants
 * below turn "how much padding does a screen edge get" into one decision instead of ninety-five.
 *
 * @property xxs hairline gaps: between a label and the icon it belongs to.
 * @property xs tight gaps inside a single control.
 * @property sm gaps between closely related elements, e.g. a title and its metadata line.
 * @property md the default gap between distinct elements in a row.
 * @property lg the default screen and card padding.
 * @property xl gaps between sections.
 * @property xxl generous padding, e.g. around an empty state.
 * @property xxxl the largest step; hero layouts only.
 * @property screenHorizontal padding from the left and right screen edges. Every screen.
 * @property listItemVertical vertical padding inside one list row.
 * @property sectionGap vertical gap between two sections of a screen.
 * @property minTouchTarget the accessibility floor for anything tappable.
 */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val screenHorizontal: Dp = 16.dp,
    val listItemVertical: Dp = 12.dp,
    val sectionGap: Dp = 24.dp,
    val minTouchTarget: Dp = 48.dp,
)

internal val defaultSpacing = Spacing()
