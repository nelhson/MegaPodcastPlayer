package md.borisveriga.megapodcastplayer.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import md.borisveriga.megapodcastplayer.core.common.format.formatBytes
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.designsystem.R as DesignSystemR
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.component.SectionHeader
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsChoiceRow
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsSwitchRow
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice

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

    // Created here rather than in SettingsScreen so the stateless screen stays testable under
    // createComposeRule, which has no activity result registry to register against.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(OPML_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportTo) }

    // Deliberately loose: an OPML file arrives from another app, and other apps disagree about
    // whether `.opml` is `text/x-opml`, `text/xml`, `application/xml` or nothing at all. A filter
    // that hides the file the user came here to import would be the one failure they cannot work
    // around, and a wrong file is refused by decoding a moment later anyway.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::prepareImport) }

    // Resolved here rather than in the stateless screen, which has no activity to start one from
    // under `createComposeRule`.
    val context = LocalContext.current

    SettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onThemeChange = viewModel::setTheme,
        onDynamicColorChange = viewModel::setDynamicColor,
        onPureBlackChange = viewModel::setPureBlack,
        onOpenNotificationSettings = { context.openNotificationSettings() },
        onSpeedChange = viewModel::setSpeed,
        onSkipForwardChange = viewModel::setSkipForward,
        onSkipBackChange = viewModel::setSkipBack,
        onAutoPlayNextChange = viewModel::setAutoPlayNext,
        onAutoDownloadChange = viewModel::setAutoDownload,
        onUnmeteredOnlyChange = viewModel::setUnmeteredOnly,
        onKeepLimitChange = viewModel::setKeepLimit,
        onDeleteAfterPlayingChange = viewModel::setDeleteAfterPlaying,
        onRemoveAllDownloads = viewModel::removeAllDownloads,
        onExportSubscriptions = { exportLauncher.launch(viewModel.suggestedFileName()) },
        onImportSubscriptions = { importLauncher.launch(OPML_PICKER_TYPES) },
        onConfirmRestore = viewModel::confirmRestore,
        onCancelRestore = viewModel::cancelRestore,
        onRestoreResultShown = viewModel::acknowledgeRestoreResult,
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
 * @param onThemeChange light/dark/system handler.
 * @param onDynamicColorChange wallpaper-palette toggle handler.
 * @param onPureBlackChange true-black-in-dark toggle handler.
 * @param onOpenNotificationSettings opens the system's own notification settings for this app.
 * @param onSpeedChange playback rate handler.
 * @param onSkipForwardChange skip-ahead interval handler.
 * @param onSkipBackChange skip-back interval handler.
 * @param onAutoPlayNextChange auto-play toggle handler.
 * @param onAutoDownloadChange auto-download toggle handler.
 * @param onUnmeteredOnlyChange Wi-Fi-only toggle handler.
 * @param onKeepLimitChange keep-limit handler.
 * @param onDeleteAfterPlayingChange delete-after-playing toggle handler.
 * @param onRemoveAllDownloads remove-all handler.
 * @param onExportSubscriptions called when the user asks to write the subscription list.
 * @param onImportSubscriptions called when the user asks to read one.
 * @param onConfirmRestore called when the user accepts a picked subscription list.
 * @param onCancelRestore called when the user backs out of one.
 * @param onRestoreResultShown called when the user dismisses the finished import's report, which
 *   is what stops it being shown again.
 * @param onMessageShown called once a snackbar message has been displayed.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPureBlackChange: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSkipForwardChange: (Long) -> Unit,
    onSkipBackChange: (Long) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onUnmeteredOnlyChange: (Boolean) -> Unit,
    onKeepLimitChange: (Int) -> Unit,
    onDeleteAfterPlayingChange: (Boolean) -> Unit,
    onRemoveAllDownloads: () -> Unit,
    onExportSubscriptions: () -> Unit,
    onImportSubscriptions: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onRestoreResultShown: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Saveable so that opening the Fold 7 mid-read does not close the licence text.
    var fontLicencesOpen by rememberSaveable { mutableStateOf(false) }
    // Resolved in composition: `LaunchedEffect` runs outside it, where `stringResource` is not
    // available. `LocalResources` rather than `LocalContext.current.resources`, so a configuration
    // change invalidates the read.
    val resources = LocalResources.current

    // Whether the "remove all downloads" confirmation is open. A question about this screen, not
    // about the app: nothing has happened yet, and a dialog that survived a process death would
    // reappear asking about downloads the user has since forgotten they were asked about.
    var confirmRemoveAll by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                is SettingsMessage.DownloadsRemoved -> resources.getString(
                    R.string.settings_message_downloads_removed,
                    formatBytes(resources, message.freedBytes),
                )

                SettingsMessage.SubscriptionsExported ->
                    resources.getString(R.string.settings_subscriptions_exported)

                SettingsMessage.SubscriptionsExportFailed ->
                    resources.getString(R.string.settings_subscriptions_export_failed)

                SettingsMessage.SubscriptionsReadFailed ->
                    resources.getString(R.string.settings_subscriptions_read_failed)

                SettingsMessage.SubscriptionsNotRecognised ->
                    resources.getString(R.string.settings_subscriptions_not_recognised)

                SettingsMessage.SubscriptionsEmpty ->
                    resources.getString(R.string.settings_subscriptions_empty)
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
            // First, because it is the only section whose effect is visible while it is being
            // chosen: everything else here is a rule that applies later.
            SectionHeader(text = stringResource(R.string.settings_section_appearance))
            SettingsCard {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_theme),
                    options = ThemeChoice.entries,
                    selected = uiState.appearance.theme,
                    label = { choice -> stringResource(choice.labelResId) },
                    onSelect = onThemeChange,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_dynamic_color_title),
                    description = stringResource(R.string.settings_dynamic_color_description),
                    checked = uiState.appearance.dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_pure_black_title),
                    // Says what it is for, and — when the app is light — that it is currently
                    // doing nothing, rather than leaving a switch that visibly changes nothing.
                    description = stringResource(
                        if (uiState.appearance.isDark(isSystemInDarkTheme())) {
                            R.string.settings_pure_black_description
                        } else {
                            R.string.settings_pure_black_description_light
                        },
                    ),
                    checked = uiState.appearance.pureBlack,
                    onCheckedChange = onPureBlackChange,
                )
            }

            SectionHeader(text = stringResource(R.string.settings_section_notifications))
            SettingsCard {
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(R.string.settings_notification_channels))
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(
                                R.string.settings_notification_channels_description,
                            ),
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsNone,
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clickable(onClick = onOpenNotificationSettings)
                        .semantics { role = Role.Button },
                )
            }

            SectionHeader(text = stringResource(R.string.settings_section_playback))
            SettingsCard {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_playback_speed),
                    options = PlaybackSettings.SPEED_STEPS,
                    selected = uiState.playback.speed,
                    label = { speed -> formatSpeed(speed) },
                    onSelect = onSpeedChange,
                    // A default is only a default if what departs from it is named. Until the
                    // per-show rate existed this row *was* the speed; now it is the speed of every
                    // show that has not said otherwise, and the ones that have are listed here so
                    // that a rate the user does not recognise has somewhere to be explained.
                    description = speedOverridesText(uiState.speedOverrides),
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

            SectionHeader(text = stringResource(R.string.settings_section_about))
            SettingsCard {
                AboutRows(
                    isCrashReporting = uiState.isCrashReporting,
                    onOpenFontLicences = { fontLicencesOpen = true },
                )
            }

            SectionHeader(text = stringResource(R.string.settings_section_subscriptions))
            SettingsCard {
                BackupRows(
                    state = uiState.backup,
                    onExport = onExportSubscriptions,
                    onImport = onImportSubscriptions,
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
                                    formatBytes(resources, uiState.downloadedBytes),
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
                            onClick = { confirmRemoveAll = true },
                        )
                        .semantics { role = Role.Button },
                )
            }
        }

        if (fontLicencesOpen) {
            FontLicencesDialog(onDismiss = { fontLicencesOpen = false })
        }

        if (confirmRemoveAll) {
            RemoveAllDownloadsDialog(
                episodeCount = uiState.downloadedEpisodeCount,
                bytes = uiState.downloadedBytes,
                onConfirm = {
                    confirmRemoveAll = false
                    onRemoveAllDownloads()
                },
                onDismiss = { confirmRemoveAll = false },
            )
        }

        uiState.backup.pendingRestore?.let { pending ->
            RestoreConfirmDialog(
                pending = pending,
                onConfirm = onConfirmRestore,
                onDismiss = onCancelRestore,
            )
        }

        // A finished run is retained and replayed for as long as the work lives, so dismissing
        // this dialog is recorded outside the screen: a summary read once is not a week of
        // announcing the same import on every visit to settings.
        (uiState.backup.restore as? RestoreRun.Finished)?.let { finished ->
            RestoreResultDialog(
                summary = finished.summary,
                onDismiss = onRestoreResultShown,
            )
        }
    }
}

/**
 * The caption on each theme choice.
 *
 * Beside the screen rather than on [ThemeChoice]: what the three choices *are* is a fact about the
 * app and lives in `:core:model`, the words for them belong with this module's `strings.xml`.
 */
@get:StringRes
private val ThemeChoice.labelResId: Int
    get() = when (this) {
        ThemeChoice.SYSTEM -> R.string.settings_theme_system
        ThemeChoice.LIGHT -> R.string.settings_theme_light
        ThemeChoice.DARK -> R.string.settings_theme_dark
    }

/**
 * Opens the system's notification settings for this app.
 *
 * The system page rather than a set of switches here, because every one of those switches already
 * exists in Android and is the one the user will find again when they go looking. What this app
 * owns — whether a *particular show* is worth an interruption — is on the show's own page, where
 * the show is; see `ShowSettingsSheet`.
 *
 * A device with no such screen is not worth a message: nothing has been lost and there is nothing
 * to say about it. Every phone this app runs on has one.
 */
private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Deliberately silent; see above.
    }
}

/**
 * What the app can say about itself: which build, whether it reports, and whose fonts it uses.
 *
 * Its own composable rather than three more `ListItem`s in the screen's column, because the screen
 * is already at the complexity the build allows — and because these three are the only rows here
 * that report rather than change anything.
 *
 * @param isCrashReporting whether handled failures actually leave the device.
 * @param onOpenFontLicences opens the licence text.
 */
@Composable
private fun AboutRows(isCrashReporting: Boolean, onOpenFontLicences: () -> Unit) {
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_version)) },
        supportingContent = { Text(text = appVersionName()) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_crash_reporting)) },
        // States which of the two builds this is rather than offering a switch there is nothing
        // behind: whether anything is sent was decided by whether a configuration file was present
        // when the APK was built.
        supportingContent = {
            Text(
                text = stringResource(
                    if (isCrashReporting) {
                        R.string.settings_crash_reporting_on
                    } else {
                        R.string.settings_crash_reporting_off
                    },
                ),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_font_licences)) },
        supportingContent = {
            Text(text = stringResource(R.string.settings_font_licences_description))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onOpenFontLicences)
            .semantics { role = Role.Button },
    )
}

/**
 * The bundled fonts' licences, verbatim.
 *
 * Read from a raw resource rather than held in `strings.xml`: it is a page of legal text that is
 * never translated, never interpolated and only ever displayed whole, and the OFL requires it to
 * travel with the software — which for a user means the APK, not the repository. `docs/` used to
 * hold the only copy, which satisfied nobody who had not cloned the project.
 *
 * @param onDismiss closes it.
 */
@Composable
private fun FontLicencesDialog(onDismiss: () -> Unit) {
    val resources = LocalResources.current
    // Read once, off the composition's hot path. Ten kilobytes of text, and it is read only when
    // the dialog is actually opened.
    val text = remember(resources) {
        resources.openRawResource(DesignSystemR.raw.font_licenses)
            .bufferedReader()
            .use { it.readText() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_font_licences)) },
        text = {
            Text(
                text = text,
                style = MegaPodcastPlayerTheme.type.numeric,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_close))
            }
        },
    )
}

/**
 * This build's version, as the package manager reports it.
 *
 * Read from the installed package rather than from a `BuildConfig`: this is a feature module, and
 * its `BuildConfig` describes the library's own variant, not the APK the user is holding.
 *
 * @return the version name, or a dash on the platforms and test runners that report none.
 */
@Composable
private fun appVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }.ifEmpty { stringResource(R.string.settings_version_unknown) }
}

/**
 * The shows that play at a rate other than the app's, as one line.
 *
 * Null when there are none: a row that said "no shows override this" would be explaining a feature
 * rather than reporting a fact, on a screen that has twenty other rows to get through.
 *
 * @param overrides the shows and their rates.
 * @return the line, or null.
 */
@Composable
private fun speedOverridesText(overrides: List<ShowSpeedOverride>): String? {
    if (overrides.isEmpty()) return null
    val entryFormat = stringResource(R.string.settings_speed_override)
    val separator = stringResource(R.string.settings_list_separator)
    // Formatted rather than composed row by row: `joinToString` takes an ordinary lambda, and a
    // composable one cannot be passed to it.
    val named = overrides.joinToString(separator) { override ->
        entryFormat.format(override.title, formatSpeed(override.speed))
    }
    return stringResource(R.string.settings_speed_overrides, named)
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
 * Asks before deleting every downloaded episode.
 *
 * This was the one destructive action in the app that fired on a single tap. Every other one —
 * removing a show, restoring a backup, deleting a moment — counts what is at stake first, and this
 * is the one whose cost is measured in gigabytes and a re-download over mobile data. There is no
 * undo to offer instead: the audio is gone from the device, so the question comes first.
 *
 * The body names both numbers, because either alone is the wrong one to decide on: forty episodes
 * sounds like a lot and might be 200 MB, and 3 GB sounds like a lot and might be four.
 *
 * @param episodeCount how many episodes will be deleted.
 * @param bytes how much space they free.
 * @param onConfirm proceeds with the deletion.
 * @param onDismiss closes the dialog, changing nothing.
 */
@Composable
private fun RemoveAllDownloadsDialog(
    episodeCount: Int,
    bytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_remove_all_confirm_title)) },
        text = {
            Text(
                text = pluralStringResource(
                    R.plurals.settings_remove_all_confirm_body,
                    episodeCount,
                    episodeCount,
                    formatBytes(LocalResources.current, bytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_remove_all_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
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
        formatBytes(LocalResources.current, bytes),
    )
}

@ThemePreviews
@FontScalePreviews
@Composable
internal fun SettingsScreenPreview() {
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
            onThemeChange = {},
            onDynamicColorChange = {},
            onPureBlackChange = {},
            onOpenNotificationSettings = {},
            onRemoveAllDownloads = {},
            onExportSubscriptions = {},
            onImportSubscriptions = {},
            onConfirmRestore = {},
            onCancelRestore = {},
            onRestoreResultShown = {},
            onMessageShown = {},
        )
    }
}

/**
 * What an exported subscription list is created as.
 *
 * `text/x-opml` is the type the format's own spec names, and enough document providers have never
 * heard of it that the file would land without an extension — so the suggested name carries
 * `.opml` and this stays the honest label rather than a lie that happens to be recognised.
 */
private const val OPML_MIME_TYPE = "text/x-opml"

/** The types the import picker offers; wider still, for the reason its launcher gives. */
private val OPML_PICKER_TYPES =
    arrayOf("text/x-opml", "text/xml", "application/xml", "text/plain", "*/*")
