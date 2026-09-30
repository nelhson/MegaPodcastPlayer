package md.borisveriga.megapodcastplayer.core.media.download

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
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
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlySentinel

/**
 * The app's handle on Media3's download machinery.
 *
 * An episode can have two downloads: its sound, under the episode id, which every episode has; and
 * for a YouTube episode its picture at one rendition, under an id of its own (see
 * `VideoDownloads.kt`), which is what lets it be watched offline.
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
@OptIn(UnstableApi::class)
class EpisodeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    @DownloadCache private val cache: Cache,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Every change to an episode's audio download, as it happens.
     *
     * A removal is reported as [EpisodeDownloadStatus.notDownloaded] rather than as an event of its
     * own, because that is exactly what a caller mirroring the state into a database wants to
     * write. Collectors get nothing until something changes; use [currentStatuses] for the starting
     * picture.
     *
     * Picture downloads are left out: their content id is not an episode id, and the episodes table
     * this feeds describes the sound. They are reported by [videoDownloads].
     */
    val statusUpdates: Flow<EpisodeDownloadStatus> = callbackFlow {
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                if (!download.isVideoDownload) trySend(download.asEpisodeDownloadStatus())
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                if (download.isVideoDownload) return
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
     * Every episode's picture download, keyed by episode id, as it stands and then as it changes.
     *
     * Unlike [statusUpdates] this starts with the whole picture, read from the index, because
     * nothing mirrors it anywhere: the index is the only record of a picture download, so a
     * collector has to be told what is already there. Updates that land while the index is being
     * read win over the snapshot, which is older than they are.
     *
     * An episode with no picture download is absent rather than present as "not downloaded".
     */
    val videoDownloads: Flow<Map<String, VideoDownload>> = callbackFlow {
        // Touched only on the main thread: the listener is called there, and the producer runs
        // there too, apart from the index read, which hands its result back before it is applied.
        val downloads = mutableMapOf<String, VideoDownload>()
        val changedDuringRead = mutableSetOf<String>()
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                val episodeId = episodeIdOfVideoDownloadOrNull(download.request.id) ?: return
                changedDuringRead += episodeId
                val video = download.asVideoDownloadOrNull()?.second
                if (video == null) downloads -= episodeId else downloads[episodeId] = video
                trySend(downloads.toMap())
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                val episodeId = episodeIdOfVideoDownloadOrNull(download.request.id) ?: return
                changedDuringRead += episodeId
                downloads -= episodeId
                trySend(downloads.toMap())
            }
        }
        downloadManager.addListener(listener)
        readIndex().mapNotNull { it.asVideoDownloadOrNull() }.forEach { (episodeId, video) ->
            if (episodeId !in changedDuringRead) downloads[episodeId] = video
        }
        send(downloads.toMap())
        awaitClose { downloadManager.removeListener(listener) }
    }
        // DownloadManager may only be touched from the looper it was created on.
        .flowOn(Dispatchers.Main.immediate)

    /**
     * Queues an episode's picture for download, at one rendition.
     *
     * The picture only: an offline video also needs the episode's sound, which is its ordinary
     * download, and asking for that is the caller's business — it knows whether the sound is
     * already on the device.
     *
     * The request's URI is the video-only sentinel for [quality], so the picture is filed under the
     * very key the player reads when it is asked to show that video at that height — the same trick
     * that lets a downloaded episode's sound play from disk.
     *
     * An episode keeps one rendition. Asking for the height it already has, or is fetching, is a
     * no-op or a retry, as for sound. Asking for another replaces it: the old picture is removed and
     * the new one queued under the same content id. Media3 holds an add that arrives while its id
     * is being removed until the removal is done, and the removal works from the request it started
     * with, so it deletes the old picture's bytes and not the new one's. Merging the new request
     * into a finished download instead would leave the old bytes filed under a key nothing reads
     * any more, taking up storage no screen accounts for.
     *
     * @param episodeId the episode.
     * @param videoId the YouTube video behind it.
     * @param quality the rendition height to keep.
     * @param foreground true when a user action started this; see [download].
     */
    suspend fun downloadVideo(
        episodeId: String,
        videoId: String,
        quality: VideoQuality,
        foreground: Boolean = true,
    ) {
        val id = videoDownloadId(episodeId)
        val uri = youTubeVideoOnlySentinel(videoId, quality).toUri()
        val existing = withContext(ioDispatcher) {
            suspendRunCatching { downloadManager.downloadIndex.getDownload(id) }.getOrNull()
        }
        if (existing != null && existing.request.uri != uri) removeById(id, foreground)
        val request = DownloadRequest.Builder(id, uri).build()
        sendToService(id) {
            DownloadService.sendAddDownload(
                context,
                EpisodeDownloadService::class.java,
                request,
                foreground,
            )
        }
    }

    /**
     * Removes an episode's downloads: its sound and, if it has one, its picture.
     *
     * Both, because a picture without its sound does not play offline, and because every caller
     * means "take this episode off the device" — the downloads screen, the delete-after-playing
     * rule, a show being removed. Safe to call for an episode that was never downloaded; Media3
     * ignores an unknown content id.
     */
    suspend fun remove(episodeId: String, foreground: Boolean = true) {
        removeById(episodeId, foreground)
        removeById(videoDownloadId(episodeId), foreground)
    }

    /**
     * Removes an episode's picture download and leaves its sound on the device.
     *
     * @param episodeId the episode.
     * @param foreground see [remove].
     */
    suspend fun removeVideo(episodeId: String, foreground: Boolean = true) {
        removeById(videoDownloadId(episodeId), foreground)
    }

    /** Asks the service to remove the download whose content id is [id]. */
    private suspend fun removeById(id: String, foreground: Boolean) {
        sendToService(id) {
            DownloadService.sendRemoveDownload(
                context,
                EpisodeDownloadService::class.java,
                id,
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
     * Reads the current state of every episode's audio download Media3 knows about.
     *
     * Used to reconcile the database on start-up: a download that finished while the app was dead
     * fired its event to nobody, so the row still claims to be downloading until this puts it
     * right. Picture downloads are left out, as from [statusUpdates].
     */
    suspend fun currentStatuses(): List<EpisodeDownloadStatus> =
        readIndex().filterNot { it.isVideoDownload }.map { it.asEpisodeDownloadStatus() }

    /**
     * Every download in the index, sound and picture alike.
     *
     * An unreadable index is not worth crashing over: it reads as empty, and the UI simply keeps
     * showing whatever it last knew.
     */
    private suspend fun readIndex(): List<Download> = withContext(ioDispatcher) {
        suspendRunCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.download)
                }
            }
        }.getOrElse { error ->
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
