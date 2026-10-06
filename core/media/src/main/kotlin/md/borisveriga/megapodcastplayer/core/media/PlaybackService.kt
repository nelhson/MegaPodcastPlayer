package md.borisveriga.megapodcastplayer.core.media

import android.app.PendingIntent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.di.PlaybackDataSource
import md.borisveriga.megapodcastplayer.core.media.youtube.YouTubeMediaSourceFactory
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

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

    /** Where playback was left: what [restoreQueue] loads, and what the system is handed to resume. */
    @Inject
    lateinit var queueSource: PlaybackQueueSource

    /** Receives position, played state and queue changes. */
    @Inject
    lateinit var progressRecorder: PlaybackProgressRecorder

    /** Playback speed and skip intervals. */
    @Inject
    lateinit var userPreferences: UserPreferencesDataSource

    /** Whether the user asked to be told when the episode playing finishes. */
    @Inject
    lateinit var episodeEndBell: EpisodeEndBell

    /** Where that bell is rung, once the player says an episode has ended. */
    @Inject
    lateinit var bellRinger: BellRinger

    /** The loaded episode's chapters, so the notification's previous/next can mean one. */
    @Inject
    lateinit var chapterSource: PlaybackChapterSource

    /** Where a failed progress write goes, since nothing on screen is waiting for one. */
    @Inject
    lateinit var crashReporter: CrashReporter

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

    /**
     * The player itself, kept apart from the session's wrapper for the one thing the wrapper is
     * not for: swapping the item playing between its sound-only and its video flavour, which is an
     * edit to the playlist rather than a meaning given to a button.
     */
    private var exoPlayer: ExoPlayer? = null

    /**
     * Completed once [restoreQueue] has finished, with or without anything to restore, and when the
     * service is destroyed — so that nothing waiting on it waits for a service that is gone.
     */
    private val restored = CompletableDeferred<Unit>()

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
            // Media3's default for every stored episode; the wrapper only steps in for a video
            // sentinel, which it plays as a picture merged with the episode's own audio.
            .setMediaSourceFactory(
                YouTubeMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory), dataSourceFactory),
            )
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
            // Off by default, and off means the player builds no StreamVolumeManager at all: the
            // device-volume commands are then absent from `availableCommands`, `deviceVolume`
            // reads zero and `deviceInfo.maxVolume` reads zero. The watch's volume control is
            // exactly that scale arriving over the Data Layer, so without this line it draws
            // nothing and nothing explains why. The stream it moves is the one the audio
            // attributes above choose, which is the music stream.
            .setDeviceVolumeControlEnabled(true)
            // Streaming needs the radio to stay up while the screen is off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // These are what the *notification's* skip buttons actually seek by; the glyphs beside
            // them come from [mediaButtonPreferences]. The in-app buttons seek explicitly through
            // PlaybackConnection, so a changed interval takes effect there immediately. Built with
            // the defaults and corrected by [followPersistedSettings] below: Media3 hands the
            // session a Player during onCreate, so construction cannot wait on disk.
            .setSeekForwardIncrementMs(PlaybackSettings.DEFAULT_SKIP_FORWARD_MS)
            .setSeekBackIncrementMs(PlaybackSettings.DEFAULT_SKIP_BACK_MS)
            .build()
        exoPlayer = player

        player.addListener(
            PlaybackPersistenceListener(
                player = player,
                scope = serviceScope,
                progressRecorder = progressRecorder,
                userPreferences = userPreferences,
                crashReporter = crashReporter,
            ),
        )

        // A second listener rather than more branches in the first: what the database remembers and
        // what wakes the user are unrelated concerns that happen to read the same two callbacks.
        player.addListener(
            EndOfEpisodeBellListener(
                player = player,
                scope = serviceScope,
                bell = episodeEndBell,
                ringer = bellRinger,
            ),
        )

        // A picture that fails takes the sound down with it, since the two are one merged source;
        // this hands the episode back to sound so the listening carries on. Only once the retries
        // below are used up: most failures are a moment of bad network, and asking again is cheaper
        // for the user than being told.
        val videoFallback = VideoFallbackListener(player = player, crashReporter = crashReporter)
        player.addListener(videoFallback)
        // Before the session is built, so it hears the error first: the controllers are told a
        // retry is pending before the error itself reaches them, and show buffering instead.
        val retry = PlaybackRetryListener(
            player = player,
            scope = serviceScope,
            crashReporter = crashReporter,
            onRetryingChanged = { retrying -> mediaSession?.setSessionExtras(retryingExtras(retrying)) },
            onGaveUp = videoFallback::fallBack,
        )
        player.addListener(retry)

        // Everything above is installed on the real player, because everything above is about what
        // the *player* did. The wrapper below is about what a button *means*, and it is what the
        // session — and therefore the notification, the lock screen, a car and a headset — sees.
        // A stop from any controller drops a retry still waiting; see [PlaybackRetryListener.cancelPending].
        val sessionPlayer = ChapterAwarePlayer(player, onStop = retry::cancelPending)
        sessionPlayer.addListener(
            ChapterFollowingListener(
                player = sessionPlayer,
                scope = serviceScope,
                chapterSource = chapterSource,
            ),
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setCallback(SessionCallback())
            // Skip back and skip forward in the two slots the lock screen actually shows, rather
            // than Media3's music-shaped previous/next default; see [mediaButtonPreferences]. Built
            // from the defaults here and corrected by [followPersistedSettings] once disk answers.
            .setMediaButtonPreferences(mediaButtonPreferences(this, PlaybackSettings()))
            .apply { sessionActivityIntent()?.let(::setSessionActivity) }
            .build()

        startPositionTicker(sessionPlayer)
        followPersistedSettings(player)
        restoreQueue(player)
    }

    /**
     * Puts the persisted queue back into the player this service has just built.
     *
     * Here rather than in the UI because the service is what every way into playback has in
     * common: the app's own screens, the widget, a headset and the watch all end up at this player,
     * and only the first of them used to restore it. Done through the controller, the restore could
     * also land *after* one of the others had started something and replace it with a paused queue;
     * see [restoreIfEmpty] on why it cannot here.
     *
     * A failed read is reported and otherwise ignored: the player stays empty, which is what it
     * would have been, and [restored] still completes so that nobody waits on a read that failed.
     *
     * @param player the player built in [onCreate].
     */
    private fun restoreQueue(player: ExoPlayer) {
        serviceScope.launch {
            suspendRunCatching { player.restoreIfEmpty(queueSource.resumePoint()) }
                .onFailure { error -> crashReporter.recordNonFatal(NON_FATAL_RESTORE, error) }
            restored.complete(Unit)
        }
    }

    /**
     * Keeps the player and the notification's buttons in step with the user's stored preferences.
     *
     * Deliberately asynchronous. Blocking `onCreate` on a DataStore read is harmless on the happy
     * path and dangerous on the one that matters: the service is most often recreated *under memory
     * pressure*, which is exactly when a cold DataStore read has to go to disk and can reach the ANR
     * window. Every value is settable after construction, so the only consequence of waiting is that
     * the notification's skip buttons use [PlaybackSettings]' defaults for the few milliseconds
     * before the read lands — and nothing can be playing yet at that point.
     *
     * A continuous collection rather than a single read, because the skip interval is now drawn as
     * well as applied: the notification's glyph carries the number, so a user who changes 30 seconds
     * to 15 in Settings while the service is alive would otherwise be left with a button that says
     * one thing and does another until the process next died.
     *
     * The speed is followed apart from the rest, and applied only when the stored speed itself
     * changes. The player's speed is not always the stored one — a show can have a speed of its own,
     * set through the controller when one of its episodes loads — so re-applying it because a skip
     * interval or the auto-play switch changed would put the app's speed back over the show's.
     *
     * @param player the player built in [onCreate].
     */
    private fun followPersistedSettings(player: ExoPlayer) {
        serviceScope.launch {
            userPreferences.playbackSettings
                .map { it.speed }
                .distinctUntilChanged()
                .collect { speed -> player.setPlaybackSpeed(speed) }
        }
        serviceScope.launch {
            userPreferences.playbackSettings.collect { settings ->
                player.setSeekForwardIncrementMs(settings.skipForwardMs)
                player.setSeekBackIncrementMs(settings.skipBackMs)
                mediaSession?.setMediaButtonPreferences(
                    mediaButtonPreferences(this@PlaybackService, settings),
                )
            }
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
        exoPlayer = null
        serviceScope.cancel()
        restored.complete(Unit)
        super.onDestroy()
    }

    /**
     * Persists the current position every [POSITION_SAVE_INTERVAL_MS] while audio is playing.
     *
     * A timer rather than a callback because Media3 publishes no "position changed" event — the
     * position simply advances. Five seconds is the compromise between losing progress to a crash
     * and writing to disk more often than anyone could notice.
     *
     * The absence of that event is also why the chapter-aware player is refreshed from here. Whether
     * a chapter follows the playhead is a fact about the position, so the one loop that already
     * exists because the position moves silently is the honest place to notice it changed.
     *
     * @param player the wrapper, not the [ExoPlayer]: its position is the same and it is the one
     *   with a chapter list to re-derive.
     */
    private fun startPositionTicker(player: ChapterAwarePlayer) {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (!player.isPlaying) continue
                player.positionReading()?.recordInto(progressRecorder)
                player.refreshChapterCommands()
            }
        }
    }

    /**
     * A [PendingIntent] that reopens the app, so tapping the media notification lands on the player
     * rather than on nothing.
     *
     * Resolved through the package manager rather than by naming an activity class, which would
     * force `:core:media` to depend on `:app`.
     *
     * It carries [EXTRA_OPEN_PLAYER], and that is the whole difference between landing *at* the
     * player and landing near it. The tap comes from a card that is already showing the episode,
     * the artwork and the transport controls; arriving at a library list with a collapsed bar at
     * the bottom asks the user to find their way back to what they were just looking at.
     */
    private fun sessionActivityIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                /* requestCode = */ 0,
                launchIntent.putExtra(EXTRA_OPEN_PLAYER, true),
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
         *
         * The two video commands go to this app alone. They edit the playlist, and nothing outside
         * the app has a surface to show a picture on.
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
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            // By uid, which the kernel vouches for, rather than the package name the controller
            // reports about itself.
            if (controller.uid == Process.myUid()) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(SESSION_COMMAND_ENTER_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(SESSION_COMMAND_EXIT_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(SESSION_COMMAND_AWAIT_RESTORE, Bundle.EMPTY))
                        .build(),
                )
            }
            return builder.build()
        }

        /**
         * Handles the two video commands and the wait for the restore; everything else is left to
         * Media3.
         *
         * The wait is the one answer here that is not immediate, since waiting is what it is for.
         *
         * Answered synchronously, on the player's thread, because a swap is three playlist calls
         * and the caller is waiting to attach a surface: an asynchronous answer would only add a
         * hop. The outcome is folded into the result code so the screen can tell "already showing"
         * from "this episode has no picture" without a second round trip.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = exoPlayer
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            val outcome = when (customCommand.customAction) {
                SESSION_COMMAND_ENTER_VIDEO -> {
                    val height = args.getInt(EXTRA_VIDEO_HEIGHT, 0)
                    if (height <= 0) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    player.enterVideoMode(VideoQuality(height), args.getString(EXTRA_VIDEO_EPISODE_ID))
                }

                SESSION_COMMAND_EXIT_VIDEO -> player.exitVideoMode()

                else -> return if (customCommand.customAction == SESSION_COMMAND_AWAIT_RESTORE) {
                    whenRestored()
                } else {
                    super.onCustomCommand(session, controller, customCommand, args)
                }
            }
            val code = when (outcome) {
                VideoModeOutcome.SWAPPED, VideoModeOutcome.UNCHANGED -> SessionResult.RESULT_SUCCESS
                VideoModeOutcome.NOT_YOUTUBE -> SessionError.ERROR_BAD_VALUE
                VideoModeOutcome.NOTHING_LOADED -> SessionError.ERROR_INVALID_STATE
                VideoModeOutcome.SUPERSEDED -> SessionResult.RESULT_INFO_SKIPPED
            }
            return Futures.immediateFuture(SessionResult(code))
        }

        /**
         * Answers [SESSION_COMMAND_AWAIT_RESTORE].
         *
         * @return a future that succeeds once [restoreQueue] has finished; at once if it already has.
         */
        private fun whenRestored(): ListenableFuture<SessionResult> {
            val future = SettableFuture.create<SessionResult>()
            restored.invokeOnCompletion { future.set(SessionResult(SessionResult.RESULT_SUCCESS)) }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val job = serviceScope.launch {
                suspendRunCatching {
                    // An episode whose stored audio URL fails the scheme allowlist is dropped from
                    // the resumed queue rather than handed to the player, and the place in the
                    // queue is kept across the drop.
                    val resumePoint = queueSource.resumePoint().keeping { it.hasPlayableAudio }
                    MediaSession.MediaItemsWithStartPosition(
                        resumePoint.queue.mapNotNull { it.toMediaItemOrNull() },
                        // The episode that was playing, which is not the head of the queue once
                        // anything before it has been played.
                        /* startIndex = */ resumePoint.index,
                        // Resume where the user stopped, not at the top of the episode.
                        /* startPositionMs = */ resumePoint.positionMs,
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

        /** One message for the failure kind, fixed, so each groups into one report. */
        const val NON_FATAL_RESTORE = "Queue restore failed"

        /** How often the position is written while playing. */
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}

/**
 * Boolean extra on the launch intent asking the app to open with the player expanded.
 *
 * Lives here rather than in `:app` because this module is the one that sets it, and `:core:media`
 * cannot depend on the app it is a part of. The app reads it; nothing else does.
 */
const val EXTRA_OPEN_PLAYER: String = "md.borisveriga.megapodcastplayer.extra.OPEN_PLAYER"
