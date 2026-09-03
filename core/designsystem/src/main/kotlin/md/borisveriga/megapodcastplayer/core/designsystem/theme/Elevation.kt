package md.borisveriga.megapodcastplayer.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tonal elevation levels, named.
 *
 * Material 3 expresses elevation mostly as a tonal shift rather than a shadow, and the levels
 * are a fixed six-step scale. Naming them stops the app from doing what it did before: a single
 * `tonalElevation = 3.dp` on the mini player and nothing anywhere else, with no way to tell
 * whether 3 was chosen or copied.
 *
 * @property level0 flush with the surface. The default for list content.
 * @property level1 barely raised: cards resting on a screen.
 * @property level2 the mini player bar, search bars, raised chrome.
 * @property level3 menus and the expanded player over content.
 * @property level4 navigation drawers.
 * @property level5 the highest step; dialogs over a busy screen.
 */
@Immutable
data class Elevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
)

internal val defaultElevation = Elevation()
