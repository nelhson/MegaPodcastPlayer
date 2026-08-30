package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sizes artwork is drawn at, named.
 *
 * Callers used to pass their own `Modifier.size(56.dp)` and their own corner radius, which is how
 * the app ended up with six artwork sizes and two different roundings for what is visually the
 * same element. Naming the rungs makes "the size a list row uses" one decision.
 *
 * @property dimension the edge length of the square.
 */
enum class ArtworkSize(val dimension: Dp) {
    /** The collapsed player bar. */
    Mini(44.dp),

    /** Standard list rows: downloads, search results, the queue. */
    Row(56.dp),

    /** The library list, where artwork is the primary way a show is recognised. */
    RowLarge(64.dp),

    /** A show's detail header. */
    Header(96.dp),
}
