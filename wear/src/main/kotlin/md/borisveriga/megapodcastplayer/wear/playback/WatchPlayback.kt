package md.borisveriga.megapodcastplayer.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.await
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode
import md.borisveriga.megapodcastplayer.wear.data.WatchEpisodeStore

/**
 * The screen's handle on the watch's own player.
 *
 * The player itself lives in [WatchPlaybackService], where it can outlive every screen; this is the
 * [MediaController] that talks to it. The split is the same one the phone app makes, and for the
 * same reason: a player owned by a composable stops when the wrist drops.
 *
 * One controller is kept for the life of the process rather than one per screen. A controller is a
 * binder connection, and connecting one takes a round trip through the system — long enough that
 * building a fresh one for each tap would put a visible pause between pressing play and hearing
 * anything.
 *
 * @property context used to address the service and to build the controller.
 * @property store the episodes on disk: where their audio is, and where the wearer left them.
 */
@Singleton
class WatchPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: WatchEpisodeStore,
) {

    /** Guards [controller] so two taps at once cannot build two connections. */
    private val mutex = Mutex()

    private var controller: MediaController? = null

    /**
     * What the watch's player is doing, as it changes.
     *
     * Null when nothing is loaded, which is the ordinary state: the watch is a remote control until
     * somebody deliberately plays something on it.
     *
     * The position is both pushed and polled. A player reports *events* — playing, paused, seeked —
     * but a position that merely advances is not an event, so the ticker below fills in between
     * them. It runs only while something is playing, and only while this flow is collected, so a
     * screen that has gone away costs nothing.
     *
     * Emits null before connecting anything. The screen state is a `combine`, so it renders nothing
     * at all until every source has spoken once — and binding a media session is a round trip
     * through the system that can take a moment, or on a watch whose playback service will not start,
     * forever. Nothing local is playing until this says so anyway.
     */
    val state: Flow<WatchPlaybackState?> = callbackFlow {
        send(null)

        val controller = controller() ?: run {
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                trySend(readState(player))
            }
        }
        controller.addListener(listener)
        send(readState(controller))

        val ticker = launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                if (controller.isPlaying) trySend(readState(controller))
            }
        }

        awaitClose {
            ticker.cancel()
            controller.removeListener(listener)
        }
    }
        // A MediaController may only be touched from the thread it was built on.
        .flowOn(Dispatchers.Main.immediate)

    /**
     * Starts an episode the watch holds.
     *
     * Resumes from where it was left rather than from the beginning, which for a half-heard episode
     * is the only sane place to start; that position is the watch's own record, kept by
     * [WatchEpisodeStore] and written by the service.
     *
     * @param episode the episode to play.
     */
    suspend fun play(episode: StoredEpisode) {
        val controller = controller() ?: return
        val file = store.audioFile(episode.id)
        if (!file.exists()) return

        withContext(Dispatchers.Main.immediate) {
            controller.setMediaItem(
                MediaItem.Builder()
                    // The id travels with the item so the service knows whose position it is
                    // writing down, without holding any state of its own.
                    .setMediaId(episode.id)
                    .setUri(file.toUri())
                    .build(),
                // A finished episode starts again; anything else picks up where it stopped.
                if (episode.isPlayed) 0L else episode.positionMs,
            )
            controller.prepare()
            controller.play()
        }
    }

    /** Starts or pauses whatever is loaded. */
    suspend fun togglePlayPause() = onController {
        if (it.isPlaying) it.pause() else it.play()
    }

    /**
     * Jumps forward, stopping at the end of the episode.
     *
     * @param skipMs how far, in milliseconds.
     */
    suspend fun skipForward(skipMs: Long) = onController {
        val target = it.currentPosition + skipMs
        // The player reports an unset duration until it has read enough of the file, and clamping
        // to that would seek to zero — a skip-ahead button that jumps to the beginning.
        val duration = it.duration
        it.seekTo(if (duration > 0L) minOf(target, duration) else target)
    }

    /**
     * Jumps back, stopping at the beginning.
     *
     * @param skipMs how far, in milliseconds.
     */
    suspend fun skipBack(skipMs: Long) = onController {
        it.seekTo((it.currentPosition - skipMs).coerceAtLeast(0L))
    }

    /**
     * Seeks to an absolute position.
     *
     * @param positionMs where to go.
     */
    suspend fun seekTo(positionMs: Long) = onController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    /**
     * Sets the playback rate.
     *
     * @param speed the new rate.
     */
    suspend fun setSpeed(speed: Float) = onController { it.setPlaybackSpeed(speed) }

    /**
     * Stops local playback and unloads the episode.
     *
     * This is what hands the screen back to the phone: with nothing loaded here, the watch is a
     * remote control again.
     */
    suspend fun stop() = onController {
        it.pause()
        it.clearMediaItems()
    }

    /**
     * Runs one command against the controller on the thread it belongs to.
     *
     * @param block what to do with it; skipped entirely when there is no service to talk to, which
     *   is what a watch whose playback service could not start looks like.
     */
    private suspend fun onController(block: (MediaController) -> Unit) {
        val controller = controller() ?: return
        withContext(Dispatchers.Main.immediate) { block(controller) }
    }

    /**
     * The controller, connecting it on first use.
     *
     * @return the controller, or null if the service could not be reached — in which case every
     *   local control quietly does nothing, and the screen keeps working as a remote for the phone.
     */
    private suspend fun controller(): MediaController? = mutex.withLock {
        controller?.takeIf { it.isConnected } ?: connect()?.also { controller = it }
    }

    /** Builds a controller bound to [WatchPlaybackService]. */
    private suspend fun connect(): MediaController? = withContext(Dispatchers.Main.immediate) {
        suspendRunCatching {
            val token = SessionToken(
                context,
                ComponentName(context, WatchPlaybackService::class.java),
            )
            MediaController.Builder(context, token).buildAsync().await()
        }.getOrNull()
    }

    /**
     * Reads the player into a state the screen can draw.
     *
     * @param player the controller, on its own thread.
     * @return the state, or null when nothing is loaded.
     */
    private fun readState(player: Player): WatchPlaybackState? {
        val episodeId = player.currentMediaItem?.mediaId ?: return null
        val episode = store.episodes.value.firstOrNull { it.id == episodeId } ?: return null

        return WatchPlaybackState(
            episode = episode,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
        )
    }

    private companion object {
        /**
         * How often the position is re-read while playing.
         *
         * Twice a second: the progress bar is a few pixels tall, and the label beside it counts in
         * seconds, so anything finer would be redrawing the same picture.
         */
        const val POSITION_TICK_MS = 500L
    }
}
