package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import md.borisveriga.megapodcastplayer.core.designsystem.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * Flips a list between two orders.
 *
 * One of the two shapes a sort control takes here, and they live in the same file because they are
 * one control with two arities: a list with two orders is flipped, a list with more than two is
 * chosen from ([SortMenuChip]). Both are an [AssistChip], so that a sort control is recognisably
 * the same thing on the show page and in the library, and both say which order is *on* rather than
 * which one they would switch to — a control labelled with its own effect reads as a statement
 * about the list under it, which is what someone scanning a screen is looking for.
 *
 * A toggle rather than a menu here: there are two answers, and a menu to choose between two things
 * is a tap spent on arithmetic.
 *
 * @param label the order currently applied, as the user would say it.
 * @param icon the glyph beside it; decorative, because [label] already says the order.
 * @param switchToDescription what tapping does, e.g. "Sort oldest first". The words are the only
 *   thing that says this is a switch rather than a statement, so a screen reader gets them instead
 *   of the label.
 * @param onClick invoked to switch to the other order.
 * @param modifier layout modifier.
 */
@Composable
fun SortToggleChip(
    label: String,
    icon: ImageVector,
    switchToDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        modifier = modifier.semantics { contentDescription = switchToDescription },
    )
}

/**
 * Chooses one of several orders from a menu hung under the chip.
 *
 * A menu rather than a row of chips, because an order is exclusive and a row of four filter chips
 * that behave like radio buttons is the standard way to be misread as a multi-select. The chip
 * itself carries the chosen order, so the current answer is legible without opening anything.
 *
 * Ticks the chosen row, and gives it the same spoken state the choice chips elsewhere use, so the
 * menu answers "which order is on" both to the eye and to TalkBack.
 *
 * @param label the order currently applied.
 * @param options every order on offer, in the order they should be listed, each with the caption
 *   to draw for it.
 * @param selected which of [options] is applied; it is what [label] names.
 * @param onSelect invoked with the chosen order. The menu closes itself first.
 * @param menuDescription what the chip opens, e.g. "Sort shows". Supplies the verb the label lacks.
 * @param optionLabel the caption for one option.
 * @param modifier layout modifier.
 */
@Composable
fun <T> SortMenuChip(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    menuDescription: String,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    // Local to the control: an open menu is not state any screen or view model has an opinion
    // about, and one that survived a fold would reopen over a list the user had moved on from.
    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AssistChip(
            onClick = { isExpanded = true },
            label = { Text(text = label) },
            leadingIcon = {
                Icon(imageVector = Icons.Rounded.SwapVert, contentDescription = null)
            },
            modifier = Modifier.semantics { contentDescription = menuDescription },
        )

        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            options.forEach { option ->
                val isSelected = option == selected
                // Resolved here rather than inside `semantics`, which is not a composable scope.
                val state = stringResource(
                    if (isSelected) {
                        R.string.designsystem_chip_selected
                    } else {
                        R.string.designsystem_chip_not_selected
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = optionLabel(option)) },
                    onClick = {
                        isExpanded = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        // The glyph is the same fact the state description carries, drawn for the
                        // eye; describing it too would have TalkBack say "selected" twice.
                        if (isSelected) {
                            Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    modifier = Modifier.semantics { stateDescription = state },
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SortToggleChipPreview() {
    MegaPodcastPlayerTheme {
        SortToggleChip(
            label = "Newest first",
            icon = Icons.Rounded.ArrowDownward,
            switchToDescription = "Sort oldest first",
            onClick = {},
        )
    }
}

@ThemePreviews
@Composable
private fun SortMenuChipPreview() {
    MegaPodcastPlayerTheme {
        SortMenuChip(
            label = "Recently updated",
            options = listOf("Manual", "Recently updated", "A–Z"),
            selected = "Recently updated",
            onSelect = {},
            menuDescription = "Sort shows",
            optionLabel = { it },
        )
    }
}
