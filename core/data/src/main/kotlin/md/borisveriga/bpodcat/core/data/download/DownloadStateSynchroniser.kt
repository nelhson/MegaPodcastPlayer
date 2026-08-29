package md.borisveriga.bpodcat.core.data.download

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import md.borisveriga.bpodcat.core.common.di.ApplicationScope
import md.borisveriga.bpodcat.core.data.repository.DownloadRepository
import md.borisveriga.bpodcat.core.database.dao.EpisodeDao
import md.borisveriga.bpodcat.core.media.download.DownloadStatusRecorder
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloadStatus
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloader

/**
 * Keeps the episodes table in step with Media3's download index.
 *
 * Media3 reports progress through a listener that only exists while something is listening, and
 * downloads keep running when the app is not. Two things follow, and this class does both:
 *
 *  1. **Reconcile on start-up.** A download that finished while the app was dead fired its event to
 *     nobody, so the row still claims to be downloading. The index is the truth; the table is
 *     corrected from it.
 *  2. **Mirror from then on.** Every subsequent event is written straight through.
 *
 * It also pushes the user's "Wi-Fi only" preference into Media3 whenever it changes, which is the
 * one setting that has to live on both sides.
 *
 * @property downloader the source of download events.
 * @property recorder where events are written; the same object as [repository] in practice.
 * @property repository read for the download settings.
 * @property episodeDao used to find rows that claim a download Media3 has no record of.
 * @property scope application scope — this outlives every screen, and must, because downloads do.
 */
@Singleton
class DownloadStateSynchroniser @Inject constructor(
    private val downloader: EpisodeDownloader,
    private val recorder: DownloadStatusRecorder,
    private val repository: DownloadRepository,
    private val episodeDao: EpisodeDao,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [start] so that it runs once however many times the application object calls it. */
    private val started = AtomicBoolean(false)

    /**
     * Begins mirroring, after one reconciliation pass.
     *
     * Called from the application's `onCreate`. Returns immediately; the work runs on [scope].
     *
     * @return the mirroring job, or null if mirroring was already started. Production callers
     *   ignore it — nothing waits for a job that only ends with the process. Tests join it, because
     *   reconciliation suspends on Room's executor and would otherwise still be in flight when the
     *   assertions run.
     */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null

        val mirrorJob = scope.launch {
            reconcile()
            // Collected after reconciling, not before: an event that arrives mid-reconciliation
            // would otherwise be overwritten by the older index snapshot.
            downloader.statusUpdates.collect(recorder::recordDownloadStatus)
        }

        scope.launch {
            repository.observeDownloadSettings()
                .map { it.unmeteredOnly }
                .distinctUntilChanged()
                .collect(downloader::setUnmeteredOnly)
        }

        return mirrorJob
    }

    /**
     * Corrects the episodes table against Media3's index.
     *
     * Runs in both directions. Downloads Media3 knows about are written through as they are. Rows
     * that claim a download Media3 has *no* record of are cleared — that is what an episode whose
     * audio was deleted by the system to reclaim storage looks like, and leaving it marked
     * downloaded would offer the user offline playback that silently falls back to the network.
     */
    private suspend fun reconcile() {
        val statuses = downloader.currentStatuses()
        statuses.forEach { recorder.recordDownloadStatus(it) }

        val known = statuses.mapTo(mutableSetOf()) { it.episodeId }
        episodeDao.getIdsWithDownloadState()
            .filterNot { it in known }
            .forEach { orphanedId ->
                recorder.recordDownloadStatus(EpisodeDownloadStatus.notDownloaded(orphanedId))
            }
    }

    /**
     * Applies the stored "Wi-Fi only" preference to Media3 once, without collecting.
     *
     * Exposed for the download service's own start-up path, where the app process may have been
     * created for the service alone and [start] has not run.
     */
    suspend fun applyStoredRequirements() {
        downloader.setUnmeteredOnly(repository.observeDownloadSettings().first().unmeteredOnly)
    }
}
