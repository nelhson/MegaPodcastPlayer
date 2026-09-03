package md.borisveriga.megapodcastplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * State rendered by the settings screen.
 *
 * @property playback the user's playback preferences.
 * @property downloads the user's download rules.
 * @property downloadedEpisodeCount how many episodes are available offline.
 * @property downloadedBytes how much storage those downloads occupy.
 * @property isRemovingDownloads true while "remove all downloads" is in flight, so the row can be
 *   disabled rather than let a second tap race the first.
 * @property message a one-off outcome for the snackbar.
 */
data class SettingsUiState(
    val playback: PlaybackSettings = PlaybackSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val downloadedEpisodeCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val isRemovingDownloads: Boolean = false,
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
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientState())

    val uiState: StateFlow<SettingsUiState> = combine(
        playbackRepository.observePlaybackSettings(),
        downloadRepository.observeDownloadSettings(),
        downloadRepository.observeDownloadedEpisodes(),
        transientState,
    ) { playback, downloads, downloaded, transient ->
        SettingsUiState(
            playback = playback,
            downloads = downloads,
            downloadedEpisodeCount = downloaded.size,
            // Summed from the rows rather than read from the cache so the figure updates with the
            // list it sits next to; the exact on-disk total is refreshed by refreshStorageUsage().
            downloadedBytes = transient.downloadedBytes
                ?: downloaded.sumOf { it.downloadedBytes },
            isRemovingDownloads = transient.isRemovingDownloads,
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

    /** Clears the current message once its snackbar has been shown. */
    fun onMessageShown() {
        transientState.value = transientState.value.copy(message = null)
    }

    /**
     * State that belongs to the screen rather than to storage.
     *
     * @property downloadedBytes the cache's own size once read; null until then, so the screen can
     *   fall back to summing the episode rows instead of flashing a zero.
     */
    private data class TransientState(
        val isRemovingDownloads: Boolean = false,
        val downloadedBytes: Long? = null,
        val message: SettingsMessage? = null,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
