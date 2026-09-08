package md.borisveriga.megapodcastplayer.backup

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import md.borisveriga.megapodcastplayer.core.data.backup.LibraryRestorer
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreProgress
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary

/**
 * Starts [RestoreWorker] and translates WorkManager's view of it back into [RestoreRun].
 *
 * This is the only place that knows a restore is a worker at all; the settings screen is told about
 * progress and results, never about work states.
 *
 * @property context application context; WorkManager is a per-process singleton keyed on it.
 */
@Singleton
class RestoreScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LibraryRestorer {

    override fun start(validatedJson: String, reDownload: Boolean) {
        val handover = File(context.cacheDir, RestoreWorker.HANDOVER_FILE_NAME)
        handover.writeText(validatedJson)

        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(
                workDataOf(
                    RestoreWorker.KEY_FILE_PATH to handover.absolutePath,
                    RestoreWorker.KEY_RE_DOWNLOAD to reDownload,
                ),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            // A second restore begun while one is running would interleave two sets of feed fetches
            // over the same rows. Keeping the first is the only sane answer.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun observe(): Flow<RestoreRun?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .map { infos -> infos.lastOrNull()?.asRestoreRun() }

    internal companion object {

        /** Name of the unique work. */
        const val UNIQUE_WORK_NAME = "library-restore"
    }
}

/**
 * Maps one work state onto what the screen needs to say.
 *
 * @return the run's state, or null while it is still only enqueued — there is nothing useful to
 *   show between tapping restore and the first feed being fetched, and a spinner with no counter
 *   reads as a hang.
 */
internal fun WorkInfo.asRestoreRun(): RestoreRun? = when (state) {
    WorkInfo.State.RUNNING -> RestoreRun.Running(progress.asRestoreProgress())
    WorkInfo.State.SUCCEEDED -> RestoreRun.Finished(outputData.asRestoreSummary())
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> RestoreRun.Failed
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RestoreRun.Running(RestoreProgress(0, 0, ""))
}

/** Reads the counters [RestoreWorker] publishes as it goes. */
private fun Data.asRestoreProgress(): RestoreProgress = RestoreProgress(
    completed = getInt(RestoreWorker.KEY_COMPLETED, 0),
    total = getInt(RestoreWorker.KEY_TOTAL, 0),
    currentTitle = getString(RestoreWorker.KEY_CURRENT_TITLE).orEmpty(),
)

/** Reads the scalars a finished [RestoreWorker] reports. */
private fun Data.asRestoreSummary(): RestoreSummary = RestoreSummary(
    showsRestored = getInt(RestoreWorker.KEY_SHOWS_RESTORED, 0),
    episodesRestored = getInt(RestoreWorker.KEY_EPISODES_RESTORED, 0),
    episodesMissing = getInt(RestoreWorker.KEY_EPISODES_MISSING, 0),
    queueRestored = getInt(RestoreWorker.KEY_QUEUE_RESTORED, 0),
    downloadsQueued = getInt(RestoreWorker.KEY_DOWNLOADS_QUEUED, 0),
    failedTitles = getStringArray(RestoreWorker.KEY_FAILED_TITLES)?.toList().orEmpty(),
)
