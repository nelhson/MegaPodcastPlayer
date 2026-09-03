package md.borisveriga.megapodcastplayer.core.media

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests the channel and provider behind the "now playing" notification.
 *
 * The notification is what keeps [PlaybackService] in the foreground, so the things asserted here
 * are the ones whose failure is invisible until playback dies in the background: a channel that
 * never got created, an importance that would make the controls chime, or an id that collides with
 * another notification and evicts the foreground service's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackNotificationTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `creates the playback channel`() {
        createPlaybackNotificationChannel(application)

        val channel = NotificationManagerCompat.from(application)
            .getNotificationChannelCompat(PLAYBACK_NOTIFICATION_CHANNEL_ID)

        assertNotNull("The playback notification channel was not created", channel)
        assertEquals(
            application.getString(R.string.playback_notification_channel_name),
            channel!!.name,
        )
        assertEquals(
            application.getString(R.string.playback_notification_channel_description),
            channel.description,
        )
    }

    @Test
    fun `posts the controls silently`() {
        createPlaybackNotificationChannel(application)

        val channel = requireNotNull(
            NotificationManagerCompat.from(application)
                .getNotificationChannelCompat(PLAYBACK_NOTIFICATION_CHANNEL_ID),
        )

        // Anything above LOW makes a sound. Transport controls that chimed on every episode
        // change would be the first thing a user turned off — taking the controls with them.
        assertEquals(NotificationManagerCompat.IMPORTANCE_LOW, channel.importance)
        assertFalse("The player controls are one card, not a count", channel.canShowBadge())
    }

    @Test
    fun `creating the channel twice is harmless`() {
        createPlaybackNotificationChannel(application)
        createPlaybackNotificationChannel(application)

        // Called once per service creation, and the service is created more than once per install.
        assertNotNull(
            NotificationManagerCompat.from(application)
                .getNotificationChannelCompat(PLAYBACK_NOTIFICATION_CHANNEL_ID),
        )
    }

    @Test
    fun `keeps the notification id Media3 has always used`() {
        // Moving it would strand every other notification's collision rule, which is written
        // against this number rather than against the constant.
        assertEquals(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            PLAYBACK_NOTIFICATION_ID,
        )
    }

    @Test
    fun `does not collide with the download notification`() {
        // Reusing the download service's id would have each foreground service cancel the other's
        // notification, and a foreground service without its notification is force-stopped.
        assertNotEquals(2, PLAYBACK_NOTIFICATION_ID)
    }

    @Test
    fun `builds a provider`() {
        // A smoke test with teeth: the builder resolves the channel name and the small icon out of
        // this module's resources, so a renamed or deleted resource fails here rather than at the
        // first play on a device.
        assertNotNull(playbackNotificationProvider(application))
    }
}
