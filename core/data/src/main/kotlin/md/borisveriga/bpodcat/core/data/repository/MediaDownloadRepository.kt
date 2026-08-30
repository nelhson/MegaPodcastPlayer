package md.borisveriga.bpodcat.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import md.borisveriga.bpodcat.core.common.di.BPodcatDispatcher
import md.borisveriga.bpodcat.core.common.di.Dispatcher
import md.borisveriga.bpodcat.core.data.mapper.asEpisodeWithShow
import md.borisveriga.bpodcat.core.database.dao.EpisodeDao
import md.borisveriga.bpodcat.core.database.dao.QueueDao
import md.borisveriga.bpodcat.core.database.model.asExternalModel
import md.borisveriga.bpodcat.core.datastore.UserPreferencesDataSource
import md.borisveriga.bpodcat.core.media.download.DownloadStatusRecorder
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloadStatus
import md.borisveriga.bpodcat.core.media.download.EpisodeDownloader
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * Media3- and Room-backed implementation of the download stack.
 *
 * One class implements three interfaces for the same reason [DefaultPlaybackRepository] does: they
 * are three views of one thing. [DownloadRepository] is what the UI asks, [AutoDownloadScheduler] is
 * what a refresh tells, and [DownloadStatusRecorder] is what the download service writes back
 * through.
 *
 * The division of labour with Media3 is worth stating once: Media3 owns the *audio* and its own
 * index, and this class owns the *episode rows* the UI observes. Every state change flows one way —
 * Media3 event, then Room write — so the two can never disagree for longer than one event.
 *
 * @property episodeDao episode rows, including the download columns.
 * @property queueDao read to find out which episodes the keep-limit sweep must spare.
 * @property userPreferences the download rules.
 * @property downloader the handle on Media3's download machinery.
 * @property ioDispatcher dispatcher for the database work.
 */
@Singleton
class MediaDownloadRepository @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val queueDao: QueueDao,
    private val userPreferences: UserPreferencesDataSource,
    private val downloader: EpisodeDownloader,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository, AutoDownloadScheduler, DownloadStatusRecorder {

    override fun observeDownloadSettings(): Flow<DownloadSettings> = userPreferences.downloadSettings

    override fun observeDownloadedEpisodes(): Flow<List<Episode>> =
        episodeDao.observeDownloaded().map { rows -> rows.map { it.asExternalModel() } }

    override fun observeDownloads(): Flow<List<EpisodeWithShow>> =
        episodeDao.observeDownloadsWithShow().map { rows -> rows.map { it.asEpisodeWithShow() } }

    override suspend fun downloadedBytes(): Long = downloader.downloadedBytes()

    override suspend fun freeBytes(): Long = downloader.freeBytes()

    override suspend fun download(episodeId: String): Boolean {
        val episode = withContext(ioDispatcher) { episodeDao.getById(episodeId) } ?: return false
        // Written optimistically so the button flips to "queued" on the tap rather than a beat
        // later when Media3's first event arrives. The event overwrites this with the truth.
        withContext(ioDispatcher) {
            episodeDao.updateDownloadState(
                id = episodeId,
                state = DownloadState.QUEUED,
                downloadedBytes = 0L,
                percent = 0f,
            )
        }
        downloader.download(episodeId = episodeId, audioUrl = episode.audioUrl)
        return true
    }

    override suspend fun removeDownload(episodeId: String) {
        downloader.remove(episodeId)
        // Media3 confirms the removal with an event, but only once the file is actually gone. The
        // row is cleared now so the UI does not show "downloaded" for an episode already on its way
        // out.
        withContext(ioDispatcher) {
            episodeDao.updateDownloadState(
                id = episodeId,
                state = DownloadState.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                percent = 0f,
            )
        }
    }

    override suspend fun removeAllDownloads() {
        downloader.removeAll()
        recordAllDownloadsRemoved()
    }

    override suspend fun enforceKeepLimit(podcastId: String) {
        val settings = userPreferences.downloadSettings.first()
        if (!settings.enforcesKeepLimit) return

        val (downloaded, protectedIds) = withContext(ioDispatcher) {
            val rows = episodeDao.getDownloadedForPodcast(podcastId).map { it.asExternalModel() }
            rows to queueDao.getEntries().mapTo(mutableSetOf()) { it.episodeId }
        }

        settings.episodesToSweep(downloaded, protectedIds).forEach { episode ->
            removeDownload(episode.id)
        }
    }

    override suspend fun onEpisodesDiscovered(podcastId: String, episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        val settings = userPreferences.downloadSettings.first()
        if (!settings.autoDownloadNewEpisodes) return

        // A refresh that discovered fifty back-catalogue episodes must not queue fifty downloads;
        // the keep-limit is what the user asked to hold, so it also bounds what is fetched.
        val toDownload = if (settings.enforcesKeepLimit) {
            episodeIds.take(settings.keepLimitPerPodcast)
        } else {
            episodeIds
        }

        // Resolved and marked in one hop onto the IO dispatcher rather than one per episode: a
        // refresh can discover dozens at once, and each hop is a context switch for a single row.
        val episodes = withContext(ioDispatcher) {
            toDownload.mapNotNull { id -> episodeDao.getById(id) }
                .onEach { episode ->
                    episodeDao.updateDownloadState(
                        id = episode.id,
                        state = DownloadState.QUEUED,
                        downloadedBytes = 0L,
                        percent = 0f,
                    )
                }
        }

        episodes.forEach { episode ->
            // A refresh can run from a background worker, where starting a foreground service is
            // forbidden; Media3's scheduler picks the work up instead.
            downloader.download(
                episodeId = episode.id,
                audioUrl = episode.audioUrl,
                foreground = false,
            )
        }

        enforceKeepLimit(podcastId)
    }

    override suspend fun recordDownloadStatus(status: EpisodeDownloadStatus) {
        withContext(ioDispatcher) {
            episodeDao.updateDownloadState(
                id = status.episodeId,
                state = status.state,
                downloadedBytes = status.downloadedBytes,
                percent = status.percent,
            )
        }
    }

    override suspend fun recordAllDownloadsRemoved() {
        withContext(ioDispatcher) { episodeDao.clearAllDownloadStates() }
    }

    override suspend fun setAutoDownloadNewEpisodes(enabled: Boolean) =
        userPreferences.setAutoDownloadNewEpisodes(enabled)

    override suspend fun setUnmeteredOnly(enabled: Boolean) {
        userPreferences.setUnmeteredOnly(enabled)
        // Applied to Media3 here as well as by DownloadStateSynchroniser's settings collector, so
        // that a user who toggles this while nothing is collecting still gets the new rule.
        downloader.setUnmeteredOnly(enabled)
    }

    override suspend fun setKeepLimitPerPodcast(limit: Int) =
        userPreferences.setKeepLimitPerPodcast(limit)

    override suspend fun setDeleteAfterPlaying(enabled: Boolean) =
        userPreferences.setDeleteAfterPlaying(enabled)
}
