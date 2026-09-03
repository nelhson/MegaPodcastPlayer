package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource

/**
 * Picks the skip-ahead glyph that matches the configured interval.
 *
 * Material ships numbered icons for 5, 10 and 30 seconds only. Showing "30" on a button that jumps
 * 45 seconds would be a small lie the user notices the first time they use it, so any other interval
 * falls back to the unnumbered [Icons.Rounded.FastForward].
 *
 * @param skipMs the configured skip-ahead distance.
 */
fun skipForwardIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Forward5
    10_000L -> Icons.Rounded.Forward10
    30_000L -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/**
 * Picks the skip-back glyph that matches the configured interval; see [skipForwardIcon].
 *
 * @param skipMs the configured skip-back distance.
 */
fun skipBackIcon(skipMs: Long): ImageVector = when (skipMs) {
    5_000L -> Icons.Rounded.Replay5
    10_000L -> Icons.Rounded.Replay10
    30_000L -> Icons.Rounded.Replay30
    else -> Icons.Rounded.FastRewind
}

/**
 * Describes a skip button for TalkBack.
 *
 * The glyph carries the number visually; the description has to say it out loud.
 *
 * @param skipMs the configured distance.
 * @param forward true for the skip-ahead button.
 * @return the spoken label, pluralised on the number of seconds.
 */
@Composable
fun skipContentDescription(skipMs: Long, forward: Boolean): String {
    val seconds = (skipMs / 1_000L).coerceAtLeast(1L).toInt()
    return pluralStringResource(
        id = if (forward) R.plurals.player_skip_forward else R.plurals.player_skip_back,
        count = seconds,
        seconds,
    )
}
