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
import md.borisveriga.megapodcastplayer.core.data.backup.BackupFileStore
import md.borisveriga.megapodcastplayer.core.data.backup.LibraryRestorer
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.UiPreferencesRepository
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice
import md.borisveriga.megapodcastplayer.core.model.backup.BackupCodec
import md.borisveriga.megapodcastplayer.core.model.backup.BackupDecodeResult

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
 * @property backup everything the backup section renders.
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
    val message: SettingsMessage? = null,
) {
    /** True when there is anything on disk to free. */
    val hasDownloads: Boolean get() = downloadedEpisodeCount > 0
}

/** A one-off outcome to show the user. */
sealed interface SettingsMessage {

    /**
     * Every download was removed.
     *
     * @property freedBytes how much storage came back, so the confirmation is worth reading.
     */
    data class DownloadsRemoved(val freedBytes: Long) : SettingsMessage

    /** The library was written to the document the user chose. */
    data object BackupExported : SettingsMessage

    /** The document could not be written — a revoked grant, or a provider that went away. */
    data object BackupExportFailed : SettingsMessage

    /** The document could not be read back. */
    data object BackupReadFailed : SettingsMessage

    /** The picked file is not one of ours: the wrong document, or a truncated one. */
    data object BackupNotRecognised : SettingsMessage

    /**
     * The picked file was written by a build that knows fields this one does not.
     *
     * Refused rather than partially restored: silently recreating three quarters of a library is
     * worse than declining to try.
     */
    data object BackupTooNew : SettingsMessage
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
 * @property backupRepository reads the library into a document and puts one back.
 * @property backupFileStore reads and writes the document the user picked.
 * @property libraryRestorer runs a restore somewhere that outlives this screen.
 * @property uiPreferences the appearance choices; the same repository the library's layout and
 *   order live in, for the same reason — none of it changes what the app does.
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
    private val clock: Clock,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    /**
     * The backup section's own state, combined before the rest.
     *
     * Folded into one flow rather than added as two more arms of the main [combine]: the section is
     * a self-contained concern, and the alternative is a five-argument lambda whose parameters have
     * to be read positionally.
     */
    private val backupState: kotlinx.coroutines.flow.Flow<BackupUiState> = combine(
        backupRepository.observeLastBackupAt(),
        libraryRestorer.observe(),
        transientState,
    ) { lastBackupAt, restore, transient ->
        BackupUiState(
            lastBackupAtMs = lastBackupAt,
            isExporting = transient.isExporting,
            pendingRestore = transient.pendingRestore,
            restore = restore,
        )
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

    /** Sets how many downloaded episodes to keep per show. */
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
     * The file name to suggest when the picker asks where to put the export.
     *
     * @return a name carrying today's date, so a folder of backups sorts chronologically.
     */
    fun suggestedBackupFileName(): String =
        FILE_NAME_PREFIX +
            FILE_NAME_DATE.format(clock.instant().atZone(ZoneId.systemDefault())) +
            FILE_NAME_SUFFIX

    /**
     * Writes the library to the document the user created.
     *
     * @param uri the document the picker returned.
     */
    fun exportTo(uri: Uri) {
        if (transientState.value.isExporting) return
        transientState.value = transientState.value.copy(isExporting = true)
        viewModelScope.launch {
            val file = backupRepository.export()
            val written = backupFileStore.write(uri, BackupCodec.encode(file))
            if (written.isSuccess) {
                backupRepository.recordExported(file.exportedAtMs)
            }
            transientState.value = transientState.value.copy(
                isExporting = false,
                message = if (written.isSuccess) {
                    SettingsMessage.BackupExported
                } else {
                    SettingsMessage.BackupExportFailed
                },
            )
        }
    }

    /**
     * Reads and validates a document the user picked, then asks them to confirm.
     *
     * Validation happens here rather than in the worker so that a foreign or truncated file is
     * refused while the user is still in the flow that produced it, and so that nothing is enqueued
     * that is going to fail anyway.
     *
     * @param uri the document the picker returned.
     */
    fun prepareRestore(uri: Uri) {
        if (transientState.value.pendingRestore != null) return
        viewModelScope.launch {
            val text = backupFileStore.read(uri).getOrElse {
                transientState.value =
                    transientState.value.copy(message = SettingsMessage.BackupReadFailed)
                return@launch
            }
            transientState.value = when (val decoded = BackupCodec.decode(text)) {
                is BackupDecodeResult.Decoded -> transientState.value.copy(
                    pendingRestore = PendingRestore(
                        json = text,
                        showCount = decoded.file.podcasts.size,
                    ),
                )

                is BackupDecodeResult.TooNew ->
                    transientState.value.copy(message = SettingsMessage.BackupTooNew)

                is BackupDecodeResult.Malformed ->
                    transientState.value.copy(message = SettingsMessage.BackupNotRecognised)
            }
        }
    }

    /**
     * Starts the restore the user has confirmed.
     *
     * @param reDownload whether to re-queue the downloads the backup records. Off unless asked for:
     *   the audio is not in the file, so this is a fresh download of everything.
     */
    fun confirmRestore(reDownload: Boolean) {
        val pending = transientState.value.pendingRestore ?: return
        transientState.value = transientState.value.copy(pendingRestore = null)
        libraryRestorer.start(pending.json, reDownload)
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
     * A named type rather than a `Triple`, so the combine above reads as three settings rather than
     * as `first`, `second` and `third`.
     *
     * @property playback the playback preferences.
     * @property downloads the download rules.
     * @property appearance how the app draws itself.
     */
    private data class StoredPreferences(
        val playback: PlaybackSettings,
        val downloads: DownloadSettings,
        val appearance: AppearanceSettings,
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
         * ISO order so a folder of backups sorts chronologically, and no separators beyond the
         * hyphen so that every document provider accepts it verbatim.
         */
        private val FILE_NAME_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** Prefix of the suggested file name. */
        private const val FILE_NAME_PREFIX = "megapodcastplayer-backup-"

        /** Suffix of the suggested file name. */
        private const val FILE_NAME_SUFFIX = ".json"
    }
}
