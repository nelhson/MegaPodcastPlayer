package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import md.borisveriga.bpodcat.core.designsystem.R
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews

/**
 * A labelled switch.
 *
 * Lifted out of the settings screen unchanged, because that screen already had the best
 * accessibility in the app and this is the standard the rest is being raised to rather than the one
 * being changed: the whole row toggles, not just the switch — a 48dp target at the far edge of the
 * screen is a poor one — and the row owns the semantics so TalkBack announces one control instead
 * of a row with a switch beside it.
 *
 * @param title the setting's name.
 * @param description one line explaining what it does.
 * @param checked current value.
 * @param onCheckedChange invoked with the new value.
 * @param modifier layout modifier.
 * @param containerColor the row's ground; transparent when the row sits inside a card that has
 *   already painted one.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
) {
    // Built here rather than inside `semantics`, which is not a composable scope.
    val rowDescription = stringResource(R.string.designsystem_setting_description, title, description)

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            // The row owns the semantics, so the switch itself must not announce a second control.
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = modifier
            .clickable { onCheckedChange(!checked) }
            .semantics {
                role = Role.Switch
                contentDescription = rowDescription
                toggleableState = ToggleableState(checked)
            },
    )
}

/**
 * A row of mutually exclusive chips.
 *
 * A chip row rather than a dialog because these settings each have a handful of sensible values and
 * no free-form input, and seeing them all is faster than opening a picker.
 *
 * @param T the option type.
 * @param title the setting's name.
 * @param options every value on offer.
 * @param selected the current value.
 * @param label renders one option's caption; composable, because the captions come from resources.
 * @param onSelect invoked with the chosen value.
 * @param modifier layout modifier.
 */
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = BPodcatTheme.spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = BPodcatTheme.spacing.lg),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    horizontal = BPodcatTheme.spacing.lg,
                    vertical = BPodcatTheme.spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
        ) {
            options.forEach { option ->
                val optionLabel = label(option)
                val isSelected = option == selected
                // Built here rather than inside `semantics`, which is not a composable scope.
                val chipDescription =
                    stringResource(R.string.designsystem_choice_description, title, optionLabel)
                val chipState = stringResource(
                    if (isSelected) {
                        R.string.designsystem_chip_selected
                    } else {
                        R.string.designsystem_chip_not_selected
                    },
                )
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel) },
                    // Without this the chip announces only its own caption — "30 s" tells a
                    // TalkBack user nothing about which setting they are changing.
                    modifier = Modifier.semantics {
                        contentDescription = chipDescription
                        stateDescription = chipState
                    },
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun SettingsSwitchRowPreview() {
    BPodcatTheme {
        SettingsSwitchRow(
            title = "Download on Wi-Fi only",
            description = "Wait for an unmetered network before downloading",
            checked = true,
            onCheckedChange = {},
        )
    }
}

@ThemePreviews
@Composable
private fun SettingsChoiceRowPreview() {
    BPodcatTheme {
        SettingsChoiceRow(
            title = "Playback speed",
            options = listOf(1f, 1.25f, 1.5f, 2f),
            selected = 1.25f,
            label = { speed -> "${speed}x" },
            onSelect = {},
        )
    }
}
