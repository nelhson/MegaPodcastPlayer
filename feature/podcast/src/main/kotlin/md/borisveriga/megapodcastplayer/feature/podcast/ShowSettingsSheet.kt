package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.R as DesignSystemR
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ShowSettings

/**
 * What this show does differently from the rest.
 *
 * Every setting here was a single app-wide switch before, and each of them was the wrong shape for
 * it. Auto-download is worth it for a weekly show and wasteful for a daily one. A speed that suits
 * one host is unlistenable for another. A show that publishes four times a year deserves a
 * notification and one that publishes daily does not. And an intro to skip is by definition a
 * property of the show that has one.
 *
 * Three of the four therefore offer *no answer* as well as yes and no, spelled "Use the app
 * setting", and that is the default. A show the user has said nothing about is not stored at all
 * (see [ShowSettings]), so this sheet writes only what is actually a decision.
 *
 * @param showTitle the show, named in the header so the sheet cannot be mistaken for the app's own
 *   settings — which is the mistake a sheet of familiar-looking switches invites.
 * @param settings the show's current settings.
 * @param appSpeed the app-wide rate, named on the chip that defers to it.
 * @param appAutoDownload the app-wide auto-download answer, named for the same reason.
 * @param onSettingsChange applies a change; receives the whole new settings object.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShowSettingsSheet(
    showTitle: String,
    settings: ShowSettings,
    appSpeed: Float,
    appAutoDownload: Boolean,
    onSettingsChange: (ShowSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.show_settings_title),
        subtitle = showTitle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Four sections of chips outgrow a sheet at 200 % font scale; without this the
                // last one is simply unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
                .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.lg),
        ) {
            Section(title = stringResource(R.string.show_settings_speed)) {
                SettingChip(
                    label = stringResource(R.string.show_settings_app_speed, formatSpeed(appSpeed)),
                    selected = settings.speed == null,
                    onClick = { onSettingsChange(settings.copy(speed = null)) },
                )
                PlaybackSettings.SPEED_STEPS.forEach { preset ->
                    SettingChip(
                        label = formatSpeed(preset),
                        selected = settings.speed == preset,
                        onClick = { onSettingsChange(settings.copy(speed = preset)) },
                    )
                }
            }

            Section(title = stringResource(R.string.show_settings_auto_download)) {
                SettingChip(
                    label = stringResource(
                        if (appAutoDownload) {
                            R.string.show_settings_app_download_on
                        } else {
                            R.string.show_settings_app_download_off
                        },
                    ),
                    selected = settings.autoDownload == null,
                    onClick = { onSettingsChange(settings.copy(autoDownload = null)) },
                )
                SettingChip(
                    label = stringResource(R.string.show_settings_download_always),
                    selected = settings.autoDownload == true,
                    onClick = { onSettingsChange(settings.copy(autoDownload = true)) },
                )
                SettingChip(
                    label = stringResource(R.string.show_settings_download_never),
                    selected = settings.autoDownload == false,
                    onClick = { onSettingsChange(settings.copy(autoDownload = false)) },
                )
            }

            Section(title = stringResource(R.string.show_settings_skip_intro)) {
                SKIP_INTRO_SECONDS.forEach { seconds ->
                    SettingChip(
                        label = if (seconds == 0) {
                            stringResource(R.string.show_settings_skip_intro_none)
                        } else {
                            pluralStringResource(
                                R.plurals.show_settings_skip_intro_seconds,
                                seconds,
                                seconds,
                            )
                        },
                        selected = settings.skipIntroMs == seconds * MILLIS_PER_SECOND,
                        onClick = {
                            onSettingsChange(
                                settings.copy(skipIntroMs = seconds * MILLIS_PER_SECOND),
                            )
                        },
                    )
                }
            }

            NotifyRow(
                enabled = settings.notifyNewEpisodes,
                onChange = { onSettingsChange(settings.copy(notifyNewEpisodes = it)) },
            )
        }
    }
}

/**
 * One labelled group of chips.
 *
 * @param title the group's name, as a heading above its chips.
 * @param content the chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
        ) {
            content()
        }
    }
}

/**
 * One option.
 *
 * The same filter chip the sleep timer and the speed sheet use, for the same reason: which one is
 * chosen is the whole state being shown, and a screen reader is told so rather than being left to
 * infer it from a tint.
 *
 * @param label the words.
 * @param selected whether this is the current answer.
 * @param onClick chooses it.
 */
@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val state = stringResource(
        if (selected) {
            DesignSystemR.string.designsystem_chip_selected
        } else {
            DesignSystemR.string.designsystem_chip_not_selected
        },
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = if (selected) {
            { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.semantics { stateDescription = state },
    )
}

/**
 * The one setting here with only two answers.
 *
 * A switch rather than chips, and deliberately not offered a third "use the app setting" state:
 * there is no app-wide new-episode toggle to defer to — the notification either happens or it does
 * not — so a third option would defer to nothing.
 *
 * @param enabled whether this show may post a new-episode notification.
 * @param onChange applies the change.
 */
@Composable
private fun NotifyRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        // The whole row toggles, and the switch itself is left inert: one node for a screen reader
        // rather than a label and a control it has to associate, and a tap target the width of the
        // sheet rather than the width of a switch.
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = MegaPodcastPlayerTheme.spacing.md)) {
            Text(
                text = stringResource(R.string.show_settings_notify),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.show_settings_notify_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/** The intro lengths on offer; zero is "this show has no intro", and is the default. */
private val SKIP_INTRO_SECONDS = listOf(0, 15, 30, 45, 60, 90)

private const val MILLIS_PER_SECOND = 1_000L
