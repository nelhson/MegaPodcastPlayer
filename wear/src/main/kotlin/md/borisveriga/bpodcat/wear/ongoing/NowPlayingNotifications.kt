package md.borisveriga.bpodcat.wear.ongoing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.wear.MainActivity
import md.borisveriga.bpodcat.wear.R

/** Channel the watch-face chip's backing notification lives on. */
internal const val ONGOING_CHANNEL_ID = "bpodcat_now_playing"

/** The chip's notification id. Fixed: there is only ever one, and it is replaced, never stacked. */
internal const val ONGOING_NOTIFICATION_ID = 1

/**
 * Whether the watch face should be showing a playback chip.
 *
 * A chip is a claim that audio is coming out right now, so it needs both: a phone that is actually
 * playing, and an episode for the chip to name. A paused phone gets nothing — the user can raise
 * their wrist and open the app — because a chip that persists through a pause is indistinguishable
 * from one that is simply stale.
 *
 * Kept as a free function so the decision can be tested without a NotificationManager.
 *
 * @param snapshot the phone's last published state, or null when it has said nothing or the watch
 *   could not read what it said.
 */
internal fun shouldShowChip(snapshot: NowPlayingSnapshot?): Boolean =
    snapshot != null && snapshot.isPlaying && !snapshot.isIdle

/**
 * Posts, updates and clears the watch-face playback chip.
 *
 * The chip is an [OngoingActivity]: on Wear, that is what promotes an ordinary ongoing notification
 * into something the watch face itself surfaces, so playback stays one tap away without the app
 * being open.
 *
 * @property context used to build the notification and reach the notification manager.
 */
internal class NowPlayingNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Brings the chip into line with what the phone last said.
     *
     * @param snapshot the phone's state, or null if unknown.
     */
    fun update(snapshot: NowPlayingSnapshot?) {
        if (!shouldShowChip(snapshot) || snapshot == null) {
            clear()
            return
        }
        show(snapshot)
    }

    /** Removes the chip. Safe to call when there is none. */
    fun clear() {
        manager.cancel(ONGOING_NOTIFICATION_ID)
    }

    /**
     * Posts or replaces the chip for a playing episode.
     *
     * A missing notification permission is not an error to recover from: the user declined, the chip
     * simply does not appear, and every other part of the watch app is unaffected.
     */
    private fun show(snapshot: NowPlayingSnapshot) {
        createChannel()

        val builder = NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ongoing_playing)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.showTitle.ifBlank { context.getString(R.string.watch_ongoing_status) })
            .setContentIntent(touchIntent())
            // Ongoing and non-dismissable: this mirrors playback happening elsewhere, so swiping it
            // away on the watch would not stop anything and would only desynchronise the two.
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSilent(true)

        OngoingActivity.Builder(context, ONGOING_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_ongoing_playing)
            .setTouchIntent(touchIntent())
            .setStatus(Status.forPart(Status.TextPart(snapshot.title)))
            .build()
            .apply(context)

        if (manager.areNotificationsEnabled()) {
            manager.notify(ONGOING_NOTIFICATION_ID, builder.build())
        }
    }

    /**
     * Opens the watch app.
     *
     * Carries no intent flags on purpose. `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` is the
     * reflex here and is wrong on Wear — it breaks how the system's recents treats the app, which is
     * what lint's `WearRecents` check is about. A `PendingIntent` starts the activity through the
     * system anyway, and the activity's empty `taskAffinity` in the manifest already keeps the watch
     * app to one task.
     */
    private fun touchIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Creates the channel if it is not already there; the platform ignores a repeat call. */
    private fun createChannel() {
        val channel = NotificationChannel(
            ONGOING_CHANNEL_ID,
            context.getString(R.string.watch_ongoing_channel_name),
            // Low: the chip is a place to look, never an interruption. The phone already made
            // whatever noise this episode was going to make.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.watch_ongoing_channel_description)
            setShowBadge(false)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
