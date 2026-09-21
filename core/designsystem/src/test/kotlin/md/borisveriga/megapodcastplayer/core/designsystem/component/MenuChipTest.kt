package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
 * Renders [MenuChip].
 *
 * What is asserted is what distinguishes it from the sort chip it was lifted out of: that nothing
 * is ticked when nothing is chosen, and that the option picked is the one reported.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MenuChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Shows a chip over three lengths of time.
     *
     * @param selected the option to tick, or null.
     * @param onSelect receives the option picked.
     */
    private fun setChip(selected: String?, onSelect: (String) -> Unit = {}) {
        composeTestRule.setContent {
            MegaPodcastPlayerTheme {
                MenuChip(
                    label = "Stop after a set time",
                    icon = Icons.Rounded.Timer,
                    options = listOf("15 min", "30 min", "45 min"),
                    selected = selected,
                    onSelect = onSelect,
                    menuDescription = "Choose how long to keep playing",
                    optionLabel = { it },
                )
            }
        }
    }

    /**
     * Matches a node by its spoken state.
     *
     * @param state the state description expected.
     * @return the matcher.
     */
    private fun hasState(state: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    @Test
    fun `the menu stays shut until the chip is pressed`() {
        setChip(selected = null)

        composeTestRule.onNodeWithText("30 min").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Choose how long to keep playing").performClick()

        composeTestRule.onNodeWithText("30 min").assertIsDisplayed()
    }

    @Test
    fun `the chip says its answer as well as what it opens`() {
        setChip(selected = null)

        // A content description is spoken instead of a label; the label is the answer.
        composeTestRule
            .onNodeWithContentDescription("Choose how long to keep playing")
            .assert(hasState("Stop after a set time"))
    }

    @Test
    fun `nothing chosen ticks nothing`() {
        setChip(selected = null)

        composeTestRule.onNodeWithContentDescription("Choose how long to keep playing").performClick()

        listOf("15 min", "30 min", "45 min").forEach { option ->
            composeTestRule.onNodeWithText(option).assert(hasState("Not selected"))
        }
    }

    @Test
    fun `the chosen option says it is selected`() {
        setChip(selected = "30 min")

        composeTestRule.onNodeWithContentDescription("Choose how long to keep playing").performClick()

        composeTestRule.onNodeWithText("30 min").assert(hasState("Selected"))
        composeTestRule.onNodeWithText("15 min").assert(hasState("Not selected"))
    }

    @Test
    fun `picking an option reports it and closes the menu`() {
        var picked: String? = null
        setChip(selected = null, onSelect = { picked = it })

        composeTestRule.onNodeWithContentDescription("Choose how long to keep playing").performClick()
        composeTestRule.onNodeWithText("45 min").performClick()

        assertEquals("45 min", picked)
        composeTestRule.onNodeWithText("15 min").assertDoesNotExist()
    }
}
