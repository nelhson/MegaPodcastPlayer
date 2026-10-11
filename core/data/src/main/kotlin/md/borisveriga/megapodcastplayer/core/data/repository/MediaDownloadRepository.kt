package md.borisveriga.megapodcastplayer.core.data.repository

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.data.mapper.asEpisodeWithShow
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.model.asExternalModel
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.media.download.DownloadStatusRecorder
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloadStatus
import md.borisveriga.megapodcastplayer.core.media.download.EpisodeDownloader
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.SwipeDownload
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.downloadListMarkdown
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoIdOrNull

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
 * A YouTube episode under the official [YouTubeSource] is not downloadable and reads as not
 * downloaded: that source plays nothing from a file, by YouTube's terms. Every list here leaves such
 * episodes out and every request for one is declined, while the rows and the files on the device
 * are left exactly as they are, for the day the source is switched back.
 *
 * @property episodeDao episode rows, including the download columns.
 * @property userPreferences the download rules, and the YouTube source.
 * @property downloader the handle on Media3's download machinery.
 * @property folders which folder each download is filed under: set as a download is asked for,
 *   forgotten as it goes, so a folder never lists a download that is no longer on the device.
 * @property clock stamps the date on an exported download list.
 * @property ioDispatcher dispatcher for the database work.
 * @property scope application scope, for the one piece of work here that outlives its caller: the
 *   wait that puts the "Wi-Fi only" rule back after [downloadNow] lifted it.
 */
@Singleton
class MediaDownloadRepository @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val userPreferences: UserPreferencesDataSource,
    private val downloader: EpisodeDownloader,
    private val folders: DownloadFolderRepository,
    private val clock: Clock,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : DownloadRepository, AutoDownloadScheduler, DownloadStatusRecorder {

    /**
     * The wait for the queue to drain after [downloadNow] lifted the Wi-Fi rule.
     *
     * One at a time: a second *download now* while the first is still running is the same wait, and
     * two of them would put the rule back twice.
     */
    private var requirementRestore: Job? = null

    override fun observeDownloadSettings(): Flow<DownloadSettings> = userPreferences.downloadSettings

    override fun observeDownloadedEpisodes(): Flow<List<Episode>> = combine(
        episodeDao.observeDownloaded().map { rows -> rows.map { it.asExternalModel() } },
        userPreferences.youTubeSource,
    ) { downloaded, youTubeSource ->
        downloaded.filterNot { youTubeSource.hidesDownloadOf(it) }
    }

    override fun observeDownloads(): Flow<List<EpisodeWithShow>> = combine(
        episodeDao.observeDownloadsWithShow().map { rows -> rows.map { it.asEpisodeWithShow() } },
        userPreferences.downloadOrder,
        userPreferences.youTubeSource,
    ) { tracked, order, youTubeSource ->
        val downloads = tracked.filterNot { youTubeSource.hidesDownloadOf(it.episode) }
        if (order.isEmpty()) return@combine downloads

        // A map rather than `indexOf` per row: the stored order keeps ids that are no longer
        // downloads, so it can be much longer than the list being sorted.
        val positions = order.withIndex().associate { (index, id) -> id to index }
        // Anything the user has never placed sorts after everything they have, keeping the DAO's
        // own ordering among themselves — `sortedBy` is stable, so a newly failed download arrives
        // at the bottom rather than displacing a row that was put where it is on purpose.
        downloads.sortedBy { positions[it.episode.id] ?: UNPLACED }
    }

    override suspend fun reorderDownloads(episodeIds: List<String>) =
        userPreferences.setDownloadOrder(episodeIds)

    override suspend fun downloadedBytes(): Long = downloader.downloadedBytes()

    override suspend fun freeBytes(): Long = downloader.freeBytes()

    override suspend fun download(episodeId: String, destination: DownloadDestination): Boolean {
        val episode = withContext(ioDispatcher) { episodeDao.getById(episodeId) } ?: return false
        // Declined as an episode that is not there is declined: the screen hides the control, and
        // this is for whatever reaches here regardless — a swipe, a widget, a stale screen.
        if (isUndownloadable(episode.audioUrl)) return false
        // Filed before it is requested, so the row is in its folder from its first frame.
        folders.file(episodeId, destination)
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

    override suspend fun downloadNow(episodeId: String): Boolean {
        // Lifted before the request rather than after: Media3 evaluates the requirement when the
        // download is added, and a request sent first would sit waiting until something else woke
        // the manager up.
        downloader.setUnmeteredOnly(false)
        val requested = download(episodeId)
        if (requested) {
            restoreRequirements()
        } else {
            // Nothing was started, so nothing will finish and put the rule back. Do it here.
            applyStoredRequirements()
        }
        return requested
    }

    override suspend fun exportListMarkdown(podcastId: String?): String {
        val entries = withContext(ioDispatcher) { episodeDao.getDownloadList(podcastId) }
        if (entries.isEmpty()) return ""
        return downloadListMarkdown(entries.map { it.asExternalModel() }, clock.millis())
    }

    override suspend fun removeDownload(episodeId: String) {
        downloader.remove(episodeId)
        // Also forgotten when Media3 reports the removal (see recordDownloadStatus); done here too
        // so the folder lets go of the row in the same frame the row says it is gone.
        folders.forget(listOf(episodeId))
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

    override fun observeVideoDownloads(): Flow<Map<String, VideoDownload>> = combine(
        downloader.videoDownloads,
        userPreferences.youTubeSource,
    ) { downloads, youTubeSource ->
        // Only a YouTube episode has a video, so under the official source there is nothing to show.
        if (youTubeSource == YouTubeSource.OFFICIAL) emptyMap() else downloads
    }

    override suspend fun downloadVideo(
        episodeId: String,
        quality: VideoQuality,
        destination: DownloadDestination,
    ): Boolean {
        val episode = withContext(ioDispatcher) { episodeDao.getById(episodeId) } ?: return false
        val videoId = youTubeVideoIdOrNull(episode.audioUrl) ?: return false
        if (isUndownloadable(episode.audioUrl)) return false
        // Only when the audio is missing or failed: asking again for audio that is on the device
        // would briefly mark a finished download queued, and Media3 would walk the whole file to
        // confirm what it already knows. Filed either way — the picture and the sound are one row.
        if (episode.downloadState in AUDIO_TO_FETCH) {
            download(episodeId, destination)
        } else {
            folders.file(episodeId, destination)
        }
        downloader.downloadVideo(episodeId = episodeId, videoId = videoId, quality = quality)
        return true
    }

    override suspend fun removeVideoDownload(episodeId: String) {
        downloader.removeVideo(episodeId)
    }

    /**
     * Puts the stored "Wi-Fi only" rule back once nothing is downloading any more.
     *
     * The scope is the application's, not a caller's: the wait outlives the screen that started it,
     * and a download that takes four minutes must not leave the rule lifted because the user
     * switched tabs. If the process dies first, the rule is re-applied on the next start anyway —
     * see `DownloadStateSynchroniser.applyStoredRequirements`.
     */
    private fun restoreRequirements() {
        requirementRestore?.cancel()
        requirementRestore = scope.launch {
            episodeDao.observeActiveDownloadCount().first { it == 0 }
            applyStoredRequirements()
        }
    }

    /** Re-applies whatever the user's download settings say the network rule is. */
    private suspend fun applyStoredRequirements() {
        downloader.setUnmeteredOnly(userPreferences.downloadSettings.first().unmeteredOnly)
    }

    override suspend fun removeAllDownloads() {
        downloader.removeAll()
        recordAllDownloadsRemoved()
    }

    override suspend fun onEpisodesDiscovered(podcastId: String, episodeIds: List<String>) {
        if (episodeIds.isEmpty()) return
        val settings = userPreferences.downloadSettings.first()
        // The show has the last word. A weekly show worth keeping offline and a daily one that is
        // only ever streamed are the same app-wide setting and two different answers, which is
        // what makes auto-download a per-show decision in practice.
        val show = userPreferences.showSettings.first()[podcastId] ?: ShowSettings.DEFAULT
        if (!show.autoDownloadOr(settings.autoDownloadNewEpisodes)) return

        // A refresh that discovered fifty back-catalogue episodes must not queue fifty downloads;
        // the limit is how many new ones the user asked for. It bounds what is *fetched* and
        // nothing more: a refresh used to end by sweeping the show's oldest downloads back down to
        // the limit, which deleted episodes the user had saved by hand because a new one arrived.
        // Pulling the library down must never cost a download. What leaves the device now does so
        // only by the user's own gesture or by the delete-after-playing rule.
        val toDownload = if (settings.enforcesKeepLimit) {
            episodeIds.take(settings.keepLimitPerPodcast)
        } else {
            episodeIds
        }

        val youTubeSource = userPreferences.youTubeSource.first()
        // Resolved and marked in one hop onto the IO dispatcher rather than one per episode: a
        // refresh can discover dozens at once, and each hop is a context switch for a single row.
        val episodes = withContext(ioDispatcher) {
            toDownload.mapNotNull { id -> episodeDao.getById(id) }
                // A YouTube show refreshed under the official source discovers videos it may not
                // download; they are skipped, not counted against the limit, and not marked.
                .filterNot { youTubeSource.hidesDownloadOf(it.audioUrl) }
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
            // Nobody chose a folder for these, so they go where new downloads go.
            folders.file(episode.id, DownloadDestination.Unspecified)
            // A refresh can run from a background worker, where starting a foreground service is
            // forbidden; Media3's scheduler picks the work up instead.
            downloader.download(
                episodeId = episode.id,
                audioUrl = episode.audioUrl,
                foreground = false,
            )
        }
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
        // Every way a download leaves — a delete here, delete-after-playing, a removal from
        // anywhere else — ends in this event, so it is where a folder lets go of it.
        if (status.state == DownloadState.NOT_DOWNLOADED) folders.forget(listOf(status.episodeId))
    }

    override suspend fun recordAllDownloadsRemoved() {
        withContext(ioDispatcher) { episodeDao.clearAllDownloadStates() }
        folders.forgetAll()
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

    override suspend fun setSwipeDownload(choice: SwipeDownload) =
        userPreferences.setSwipeDownload(choice)

    /**
     * Whether a download of the episode behind [audioUrl] must be declined right now.
     *
     * @param audioUrl the episode's stored URL; a YouTube sentinel is what makes the answer yes.
     */
    private suspend fun isUndownloadable(audioUrl: String): Boolean =
        userPreferences.youTubeSource.first().hidesDownloadOf(audioUrl)

    private companion object {
        /** Where a download the user has never dragged sorts: after everything they have placed. */
        const val UNPLACED = Int.MAX_VALUE

        /** Audio states a video download also asks for the audio from: nothing there, or broken. */
        val AUDIO_TO_FETCH = setOf(DownloadState.NOT_DOWNLOADED, DownloadState.FAILED)
    }
}

/**
 * Whether this source keeps the download of the episode behind [audioUrl] out of sight.
 *
 * True only for a YouTube episode under [YouTubeSource.OFFICIAL]: an RSS episode is downloadable
 * under either, and the extractor downloads anything.
 *
 * @param audioUrl the episode's stored URL.
 */
private fun YouTubeSource.hidesDownloadOf(audioUrl: String): Boolean =
    this == YouTubeSource.OFFICIAL && youTubeVideoIdOrNull(audioUrl) != null

/** [hidesDownloadOf] for an episode in hand. */
private fun YouTubeSource.hidesDownloadOf(episode: Episode): Boolean = hidesDownloadOf(episode.audioUrl)
