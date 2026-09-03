package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Asserts the state a row announces to accessibility services.
 *
 * Pulled out of the tests because the whole point of the shared components is that this
 * announcement is written once; a helper keeps the assertion phrased the same way everywhere and
 * makes the failure message name the row rather than an anonymous semantics node.
 *
 * @param text any text inside the row, used to find it.
 * @param expected the exact `stateDescription` the row should carry.
 */
internal fun ComposeContentTestRule.assertRowState(text: String, expected: String) {
    onNode(hasText(text, substring = true)).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected),
    )
}
