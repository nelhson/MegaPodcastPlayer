package md.borisveriga.bpodcat.core.data.repository

import android.util.Log
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import md.borisveriga.bpodcat.core.common.di.BPodcatDispatcher
import md.borisveriga.bpodcat.core.common.di.Dispatcher
import md.borisveriga.bpodcat.core.common.result.suspendRunCatching
import md.borisveriga.bpodcat.core.data.mapper.asEpisodeEntity
import md.borisveriga.bpodcat.core.data.mapper.asPodcastEntity
import md.borisveriga.bpodcat.core.database.dao.EpisodeDao
import md.borisveriga.bpodcat.core.database.dao.PodcastDao
import md.borisveriga.bpodcat.core.database.model.PodcastEntity
import md.borisveriga.bpodcat.core.database.model.asExternalModel
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastLink
import md.borisveriga.bpodcat.core.model.PodcastLinkParser
import md.borisveriga.bpodcat.core.model.PodcastSearchResult
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.PodcastWithCounts
import md.borisveriga.bpodcat.core.model.youTubePlaylistFeedUrl
import md.borisveriga.bpodcat.core.network.itunes.ItunesRemoteDataSource
import md.borisveriga.bpodcat.core.network.rss.FeedFetchResult
import md.borisveriga.bpodcat.core.network.rss.FeedRemoteDataSource

/**
 * Room-backed, offline-first implementation of [PodcastRepository].
 *
 * @property podcastDao podcast rows.
 * @property episodeDao episode rows.
 * @property itunes Apple search/lookup.
 * @property feeds feed fetching and parsing, for both RSS and YouTube playlists.
 * @property autoDownloadScheduler told about newly discovered episodes, so the download stack
 *   can act on them without this class knowing anything about downloads.
 * @property clock injected so refresh timestamps are deterministic in tests.
 * @property ioDispatcher dispatcher for the database and network work.
 */
@Singleton
class OfflineFirstPodcastRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val itunes: ItunesRemoteDataSource,
    private val feeds: FeedRemoteDataSource,
    private val autoDownloadScheduler: AutoDownloadScheduler,
    private val clock: Clock,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : PodcastRepository {

    override fun observeLibrary(): Flow<List<PodcastWithCounts>> =
        podcastDao.observeAllWithCounts().map { rows -> rows.map { it.asExternalModel() } }

    override fun observePodcast(podcastId: String): Flow<Podcast?> =
        podcastDao.observeById(podcastId).map { it?.asExternalModel() }

    override fun observeEpisodes(podcastId: String): Flow<List<Episode>> =
        episodeDao.observeByPodcast(podcastId).map { rows -> rows.map { it.asExternalModel() } }

    override fun observeDownloadedEpisodes(): Flow<List<Episode>> =
        episodeDao.observeDownloaded().map { rows -> rows.map { it.asExternalModel() } }

    override fun observeEpisode(episodeId: String): Flow<Episode?> =
        episodeDao.observeById(episodeId).map { it?.asExternalModel() }

    override suspend fun search(term: String): Result<List<PodcastSearchResult>> =
        withContext(ioDispatcher) {
            suspendRunCatching { itunes.search(term) }
        }

    override suspend fun addFromInput(input: String): AddPodcastResult = withContext(ioDispatcher) {
        when (val link = PodcastLinkParser.parse(input)) {
            // A YouTube URL the parser could not read is a YouTube URL that names no playlist we
            // can follow: a single video, a channel page, or a per-viewer list. Separating it from
            // ordinary unparseable input is what lets the UI say "open the playlist and copy its
            // link" instead of "that is not a valid link".
            null -> if (PodcastLinkParser.isYouTubeUrl(input.trim())) {
                AddPodcastResult.NotAPlaylist
            } else {
                AddPodcastResult.InvalidInput
            }

            is PodcastLink.Rss -> addFeed(
                feedUrl = link.feedUrl,
                itunesId = null,
                fallbackTitle = null,
                fallbackArtworkUrl = null,
            )

            is PodcastLink.YouTubePlaylist -> addFeed(
                // The Atom feed URL is the show's identity, so every spelling of this playlist the
                // parser accepts collapses onto one row here.
                feedUrl = youTubePlaylistFeedUrl(link.playlistId),
                itunesId = null,
                // No fallbacks and no Apple round trip: the playlist feed carries the title itself,
                // and the artwork is derived from the newest video.
                fallbackTitle = null,
                fallbackArtworkUrl = null,
                source = PodcastSource.YOUTUBE,
            )

            is PodcastLink.Apple -> {
                val show = itunes.lookup(link.itunesId)
                when {
                    show == null -> AddPodcastResult.NotFound
                    show.feedUrl.isNullOrBlank() -> AddPodcastResult.NoFeedAvailable(show.title)
                    else -> addFromSearchResult(show)
                }
            }
        }
    }

    override suspend fun addFromSearchResult(result: PodcastSearchResult): AddPodcastResult {
        val feedUrl = result.feedUrl
        if (feedUrl.isNullOrBlank()) return AddPodcastResult.NoFeedAvailable(result.title)
        return addFeed(
            feedUrl = feedUrl,
            itunesId = result.itunesId,
            fallbackTitle = result.title,
            fallbackArtworkUrl = result.artworkUrl,
        )
    }

    /**
     * Resolves, parses and stores a feed.
     *
     * Duplicate detection happens on the feed URL, so adding the same show once by search and once
     * by pasted link is a no-op the second time. For a YouTube playlist that URL is canonical, so
     * the several spellings of one playlist link collapse here too.
     *
     * @param source which parser to run the body through, and what to record on the stored show.
     */
    private suspend fun addFeed(
        feedUrl: String,
        itunesId: Long?,
        fallbackTitle: String?,
        fallbackArtworkUrl: String?,
        source: PodcastSource = PodcastSource.RSS,
    ): AddPodcastResult = withContext(ioDispatcher) {
        podcastDao.getByFeedUrl(feedUrl)?.let { existing ->
            return@withContext AddPodcastResult.AlreadyInLibrary(existing.asExternalModel())
        }

        // suspendRunCatching, not try/catch: a cancelled add must unwind rather than be reported
        // to the user as a failed one and then keep writing rows.
        val fetched = suspendRunCatching { feeds.fetch(feedUrl, source = source) }
            .getOrElse { error ->
                // The user only sees a short snackbar; keep the stack trace where it can be read.
                Log.w(TAG, "Failed to add feed $feedUrl", error)
                return@withContext AddPodcastResult.Failed(error)
            }

        // A brand-new feed can never answer 304, but the type forces us to be explicit.
        val channel = when (fetched) {
            is FeedFetchResult.Fetched -> fetched
            FeedFetchResult.NotModified ->
                return@withContext AddPodcastResult.Failed(
                    IllegalStateException("Server answered 304 for a feed we have never fetched"),
                )
        }

        val now = Instant.now(clock)
        val podcastEntity = channel.channel.asPodcastEntity(
            feedUrl = feedUrl,
            itunesId = itunesId,
            now = now,
            fallbackTitle = fallbackTitle,
            fallbackArtworkUrl = fallbackArtworkUrl,
            source = source,
        ).copy(etag = channel.etag, lastModified = channel.lastModified)

        podcastDao.upsert(podcastEntity)
        val episodes = channel.channel.items.map { it.asEpisodeEntity(podcastEntity.id) }
        episodeDao.upsertFromFeed(episodes)

        // Episodes present when the show is first added are not "new" — the user has seen none of
        // them, so badging all 500 would be noise.
        episodeDao.clearNewFlags(podcastEntity.id)

        AddPodcastResult.Added(
            podcast = podcastEntity.asExternalModel(),
            episodeCount = episodes.size,
        )
    }

    override suspend fun refresh(podcastId: String): Result<Int> = withContext(ioDispatcher) {
        val podcast = podcastDao.getById(podcastId)
            ?: return@withContext Result.failure(NoSuchElementException("Unknown podcast $podcastId"))
        suspendRunCatching { refreshOne(podcast) }
    }

    override suspend fun refreshAll(onlyAutoRefreshable: Boolean): RefreshSummary =
        withContext(ioDispatcher) {
            val podcasts = if (onlyAutoRefreshable) {
                podcastDao.getAutoRefreshable()
            } else {
                podcastDao.getAll()
            }

            var refreshed = 0
            var notModified = 0
            var newEpisodes = 0
            val failed = mutableListOf<String>()

            for (podcast in podcasts) {
                // One unreachable host must not abort the whole run — but a cancelled run must
                // stop here rather than work through every remaining feed. suspendRunCatching is
                // what keeps those two apart.
                suspendRunCatching { refreshOne(podcast) }
                    .onSuccess { discovered ->
                        if (discovered == NOT_MODIFIED) notModified++ else refreshed++
                        if (discovered > 0) newEpisodes += discovered
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Refresh failed for ${podcast.feedUrl}", error)
                        failed += podcast.title
                    }
            }

            RefreshSummary(
                refreshedCount = refreshed,
                notModifiedCount = notModified,
                newEpisodeCount = newEpisodes,
                failedTitles = failed,
            )
        }

    /**
     * Fetches one feed and applies it.
     *
     * @return the number of newly discovered episodes, or [NOT_MODIFIED] when the server reported
     *   the feed unchanged.
     */
    private suspend fun refreshOne(podcast: PodcastEntity): Int {
        val result = feeds.fetch(
            feedUrl = podcast.feedUrl,
            etag = podcast.etag,
            lastModified = podcast.lastModified,
            source = podcast.source,
        )

        val now = Instant.now(clock).toEpochMilli()

        return when (result) {
            FeedFetchResult.NotModified -> {
                podcastDao.updateRefreshMetadata(
                    id = podcast.id,
                    refreshedAt = now,
                    etag = podcast.etag,
                    lastModified = podcast.lastModified,
                )
                NOT_MODIFIED
            }

            is FeedFetchResult.Fetched -> {
                val episodes = result.channel.items.map { it.asEpisodeEntity(podcast.id) }
                val newIds = episodeDao.upsertFromFeed(episodes)
                podcastDao.updateRefreshMetadata(
                    id = podcast.id,
                    refreshedAt = now,
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                // After the metadata write, so a failure to queue downloads cannot cost us the
                // etag and make the next refresh re-download the whole feed.
                autoDownloadScheduler.onEpisodesDiscovered(podcast.id, newIds)
                newIds.size
            }
        }
    }

    override suspend fun remove(podcastId: String) = withContext(ioDispatcher) {
        podcastDao.deleteById(podcastId)
    }

    override suspend fun markEpisodesSeen(podcastId: String) = withContext(ioDispatcher) {
        episodeDao.clearNewFlags(podcastId)
    }

    override suspend fun setAutoRefresh(podcastId: String, enabled: Boolean) =
        withContext(ioDispatcher) {
            podcastDao.setAutoRefresh(podcastId, enabled)
        }

    private companion object {
        private const val TAG = "PodcastRepository"

        /** Sentinel returned by [refreshOne] when the server answered `304 Not Modified`. */
        const val NOT_MODIFIED = -1
    }
}
