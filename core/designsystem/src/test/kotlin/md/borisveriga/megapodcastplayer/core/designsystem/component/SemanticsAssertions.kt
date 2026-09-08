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

/**
 * Asserts whether a row tells accessibility services it is selected.
 *
 * Two-pane selection is announced with Compose's own `Selected` property rather than with a
 * sentence in the state description, so it is asserted separately from [assertRowState] — and the
 * unselected case asserts the property is *absent*, not false: a list with no detail pane beside it
 * should not be answering a question about selection at all.
 *
 * @param text any text inside the row, used to find it.
 * @param expected whether the row should be announcing itself as selected.
 */
internal fun ComposeContentTestRule.assertRowSelected(text: String, expected: Boolean) {
    val node = onNode(hasText(text, substring = true))
    if (expected) {
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    } else {
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }
}
