package md.borisveriga.megapodcastplayer.core.data.repository

import java.time.Duration
import kotlinx.coroutines.flow.Flow
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSearchResult
import md.borisveriga.megapodcastplayer.core.model.PodcastWithCounts

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
 * An episode a refresh has just discovered.
 *
 * Carries the show's name alongside the episode's so that a caller — the background refresh's
 * notification, in practice — can say "Podlodka Podcast: Episode 42" without going back to the
 * database for a join it would only need once.
 *
 * @property episodeId the new episode's local id.
 * @property episodeTitle the episode's title as the feed publishes it.
 * @property podcastId the show the episode belongs to, so a notification can open it.
 * @property podcastTitle the show's title.
 */
data class NewEpisode(
    val episodeId: String,
    val episodeTitle: String,
    val podcastId: String,
    val podcastTitle: String,
)

/**
 * Result of refreshing one or more feeds.
 *
 * Every show a run considered lands in exactly one of [refreshedCount], [notModifiedCount],
 * [skippedCount] or [failedTitles] — a tally that does not add up is a bug.
 *
 * @property refreshedCount feeds that were fetched and applied.
 * @property notModifiedCount feeds the server reported as unchanged (a 304).
 * @property skippedCount feeds not contacted at all because they were refreshed too recently to be
 *   worth another request; always zero when the caller passed no staleness window.
 * @property newEpisodes every episode discovered across all refreshed feeds, in the order the
 *   feeds were visited.
 * @property failedTitles shows whose refresh failed, by title, so one dead feed can be reported
 *   without failing the whole run.
 */
data class RefreshSummary(
    val refreshedCount: Int = 0,
    val notModifiedCount: Int = 0,
    val skippedCount: Int = 0,
    val newEpisodes: List<NewEpisode> = emptyList(),
    val failedTitles: List<String> = emptyList(),
) {
    /**
     * How many episodes the run discovered.
     *
     * Derived rather than stored: a count that can disagree with the list beside it is a bug
     * waiting to be written.
     */
    val newEpisodeCount: Int get() = newEpisodes.size
}

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

    /**
     * Observes one show's episodes.
     *
     * Newest first for an RSS show. A YouTube show is ordered by hand instead — see
     * [reorderEpisodes] — and the choice is made here rather than by the caller, because which
     * ordering a show has is a property of the show.
     */
    fun observeEpisodes(podcastId: String): Flow<List<Episode>>

    /**
     * Stores a hand-made ordering for the library.
     *
     * @param podcastIds every subscribed show, in the order they should appear.
     */
    suspend fun reorderLibrary(podcastIds: List<String>)

    /**
     * Stores a hand-made ordering for one show's episodes.
     *
     * Only meaningful for a YouTube show; an RSS show's screen orders by date and never reads it.
     *
     * @param podcastId the show being reordered.
     * @param episodeIds its episodes, in the order they should appear.
     */
    suspend fun reorderEpisodes(podcastId: String, episodeIds: List<String>)

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
     *
     * @param podcastId the show to re-fetch.
     * @param staleAfter skip the request when the feed was last fetched less than this ago; null
     *   always fetches. A show that has never been refreshed is always stale.
     * @return how many episodes were discovered; zero both when the feed is unchanged, when it
     *   changed without gaining episodes, and when the fetch was skipped as too recent — to the
     *   user those are all the same answer.
     */
    suspend fun refresh(podcastId: String, staleAfter: Duration? = null): Result<Int>

    /**
     * Re-fetches feeds and stores any new episodes.
     *
     * @param onlyAutoRefreshable true for the periodic background run and the automatic refresh the
     *   library performs on entry (both respect the per-show toggle), false for a user-initiated
     *   "refresh all".
     * @param staleAfter skip any feed fetched less than this ago, counted into
     *   [RefreshSummary.skippedCount]; null fetches every selected feed. This is what lets a screen
     *   refresh on every entry without turning a burst of tab switches into a burst of requests.
     */
    suspend fun refreshAll(
        onlyAutoRefreshable: Boolean,
        staleAfter: Duration? = null,
    ): RefreshSummary

    /**
     * Discards this show's stored episode list and imports the feed again from scratch.
     *
     * [refresh] merges a feed into what is already stored and goes out of its way to preserve
     * playback progress, played flags, download state and any hand-made order. This deliberately
     * loses all of it, because it is the answer to a list a merge cannot fix: a publisher who
     * re-issued their back catalogue under new GUIDs, a playlist whose stored order no longer
     * resembles the real one, or a feed that was half-read at import time. In every one of those a
     * refresh only ever adds to the wrong list.
     *
     * The feed is fetched *before* anything is deleted, and unconditionally — no `ETag`, no
     * `Last-Modified` — so a failed fetch leaves the show exactly as it was, and the server cannot
     * answer "unchanged" to a request whose entire point is to be told everything again.
     *
     * Never downloads audio, and does not remove any that is already on the device: the rows that
     * tracked those downloads are gone afterwards, so freeing the files is the caller's job.
     *
     * @param podcastId the show to rebuild.
     * @return how many episodes the feed yielded, or a failure carrying the fetch error.
     */
    suspend fun rebuild(podcastId: String): Result<Int>

    /** Removes a show and, by cascade, its episodes and queue entries. */
    suspend fun remove(podcastId: String)

    /** Clears the "new" badges for a show once its episode list has been opened. */
    suspend fun markEpisodesSeen(podcastId: String)

    /**
     * The newest episode of a show that has not been played yet.
     *
     * What "queue the next one from this show" resolves to, from a row that knows only the show.
     *
     * @param podcastId the show to look in.
     * @return the episode, or null when the show is finished or empty.
     */
    suspend fun newestUnplayedEpisode(podcastId: String): Episode?

    /**
     * Marks every unplayed episode of a show played.
     *
     * Returns what it changed rather than nothing, so the caller can offer an undo that puts back
     * exactly the episodes this touched — not "everything in the show", which would also unplay
     * episodes the user had finished long before.
     *
     * Playback positions are left alone; see `EpisodeDao.markPodcastPlayed`.
     *
     * @param podcastId the show to mark.
     * @return the ids that were unplayed beforehand.
     */
    suspend fun markPodcastPlayed(podcastId: String): List<String>

    /**
     * Sets the played flag on specific episodes, without touching their positions.
     *
     * The undo of [markPodcastPlayed].
     *
     * @param episodeIds the episodes to change; an empty list is a no-op.
     * @param isPlayed the flag to write.
     */
    suspend fun setEpisodesPlayed(episodeIds: List<String>, isPlayed: Boolean)

    /** Enables or disables background refresh for one show. */
    suspend fun setAutoRefresh(podcastId: String, enabled: Boolean)
}
