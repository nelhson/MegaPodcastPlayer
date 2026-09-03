package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers the two controls whose visual state is drawn rather than written.
 *
 * [PlayPauseButton] swaps its glyph and morphs its outline; [DownloadButton] has two of five faces
 * that are bare canvases with no icon at all. In both cases the only thing a non-sighted user gets
 * is the accessible name, so that name is what these tests pin down — a canvas that renders
 * perfectly while announcing nothing is the exact failure worth catching here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `play button announces play when paused`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme { PlayPauseButton(playing = false, onToggle = {}) }
        }

        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun `play button announces pause when playing`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme { PlayPauseButton(playing = true, onToggle = {}) }
        }

        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    /**
     * Buffering keeps the control interactive and in place. A spinner that replaces the button
     * moves the layout under the user's finger at the worst possible moment.
     */
    @Test
    fun `play button announces buffering but stays pressable`() {
        var toggles = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                PlayPauseButton(playing = true, buffering = true, onToggle = { toggles++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Buffering").performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `play button reports the requested state`() {
        var requested: Boolean? = null
        composeTestRule.setContent {
            MegaPodcastPlayerTheme { PlayPauseButton(playing = false, onToggle = { requested = it }) }
        }

        composeTestRule.onNodeWithContentDescription("Play").performClick()

        assertEquals(true, requested)
    }

    /**
     * All five faces are rendered together rather than one per test: `setContent` may only be
     * called once per test, and a single column also proves the five states are distinguishable
     * from each other rather than merely individually labelled.
     */
    @Test
    fun `every download state announces itself`() {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                Column {
                    DownloadState.entries.forEach { state ->
                        DownloadButton(state = state, progressPercent = 62f, onClick = {})
                    }
                }
            }
        }

        listOf(
            "Download",
            "Download queued",
            "Downloading, 62%",
            "Downloaded, remove from device",
            "Download failed, retry",
        ).forEach { description ->
            composeTestRule.onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun `download button reports a click`() {
        var clicks = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                DownloadButton(
                    state = DownloadState.NOT_DOWNLOADED,
                    progressPercent = 0f,
                    onClick = { clicks++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Download").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled download button does not fire`() {
        var clicks = 0
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                DownloadButton(
                    state = DownloadState.NOT_DOWNLOADED,
                    progressPercent = 0f,
                    enabled = false,
                    onClick = { clicks++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Download").performClick()

        assertEquals(0, clicks)
    }
}
