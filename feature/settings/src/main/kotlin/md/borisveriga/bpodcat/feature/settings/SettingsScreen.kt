package md.borisveriga.bpodcat.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.bpodcat.core.common.format.formatBytes
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.PlaybackSettings

/**
 * Settings screen.
 *
 * @param onBack invoked when the user navigates back.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onSpeedChange = viewModel::setSpeed,
        onSkipForwardChange = viewModel::setSkipForward,
        onSkipBackChange = viewModel::setSkipBack,
        onAutoPlayNextChange = viewModel::setAutoPlayNext,
        onAutoDownloadChange = viewModel::setAutoDownload,
        onUnmeteredOnlyChange = viewModel::setUnmeteredOnly,
        onKeepLimitChange = viewModel::setKeepLimit,
        onDeleteAfterPlayingChange = viewModel::setDeleteAfterPlaying,
        onRemoveAllDownloads = viewModel::removeAllDownloads,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * Stateless settings screen.
 *
 * @param uiState what to render.
 * @param onBack back handler.
 * @param onSpeedChange playback rate handler.
 * @param onSkipForwardChange skip-ahead interval handler.
 * @param onSkipBackChange skip-back interval handler.
 * @param onAutoPlayNextChange auto-play toggle handler.
 * @param onAutoDownloadChange auto-download toggle handler.
 * @param onUnmeteredOnlyChange Wi-Fi-only toggle handler.
 * @param onKeepLimitChange keep-limit handler.
 * @param onDeleteAfterPlayingChange delete-after-playing toggle handler.
 * @param onRemoveAllDownloads remove-all handler.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSkipForwardChange: (Long) -> Unit,
    onSkipBackChange: (Long) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onUnmeteredOnlyChange: (Boolean) -> Unit,
    onKeepLimitChange: (Int) -> Unit,
    onDeleteAfterPlayingChange: (Boolean) -> Unit,
    onRemoveAllDownloads: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                is SettingsMessage.DownloadsRemoved -> resources.getString(
                    R.string.settings_message_downloads_removed,
                    formatBytes(message.freedBytes),
                )
            },
        )
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_playback))

            ChoiceRow(
                title = stringResource(R.string.settings_playback_speed),
                options = PlaybackSettings.SPEED_STEPS,
                selected = uiState.playback.speed,
                label = { speed -> formatSpeed(speed) },
                onSelect = onSpeedChange,
            )

            ChoiceRow(
                title = stringResource(R.string.settings_skip_forward),
                options = SKIP_FORWARD_STEPS_MS,
                selected = uiState.playback.skipForwardMs,
                label = { millis -> formatSkip(millis) },
                onSelect = onSkipForwardChange,
            )

            ChoiceRow(
                title = stringResource(R.string.settings_skip_back),
                options = SKIP_BACK_STEPS_MS,
                selected = uiState.playback.skipBackMs,
                label = { millis -> formatSkip(millis) },
                onSelect = onSkipBackChange,
            )

            SwitchRow(
                title = stringResource(R.string.settings_auto_play_next_title),
                description = stringResource(R.string.settings_auto_play_next_description),
                checked = uiState.playback.autoPlayNext,
                onCheckedChange = onAutoPlayNextChange,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_downloads))

            SwitchRow(
                title = stringResource(R.string.settings_auto_download_title),
                description = stringResource(R.string.settings_auto_download_description),
                checked = uiState.downloads.autoDownloadNewEpisodes,
                onCheckedChange = onAutoDownloadChange,
            )

            SwitchRow(
                title = stringResource(R.string.settings_unmetered_title),
                description = stringResource(R.string.settings_unmetered_description),
                checked = uiState.downloads.unmeteredOnly,
                onCheckedChange = onUnmeteredOnlyChange,
            )

            ChoiceRow(
                title = stringResource(R.string.settings_keep_limit_title),
                options = DownloadSettings.KEEP_LIMIT_STEPS,
                selected = uiState.downloads.keepLimitPerPodcast,
                label = { limit -> formatKeepLimit(limit) },
                onSelect = onKeepLimitChange,
            )

            SwitchRow(
                title = stringResource(R.string.settings_delete_after_playing_title),
                description = stringResource(R.string.settings_delete_after_playing_description),
                checked = uiState.downloads.deleteAfterPlaying,
                onCheckedChange = onDeleteAfterPlayingChange,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_storage))

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_downloaded_episodes)) },
                supportingContent = {
                    Text(formatStorage(uiState.downloadedEpisodeCount, uiState.downloadedBytes))
                },
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_remove_all)) },
                supportingContent = {
                    Text(
                        if (uiState.hasDownloads) {
                            stringResource(
                                R.string.settings_remove_all_frees,
                                formatBytes(uiState.downloadedBytes),
                            )
                        } else {
                            stringResource(R.string.settings_remove_all_nothing)
                        },
                    )
                },
                leadingContent = {
                    Icon(imageVector = Icons.Rounded.DeleteSweep, contentDescription = null)
                },
                modifier = Modifier
                    .clickable(
                        enabled = uiState.hasDownloads && !uiState.isRemovingDownloads,
                        onClick = onRemoveAllDownloads,
                    )
                    .semantics { role = Role.Button },
            )
        }
    }
}

/**
 * A settings group heading.
 *
 * @param title the group's name.
 * @param modifier layout modifier.
 */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A labelled switch.
 *
 * The whole row toggles, not just the switch: a 48 dp target at the far edge of the screen is a
 * poor one, and TalkBack should announce one control rather than a row and a switch beside it.
 *
 * @param title the setting's name.
 * @param description one line explaining what it does.
 * @param checked current value.
 * @param onCheckedChange invoked with the new value.
 * @param modifier layout modifier.
 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Built here rather than inside `semantics`, which is not a composable scope.
    val rowDescription = stringResource(R.string.settings_switch_description, title, description)

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
 * A chip row rather than a dialog because every one of these settings has a handful of sensible
 * values and no free-form input, and seeing them all is faster than opening a picker.
 *
 * @param T the option type.
 * @param title the setting's name.
 * @param options every value on offer.
 * @param selected the current value.
 * @param label renders one option's caption; composable, because the captions come from
 *   resources.
 * @param onSelect invoked with the chosen value.
 * @param modifier layout modifier.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val optionLabel = label(option)
                val isSelected = option == selected
                // Built here rather than inside `semantics`, which is not a composable scope.
                val chipDescription =
                    stringResource(R.string.settings_choice_description, title, optionLabel)
                val chipState = stringResource(
                    if (isSelected) {
                        R.string.settings_chip_selected
                    } else {
                        R.string.settings_chip_not_selected
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

/** The skip-ahead intervals on offer; ad breaks are the reason the range reaches a minute. */
private val SKIP_FORWARD_STEPS_MS = listOf(10_000L, 15_000L, 30_000L, 45_000L, 60_000L)

/** The skip-back intervals on offer, shorter because skipping back is about a missed sentence. */
private val SKIP_BACK_STEPS_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

/**
 * Formats a playback rate as `1x` or `1.5x`, dropping a trailing `.0`.
 *
 * @param speed the rate.
 * @return the chip caption.
 */
@Composable
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100) / 100f
    val digits = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    return stringResource(R.string.settings_speed_format, digits)
}

/**
 * Formats a skip interval as `30 s`, or `1 min` at exactly a minute.
 *
 * @param millis the interval.
 * @return the chip caption.
 */
@Composable
private fun formatSkip(millis: Long): String {
    val seconds = millis / 1_000L
    return if (seconds >= 60L && seconds % 60L == 0L) {
        stringResource(R.string.settings_skip_minutes, seconds / 60L)
    } else {
        stringResource(R.string.settings_skip_seconds, seconds)
    }
}

/**
 * Formats a keep-limit, spelling out the "keep everything" sentinel.
 *
 * @param limit the configured limit.
 * @return the chip caption.
 */
@Composable
private fun formatKeepLimit(limit: Int): String = when (limit) {
    DownloadSettings.KEEP_ALL -> stringResource(R.string.settings_keep_limit_all)
    else -> limit.toString()
}

/**
 * Formats the storage summary.
 *
 * @param count how many episodes are downloaded.
 * @param bytes how much space they take.
 * @return the supporting line under "Downloaded episodes".
 */
@Composable
private fun formatStorage(count: Int, bytes: Long): String = when (count) {
    0 -> stringResource(R.string.settings_storage_none)

    else -> stringResource(
        R.string.settings_storage_summary,
        pluralStringResource(R.plurals.settings_episode_count, count, count),
        formatBytes(bytes),
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    BPodcatTheme {
        SettingsScreen(
            uiState = SettingsUiState(
                playback = PlaybackSettings(speed = 1.2f),
                downloads = DownloadSettings(autoDownloadNewEpisodes = true),
                downloadedEpisodeCount = 7,
                downloadedBytes = 512_000_000L,
            ),
            onBack = {},
            onSpeedChange = {},
            onSkipForwardChange = {},
            onSkipBackChange = {},
            onAutoPlayNextChange = {},
            onAutoDownloadChange = {},
            onUnmeteredOnlyChange = {},
            onKeepLimitChange = {},
            onDeleteAfterPlayingChange = {},
            onRemoveAllDownloads = {},
            onMessageShown = {},
        )
    }
}
