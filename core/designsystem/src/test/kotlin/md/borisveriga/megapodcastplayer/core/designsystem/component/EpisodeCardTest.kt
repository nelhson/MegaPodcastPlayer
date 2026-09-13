package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders one card of a shelf.
 *
 * The thing worth pinning is the bottom row. The card is a fixed width and the metadata line is
 * whatever the feature writes there, so a long enough line — "6 days ago · 1 h 30 min left" was
 * enough — once took the whole row and left the play button a one-pixel sliver. The button's size
 * is not negotiable; the text is.
 *
 * Native graphics, because the fault is in how wide a string measures: under Robolectric's legacy
 * graphics every glyph is a pixel wide, no metadata line is ever long enough to crowd anything,
 * and the assertions below pass against the layout they were written to reject.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EpisodeCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `keeps the play button at full size under a long metadata line`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                EpisodeCard(
                    title = "Podlodka #493 – Выгорание от AI",
                    showTitle = "Podlodka Podcast",
                    metadata = "6 days ago · 1 h 30 min left, and then some more words",
                    artworkUrl = null,
                    playedFraction = 0.3f,
                    isNowPlaying = false,
                    isPlaying = false,
                    isBuffering = false,
                    stateDescription = "30% played",
                    onClick = {},
                    onPlay = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Play", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(PlayPauseSize.Small.container)
            .assertHeightIsEqualTo(PlayPauseSize.Small.container)
    }

    @Test
    fun `keeps the play button at full size under a short metadata line`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                EpisodeCard(
                    title = "The AI bubble, revisited",
                    showTitle = "Hard Fork",
                    metadata = "42 min left",
                    artworkUrl = null,
                    playedFraction = 0f,
                    isNowPlaying = false,
                    isPlaying = false,
                    isBuffering = false,
                    stateDescription = "Not played",
                    onClick = {},
                    onPlay = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Play", useUnmergedTree = true)
            .assertWidthIsEqualTo(PlayPauseSize.Small.container)
    }

    @Test
    fun `the metadata line stays on one line no matter how long`() {
        val metadata = "6 days ago · 1 h 30 min left, and then some more words that cannot fit"
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                EpisodeCard(
                    title = "Episode",
                    showTitle = "Show",
                    metadata = metadata,
                    artworkUrl = null,
                    playedFraction = 0f,
                    isNowPlaying = false,
                    isPlaying = false,
                    isBuffering = false,
                    stateDescription = "Not played",
                    onClick = {},
                    onPlay = {},
                )
            }
        }

        // The node still carries the whole string; only what is drawn is cut, which the layout
        // result reports as a single line that overflowed.
        val layout = composeTestRule.onNodeWithText(metadata, useUnmergedTree = true)
            .assertIsDisplayed()
            .textLayoutResult()
        assertEquals(1, layout.lineCount)
        assertTrue("expected the line to be cut off", layout.hasVisualOverflow)
    }

    @Test
    fun `reports a play tap separately from a card tap`() {
        var plays = 0
        var opens = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                EpisodeCard(
                    title = "Tap me",
                    showTitle = "Show",
                    metadata = "42 min left",
                    artworkUrl = null,
                    playedFraction = 0f,
                    isNowPlaying = false,
                    isPlaying = false,
                    isBuffering = false,
                    stateDescription = "Not played",
                    onClick = { opens++ },
                    onPlay = { plays++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Play", useUnmergedTree = true).performClick()

        assertEquals(1, plays)
        assertEquals(0, opens)
    }

    /**
     * Asks the text node how it was laid out, the way a screen reader would.
     *
     * @return the layout result of the node's text.
     */
    private fun SemanticsNodeInteraction.textLayoutResult(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        checkNotNull(action) { "text node exposes no layout result" }
        assertTrue("the node reported no layout result", action(results))
        return results.single()
    }
}
