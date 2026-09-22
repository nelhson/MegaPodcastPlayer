package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the skip buttons' glyph.
 *
 * What the glyph looks like is a golden's job — `PlayerScreenshotTest.skipGlyphs` draws all six
 * intervals both ways round, and it is the only check that the numeral lands inside the arc. What
 * is pinned here is what the glyph *says*, which an image cannot show: one spoken label for the
 * whole button, naming the interval it will really jump, with the digits drawn inside it kept out
 * of the semantics tree so that a screen reader never meets a bare number (`COPY_RULES` rule 10).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SkipGlyphTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an interval with no Material icon is still spoken in full`() {
        showGlyph(skipMs = 15_000L, forward = false)

        composeRule.onNodeWithContentDescription("Skip back 15 seconds").assertIsDisplayed()
    }

    @Test
    fun `the forward glyph says which way it goes`() {
        showGlyph(skipMs = 45_000L, forward = true)

        composeRule.onNodeWithContentDescription("Skip ahead 45 seconds").assertIsDisplayed()
    }

    @Test
    fun `the numeral drawn in the arc is not announced on its own`() {
        showGlyph(skipMs = 15_000L, forward = false)

        // The digits are half a glyph, not a label. A screen reader that found them would say
        // "15" with nothing to attach it to.
        composeRule.onNodeWithText("15").assertDoesNotExist()
    }

    @Test
    fun `one second is spoken in the singular`() {
        showGlyph(skipMs = 1_000L, forward = true)

        composeRule.onNodeWithContentDescription("Skip ahead 1 second").assertIsDisplayed()
    }

    /**
     * A distance below a second still has to name one.
     *
     * Nothing in the app can set this, but the label is built by integer division, and a zero
     * would reach the plural rules as "Skip back 0 seconds" on a button that does move.
     */
    @Test
    fun `less than a second is rounded up rather than to nothing`() {
        showGlyph(skipMs = 400L, forward = false)

        composeRule.onNodeWithContentDescription("Skip back 1 second").assertIsDisplayed()
    }

    /**
     * Draws one glyph in the app's theme.
     *
     * @param skipMs the distance it is configured for.
     * @param forward true for the skip-ahead direction.
     */
    private fun showGlyph(skipMs: Long, forward: Boolean) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SkipGlyph(skipMs = skipMs, forward = forward)
            }
        }
    }
}
