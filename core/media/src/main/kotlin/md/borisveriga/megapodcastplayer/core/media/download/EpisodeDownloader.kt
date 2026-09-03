package md.borisveriga.megapodcastplayer.core.media.download

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.media.di.DownloadCache

/**
 * The app's handle on Media3's download machinery.
 *
 * Wraps [DownloadManager] and [EpisodeDownloadService] so that callers deal in episode ids, suspend
 * functions and a [Flow] of [EpisodeDownloadStatus], rather than in intents, content ids and a
 * listener that must be registered on a particular looper.
 *
 * Everything that touches the manager is funnelled onto the main thread, which is the looper Media3
 * builds it on; callers may use this from any dispatcher.
 *
 * @property context application context, used to send service intents.
 * @property downloadManager the single download manager, shared with [EpisodeDownloadService].
 * @property cache the download cache, read for the storage figure the settings screen shows.
 * @property ioDispatcher dispatcher for the index and cache reads, which both hit disk.
 */
@Singleton
class EpisodeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    @DownloadCache private val cache: Cache,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Every download state change, as it happens.
     *
     * A removal is reported as [EpisodeDownloadStatus.notDownloaded] rather than as an event of its
     * own, because that is exactly what a caller mirroring the state into a database wants to
     * write. Collectors get nothing until something changes; use [currentStatuses] for the starting
     * picture.
     */
    val statusUpdates: Flow<EpisodeDownloadStatus> = callbackFlow {
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(download.asEpisodeDownloadStatus())
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                trySend(EpisodeDownloadStatus.notDownloaded(download.request.id))
            }
        }
        downloadManager.addListener(listener)
        awaitClose { downloadManager.removeListener(listener) }
    }
        // DownloadManager may only be touched from the looper it was created on.
        .flowOn(Dispatchers.Main.immediate)

    /**
     * Queues an episode for download.
     *
     * Idempotent: Media3 treats a repeat request for the same content id as a no-op when it is
     * already downloading or downloaded, and as a retry when it previously failed — which is what
     * makes this safe behind a "retry" button as well as behind a "download" one.
     *
     * @param episodeId the episode; becomes Media3's content id.
     * @param audioUrl the enclosure URL to fetch.
     * @param foreground true when a user action started this, which lets the service take the
     *   foreground immediately. Pass false from background work.
     */
    suspend fun download(episodeId: String, audioUrl: String, foreground: Boolean = true) {
        // No custom cache key on purpose: without one, the downloader and the player both key the
        // cache off the audio URL, which is what makes a downloaded episode actually play from disk
        // instead of being fetched all over again.
        val request = DownloadRequest.Builder(episodeId, audioUrl.toUri()).build()
        sendToService(episodeId) {
            DownloadService.sendAddDownload(
                context,
                EpisodeDownloadService::class.java,
                request,
                foreground,
            )
        }
    }

    /**
     * Removes a download and its audio.
     *
     * Safe to call for an episode that was never downloaded; Media3 ignores an unknown content id.
     */
    suspend fun remove(episodeId: String, foreground: Boolean = true) {
        sendToService(episodeId) {
            DownloadService.sendRemoveDownload(
                context,
                EpisodeDownloadService::class.java,
                episodeId,
                foreground,
            )
        }
    }

    /** Removes every download — the settings screen's "free up all storage". */
    suspend fun removeAll(foreground: Boolean = true) {
        sendToService(contentId = "all") {
            DownloadService.sendRemoveAllDownloads(
                context,
                EpisodeDownloadService::class.java,
                foreground,
            )
        }
    }

    /**
     * Sets whether downloads wait for an unmetered network.
     *
     * Applies to queued downloads as well as future ones: switching this on while something is
     * downloading over mobile data stops it there and then, which is the whole point of the switch.
     */
    suspend fun setUnmeteredOnly(unmeteredOnly: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            downloadManager.requirements = Requirements(
                if (unmeteredOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
            )
        }
    }

    /**
     * Reads the current state of every download Media3 knows about.
     *
     * Used to reconcile the database on start-up: a download that finished while the app was dead
     * fired its event to nobody, so the row still claims to be downloading until this puts it
     * right.
     */
    suspend fun currentStatuses(): List<EpisodeDownloadStatus> = withContext(ioDispatcher) {
        suspendRunCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.download.asEpisodeDownloadStatus())
                    }
                }
            }
        }.getOrElse { error ->
            // An unreadable index is not worth crashing over: the UI simply keeps showing whatever
            // the database last recorded.
            Log.w(TAG, "Could not read the download index", error)
            emptyList()
        }
    }

    /**
     * Total bytes the download cache occupies on disk.
     *
     * Read from the cache rather than summed over the episodes table, because the cache is the
     * thing actually taking up the user's storage — partial downloads and all.
     */
    suspend fun downloadedBytes(): Long = withContext(ioDispatcher) {
        suspendRunCatching { cache.cacheSpace }.getOrElse { 0L }
    }

    /**
     * Bytes the app could still write to the volume the downloads live on.
     *
     * Asked of the volume holding the app's private files directory, which is where [
     * md.borisveriga.megapodcastplayer.core.media.di.DownloadModule] puts the cache: any other volume would
     * produce a number that looks right and is not, on a device with removable storage.
     *
     * `getAllocatableBytes` rather than `File.usableSpace`, which is what the platform recommends
     * and is also the more honest answer to the question the downloads screen is asking. The system
     * will clear other apps' cached data to make room, so what can actually be downloaded is
     * usually larger than what is free at this instant.
     *
     * Zero on failure, for the same reason as [downloadedBytes]: this figure decorates a bar, and
     * no bar is better than a crash.
     */
    suspend fun freeBytes(): Long = withContext(ioDispatcher) {
        suspendRunCatching {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(context.filesDir))
        }.getOrElse { 0L }
    }

    /**
     * Sends a command to [EpisodeDownloadService], swallowing a background-start refusal.
     *
     * Android forbids starting a foreground service from the background, and an auto-download
     * triggered by a periodic refresh can land in exactly that window. The command is dropped, but
     * Media3's scheduler re-runs outstanding work the next time requirements are met, so nothing is
     * lost permanently — and a background refresh must never crash the app.
     */
    private suspend fun sendToService(contentId: String, send: () -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            try {
                send()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the download service for $contentId", e)
            }
        }
    }

    private companion object {
        const val TAG = "EpisodeDownloader"
    }
}
