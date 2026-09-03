package md.borisveriga.megapodcastplayer.core.network.rss

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher

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
 * Downloads and parses RSS show feeds.
 *
 * YouTube playlists used to come through here too, as a second parser over a second URL shape. They
 * no longer do: the Atom feed that endpoint serves is capped at fifteen entries with no pagination,
 * so it could not import a longer playlist and could never notice a video added past position
 * fifteen. Playlists are read with the extractor instead —
 * `md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher` — and the choice between the two is
 * made one level up, in the repository, since `:core:network` cannot depend on `:core:youtube`.
 *
 * @property api Retrofit client for arbitrary feed URLs.
 * @property rssParser the streaming RSS 2.0 parser.
 * @property ioDispatcher dispatcher for the blocking parse; the HTTP call itself already suspends.
 */
@Singleton
class FeedRemoteDataSource @Inject constructor(
    private val api: FeedApi,
    private val rssParser: RssParser,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Fetches [feedUrl], sending validators so unchanged feeds cost almost nothing.
     *
     * @param feedUrl absolute feed URL.
     * @param etag `ETag` stored from the previous fetch.
     * @param lastModified `Last-Modified` stored from the previous fetch.
     * @return [FeedFetchResult.NotModified] on a 304, otherwise the parsed feed.
     * @throws RssParseException if the body is not a usable feed.
     * @throws java.io.IOException on network failure or a non-2xx, non-304 status.
     */
    suspend fun fetch(
        feedUrl: String,
        etag: String? = null,
        lastModified: String? = null,
    ): FeedFetchResult = withContext(ioDispatcher) {
        val response = api.getFeed(url = feedUrl, etag = etag, lastModified = lastModified)

        if (response.code() == HTTP_NOT_MODIFIED) return@withContext FeedFetchResult.NotModified

        if (!response.isSuccessful) {
            throw java.io.IOException("Feed request failed with HTTP ${response.code()} for $feedUrl")
        }

        val body = response.body()
            ?: throw RssParseException("Feed response for $feedUrl had no body")

        val channel = body.byteStream().use { stream -> rssParser.parse(stream) }
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
