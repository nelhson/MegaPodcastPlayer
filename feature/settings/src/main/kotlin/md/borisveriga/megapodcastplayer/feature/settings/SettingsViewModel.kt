package md.borisveriga.megapodcastplayer.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.backup.LibraryRestorer
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice
import md.borisveriga.megapodcastplayer.core.model.backup.BackupCodec
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlCodec
import md.borisveriga.megapodcastplayer.core.model.backup.OpmlDecodeResult

/**
 * State rendered by the settings screen.
 *
 * @property playback the user's playback preferences.
 * @property downloads the user's download rules.
 * @property appearance how the app draws itself. Read here as well as in the activity that applies
 *   it, because this is the screen where it is chosen.
 * @property downloadedEpisodeCount how many episodes are available offline.
 * @property downloadedBytes how much storage those downloads occupy.
 * @property isRemovingDownloads true while "remove all downloads" is in flight, so the row can be
 *   disabled rather than let a second tap race the first.
 * @property backup everything the subscriptions section renders.
 * @property speedOverrides the shows that play at a rate of their own, alphabetically. What turns
 *   the playback row from "the speed" into "the *default* speed": a default that never names its
 *   exceptions is indistinguishable from a setting that is being quietly ignored (SET-6).
 * @property isCrashReporting whether handled failures actually leave the device. A build with no
 *   Firebase configuration reports nothing, and an app carrying a crash reporter should say which
 *   of the two it is somewhere the user can read (SET-4).
 * @property message a one-off outcome for the snackbar.
 */
data class SettingsUiState(
    val playback: PlaybackSettings = PlaybackSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val downloadedEpisodeCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val isRemovingDownloads: Boolean = false,
    val backup: BackupUiState = BackupUiState(),
    val speedOverrides: List<ShowSpeedOverride> = emptyList(),
    val isCrashReporting: Boolean = false,
    val message: SettingsMessage? = null,
) {
    /** True when there is anything on disk to free. */
    val hasDownloads: Boolean get() = downloadedEpisodeCount > 0
}

/**
 * One show that plays at a rate of its own.
 *
 * Carries the title rather than the id: this exists to be read, and the settings screen has no
 * other reason to know what a podcast id is.
 *
 * @property title the show.
 * @property speed the rate it plays at.
 */
data class ShowSpeedOverride(val title: String, val speed: Float)

/** A one-off outcome to show the user. */
sealed interface SettingsMessage {

    /**
     * Every download was removed.
     *
     * @property freedBytes how much storage came back, so the confirmation is worth reading.
     */
    data class DownloadsRemoved(val freedBytes: Long) : SettingsMessage

    /** The subscription list was written to the document the user chose. */
    data object SubscriptionsExported : SettingsMessage

    /** The document could not be written — a revoked grant, or a provider that went away. */
    data object SubscriptionsExportFailed : SettingsMessage

    /** The picked document could not be read at all. */
    data object SubscriptionsReadFailed : SettingsMessage

    /** The picked file is not OPML: another app's export in some other format, or a feed. */
    data object SubscriptionsNotRecognised : SettingsMessage

    /**
     * Readable OPML with nothing in it to subscribe to.
     *
     * Its own message rather than a zero-show import, because "the file is empty" and "the file is
     * wrong" are different things to be told, and only one of them is worth going back for.
     */
    data object SubscriptionsEmpty : SettingsMessage
}

/**
 * Drives the settings screen.
 *
 * Writes go straight to the repositories and come back through the same flows the screen renders,
 * so there is no local copy of a preference that could drift from what is stored. The one piece of
 * state held here is [SettingsUiState.isRemovingDownloads], which describes an in-flight action
 * rather than a setting.
 *
 * @property playbackRepository playback speed, skip intervals and auto-play.
 * @property downloadRepository the download rules and the downloads themselves.
 * @property backupRepository reads the library's subscriptions into a document and puts them back.
 * @property backupFileStore reads and writes the document the user picked.
 * @property libraryRestorer runs an import somewhere that outlives this screen.
 * @property uiPreferences the appearance choices; the same repository the library's layout and
 *   order live in, for the same reason — none of it changes what the app does.
 * @property podcastRepository the library, read only for the titles of the shows that override the
 *   app's playback rate.
 * @property showSettingsRepository which shows have overridden it.
 * @property crashReporter asked one question — whether it reports at all.
 * @property clock names the exported file after the day it was written.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val downloadRepository: DownloadRepository,
    private val backupRepository: BackupRepository,
    private val backupFileStore: BackupFileStore,
    private val libraryRestorer: LibraryRestorer,
    private val uiPreferences: UiPreferencesRepository,
    private val podcastRepository: PodcastRepository,
    private val showSettingsRepository: ShowSettingsRepository,
    private val crashReporter: CrashReporter,
    private val clock: Clock,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    /**
     * The subscriptions section's own state, combined before the rest.
     *
     * Folded into one flow rather than added as two more arms of the main [combine]: the section is
     * a self-contained concern, and the alternative is a five-argument lambda whose parameters have
     * to be read positionally.
     */
    private val backupState: kotlinx.coroutines.flow.Flow<BackupUiState> = combine(
        backupRepository.observeLastBackupAt(),
        libraryRestorer.observe(),
        backupRepository.observeAcknowledgedRestoreId(),
        transientState,
    ) { lastBackupAt, restore, acknowledgedRestoreId, transient ->
        BackupUiState(
            lastBackupAtMs = lastBackupAt,
            isExporting = transient.isExporting,
            pendingRestore = transient.pendingRestore,
            // A finished run is retained and replayed for days, so the one the user has already
            // been told about is dropped here rather than reported again on the next visit.
            restore = restore.takeUnless {
                it is RestoreRun.Finished && it.id == acknowledgedRestoreId
            },
        )
    }

    /**
     * The shows that play at a rate other than the app's, named and in alphabetical order.
     *
     * Joined here rather than in the screen because it is a join: the rates are in preferences and
     * the titles are in the database, and an override whose show has since been removed is dropped
     * rather than drawn as a blank line. Alphabetical because there is no other order — these are
     * decisions made at unrelated times about unrelated shows.
     */
    private val speedOverrides: kotlinx.coroutines.flow.Flow<List<ShowSpeedOverride>> = combine(
        showSettingsRepository.observeAll(),
        podcastRepository.observeLibrary(),
    ) { settings, library ->
        library
            .mapNotNull { entry ->
                settings[entry.podcast.id]
                    ?.speed
                    ?.let { speed -> ShowSpeedOverride(entry.podcast.title, speed) }
            }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * The three sets of stored preferences, combined before the rest.
     *
     * Folded together for the same reason [backupState] is, with an added constraint: `combine`
     * has a typed overload for five flows and no more, and the alternative is an array of `Any?`
     * unpacked by position.
     */
    private val preferences: kotlinx.coroutines.flow.Flow<StoredPreferences> = combine(
        playbackRepository.observePlaybackSettings(),
        downloadRepository.observeDownloadSettings(),
        uiPreferences.observeAppearance(),
        speedOverrides,
        ::StoredPreferences,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences,
        downloadRepository.observeDownloadedEpisodes(),
        backupState,
        transientState,
    ) { stored, downloaded, backup, transient ->
        SettingsUiState(
            playback = stored.playback,
            downloads = stored.downloads,
            appearance = stored.appearance,
            speedOverrides = stored.speedOverrides,
            isCrashReporting = crashReporter.isReporting,
            downloadedEpisodeCount = downloaded.size,
            // Summed from the rows rather than read from the cache so the figure updates with the
            // list it sits next to; the exact on-disk total is refreshed by refreshStorageUsage().
            downloadedBytes = transient.downloadedBytes
                ?: downloaded.sumOf { it.downloadedBytes },
            isRemovingDownloads = transient.isRemovingDownloads,
            backup = backup,
            message = transient.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    init {
        refreshStorageUsage()
    }

    /**
     * Re-reads the exact storage the downloads occupy.
     *
     * The per-episode byte counts add up to slightly less than the cache actually holds — partial
     * downloads and Media3's own index are not in any episode row — so the screen shows the cache's
     * own figure once it has been read.
     */
    fun refreshStorageUsage() {
        viewModelScope.launch {
            val bytes = downloadRepository.downloadedBytes()
            transientState.value = transientState.value.copy(downloadedBytes = bytes)
        }
    }

    /**
     * Chooses the palette the app draws itself in.
     *
     * @param theme the palette to use from now on.
     */
    fun setTheme(theme: ThemeChoice) {
        viewModelScope.launch { uiPreferences.setTheme(theme) }
    }

    /**
     * Turns the wallpaper-derived palette on or off.
     *
     * @param enabled true for Material You's colours, false for the app's own.
     */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.setDynamicColor(enabled) }
    }

    /**
     * Turns true-black backgrounds on or off for the dark theme.
     *
     * @param enabled true for black rather than very dark grey.
     */
    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.setPureBlack(enabled) }
    }

    /** Sets the playback rate. */
    fun setSpeed(speed: Float) {
        viewModelScope.launch { playbackRepository.setSpeed(speed) }
    }

    /**
     * Sets how far the skip-ahead button jumps, leaving the skip-back interval alone.
     *
     * The repository takes both at once, so the current back interval is passed through unchanged.
     */
    fun setSkipForward(forwardMs: Long) {
        viewModelScope.launch {
            playbackRepository.setSkipIntervals(
                forwardMs = forwardMs,
                backMs = uiState.value.playback.skipBackMs,
            )
        }
    }

    /** Sets how far the skip-back button jumps; see [setSkipForward]. */
    fun setSkipBack(backMs: Long) {
        viewModelScope.launch {
            playbackRepository.setSkipIntervals(
                forwardMs = uiState.value.playback.skipForwardMs,
                backMs = backMs,
            )
        }
    }

    /** Enables or disables starting the next queued episode when one finishes. */
    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { playbackRepository.setAutoPlayNext(enabled) }
    }

    /** Enables or disables downloading episodes as a refresh discovers them. */
    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { downloadRepository.setAutoDownloadNewEpisodes(enabled) }
    }

    /** Sets whether downloads wait for an unmetered network. */
    fun setUnmeteredOnly(enabled: Boolean) {
        viewModelScope.launch { downloadRepository.setUnmeteredOnly(enabled) }
    }

    /** Sets how many newly discovered episodes auto-download fetches per show. */
    fun setKeepLimit(limit: Int) {
        viewModelScope.launch { downloadRepository.setKeepLimitPerPodcast(limit) }
    }

    /** Sets whether finishing an episode removes its downloaded audio. */
    fun setDeleteAfterPlaying(enabled: Boolean) {
        viewModelScope.launch { downloadRepository.setDeleteAfterPlaying(enabled) }
    }

    /**
     * Removes every download.
     *
     * The freed figure is captured before the removal, because afterwards there is nothing left to
     * measure.
     */
    fun removeAllDownloads() {
        if (transientState.value.isRemovingDownloads) return
        val freedBytes = uiState.value.downloadedBytes
        transientState.value = transientState.value.copy(isRemovingDownloads = true)
        viewModelScope.launch {
            downloadRepository.removeAllDownloads()
            transientState.value = transientState.value.copy(
                isRemovingDownloads = false,
                downloadedBytes = downloadRepository.downloadedBytes(),
                message = SettingsMessage.DownloadsRemoved(freedBytes),
            )
        }
    }

    /**
     * Names the exported subscription list.
     *
     * @return a name carrying today's date, so a folder of exports sorts chronologically.
     */
    fun suggestedFileName(): String =
        FILE_NAME_PREFIX +
            FILE_NAME_DATE.format(clock.instant().atZone(ZoneId.systemDefault())) +
            FILE_NAME_SUFFIX

    /**
     * Writes the subscription list to the document the user created.
     *
     * Recorded as the export the section's "last exported" line reports. There is one export now,
     * so there is one date, and it answers the question that line exists for: the database is
     * recreated rather than migrated, and this file is what puts the shows back.
     *
     * @param uri the document the picker returned.
     */
    fun exportTo(uri: Uri) {
        if (transientState.value.isExporting) return
        transientState.value = transientState.value.copy(isExporting = true)
        viewModelScope.launch {
            val file = backupRepository.export()
            val document = OpmlCodec.encode(
                podcasts = file.podcasts,
                title = OPML_DOCUMENT_TITLE,
                exportedAtMs = file.exportedAtMs,
            )
            val written = backupFileStore.write(uri, document)
            if (written.isSuccess) {
                backupRepository.recordExported(file.exportedAtMs)
            }
            transientState.value = transientState.value.copy(
                isExporting = false,
                message = if (written.isSuccess) {
                    SettingsMessage.SubscriptionsExported
                } else {
                    SettingsMessage.SubscriptionsExportFailed
                },
            )
        }
    }

    /**
     * Reads a subscription list the user picked, and asks them to confirm subscribing to it.
     *
     * Validation happens here rather than in the worker so that a foreign or truncated file is
     * refused while the user is still in the flow that produced it, and so that nothing is enqueued
     * that is going to fail anyway.
     *
     * The file is turned into a `BackupFile` of subscriptions and handed to the restorer — the same
     * worker, the same progress, the same by-name report of the feeds that could not be fetched.
     * Writing a second importer would mean a second answer to every question the restorer has
     * already answered, starting with what happens when the process is killed nine shows in.
     *
     * @param uri the document the picker returned.
     */
    fun prepareImport(uri: Uri) {
        if (transientState.value.pendingRestore != null) return
        viewModelScope.launch {
            val text = backupFileStore.read(uri).getOrElse {
                transientState.value =
                    transientState.value.copy(message = SettingsMessage.SubscriptionsReadFailed)
                return@launch
            }
            transientState.value = when (val decoded = OpmlCodec.decode(text)) {
                is OpmlDecodeResult.Decoded -> transientState.value.copy(
                    pendingRestore = PendingRestore(
                        json = BackupCodec.encode(decoded.asBackupFile(clock.millis())),
                        showCount = decoded.feeds.size,
                        skipped = decoded.skipped,
                    ),
                )

                OpmlDecodeResult.NoFeeds ->
                    transientState.value.copy(message = SettingsMessage.SubscriptionsEmpty)

                OpmlDecodeResult.NotOpml ->
                    transientState.value.copy(message = SettingsMessage.SubscriptionsNotRecognised)
            }
        }
    }

    /** Starts the import the user has confirmed. */
    fun confirmRestore() {
        val pending = transientState.value.pendingRestore ?: return
        transientState.value = transientState.value.copy(pendingRestore = null)
        libraryRestorer.start(pending.json)
    }

    /**
     * Records that the finished import's result has been read, so it is not announced again.
     *
     * Written to storage rather than kept here: this view model dies with the settings screen, and
     * the run it describes outlives both.
     */
    fun acknowledgeRestoreResult() {
        val finished = uiState.value.backup.restore as? RestoreRun.Finished ?: return
        viewModelScope.launch { backupRepository.acknowledgeRestore(finished.id) }
    }

    /** Drops a picked document the user decided against. */
    fun cancelRestore() {
        transientState.value = transientState.value.copy(pendingRestore = null)
    }

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    /**
     * The stored preferences, carried as one value.
     *
     * A named type rather than a tuple, so the combine above reads as the settings it carries
     * rather than as `first`, `second` and `third`.
     *
     * @property playback the playback preferences.
     * @property downloads the download rules.
     * @property appearance how the app draws itself.
     * @property speedOverrides the shows that play at a rate of their own. Not a stored preference
     *   in the same sense as the other three — it is a join of two of them — but it arrives from
     *   the same combine and would otherwise need a fifth arm on the one below it.
     */
    private data class StoredPreferences(
        val playback: PlaybackSettings,
        val downloads: DownloadSettings,
        val appearance: AppearanceSettings,
        val speedOverrides: List<ShowSpeedOverride>,
    )

    /**
     * State that belongs to the screen rather than to storage.
     *
     * @property downloadedBytes the cache's own size once read; null until then, so the screen can
     *   fall back to summing the episode rows instead of flashing a zero.
     */
    private data class TransientState(
        val isRemovingDownloads: Boolean = false,
        val downloadedBytes: Long? = null,
        val isExporting: Boolean = false,
        val pendingRestore: PendingRestore? = null,
        val message: SettingsMessage? = null,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Names the exported document after the day it was written.
         *
         * ISO order so a folder of exports sorts chronologically, and no separators beyond the
         * hyphen so that every document provider accepts it verbatim.
         */
        private val FILE_NAME_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Prefix of the suggested file name. */
        private const val FILE_NAME_PREFIX = "megapodcastplayer-subscriptions-"

        /** Extension of the suggested file name. */
        private const val FILE_NAME_SUFFIX = ".opml"

        /** What the exported document calls itself, which is what an importing app shows. */
        private const val OPML_DOCUMENT_TITLE = "MegaPodcastPlayer subscriptions"
    }
}
