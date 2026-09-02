package md.borisveriga.bpodcat.core.data.repository

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
import md.borisveriga.bpodcat.core.model.youTubePlaylistIdOrNull
import md.borisveriga.bpodcat.core.network.itunes.ItunesRemoteDataSource
import md.borisveriga.bpodcat.core.network.rss.FeedFetchResult
import md.borisveriga.bpodcat.core.network.rss.FeedRemoteDataSource
import md.borisveriga.bpodcat.core.youtube.YouTubePlaylistFetcher

/**
 * Room-backed, offline-first implementation of [PodcastRepository].
 *
 * @property podcastDao podcast rows.
 * @property episodeDao episode rows.
 * @property itunes Apple search/lookup.
 * @property feeds feed downloading and parsing, for RSS shows.
 * @property youTubePlaylists playlist reading, for YouTube shows. A separate collaborator rather
 *   than another parser behind [feeds] because a playlist is not fetched over HTTP at all — see
 *   [fetchFeed].
 * @property autoDownloadScheduler told about newly discovered episodes, so the download stack
 *   can act on them without this class knowing anything about downloads.
 * @property clock injected so refresh timestamps are deterministic in tests.
 * @property ioDispatcher dispatcher for the database and network work.
 */
// `flatMapLatest` picks the episode ordering from the show's source; still experimental, and
// stable enough that the alternative — duplicating the choice into every caller — is worse.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OfflineFirstPodcastRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val itunes: ItunesRemoteDataSource,
    private val feeds: FeedRemoteDataSource,
    private val youTubePlaylists: YouTubePlaylistFetcher,
    private val autoDownloadScheduler: AutoDownloadScheduler,
    private val clock: Clock,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : PodcastRepository {

    override fun observeLibrary(): Flow<List<PodcastWithCounts>> =
        podcastDao.observeAllWithCounts().map { rows -> rows.map { it.asExternalModel() } }

    override fun observePodcast(podcastId: String): Flow<Podcast?> =
        podcastDao.observeById(podcastId).map { it?.asExternalModel() }

    override fun observeEpisodes(podcastId: String): Flow<List<Episode>> =
        // Which ordering a show gets is a property of the show, so it is resolved here rather than
        // asked of every screen. `flatMapLatest` because the source can change under us: removing
        // and re-adding a feed as a different kind is a real sequence.
        podcastDao.observeById(podcastId)
            .map { it?.source == PodcastSource.YOUTUBE }
            .distinctUntilChanged()
            .flatMapLatest { handOrdered ->
                if (handOrdered) {
                    episodeDao.observeByPodcastOrdered(podcastId)
                } else {
                    episodeDao.observeByPodcast(podcastId)
                }
            }
            .map { rows -> rows.map { it.asExternalModel() } }

    override suspend fun reorderLibrary(podcastIds: List<String>) =
        withContext(ioDispatcher) { podcastDao.reorder(podcastIds) }

    override suspend fun reorderEpisodes(podcastId: String, episodeIds: List<String>) =
        withContext(ioDispatcher) { episodeDao.reorder(episodeIds) }

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
     * @param source where the show is read from, and what to record on the stored row.
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
        val fetched = suspendRunCatching {
            fetchFeed(feedUrl, etag = null, lastModified = null, source = source)
        }
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

        // Appended rather than slotted in alphabetically: the library is arranged by hand, so a
        // new show belongs where the user can find it — at the end — not somewhere in the middle
        // of an order they built.
        podcastDao.upsert(podcastEntity.copy(sortOrder = podcastDao.nextSortOrder()))
        val episodes = channel.channel.items.map { it.asEpisodeEntity(podcastEntity.id) }
        episodeDao.upsertFromFeed(episodes, handOrdered = source == PodcastSource.YOUTUBE)

        // Episodes present when the show is first added are not "new" — the user has seen none of
        // them, so badging all 500 would be noise.
        episodeDao.clearNewFlags(podcastEntity.id)

        AddPodcastResult.Added(
            podcast = podcastEntity.asExternalModel(),
            episodeCount = episodes.size,
        )
    }

    override suspend fun refresh(podcastId: String, staleAfter: Duration?): Result<Int> =
        withContext(ioDispatcher) {
            val podcast = podcastDao.getById(podcastId)
                ?: return@withContext Result.failure(
                    NoSuchElementException("Unknown podcast $podcastId"),
                )
            // A skipped fetch discovers nothing, which the caller reads exactly the way it reads a
            // feed that had no news — so it needs no result of its own.
            if (podcast.isFreshAt(Instant.now(clock), staleAfter)) {
                return@withContext Result.success(0)
            }
            suspendRunCatching { refreshOne(podcast).newEpisodes.size }
        }

    override suspend fun refreshAll(
        onlyAutoRefreshable: Boolean,
        staleAfter: Duration?,
    ): RefreshSummary =
        withContext(ioDispatcher) {
            val selected = if (onlyAutoRefreshable) {
                podcastDao.getAutoRefreshable()
            } else {
                podcastDao.getAll()
            }

            // Read once for the whole run rather than per feed: a slow library must not have its
            // later shows judged against a later "now" than its earlier ones.
            val now = Instant.now(clock)
            val (fresh, podcasts) = selected.partition { it.isFreshAt(now, staleAfter) }

            var refreshed = 0
            var notModified = 0
            val newEpisodes = mutableListOf<NewEpisode>()
            val failed = mutableListOf<String>()

            for (podcast in podcasts) {
                // One unreachable host must not abort the whole run — but a cancelled run must
                // stop here rather than work through every remaining feed. suspendRunCatching is
                // what keeps those two apart.
                suspendRunCatching { refreshOne(podcast) }
                    .onSuccess { outcome ->
                        if (outcome.notModified) notModified++ else refreshed++
                        newEpisodes += outcome.newEpisodes
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Refresh failed for ${podcast.feedUrl}", error)
                        failed += podcast.title
                    }
            }

            RefreshSummary(
                refreshedCount = refreshed,
                notModifiedCount = notModified,
                skippedCount = fresh.size,
                newEpisodes = newEpisodes,
                failedTitles = failed,
            )
        }

    /**
     * Reads a show, from wherever that show comes from.
     *
     * The single seam where the two sources meet, so that adding a show and refreshing one cannot
     * drift apart — which they previously could, because each spelled the fetch out for itself.
     *
     * A YouTube playlist is not fetched over HTTP here at all. Its stored `feedUrl` is an identity
     * rather than an address: the Atom feed it names returns only the first fifteen entries of a
     * playlist and cannot page, so it can neither import a longer playlist nor ever report a video
     * added past position fifteen. The extractor reads the whole playlist instead, which is why the
     * playlist id has to be recovered from the stored URL.
     *
     * It follows that a YouTube show never answers [FeedFetchResult.NotModified] — there is no
     * conditional GET to answer it with. In practice nothing changes: YouTube's feed endpoint sent
     * neither `ETag` nor `Last-Modified` either, so a YouTube refresh always re-read everything, and
     * `upsertFromFeed` makes re-seeing a known entry a no-op.
     *
     * @param feedUrl the show's stored feed URL.
     * @param etag `ETag` from the previous fetch; meaningless for YouTube.
     * @param lastModified `Last-Modified` from the previous fetch; meaningless for YouTube.
     * @param source where to read the show from.
     */
    private suspend fun fetchFeed(
        feedUrl: String,
        etag: String?,
        lastModified: String?,
        source: PodcastSource,
    ): FeedFetchResult = when (source) {
        PodcastSource.RSS -> feeds.fetch(feedUrl, etag, lastModified)

        PodcastSource.YOUTUBE -> {
            // Only reachable if a row were written with a YOUTUBE source and a URL that is not a
            // playlist feed URL, which nothing can do: youTubePlaylistFeedUrl mints every one of
            // them. Loud rather than silent, because the alternative is a show that stops
            // refreshing for no visible reason.
            val playlistId = requireNotNull(youTubePlaylistIdOrNull(feedUrl)) {
                "YouTube show stored with a feed URL that names no playlist: $feedUrl"
            }
            FeedFetchResult.Fetched(
                channel = youTubePlaylists.fetch(playlistId),
                etag = null,
                lastModified = null,
            )
        }
    }

    /**
     * Fetches one feed and applies it.
     *
     * @return what the fetch did: whether the server reported the feed unchanged, and which
     *   episodes were genuinely new.
     */
    private suspend fun refreshOne(podcast: PodcastEntity): RefreshOutcome {
        val result = fetchFeed(
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
                RefreshOutcome(notModified = true)
            }

            is FeedFetchResult.Fetched -> {
                val episodes = result.channel.items.map { it.asEpisodeEntity(podcast.id) }
                val newIds = episodeDao.upsertFromFeed(
                    episodes = episodes,
                    handOrdered = podcast.source == PodcastSource.YOUTUBE,
                )
                podcastDao.updateRefreshMetadata(
                    id = podcast.id,
                    refreshedAt = now,
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                // After the metadata write, so a failure to queue downloads cannot cost us the
                // etag and make the next refresh re-download the whole feed.
                autoDownloadScheduler.onEpisodesDiscovered(podcast.id, newIds)

                // The entities are already in hand, so the titles cost nothing here; re-reading
                // the rows would be a second query for data we have just written.
                val newIdSet = newIds.toSet()
                RefreshOutcome(
                    newEpisodes = episodes
                        .filter { it.id in newIdSet }
                        .map { episode ->
                            NewEpisode(
                                episodeId = episode.id,
                                episodeTitle = episode.title,
                                podcastId = podcast.id,
                                podcastTitle = podcast.title,
                            )
                        },
                )
            }
        }
    }

    /**
     * What one feed fetch did.
     *
     * @property notModified true when the server answered `304 Not Modified` and nothing was
     *   written.
     * @property newEpisodes the episodes this fetch discovered; empty both for a 304 and for a feed
     *   that changed without gaining episodes.
     */
    private data class RefreshOutcome(
        val notModified: Boolean = false,
        val newEpisodes: List<NewEpisode> = emptyList(),
    )

    override suspend fun rebuild(podcastId: String): Result<Int> = withContext(ioDispatcher) {
        val podcast = podcastDao.getById(podcastId)
            ?: return@withContext Result.failure(
                NoSuchElementException("Unknown podcast $podcastId"),
            )

        suspendRunCatching {
            // Fetched before anything is deleted, and with no validators: nothing is thrown away
            // until the replacement is in hand, so a dead network leaves the user the list they
            // already had rather than an empty show.
            val fetched = fetchFeed(
                feedUrl = podcast.feedUrl,
                etag = null,
                lastModified = null,
                source = podcast.source,
            )
            val channel = when (fetched) {
                is FeedFetchResult.Fetched -> fetched

                // A 304 is what a *conditional* GET earns, and we sent nothing to condition on.
                FeedFetchResult.NotModified -> throw IllegalStateException(
                    "Server answered 304 to a request that carried no validators",
                )
            }

            val episodes = channel.channel.items.map { it.asEpisodeEntity(podcastId) }
            episodeDao.replaceForPodcast(
                podcastId = podcastId,
                episodes = episodes,
                handOrdered = podcast.source == PodcastSource.YOUTUBE,
            )
            podcastDao.updateRefreshMetadata(
                id = podcastId,
                refreshedAt = Instant.now(clock).toEpochMilli(),
                etag = channel.etag,
                lastModified = channel.lastModified,
            )

            // No `autoDownloadScheduler` call, unlike `refreshOne`. Every episode here is
            // technically newly inserted, and handing the whole back catalogue to the download
            // stack is precisely what nobody asked for by rebuilding a list.
            episodes.size
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

    override suspend fun setPinned(podcastId: String, pinned: Boolean) = withContext(ioDispatcher) {
        podcastDao.setPinned(podcastId, pinned)
    }

    override suspend fun markAllPlayed(podcastId: String) = withContext(ioDispatcher) {
        episodeDao.markAllPlayed(podcastId)
    }

    override suspend fun hideEpisode(episodeId: String) = withContext(ioDispatcher) {
        episodeDao.hide(episodeId)
    }

    private companion object {
        private const val TAG = "PodcastRepository"
    }
}

/**
 * Whether this show was fetched recently enough to leave alone.
 *
 * A conditional GET is cheap but not free — it is still a round trip per show, and a YouTube
 * playlist has no validators to make it cheap at all — so a screen that refreshes on every entry
 * needs a floor under how often that turns into traffic.
 *
 * @param now the moment the run started.
 * @param staleAfter how old a fetch may be before it is worth repeating; null means the caller
 *   wants the feed fetched whatever its age.
 * @return true when the fetch should be skipped. Never true for a show that has no recorded fetch:
 *   it has nothing to be fresh from.
 */
private fun PodcastEntity.isFreshAt(now: Instant, staleAfter: Duration?): Boolean {
    if (staleAfter == null) return false
    val fetchedAt = lastRefreshAt?.let(Instant::ofEpochMilli) ?: return false
    // A clock that has gone backwards (a timezone-less device correcting itself, a restored backup)
    // yields a negative age; treating that as stale re-fetches once rather than wedging the show
    // until the stored timestamp is passed again.
    val age = Duration.between(fetchedAt, now)
    return !age.isNegative && age < staleAfter
}
