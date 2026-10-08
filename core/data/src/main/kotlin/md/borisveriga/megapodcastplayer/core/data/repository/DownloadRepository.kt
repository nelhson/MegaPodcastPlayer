package md.borisveriga.megapodcastplayer.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.DownloadDestination
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.SwipeDownload
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality

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
     * The download is filed under a folder before it is requested, so its row appears in the right
     * one while it is still waiting; see `DownloadFolders.folderForDownload` for which.
     *
     * @param episodeId the episode to download.
     * @param destination the folder to file it under; by default the one it is already in, and
     *   otherwise the user's default.
     * @return true if the request was made; false if the episode is not stored.
     */
    suspend fun download(
        episodeId: String,
        destination: DownloadDestination = DownloadDestination.Unspecified,
    ): Boolean

    /**
     * Starts a waiting download now, without waiting for Wi-Fi.
     *
     * What the downloads screen offers on a row that says *Waiting for Wi-Fi*. The user is standing
     * somewhere without it and wants this episode on the phone before they leave, which no other
     * control on that screen can express — the setting that put it there is app-wide and turning it
     * off is a trip to Settings and back.
     *
     * The lifted rule is app-wide too, because Media3 enforces one network requirement for the
     * whole download manager and has no per-download equivalent. Anything else already waiting will
     * therefore start as well, which is why the screen says so rather than implying the one row was
     * singled out. The stored preference is untouched and comes back into force as soon as nothing
     * is left downloading.
     *
     * @param episodeId the episode to fetch now.
     * @return true if the request was made; false if the episode is not stored.
     */
    suspend fun downloadNow(episodeId: String): Boolean

    /**
     * Removes an episode's downloaded audio, and its downloaded video if it has one, cancelling
     * either first if it is still in progress.
     *
     * @param episodeId the episode to remove.
     */
    suspend fun removeDownload(episodeId: String)

    /**
     * Observes every YouTube episode's downloaded video, keyed by episode id.
     *
     * A video is the picture of an episode, kept beside its audio so the episode can be watched
     * offline; the audio is still [Episode.downloadState]. An episode with no video download is
     * absent from the map.
     */
    fun observeVideoDownloads(): Flow<Map<String, VideoDownload>>

    /**
     * Requests a YouTube episode's video be downloaded at [quality].
     *
     * Downloads the audio too when it is not already on the device or on its way, because the
     * picture plays merged with the audio and a video with its sound still on the network does not
     * play offline. An episode keeps one quality: asking for another replaces the one it has.
     *
     * The episode is one download with its audio, so it is filed as [download] files it: a video
     * joining audio already on the device stays in the audio's folder unless told otherwise.
     *
     * @param episodeId the episode.
     * @param quality the rendition to keep, one of those the video offers.
     * @param destination the folder to file the episode under; see [download].
     * @return true if the request was made; false if the episode is not stored or is not a YouTube
     *   episode, which has no picture to download.
     */
    suspend fun downloadVideo(
        episodeId: String,
        quality: VideoQuality,
        destination: DownloadDestination = DownloadDestination.Unspecified,
    ): Boolean

    /**
     * Deletes an episode's downloaded video and keeps its downloaded audio.
     *
     * @param episodeId the episode.
     */
    suspend fun removeVideoDownload(episodeId: String)

    /**
     * Writes the finished downloads as a Markdown list, grouped by show.
     *
     * The text companion to the audio export: the show, its feed URL and a link for every episode.
     * See `downloadListMarkdown` in `:core:model` for the format.
     *
     * @param podcastId one show to list, or null for every show.
     * @return the document, or an empty string when nothing is downloaded.
     */
    suspend fun exportListMarkdown(podcastId: String? = null): String

    /** Removes every download and frees all the storage they occupy. */
    suspend fun removeAllDownloads()

    /** Enables or disables downloading episodes as a feed refresh discovers them. */
    suspend fun setAutoDownloadNewEpisodes(enabled: Boolean)

    /** Sets whether downloads wait for an unmetered network. */
    suspend fun setUnmeteredOnly(enabled: Boolean)

    /**
     * Sets how many newly discovered episodes auto-download fetches per show;
     * [DownloadSettings.KEEP_ALL] lifts the bound.
     */
    suspend fun setKeepLimitPerPodcast(limit: Int)

    /** Sets whether finishing an episode removes its downloaded audio. */
    suspend fun setDeleteAfterPlaying(enabled: Boolean)

    /** Sets what the download swipe on a show's episode list fetches. */
    suspend fun setSwipeDownload(choice: SwipeDownload)
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
