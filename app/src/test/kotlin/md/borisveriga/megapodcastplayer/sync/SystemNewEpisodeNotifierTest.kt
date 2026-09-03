package md.borisveriga.megapodcastplayer.sync

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import md.borisveriga.megapodcastplayer.core.data.repository.NewEpisode
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
 * Tests for the platform side of the new-episode notification.
 *
 * The permission guard is the reason this test exists: on API 33+ a missing `POST_NOTIFICATIONS`
 * makes `notify` a silent no-op, and silence is exactly the failure mode a test has to pin down.
 */
@RunWith(RobolectricTestRunner::class)
class SystemNewEpisodeNotifierTest {

    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: SystemNewEpisodeNotifier

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notificationManager =
            application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifier = SystemNewEpisodeNotifier(application)
    }

    private fun grantNotificationPermission() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun newEpisode(id: String, podcastId: String = "pod-1") = NewEpisode(
        episodeId = id,
        episodeTitle = "Episode $id",
        podcastId = podcastId,
        podcastTitle = "Show $podcastId",
    )

    private fun postedNotifications() = shadowOf(notificationManager).allNotifications

    private companion object {

        /**
         * Media3's `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID`, which
         * `PlaybackService` leaves at its default. Duplicated as a literal rather than referenced,
         * because the point is to notice if Media3 ever moves it.
         */
        const val MEDIA3_PLAYBACK_NOTIFICATION_ID = 1001

        /** `EpisodeDownloadService.FOREGROUND_NOTIFICATION_ID`, duplicated for the same reason. */
        const val DOWNLOAD_FOREGROUND_NOTIFICATION_ID = 2
    }

    @Test
    fun `posts nothing without the runtime permission`() {
        notifier.notifyNewEpisodes(listOf(newEpisode("a")))

        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `posts nothing when the refresh found nothing`() {
        grantNotificationPermission()

        notifier.notifyNewEpisodes(emptyList())

        assertTrue(postedNotifications().isEmpty())
    }

    @Test
    fun `posts one notification naming the show and the episode`() {
        grantNotificationPermission()

        notifier.notifyNewEpisodes(listOf(newEpisode("a")))

        val notification = postedNotifications().single()
        assertEquals(
            "1 new episode",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString(),
        )
        assertEquals(
            "Show pod-1: Episode a",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
        )
        assertNotNull("Tapping it has to open the app", notification.contentIntent)
    }

    @Test
    fun `a second refresh replaces the first notification rather than stacking one`() {
        grantNotificationPermission()

        notifier.notifyNewEpisodes(listOf(newEpisode("a")))
        notifier.notifyNewEpisodes(listOf(newEpisode("b"), newEpisode("c")))

        val notification = postedNotifications().single()
        assertEquals(
            "2 new episodes",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun `does not reuse a notification id another part of the app already posts`() {
        // Found on a device, not in a test: Media3's playback notification sits on 1001 and the
        // download service's on 2, both belonging to foreground services. Posting over either
        // would replace a running service's own card.
        assertNotEquals(
            "Media3's DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID",
            MEDIA3_PLAYBACK_NOTIFICATION_ID,
            SystemNewEpisodeNotifier.NOTIFICATION_ID,
        )
        assertNotEquals(
            "EpisodeDownloadService.FOREGROUND_NOTIFICATION_ID",
            DOWNLOAD_FOREGROUND_NOTIFICATION_ID,
            SystemNewEpisodeNotifier.NOTIFICATION_ID,
        )
    }

    @Test
    fun `creates the channel it posts to`() {
        grantNotificationPermission()

        notifier.notifyNewEpisodes(listOf(newEpisode("a")))

        val channel = notificationManager
            .getNotificationChannel(SystemNewEpisodeNotifier.CHANNEL_ID)
        assertNotNull("A notification posted to a channel that does not exist is dropped", channel)
    }
}
