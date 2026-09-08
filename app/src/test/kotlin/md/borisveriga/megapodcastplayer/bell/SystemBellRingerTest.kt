package md.borisveriga.megapodcastplayer.bell

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.NoOpCrashReporter
import md.borisveriga.megapodcastplayer.wearsync.WatchBellSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests for the platform side of the end-of-episode bell.
 *
 * Two things here are worth a test rather than a reading of the code. The permission guard, because
 * without `POST_NOTIFICATIONS` the platform makes `notify` a silent no-op and silence is the exact
 * failure mode a bell must not have quietly. And the notification id, because the app posts three
 * others and reusing the player's would take the playback service down with it.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBellRingerTest {

    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager
    private lateinit var watchBellSender: WatchBellSender
    private lateinit var ringer: SystemBellRinger

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notificationManager =
            application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        watchBellSender = mockk(relaxed = true)
        ringer = SystemBellRinger(application, watchBellSender, NoOpCrashReporter)
    }

    private fun grantNotificationPermission() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun postedNotifications() = shadowOf(notificationManager).allNotifications

    @Test
    fun `posts nothing without the runtime permission`() = runTest {
        ringer.ring("The one about ovens")

        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `the watch is buzzed even when the phone cannot post`() = runTest {
        // The half that reaches a wrist under a duvet is exactly the half that matters most when
        // the phone has been told to stay quiet.
        ringer.ring("The one about ovens")

        coVerify(exactly = 1) { watchBellSender.buzz() }
    }

    @Test
    fun `rings on the phone and the watch`() = runTest {
        grantNotificationPermission()

        ringer.ring("The one about ovens")

        assertEquals(1, postedNotifications().size)
        coVerify(exactly = 1) { watchBellSender.buzz() }
    }

    @Test
    fun `the notification names the episode and says playback stopped`() = runTest {
        grantNotificationPermission()

        ringer.ring("The one about ovens")

        val text = postedNotifications()
            .single()
            .extras
            .getCharSequence(NotificationCompat.EXTRA_TEXT)
            ?.toString()
        assertNotNull(text)
        assertTrue(text!!.contains("The one about ovens"))
        assertTrue(text.contains("stopped"))
    }

    @Test
    fun `an episode with no title still gets a sensible card`() = runTest {
        grantNotificationPermission()

        ringer.ring(null)

        val text = postedNotifications()
            .single()
            .extras
            .getCharSequence(NotificationCompat.EXTRA_TEXT)
            ?.toString()
        assertEquals(
            application.getString(md.borisveriga.megapodcastplayer.R.string.bell_text_unknown_episode),
            text,
        )
    }

    @Test
    fun `the channel is created before the first post and survives a second`() = runTest {
        grantNotificationPermission()

        ringer.ring("a")
        ringer.ring("b")

        val channel = shadowOf(notificationManager)
            .notificationChannels
            .single { it.id == SystemBellRinger.CHANNEL_ID }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        // The whole point of the alarm stream: a phone turned down for the night still has it up.
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, channel.audioAttributes?.usage)
        assertNotNull(channel.sound)
    }

    @Test
    fun `a second bell replaces the first rather than stacking`() = runTest {
        grantNotificationPermission()

        ringer.ring("a")
        ringer.ring("b")

        assertEquals(1, postedNotifications().size)
    }

    @Test
    fun `the notification id avoids the three the app already posts`() {
        // Not a tautology: reusing the player's id would replace a foreground service's own
        // notification and stop playback, by a route that looks nothing like its cause.
        assertNotEquals(MEDIA3_PLAYBACK_NOTIFICATION_ID, SystemBellRinger.NOTIFICATION_ID)
        assertNotEquals(DOWNLOAD_FOREGROUND_NOTIFICATION_ID, SystemBellRinger.NOTIFICATION_ID)
        assertNotEquals(NEW_EPISODES_NOTIFICATION_ID, SystemBellRinger.NOTIFICATION_ID)
    }

    private companion object {

        /**
         * Media3's `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID`, which
         * `PlaybackService` leaves at its default. Duplicated as a literal rather than referenced,
         * because the point is to notice if Media3 ever moves it.
         */
        const val MEDIA3_PLAYBACK_NOTIFICATION_ID = 1001

        /** `EpisodeDownloadService.FOREGROUND_NOTIFICATION_ID`, duplicated for the same reason. */
        const val DOWNLOAD_FOREGROUND_NOTIFICATION_ID = 2

        /** `SystemNewEpisodeNotifier.NOTIFICATION_ID`, likewise. */
        const val NEW_EPISODES_NOTIFICATION_ID = 3
    }
}
