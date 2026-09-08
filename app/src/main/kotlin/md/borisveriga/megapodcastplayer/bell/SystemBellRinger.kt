package md.borisveriga.megapodcastplayer.bell

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.MainActivity
import md.borisveriga.megapodcastplayer.R
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.media.BellRinger
import md.borisveriga.megapodcastplayer.wearsync.WatchBellSender

/**
 * Rings the end-of-episode bell on the phone, and asks the watch to buzz.
 *
 * Lives in `:app` rather than `:core:media` because it needs the launcher activity for its tap
 * target and the Data Layer for the watch; the player service reaches it through [BellRinger].
 *
 * Both halves are attempted independently and neither can throw. This is called from a player
 * callback on a service that is very probably the only thing running, and a bell that failed to
 * ring must not take playback down with it — but a bell that silently failed to ring is exactly the
 * invisible failure worth recording, so both go to [crashReporter].
 *
 * @property context application context; used for resources, the notification manager and the
 *   activity the tap opens.
 * @property watchBellSender the other half of the ring.
 * @property crashReporter where a bell that did not ring goes.
 */
@Singleton
class SystemBellRinger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchBellSender: WatchBellSender,
    private val crashReporter: CrashReporter,
) : BellRinger {

    private val notificationManager = NotificationManagerCompat.from(context)

    override suspend fun ring(episodeTitle: String?) {
        suspendRunCatching { postNotification(episodeTitle) }.onFailure { failure ->
            crashReporter.recordNonFatal("Could not post the end-of-episode bell", failure)
        }
        // Not inside the same `runCatching`: a phone that could not post its notification is
        // exactly the phone whose wearer most needs the wrist to buzz.
        watchBellSender.buzz()
    }

    /**
     * Posts the notification that is the bell itself.
     *
     * @param episodeTitle what finished, or null when the player had no title for it.
     */
    private fun postNotification(episodeTitle: String?) {
        // Posting without the runtime permission is a silent no-op on API 33+, but checking makes
        // that explicit — and it is what lets lint prove the `notify` call below is permitted.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        ensureChannel()
        val content = bellNotificationContent(episodeTitle)
        val text = content.episodeTitle
            ?.let { title -> context.getString(content.textRes, title) }
            ?: context.getString(content.textRes)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle(context.getString(content.titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // CATEGORY_ALARM is what tells the platform this is time-critical, which is how it
            // avoids some of the ways an ordinary notification gets deferred or bundled away.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Creates the channel the bell is posted to.
     *
     * Idempotent — the platform ignores a channel that already exists — so it runs on every ring
     * rather than needing an "already created" flag.
     *
     * The sound is the device's *alarm* ringtone played with [AudioAttributes.USAGE_ALARM], which
     * is what makes it come out at alarm volume rather than notification volume: a phone turned
     * down for the night still has its alarm stream up, and waking the user is the entire point. It
     * is still a notification and not a true alarm, so Do Not Disturb can silence it unless the
     * user gives this channel an override.
     *
     * A channel's sound and importance are immutable once it exists, so changing either later means
     * a new [CHANNEL_ID] rather than an edit here.
     */
    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.bell_channel_name))
                .setDescription(context.getString(R.string.bell_channel_description))
                .setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setVibrationEnabled(true)
                .build(),
        )
    }

    /**
     * Builds the tap target: the app, wherever it left off, which is the player.
     *
     * Explicit, with no exported deep-link filter, so nothing outside the app can drive it.
     * `CLEAR_TOP or SINGLE_TOP` against [MainActivity]'s `singleTop` launch mode brings a running
     * app forward rather than stacking a second copy of itself.
     */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal companion object {

        /** Channel id; stable, because renaming one strands the user's per-channel settings. */
        const val CHANNEL_ID = "episode_bell"

        /**
         * Fixed, so a second bell replaces the first rather than adding to a pile.
         *
         * Notification ids are per-package, and the app already posts three: the player controls
         * are **1001**, `EpisodeDownloadService`'s foreground notification is **2**, and the
         * new-episode card is **3**. Reusing one would replace a foreground service's own
         * notification, and for the player that would take the service down with it — stopping the
         * playback this bell has just stopped deliberately, by a route nobody could debug.
         */
        const val NOTIFICATION_ID = 4

        /**
         * Request code for the tap intent, fixed so [PendingIntent.FLAG_UPDATE_CURRENT] has
         * something to update. Request codes live in their own namespace.
         */
        private const val REQUEST_CODE = 1002
    }
}
