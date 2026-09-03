package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the canonical show row, used by the library's list layout and by search results.
 *
 * The distinction being protected here is the one that made this a separate component from
 * [EpisodeRow]: a show has no playback state, so the row must not invent one. A library of shows
 * announcing "not played" would be saying something untrue of a podcast.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShowRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows the title author and counts`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                ShowRow(
                    title = "Podlodka Podcast",
                    author = "Egor Tolstoy",
                    metadata = "412 episodes · 2 downloaded",
                )
            }
        }

        composeTestRule.onNodeWithText("Podlodka Podcast").assertIsDisplayed()
        composeTestRule.onNodeWithText("Egor Tolstoy").assertIsDisplayed()
        composeTestRule.onNodeWithText("412 episodes · 2 downloaded").assertIsDisplayed()
    }

    /**
     * The counts line already says "2 downloaded", so the mark beside it is decoration and has to
     * stay silent: the alternative is TalkBack reading the same fact twice in one breath. Same
     * reasoning as the library's new-episode badge, which is muted for the same reason.
     */
    @Test
    fun `marks a show with stored episodes without announcing it twice`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                ShowRow(
                    title = "Podlodka Podcast",
                    metadata = "412 episodes · 2 downloaded",
                    isDownloaded = true,
                )
            }
        }

        composeTestRule.onNodeWithText("412 episodes · 2 downloaded").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Downloaded").assertDoesNotExist()
    }

    @Test
    fun `reports a click`() {
        var clicks = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                ShowRow(title = "Tap me", onClick = { clicks++ })
            }
        }

        composeTestRule.onNodeWithText("Tap me").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled row is still readable but does not fire`() {
        var clicks = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                // What an Apple exclusive looks like in search results: shown, explained, inert.
                ShowRow(
                    title = "Exclusive Show",
                    metadata = "Apple Podcasts exclusive",
                    onClick = { clicks++ },
                    enabled = false,
                )
            }
        }

        composeTestRule.onNodeWithText("Apple Podcasts exclusive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exclusive Show").performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun `announces the state it was given`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                ShowRow(title = "Waiting", stateDescription = "3 new episodes", onClick = {})
            }
        }

        composeTestRule.assertRowState("Waiting", "3 new episodes")
    }

    @Test
    fun `says nothing about playback, because a show has none`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                ShowRow(title = "Quiet", source = PodcastSource.RSS, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Quiet").assert(
            SemanticsMatcher("carries no state description") { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription) == null
            },
        )
    }
}
