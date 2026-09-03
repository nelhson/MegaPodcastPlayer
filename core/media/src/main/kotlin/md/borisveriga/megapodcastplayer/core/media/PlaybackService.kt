package md.borisveriga.megapodcastplayer.core.media

import android.app.PendingIntent
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.di.PlaybackDataSource
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * The foreground service that owns the one and only [ExoPlayer] instance.
 *
 * Everything that has to keep working while the UI is gone lives here: audio focus, the media
 * notification, and writing playback position back to the database. The UI talks to it through a
 * [androidx.media3.session.MediaController], never directly, which is also how the notification,
 * Bluetooth controls and (later) the watch reach the same player.
 *
 * **How it survives the screen going off.** Nothing here calls `startForeground` directly. Media3
 * does it, and the trigger is the notification: when a session becomes user-engaged it posts the
 * one [playbackNotificationProvider] builds and promotes this service to the foreground, which is
 * the state the platform declines to kill. When playback pauses it lets the promotion lapse — after
 * a grace period of [MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS], the default this
 * service keeps — and the process becomes ordinary background memory again. That is by design and
 * not a bug to route around: a long-paused episode comes back through `onPlaybackResumption` and
 * the persisted queue, not by holding the whole process hostage.
 *
 * The corollary is that the two ways foregrounding can fail both have to be handled, because either
 * one silently leaves audio running in a killable process:
 *
 *  - the promotion itself being refused, which [ForegroundStartListener] catches;
 *  - stopping the service while it still holds the foreground, which is why `onTaskRemoved` is
 *    *not* overridden here. Media3's implementation goes through `pauseAllPlayersAndStopSelf()`,
 *    which drops the foreground state before `stopSelf()`; a bare `stopSelf()` in its place gets
 *    the service torn down and restarted by the system. Its default is already what this app
 *    wants — keep playing when swiped away mid-episode, stop when nothing is playing.
 */
// Opting *in*, rather than being marked `@UnstableApi` itself: the foreground and notification
// controls this service needs are all unstable Media3 API, but PlaybackConnection names this class
// to build a SessionToken, and marking it unstable would push that opt-in onto every caller.
@OptIn(UnstableApi::class)
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
     * Outlives the service, and is therefore the only scope that can carry the final position
     * write in [onDestroy] — [serviceScope] is cancelled there by definition.
     */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

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

        // Before anything can play. Posting this notification is how Media3 promotes the service to
        // the foreground, and a foreground service is the only kind the platform leaves alone once
        // the screen goes off — so the channel has to exist and the provider has to be installed
        // before the first play, not lazily on the way to it.
        createPlaybackNotificationChannel(this)
        setMediaNotificationProvider(playbackNotificationProvider(this))
        setListener(ForegroundStartListener())

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
            // through PlaybackConnection, so a changed interval takes effect there immediately.
            // Built with the defaults and corrected by [applyPersistedSettings] below: Media3 hands
            // the session a Player during onCreate, so construction cannot wait on disk.
            .setSeekForwardIncrementMs(PlaybackSettings.DEFAULT_SKIP_FORWARD_MS)
            .setSeekBackIncrementMs(PlaybackSettings.DEFAULT_SKIP_BACK_MS)
            .build()

        player.addListener(
            PlaybackPersistenceListener(
                player = player,
                scope = serviceScope,
                progressRecorder = progressRecorder,
                userPreferences = userPreferences,
            ),
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .apply { sessionActivityIntent()?.let(::setSessionActivity) }
            .build()

        startPositionTicker(player)
        applyPersistedSettings(player)
    }

    /**
     * Applies the user's stored speed and skip intervals to an already-built player.
     *
     * Deliberately asynchronous. Blocking `onCreate` on a DataStore read is harmless on the happy
     * path and dangerous on the one that matters: the service is most often recreated *under memory
     * pressure*, which is exactly when a cold DataStore read has to go to disk and can reach the ANR
     * window. All three values are settable after construction, so the only consequence of waiting
     * is that the notification's skip buttons use [PlaybackSettings]' defaults for the few
     * milliseconds before the read lands — and nothing can be playing yet at that point.
     *
     * @param player the player built in [onCreate].
     */
    private fun applyPersistedSettings(player: ExoPlayer) {
        serviceScope.launch {
            val settings = userPreferences.playbackSettings.first()
            player.setPlaybackSpeed(settings.speed)
            player.setSeekForwardIncrementMs(settings.skipForwardMs)
            player.setSeekBackIncrementMs(settings.skipBackMs)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Nothing else may be delivered to the listener once the player is gone.
        clearListener()
        mediaSession?.run {
            // One last flush, in two halves. The reading is synchronous because the player is
            // released on the very next line; the *write* is handed to [applicationScope], which
            // outlives the service. Blocking here instead would put disk IO on the main thread
            // during system-initiated shutdown — the moment disk contention is at its worst.
            player.positionReading()?.let { position ->
                applicationScope.launch { position.recordInto(progressRecorder) }
            }
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
                if (player.isPlaying) player.positionReading()?.recordInto(progressRecorder)
            }
        }
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
     * Handles Media3 failing to put this service in the foreground.
     *
     * The platform refuses the promotion when the app is not in a state that permits starting a
     * foreground service — most often because playback was triggered from the background, by a
     * media button or a resumption request, after the app's window to start one had closed.
     *
     * Stopping is the only correct response, and Media3 documents it as such: a service that was
     * started with `startForegroundService` and never reached `startForeground` is force-stopped by
     * the system, which surfaces to the user as the app disappearing mid-episode.
     * [pauseAllPlayersAndStopSelf] gets there in the right order — pausing first, which runs the
     * persistence listener and writes the position, so the episode resumes where it stopped rather
     * than where it was last flushed.
     */
    private inner class ForegroundStartListener : Listener {
        override fun onForegroundServiceStartNotAllowedException() {
            Log.w(TAG, "Not allowed to start the playback service in the foreground; stopping")
            pauseAllPlayersAndStopSelf()
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
            val job = serviceScope.launch {
                suspendRunCatching {
                    // mapNotNull, not map: an episode whose stored audio URL fails the scheme
                    // allowlist is dropped from the resumed queue rather than handed to the player.
                    val queue = queueSource.resumableQueue().filter { it.hasPlayableAudio }
                    MediaSession.MediaItemsWithStartPosition(
                        queue.mapNotNull { it.toMediaItemOrNull() },
                        /* startIndex = */ 0,
                        // Resume where the user stopped, not at the top of the episode.
                        /* startPositionMs = */ queue.firstOrNull()?.episode?.positionMs ?: 0L,
                    )
                }.onSuccess { items -> future.set(items) }.onFailure { error ->
                    // Media3 turns a failed future into "nothing to resume", which is the right
                    // outcome when the database cannot be read.
                    Log.w(TAG, "Could not rebuild the queue for playback resumption", error)
                    future.setException(error)
                }
            }
            // Cancellation propagates out of the block above now that it is no longer swallowed, so
            // the service dying mid-read must not leave Media3 awaiting a future forever. This is a
            // no-op on a future that has already been set.
            job.invokeOnCompletion { future.cancel(/* mayInterruptIfRunning = */ false) }
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
            // from MegaPodcastPlayer's own watch app: that one talks over the Data Layer, not a MediaSession.
            "com.google.android.wearable.app",
            // The Bluetooth stack's AVRCP bridge, i.e. the buttons on a car stereo or headset.
            "com.android.bluetooth",
        )

        /** How often the position is written while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
