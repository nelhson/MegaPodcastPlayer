package md.borisveriga.megapodcastplayer.feature.player.video

import android.view.SurfaceView
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
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
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadFolderRepository
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.NetworkStatus
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.media.VideoOutput
import md.borisveriga.megapodcastplayer.core.media.VideoQualitySource
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
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
 * @property refused true while the picture that was asked for is not being shown: the service
 *   would not show it, it could not be fetched, or it failed and the episode fell back to sound.
 *   A standing fact rather than a message, so the screen can say it where the picture would be
 *   for as long as it is true; asking again clears it.
 * @property videoDownload the loaded episode's downloaded video, or null when it has none.
 * @property downloadMessage what a download request just did, until the screen has said so;
 *   cleared via [VideoViewModel.onDownloadMessageShown].
 * @property downloadFolders the user's download folders, for the download sheet's "Save to" picker.
 */
data class VideoUiState(
    val playback: PlaybackState = PlaybackState(),
    val settings: PlaybackSettings = PlaybackSettings(),
    val preferredQuality: VideoQuality = VideoQuality.DEFAULT,
    val qualities: List<VideoQuality>? = null,
    val qualitiesFailed: Boolean = false,
    val refused: Boolean = false,
    val videoDownload: VideoDownload? = null,
    val downloadMessage: VideoDownloadMessage? = null,
    val downloadFolders: DownloadFolders = DownloadFolders.NONE,
) {

    /** Whether the loaded episode has a picture at all; false is the screen's cue to leave. */
    val canWatch: Boolean get() = playback.canWatch

    /**
     * The rendition the quality button names: the one asked for on screen, else the one that
     * would be. Asked for rather than measured, because it is the user's choice being named.
     */
    val qualityShown: VideoQuality get() = playback.videoQuality ?: preferredQuality
}

/** What asking to download or delete the loaded episode's video did, for a snackbar to say. */
sealed interface VideoDownloadMessage {

    /**
     * The video was queued for download.
     *
     * @property quality the rendition asked for.
     * @property waitingForWifi whether it waits for an unmetered network, which the message then
     *   says, as the audio's does on the show page.
     */
    data class Queued(val quality: VideoQuality, val waitingForWifi: Boolean = false) : VideoDownloadMessage

    /** The downloaded video was deleted; the audio stays. */
    data object Deleted : VideoDownloadMessage

    /** A video download still under way was called off; there was no file yet to delete. */
    data object Cancelled : VideoDownloadMessage

    /** The episode is no longer stored, so there was nothing to download. */
    data object Failed : VideoDownloadMessage
}

/**
 * Drives the picture of a YouTube episode, in the two places it is shown: the video screen, and the
 * collapsed bar the screen is put away behind.
 *
 * Holds no playback state of its own. The player is the [PlaybackConnection]'s, and the picture is
 * a surface attached to it plus two commands — show the picture, stop showing it — that bracket the
 * time the player spends in video with the app in front. Everything else here is the ordinary
 * transport, delegated the way the player sheet's view model delegates it.
 *
 * Both halves are kept as *what is wanted now* rather than as calls passed on one by one. The
 * player has one output and one flavour, and the screen and the bar come and go in an order
 * nobody controls — a rotation builds the new pair before it has finished taking the old one down.
 * So the views register and leave, the asks come and go, and one collector apiece sends the player
 * whatever the latest answer is.
 *
 * One instance serves both places, held by the shell that holds both. The bracket is the shell's
 * to keep for that reason: minimising the screen moves the picture to the bar rather than ending
 * it, and two holders each bracketing their own time on screen would hand back to sound in the gap
 * between them.
 *
 * @property connection the handle on the playback service.
 * @property playbackRepository the skip intervals and the remembered rendition.
 * @property qualitySource asks the extractor which renditions a video comes in.
 * @property downloadRepository keeps a video on the device, and says which one is there.
 * @property networkStatus says whether a picture that is not on the device could be fetched.
 * @property crashReporter where a failed lookup goes; the picker shows a sentence, the report
 *   keeps the cause.
 * @property applicationScope where the asks for the picture are sent from. The screen's own scope
 *   dies with the screen, and handing back to audio is the one thing that must outlive it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VideoViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val qualitySource: VideoQualitySource,
    private val downloadRepository: DownloadRepository,
    private val folderRepository: DownloadFolderRepository,
    private val networkStatus: NetworkStatus,
    private val crashReporter: CrashReporter,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val refusedState = MutableStateFlow(false)

    private val downloadMessageState = MutableStateFlow<VideoDownloadMessage?>(null)

    /**
     * Whether the picture is wanted, between [enter] and [exit].
     *
     * Read by the collector in `init` that follows the episode: the player moving on to another
     * YouTube episode should keep the picture going only while there is somewhere to show it. Also
     * read where the asks are sent, on another thread, hence volatile.
     */
    @Volatile
    private var watching = false

    /**
     * The latest thing asked of the picture, waiting to be sent.
     *
     * One slot, read by one sender. [enter] reads the remembered rendition from disk before it
     * asks, and used to do so on a different scope from [exit]: leaving within those milliseconds
     * could let the exit arrive first and the enter after it, leaving a picture streaming with no
     * screen. With a single sender the commands reach the service in the order they were asked,
     * and an ask that a newer one has replaced is abandoned rather than sent late.
     */
    private val pictureAsks = Channel<PictureAsk>(Channel.CONFLATED)

    /**
     * The views that could show the picture right now, oldest first. Main thread only.
     *
     * Usually one. Two for the length of a hand-over — minimising, reopening, the first frame
     * after a rotation — and the newest is the one drawn on, which is the one on its way in.
     */
    private val outputs = ArrayList<VideoOutput>()

    /** The view the picture belongs on: the newest of [outputs], or null when there is none. */
    private val wantedOutput = MutableStateFlow<VideoOutput?>(null)

    /** The output the player was last given, so it can be let go when this view model is. */
    private var givenOutput: VideoOutput? = null

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

    /** The loaded episode's downloaded video, followed as the episode and the download change. */
    private val videoDownload: Flow<VideoDownload?> = combine(
        connection.playbackState.map { it.episodeId }.distinctUntilChanged(),
        downloadRepository.observeVideoDownloads(),
    ) { episodeId, downloads -> episodeId?.let(downloads::get) }
        .distinctUntilChanged()

    /** What the screen shows about renditions and downloads, gathered to keep [uiState] readable. */
    private val extras: Flow<Extras> = combine(
        qualities,
        refusedState,
        videoDownload,
        downloadMessageState,
        folderRepository.observeFolders(),
    ) { qualities, refused, download, message, folders ->
        Extras(qualities.offeringDownload(download), refused, download, message, folders)
    }

    /**
     * Everything the video screen renders, kept while the screen is subscribed.
     *
     * Forgotten once it has stopped being kept. This view model outlives a visit to the screen, and
     * the screen reads the state before the first fresh value arrives; a value left over from the
     * last visit — an episode with nothing to show, say, which is what sent that visit away — would
     * send this one away too. So an unwatched state goes back to the empty one, which the screen
     * reads as "not connected yet" and waits on.
     */
    val uiState: StateFlow<VideoUiState> = combine(
        connection.playbackState,
        playbackRepository.observePlaybackSettings(),
        playbackRepository.observeVideoQuality(),
        extras,
    ) { playback, settings, preferred, extras ->
        VideoUiState(
            playback = playback,
            settings = settings,
            preferredQuality = preferred,
            qualities = extras.qualities.available,
            qualitiesFailed = extras.qualities.failed,
            refused = extras.refused,
            videoDownload = extras.download,
            downloadMessage = extras.message,
            downloadFolders = extras.folders,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = STOP_TIMEOUT_MS,
            replayExpirationMillis = 0L,
        ),
        initialValue = VideoUiState(),
    )

    init {
        // Send the asks, one at a time and newest only. On the application scope: the last of them
        // is the hand back to sound as the activity stops, which can be the activity finishing.
        applicationScope.launch {
            pictureAsks.consumeAsFlow().collectLatest { ask ->
                when (ask) {
                    is PictureAsk.Show -> showPicture(ask.quality)
                    PictureAsk.Hide -> connection.exitVideo()
                }
            }
        }
        // Keep the player drawing on whichever view is wanted. Media3 holds one output and does
        // not fall back to the one before it, so when the newest view leaves, the survivor has to
        // be given the picture again — which is the whole of what a rotation used to break.
        viewModelScope.launch {
            wantedOutput.collect { output ->
                // Nothing given and nothing wanted: the state this view model starts in.
                if (output == null && givenOutput == null) return@collect
                if (connection.showVideoOn(output)) givenOutput = output
            }
        }
        // Follow the episode. When the queue moves on while the picture is wanted — the episode
        // ended, or the user pressed next — the next one arrives as sound, because that is how
        // every episode is stored; if it has a picture, show it too. The first value is whatever
        // was loaded when this view model was made, which is `enter`'s to handle if it is wanted.
        viewModelScope.launch {
            connection.playbackState
                .map { it.youTubeVideoId }
                .distinctUntilChanged()
                .drop(1)
                .collect { videoId ->
                    if (watching && videoId != null) pictureAsks.trySend(PictureAsk.Show())
                }
        }
        // Notice the service handing a failed picture back to sound. The same video dropping from
        // picture to sound while the screen is up is only ever that: leaving clears `watching`
        // first, and a change of rendition stays in picture.
        viewModelScope.launch {
            var before: Flavour? = null
            connection.playbackState
                .map { Flavour(videoId = it.youTubeVideoId, isVideo = it.isVideo) }
                .distinctUntilChanged()
                .collect { now ->
                    if (watching && before.fellBackTo(now)) refusedState.value = true
                    before = now
                }
        }
    }

    /**
     * Shows the picture of the episode playing, at the remembered rendition.
     *
     * Safe to call again while the picture is already wanted, and called so: by the shell when the
     * player is put in video, and by the video screen each time it starts. The service answers a
     * repeat by doing nothing, so the second ask costs a round trip and no re-buffer — and it is
     * what retries a picture that was refused the first time, which is why a standing refusal is
     * dropped here rather than left over an ask that has been superseded.
     */
    fun enter() {
        watching = true
        refusedState.value = false
        pictureAsks.trySend(PictureAsk.Show())
    }

    /**
     * Goes back to sound only. Playback carries on; only the picture stops.
     *
     * Replaces an [enter] that has not been sent yet, and follows one that has; see [pictureAsks].
     */
    fun exit() {
        watching = false
        pictureAsks.trySend(PictureAsk.Hide)
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
            pictureAsks.trySend(PictureAsk.Show(quality))
        }
    }

    /**
     * Offers the screen's surface to draw on. The newest view offered is the one drawn on.
     *
     * @param view the surface, freshly created by the screen.
     */
    fun attachSurface(view: SurfaceView) = offer(VideoOutput.Screen(view))

    /**
     * Takes the surface back before the screen destroys it; the bar's texture, if it is still
     * there, is given the picture again.
     *
     * @param view the surface handed over by [attachSurface].
     */
    fun detachSurface(view: SurfaceView) = withdraw(VideoOutput.Screen(view))

    /**
     * Offers the collapsed bar's texture to draw on. The newest view offered is the one drawn on.
     *
     * @param view the texture, freshly created by the bar.
     */
    fun attachTexture(view: TextureView) = offer(VideoOutput.Bar(view))

    /**
     * Takes the texture back before the bar destroys it; the screen's surface, if it is still
     * there, is given the picture again.
     *
     * @param view the texture handed over by [attachTexture].
     */
    fun detachTexture(view: TextureView) = withdraw(VideoOutput.Bar(view))

    /** Clears the player's error once the video screen has said it. */
    fun onErrorShown() {
        connection.clearError()
    }

    /**
     * Lets go of the picture's output and stops taking asks.
     *
     * The views have normally all left by now. One that has not is released on the application
     * scope, and only if the player is still drawing on it: by the time this runs another activity
     * may have given the player a view of its own.
     */
    override fun onCleared() {
        pictureAsks.close()
        val output = givenOutput ?: return
        applicationScope.launch { connection.releaseVideoOutput(output) }
    }

    /**
     * Downloads the loaded episode's video at [quality], with its audio if that is not already
     * on the device; a video kept at another quality is replaced.
     *
     * Leaves what is playing alone. The picture on screen keeps streaming at its own quality, and
     * the download is what the next visit to this screen plays from.
     *
     * @param quality the rendition to keep.
     * @param destination the folder to file the episode under, from the sheet's picker; by default
     *   the one it is in, else the default folder.
     */
    fun downloadVideo(quality: VideoQuality, destination: DownloadDestination = DownloadDestination.Unspecified) {
        val episodeId = uiState.value.playback.episodeId ?: return
        viewModelScope.launch {
            val requested = downloadRepository.downloadVideo(episodeId, quality, destination)
            downloadMessageState.value = if (requested) {
                VideoDownloadMessage.Queued(
                    quality = quality,
                    waitingForWifi = downloadRepository.observeDownloadSettings().first().unmeteredOnly,
                )
            } else {
                VideoDownloadMessage.Failed
            }
        }
    }

    /**
     * Deletes the loaded episode's downloaded video, or calls off one still under way, and keeps
     * its downloaded audio either way.
     */
    fun deleteVideoDownload() {
        val state = uiState.value
        val episodeId = state.playback.episodeId ?: return
        val finished = state.videoDownload?.isComplete == true
        viewModelScope.launch {
            downloadRepository.removeVideoDownload(episodeId)
            downloadMessageState.value = if (finished) {
                VideoDownloadMessage.Deleted
            } else {
                VideoDownloadMessage.Cancelled
            }
        }
    }

    /** Clears [VideoUiState.downloadMessage] once its snackbar has been shown. */
    fun onDownloadMessageShown() {
        downloadMessageState.value = null
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

    /**
     * Adds [output] to the views the picture could be drawn on, as the newest.
     *
     * @param output a view that has just been created.
     */
    private fun offer(output: VideoOutput) {
        outputs.remove(output)
        outputs.add(output)
        wantedOutput.value = outputs.lastOrNull()
    }

    /**
     * Removes [output]; the picture goes to the newest view left, or nowhere.
     *
     * @param output a view that is about to be destroyed.
     */
    private fun withdraw(output: VideoOutput) {
        outputs.remove(output)
        wantedOutput.value = outputs.lastOrNull()
    }

    /**
     * Asks the service for the picture, noting whether it was refused.
     *
     * At [chosen] when the user has just picked a rendition; otherwise at the downloaded rendition
     * when the episode has a finished video download, else at the remembered one. A download is
     * filed under its rendition, so asking for any other would stream a picture that is already on
     * the device — or, offline, fail to show it at all.
     *
     * Not asked at all for a picture that is neither on the device nor fetchable. The video flavour
     * is one merged source, so asking takes the sound down until the player gives up on the picture
     * and falls back — and this is asked on its own every time the app comes forward in video, not
     * only when the user says "watch". Offline with the audio downloaded, that would be an
     * interruption per visit to the app. It is said as a refusal instead, which the video screen
     * words if it is up and the bar lets pass.
     *
     * The episode is named in the ask. It is read here, a disk read before the command goes, and
     * the queue can move on in between; the service then leaves the newcomer alone, and the
     * collector that follows the episode asks again for it.
     *
     * Nor before the service has said what it holds. The video screen can be restored with the
     * activity, ahead of the queue the service is still putting back, and asking then is asking an
     * empty player: the answer is a refusal, said in the frame of an episode that is about to
     * arrive and show its picture.
     *
     * @param chosen the rendition the user just picked, or null to work it out.
     */
    private suspend fun showPicture(chosen: VideoQuality? = null) {
        val episodeId = connection.playbackState.first { !it.isRestoring }.episodeId
        val downloaded = episodeId
            ?.let { downloadRepository.observeVideoDownloads().first()[it] }
            ?.takeIf { it.isComplete }
            ?.quality
        val quality = chosen ?: downloaded ?: playbackRepository.observeVideoQuality().first()
        // The screen may have gone while the rendition was being read. A rendition picked by hand
        // is shown regardless: the picker is only reachable from the picture.
        if (chosen == null && !watching) return
        if (quality != downloaded && !networkStatus.isOnline()) {
            refusedState.value = true
            return
        }
        refusedState.value = !connection.enterVideo(quality, episodeId)
    }

    /** What can be asked of the picture; see [pictureAsks]. */
    private sealed interface PictureAsk {

        /**
         * Show the picture.
         *
         * @property quality the rendition the user just picked, or null for the usual one.
         */
        data class Show(val quality: VideoQuality? = null) : PictureAsk

        /** Go back to sound only. */
        data object Hide : PictureAsk
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
    ) {

        /**
         * These renditions, with a finished download standing in when the extractor could not be
         * asked.
         *
         * Offline the lookup fails, but a downloaded video still plays, and a picker that says it
         * knows of no quality while the picture is showing at one would contradict the screen.
         *
         * @param download the loaded episode's downloaded video, if any.
         */
        fun offeringDownload(download: VideoDownload?): Qualities =
            if (failed && download?.isComplete == true) {
                Qualities(available = listOf(download.quality))
            } else {
                this
            }
    }

    /**
     * The parts of [VideoUiState] beyond the player and the settings.
     *
     * @property qualities what is known of the renditions.
     * @property refused whether a refusal waits to be shown.
     * @property download the loaded episode's downloaded video, if any.
     * @property message a download message waiting to be shown.
     * @property folders the user's download folders.
     */
    private data class Extras(
        val qualities: Qualities,
        val refused: Boolean,
        val download: VideoDownload?,
        val message: VideoDownloadMessage?,
        val folders: DownloadFolders,
    )

    /**
     * Which video is loaded and whether it is showing its picture.
     *
     * @property videoId the loaded YouTube video, or null for a feed episode or nothing.
     * @property isVideo true while it plays as sound and picture.
     */
    private data class Flavour(val videoId: String?, val isVideo: Boolean)

    /**
     * Whether going from this to [now] is the same video dropping from picture to sound.
     *
     * @param now the flavour just observed.
     * @return true for a fall back to sound; false for a first value, another video, or anything else.
     */
    private fun Flavour?.fellBackTo(now: Flavour): Boolean =
        this != null && isVideo && !now.isVideo && videoId != null && videoId == now.videoId

    private companion object {
        /** Keeps the state alive across a rotation or a fold, like the player sheet's. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
