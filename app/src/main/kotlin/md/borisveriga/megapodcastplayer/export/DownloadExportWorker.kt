package md.borisveriga.megapodcastplayer.export

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import md.borisveriga.megapodcastplayer.R
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.export.EpisodeAudioExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportStage
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter

/**
 * Downloads one show's on-screen episodes, then exports them into the folder the user named.
 *
 * A worker rather than a coroutine in the show's view model, because a playlist's worth of audio is
 * hundreds of megabytes, downloaded and then copied: long enough that the user will leave the app,
 * and long enough that the system would otherwise stop the process while they do. It runs in the
 * foreground for the same reason, with a progress notification that says what it is doing.
 *
 * The downloading itself is Media3's download service; this only waits for it. When the system
 * stops a run part way (a foreground time limit, say), WorkManager runs it again and nothing is
 * repeated: finished downloads are not asked for twice and copied files are recognised.
 *
 * It is handed the picked folder's URI, which a restore is not given: a folder the app holds a
 * *persistable* grant on is still writable whenever this runs, where a picked document is not. The
 * scheduler takes that grant before enqueueing.
 *
 * @property exporter does the copying.
 * @property crashReporter told when the foreground notification cannot be shown. The export still
 *   runs; it is only less visible, and more likely to be stopped.
 */
@HiltWorker
class DownloadExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val exporter: EpisodeAudioExporter,
    private val crashReporter: CrashReporter,
) : CoroutineWorker(appContext, workerParameters) {

    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    /**
     * Set once the platform has refused the foreground.
     *
     * The refusal does not change for the rest of the run, and trying again after every episode
     * would send one crash report per episode for a single cause.
     */
    private var foregroundRefused = false

    override suspend fun doWork(): Result {
        val request = readRequest() ?: return Result.failure()

        ensureChannel()
        showProgress(ExportProgress(done = 0, total = 0))

        val result = exporter.export(
            podcastId = request.podcastId,
            treeUri = request.treeUri,
            folderName = request.folderName,
            filter = request.filter,
        ) { progress ->
            setProgress(
                workDataOf(
                    KEY_DONE to progress.done,
                    KEY_TOTAL to progress.total,
                    KEY_STAGE to progress.stage.name,
                ),
            )
            showProgress(progress)
        }

        return result.fold(
            onSuccess = { summary ->
                notifyFinished(summary)
                Result.success(summary.asOutputData())
            },
            // Already recorded by the exporter. Said here as well as on the screen, because the
            // user may have left it, and a failed run would otherwise end in silence.
            onFailure = {
                notifyFailed()
                Result.failure()
            },
        )
    }

    /**
     * Reads what the scheduler handed over.
     *
     * @return the run's inputs, or null when any is missing or the filter is not one this build
     *   knows, which fails the run without exporting.
     */
    private fun readRequest(): ExportRequest? {
        val podcastId = inputData.getString(KEY_PODCAST_ID)
        val treeUri = inputData.getString(KEY_TREE_URI)
        val folderName = inputData.getString(KEY_FOLDER_NAME)
        val filterName = inputData.getString(KEY_FILTER)
        val filter = EpisodeFilter.entries.firstOrNull { it.name == filterName }
        val hasLocation = podcastId != null && treeUri != null && folderName != null
        return if (hasLocation && filter != null) {
            ExportRequest(podcastId, treeUri, folderName, filter)
        } else {
            null
        }
    }

    /**
     * One run's inputs, read and checked.
     *
     * @property podcastId the show.
     * @property treeUri the picked location.
     * @property folderName the name the user gave the folder.
     * @property filter the show page's filter, which decides the episodes.
     */
    private data class ExportRequest(
        val podcastId: String,
        val treeUri: String,
        val folderName: String,
        val filter: EpisodeFilter,
    )

    /**
     * Puts the run in the foreground, or updates its notification.
     *
     * A foreground start can be refused (the platform refuses one from the background) and that is
     * not a reason to abandon a copy that can still finish, so it is recorded and the work goes on.
     */
    private suspend fun showProgress(progress: ExportProgress) {
        if (foregroundRefused) return
        suspendRunCatching { setForeground(foregroundInfo(progressNotification(progress))) }
            .onFailure { failure ->
                foregroundRefused = true
                crashReporter.recordNonFatal("Episode export could not enter the foreground", failure)
            }
    }

    /** Wraps a notification for [setForeground]; `dataSync` is what copying files is. */
    private fun foregroundInfo(notification: Notification): ForegroundInfo = ForegroundInfo(
        PROGRESS_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    /**
     * The ongoing notification.
     *
     * Indeterminate until the exporter has counted the episodes, so it never shows "0 of 0". The
     * title names the stage, because a run waiting on downloads can sit at one number for a long
     * time and "Exporting" would make that look stuck.
     */
    private fun progressNotification(progress: ExportProgress): Notification {
        val text = if (progress.total == 0) {
            null
        } else {
            applicationContext.getString(R.string.export_progress_text, progress.done, progress.total)
        }
        val title = when (progress.stage) {
            ExportStage.DOWNLOADING -> R.string.export_downloading_title
            ExportStage.COPYING -> R.string.export_progress_title
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(title))
            .setContentText(text)
            .setProgress(progress.total, progress.done, progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /**
     * Says the export is over, once the progress notification has gone with the run.
     *
     * The screen reports the same result if it is still open; this is for the user who left, which
     * is who a background export is for.
     */
    private fun notifyFinished(summary: ExportSummary) {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val resources = applicationContext.resources
        val saved = summary.exported + summary.alreadyThere
        val text = if (summary.failed == 0) {
            resources.getQuantityString(R.plurals.export_finished_text, saved, saved)
        } else {
            resources.getQuantityString(
                R.plurals.export_finished_with_failures_text,
                summary.failed,
                saved,
                summary.failed,
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.export_finished_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(FINISHED_NOTIFICATION_ID, notification)
    }

    /**
     * Says the export could not reach the folder, so nothing was copied.
     *
     * Posted under the finished notification's id: a run ends one way or the other, never both.
     */
    private fun notifyFailed() {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.export_failed_title))
            .setContentText(applicationContext.getString(R.string.export_failed_text))
            .setAutoCancel(true)
            .build()
        notificationManager.notify(FINISHED_NOTIFICATION_ID, notification)
    }

    /**
     * Creates the channel both notifications are posted to.
     *
     * Low importance: an export the user started themselves needs to be visible, not to make a
     * sound. Idempotent, like every channel the app creates.
     */
    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.export_channel_name))
                .setDescription(applicationContext.getString(R.string.export_channel_description))
                .build(),
        )
    }

    companion object {

        /** Input: the show to export. */
        const val KEY_PODCAST_ID = "podcast_id"

        /** Input: the picked folder, which the scheduler already holds a persistable grant on. */
        const val KEY_TREE_URI = "tree_uri"

        /** Input: the name the user gave the folder the files go into. */
        const val KEY_FOLDER_NAME = "folder_name"

        /**
         * Input: the show page's filter, by name, which decides the episodes.
         *
         * The filter rather than the episode ids, because WorkManager's input is capped at 10 KB and
         * a long show's ids would not fit.
         */
        const val KEY_FILTER = "filter"

        /** Progress: whether the run is downloading or copying, as an [ExportStage] name. */
        const val KEY_STAGE = "stage"

        /** Progress: episodes dealt with so far. */
        const val KEY_DONE = "done"

        /** Progress: episodes in the export. */
        const val KEY_TOTAL = "total"

        /** Output: episodes written as new files. */
        const val KEY_EXPORTED = "exported"

        /** Output: episodes whose file was already there. */
        const val KEY_ALREADY_THERE = "already_there"

        /** Output: episodes that could not be written. */
        const val KEY_FAILED = "failed"

        /** Channel id; stable, because renaming one strands the user's per-channel settings. */
        internal const val CHANNEL_ID = "episode_export"

        /**
         * The ongoing notification's id.
         *
         * Clear of every id the app already posts: 2 is the download service's foreground
         * notification, 3 new episodes, 4 the bell, 1001 the player. Sharing one with a foreground
         * service would replace its notification and could take the service down with it.
         */
        internal const val PROGRESS_NOTIFICATION_ID = 5

        /**
         * The result's id, apart from [PROGRESS_NOTIFICATION_ID] because WorkManager removes that
         * one as the run ends and could take a result posted under it along.
         */
        internal const val FINISHED_NOTIFICATION_ID = 6
    }
}

/**
 * Flattens a summary into the scalars WorkManager can carry back to the screen.
 *
 * @return the output data a finished run reports.
 */
private fun ExportSummary.asOutputData(): Data = workDataOf(
    DownloadExportWorker.KEY_EXPORTED to exported,
    DownloadExportWorker.KEY_ALREADY_THERE to alreadyThere,
    DownloadExportWorker.KEY_FAILED to failed,
)
