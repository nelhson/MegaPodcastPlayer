package md.borisveriga.megapodcastplayer.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.export.DownloadExporter
import md.borisveriga.megapodcastplayer.core.data.export.ExportProgress
import md.borisveriga.megapodcastplayer.core.data.export.ExportRun
import md.borisveriga.megapodcastplayer.core.data.export.ExportSummary

/**
 * Starts [DownloadExportWorker] and translates WorkManager's view of it back into [ExportRun].
 *
 * The only place that knows an export is a worker; the show page is told about progress and
 * results, never about work states.
 *
 * @property context application context; WorkManager is a per-process singleton keyed on it.
 * @property crashReporter told when the folder's grant cannot be kept.
 */
@Singleton
class DownloadExportScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val crashReporter: CrashReporter,
) : DownloadExporter {

    override fun start(podcastId: String, treeUri: String) {
        keepAccess(treeUri.toUri())

        val request = OneTimeWorkRequestBuilder<DownloadExportWorker>()
            .setInputData(
                workDataOf(
                    DownloadExportWorker.KEY_PODCAST_ID to podcastId,
                    DownloadExportWorker.KEY_TREE_URI to treeUri,
                ),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(podcastId),
            // Two exports of one show into the same folder would race to create the same files.
            // Keeping the running one is the answer that loses nothing.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun observe(podcastId: String): Flow<ExportRun?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(uniqueWorkName(podcastId))
            .map { infos -> infos.lastOrNull()?.asExportRun() }

    /**
     * Keeps write access to the picked folder past the activity result that granted it.
     *
     * Taken with the application context, which works because a grant belongs to the app rather than
     * to the activity that received it. A failure is recorded and otherwise left to the worker, which
     * then fails to reach the folder and says so.
     */
    private fun keepAccess(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (failure: SecurityException) {
            crashReporter.recordNonFatal("Episode export folder grant not kept", failure)
        }
    }

    internal companion object {

        /**
         * One unique work per show, so exporting two shows at once is allowed and exporting the
         * same show twice is not.
         */
        fun uniqueWorkName(podcastId: String): String = "episode-export-$podcastId"
    }
}

/**
 * Maps one work state onto what the show page needs to say.
 *
 * @return the run's state. Waiting to start reads as running with nothing counted yet, so the
 *   menu cannot start a second run in the gap.
 */
internal fun WorkInfo.asExportRun(): ExportRun = when (state) {
    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
        ExportRun.Running(
            ExportProgress(
                done = progress.getInt(DownloadExportWorker.KEY_DONE, 0),
                total = progress.getInt(DownloadExportWorker.KEY_TOTAL, 0),
            ),
        )

    WorkInfo.State.SUCCEEDED -> ExportRun.Finished(outputData.asExportSummary())

    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ExportRun.Failed
}

/** Reads the scalars a finished [DownloadExportWorker] reports. */
private fun Data.asExportSummary(): ExportSummary = ExportSummary(
    exported = getInt(DownloadExportWorker.KEY_EXPORTED, 0),
    alreadyThere = getInt(DownloadExportWorker.KEY_ALREADY_THERE, 0),
    failed = getInt(DownloadExportWorker.KEY_FAILED, 0),
)
