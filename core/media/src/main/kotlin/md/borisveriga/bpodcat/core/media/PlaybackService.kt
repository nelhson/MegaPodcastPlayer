package md.borisveriga.bpodcat.core.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
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
            .setCallback(SessionCallback())
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
     * The session's policy: who may connect, and what to hand them on resumption.
     *
     * @see onConnect
     * @see onPlaybackResumption
     */
    private inner class SessionCallback : MediaSession.Callback {

        /**
         * Decides which controllers may bind the session.
         *
         * The service is `exported="true"` because Media3 requires it, so without this override any
         * app on the device could connect, read episode and show titles out of the metadata, and
         * drive playback. The default `MediaSession.Callback.onConnect` accepts everyone.
         *
         * Accepted, with the full command set:
         *  - this app's own UID — the UI, and the media button receiver that ships inside it;
         *  - [Process.SYSTEM_UID] — the notification shade, the lock screen and the media
         *    resumption tile, all of which are system UI;
         *  - the packages in [TRUSTED_CONTROLLER_PACKAGES].
         *
         * Everyone else is rejected outright rather than connected with an empty command set: a
         * connected controller can still read `MediaMetadata`, and the metadata is half of what
         * there is to protect here.
         *
         * If a legitimate integration ever stops working — a car head unit, a launcher's media
         * widget — the fix is to add its package to that list, having checked what it is.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val isTrusted = controller.uid == Process.myUid() ||
                controller.uid == Process.SYSTEM_UID ||
                controller.packageName == packageName ||
                controller.packageName in TRUSTED_CONTROLLER_PACKAGES

            if (!isTrusted) {
                Log.i(TAG, "Refused a media session connection from ${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    // mapNotNull, not map: an episode whose stored audio URL fails the scheme
                    // allowlist is dropped from the resumed queue rather than handed to the player.
                    val queue = queueSource.resumableQueue().filter { it.hasPlayableAudio }
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            queue.mapNotNull { it.toMediaItemOrNull() },
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

        /**
         * Packages allowed to control playback despite running as another app.
         *
         * Each entry is a first-party surface a user reasonably expects to drive a podcast player
         * from, and each is here because rejecting it would break a feature rather than close a
         * hole. Deliberately short: anything not on it, and not this app or the system, is refused.
         */
        val TRUSTED_CONTROLLER_PACKAGES = setOf(
            // Android Auto's projected UI.
            "com.google.android.projection.gearhead",
            // Assistant ("play my podcast"), which connects as the search app.
            "com.google.android.googlequicksearchbox",
            // The Wear OS companion, which is what puts media controls on a paired watch. Distinct
            // from BPodcat's own watch app: that one talks over the Data Layer, not a MediaSession.
            "com.google.android.wearable.app",
            // The Bluetooth stack's AVRCP bridge, i.e. the buttons on a car stereo or headset.
            "com.android.bluetooth",
        )

        /** How often the position is written while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
