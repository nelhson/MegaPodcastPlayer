package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [ShowSettingsSheet].
 *
 * The rule worth pinning is the third state. Every setting here that has an app-wide equivalent can
 * be left to it, and "left to it" has to be *storable as nothing* rather than as a copy of the
 * current app value — otherwise changing the app setting later would silently fail to move a show
 * that never disagreed with it. So the tests assert on null as much as on the values.
 *
 * The chips also name the app-wide value they defer to, which is what makes choosing an override a
 * comparison rather than a guess; that the number is really on screen is asserted too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShowSettingsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var latest: ShowSettings? = null

    @Test
    fun `the deferring chips name the app-wide values`() {
        showSheet(appSpeed = 1.5f, appAutoDownload = true)

        composeRule.onNodeWithText("App speed (1.5x)").assertIsDisplayed()
        composeRule.onNodeWithText("App setting (download)").assertIsDisplayed()
    }

    @Test
    fun `choosing a rate stores it against the show`() {
        showSheet()

        composeRule.onNodeWithText("2x").performClick()

        assertEquals(2f, latest?.speed)
    }

    @Test
    fun `deferring to the app clears the rate rather than copying it`() {
        showSheet(settings = ShowSettings(speed = 2f), appSpeed = 1.5f)

        composeRule.onNodeWithText("App speed (1.5x)").performClick()

        assertNull(latest?.speed)
    }

    @Test
    fun `a show can refuse downloads the app setting would make`() {
        showSheet(appAutoDownload = true)

        composeRule.onNodeWithText("Never download").performClick()

        assertEquals(false, latest?.autoDownload)
    }

    @Test
    fun `an intro length is stored in milliseconds`() {
        showSheet()

        composeRule.onNodeWithText("45 seconds").performClick()

        assertEquals(45_000L, latest?.skipIntroMs)
    }

    @Test
    fun `notifications can be turned off for one show`() {
        showSheet()

        composeRule.onNodeWithText("Tell me about new episodes").performClick()

        assertEquals(false, latest?.notifyNewEpisodes)
    }

    /**
     * Draws the sheet, recording what it asks to change.
     *
     * @param settings the show's settings as stored.
     * @param appSpeed the app-wide rate.
     * @param appAutoDownload the app-wide auto-download answer.
     */
    private fun showSheet(
        settings: ShowSettings = ShowSettings.DEFAULT,
        appSpeed: Float = 1f,
        appAutoDownload: Boolean = false,
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                ShowSettingsSheet(
                    showTitle = "Podlodka Podcast",
                    settings = settings,
                    appSpeed = appSpeed,
                    appAutoDownload = appAutoDownload,
                    onSettingsChange = { latest = it },
                    onDismiss = {},
                )
            }
        }
    }
}
