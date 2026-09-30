package md.borisveriga.megapodcastplayer.feature.player.video

import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

// One message per failure kind, fixed, so each groups into one report; see CrashReporter.
private const val NON_FATAL_QUALITIES = "Video qualities lookup failed"

/**
 * State rendered by the video screen.
 *
 * @property playback what the player is doing right now, picture included.
 * @property settings the user's speed and skip preferences; the skip glyphs draw the intervals.
 * @property preferredQuality the rendition the user last chose, which is what a video opens at.
 * @property qualities the renditions the loaded video comes in, lowest first; null while the
 *   extractor is still being asked, empty when the video has no picture at all.
 * @property qualitiesFailed true when the extractor could not be asked; the picker says so.
 * @property refused true when the service would not show the picture — the episode has none, or the
 *   service was unreachable — until the screen has said so; cleared via
 *   [VideoViewModel.onRefusalShown].
 */
data class VideoUiState(
    val playback: PlaybackState = PlaybackState(),
    val settings: PlaybackSettings = PlaybackSettings(),
    val preferredQuality: VideoQuality = VideoQuality.DEFAULT,
    val qualities: List<VideoQuality>? = null,
    val qualitiesFailed: Boolean = false,
    val refused: Boolean = false,
) {

    /** Whether the loaded episode has a picture at all; false is the screen's cue to leave. */
    val canWatch: Boolean get() = playback.canWatch

    /**
     * The rendition the quality button names: the one asked for on screen, else the one that
     * would be. Asked for rather than measured, because it is the user's choice being named.
     */
    val qualityShown: VideoQuality get() = playback.videoQuality ?: preferredQuality
}

/**
 * Drives the video screen: the one place the picture of a YouTube episode is shown.
 *
 * Holds no playback state of its own. The player is the [PlaybackConnection]'s, and the screen is a
 * surface attached to it plus two commands — show the picture, stop showing it — that bracket the
 * screen's time in the foreground. Everything else here is the ordinary transport, delegated the
 * way the player sheet's view model delegates it.
 *
 * @property connection the handle on the playback service.
 * @property playbackRepository the skip intervals and the remembered rendition.
 * @property qualitySource asks the extractor which renditions a video comes in.
 * @property crashReporter where a failed lookup goes; the picker shows a sentence, the report
 *   keeps the cause.
 * @property applicationScope where leaving runs. The screen's own scope dies with the screen, and
 *   handing back to audio is the one thing that must outlive it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VideoViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val qualitySource: VideoQualitySource,
    private val crashReporter: CrashReporter,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val refusedState = MutableStateFlow(false)

    /**
     * Whether the screen is up, between [enter] and [exit].
     *
     * Read by the collector in `init` that follows the episode: the player moving on to another
     * YouTube episode should keep the picture going only while there is a screen to show it on.
     */
    private var watching = false

    /**
     * The renditions of whichever video is loaded, re-asked as the video changes.
     *
     * `distinctUntilChanged` before the `flatMapLatest`, or every position tick would ask again.
     * The resolver caches, so a lookup right after the player started the same video is free; a
     * failure is reported and shown, not thrown — the screen still has a picture to show.
     */
    private val qualities: Flow<Qualities> = connection.playbackState
        .map { it.youTubeVideoId }
        .distinctUntilChanged()
        .flatMapLatest { videoId ->
            flow {
                emit(Qualities())
                if (videoId == null) return@flow
                suspendRunCatching { qualitySource.qualitiesOf(videoId) }
                    .onSuccess { available -> emit(Qualities(available = available)) }
                    .onFailure { failure ->
                        crashReporter.recordNonFatal(NON_FATAL_QUALITIES, failure)
                        emit(Qualities(failed = true))
                    }
            }
        }

    val uiState: StateFlow<VideoUiState> = combine(
        connection.playbackState,
        playbackRepository.observePlaybackSettings(),
        playbackRepository.observeVideoQuality(),
        qualities,
        refusedState,
    ) { playback, settings, preferred, qualities, refused ->
        VideoUiState(
            playback = playback,
            settings = settings,
            preferredQuality = preferred,
            qualities = qualities.available,
            qualitiesFailed = qualities.failed,
            refused = refused,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = VideoUiState(),
    )

    init {
        // Follow the episode. When the queue moves on while the screen is up — the episode ended,
        // or the user pressed next — the next one arrives as sound, because that is how every
        // episode is stored; if it has a picture, show it too. The first value is the episode the
        // screen opened on, which `enter` already handles.
        viewModelScope.launch {
            connection.playbackState
                .map { it.youTubeVideoId }
                .distinctUntilChanged()
                .drop(1)
                .collect { videoId -> if (watching && videoId != null) showPicture() }
        }
    }

    /** Shows the picture of the episode playing, at the remembered rendition. */
    fun enter() {
        watching = true
        viewModelScope.launch { showPicture() }
    }

    /**
     * Goes back to sound only. Playback carries on; only the picture stops.
     *
     * On the application scope rather than this view model's: the screen calls this on its way out,
     * and a scope that is being torn down would cancel the very command that hands back to audio.
     */
    fun exit() {
        watching = false
        applicationScope.launch { connection.exitVideo() }
    }

    /**
     * Switches to another rendition and remembers it.
     *
     * The swap keeps the position, so this is a re-buffer and not a restart.
     *
     * @param quality the rendition chosen.
     */
    fun setQuality(quality: VideoQuality) {
        viewModelScope.launch {
            playbackRepository.setVideoQuality(quality)
            if (!connection.enterVideo(quality)) refusedState.value = true
        }
    }

    /**
     * Gives the player the screen's surface to draw on.
     *
     * @param view the surface, freshly created by the screen.
     */
    fun attachSurface(view: SurfaceView) {
        viewModelScope.launch { connection.attachVideoSurface(view) }
    }

    /**
     * Takes the surface back before the screen destroys it.
     *
     * On the application scope for the reason [exit] is: this runs as the screen is disposed.
     *
     * @param view the surface handed over by [attachSurface].
     */
    fun detachSurface(view: SurfaceView) {
        applicationScope.launch { connection.detachVideoSurface(view) }
    }

    /** Clears [VideoUiState.refused] once its snackbar has been shown. */
    fun onRefusalShown() {
        refusedState.value = false
    }

    /** Starts or pauses playback. */
    fun togglePlayPause() {
        viewModelScope.launch { connection.togglePlayPause() }
    }

    /**
     * Seeks within the current episode.
     *
     * @param positionMs the absolute position to seek to.
     */
    fun seekTo(positionMs: Long) {
        viewModelScope.launch { connection.seekTo(positionMs) }
    }

    /** Jumps forward by the user's configured interval. */
    fun skipForward() {
        viewModelScope.launch { connection.skipForward(uiState.value.settings.skipForwardMs) }
    }

    /** Jumps back by the user's configured interval. */
    fun skipBack() {
        viewModelScope.launch { connection.skipBack(uiState.value.settings.skipBackMs) }
    }

    /** Moves to the next queued episode; the screen follows it if it has a picture. */
    fun skipToNext() {
        viewModelScope.launch { connection.skipToNext() }
    }

    /** Restarts the episode, or goes back to the previous one when already near the start. */
    fun skipToPrevious() {
        viewModelScope.launch { connection.skipToPrevious() }
    }

    /**
     * Applies a playback rate without remembering it; what the speed sheet calls mid-drag.
     *
     * @param speed the rate to play at; clamped by the player.
     */
    fun previewSpeed(speed: Float) {
        viewModelScope.launch { connection.setSpeed(speed) }
    }

    /**
     * Applies a playback rate and remembers it.
     *
     * @param speed the rate to play at; clamped on the way to storage.
     */
    fun setSpeed(speed: Float) {
        viewModelScope.launch {
            playbackRepository.setSpeed(speed)
            connection.setSpeed(speed)
        }
    }

    /** Asks the service for the picture at the remembered rendition, noting a refusal. */
    private suspend fun showPicture() {
        val quality = playbackRepository.observeVideoQuality().first()
        if (!connection.enterVideo(quality)) refusedState.value = true
    }

    /**
     * What is known about the loaded video's renditions.
     *
     * @property available the renditions, or null while still being asked.
     * @property failed true when asking failed.
     */
    private data class Qualities(
        val available: List<VideoQuality>? = null,
        val failed: Boolean = false,
    )

    private companion object {
        /** Keeps the state alive across a rotation or a fold, like the player sheet's. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
