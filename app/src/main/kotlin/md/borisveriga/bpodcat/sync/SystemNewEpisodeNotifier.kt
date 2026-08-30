package md.borisveriga.bpodcat.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.bpodcat.MainActivity
import md.borisveriga.bpodcat.R
import md.borisveriga.bpodcat.core.data.repository.NewEpisode

/**
 * Posts the "new episodes" notification through the platform notification manager.
 *
 * One notification, reused: a refresh that finds three episodes replaces the one the previous
 * refresh posted rather than stacking a second card. The user's mental model is "what is new since
 * I last looked", not "what each refresh run did".
 *
 * @property context application context; used for resources, the notification manager and the
 *   activity the tap opens.
 */
@Singleton
class SystemNewEpisodeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NewEpisodeNotifier {

    private val notificationManager = NotificationManagerCompat.from(context)

    override fun notifyNewEpisodes(newEpisodes: List<NewEpisode>) {
        val content = newEpisodeNotificationContent(newEpisodes) ?: return
        // Posting without the runtime permission is a silent no-op on API 33+, but checking makes
        // that explicit — and it is what lets lint prove the `notify` call below is permitted.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        ensureChannel()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    /**
     * Creates the channel the notification is posted to.
     *
     * Idempotent — the platform ignores a channel that already exists — so it runs on every post
     * rather than needing an "already created" flag. Importance is [IMPORTANCE_DEFAULT] rather than
     * high: a new episode is worth a glance, not an interruption.
     */
    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName(context.getString(R.string.new_episodes_channel_name))
                .setDescription(context.getString(R.string.new_episodes_channel_description))
                .build(),
        )
    }

    /**
     * Renders [content] as a notification.
     *
     * @param content what to say, already decided by [newEpisodeNotificationContent].
     */
    private fun buildNotification(content: NewEpisodeNotificationContent): android.app.Notification {
        val title = context.resources.getQuantityString(
            R.plurals.new_episodes_title,
            content.episodeCount,
            content.episodeCount,
        )
        val lines = content.lines.map { episode ->
            context.getString(
                R.string.new_episodes_line,
                episode.podcastTitle,
                episode.episodeTitle,
            )
        }
        val style = NotificationCompat.InboxStyle().also { style ->
            lines.forEach(style::addLine)
            if (content.overflowCount > 0) {
                style.addLine(
                    context.resources.getQuantityString(
                        R.plurals.new_episodes_more,
                        content.overflowCount,
                        content.overflowCount,
                    ),
                )
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_new_episodes)
            .setContentTitle(title)
            // The collapsed card shows one line; the expanded one shows the rest.
            .setContentText(lines.first())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(contentIntent(content.targetPodcastId))
            .setAutoCancel(true)
            .build()
    }

    /**
     * Builds the tap target.
     *
     * The intent is explicit — no exported deep-link filter — so nothing outside the app can drive
     * this navigation. `CLEAR_TOP | SINGLE_TOP` against [MainActivity]'s `singleTop` launch mode
     * means a running app is brought forward and told about the new intent rather than getting a
     * second copy of itself on the stack.
     *
     * @param podcastId the show to open, or null to open the app where it left off.
     */
    private fun contentIntent(podcastId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (podcastId != null) putExtra(MainActivity.EXTRA_PODCAST_ID, podcastId)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            // UPDATE_CURRENT so a second refresh re-points the tap at whatever it just found;
            // IMMUTABLE because nothing is meant to fill anything in for us.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal companion object {

        /** Channel id; stable, because renaming one strands the user's per-channel settings. */
        const val CHANNEL_ID = "new_episodes"

        /** Fixed, so each refresh replaces the previous card rather than adding to a pile. */
        const val NOTIFICATION_ID = 1001

        /** Fixed too, so [PendingIntent.FLAG_UPDATE_CURRENT] has something to update. */
        private const val REQUEST_CODE = 1001
    }
}
