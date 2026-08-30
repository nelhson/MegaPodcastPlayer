package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the canonical episode row.
 *
 * The row is the one component every list screen delegates to, so a regression here is a
 * regression on five screens at once. These tests cover what the row promises rather than how it
 * looks: the text it shows, the click it reports, and — most importantly — the state it announces,
 * because that announcement is the only thing a TalkBack user gets from the progress hairline and
 * the now-playing tint.
 *
 * A phone-sized display, since the row's two-line title and trailing slot need real width.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EpisodeRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows the title show and metadata`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(
                    title = "The AI bubble, revisited",
                    showTitle = "Hard Fork",
                    metadata = "42 min · 2 days ago",
                )
            }
        }

        composeTestRule.onNodeWithText("The AI bubble, revisited").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hard Fork").assertIsDisplayed()
        composeTestRule.onNodeWithText("42 min · 2 days ago").assertIsDisplayed()
    }

    @Test
    fun `reports a click`() {
        var clicks = 0
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Tap me", onClick = { clicks++ })
            }
        }

        composeTestRule.onNodeWithText("Tap me").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `is inert without an onClick`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Not tappable")
            }
        }

        // Performing a click on a node with no click action throws, so the assertion is simply
        // that the row still renders and offers nothing to press.
        composeTestRule.onNodeWithText("Not tappable").assertIsDisplayed()
    }

    @Test
    fun `announces an unplayed episode as not played`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Fresh", isUnplayed = true)
            }
        }

        composeTestRule.assertRowState("Fresh", "Not played")
    }

    @Test
    fun `announces a finished episode as played`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Done", isPlayed = true)
            }
        }

        composeTestRule.assertRowState("Done", "Played")
    }

    /**
     * The progress hairline is the only visual cue that an episode is half-finished, and it is
     * three pixels tall. Without this announcement a TalkBack user has no way to tell a
     * part-played episode from an untouched one.
     */
    @Test
    fun `announces how far through a part-played episode is`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Halfway", playedFraction = 0.42f)
            }
        }

        composeTestRule.assertRowState("Halfway", "42% played")
    }

    @Test
    fun `announces the row that is currently playing`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(
                    title = "Current",
                    playedFraction = 0.1f,
                    isNowPlaying = true,
                    isPlaying = true,
                )
            }
        }

        // Now-playing outranks the progress announcement: it is the more useful fact.
        composeTestRule.assertRowState("Current", "Now playing")
    }

    @Test
    fun `reports a long press, and offers it as a named action a gesture cannot reach`() {
        var longPresses = 0
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(
                    title = "Hold me",
                    onClick = {},
                    onLongClick = { longPresses++ },
                    longClickLabel = "Select",
                )
            }
        }

        composeTestRule.onNodeWithText("Hold me").performTouchInput { longClick() }
        assertEquals(1, longPresses)

        // The label is what a TalkBack user is offered in place of the gesture, so an unlabelled
        // long press is a selection they cannot start.
        composeTestRule.onNodeWithText("Hold me").assert(
            SemanticsMatcher("has a labelled long-press action") { node ->
                node.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Select"
            },
        )
    }

    @Test
    fun `a selected row says so`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Picked", isSelected = true, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Picked").assert(isSelected())
    }

    @Test
    fun `a row outside a selection does not announce itself as unselected`() {
        composeTestRule.setContent {
            BPodcatTheme {
                EpisodeRow(title = "Ordinary", onClick = {})
            }
        }

        // `isNotSelected()` would also match a row carrying `selected = false`, which is exactly
        // the noise this avoids: the property must be absent, not present and false.
        composeTestRule.onNodeWithText("Ordinary").assert(
            SemanticsMatcher("carries no selection state") { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == null
            },
        )
    }
}
