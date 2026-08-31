package md.borisveriga.bpodcat.core.media

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification

/**
 * The "now playing" notification: the channel it lives on, and the provider that builds it.
 *
 * This notification is not decoration. It is the thing that keeps [PlaybackService] alive: Media3
 * promotes the service to the foreground by posting this notification, and a foreground service is
 * what the platform declines to kill while the screen is off or the app is in the background. If it
 * cannot be posted, playback is a background service on borrowed time.
 *
 * Media3 would build a serviceable notification with no help from us. What this file adds is
 * ownership of the three things the default gets generically wrong:
 *
 *  - an app-owned channel, created eagerly and described, instead of Media3's shared
 *    `default_channel_id` named from its own resources;
 *  - the app's own small icon in the status bar rather than Media3's generic glyph;
 *  - a named notification id, so the collision rule other notifications observe is written down
 *    somewhere both halves can read.
 */

/**
 * Channel the player controls are posted to.
 *
 * Stable, because renaming a channel strands whatever the user configured on the old one.
 */
const val PLAYBACK_NOTIFICATION_CHANNEL_ID: String = "bpodcat_playback"

/**
 * Id of the one playback notification.
 *
 * Kept at [DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID]'s value so that nothing which
 * already routes around it has to move. Notification ids are per-package: anything else the app
 * posts must avoid this one, or it would replace a foreground service's own notification and take
 * the service down with it.
 */
const val PLAYBACK_NOTIFICATION_ID: Int = 1001

/**
 * Creates the playback notification channel, if it does not already exist.
 *
 * Media3 creates this channel lazily, the first time it builds a notification. Doing it up front in
 * `PlaybackService.onCreate` buys two things: the channel is listed in the system's notification
 * settings before the user's first play — so "why is this app allowed to notify me?" has an answer
 * on day one — and it carries a description, which Media3's own call site has no way to set.
 *
 * Idempotent: the platform ignores a channel that already exists, keeping whatever the user changed
 * about it. That also means whichever call runs first wins, which is why this one runs before the
 * provider is installed.
 *
 * [NotificationManagerCompat.IMPORTANCE_LOW] matches what Media3 would have used, and is the right
 * level for a transport control: it belongs in the shade and on the lock screen, silently. A media
 * notification that made a sound every time the episode changed would be unbearable.
 *
 * @param context any context; the notification manager is fetched from the application one.
 */
fun createPlaybackNotificationChannel(context: Context) {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(
            PLAYBACK_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.playback_notification_channel_name))
            .setDescription(context.getString(R.string.playback_notification_channel_description))
            // The controls are a single persistent card, not a count of unread things.
            .setShowBadge(false)
            .build(),
    )
}

/**
 * Builds the provider that renders the player controls.
 *
 * Media3 calls into this on every state change, so it is deliberately a thin configuration of
 * [DefaultMediaNotificationProvider] rather than a hand-built notification: the default already
 * handles the parts that are easy to get wrong and hard to notice — `MediaStyle` compact-view
 * indices, artwork loading, the `FOREGROUND_SERVICE_IMMEDIATE` behaviour that stops the platform
 * delaying the notification by ten seconds, and the button set the lock screen and Android Auto
 * expect.
 *
 * @param context the service; used for resources and for the channel.
 * @return the provider to hand to `MediaSessionService.setMediaNotificationProvider`.
 */
@UnstableApi
fun playbackNotificationProvider(context: Context): MediaNotification.Provider =
    DefaultMediaNotificationProvider.Builder(context)
        .setChannelId(PLAYBACK_NOTIFICATION_CHANNEL_ID)
        .setChannelName(R.string.playback_notification_channel_name)
        .setNotificationId(PLAYBACK_NOTIFICATION_ID)
        .build()
        .apply { setSmallIcon(R.drawable.ic_notification_playback) }
