package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [SpeedSheet].
 *
 * The sheet exists because the control it replaced could not reach a rate between two presets and
 * wrapped from the fastest back to the slowest. Both of those are asserted here: a nudge lands on
 * 1.35×, a rate no chip offers, and the fast end stops rather than wrapping.
 *
 * The other thing worth pinning is which callback each control uses. A tap is a decision and is
 * remembered; only a drag in progress is not. Getting that backwards would either lose the setting
 * on every restart or write the preference thirty times per gesture, and neither is visible on
 * screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SpeedSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val committed = mutableListOf<Float>()
    private val previewed = mutableListOf<Float>()

    @Test
    fun `shows the rate it was opened at`() {
        showSheet(speed = 1.5f)

        composeRule.readout("1.5x").assertIsDisplayed()
    }

    @Test
    fun `a preset commits that rate`() {
        showSheet(speed = 1f)

        composeRule.onNodeWithText("2x").performClick()

        assertEquals(listOf(2f), committed)
        // A tap is a decision, not a search: nothing is applied without also being remembered.
        assertTrue(previewed.isEmpty())
    }

    @Test
    fun `the plus button reaches a rate no preset offers`() {
        showSheet(speed = 1.3f)

        composeRule.onNodeWithContentDescription("Faster").performClick()

        assertEquals(1, committed.size)
        assertEquals(1.35f, committed.single(), TOLERANCE)
    }

    @Test
    fun `the minus button moves the other way`() {
        showSheet(speed = 1.3f)

        composeRule.onNodeWithContentDescription("Slower").performClick()

        assertEquals(1.25f, committed.single(), TOLERANCE)
    }

    @Test
    fun `the fast end stops instead of wrapping`() {
        showSheet(speed = 3f)

        composeRule.onNodeWithContentDescription("Faster").assertIsNotEnabled()
    }

    @Test
    fun `the slow end stops too`() {
        showSheet(speed = 0.5f)

        composeRule.onNodeWithContentDescription("Slower").assertIsNotEnabled()
    }

    @Test
    fun `the default chip is the way back to normal speed`() {
        showSheet(speed = 2.5f)

        // The chip is marked as the default by a tint and a glyph, neither of which a screen
        // reader can see, so it also carries the words; and being the only chip with any, they
        // find it.
        composeRule.onNodeWithContentDescription(DEFAULT).performClick()

        assertEquals(listOf(1f), committed)
        composeRule.readout("1x").assertIsDisplayed()
    }

    @Test
    fun `only the default chip is called normal speed`() {
        showSheet(speed = 1f)

        composeRule.onAllNodesWithContentDescription(DEFAULT).assertCountEquals(1)
    }

    /**
     * The description has to name the rate itself.
     *
     * A content description on a chip replaces the label rather than joining it, so a bare "Normal
     * speed" would leave the middle of the scale as the one rate TalkBack never says. Asserting
     * the `Text` instead would prove nothing: it survives in the semantics tree either way.
     */
    @Test
    fun `the default chip still says which rate it is`() {
        showSheet(speed = 2.5f)

        composeRule.onNodeWithContentDescription("1x, normal speed").assertIsDisplayed()
    }

    /**
     * The rate readout between the two nudge buttons.
     *
     * Addressed as "the one that is not a button", because the same rate is on screen twice once a
     * preset matches it: as the number the sheet is showing, and as the chip that is ticked.
     *
     * @param text the formatted rate to look for.
     * @return the readout node.
     */
    private fun ComposeContentTestRule.readout(text: String) =
        onNode(hasText(text) and !hasClickAction())

    /**
     * Draws the sheet at a starting rate, recording what it calls.
     *
     * @param speed the rate the player is running at when the sheet opens.
     */
    private fun showSheet(speed: Float) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SpeedSheet(
                    speed = speed,
                    onPreview = { previewed += it },
                    onCommit = { committed += it },
                    onDismiss = {},
                )
            }
        }
    }

    private companion object {
        /** Rates are floats built by arithmetic; comparing them exactly would be luck. */
        const val TOLERANCE = 0.001f

        /** What the 1× chip says to a screen reader, since its mark says it to everyone else. */
        const val DEFAULT = "1x, normal speed"
    }
}
