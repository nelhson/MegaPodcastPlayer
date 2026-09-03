package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow

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
     * Observes every episode the download stack is tracking — completed, transferring, waiting and
     * failed — with the show it belongs to.
     *
     * Two differences from [observeDownloadedEpisodes], both deliberate. It carries the show,
     * because the downloads screen mixes shows and an episode title alone does not say what you are
     * looking at. And it is not limited to what is on the device: a transfer in progress and a
     * failure are precisely what the user opens this screen to find out about, and neither is
     * "available offline".
     *
     * Ordered by hand where the user has said so — see [reorderDownloads] — and otherwise failures
     * first, then in progress, then waiting, then completed. A download the user has never placed
     * follows the ones they have, in that state ordering.
     */
    fun observeDownloads(): Flow<List<EpisodeWithShow>>

    /**
     * Stores a hand-made ordering for the downloads screen.
     *
     * The screen shows every tracked download at once, so the caller passes the whole list rather
     * than a pair of positions: the stored order is the arrangement, not a log of moves, which is
     * what keeps it right when a transfer finishes or fails mid-drag.
     *
     * @param episodeIds the downloads in the order they should appear, first row first.
     */
    suspend fun reorderDownloads(episodeIds: List<String>)

    /**
     * Total bytes the downloads occupy on disk.
     *
     * Read once rather than observed: it is a settings-screen figure, and watching a byte counter
     * would mean waking the UI on every write during a download.
     */
    suspend fun downloadedBytes(): Long

    /**
     * Bytes still free on the volume the downloads are written to.
     *
     * Read once, for the same reason as [downloadedBytes]. It exists so the downloads screen can
     * draw what is stored against what is left: "1.4 GB" means nothing on its own, and the whole
     * point of the figure is to answer "can I keep doing this".
     */
    suspend fun freeBytes(): Long

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
