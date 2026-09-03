package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The sizes the play/pause control is drawn at. */
enum class PlayPauseSize(internal val container: Dp, internal val glyph: Dp) {
    /** Inside a list row. */
    Small(40.dp, 22.dp),

    /** The collapsed player bar. */
    Medium(48.dp, 26.dp),

    /** The expanded player's primary control. */
    Hero(76.dp, 40.dp),
}
