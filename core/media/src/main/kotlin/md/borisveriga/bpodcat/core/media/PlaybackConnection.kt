package md.borisveriga.bpodcat.core.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
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
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.model.PlaybackSettings

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
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards lazy creation of [controller] against two screens connecting at once. */
    private val connectionLock = Mutex()

    private var controller: MediaController? = null

    /** Set when a command fails, so the UI can explain why nothing happened. */
    private val commandErrors = MutableStateFlow<String?>(null)

    /**
     * The current playback state, re-emitted on every player event and, while playing, every
     * [POSITION_TICK_MS] so the scrubber advances.
     *
     * Sharing is [SharingStarted.WhileSubscribed] with a grace period: rotating the device or
     * navigating from the mini player to the full player must not tear the connection down.
     */
    val playbackState: StateFlow<PlaybackState> = callbackFlow {
        val mediaController = try {
            controller()
        } catch (e: Exception) {
            // No service means no playback, but the UI must still render — as idle, not as a crash.
            send(PlaybackState(isConnected = false, errorMessage = e.message))
            return@callbackFlow
        }

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                trySend(mediaController.snapshot(commandErrors.value))
            }
        }
        mediaController.addListener(listener)
        send(mediaController.snapshot(commandErrors.value))

        val ticker = launch {
            while (isActive) {
                kotlinx.coroutines.delay(POSITION_TICK_MS)
                if (mediaController.isPlaying) {
                    trySend(mediaController.snapshot(commandErrors.value))
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
            val insertAt = if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
            player.addMediaItem(insertAt, episode.toMediaItem())
            player.seekTo(insertAt, startPositionMs)
        }
        player.prepare()
        player.play()
    }

    /**
     * Replaces the whole queue and starts playing.
     *
     * Used when restoring a persisted queue on a cold start, and when the user plays a list.
     *
     * @param episodes the new queue, in play order.
     * @param startIndex which entry to start on.
     * @param startPositionMs where in that entry to start.
     * @param playWhenReady false to load the queue without making noise, which is what a cold start
     *   does so the mini player appears without ambushing the user.
     */
    suspend fun setQueue(
        episodes: List<PlayableEpisode>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) = onController { player ->
        if (episodes.isEmpty()) {
            player.clearMediaItems()
            return@onController
        }
        player.setMediaItems(
            episodes.map { it.toMediaItem() },
            startIndex.coerceIn(episodes.indices),
            startPositionMs.coerceAtLeast(0L),
        )
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /** Appends [episode] to the end of the queue without disturbing what is playing. */
    suspend fun addToQueue(episode: PlayableEpisode) = onController { player ->
        if (player.indexOfEpisode(episode.episode.id) != null) return@onController
        player.addMediaItem(episode.toMediaItem())
        // A queue added to while the player is empty should be ready to play on the first tap.
        if (player.mediaItemCount == 1) player.prepare()
    }

    /** Plays [episode] immediately after the current one, ahead of everything else queued. */
    suspend fun playNext(episode: PlayableEpisode) = onController { player ->
        player.indexOfEpisode(episode.episode.id)?.let(player::removeMediaItem)
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, episode.toMediaItem())
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

    /** Stops playback and empties the queue. */
    suspend fun stop() = onController { player ->
        player.stop()
        player.clearMediaItems()
    }

    /** Clears the last command error once the UI has shown it. */
    fun clearError() {
        commandErrors.value = null
    }

    /**
     * Reads the player's state directly, once.
     *
     * [playbackState] only reflects the player while something collects it, so a caller that needs
     * to know what is loaded *before* subscribing — the cold-start queue restore, for one — has to
     * ask the controller itself. Returns a disconnected [PlaybackState] if the service cannot be
     * reached, which reads as "nothing is playing" and is the right answer in that case.
     */
    suspend fun currentState(): PlaybackState = withContext(Dispatchers.Main.immediate) {
        runCatching { controller().snapshot(commandErrors.value) }.getOrElse { PlaybackState() }
    }

    /**
     * Runs [block] against the controller on the main thread.
     *
     * Command failures are recorded rather than thrown: a player command failing (the service was
     * killed, say) must not take down the caller's view model.
     */
    private suspend fun onController(block: (MediaController) -> Unit) {
        try {
            withContext(Dispatchers.Main.immediate) { block(controller()) }
        } catch (e: Exception) {
            commandErrors.value = e.message ?: e::class.simpleName
        }
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
        /** How often the scrubber is refreshed while playing. */
        const val POSITION_TICK_MS = 500L

        /** Grace period before the controller's listener is detached after the last collector. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** Past this point, "previous" restarts the episode instead of leaving it. */
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}

/** The index of [episodeId] in the player's queue, or null if it is not queued. */
private fun Player.indexOfEpisode(episodeId: String): Int? =
    (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).episodeId == episodeId }

/** The media duration once the player knows it, otherwise null. */
private fun Player.knownDurationMs(): Long? = duration.takeIf { it != C.TIME_UNSET && it > 0L }

/**
 * Flattens the controller's current state into a [PlaybackState].
 *
 * @param errorMessage the last failed command's message, folded in so the UI reads one object.
 */
private fun MediaController.snapshot(errorMessage: String?): PlaybackState {
    val metadata = mediaMetadata
    return PlaybackState(
        isConnected = isConnected,
        episodeId = currentMediaItem?.episodeId,
        title = metadata.title?.toString().orEmpty(),
        showTitle = metadata.artist?.toString().orEmpty(),
        artworkUrl = metadata.artworkUri?.toString(),
        isPlaying = isPlaying,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        positionMs = currentPosition.coerceAtLeast(0L),
        durationMs = knownDurationMs() ?: 0L,
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        speed = playbackParameters.speed,
        queueEpisodeIds = (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).episodeId },
        queueIndex = currentMediaItemIndex,
        errorMessage = playerError?.message ?: errorMessage,
    )
}

/**
 * Suspends until a [ListenableFuture] completes.
 *
 * Media3 hands back Guava futures; this is the two-line bridge to coroutines that avoids pulling in
 * `kotlinx-coroutines-guava` for one call site.
 */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        },
        // The listener only completes a continuation, so hopping threads would be pure overhead.
        MoreExecutors.directExecutor(),
    )
    continuation.invokeOnCancellation { cancel(/* mayInterruptIfRunning = */ false) }
}
