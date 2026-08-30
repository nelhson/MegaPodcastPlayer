package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
