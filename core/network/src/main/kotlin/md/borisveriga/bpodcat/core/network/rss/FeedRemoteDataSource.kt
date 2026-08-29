package md.borisveriga.bpodcat.core.network.rss

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.bpodcat.core.common.di.BPodcatDispatcher
import md.borisveriga.bpodcat.core.common.di.Dispatcher
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Outcome of a conditional feed fetch.
 */
sealed interface FeedFetchResult {

    /**
     * The feed was downloaded and parsed.
     *
     * @property channel the parsed feed.
     * @property etag `ETag` to send on the next fetch, if the server provided one.
     * @property lastModified `Last-Modified` to send on the next fetch, if provided.
     */
    data class Fetched(
        val channel: FeedChannel,
        val etag: String?,
        val lastModified: String?,
    ) : FeedFetchResult

    /**
     * The server answered `304`: the cached episode list is still current.
     *
     * This is the common case for the periodic refresh and costs a few hundred bytes.
     */
    data object NotModified : FeedFetchResult
}

/**
 * Downloads and parses show feeds.
 *
 * Which parser runs is decided by the caller rather than sniffed from the body: both callers already
 * know the answer — adding a show knows it from the classified link, refreshing one knows it from
 * the stored [PodcastSource] — so sniffing would mean buffering the stream to re-derive information
 * that was already in hand.
 *
 * @property api Retrofit client for arbitrary feed URLs.
 * @property rssParser the streaming RSS 2.0 parser.
 * @property youTubeParser the streaming parser for YouTube's playlist Atom feed.
 * @property ioDispatcher dispatcher for the blocking parse; the HTTP call itself already suspends.
 */
@Singleton
class FeedRemoteDataSource @Inject constructor(
    private val api: FeedApi,
    private val rssParser: RssParser,
    private val youTubeParser: YouTubeAtomParser,
    @Dispatcher(BPodcatDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Fetches [feedUrl], sending validators so unchanged feeds cost almost nothing.
     *
     * Note that YouTube's playlist endpoint returns neither `ETag` nor `Last-Modified` — it sends
     * `Cache-Control: max-age=900` instead — so a YouTube refresh always re-downloads and re-parses
     * its (at most fifteen) entries. That is cheap, and `upsertFromFeed` makes re-seeing a known
     * entry a no-op.
     *
     * @param feedUrl absolute feed URL.
     * @param etag `ETag` stored from the previous fetch.
     * @param lastModified `Last-Modified` stored from the previous fetch.
     * @param source which parser to run the body through.
     * @return [FeedFetchResult.NotModified] on a 304, otherwise the parsed feed.
     * @throws RssParseException if the body is not a usable feed.
     * @throws java.io.IOException on network failure or a non-2xx, non-304 status.
     */
    suspend fun fetch(
        feedUrl: String,
        etag: String? = null,
        lastModified: String? = null,
        source: PodcastSource = PodcastSource.RSS,
    ): FeedFetchResult = withContext(ioDispatcher) {
        val response = api.getFeed(url = feedUrl, etag = etag, lastModified = lastModified)

        if (response.code() == HTTP_NOT_MODIFIED) return@withContext FeedFetchResult.NotModified

        if (!response.isSuccessful) {
            throw java.io.IOException("Feed request failed with HTTP ${response.code()} for $feedUrl")
        }

        val body = response.body()
            ?: throw RssParseException("Feed response for $feedUrl had no body")

        val channel = body.byteStream().use { stream ->
            when (source) {
                PodcastSource.RSS -> rssParser.parse(stream)
                PodcastSource.YOUTUBE -> youTubeParser.parse(stream)
            }
        }
        FeedFetchResult.Fetched(
            channel = channel,
            etag = response.headers()["ETag"],
            lastModified = response.headers()["Last-Modified"],
        )
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
    }
}
