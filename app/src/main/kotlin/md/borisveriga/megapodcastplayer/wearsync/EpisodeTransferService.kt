package md.borisveriga.megapodcastplayer.wearsync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.R

/**
 * Keeps the phone at foreground priority while it copies episodes to the watch.
 *
 * This service does no work of its own: [EpisodeAudioSender] still owns the copy, and this exists
 * only so that the process running it is not a cached one. See [ForegroundEpisodeAudioTransfers] for
 * why that distinction decides how long a transfer takes.
 *
 * **One service, any number of transfers.** Each start adds an episode and the service stops when
 * the last of them finishes, rather than one service per episode: they share a single Bluetooth
 * link, and a second notification would describe the same wait twice.
 *
 * **It does not restart itself.** [START_NOT_STICKY], and no attempt to resume after the process
 * dies: the watch discards a partial file, and it asks again the next time it is opened. Re-opening
 * a channel to a watch that may have moved out of range, for an episode nobody is waiting for any
 * more, would be worse than doing nothing.
 */
@AndroidEntryPoint
class EpisodeTransferService : Service() {

    @Inject
    internal lateinit var sender: EpisodeAudioSender

    /**
     * Where the transfers are awaited.
     *
     * The copy itself runs on the application scope inside [EpisodeAudioSender] — it must survive
     * this service being destroyed unexpectedly — so these coroutines only wait for it and then
     * decide whether to stop.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [active] and [lastStartId], which are read and written from [scope] and the main thread. */
    private val lock = Any()

    /** How many transfers this service is still waiting on. */
    private var active = 0

    /**
     * The most recent start id.
     *
     * `stopSelf(startId)` is a no-op when a newer start has arrived, which is what stops a transfer
     * that began while the previous one was finishing from being cut off. Taking the newest id under
     * [lock], with [active] at zero, means there is genuinely nothing left to cut off.
     */
    private var lastStartId = 0

    /** Nothing binds to this service; it is started and left to finish. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, and before anything that can return early: the platform gives a service started
        // with startForegroundService a few seconds to say so, and kills it for missing the window.
        goToForeground()

        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        val episodeId = intent?.getStringExtra(EXTRA_EPISODE_ID)
        if (nodeId == null || episodeId == null) {
            Log.w(TAG, "Started without an episode to send")
            synchronized(lock) {
                lastStartId = startId
                if (active == 0) stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        synchronized(lock) {
            lastStartId = startId
            active++
        }

        scope.launch {
            try {
                // Null means that episode is already on its way, in which case there is nothing to
                // wait for here — the transfer that owns it keeps the service up.
                sender.send(nodeId, episodeId)?.join()
            } finally {
                finished()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Only the waiting is cancelled. The transfers themselves are on the application scope, so a
        // service killed for any reason does not take a half-sent episode down with it.
        scope.cancel()
        super.onDestroy()
    }

    /** Records one transfer as done and stops the service if it was the last. */
    private fun finished() = synchronized(lock) {
        active--
        if (active <= 0) {
            active = 0
            stopSelf(lastStartId)
        }
    }

    /**
     * Posts the ongoing notification and enters the foreground.
     *
     * A refused promotion is logged rather than thrown: the service is already running at that
     * point, and a plain started service is still better than the cached process this exists to
     * avoid. The copy carries on either way.
     */
    private fun goToForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not enter the foreground; the transfer continues without it", error)
        }
    }

    /**
     * The notification that makes this service a foreground one.
     *
     * Deliberately without progress. The phone cannot see how much of the stream the watch has
     * accepted — only what it has handed to the Bluetooth stack, which runs minutes ahead of the
     * truth — and the watch already draws the honest bar next to the episode it is receiving.
     *
     * The default deferred behaviour is kept, so a transfer that finishes inside ten seconds never
     * shows a card at all.
     */
    private fun buildNotification(): Notification {
        val manager = NotificationManagerCompat.from(this)
        // Idempotent; the platform ignores a channel it already has.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.watch_transfer_channel_name))
                .setDescription(getString(R.string.watch_transfer_channel_description))
                .build(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_watch_transfer)
            .setContentTitle(getString(R.string.watch_transfer_title))
            .setContentText(getString(R.string.watch_transfer_text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            // Nothing to open: the thing being reported on is happening on the other wrist, and the
            // phone's own screens say nothing about it.
            .setShowWhen(false)
            .build()
    }

    internal companion object {

        private const val TAG = "EpisodeTransferService"

        /** Channel id; stable, because renaming one strands the user's per-channel settings. */
        const val CHANNEL_ID = "watch_transfer"

        /**
         * Fixed, because one service covers every transfer.
         *
         * Notification ids are per-package, so this avoids the three the app already posts: 2 is
         * `EpisodeDownloadService`, 3 is `SystemNewEpisodeNotifier`, and 1001 is the player's
         * controls in `:core:media`. Reusing a foreground service's id would replace its
         * notification and take that service down with it.
         */
        const val NOTIFICATION_ID = 4

        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_EPISODE_ID = "episode_id"

        /**
         * The intent that starts one transfer.
         *
         * Explicit, and the service is unexported: nothing outside the app may ask this phone to
         * open a channel to a watch.
         *
         * @param context used only to name the component.
         * @param nodeId the watch that asked.
         * @param episodeId the episode it asked for.
         */
        fun intent(context: Context, nodeId: String, episodeId: String): Intent =
            Intent(context, EpisodeTransferService::class.java).apply {
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
            }
    }
}
