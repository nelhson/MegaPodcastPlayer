package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * A skip button's glyph: the circular arrow, with the number of seconds inside it.
 *
 * Drawn rather than picked from a set. Material ships numbered icons for 5, 10 and 30 seconds only,
 * and the settings screen offers 15, 45 and 60 as well — those used to fall back to the unnumbered
 * `FastRewind` and `FastForward`, which are two stacked triangles, which is the glyph every player
 * ever made uses for *previous track* and *next track*. A button that jumps fifteen seconds and
 * looks like the one that abandons the episode is worse than one that says the wrong number.
 *
 * So the arc and the numeral are separate here: [Icons.Rounded.Replay] is exactly the arc Material's
 * own `Replay30` is built on, and the number goes inside it. Forward is the same arc mirrored, which
 * is how `Forward30` relates to `Replay30`; the numeral is drawn after the mirroring, so it is not
 * mirrored with it.
 *
 * The numeral's size is fixed in density pixels rather than scaled text units. It is not text the
 * user reads a sentence of — it is part of a glyph, and at 200 % font scale it would otherwise burst
 * the arc that contains it. Nothing is lost: what the button does is spoken in full by its content
 * description, which is where a font-scale setting is a request to be told more clearly.
 *
 * @param skipMs the configured distance.
 * @param forward true for the skip-ahead button, which mirrors the arc.
 * @param modifier layout modifier.
 * @param size the glyph's side; the numeral is sized from it.
 */
@Composable
fun SkipGlyph(
    skipMs: Long,
    forward: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = SKIP_GLYPH_DEFAULT_SIZE,
) {
    val seconds = skipSeconds(skipMs)
    val description = skipContentDescription(skipMs, forward)
    val numeralSize = with(LocalDensity.current) { (size * SKIP_NUMERAL_FRACTION).toSp() }

    Box(
        // One node, one description: the arc and the digits are two halves of a single glyph, and
        // a screen reader that found the digits on their own would announce a bare number.
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Replay,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                // Mirrored on the x axis, which turns the anticlockwise arc clockwise.
                .scale(scaleX = if (forward) -1f else 1f, scaleY = 1f),
        )
        Text(
            text = seconds.toString(),
            fontSize = numeralSize,
            lineHeight = numeralSize,
            fontWeight = FontWeight.Bold,
            // The arc's opening is at the top, so the digits sit a little below the centre, where
            // Material's own numbered icons put them.
            modifier = Modifier.padding(top = size * SKIP_NUMERAL_DROP),
        )
    }
}

/**
 * How many seconds a skip distance is, for the glyph and the spoken label alike.
 *
 * @param skipMs the configured distance.
 * @return whole seconds, never less than one.
 */
private fun skipSeconds(skipMs: Long): Int = (skipMs / MILLIS_PER_SECOND).coerceAtLeast(1L).toInt()

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
    val seconds = skipSeconds(skipMs)
    return pluralStringResource(
        id = if (forward) R.plurals.player_skip_forward else R.plurals.player_skip_back,
        count = seconds,
        seconds,
    )
}

/** The size the transport rows drew these at before the glyph was drawn rather than picked. */
private val SKIP_GLYPH_DEFAULT_SIZE: Dp = 24.dp

/**
 * The numeral's height as a fraction of the glyph, and how far below centre it sits.
 *
 * Both were arrived at by looking: the golden was recorded at 96 dp to see where the digits meet
 * the arc, then at the 24 dp and 32 dp the app actually draws, where a numeral that clears the arc
 * comfortably is one nobody can read. These are the sizes at which "60" fits and "5" is legible.
 */
private const val SKIP_NUMERAL_FRACTION = 0.40f
private const val SKIP_NUMERAL_DROP = 0.10f

private const val MILLIS_PER_SECOND = 1_000L

/**
 * Every interval the settings screen offers, both ways round.
 *
 * The one state worth an image: what this file exists to fix is a *number that did not fit any
 * icon*, so the check has to be the whole set at once. Two digits in the arc is the tight case, and
 * 5 against 60 is where a numeral sized off the glyph rather than off the text scale shows.
 */
@ThemePreviews
@Composable
internal fun SkipGlyphsPreview() {
    MegaPodcastPlayerTheme {
        Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
            listOf(false, true).forEach { forward ->
                Row(horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
                    listOf(5_000L, 10_000L, 15_000L, 30_000L, 45_000L, 60_000L).forEach { skipMs ->
                        SkipGlyph(skipMs = skipMs, forward = forward, size = PREVIEW_GLYPH_SIZE)
                    }
                }
            }
        }
    }
}

/** The expanded player's size, which is the larger of the two the app draws. */
private val PREVIEW_GLYPH_SIZE: Dp = 32.dp
