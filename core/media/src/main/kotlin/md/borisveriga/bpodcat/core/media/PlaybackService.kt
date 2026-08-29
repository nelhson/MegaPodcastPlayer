package md.borisveriga.bpodcat.core.media

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import md.borisveriga.bpodcat.core.datastore.UserPreferencesDataSource
import md.borisveriga.bpodcat.core.media.di.PlaybackDataSource

/**
 * The foreground service that owns the one and only [ExoPlayer] instance.
 *
 * Everything that has to keep working while the UI is gone lives here: audio focus, the media
 * notification, and writing playback position back to the database. The UI talks to it through a
 * [androidx.media3.session.MediaController], never directly, which is also how the notification,
 * Bluetooth controls and (later) the watch reach the same player.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    /**
     * Reads downloaded audio from the download cache and falls back to the network.
     *
     * This is what makes a downloaded episode play in airplane mode: the player asks the cache
     * first and only reaches for the network when the bytes are not already on disk.
     */
    @Inject
    @PlaybackDataSource
    lateinit var dataSourceFactory: DataSource.Factory

    /** Supplies episodes when the system asks us to resume playback after a process death. */
    @Inject
    lateinit var queueSource: PlaybackQueueSource

    /** Receives position, played state and queue changes. */
    @Inject
    lateinit var progressRecorder: PlaybackProgressRecorder

    /** Playback speed and skip intervals. */
    @Inject
    lateinit var userPreferences: UserPreferencesDataSource

    /**
     * Scope for progress writes.
     *
     * Runs on the main dispatcher because every callback that feeds it already arrives on the
     * player's thread, and cancelled in [onDestroy] so nothing outlives the player it describes.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // The player must exist synchronously — Media3 hands the session a Player during onCreate —
        // and the persisted speed and skip intervals are part of building it. This is one small
        // DataStore read of a file the OS has usually already paged in.
        val settings = runBlocking { userPreferences.playbackSettings.first() }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // The speech content type lets a car head unit duck us for a navigation prompt
                    // instead of pausing, and asks the platform for speech-tuned processing.
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause rather than blare out of the phone speaker when headphones are unplugged.
            .setHandleAudioBecomingNoisy(true)
            // Streaming needs the radio to stay up while the screen is off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // These drive the *notification's* skip buttons. The in-app buttons seek explicitly
            // through PlaybackConnection, so a changed interval takes effect there immediately and
            // here the next time the service starts.
            .setSeekForwardIncrementMs(settings.skipForwardMs)
            .setSeekBackIncrementMs(settings.skipBackMs)
            .build()
            .apply { setPlaybackSpeed(settings.speed) }

        player.addListener(PlaybackPersistenceListener(player))

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(ResumptionCallback())
            .apply { sessionActivityIntent()?.let(::setSessionActivity) }
            .build()

        startPositionTicker(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Stops the service when the user swipes the app away and nothing is playing.
     *
     * Without this, a paused session lingers as a notification the user cannot dismiss.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            // One last flush. Deliberately blocking: [serviceScope] is cancelled immediately below,
            // so a launched coroutine would never get to run.
            runBlocking { savePosition(player) }
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Persists the current position every [POSITION_SAVE_INTERVAL_MS] while audio is playing.
     *
     * A timer rather than a callback because Media3 publishes no "position changed" event — the
     * position simply advances. Five seconds is the compromise between losing progress to a crash
     * and writing to disk more often than anyone could notice.
     */
    private fun startPositionTicker(player: Player) {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition(player)
            }
        }
    }

    /** Writes the loaded episode's position, if an episode is loaded at all. */
    private suspend fun savePosition(player: Player) {
        val episodeId = player.currentMediaItem?.episodeId ?: return
        progressRecorder.recordPosition(
            episodeId = episodeId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            // Feeds routinely misreport itunes:duration, so prefer what the decoder measured — but
            // only once it knows.
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L },
        )
    }

    /**
     * A [PendingIntent] that reopens the app, so tapping the media notification lands on the player
     * rather than on nothing.
     *
     * Resolved through the package manager rather than by naming an activity class, which would
     * force `:core:media` to depend on `:app`.
     */
    private fun sessionActivityIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                /* requestCode = */ 0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    /**
     * Mirrors what the player does into durable storage.
     *
     * @property player the player being observed; callbacks that need more than their arguments
     *   (the queue, the current position) read it directly.
     */
    private inner class PlaybackPersistenceListener(private val player: Player) : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            serviceScope.launch { userPreferences.setLastPlayedEpisodeId(mediaItem?.episodeId) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // An automatic transition is the only discontinuity that means "the previous episode
            // finished". A seek, or a user tapping "next", must not mark anything played.
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            val finishedId = oldPosition.mediaItem?.episodeId ?: return
            serviceScope.launch { progressRecorder.recordCompleted(finishedId) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // STATE_ENDED is the last episode in the queue finishing; earlier ones arrive as an
            // automatic transition instead.
            if (playbackState != Player.STATE_ENDED) return
            val episodeId = player.currentMediaItem?.episodeId ?: return
            serviceScope.launch { progressRecorder.recordCompleted(episodeId) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Pausing is the moment the user is most likely to close the app, so flush now instead
            // of waiting up to five seconds for the ticker.
            if (!isPlaying) serviceScope.launch { savePosition(player) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            val episodeIds = (0 until player.mediaItemCount)
                .mapNotNull { index -> player.getMediaItemAt(index).episodeId }
            serviceScope.launch { progressRecorder.recordQueue(episodeIds) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // The controller surfaces this to the user; log it where the cause is readable.
            Log.w(TAG, "Playback failed for ${player.currentMediaItem?.episodeId}", error)
        }
    }

    /**
     * Answers the system's request to resume playback without the app being open — the headset play
     * button, or the Android 13+ media resumption tile.
     */
    private inner class ResumptionCallback : MediaSession.Callback {

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val queue = queueSource.resumableQueue()
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            queue.map { it.toMediaItem() },
                            /* startIndex = */ 0,
                            // Resume where the user stopped, not at the top of the episode.
                            /* startPositionMs = */ queue.firstOrNull()?.episode?.positionMs ?: 0L,
                        ),
                    )
                } catch (e: Exception) {
                    // Media3 turns a failed future into "nothing to resume", which is the right
                    // outcome when the database cannot be read.
                    Log.w(TAG, "Could not rebuild the queue for playback resumption", e)
                    future.setException(e)
                }
            }
            return future
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** How often the position is written while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
