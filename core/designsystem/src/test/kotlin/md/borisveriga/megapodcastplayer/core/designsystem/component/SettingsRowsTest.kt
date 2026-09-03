package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the two settings rows.
 *
 * These moved out of the settings screen unchanged, and the reason to test them here is that the
 * move is the risk: the screen already had the best accessibility in the app, and the rows now
 * carry it for everyone. What is asserted is exactly what would be silently lost — the whole row
 * being the switch, the row announcing one control rather than two, and a chip naming the setting
 * it belongs to instead of only its own caption.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the whole switch row toggles, not just the switch`() {
        var checked = false
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                SettingsSwitchRow(
                    title = "Download on Wi-Fi only",
                    description = "Wait for an unmetered network",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        // The description is at the far end of the row from the switch: pressing it must still
        // toggle, which is the whole point of the row owning the gesture.
        composeTestRule.onNodeWithText("Wait for an unmetered network").performClick()

        assertEquals(true, checked)
    }

    @Test
    fun `the switch row is one control, named and toggleable`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                SettingsSwitchRow(
                    title = "Auto-play next",
                    description = "Play the next queued episode",
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Auto-play next. Play the next queued episode")
            .assertIsOn()
    }

    @Test
    fun `an off switch row says so rather than staying silent`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                SettingsSwitchRow(
                    title = "Auto-download",
                    description = "Download new episodes as they arrive",
                    checked = false,
                    onCheckedChange = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Auto-download. Download new episodes as they arrive")
            .assertIsOff()
    }

    @Test
    fun `a choice chip names its setting and reports the value it stands for`() {
        var chosen = 1f
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                SettingsChoiceRow(
                    title = "Playback speed",
                    options = listOf(1f, 1.5f, 2f),
                    selected = chosen,
                    label = { speed -> "${speed}x" },
                    onSelect = { chosen = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Playback speed").assertIsDisplayed()
        // Found by the description rather than the caption: "1.5x" on its own is what a TalkBack
        // user must never be left with.
        composeTestRule.onNodeWithContentDescription("Playback speed: 1.5x").performClick()

        assertEquals(1.5f, chosen)
    }
}
