package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner rounding, Expressive-scale.
 *
 * Every radius is a step larger than the Material baseline. That is the single most visible
 * decision in an Expressive design: softer containers read as friendlier, and they give the
 * shape-morphing components something worth morphing between.
 */
internal val megaPodcastPlayerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Shapes with a specific job, which the Material scale has no slot for.
 *
 * @property artwork list-sized cover art. Rounded enough to read as a squircle at 44–64dp.
 * @property artworkLarge header and hero cover art, where the same visual softness needs a
 *   larger radius to survive the larger box.
 * @property sheet the expanding player, rounded on its top edge only.
 * @property pill chips, badges and anything that should read as fully round.
 * @property artworkRadius the radius behind [artwork], and [artworkLargeRadius] the one behind
 *   [artworkLarge]. Both are exposed as raw dimensions for the one caller that cannot use the
 *   shapes: the expanding player interpolates between them frame by frame as its single piece of
 *   artwork grows, and a `Shape` cannot be interpolated. Anything that is not animating between
 *   the two sizes should use the shapes.
 * @property sheetRadius the radius behind [sheet], exposed for the same reason — the sheet's top
 *   corners flatten as it fills the screen.
 */
@Immutable
data class MegaPodcastPlayerShapeExtras(
    val artwork: CornerBasedShape,
    val artworkLarge: CornerBasedShape,
    val sheet: CornerBasedShape,
    val pill: Shape,
    val artworkRadius: Dp,
    val artworkLargeRadius: Dp,
    val sheetRadius: Dp,
)

private val artworkRadius = 14.dp
private val artworkLargeRadius = 28.dp
private val sheetRadius = 32.dp

internal val megaPodcastPlayerShapeExtras = MegaPodcastPlayerShapeExtras(
    artwork = RoundedCornerShape(artworkRadius),
    artworkLarge = RoundedCornerShape(artworkLargeRadius),
    sheet = RoundedCornerShape(topStart = sheetRadius, topEnd = sheetRadius),
    pill = CircleShape,
    artworkRadius = artworkRadius,
    artworkLargeRadius = artworkLargeRadius,
    sheetRadius = sheetRadius,
)
