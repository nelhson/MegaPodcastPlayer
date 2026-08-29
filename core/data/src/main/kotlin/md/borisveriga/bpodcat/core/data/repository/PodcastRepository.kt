package md.borisveriga.bpodcat.core.data.repository

import kotlinx.coroutines.flow.Flow
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSearchResult
import md.borisveriga.bpodcat.core.model.PodcastWithCounts

/**
 * Outcome of trying to add a show to the library.
 *
 * Modelled as a closed set rather than a thrown exception because every branch has its own message
 * in the UI, and two of them ([AlreadyInLibrary], [NoFeedAvailable]) are not errors at all.
 */
sealed interface AddPodcastResult {

    /** The show was resolved, its feed parsed, and it plus its episodes were stored. */
    data class Added(val podcast: Podcast, val episodeCount: Int) : AddPodcastResult

    /** The feed URL is already in the library; the UI should navigate to it rather than complain. */
    data class AlreadyInLibrary(val podcast: Podcast) : AddPodcastResult

    /**
     * Apple knows the show but publishes no RSS feed for it — typically an Apple Podcasts exclusive.
     *
     * @property title the show's name, so the message can be specific.
     */
    data class NoFeedAvailable(val title: String) : AddPodcastResult

    /** The pasted text was none of an Apple link, an Apple id, a playlist link, or an http(s) URL. */
    data object InvalidInput : AddPodcastResult

    /**
     * The link is a YouTube URL, but not one that names a playlist we can follow.
     *
     * Its own result rather than a [Failed] because it is the most likely mistake a user makes here
     * — pasting a single video rather than the playlist — and it deserves an answer that says what
     * to do instead. It also covers channel pages and the playlists YouTube generates per viewer
     * (Watch Later, Liked, autoplay mixes), none of which have a durable feed.
     */
    data object NotAPlaylist : AddPodcastResult

    /** Apple returned no show for the given id. */
    data object NotFound : AddPodcastResult

    /**
     * The feed could not be fetched or parsed.
     *
     * @property cause the underlying network or parse failure, for logging.
     */
    data class Failed(val cause: Throwable) : AddPodcastResult
}

/**
 * Result of refreshing one or more feeds.
 *
 * @property refreshedCount feeds that were fetched and applied.
 * @property notModifiedCount feeds the server reported as unchanged (a 304).
 * @property newEpisodeCount episodes discovered across all refreshed feeds.
 * @property failedTitles shows whose refresh failed, by title, so one dead feed can be reported
 *   without failing the whole run.
 */
data class RefreshSummary(
    val refreshedCount: Int = 0,
    val notModifiedCount: Int = 0,
    val newEpisodeCount: Int = 0,
    val failedTitles: List<String> = emptyList(),
)

/**
 * The single entry point for everything the app knows about podcasts.
 *
 * Reads are offline-first: they come from Room and never touch the network, so the UI renders
 * instantly and works in airplane mode. Writes ([addFromInput], [refreshAll]) are the only paths
 * that reach out to Apple or a publisher.
 */
interface PodcastRepository {

    /** Observes the library with per-show episode counts. */
    fun observeLibrary(): Flow<List<PodcastWithCounts>>

    /** Observes a single show; emits null after it is removed. */
    fun observePodcast(podcastId: String): Flow<Podcast?>

    /** Observes one show's episodes, newest first. */
    fun observeEpisodes(podcastId: String): Flow<List<Episode>>

    /** Observes every episode available offline. */
    fun observeDownloadedEpisodes(): Flow<List<Episode>>

    /** Observes a single episode. */
    fun observeEpisode(episodeId: String): Flow<Episode?>

    /**
     * Searches Apple Podcasts.
     *
     * @param term the user's query; blank returns an empty list without a network call.
     * @return the matches, or a failure carrying the network error.
     */
    suspend fun search(term: String): Result<List<PodcastSearchResult>>

    /**
     * Adds a show from whatever the user pasted: an Apple Podcasts URL, a bare Apple id, or an RSS
     * feed URL.
     */
    suspend fun addFromInput(input: String): AddPodcastResult

    /** Adds a show the user picked from search results. */
    suspend fun addFromSearchResult(result: PodcastSearchResult): AddPodcastResult

    /**
     * Re-fetches one feed and stores any new episodes.
     *
     * Never downloads audio.
     */
    suspend fun refresh(podcastId: String): Result<Int>

    /**
     * Re-fetches feeds and stores any new episodes.
     *
     * @param onlyAutoRefreshable true for the periodic background run (respects the per-show
     *   toggle), false for a user-initiated "refresh all".
     */
    suspend fun refreshAll(onlyAutoRefreshable: Boolean): RefreshSummary

    /** Removes a show and, by cascade, its episodes and queue entries. */
    suspend fun remove(podcastId: String)

    /** Clears the "new" badges for a show once its episode list has been opened. */
    suspend fun markEpisodesSeen(podcastId: String)

    /** Enables or disables background refresh for one show. */
    suspend fun setAutoRefresh(podcastId: String, enabled: Boolean)
}
