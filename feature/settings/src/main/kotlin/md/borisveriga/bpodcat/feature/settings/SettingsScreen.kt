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

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                is SettingsMessage.DownloadsRemoved ->
                    "Removed all downloads, freeing ${formatBytes(message.freedBytes)}"
            },
        )
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
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
            SectionHeader("Playback")

            ChoiceRow(
                title = "Playback speed",
                options = PlaybackSettings.SPEED_STEPS,
                selected = uiState.playback.speed,
                label = { speed -> formatSpeed(speed) },
                onSelect = onSpeedChange,
            )

            ChoiceRow(
                title = "Skip forward",
                options = SKIP_FORWARD_STEPS_MS,
                selected = uiState.playback.skipForwardMs,
                label = { millis -> formatSkip(millis) },
                onSelect = onSkipForwardChange,
            )

            ChoiceRow(
                title = "Skip back",
                options = SKIP_BACK_STEPS_MS,
                selected = uiState.playback.skipBackMs,
                label = { millis -> formatSkip(millis) },
                onSelect = onSkipBackChange,
            )

            SwitchRow(
                title = "Play next automatically",
                description = "Start the next queued episode when one finishes",
                checked = uiState.playback.autoPlayNext,
                onCheckedChange = onAutoPlayNextChange,
            )

            HorizontalDivider()
            SectionHeader("Downloads")

            SwitchRow(
                title = "Download new episodes",
                description = "Save episodes for offline listening as they are published",
                checked = uiState.downloads.autoDownloadNewEpisodes,
                onCheckedChange = onAutoDownloadChange,
            )

            SwitchRow(
                title = "Wi-Fi only",
                description = "Wait for an unmetered network before downloading",
                checked = uiState.downloads.unmeteredOnly,
                onCheckedChange = onUnmeteredOnlyChange,
            )

            ChoiceRow(
                title = "Episodes to keep per show",
                options = DownloadSettings.KEEP_LIMIT_STEPS,
                selected = uiState.downloads.keepLimitPerPodcast,
                label = { limit -> formatKeepLimit(limit) },
                onSelect = onKeepLimitChange,
            )

            SwitchRow(
                title = "Delete after playing",
                description = "Free up storage when an episode finishes",
                checked = uiState.downloads.deleteAfterPlaying,
                onCheckedChange = onDeleteAfterPlayingChange,
            )

            HorizontalDivider()
            SectionHeader("Storage")

            ListItem(
                headlineContent = { Text("Downloaded episodes") },
                supportingContent = {
                    Text(formatStorage(uiState.downloadedEpisodeCount, uiState.downloadedBytes))
                },
            )

            ListItem(
                headlineContent = { Text("Remove all downloads") },
                supportingContent = {
                    Text(
                        if (uiState.hasDownloads) {
                            "Frees ${formatBytes(uiState.downloadedBytes)}"
                        } else {
                            "Nothing is downloaded"
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
                contentDescription = "$title. $description"
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
 * @param label renders one option's caption.
 * @param onSelect invoked with the chosen value.
 * @param modifier layout modifier.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
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
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel) },
                    // Without this the chip announces only its own caption — "30 s" tells a
                    // TalkBack user nothing about which setting they are changing.
                    modifier = Modifier.semantics {
                        contentDescription = "$title: $optionLabel"
                        stateDescription = if (option == selected) "Selected" else "Not selected"
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

/** Formats a playback rate as `1x` or `1.5x`, dropping a trailing `.0`. */
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100) / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
}

/** Formats a skip interval as `30 s`, or `1 min` at exactly a minute. */
private fun formatSkip(millis: Long): String {
    val seconds = millis / 1_000L
    return if (seconds >= 60L && seconds % 60L == 0L) "${seconds / 60} min" else "$seconds s"
}

/** Formats a keep-limit, spelling out the "keep everything" sentinel. */
private fun formatKeepLimit(limit: Int): String = when (limit) {
    DownloadSettings.KEEP_ALL -> "All"
    1 -> "1"
    else -> "$limit"
}

/**
 * Formats the storage summary.
 *
 * @param count how many episodes are downloaded.
 * @param bytes how much space they take.
 */
private fun formatStorage(count: Int, bytes: Long): String = when (count) {
    0 -> "None"
    1 -> "1 episode · ${formatBytes(bytes)}"
    else -> "$count episodes · ${formatBytes(bytes)}"
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
