package md.borisveriga.bpodcat.wear.playback

import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.wear.data.PositionReporter
import md.borisveriga.bpodcat.wear.data.WatchEpisodeStore

/**
 * Plays the episodes the watch holds, with no phone involved.
 *
 * A [MediaSessionService] rather than a player owned by the screen, for the reason the phone's own
 * player is one: the audio has to keep coming with the screen off and the app closed, and the
 * system's own transport controls — the ones on a Bluetooth headset's buttons — have to reach it.
 *
 * It also owns saving the position, because it is the component that outlives the UI: an episode
 * paused with the wrist down and the app long gone still has to be where the wearer left it, on both
 * devices. See [PositionReporter] for the half of that which crosses back to the phone.
 *
 * @property store where the audio and the positions live.
 * @property reporter tells the phone what was played here, when it can be reached.
 * @property appScope where a position is written down. Deliberately not this service's own scope:
 *   the last save of all happens as the service is destroyed, and a coroutine launched on a scope
 *   that is about to be cancelled is a coroutine that never runs.
 */
@AndroidEntryPoint
class WatchPlaybackService : MediaSessionService() {

    @Inject
    lateinit var store: WatchEpisodeStore

    @Inject
    lateinit var reporter: PositionReporter

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    private var session: MediaSession? = null

    /** Runs the save ticker, and stops with the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Runs while something is playing, writing the position down as it goes. */
    private var ticker: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // Speech, not music: this is what tells the system to duck rather than pause
                    // for a notification, and what a headset's voice profile keys off.
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // Handle audio focus: another app taking the output must pause this, not talk over
                // it.
                true,
            )
            // Unplugging headphones pauses, rather than starting to play out loud on a wrist.
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener(player))
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Stops the service when the app is swiped away and nothing is playing.
     *
     * Without this a paused session lingers as an empty notification the wearer cannot get rid of.
     * A *playing* session deliberately survives: swiping the app away on a run must not stop the
     * audio.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        session?.let { session ->
            savePosition(session.player, finished = false)
            session.player.release()
            session.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Watches the player for the moments a position is worth writing down.
     *
     * Three of them: playback stopping, an episode ending, and every few seconds while it runs. The
     * last one is what covers the case none of the others do — a watch whose battery dies mid-run.
     *
     * @param player the player to watch.
     */
    private fun playerListener(player: Player) = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicking(player)
            } else {
                ticker?.cancel()
                savePosition(player, finished = false)
                report(player)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                ticker?.cancel()
                savePosition(player, finished = true)
                report(player)
            }
        }
    }

    /** Writes the position down every [SAVE_INTERVAL_MS] for as long as audio is coming out. */
    private fun startTicking(player: Player) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                savePosition(player, finished = false)
            }
        }
    }

    /**
     * Records where the loaded episode has got to.
     *
     * @param player the player to read; its current media item's id is the episode's.
     * @param finished true when it reached the end, which is what marks it played.
     */
    private fun savePosition(player: Player, finished: Boolean) {
        val episodeId = player.currentMediaItem?.mediaId ?: return
        val positionMs = player.currentPosition

        appScope.launch {
            store.setPosition(episodeId, positionMs = positionMs, isPlayed = finished)
        }
    }

    /**
     * Tells the phone what was played here, if it can be reached.
     *
     * Failure is not handled, because it is not a failure: the position is already on disk marked
     * unreported, and [PositionReporter] carries it over the next time the phone is in range.
     *
     * @param player the player whose episode was played.
     */
    private fun report(player: Player) {
        val episodeId = player.currentMediaItem?.mediaId ?: return
        appScope.launch { reporter.report(episodeId) }
    }

    /**
     * Who may bind the session.
     *
     * The service is `exported="true"` because Media3 requires it, and the default callback accepts
     * everyone — so without this any app on the watch could connect, read the episode titles out of
     * the metadata and drive playback. The phone's player makes the same decision for the same
     * reason; the watch's list of trusted packages is shorter, because there is no car head unit or
     * assistant integration on a wrist to accommodate.
     */
    @OptIn(UnstableApi::class)
    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val trusted = controller.uid == Process.myUid() ||
                controller.uid == Process.SYSTEM_UID ||
                controller.packageName == packageName

            if (!trusted) {
                Log.i(TAG, "Refused a media session connection from ${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }
    }

    private companion object {
        const val TAG = "WatchPlaybackService"

        /**
         * How often a position is written down while playing.
         *
         * Ten seconds is the most listening a dead battery can cost, and one small write per ten
         * seconds is nothing beside decoding audio.
         */
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
