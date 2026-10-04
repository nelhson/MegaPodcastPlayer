package md.borisveriga.megapodcastplayer.core.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

/**
 * The app's handle on [PlaybackService].
 *
 * Wraps a Media3 [MediaController] so that callers see a [StateFlow] of [PlaybackState] and plain
 * suspend commands, rather than a connection future, a listener interface and a main-thread rule.
 *
 * All player access is funnelled onto the main thread, which is what Media3 requires; callers may
 * invoke every method here from any dispatcher.
 *
 * @property context application context, used to bind to the service.
 * @property scope application-wide scope; the state flow outlives any one screen so that switching
 *   between the mini player and the full player does not reconnect the controller.
 * @property crashReporter where a command that was dropped without anyone being told is recorded.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val crashReporter: CrashReporter,
) {

    /** The system's audio service, which is what [setDeviceVolume] sets the media volume through. */
    private val audioManager: AudioManager by lazy {
        context.getSystemService(AudioManager::class.java)
    }

    /** Whether a refused device volume has been reported yet; see [setDeviceVolume]. */
    private val volumeRefusalReported = AtomicBoolean(false)

    /** Guards lazy creation of [controller] against two screens connecting at once. */
    private val connectionLock = Mutex()

    private var controller: MediaController? = null

    /** Set when a command fails, so the UI can explain why nothing happened. */
    private val commandErrors = MutableStateFlow<String?>(null)

    /**
     * Whether the player has drawn a frame of the current picture on the current output.
     *
     * Kept here rather than read off the controller, which has no such property: Media3 only
     * *announces* a first frame. Set by that announcement and cleared by whatever makes the next
     * frame a new first one — another item, another output. Touched on the main thread only.
     */
    private var pictureReady = false

    /**
     * The current playback state, re-emitted on every player event and, while playing, every
     * [POSITION_TICK_MS] so the scrubber advances.
     *
     * Sharing is [SharingStarted.WhileSubscribed] with a grace period: rotating the device or
     * navigating from the mini player to the full player must not tear the connection down.
     */
    val playbackState: StateFlow<PlaybackState> = callbackFlow {
        val mediaController = suspendRunCatching { controller() }
            .getOrElse { error ->
                // No service means no playback, but the UI must still render — as idle, not as a
                // crash. A cancelled collector is not a failure and never reaches here.
                send(PlaybackState(isConnected = false, errorMessage = error.message))
                return@callbackFlow
            }

        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A swap between sound and picture is a transition too, which is the point: the
                // frame on the surface belongs to the item that just left.
                pictureReady = false
            }

            override fun onRenderedFirstFrame() {
                pictureReady = true
            }

            override fun onEvents(player: Player, events: Player.Events) {
                trySend(mediaController.snapshot(commandErrors.value, pictureReady))
            }
        }
        mediaController.addListener(listener)
        send(mediaController.snapshot(commandErrors.value, pictureReady))

        val ticker = launch {
            while (isActive) {
                kotlinx.coroutines.delay(POSITION_TICK_MS)
                if (mediaController.isPlaying) {
                    trySend(mediaController.snapshot(commandErrors.value, pictureReady))
                }
            }
        }

        awaitClose {
            ticker.cancel()
            mediaController.removeListener(listener)
        }
    }
        .flowOn(Dispatchers.Main.immediate)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = PlaybackState(),
        )

    /**
     * Plays [episode] now, keeping the rest of the queue.
     *
     * If the episode is already queued the player simply jumps to it, which is what a user tapping
     * an episode they queued earlier expects. Otherwise it is inserted directly after the current
     * one so that "up next" survives the interruption.
     *
     * @param episode the episode to play.
     * @param startPositionMs where to start; defaults to the episode's stored position so that
     *   tapping a half-listened episode resumes it.
     */
    suspend fun playNow(
        episode: PlayableEpisode,
        startPositionMs: Long = episode.episode.positionMs,
    ) = onController { player ->
        val existingIndex = player.indexOfEpisode(episode.episode.id)
        if (existingIndex != null) {
            player.seekTo(existingIndex, startPositionMs)
        } else {
            val item = episode.toMediaItemOrNull() ?: return@onController
            val insertAt = if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
            player.addMediaItem(insertAt, item)
            player.seekTo(insertAt, startPositionMs)
        }
        player.prepare()
        player.play()
    }

    /**
     * Replaces the whole queue and starts playing.
     *
     * Used when putting back a queue the user cleared, and when the user plays a list.
     *
     * @param episodes the new queue, in play order.
     * @param startIndex which entry to start on.
     * @param startPositionMs where in that entry to start.
     * @param playWhenReady false to load the queue without making noise, which is what undoing a
     *   cleared queue does.
     */
    suspend fun setQueue(
        episodes: List<PlayableEpisode>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) = onController { player ->
        // An episode whose audio URL fails the scheme allowlist is dropped rather than played;
        // see [toMediaItemOrNull]. The start index is then re-resolved by episode id so that a
        // dropped entry ahead of it does not shift the user onto the wrong episode.
        val playable = episodes.filter { it.hasPlayableAudio }
        if (playable.isEmpty()) {
            player.clearMediaItems()
            return@onController
        }
        // coerceIn first, so an out-of-range index from stale persisted state still lands on a real
        // episode — the behaviour before the filter existed — and only then is translated by id.
        val startEpisodeId = episodes[startIndex.coerceIn(episodes.indices)].episode.id
        val resolvedIndex = playable.indexOfFirst { it.episode.id == startEpisodeId }
            .takeIf { it >= 0 }
            ?: 0
        player.setMediaItems(
            playable.mapNotNull { it.toMediaItemOrNull() },
            resolvedIndex,
            startPositionMs.coerceAtLeast(0L),
        )
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /**
     * Appends [episode] to the end of the queue without disturbing what is playing.
     *
     * An episode already queued *behind* the one playing is moved to the end instead: nothing lists
     * it there, so leaving it would make this a command that reports success and changes nothing.
     *
     * @return what was done; [QueueAddResult.UNREACHABLE] when the service could not be reached.
     */
    suspend fun addToQueue(episode: PlayableEpisode): QueueAddResult {
        var result = QueueAddResult.UNREACHABLE
        onController { player ->
            result = player.enqueueEpisode(episode.episode.id, episode::toMediaItemOrNull)
        }
        return result
    }

    /**
     * Puts [episode] back at a given index, which is what undoing a removal needs.
     *
     * Distinct from [addToQueue], which appends: an episode taken out of the middle of the queue
     * and then restored to the end has not been restored. The index is clamped rather than
     * rejected, because the queue may legitimately have shrunk since it was recorded — the player
     * finished something — and appending in that case is right.
     *
     * @param episode the episode to insert.
     * @param index where it should land.
     */
    suspend fun insertInQueue(episode: PlayableEpisode, index: Int) = onController { player ->
        if (player.indexOfEpisode(episode.episode.id) != null) return@onController
        val item = episode.toMediaItemOrNull() ?: return@onController
        player.addMediaItem(index.coerceIn(0, player.mediaItemCount), item)
        if (player.mediaItemCount == 1) player.prepare()
    }

    /** Plays [episode] immediately after the current one, ahead of everything else queued. */
    suspend fun playNext(episode: PlayableEpisode) = onController { player ->
        val item = episode.toMediaItemOrNull() ?: return@onController
        player.indexOfEpisode(episode.episode.id)?.let(player::removeMediaItem)
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, item)
        if (player.mediaItemCount == 1) player.prepare()
    }

    /** Removes an episode from the queue; removing the one that is playing skips to the next. */
    suspend fun removeFromQueue(episodeId: String) = onController { player ->
        player.indexOfEpisode(episodeId)?.let(player::removeMediaItem)
    }

    /**
     * Moves a queued episode, which is how a drag-to-reorder is applied.
     *
     * @param fromIndex current index in the queue.
     * @param toIndex target index.
     */
    suspend fun moveInQueue(fromIndex: Int, toIndex: Int) = onController { player ->
        val lastIndex = player.mediaItemCount - 1
        if (fromIndex !in 0..lastIndex || toIndex !in 0..lastIndex) return@onController
        player.moveMediaItem(fromIndex, toIndex)
    }

    /** Starts or pauses playback. */
    suspend fun togglePlayPause() = onController { player ->
        if (player.isPlaying) {
            player.pause()
        } else {
            // A player that reached the end of the queue needs re-preparing before it will play.
            if (player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED
            ) {
                player.prepare()
            }
            player.play()
        }
    }

    /**
     * Starts playback, and leaves a player that is already running alone.
     *
     * [togglePlayPause] is what a play/pause button presses. This is what an outside request to
     * *resume* asks for — the launcher's Resume shortcut — where reading the state and then
     * toggling has a hole in it: playback that starts between the two turns the resume into a
     * pause. Asking the player itself, on the player's own thread, is the only reading that cannot
     * go stale.
     */
    suspend fun play() = onController { player ->
        if (player.isPlaying) return@onController
        // A player that reached the end of the queue needs re-preparing before it will play.
        if (player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            player.prepare()
        }
        player.play()
    }

    /** Pauses playback, if anything is playing. */
    suspend fun pause() = onController(Player::pause)

    /** Seeks to an absolute position within the current episode. */
    suspend fun seekTo(positionMs: Long) = onController { player ->
        player.seekTo(positionMs.coerceIn(0L, player.knownDurationMs() ?: Long.MAX_VALUE))
    }

    /**
     * Jumps forward.
     *
     * @param byMs how far; defaults to [PlaybackSettings.DEFAULT_SKIP_FORWARD_MS]. Callers pass the
     *   user's configured interval.
     */
    suspend fun skipForward(byMs: Long = PlaybackSettings.DEFAULT_SKIP_FORWARD_MS) =
        onController { player ->
            val target = player.currentPosition + byMs
            player.seekTo(target.coerceAtMost(player.knownDurationMs() ?: target))
        }

    /**
     * Jumps back.
     *
     * @param byMs how far; defaults to [PlaybackSettings.DEFAULT_SKIP_BACK_MS].
     */
    suspend fun skipBack(byMs: Long = PlaybackSettings.DEFAULT_SKIP_BACK_MS) =
        onController { player ->
            player.seekTo((player.currentPosition - byMs).coerceAtLeast(0L))
        }

    /** Skips to the next queued episode, if there is one. */
    suspend fun skipToNext() = onController { player ->
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    /**
     * Goes back to the start of the current episode, or to the previous one if already near the
     * start — the behaviour every media transport control has.
     */
    suspend fun skipToPrevious() = onController { player ->
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    /**
     * Sets the playback rate.
     *
     * @param speed the rate; clamped to [PlaybackSettings.SPEED_RANGE] because ExoPlayer throws on a
     *   non-positive value.
     */
    suspend fun setSpeed(speed: Float) = onController { player ->
        player.setPlaybackSpeed(speed.coerceIn(PlaybackSettings.SPEED_RANGE))
    }

    /**
     * Sets the player's own volume, in `0f..1f`.
     *
     * The *player's* volume, not the device's: this scales the audio the app produces and leaves
     * the user's media volume alone. It exists for the sleep timer's fade-out, which is the only
     * thing that should ever move it, and which puts it back to [FULL_VOLUME] afterwards — a player
     * left at zero volume by a crash mid-fade is a player that appears to be broken.
     *
     * @param volume the new volume; clamped, because a value out of range would throw.
     */
    suspend fun setVolume(volume: Float) = onController { player ->
        player.volume = volume.coerceIn(0f, FULL_VOLUME)
    }

    /**
     * Sets the *device's* media volume, which is what the phone's own volume keys move.
     *
     * The other half of the pair [setVolume] is one of, and the one with an audience: this is what
     * the watch's bezel reaches. It is deliberately the system's media volume rather than the
     * player's gain, so that turning the bezel and pressing the phone's volume key are the same
     * act with the same result — and so that the sleep timer's fade, which owns [setVolume]
     * outright and restores it to full afterwards, cannot quietly undo a level the user chose.
     *
     * Does nothing on a device whose volume is fixed, and records that it did nothing; see
     * [applyMediaVolume].
     *
     * @param level the level to set; clamped to the media stream's own range.
     */
    suspend fun setDeviceVolume(level: Int) {
        // Through the system's audio service rather than through the controller. Media3 offers
        // the same thing as a player command, and on the way from the controller through the
        // session and the forwarding player to ExoPlayer it went missing: the watch's level
        // arrived here, the command was sent, and the audio service never heard of it. This is
        // the call that path ends in, made directly. The player still *reads* the volume — it
        // listens for the system's own volume broadcast — so the watch is told the result the
        // same way it is told when the phone's keys are pressed.
        val outcome = suspendRunCatching { audioManager.applyMediaVolume(level) }
        if (outcome.getOrNull() == true) return
        val refusal = outcome.exceptionOrNull() ?: IllegalStateException("the device's volume is fixed")
        // Whoever asked is on a watch, looking at a bar that slid back for no reason it can give.
        // Said once per process: the bezel asks several times a second, and the answer is the same.
        if (volumeRefusalReported.compareAndSet(false, true)) {
            crashReporter.recordNonFatal("device volume refused", refusal)
        }
    }

    /** Stops playback and empties the queue. */
    suspend fun stop() = onController { player ->
        player.stop()
        player.clearMediaItems()
    }

    /**
     * Shows the picture of the episode playing, at [quality], keeping its position.
     *
     * Idempotent: asking again for the rendition already showing changes nothing, which is what
     * lets the video screen ask on every start without a rotation costing a re-buffer.
     *
     * @param quality the rendition height wanted; the resolver settles for the nearest the video has.
     * @param episodeId the episode the picture is wanted for, or null for whichever is playing.
     *   The ask is decided before it is sent and the queue can move in between; named, the service
     *   leaves another episode alone instead of showing a picture nobody asked for.
     * @return true when the episode is showing, already was, or is no longer the one playing;
     *   false when it has no picture to show, when nothing is loaded, or when the service could
     *   not be reached.
     */
    suspend fun enterVideo(quality: VideoQuality, episodeId: String? = null): Boolean =
        sendSessionCommand(
            SESSION_COMMAND_ENTER_VIDEO,
            bundleOf(EXTRA_VIDEO_HEIGHT to quality.height, EXTRA_VIDEO_EPISODE_ID to episodeId),
        )

    /**
     * Goes back to sound only, keeping the position. Playback carries on; only the picture stops.
     *
     * @return true when the episode plays as sound, or already did; false when the service could
     *   not be reached.
     */
    suspend fun exitVideo(): Boolean = sendSessionCommand(SESSION_COMMAND_EXIT_VIDEO, Bundle.EMPTY)

    /**
     * Draws the picture on [output], or on nothing when it is null.
     *
     * The controller forwards the view to the service's player, so the picture is decoded where
     * the sound is and the two cannot drift. A `SurfaceView` is a hole in the window with the
     * picture behind it, so it cannot be clipped to rounded corners, faded, or moved with what it
     * sits in; a `TextureView` is drawn like any other view, at the cost of a copy that a picture
     * the size of a thumbnail does not notice. Hence one of each: the screen's and the bar's.
     *
     * The caller says where the picture goes *now*, every time that changes, rather than attaching
     * and detaching views one by one. Media3 keeps a single output: setting a second takes the
     * picture from the first and clearing the second leaves the player with none, so two holders
     * each managing their own view left the screen black after a rotation.
     *
     * Tried twice. The first failure is usually a service that went away, which the second attempt
     * reconnects to; a second failure is recorded, because nothing on screen will say why the
     * picture is missing.
     *
     * @param output where to draw, or null to stop drawing.
     * @return true when the player took it.
     */
    suspend fun showVideoOn(output: VideoOutput?): Boolean {
        var failure: Throwable? = null
        repeat(OUTPUT_ATTEMPTS) {
            val attempt = suspendRunCatching {
                withContext(Dispatchers.Main.immediate) {
                    val player = controller()
                    // Whatever was on the last output is not on this one until a frame is drawn.
                    pictureReady = false
                    when (output) {
                        is VideoOutput.Screen -> player.setVideoSurfaceView(output.view)
                        is VideoOutput.Bar -> player.setVideoTextureView(output.view)
                        null -> player.clearVideoSurface()
                    }
                }
            }
            if (attempt.isSuccess) return true
            failure = attempt.exceptionOrNull()
        }
        failure?.let { crashReporter.recordNonFatal(NON_FATAL_VIDEO_OUTPUT, it) }
        return false
    }

    /**
     * Takes [output] back from the player, if it is the one drawing.
     *
     * A no-op when the picture has since been given somewhere else to go, which is what makes this
     * safe to call late: it is how a holder that is going away lets go without taking the picture
     * from whoever came after it.
     *
     * @param output the output handed over by [showVideoOn].
     */
    suspend fun releaseVideoOutput(output: VideoOutput) = onController { player ->
        when (output) {
            is VideoOutput.Screen -> player.clearVideoSurfaceView(output.view)
            is VideoOutput.Bar -> player.clearVideoTextureView(output.view)
        }
    }

    /**
     * Sends one of this app's own session commands and waits for the answer.
     *
     * Unlike [onController], the answer matters: the service says whether it did the thing, and a
     * refusal is an outcome the screen words, not a failure. Only an exception — the service could
     * not be reached — is recorded as a command error.
     *
     * @return true when the service reported success.
     */
    private suspend fun sendSessionCommand(action: String, args: Bundle): Boolean =
        suspendRunCatching {
            withContext(Dispatchers.Main.immediate) {
                controller().sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args).await()
            }
        }
            .map { result ->
                // Skipped is the service declining an ask that events overtook, not a refusal.
                result.resultCode == SessionResult.RESULT_SUCCESS ||
                    result.resultCode == SessionResult.RESULT_INFO_SKIPPED
            }
            .getOrElse { error ->
                commandErrors.value = error.message ?: error::class.simpleName
                false
            }

    /**
     * Waits until the service has put the persisted queue back, or found none to put back.
     *
     * The service restores by itself when it is created; binding to it is all it takes to start
     * that. This is for the caller that has to act on the result — *Resume* presses play on whatever
     * came back — and would otherwise be reading a player the restore has not reached yet.
     *
     * An unreachable service is not reported as a command error: nothing was asked of the player,
     * and the caller's next reading of it says "nothing loaded" by itself.
     *
     * @return true once the restore has finished; false when the service could not be reached.
     */
    suspend fun awaitRestored(): Boolean =
        suspendRunCatching {
            withContext(Dispatchers.Main.immediate) {
                controller()
                    .sendCustomCommand(SessionCommand(SESSION_COMMAND_AWAIT_RESTORE, Bundle.EMPTY), Bundle.EMPTY)
                    .await()
            }
        }.map { result -> result.resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)

    /** Clears the last command error once the UI has shown it. */
    fun clearError() {
        commandErrors.value = null
    }

    /**
     * Reads the player's state directly, once.
     *
     * [playbackState] only reflects the player while something collects it, so a caller that needs
     * to know what is loaded *before* subscribing — the launcher's *Resume*, for one — has to
     * ask the controller itself. Returns a disconnected [PlaybackState] if the service cannot be
     * reached, which reads as "nothing is playing" and is the right answer in that case.
     */
    suspend fun currentState(): PlaybackState = withContext(Dispatchers.Main.immediate) {
        suspendRunCatching { controller().snapshot(commandErrors.value, pictureReady) }
            .getOrElse { PlaybackState() }
    }

    /**
     * Runs [block] against the controller on the main thread.
     *
     * Command failures are recorded rather than thrown: a player command failing (the service was
     * killed, say) must not take down the caller's view model.
     */
    private suspend fun onController(block: (MediaController) -> Unit) {
        suspendRunCatching { withContext(Dispatchers.Main.immediate) { block(controller()) } }
            .onFailure { error -> commandErrors.value = error.message ?: error::class.simpleName }
    }

    /**
     * Returns the connected controller, connecting on first use.
     *
     * The controller is kept for the process's lifetime: it binds the service without starting it,
     * and Media3 only promotes the service to the foreground while audio is actually playing, so an
     * idle connection costs nothing.
     */
    private suspend fun controller(): MediaController =
        withContext(Dispatchers.Main.immediate) {
            connectionLock.withLock {
                controller?.takeIf { it.isConnected } ?: MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                ).buildAsync().await().also { controller = it }
            }
        }

    private companion object {
        /** Full volume: what the player runs at whenever the sleep timer is not fading it out. */
        const val FULL_VOLUME = 1f

        /** How often the scrubber is refreshed while playing. */
        const val POSITION_TICK_MS = 500L

        /** Grace period before the controller's listener is detached after the last collector. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** Past this point, "previous" restarts the episode instead of leaving it. */
        const val RESTART_THRESHOLD_MS = 3_000L

        /** How many times an output is offered to the player before giving up; see [showVideoOn]. */
        const val OUTPUT_ATTEMPTS = 2

        /** One message for the failure kind, fixed, so each groups into one report. */
        const val NON_FATAL_VIDEO_OUTPUT = "Video output refused by the player"
    }
}

/** The index of [episodeId] in the player's queue, or null if it is not queued. */
private fun Player.indexOfEpisode(episodeId: String): Int? =
    (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).episodeId == episodeId }

/** The media duration once the player knows it, otherwise null. */
private fun Player.knownDurationMs(): Long? = duration.takeIf { it != C.TIME_UNSET && it > 0L }

/**
 * Sets the media stream's volume, if this device will have it set.
 *
 * Apart from [PlaybackConnection.setDeviceVolume] so that the clamp and the refusal can be tested
 * without a connection.
 *
 * @param level the level to set, clamped to the stream's own range.
 * @return false on a device whose volume is fixed — a TV, a car — where nothing was done.
 */
internal fun AudioManager.applyMediaVolume(level: Int): Boolean {
    if (isVolumeFixed) return false
    val range = getStreamMinVolume(AudioManager.STREAM_MUSIC)..getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    // No FLAG_SHOW_UI: the phone this is changing is in a pocket, and the system's volume panel
    // would be drawn for nobody — on a screen that is off, over whatever is on it.
    setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(range), /* flags = */ 0)
    return true
}

/**
 * Flattens the controller's current state into a [PlaybackState].
 *
 * @param errorMessage the last failed command's message, folded in so the UI reads one object.
 * @param firstFrameRendered whether a frame of the current picture has been drawn; see
 *   [PlaybackState.pictureReady].
 */
private fun MediaController.snapshot(errorMessage: String?, firstFrameRendered: Boolean): PlaybackState {
    val metadata = mediaMetadata
    // Media3 forbids reading either of these unless the command is available, and it is absent on
    // a player built without device-volume control. Both then stay zero, which is the same thing
    // the player itself would report and which reads downstream as "there is no scale here".
    val volumeReadable = isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)
    return PlaybackState(
        isConnected = isConnected,
        episodeId = currentMediaItem?.episodeId,
        title = metadata.title?.toString().orEmpty(),
        showTitle = metadata.artist?.toString().orEmpty(),
        artworkUrl = metadata.artworkUri?.toString(),
        isPlaying = isPlaying,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        positionMs = currentPosition.coerceAtLeast(0L),
        durationMs = durationToShowMs(measuredMs = knownDurationMs(), publishedMs = metadata.durationMs),
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        speed = playbackParameters.speed,
        queueEpisodeIds = (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).episodeId },
        queueIndex = currentMediaItemIndex,
        volume = if (volumeReadable) deviceVolume else 0,
        maxVolume = if (volumeReadable) deviceInfo.maxVolume else 0,
        errorMessage = playerError?.message ?: errorMessage,
        // Classified here rather than at the screen, because this is the only place that has both
        // the exception and the episode it belongs to; see [playbackErrorOf] on why the second
        // one matters. A command that failed with no player error is still an unknown failure —
        // the message is all there is for it.
        error = playbackErrorOf(playerError, isYouTube = currentMediaItem.isYouTubeSourced())
            ?: errorMessage?.let { PlaybackError.UNKNOWN },
        youTubeVideoId = currentMediaItem?.youTubeVideoId,
        videoQuality = currentMediaItem?.videoQualityOrNull,
        videoWidth = videoSize.width,
        videoHeight = videoSize.height,
        // Only ever true of an item that is showing its picture: a frame left over from before a
        // fall back to sound is not a picture of what is playing now.
        pictureReady = firstFrameRendered && currentMediaItem?.videoQualityOrNull != null,
    )
}

/** Whether this item's audio is a `youtube://` sentinel rather than a real enclosure. */
private fun MediaItem?.isYouTubeSourced(): Boolean =
    this?.localConfiguration?.uri?.scheme == YOUTUBE_SCHEME

/** The scheme of the internal sentinel; see `youTubeAudioSentinel` in `:core:model`. */
private const val YOUTUBE_SCHEME = "youtube"

/**
 * Suspends until a [ListenableFuture] completes.
 *
 * Media3 hands back Guava futures; this is the short bridge to coroutines that avoids pulling in
 * `kotlinx-coroutines-guava` for one call site.
 *
 * The two failures [java.util.concurrent.Future.get] can report are kept apart deliberately. A
 * future that *failed* wraps the real cause in an [ExecutionException], and the caller wants the
 * cause — `PlaybackConnection` puts its message in front of the user. A future that was
 * *cancelled* is not a failure at all: it must cancel the waiting coroutine, not resume it with an
 * exception that would then be reported as a broken player.
 */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (cancellation: CancellationException) {
                continuation.cancel(cancellation)
            } catch (failure: ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            }
        },
        // The listener only completes a continuation, so hopping threads would be pure overhead.
        MoreExecutors.directExecutor(),
    )
    continuation.invokeOnCancellation { cancel(/* mayInterruptIfRunning = */ false) }
}
