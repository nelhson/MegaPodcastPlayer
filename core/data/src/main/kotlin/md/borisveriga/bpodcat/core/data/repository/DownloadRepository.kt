package md.borisveriga.bpodcat.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.DownloadedEpisode
import md.borisveriga.bpodcat.core.model.Episode

/**
 * Everything the app knows about episodes stored on the device.
 *
 * Downloads are modelled the same offline-first way as feeds: reads come from Room, which Media3's
 * download events are mirrored into, so the UI renders instantly and correctly even before the
 * download service has started. Writes here are requests — Media3 decides when they actually run,
 * because a download may be waiting for Wi-Fi.
 */
interface DownloadRepository {

    /** Observes the user's download rules. */
    fun observeDownloadSettings(): Flow<DownloadSettings>

    /** Observes every episode available offline, newest first. */
    fun observeDownloadedEpisodes(): Flow<List<Episode>>

    /**
     * Observes every episode available offline with the show it belongs to, newest first.
     *
     * The downloads screen mixes shows, so a row has to name the one it came from;
     * [observeDownloadedEpisodes] remains for callers that already know the show.
     */
    fun observeDownloads(): Flow<List<DownloadedEpisode>>

    /**
     * Total bytes the downloads occupy on disk.
     *
     * Read once rather than observed: it is a settings-screen figure, and watching a byte counter
     * would mean waking the UI on every write during a download.
     */
    suspend fun downloadedBytes(): Long

    /**
     * Requests an episode be downloaded.
     *
     * Safe to call for an episode that is already downloading (a no-op) or that previously failed
     * (a retry), which is what lets one button serve both.
     *
     * @param episodeId the episode to download.
     * @return true if the request was made; false if the episode is not stored.
     */
    suspend fun download(episodeId: String): Boolean

    /**
     * Removes an episode's downloaded audio, cancelling it first if it is still in progress.
     *
     * @param episodeId the episode to remove.
     */
    suspend fun removeDownload(episodeId: String)

    /** Removes every download and frees all the storage they occupy. */
    suspend fun removeAllDownloads()

    /**
     * Applies the keep-limit to one show, removing its oldest downloads.
     *
     * Never removes a queued episode, however old: deleting audio the user is about to play is a
     * worse outcome than briefly holding one more episode than the limit allows.
     *
     * @param podcastId the show to sweep.
     */
    suspend fun enforceKeepLimit(podcastId: String)

    /** Enables or disables downloading episodes as a feed refresh discovers them. */
    suspend fun setAutoDownloadNewEpisodes(enabled: Boolean)

    /** Sets whether downloads wait for an unmetered network. */
    suspend fun setUnmeteredOnly(enabled: Boolean)

    /** Sets how many downloaded episodes to keep per show; [DownloadSettings.KEEP_ALL] disables it. */
    suspend fun setKeepLimitPerPodcast(limit: Int)

    /** Sets whether finishing an episode removes its downloaded audio. */
    suspend fun setDeleteAfterPlaying(enabled: Boolean)
}

/**
 * Told when a feed refresh discovers episodes, so they can be downloaded automatically.
 *
 * A one-method interface rather than [DownloadRepository] itself, so that
 * [OfflineFirstPodcastRepository] depends on the *fact* that something wants to hear about new
 * episodes and not on the whole download stack — which also keeps its tests free of Media3.
 */
interface AutoDownloadScheduler {

    /**
     * Called after a refresh has stored newly discovered episodes.
     *
     * Implementations must decide for themselves whether auto-download is switched on; the caller
     * does not read settings.
     *
     * @param podcastId the show the episodes belong to.
     * @param episodeIds the episodes that were genuinely new, newest first is not guaranteed.
     */
    suspend fun onEpisodesDiscovered(podcastId: String, episodeIds: List<String>)
}
