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
 * Renders the library's cover tile.
 *
 * The badge is the part worth pinning: it is the only thing on the tile that says a show has
 * something waiting, it must not appear when there is nothing, and the number itself must not be
 * read out on its own — "3" tells a TalkBack user nothing, which is why the tile carries the
 * sentence and the badge is silent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShowTileTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows the title and the unplayed count`() {
        composeTestRule.setContent {
            BPodcatTheme {
                ShowTile(
                    title = "Podlodka Podcast",
                    badgeCount = 3,
                    stateDescription = "3 new episodes",
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Podlodka Podcast").assertIsDisplayed()
        composeTestRule.assertRowState("Podlodka Podcast", "3 new episodes")
    }

    @Test
    fun `draws no badge when nothing is waiting`() {
        composeTestRule.setContent {
            BPodcatTheme {
                ShowTile(title = "Acquired", badgeCount = 0, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `reports a click`() {
        var clicks = 0
        composeTestRule.setContent {
            BPodcatTheme {
                ShowTile(title = "Tap me", onClick = { clicks++ })
            }
        }

        composeTestRule.onNodeWithText("Tap me").performClick()

        assertEquals(1, clicks)
    }
}
