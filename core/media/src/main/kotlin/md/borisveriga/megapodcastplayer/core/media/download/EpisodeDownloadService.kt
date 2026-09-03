package md.borisveriga.megapodcastplayer.core.media.download

import android.app.Notification
import android.app.PendingIntent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import md.borisveriga.megapodcastplayer.core.media.R

/**
 * The foreground service that runs episode downloads.
 *
 * Media3 starts and stops this itself in response to the intents [EpisodeDownloader] sends; nothing
 * in the app binds to it. Its whole job is to hand Media3 three things: the [DownloadManager] to
 * drive, a [Scheduler] that can restart work after a reboot, and a notification to show while it
 * holds the foreground.
 *
 * Hilt injects the manager rather than the service building one, because the same instance has to
 * be shared with [EpisodeDownloader] — two [DownloadManager]s over one cache would fight over the
 * index. `@AndroidEntryPoint` injects before `super.onCreate()`, which is what makes the field safe
 * to read from [getDownloadManager].
 */
@AndroidEntryPoint
class EpisodeDownloadService : DownloadService(
    /* foregroundNotificationId = */ FOREGROUND_NOTIFICATION_ID,
    /* foregroundNotificationUpdateInterval = */ DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    /* channelId = */ DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    /* channelNameResourceId = */ R.string.download_notification_channel_name,
    /* channelDescriptionResourceId = */ R.string.download_notification_channel_description,
) {

    /**
     * The one download manager, shared with [EpisodeDownloader].
     *
     * Named to avoid the property's generated `getDownloadManager()` colliding with the abstract
     * method of the same name that Media3 requires this class to override.
     */
    @Inject
    lateinit var injectedDownloadManager: DownloadManager

    /** Built lazily: the channel it names does not exist until `super.onCreate()` has run. */
    private val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager = injectedDownloadManager

    /**
     * Re-runs pending downloads after a reboot, or once an unmet requirement is met again.
     *
     * [PlatformScheduler] is a JobScheduler wrapper, so the OS — not the app — remembers that work
     * is outstanding. That is the only way a download interrupted by a dead battery resumes.
     */
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    /**
     * The notification shown while downloads run.
     *
     * @param downloads the downloads currently in progress.
     * @param notMetRequirements requirement flags that are currently unmet, which Media3 renders as
     *   "waiting for Wi-Fi" rather than as stalled progress.
     */
    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        /* context = */ this,
        /* smallIcon = */ android.R.drawable.stat_sys_download,
        /* contentIntent = */ launchAppIntent(),
        /* message = */ getString(R.string.downloading_episodes),
        /* downloads = */ downloads,
        /* notMetRequirements = */ notMetRequirements,
    )

    /**
     * A [PendingIntent] that reopens the app, so tapping the download notification goes somewhere.
     *
     * Resolved through the package manager rather than by naming an activity, which would force
     * `:core:media` to depend on `:app`.
     */
    private fun launchAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                /* requestCode = */ 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private companion object {
        /** Distinct from the playback notification's id; the two coexist while downloading. */
        const val FOREGROUND_NOTIFICATION_ID = 2

        /** JobScheduler id for the reboot/requirements scheduler. */
        const val JOB_ID = 1
    }
}

/** Channel the download progress notification is posted to. */
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "megapodcastplayer_downloads"
