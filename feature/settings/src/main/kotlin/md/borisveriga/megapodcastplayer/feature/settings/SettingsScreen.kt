package md.borisveriga.megapodcastplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.common.format.formatBytes
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.SectionHeader
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsChoiceRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsSwitchRow
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

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
 * The three groups are cards rather than runs of rows between dividers. A divider says "these two
 * things are different"; a card says "these belong together", which is what a settings group is —
 * and it means a long screen can be scanned by shape rather than read line by line.
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
            MegaPodcastPlayerTopAppBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backDescription = stringResource(R.string.settings_back),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
        ) {
            SectionHeader(text = stringResource(R.string.settings_section_playback))
            SettingsCard {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_playback_speed),
                    options = PlaybackSettings.SPEED_STEPS,
                    selected = uiState.playback.speed,
                    label = { speed -> formatSpeed(speed) },
                    onSelect = onSpeedChange,
                )

                SettingsChoiceRow(
                    title = stringResource(R.string.settings_skip_forward),
                    options = SKIP_FORWARD_STEPS_MS,
                    selected = uiState.playback.skipForwardMs,
                    label = { millis -> formatSkip(millis) },
                    onSelect = onSkipForwardChange,
                )

                SettingsChoiceRow(
                    title = stringResource(R.string.settings_skip_back),
                    options = SKIP_BACK_STEPS_MS,
                    selected = uiState.playback.skipBackMs,
                    label = { millis -> formatSkip(millis) },
                    onSelect = onSkipBackChange,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_play_next_title),
                    description = stringResource(R.string.settings_auto_play_next_description),
                    checked = uiState.playback.autoPlayNext,
                    onCheckedChange = onAutoPlayNextChange,
                )
            }

            SectionHeader(text = stringResource(R.string.settings_section_downloads))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_download_title),
                    description = stringResource(R.string.settings_auto_download_description),
                    checked = uiState.downloads.autoDownloadNewEpisodes,
                    onCheckedChange = onAutoDownloadChange,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_unmetered_title),
                    description = stringResource(R.string.settings_unmetered_description),
                    checked = uiState.downloads.unmeteredOnly,
                    onCheckedChange = onUnmeteredOnlyChange,
                )

                SettingsChoiceRow(
                    title = stringResource(R.string.settings_keep_limit_title),
                    options = DownloadSettings.KEEP_LIMIT_STEPS,
                    selected = uiState.downloads.keepLimitPerPodcast,
                    label = { limit -> formatKeepLimit(limit) },
                    onSelect = onKeepLimitChange,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_delete_after_playing_title),
                    description = stringResource(R.string.settings_delete_after_playing_description),
                    checked = uiState.downloads.deleteAfterPlaying,
                    onCheckedChange = onDeleteAfterPlayingChange,
                )
            }

            SectionHeader(text = stringResource(R.string.settings_section_storage))
            SettingsCard {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(R.string.settings_downloaded_episodes))
                    },
                    supportingContent = {
                        Text(
                            text = formatStorage(
                                uiState.downloadedEpisodeCount,
                                uiState.downloadedBytes,
                            ),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                ListItem(
                    headlineContent = { Text(text = stringResource(R.string.settings_remove_all)) },
                    supportingContent = {
                        Text(
                            text = if (uiState.hasDownloads) {
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
}

/**
 * One group of settings, on its own ground.
 *
 * @param modifier layout modifier.
 * @param content the rows in the group.
 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = MegaPodcastPlayerTheme.spacing.sm),
            content = content,
        )
    }
}

/** The skip-ahead intervals on offer; ad breaks are the reason the range reaches a minute. */
private val SKIP_FORWARD_STEPS_MS = listOf(10_000L, 15_000L, 30_000L, 45_000L, 60_000L)

/** The skip-back intervals on offer, shorter because skipping back is about a missed sentence. */
private val SKIP_BACK_STEPS_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

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
    MegaPodcastPlayerTheme {
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
