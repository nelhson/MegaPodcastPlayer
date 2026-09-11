package md.borisveriga.megapodcastplayer.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.data.repository.BackupRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary
import md.borisveriga.megapodcastplayer.core.model.backup.BackupCodec
import md.borisveriga.megapodcastplayer.core.model.backup.BackupDecodeResult

/**
 * Subscribes to every show in the list the user picked.
 *
 * A worker rather than a coroutine in a ViewModel because an import is one network round trip per
 * show: thirty shows is minutes of work that has to survive a rotation, a fold, and the app being
 * put in the background.
 *
 * It is handed a *file path*, never the picked document's `Uri`. The picker does not grant
 * persistable access, so by the time WorkManager runs the granting activity may be gone; and the
 * document is far larger than the roughly 10 KB a `Data` payload allows. The route therefore reads
 * and validates the document while the user is still looking at it — which also means a foreign or
 * truncated file is refused before any work is enqueued — and leaves the validated JSON in the
 * cache for this worker to pick up.
 *
 * @property backupRepository does the restoring.
 * @property crashReporter told when the handover file is unreadable, which the user only ever sees
 *   as a run that failed.
 */
@HiltWorker
class RestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val crashReporter: CrashReporter,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val text = suspendRunCatching { File(path).readText() }
            .getOrElse { failure ->
                crashReporter.recordNonFatal("Backup restore handover unreadable", failure)
                return Result.failure()
            }

        // The route already validated this document; anything else here is a corrupted handover.
        val decoded = BackupCodec.decode(text) as? BackupDecodeResult.Decoded
            ?: return Result.failure()

        val summary = backupRepository.restore(
            file = decoded.file,
            onProgress = { progress ->
                setProgressAsync(
                    workDataOf(
                        KEY_COMPLETED to progress.completed,
                        KEY_TOTAL to progress.total,
                        KEY_CURRENT_TITLE to progress.currentTitle,
                    ),
                )
            },
        )

        // Best-effort: the handover file is in the cache, so leaving it costs nothing but tidiness.
        suspendRunCatching { File(path).delete() }

        return Result.success(summary.asOutputData())
    }

    companion object {

        /** Input: absolute path of the validated JSON the route left in the cache. */
        const val KEY_FILE_PATH = "file_path"

        /** Progress: shows finished so far. */
        const val KEY_COMPLETED = "completed"

        /** Progress: shows in the document. */
        const val KEY_TOTAL = "total"

        /** Progress: the show being fetched right now. */
        const val KEY_CURRENT_TITLE = "current_title"

        /** Output: shows restored. */
        const val KEY_SHOWS_RESTORED = "shows_restored"

        /** Output: titles of shows whose feed could not be fetched. */
        const val KEY_FAILED_TITLES = "failed_titles"

        /** The name of the cache file the route and this worker hand over through. */
        const val HANDOVER_FILE_NAME = "pending-restore.json"
    }
}

/**
 * Flattens a summary into the scalars WorkManager can carry back to the screen.
 *
 * @return the output data a finished run reports.
 */
private fun RestoreSummary.asOutputData(): Data = workDataOf(
    RestoreWorker.KEY_SHOWS_RESTORED to showsRestored,
    RestoreWorker.KEY_FAILED_TITLES to failedTitles.toTypedArray(),
)
